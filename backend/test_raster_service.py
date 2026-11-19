import base64
import hashlib
import hmac
import json
import math
import os
import shutil
import struct
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from app import (
    AUTHORITATIVE_FIREBASE_PROJECT_ID,
     ApiHttpError,
    DEFAULT_TRUSTED_RSA_KEYPAIR,
    FirebaseAdminAuthenticator,
    SatelliteBackendService,
    UNTRUSTED_ATTACKER_RSA_KEYPAIR,
    create_http_server,
    create_signed_test_firebase_token,
    verify_firebase_bearer_token,
)
from raster_service import (
    GeoTiffCogProcessor,
    GeoTransform,
    InvalidAssetUrlError,
    MissingBandError,
    RangeRequestNotSupportedError,
    RasterBandData,
    RasterDownloadInterruptedError,
    RasterIntegrityError,
    RemoteCogHttpClient,
    UnsupportedCrsError,
    aggregate_field_raster,
    align_band_to_target_grid,
    analyze_temporal_observations,
    coordinate_to_wgs84,
    dn_to_reflectance,
    evaluate_pixel,
    get_crop_thresholds,
    is_scl_masked,
    point_in_geojson_polygon,
    process_sentinel2_raster_bands,
    safe_normalized_difference,
    safe_psri,
    utm_to_wgs84,
    validate_supported_crs,
    wgs84_to_utm,
)



class TestSentinel2OperationalRasterPipeline(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="agrosight_test_rasters_")

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_dn_to_reflectance_and_boa_offset(self):
        # Standard pre-04.00 scaling (offset=0)
        self.assertAlmostEqual(dn_to_reflectance(5000, scale_factor=0.0001, boa_add_offset=0.0), 0.50, places=4)
        # Processing Baseline 04.00+ BOA offset (-1000 DN): (6000 - 1000) * 0.0001 = 0.50
        self.assertAlmostEqual(dn_to_reflectance(6000, scale_factor=0.0001, boa_add_offset=-1000.0), 0.50, places=4)
        # Negative reflectance after offset is rejected as invalid/NoData
        self.assertTrue(math.isnan(dn_to_reflectance(800, scale_factor=0.0001, boa_add_offset=-1000.0)))
        # DN=0 (NoData) and DN=65535 (Saturated) return NaN
        self.assertTrue(math.isnan(dn_to_reflectance(0)))
        self.assertTrue(math.isnan(dn_to_reflectance(65535)))

    def test_crs_utm_and_wgs84_round_trip(self):
        # Wadi El Natrun / Nile Delta West coordinates in UTM Zone 36N (EPSG:32636)
        lon_orig, lat_orig = 30.3500, 30.3833
        easting, northing = wgs84_to_utm(lon_orig, lat_orig, utm_zone=36)
        self.assertTrue(200000.0 < easting < 350000.0)
        self.assertTrue(3300000.0 < northing < 3450000.0)

        lon_back, lat_back = utm_to_wgs84(easting, northing, utm_zone=36)
        self.assertAlmostEqual(lon_orig, lon_back, places=5)
        self.assertAlmostEqual(lat_orig, lat_back, places=5)

    def test_real_geotiff_file_write_and_read_preserves_crs_transform_and_values(self):
        transform = GeoTransform(
            origin_x=245000.0,
            pixel_width=10.0,
            row_rotation=0.0,
            origin_y=3365000.0,
            col_rotation=0.0,
            pixel_height=-10.0,
        )
        values = [
            [4200.0, 4300.0, 4100.0, 0.0],
            [4250.0, 2100.0, 4150.0, 4050.0],
            [4300.0, 4400.0, 4200.0, 4100.0],
        ]
        band = RasterBandData(
            band_name="B08",
            width=4,
            height=3,
            crs_epsg=32636,
            transform=transform,
            values=values,
            nodata_value=0.0,
            spatial_resolution_m=10.0,
        )
        tif_path = os.path.join(self.temp_dir, "test_B08.tif")
        GeoTiffCogProcessor.write_geotiff(tif_path, band, dtype="uint16")
        self.assertTrue(os.path.exists(tif_path))

        loaded = GeoTiffCogProcessor.read_geotiff(tif_path, band_name="B08")
        self.assertEqual(loaded.width, 4)
        self.assertEqual(loaded.height, 3)
        self.assertEqual(loaded.crs_epsg, 32636)
        self.assertAlmostEqual(loaded.transform.origin_x, 245000.0, places=2)
        self.assertAlmostEqual(loaded.transform.origin_y, 3365000.0, places=2)
        self.assertAlmostEqual(loaded.transform.pixel_width, 10.0, places=3)
        self.assertAlmostEqual(loaded.transform.pixel_height, -10.0, places=3)
        self.assertAlmostEqual(loaded.values[1][1], 2100.0, places=1)
        self.assertAlmostEqual(loaded.values[0][3], 0.0, places=1)

    def test_multi_resolution_20m_to_10m_band_alignment(self):
        # 10m target grid: 4x4 covering 40m x 40m
        t_10m = GeoTransform(500000.0, 10.0, 0.0, 3300000.0, 0.0, -10.0)
        # 20m source grid: 2x2 covering the exact same 40m x 40m extent
        t_20m = GeoTransform(500000.0, 20.0, 0.0, 3300000.0, 0.0, -20.0)

        # Categorical SCL 20m band (top-left=4 Vegetation, top-right=9 High Cloud, bottom-left=4, bottom-right=3 Shadow)
        scl_20m = RasterBandData(
            band_name="SCL",
            width=2,
            height=2,
            crs_epsg=32636,
            transform=t_20m,
            values=[[4.0, 9.0], [4.0, 3.0]],
            nodata_value=0.0,
            spatial_resolution_m=20.0,
        )
        scl_10m = align_band_to_target_grid(scl_20m, 4, 4, t_10m, is_categorical=True)
        self.assertEqual(scl_10m.width, 4)
        self.assertEqual(scl_10m.height, 4)
        # Top-left 2x2 sub-block in 10m must all be class 4; top-right 2x2 must all be class 9 (never averaged to 6.5!)
        self.assertEqual(int(scl_10m.values[0][0]), 4)
        self.assertEqual(int(scl_10m.values[0][1]), 4)
        self.assertEqual(int(scl_10m.values[0][2]), 9)
        self.assertEqual(int(scl_10m.values[0][3]), 9)
        self.assertEqual(int(scl_10m.values[3][3]), 3)

        # Continuous B05 Red-Edge 20m band resampled bilinearly to 10m
        b05_20m = RasterBandData(
            band_name="B05",
            width=2,
            height=2,
            crs_epsg=32636,
            transform=t_20m,
            values=[[1000.0, 2000.0], [1000.0, 2000.0]],
            nodata_value=0.0,
            spatial_resolution_m=20.0,
        )
        b05_10m = align_band_to_target_grid(b05_20m, 4, 4, t_10m, is_categorical=False)
        self.assertAlmostEqual(b05_10m.values[0][0], 1000.0, places=1)
        self.assertTrue(1000.0 < b05_10m.values[0][1] < 2000.0)
        self.assertAlmostEqual(b05_10m.values[0][3], 2000.0, places=1)

    def test_end_to_end_multiband_geotiff_clipping_and_connected_anomaly_geojson(self):
        """
        End-to-end test writing real 10m (6x6) and 20m (3x3) GeoTIFF band files in UTM EPSG:32636,
        clipping against an actual farm GeoJSON polygon, masking clouds via SCL, computing
        NDVI/NDRE/NDMI/PSRI, writing georeferenced output GeoTIFFs, and vectorizing connected
        anomalous pixels into accurate GeoJSON polygons (never a fixed square around center).
        """
        origin_e, origin_n = wgs84_to_utm(30.3500, 30.3833, utm_zone=36)
        t_10m = GeoTransform(origin_e, 10.0, 0.0, origin_n, 0.0, -10.0)
        t_20m = GeoTransform(origin_e, 20.0, 0.0, origin_n, 0.0, -20.0)

        # Farm polygon covers rows 0..4, cols 0..4 (50m x 50m), excluding row 5 and col 5
        nw_lon, nw_lat = coordinate_to_wgs84(origin_e + 1.0, origin_n - 1.0, 32636)
        ne_lon, ne_lat = coordinate_to_wgs84(origin_e + 49.0, origin_n - 1.0, 32636)
        se_lon, se_lat = coordinate_to_wgs84(origin_e + 49.0, origin_n - 49.0, 32636)
        sw_lon, sw_lat = coordinate_to_wgs84(origin_e + 1.0, origin_n - 49.0, 32636)

        farm_polygon = {
            "type": "Polygon",
            "coordinates": [[
                [nw_lon, nw_lat],
                [ne_lon, ne_lat],
                [se_lon, se_lat],
                [sw_lon, sw_lat],
                [nw_lon, nw_lat],
            ]],
        }

        # Healthy pixel DNs (with BOA offset = -1000 -> +1000 added to raw DN):
        # B02=1400 (0.04), B04=1600 (0.06), B05=2200 (0.12), B06=3800 (0.28),
        # B08=5200 (0.42), B8A=5000 (0.40), B11=2800 (0.18), SCL=4 (Vegetation)
        b02_grid = [[1400.0 for _ in range(6)] for _ in range(6)]
        b04_grid = [[1600.0 for _ in range(6)] for _ in range(6)]
        b08_grid = [[5200.0 for _ in range(6)] for _ in range(6)]

        # Inject a 2-pixel connected stress hotspot at (row=2, col=2) and (row=2, col=3):
        # High Red B04=2800 (0.18), Low NIR B08=3200 (0.22) -> NDVI = (0.22-0.18)/0.40 = 0.10
        for hc in (2, 3):
            b04_grid[2][hc] = 2800.0
            b08_grid[2][hc] = 3200.0

        b05_20m_grid = [[2200.0 for _ in range(3)] for _ in range(3)]
        b06_20m_grid = [[3800.0 for _ in range(3)] for _ in range(3)]
        b8a_20m_grid = [[5000.0 for _ in range(3)] for _ in range(3)]
        b11_20m_grid = [[2800.0 for _ in range(3)] for _ in range(3)]
        scl_20m_grid = [[4.0 for _ in range(3)] for _ in range(3)]
        # Make top-right 20m cell (row 0, col 2 -> 10m rows 0..1, cols 4..5) cloud-masked (SCL=9)
        scl_20m_grid[0][2] = 9.0

        band_files = {}
        for name, grid in (("B02", b02_grid), ("B04", b04_grid), ("B08", b08_grid)):
            p = os.path.join(self.temp_dir, f"{name}.tif")
            GeoTiffCogProcessor.write_geotiff(
                p,
                RasterBandData(name, 6, 6, 32636, t_10m, grid, nodata_value=0.0, spatial_resolution_m=10.0),
                dtype="uint16",
            )
            band_files[name] = p

        for name, grid in (
            ("B05", b05_20m_grid),
            ("B06", b06_20m_grid),
            ("B8A", b8a_20m_grid),
            ("B11", b11_20m_grid),
            ("SCL", scl_20m_grid),
        ):
            p = os.path.join(self.temp_dir, f"{name}.tif")
            GeoTiffCogProcessor.write_geotiff(
                p,
                RasterBandData(name, 3, 3, 32636, t_20m, grid, nodata_value=0.0, spatial_resolution_m=20.0),
                dtype="uint16",
            )
            band_files[name] = p

        out_dir = os.path.join(self.temp_dir, "outputs")
        stats, evals = process_sentinel2_raster_bands(
            band_sources=band_files,
            farm_polygon_geojson=farm_polygon,
            crop_id="tomato",
            growth_stage_index=2,
            scale_factor=0.0001,
            boa_add_offset=-1000.0,
            output_dir=out_dir,
        )

        # Total grid is 36 pixels; polygon clips to 5x5 = 25 pixels;
        # Inside the 5x5 polygon, rows 0..1 col 4 (2 pixels) have SCL=9 (cloud), leaving 23 valid pixels
        self.assertEqual(stats.total_pixel_count, 36)
        self.assertEqual(stats.polygon_pixel_count, 25)
        self.assertEqual(stats.valid_pixel_count, 23)
        self.assertEqual(stats.masked_cloud_or_nodata_count, 2)
        self.assertEqual(stats.anomaly_pixel_count, 2)
        self.assertAlmostEqual(stats.anomaly_area_m2, 200.0, places=1)

        # Verify output georeferenced GeoTIFFs exist and can be read back
        for idx_name in ("NDVI", "NDRE", "NDMI", "PSRI"):
            self.assertIn(idx_name, stats.output_raster_files)
            out_path = stats.output_raster_files[idx_name]
            self.assertTrue(os.path.exists(out_path))
            out_band = GeoTiffCogProcessor.read_geotiff(out_path, band_name=idx_name)
            self.assertEqual(out_band.crs_epsg, 32636)
            self.assertEqual(out_band.width, 6)
            self.assertEqual(out_band.height, 6)

        ndvi_out = GeoTiffCogProcessor.read_geotiff(stats.output_raster_files["NDVI"], band_name="NDVI")
        # Healthy pixel (0,0) has NDVI = 0.75
        self.assertAlmostEqual(ndvi_out.values[0][0], 0.75, places=3)
        # Anomalous pixel (2,2) has NDVI = (0.22 - 0.18) / (0.22 + 0.18) = 0.10
        self.assertAlmostEqual(ndvi_out.values[2][2], 0.10, places=3)
        # Outside polygon pixel (5,5) is NaN
        self.assertTrue(math.isnan(ndvi_out.values[5][5]))
        # Cloud-masked pixel (0,4) is NaN
        self.assertTrue(math.isnan(ndvi_out.values[0][4]))

        # Verify connected-component GeoJSON anomaly polygon matches the actual 2-pixel cluster
        geojson_obj = json.loads(stats.anomaly_zones_geojson)
        self.assertEqual(geojson_obj["type"], "FeatureCollection")
        self.assertEqual(len(geojson_obj["features"]), 1)
        feat = geojson_obj["features"][0]
        self.assertEqual(feat["properties"]["pixel_count"], 2)
        self.assertAlmostEqual(feat["properties"]["area_m2"], 200.0, places=1)
        self.assertAlmostEqual(feat["properties"]["ndvi_mean"], 0.10, places=3)

    def test_healthy_field_produces_empty_anomaly_geojson_never_fake_center_box(self):
        healthy_px = evaluate_pixel(0.04, 0.06, 0.12, 0.28, 0.42, 0.40, 0.18, scl_class=4, row=0, col=0)
        stats = aggregate_field_raster(pixels=[healthy_px], center_lat=30.3833, center_lon=30.3500)
        self.assertEqual(stats.anomaly_pixel_count, 0)
        parsed = json.loads(stats.anomaly_zones_geojson)
        self.assertEqual(parsed["features"], [])

    def test_crop_specific_thresholds_differ_between_potato_and_date_palm(self):
        potato_t = get_crop_thresholds("potato", growth_stage_index=2)
        palm_t = get_crop_thresholds("date_palm", growth_stage_index=2)
        self.assertGreater(potato_t.stressed_ndvi_threshold, palm_t.stressed_ndvi_threshold)

        # A sparse desert date palm canopy with NDVI=0.38, NDRE=0.20, NDMI=0.15, PSRI=0.08 is healthy for date palm
        # but anomalous for mid-season dense potato canopy
        px_palm = evaluate_pixel(0.05, 0.13, 0.20, 0.26, 0.29, 0.30, 0.21, scl_class=4, thresholds=palm_t)
        px_potato = evaluate_pixel(0.05, 0.13, 0.20, 0.26, 0.29, 0.30, 0.21, scl_class=4, thresholds=potato_t)
        self.assertFalse(px_palm.is_anomalous)
        self.assertTrue(px_potato.is_anomalous)

    def test_temporal_analysis_sorts_by_date_and_filters_cloud_and_valid_pixel_ratio(self):
        # Pass scenes out of chronological order
        scenes = [
            {
                "scene_id": "S2A_OLD_CLEAR",
                "acquisition_date_iso": "2026-04-20",
                "cloud_cover_pct": 2.5,
                "valid_pixel_ratio": 0.95,
                "ndvi_mean": 0.72,
                "ndre_mean": 0.38,
                "ndmi_mean": 0.34,
            },
            {
                "scene_id": "S2A_MID_CLOUDY",
                "acquisition_date_iso": "2026-04-25",
                "cloud_cover_pct": 65.0,
                "valid_pixel_ratio": 0.30,
                "ndvi_mean": 0.12,
                "ndre_mean": 0.08,
                "ndmi_mean": 0.05,
            },
            {
                "scene_id": "S2B_NEW_CLEAR",
                "acquisition_date_iso": "2026-05-01",
                "cloud_cover_pct": 4.0,
                "valid_pixel_ratio": 0.92,
                "ndvi_mean": 0.61,
                "ndre_mean": 0.30,
                "ndmi_mean": 0.28,
            },
        ]
        result = analyze_temporal_observations(scenes)
        self.assertEqual(result["usable_clear_scene_count"], 2)
        self.assertEqual(result["cloud_gapped_scene_count"], 1)
        self.assertEqual(result["ordered_clear_scene_ids"], ["S2B_NEW_CLEAR", "S2A_OLD_CLEAR"])
        self.assertAlmostEqual(result["ndvi_delta_from_previous_clear_pass"], -0.11, places=4)
        self.assertTrue(result["is_sudden_chlorophyll_drop_detected"])

    def test_backend_http_api_authentication_ownership_and_processing_endpoints(self):
        fake_stac_scenes = {
            "http_status": 200,
            "scene_count": 1,
            "scenes": [
                {
                    "scene_id": "S2B_36RUV_20260501_0_L2A",
                    "acquisition_date_iso": "2026-05-01",
                    "datetime": "2026-05-01T08:42:11Z",
                    "cloud_cover_pct": 1.8,
                    "mgrs_tile": "36RUV",
                    "processing_baseline": "04.00",
                    "crs_epsg": 32636,
                    "scale_factor": 0.0001,
                    "boa_add_offset": 0.0,
                    "has_all_required_bands": True,
                    "band_assets": {},
                }
            ],
        }
        service = SatelliteBackendService(
            storage_root=os.path.join(self.temp_dir, "api_storage"),
            stac_search_fn=lambda **kwargs: fake_stac_scenes,
        )
        server, _ = create_http_server(service=service, host="127.0.0.1", port=0)
        port = server.server_address[1]
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()

        try:
            base_url = f"http://127.0.0.1:{port}"
            alice_token = create_signed_test_firebase_token(uid="farmer_alice", email="alice@example.com")
            bob_token = create_signed_test_firebase_token(uid="farmer_bob", email="bob@example.com")
            expired_token = create_signed_test_firebase_token(
                uid="farmer_alice",
                email="alice@example.com",
                exp_epoch_sec=int(time.time()) - 60,
            )

            def _http_call(method: str, path: str, token: str = "", payload: dict = None):
                headers = {"Content-Type": "application/json"}
                if token:
                    headers["Authorization"] = f"Bearer {token}"
                data = json.dumps(payload).encode("utf-8") if payload is not None else None
                req = urllib.request.Request(f"{base_url}{path}", data=data, headers=headers, method=method)
                try:
                    with urllib.request.urlopen(req, timeout=10) as resp:
                        return resp.getcode(), json.loads(resp.read().decode("utf-8"))
                except urllib.error.HTTPError as e:
                    return e.code, json.loads(e.read().decode("utf-8"))

            # 1. GET /api/v1/health
            code, health = _http_call("GET", "/api/v1/health")
            self.assertEqual(code, 200)
            self.assertEqual(health["status"], "HEALTHY")

            # 2. Unauthenticated or expired token -> 401
            farm_poly = {
                "type": "Polygon",
                "coordinates": [[
                    [30.3500, 30.3800],
                    [30.3510, 30.3800],
                    [30.3510, 30.3810],
                    [30.3500, 30.3810],
                    [30.3500, 30.3800],
                ]],
            }
            code_unauth, _ = _http_call("POST", "/api/v1/satellite/scenes", token="", payload={
                "farm_id": "farm_alice_1",
                "farm_polygon_geojson": farm_poly,
            })
            self.assertEqual(code_unauth, 401)

            code_exp, _ = _http_call("POST", "/api/v1/satellite/scenes", token=expired_token, payload={
                "farm_id": "farm_alice_1",
                "farm_polygon_geojson": farm_poly,
            })
            self.assertEqual(code_exp, 401)

            # 3. Authenticated scene discovery for Alice's farm -> 200
            code_scenes, scenes_resp = _http_call("POST", "/api/v1/satellite/scenes", token=alice_token, payload={
                "farm_id": "farm_alice_1",
                "owner_uid": "farmer_alice",
                "farm_polygon_geojson": farm_poly,
                "max_cloud_cover": 25.0,
            })
            self.assertEqual(code_scenes, 200)
            self.assertEqual(scenes_resp["scene_count"], 1)

            # 4. Prepare real GeoTIFF band files for POST /api/v1/satellite/process
            t_deg = GeoTransform(30.3500, 0.00025, 0.0, 30.3810, 0.0, -0.00025)
            band_paths = {}
            band_dn_defaults = {
                "B02": 400.0,
                "B04": 600.0,
                "B05": 1200.0,
                "B06": 2800.0,
                "B08": 4200.0,
                "B8A": 4000.0,
                "B11": 1800.0,
                "SCL": 4.0,
            }
            for b_name, d_val in band_dn_defaults.items():
                grid = [[d_val for _ in range(4)] for _ in range(4)]
                if b_name == "B04":
                    grid[1][1] = 1900.0
                if b_name == "B08":
                    grid[1][1] = 2300.0
                p = os.path.join(self.temp_dir, f"api_{b_name}.tif")
                GeoTiffCogProcessor.write_geotiff(
                    p,
                    RasterBandData(b_name, 4, 4, 4326, t_deg, grid, nodata_value=0.0, spatial_resolution_m=10.0),
                    dtype="uint16",
                )
                band_paths[b_name] = p

            # 5. Unauthorized farm ownership attempt: Bob cannot process Alice's farm -> 403
            code_bob_forbid, _ = _http_call("POST", "/api/v1/satellite/process", token=bob_token, payload={
                "farm_id": "farm_alice_1",
                "owner_uid": "farmer_alice",
                "crop_id": "tomato",
                "scene_id": "S2B_36RUV_20260501_0_L2A",
                "farm_polygon_geojson": farm_poly,
                "band_assets": band_paths,
            })
            self.assertEqual(code_bob_forbid, 403)

            # 6. Authorized raster processing by Alice -> 200
            code_proc, job_resp = _http_call("POST", "/api/v1/satellite/process", token=alice_token, payload={
                "farm_id": "farm_alice_1",
                "owner_uid": "farmer_alice",
                "crop_id": "tomato",
                "scene_id": "S2B_36RUV_20260501_0_L2A",
                "farm_polygon_geojson": farm_poly,
                "band_assets": band_paths,
            })
            self.assertEqual(code_proc, 200)
            self.assertEqual(job_resp["status"], "COMPLETED")
            job_id = job_resp["job_id"]
            result_id = job_resp["result_id"]

            # 7. GET /api/v1/satellite/jobs/{job_id} (Alice -> 200, Bob -> 403)
            code_job_alice, job_status = _http_call("GET", f"/api/v1/satellite/jobs/{job_id}", token=alice_token)
            self.assertEqual(code_job_alice, 200)
            self.assertEqual(job_status["progress_pct"], 100)

            code_job_bob, _ = _http_call("GET", f"/api/v1/satellite/jobs/{job_id}", token=bob_token)
            self.assertEqual(code_job_bob, 403)

            # 8. GET /api/v1/satellite/results/{result_id} (Alice -> 200, Bob -> 403)
            code_res_bob, _ = _http_call("GET", f"/api/v1/satellite/results/{result_id}", token=bob_token)
            self.assertEqual(code_res_bob, 403)

            code_res_alice, res_data = _http_call("GET", f"/api/v1/satellite/results/{result_id}", token=alice_token)
            self.assertEqual(code_res_alice, 200)
            self.assertEqual(res_data["data_mode"], "LIVE_GEOREFERENCED_RASTER")
            self.assertGreater(res_data["statistics"]["valid_pixel_count"], 0)
            self.assertGreaterEqual(res_data["statistics"]["anomaly_pixel_count"], 1)
            self.assertEqual(len(res_data["anomaly_zones_geojson"]["features"]), 1)
            self.assertTrue(os.path.exists(res_data["output_geotiff_paths"]["NDVI"]))
        finally:
            server.shutdown()
            server.server_close()

    def test_firebase_rs256_authentication_and_token_rejection_suite(self):
        """
        PRIORITY 1 Security Acceptance Suite:
        1. Valid Firebase RS256 token is accepted.
        2. Invalid signature (tampered payload/signature) is rejected.
        3. Forged RS256 token signed with attacker's 2048-bit RSA key is rejected.
        4. Forged HS256 token is rejected.
        5. Expired token is rejected.
        6. Token from another Firebase project is rejected.
        7. Token with incorrect audience is rejected.
        8. Token with invalid issuer is rejected.
        9. Revoked token is rejected.
        """
        authenticator = FirebaseAdminAuthenticator(
            project_id=AUTHORITATIVE_FIREBASE_PROJECT_ID,
            trusted_public_keys={DEFAULT_TRUSTED_RSA_KEYPAIR.kid: DEFAULT_TRUSTED_RSA_KEYPAIR},
            use_firebase_admin_sdk=False,
        )

        # 1. Valid Firebase RS256 token is accepted
        valid_token = create_signed_test_firebase_token(
            uid="farmer_verified_01",
            email="farmer01@agrosight.eg",
            email_verified=True,
        )
        claims = authenticator.verify_bearer_header(f"Bearer {valid_token}")
        self.assertEqual(claims["uid"], "farmer_verified_01")
        self.assertEqual(claims["role"], "FARMER")
        self.assertEqual(claims["project_id"], AUTHORITATIVE_FIREBASE_PROJECT_ID)

        # 2. Invalid signature (tampered payload with original RS256 signature) is rejected
        parts = valid_token.split(".")
        tampered_payload = json.dumps({
            "iss": f"https://securetoken.google.com/{AUTHORITATIVE_FIREBASE_PROJECT_ID}",
            "aud": AUTHORITATIVE_FIREBASE_PROJECT_ID,
            "sub": "impersonated_admin_uid",
            "user_id": "impersonated_admin_uid",
            "iat": int(time.time()),
            "exp": int(time.time()) + 3600,
        }).encode("utf-8")
        tampered_p_enc = base64.urlsafe_b64encode(tampered_payload).decode("ascii").rstrip("=")
        tampered_token = f"{parts[0]}.{tampered_p_enc}.{parts[2]}"
        with self.assertRaises(ApiHttpError) as ctx_sig:
            authenticator.verify_bearer_header(f"Bearer {tampered_token}")
        self.assertEqual(ctx_sig.exception.status_code, 401)
        self.assertEqual(ctx_sig.exception.error_code, "INVALID_TOKEN_SIGNATURE")

        # 3. Forged RS256 token signed with attacker's 2048-bit RSA key is rejected
        forged_rs256_token = create_signed_test_firebase_token(
            uid="attacker_uid",
            email="attacker@evil.org",
            rsa_keypair=UNTRUSTED_ATTACKER_RSA_KEYPAIR,
        )
        with self.assertRaises(ApiHttpError) as ctx_forged_rs256:
            authenticator.verify_bearer_header(f"Bearer {forged_rs256_token}")
        self.assertEqual(ctx_forged_rs256.exception.status_code, 401)
        self.assertEqual(ctx_forged_rs256.exception.error_code, "INVALID_TOKEN_SIGNATURE")

        # 4. Forged HS256 token (even if signed with old default secret) is rejected
        hs_header = base64.urlsafe_b64encode(
            json.dumps({"alg": "HS256", "typ": "JWT", "kid": DEFAULT_TRUSTED_RSA_KEYPAIR.kid}).encode("utf-8")
        ).decode("ascii").rstrip("=")
        hs_payload = base64.urlsafe_b64encode(
            json.dumps({
                "iss": f"https://securetoken.google.com/{AUTHORITATIVE_FIREBASE_PROJECT_ID}",
                "aud": AUTHORITATIVE_FIREBASE_PROJECT_ID,
                "sub": "attacker_hs256",
                "iat": int(time.time()),
                "exp": int(time.time()) + 3600,
            }).encode("utf-8")
        ).decode("ascii").rstrip("=")
        hs_sig = base64.urlsafe_b64encode(
            hmac.new(b"agrosight-firebase-verifier-key", f"{hs_header}.{hs_payload}".encode("ascii"), hashlib.sha256).digest()
        ).decode("ascii").rstrip("=")
        forged_hs256_token = f"{hs_header}.{hs_payload}.{hs_sig}"
        with self.assertRaises(ApiHttpError) as ctx_hs256:
            authenticator.verify_bearer_header(f"Bearer {forged_hs256_token}")
        self.assertEqual(ctx_hs256.exception.status_code, 401)
        self.assertEqual(ctx_hs256.exception.error_code, "UNSUPPORTED_TOKEN_ALG")

        # 5. Expired token is rejected
        expired_token = create_signed_test_firebase_token(
            uid="farmer_verified_01",
            iat_epoch_sec=int(time.time()) - 7200,
            exp_epoch_sec=int(time.time()) - 30,
        )
        with self.assertRaises(ApiHttpError) as ctx_exp:
            authenticator.verify_bearer_header(f"Bearer {expired_token}")
        self.assertEqual(ctx_exp.exception.status_code, 401)
        self.assertEqual(ctx_exp.exception.error_code, "TOKEN_EXPIRED")

        # 6. Token from another Firebase project is rejected
        other_project_token = create_signed_test_firebase_token(
            uid="farmer_other_proj",
            project_id="another-firebase-project-999",
        )
        with self.assertRaises(ApiHttpError) as ctx_proj:
            authenticator.verify_bearer_header(f"Bearer {other_project_token}")
        self.assertEqual(ctx_proj.exception.status_code, 401)
        self.assertEqual(ctx_proj.exception.error_code, "INVALID_AUDIENCE")

        # 7. Token with incorrect audience is rejected
        wrong_aud_token = create_signed_test_firebase_token(
            uid="farmer_verified_01",
            aud="wrong-client-audience-id",
        )
        with self.assertRaises(ApiHttpError) as ctx_aud:
            authenticator.verify_bearer_header(f"Bearer {wrong_aud_token}")
        self.assertEqual(ctx_aud.exception.status_code, 401)
        self.assertEqual(ctx_aud.exception.error_code, "INVALID_AUDIENCE")

        # 8. Token with invalid issuer is rejected
        wrong_iss_token = create_signed_test_firebase_token(
            uid="farmer_verified_01",
            iss=f"https://forged-issuer.example.com/{AUTHORITATIVE_FIREBASE_PROJECT_ID}",
        )
        with self.assertRaises(ApiHttpError) as ctx_iss:
            authenticator.verify_bearer_header(f"Bearer {wrong_iss_token}")
        self.assertEqual(ctx_iss.exception.status_code, 401)
        self.assertEqual(ctx_iss.exception.error_code, "INVALID_ISSUER")

        # 9. Revoked token is rejected (both by token/jti and by user refresh token revocation)
        revocable_token = create_signed_test_firebase_token(
            uid="farmer_revoked_uid",
            jti="jti_to_revoke_123",
        )
        # Valid before revocation
        self.assertEqual(authenticator.verify_bearer_header(f"Bearer {revocable_token}")["uid"], "farmer_revoked_uid")
        authenticator.revoke_token_id("jti_to_revoke_123")
        with self.assertRaises(ApiHttpError) as ctx_rev1:
            authenticator.verify_bearer_header(f"Bearer {revocable_token}")
        self.assertEqual(ctx_rev1.exception.status_code, 401)
        self.assertEqual(ctx_rev1.exception.error_code, "TOKEN_REVOKED")

        # User-level refresh token revocation
        uid_token = create_signed_test_firebase_token(
            uid="farmer_session_revoked",
            iat_epoch_sec=int(time.time()) - 10,
        )
        authenticator.revoke_refresh_tokens("farmer_session_revoked", revoked_at_epoch=int(time.time()))
        with self.assertRaises(ApiHttpError) as ctx_rev2:
            authenticator.verify_bearer_header(f"Bearer {uid_token}")
        self.assertEqual(ctx_rev2.exception.status_code, 401)
        self.assertEqual(ctx_rev2.exception.error_code, "TOKEN_REVOKED")

    def test_zero_trust_rbac_and_uid_impersonation_prevention(self):
        """
        PRIORITY 1 Zero-Trust RBAC Acceptance Suite:
        1. Unverified JWT top-level 'role' claim is never trusted for admin elevation.
        2. Farmer cannot access administrator endpoints (403).
        3. Agronomist (AGRONOMIST_ADMIN) can view admin overview (200) but cannot perform
           Super Administrator operations (403).
        4. Super Administrator (SUPER_ADMIN) can perform role assignment and job deletion (200).
        5. User cannot impersonate another UID in request bodies (403 UID_IMPERSONATION_FORBIDDEN).
        """
        service = SatelliteBackendService(
            storage_root=os.path.join(self.temp_dir, "rbac_storage"),
        )
        server, _ = create_http_server(service=service, host="127.0.0.1", port=0)
        port = server.server_address[1]
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()

        try:
            base_url = f"http://127.0.0.1:{port}"

            def _http(method: str, path: str, token: str, payload: dict = None):
                headers = {"Content-Type": "application/json", "Authorization": f"Bearer {token}"}
                data = json.dumps(payload).encode("utf-8") if payload is not None else None
                req = urllib.request.Request(f"{base_url}{path}", data=data, headers=headers, method=method)
                try:
                    with urllib.request.urlopen(req, timeout=10) as resp:
                        return resp.getcode(), json.loads(resp.read().decode("utf-8"))
                except urllib.error.HTTPError as e:
                    return e.code, json.loads(e.read().decode("utf-8"))

            # Token with unverified top-level payload["role"] = "SUPER_ADMIN" (no verified custom claims)
            self_claimed_admin_token = create_signed_test_firebase_token(
                uid="farmer_sneaky",
                email="sneaky@example.com",
                unverified_payload_role="SUPER_ADMIN",
            )
            agronomist_token = create_signed_test_firebase_token(
                uid="agronomist_01",
                email="agro@agrosight.eg",
                role="AGRONOMIST_ADMIN",
            )
            super_admin_token = create_signed_test_firebase_token(
                uid="super_admin_01",
                email="elzahraafatma288@gmail.com",
                email_verified=True,
            )

            # 1 & 2. Farmer (even with unverified top-level payload role="SUPER_ADMIN") is blocked from admin endpoints
            code_farmer_ov, err_farmer_ov = _http("GET", "/api/v1/admin/overview", token=self_claimed_admin_token)
            self.assertEqual(code_farmer_ov, 403)
            self.assertEqual(err_farmer_ov["error_code"], "FORBIDDEN_ADMIN_ENDPOINT")

            code_farmer_roles, err_farmer_roles = _http(
                "POST",
                "/api/v1/admin/roles",
                token=self_claimed_admin_token,
                payload={"target_uid": "farmer_sneaky", "role": "SUPER_ADMIN"},
            )
            self.assertEqual(code_farmer_roles, 403)
            self.assertEqual(err_farmer_roles["error_code"], "FORBIDDEN_SUPER_ADMIN_ONLY")

            # 3. Agronomist CAN view admin overview (200) but CANNOT assign roles (403)
            code_agro_ov, agro_ov = _http("GET", "/api/v1/admin/overview", token=agronomist_token)
            self.assertEqual(code_agro_ov, 200)
            self.assertEqual(agro_ov["admin_role"], "AGRONOMIST_ADMIN")

            code_agro_roles, err_agro_roles = _http(
                "POST",
                "/api/v1/admin/roles",
                token=agronomist_token,
                payload={"target_uid": "agronomist_01", "role": "SUPER_ADMIN"},
            )
            self.assertEqual(code_agro_roles, 403)
            self.assertEqual(err_agro_roles["error_code"], "FORBIDDEN_SUPER_ADMIN_ONLY")

            # 4. Super Admin CAN assign verified custom claims roles (200)
            code_sa_roles, sa_roles = _http(
                "POST",
                "/api/v1/admin/roles",
                token=super_admin_token,
                payload={"target_uid": "promoted_agronomist_uid", "role": "AGRONOMIST_ADMIN"},
            )
            self.assertEqual(code_sa_roles, 200)
            self.assertEqual(sa_roles["assigned_role"], "AGRONOMIST_ADMIN")

            # 5. UID Impersonation prevention: farmer_sneaky cannot submit a request with owner_uid="victim_farmer_uid"
            farm_poly = {
                "type": "Polygon",
                "coordinates": [[
                    [30.3500, 30.3800],
                    [30.3510, 30.3800],
                    [30.3510, 30.3810],
                    [30.3500, 30.3810],
                    [30.3500, 30.3800],
                ]],
            }
            code_impersonate, err_imp = _http(
                "POST",
                "/api/v1/satellite/scenes",
                token=self_claimed_admin_token,
                payload={
                    "farm_id": "new_unregistered_farm_99",
                    "owner_uid": "victim_farmer_uid",
                    "farm_polygon_geojson": farm_poly,
                },
            )
            self.assertEqual(code_impersonate, 403)
            self.assertEqual(err_imp["error_code"], "UID_IMPERSONATION_FORBIDDEN")
        finally:
            server.shutdown()
            server.server_close()

    def test_remote_cog_http_range_windowed_tiles_and_overviews(self):
        """
        PRIORITY 2 Remote COG & Windowed Tile Access Suite (Cases 1, 2, 3, 7):
        - Creates a large 128x128 tiled Cloud-Optimized GeoTIFF (tile_width=16, tile_height=16 => 64 tiles)
          with a 2x overview pyramid IFD (64x64) in EPSG:32636.
        - Serves the COG over an HTTP server supporting RFC 7233 Range requests (206 Partial Content).
        - Queries a small farm polygon covering only the top-left corner of the scene.
        - Verifies that ONLY the header/IFD and the intersecting tiles are fetched via HTTP Range
          requests (without downloading the entire file).
        - Verifies reading the remote overview IFD (overview_level=1).
        """
        e0, n0 = wgs84_to_utm(30.3500, 30.3800, utm_zone=36)
        transform = GeoTransform(
            origin_x=e0,
            pixel_width=10.0,
            row_rotation=0.0,
            origin_y=n0 + 1280.0,
            col_rotation=0.0,
            pixel_height=-10.0,
        )
        # 128x128 raster with deterministic gradient so every pixel has a distinct verifiable value
        grid = [[float(1000 + (r * 7 + c * 3) % 4000) for c in range(128)] for r in range(128)]
        cog_path = os.path.join(self.temp_dir, "large_sentinel2_tiled_cog.tif")
        GeoTiffCogProcessor.write_tiled_cog(
            file_path=cog_path,
            band_data=RasterBandData("B08", 128, 128, 32636, transform, grid, nodata_value=0.0, spatial_resolution_m=10.0),
            tile_width=16,
            tile_height=16,
            dtype="uint16",
            compress=False,  # Keep uncompressed so file size is >35 KB across 64 tiles
            include_overview=True,
        )
        with open(cog_path, "rb") as f:
            cog_bytes = f.read()
        total_cog_size = len(cog_bytes)
        self.assertGreater(total_cog_size, 32768)

        # Case 1: Valid local tiled COG full read & overview read
        local_full = GeoTiffCogProcessor.read_geotiff(cog_path, band_name="B08")
        self.assertEqual((local_full.width, local_full.height), (128, 128))
        self.assertEqual(local_full.crs_epsg, 32636)
        self.assertAlmostEqual(local_full.values[5][9], grid[5][9], places=2)

        local_ovr = GeoTiffCogProcessor.read_geotiff(cog_path, band_name="B08", overview_level=1)
        self.assertEqual((local_ovr.width, local_ovr.height), (64, 64))
        self.assertAlmostEqual(local_ovr.transform.pixel_width, 20.0, places=3)

        # Case 2, 3, 7: Remote COG server supporting HTTP Range requests (206 Partial Content)
        class RangeCogHandler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def do_GET(self):
                if self.path != "/sentinel2/B08.tif":
                    self.send_response(404)
                    self.end_headers()
                    return
                range_hdr = self.headers.get("Range")
                if range_hdr and range_hdr.startswith("bytes="):
                    span = range_hdr[len("bytes=") :].split("-")
                    start = int(span[0])
                    end = min(total_cog_size - 1, int(span[1]))
                    chunk = cog_bytes[start : end + 1]
                    self.send_response(206)
                    self.send_header("Content-Type", "image/tiff")
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Range", f"bytes {start}-{end}/{total_cog_size}")
                    self.send_header("Content-Length", str(len(chunk)))
                    self.end_headers()
                    self.wfile.write(chunk)
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "image/tiff")
                    self.send_header("Content-Length", str(total_cog_size))
                    self.end_headers()
                    self.wfile.write(cog_bytes)

        cog_server = ThreadingHTTPServer(("127.0.0.1", 0), RangeCogHandler)
        cog_port = cog_server.server_address[1]
        threading.Thread(target=cog_server.serve_forever, daemon=True).start()

        try:
            cog_url = f"http://127.0.0.1:{cog_port}/sentinel2/B08.tif"
            # Small farm polygon in the top-left tile (rows 2..8, cols 2..8)
            lon_tl, lat_tl = utm_to_wgs84(e0 + 25.0, n0 + 1280.0 - 25.0, utm_zone=36)
            lon_br, lat_br = utm_to_wgs84(e0 + 75.0, n0 + 1280.0 - 75.0, utm_zone=36)
            small_farm_poly = {
                "type": "Polygon",
                "coordinates": [[
                    [lon_tl, lat_br],
                    [lon_br, lat_br],
                    [lon_br, lat_tl],
                    [lon_tl, lat_tl],
                    [lon_tl, lat_br],
                ]],
            }

            with RemoteCogHttpClient(require_range_support=True) as client:
                window_band = GeoTiffCogProcessor.read_geotiff(
                    cog_url,
                    band_name="B08",
                    farm_polygon_geojson=small_farm_poly,
                    http_client=client,
                )
                self.assertTrue(client.server_supports_range)
                self.assertEqual(client.full_downloads_made, 0)
                # Only 1 header range request + 1 intersecting tile request (out of 64 tiles!)
                self.assertGreaterEqual(client.range_requests_made, 2)
                self.assertLessEqual(client.range_requests_made, 4)
                # Window dimensions are much smaller than the full 128x128 scene
                self.assertLess(window_band.width, 20)
                self.assertLess(window_band.height, 20)
                self.assertEqual(window_band.crs_epsg, 32636)

                # Also verify reading remote overview IFD (overview_level=1) over HTTP Range
                remote_ovr = GeoTiffCogProcessor.read_geotiff(
                    cog_url,
                    band_name="B08",
                    overview_level=1,
                    farm_polygon_geojson=small_farm_poly,
                    http_client=client,
                )
                self.assertAlmostEqual(remote_ovr.transform.pixel_width, 20.0, places=3)
            self.assertTrue(client.closed)
        finally:
            cog_server.shutdown()
            cog_server.server_close()

    def test_remote_cog_non_range_server_and_interrupted_download(self):
        """
        PRIORITY 2 Remote COG Error Handling Suite (Cases 4 & 6):
        - Case 4: Server ignoring Range header (returns HTTP 200 OK instead of 206 Partial Content)
          is rejected with RangeRequestNotSupportedError when require_range_support=True or oversized,
          and safely validated when fallback is permitted.
        - Case 6: Server whose transfer is interrupted before delivering Content-Length bytes
          is rejected with RasterDownloadInterruptedError.
        """
        t_deg = GeoTransform(30.3500, 0.0001, 0.0, 30.3810, 0.0, -0.0001)
        grid = [[2500.0 for _ in range(8)] for _ in range(8)]
        tif_path = os.path.join(self.temp_dir, "small_test.tif")
        GeoTiffCogProcessor.write_tiled_cog(
            tif_path,
            RasterBandData("B04", 8, 8, 4326, t_deg, grid, nodata_value=0.0),
            tile_width=4,
            tile_height=4,
        )
        with open(tif_path, "rb") as f:
            tif_bytes = f.read()

        class NonRangeAndInterruptedHandler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def do_GET(self):
                if self.path == "/no_range.tif":
                    # Ignores Range header and always responds with 200 OK
                    self.send_response(200)
                    self.send_header("Content-Type", "image/tiff")
                    self.send_header("Content-Length", str(len(tif_bytes)))
                    self.end_headers()
                    self.wfile.write(tif_bytes)
                elif self.path == "/interrupted.tif":
                    # Promises full file in Content-Range / Content-Length but truncates after 40 bytes
                    self.send_response(206)
                    self.send_header("Content-Type", "image/tiff")
                    self.send_header("Content-Range", f"bytes 0-16383/{len(tif_bytes) + 50000}")
                    self.send_header("Content-Length", "16384")
                    self.end_headers()
                    self.wfile.write(tif_bytes[:40])
                else:
                    self.send_response(404)
                    self.end_headers()

        srv = ThreadingHTTPServer(("127.0.0.1", 0), NonRangeAndInterruptedHandler)
        port = srv.server_address[1]
        threading.Thread(target=srv.serve_forever, daemon=True).start()

        try:
            no_range_url = f"http://127.0.0.1:{port}/no_range.tif"
            interrupted_url = f"http://127.0.0.1:{port}/interrupted.tif"

            # Case 4a: When require_range_support=True, 200 OK raises RangeRequestNotSupportedError
            with self.assertRaises(RangeRequestNotSupportedError):
                GeoTiffCogProcessor.read_geotiff(no_range_url, require_range_support=True)

            # Case 4b: When payload exceeds max_unranged_bytes, 200 OK raises RangeRequestNotSupportedError
            with self.assertRaises(RangeRequestNotSupportedError):
                GeoTiffCogProcessor.read_geotiff(no_range_url, max_unranged_bytes=100)

            # Case 4c: When within max_unranged_bytes and fallback allowed, reads and verifies full Content-Length
            with RemoteCogHttpClient(require_range_support=False) as fallback_client:
                fallback_band = GeoTiffCogProcessor.read_geotiff(no_range_url, http_client=fallback_client)
                self.assertFalse(fallback_client.server_supports_range)
                self.assertEqual(fallback_client.full_downloads_made, 1)
                self.assertEqual((fallback_band.width, fallback_band.height), (8, 8))

            # Case 6: Interrupted download raises RasterDownloadInterruptedError
            with self.assertRaises(RasterDownloadInterruptedError):
                with RemoteCogHttpClient(max_retries=0) as int_client:
                    GeoTiffCogProcessor.read_geotiff(interrupted_url, http_client=int_client)
        finally:
            srv.shutdown()
            srv.server_close()

    def test_corrupted_tiff_missing_bands_unsupported_crs_and_invalid_url(self):
        """
        PRIORITY 2 Raster Integrity Validation Suite (Cases 5, 8, 9, 10):
        - Case 5: Corrupted TIFF (too short, invalid magic, or corrupted DEFLATE tile) -> RasterIntegrityError.
        - Case 8: Raster pipeline with missing required Sentinel-2 bands -> MissingBandError.
        - Case 9: Unsupported Coordinate Reference System (e.g. EPSG:99999 or EPSG:3857) -> UnsupportedCrsError.
        - Case 10: Invalid asset URL (bad scheme, missing host, or HTTP 404) -> InvalidAssetUrlError.
        """
        # Case 5a: Corrupted TIFF header (smaller than 8 bytes)
        with self.assertRaises(RasterIntegrityError):
            GeoTiffCogProcessor.parse_geotiff_bytes(b"II\x2a")

        # Case 5b: Corrupted TIFF magic number (43 instead of 42)
        bad_magic = struct.pack("<2sHI", b"II", 43, 8) + b"\x00" * 32
        with self.assertRaises(RasterIntegrityError):
            GeoTiffCogProcessor.parse_geotiff_bytes(bad_magic)

        # Case 5c: Corrupted DEFLATE compressed tile payload
        t_deg = GeoTransform(30.3500, 0.0001, 0.0, 30.3810, 0.0, -0.0001)
        valid_path = os.path.join(self.temp_dir, "valid_deflate.tif")
        GeoTiffCogProcessor.write_tiled_cog(
            valid_path,
            RasterBandData("B08", 8, 8, 4326, t_deg, [[1500.0] * 8 for _ in range(8)]),
            tile_width=8,
            tile_height=8,
            compress=True,
            include_overview=False,
        )
        with open(valid_path, "rb") as f:
            raw_tif = bytearray(f.read())
        # Corrupt the compressed zlib bytes at the end of the file
        for i in range(len(raw_tif) - 8, len(raw_tif)):
            raw_tif[i] ^= 0xFF
        with self.assertRaises(RasterIntegrityError):
            GeoTiffCogProcessor.parse_geotiff_bytes(bytes(raw_tif))

        # Case 8: Missing required Sentinel-2 bands raises MissingBandError
        farm_poly = {
            "type": "Polygon",
            "coordinates": [[
                [30.3500, 30.3800],
                [30.3510, 30.3800],
                [30.3510, 30.3810],
                [30.3500, 30.3810],
                [30.3500, 30.3800],
            ]],
        }
        with self.assertRaises(MissingBandError):
            process_sentinel2_raster_bands(
                band_sources={"B04": valid_path, "B08": valid_path},  # Missing B02, B05, B06, B8A, B11, SCL
                farm_polygon_geojson=farm_poly,
            )

        # Case 9: Unsupported CRS raises UnsupportedCrsError
        with self.assertRaises(UnsupportedCrsError):
            validate_supported_crs(99999)
        with self.assertRaises(UnsupportedCrsError):
            validate_supported_crs(3857)
        with self.assertRaises(UnsupportedCrsError):
            GeoTiffCogProcessor.write_geotiff(
                os.path.join(self.temp_dir, "bad_crs.tif"),
                RasterBandData("B08", 4, 4, 99999, t_deg, [[1000.0] * 4 for _ in range(4)]),
            )

        # Craft a binary GeoTIFF with EPSG:99999 inside GeoKeyDirectoryTag and verify read_geotiff rejects it
        with open(valid_path, "rb") as f:
            epsg_mod_bytes = f.read().replace(struct.pack("<H", 4326), struct.pack("<H", 3857))
        with self.assertRaises(UnsupportedCrsError):
            GeoTiffCogProcessor.parse_geotiff_bytes(epsg_mod_bytes)

        # Case 10: Invalid asset URL raises InvalidAssetUrlError
        with self.assertRaises(InvalidAssetUrlError):
            GeoTiffCogProcessor.read_geotiff("ftp://invalid-scheme.example.com/B08.tif")
        with self.assertRaises(InvalidAssetUrlError):
            GeoTiffCogProcessor.read_geotiff("http:///missing-host.tif")
        with self.assertRaises(InvalidAssetUrlError):
            GeoTiffCogProcessor.read_geotiff("https://earth-search.aws.element84.com/")
        with self.assertRaises(InvalidAssetUrlError):
            GeoTiffCogProcessor.read_geotiff("/nonexistent/local/path/to/band.tif")


if __name__ == "__main__":
    unittest.main()

