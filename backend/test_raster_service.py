import json
import math
import os
import shutil
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from app import (
    SatelliteBackendService,
    create_http_server,
    create_signed_test_firebase_token,
)
from raster_service import (
    GeoTiffCogProcessor,
    GeoTransform,
    RasterBandData,
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


if __name__ == "__main__":
    unittest.main()
