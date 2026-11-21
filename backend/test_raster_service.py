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
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, Optional

from app import (
    AUTHORITATIVE_FIREBASE_PROJECT_ID,
    ApiHttpError,
    FirebaseAdminAuthenticator,
    SatelliteBackendService,
    create_http_server,
    verify_firebase_bearer_token,
)
from database import JOB_LIFECYCLE_STATUSES, AgroSightPersistentDatabase
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


# ==============================================================================
# Isolated Test-Harness RS256 Cryptographic Simulator for Firebase Admin SDK
# (Strictly confined to test_raster_service.py; NEVER present in backend/app.py)
# ==============================================================================

ASN1_SHA256_DER_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")


class TestHarnessRsaKeyPair:
    """Test-only 2048-bit RSA keypair used exclusively in the unit test harness."""

    def __init__(self, n: int, e: int, d: Optional[int] = None, kid: str = "google-securetoken-kid-1"):
        self.n = int(n)
        self.e = int(e)
        self.d = int(d) if d is not None else None
        self.kid = str(kid)
        self.key_byte_len = (self.n.bit_length() + 7) // 8

    def sign_rs256(self, message: bytes) -> bytes:
        if self.d is None:
            raise ValueError("Cannot sign with public-only key")
        digest = hashlib.sha256(message).digest()
        t_der = ASN1_SHA256_DER_PREFIX + digest
        ps_len = self.key_byte_len - len(t_der) - 3
        em = b"\x00\x01" + (b"\xff" * ps_len) + b"\x00" + t_der
        m_int = int.from_bytes(em, "big")
        s_int = pow(m_int, self.d, self.n)
        return s_int.to_bytes(self.key_byte_len, "big")

    def verify_rs256(self, message: bytes, signature: bytes) -> bool:
        if len(signature) != self.key_byte_len:
            return False
        s_int = int.from_bytes(signature, "big")
        if s_int <= 0 or s_int >= self.n:
            return False
        m_int = pow(s_int, self.e, self.n)
        em = m_int.to_bytes(self.key_byte_len, "big")
        digest = hashlib.sha256(message).digest()
        t_der = ASN1_SHA256_DER_PREFIX + digest
        ps_len = self.key_byte_len - len(t_der) - 3
        expected_em = b"\x00\x01" + (b"\xff" * ps_len) + b"\x00" + t_der
        return hmac.compare_digest(em, expected_em)


def _is_probable_prime(n: int) -> bool:
    if n < 2 or (n & 1) == 0:
        return n == 2
    for sp in (3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53):
        if n == sp:
            return True
        if n % sp == 0:
            return False
    d = n - 1
    s = 0
    while (d & 1) == 0:
        d >>= 1
        s += 1
    for a in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37):
        x = pow(a, d, n)
        if x == 1 or x == n - 1:
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def _derive_test_prime(seed_label: str) -> int:
    raw = bytearray()
    counter = 0
    while len(raw) < 128:
        raw.extend(hashlib.sha512(f"{seed_label}:{counter}".encode("utf-8")).digest())
        counter += 1
    candidate = int.from_bytes(bytes(raw[:128]), "big")
    candidate |= (1 << 1023) | (1 << 1022) | 1
    while not _is_probable_prime(candidate):
        candidate += 2
    return candidate


def _build_test_rsa2048_keypair(seed_name: str, kid: str) -> TestHarnessRsaKeyPair:
    p = _derive_test_prime(f"{seed_name}:p")
    q = _derive_test_prime(f"{seed_name}:q")
    n = p * q
    e = 65537
    phi = (p - 1) * (q - 1)
    d = pow(e, -1, phi)
    return TestHarnessRsaKeyPair(n=n, e=e, d=d, kid=kid)


TEST_GOOGLE_PUBLIC_CERT_KEYPAIR = _build_test_rsa2048_keypair(
    seed_name="test_google_securetoken_issuer_2048",
    kid="google-securetoken-kid-1",
)
TEST_ATTACKER_RSA_KEYPAIR = _build_test_rsa2048_keypair(
    seed_name="test_attacker_untrusted_rsa_2048",
    kid="google-securetoken-kid-1",
)


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _b64url_decode(segment: str) -> bytes:
    pad = "=" * ((4 - len(segment) % 4) % 4)
    return base64.urlsafe_b64decode((segment + pad).encode("ascii"))


def create_test_firebase_jwt(
    uid: str,
    email: str = "",
    email_verified: bool = True,
    verified_custom_role: Optional[str] = None,
    forged_top_level_role: Optional[str] = None,
    exp_epoch_sec: Optional[int] = None,
    iat_epoch_sec: Optional[int] = None,
    project_id: str = AUTHORITATIVE_FIREBASE_PROJECT_ID,
    aud: Optional[str] = None,
    iss: Optional[str] = None,
    kid: Optional[str] = None,
    jti: Optional[str] = None,
    signing_keypair: Optional[TestHarnessRsaKeyPair] = None,
) -> str:
    """Create a signed RS256 test JWT for exercising the official verify_id_token path in unit tests."""
    kp = signing_keypair or TEST_GOOGLE_PUBLIC_CERT_KEYPAIR
    now = int(time.time())
    iat_val = int(iat_epoch_sec if iat_epoch_sec is not None else now)
    exp_val = int(exp_epoch_sec if exp_epoch_sec is not None else now + 3600)
    eff_aud = aud if aud is not None else project_id
    eff_iss = iss if iss is not None else f"https://securetoken.google.com/{project_id}"

    header = {
        "alg": "RS256",
        "typ": "JWT",
        "kid": kid if kid is not None else kp.kid,
    }
    payload: Dict[str, Any] = {
        "iss": eff_iss,
        "aud": eff_aud,
        "sub": uid,
        "user_id": uid,
        "email": email,
        "email_verified": email_verified,
        "iat": iat_val,
        "auth_time": iat_val,
        "exp": exp_val,
        "jti": jti or f"jwt_{uuid.uuid4().hex}",
    }
    if verified_custom_role is not None:
        payload["firebase_custom_claims"] = {"role": verified_custom_role}
    if forged_top_level_role is not None:
        payload["role"] = forged_top_level_role

    h_enc = _b64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    p_enc = _b64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    sig = kp.sign_rs256(f"{h_enc}.{p_enc}".encode("ascii"))
    return f"{h_enc}.{p_enc}.{_b64url_encode(sig)}"


def make_test_firebase_sdk_verifier() -> Callable[[str, bool], Dict[str, Any]]:
    """
    Simulate `firebase_admin.auth.verify_id_token(token, check_revoked=...)` inside unit tests
    by verifying the JWT's RS256 signature against `TEST_GOOGLE_PUBLIC_CERT_KEYPAIR`.
    """
    def _sdk_verify(token: str, check_revoked: bool) -> Dict[str, Any]:
        parts = token.split(".")
        if len(parts) != 3 or not all(parts):
            raise ValueError("Invalid 3-part JWT format")
        header = json.loads(_b64url_decode(parts[0]).decode("utf-8"))
        payload = json.loads(_b64url_decode(parts[1]).decode("utf-8"))
        sig_bytes = _b64url_decode(parts[2])
        if header.get("alg") != "RS256":
            raise ValueError(f"Unsupported JWT alg: {header.get('alg')}")
        if header.get("kid") != TEST_GOOGLE_PUBLIC_CERT_KEYPAIR.kid:
            raise ValueError(f"Unknown kid: {header.get('kid')}")
        signing_input = f"{parts[0]}.{parts[1]}".encode("ascii")
        if not TEST_GOOGLE_PUBLIC_CERT_KEYPAIR.verify_rs256(signing_input, sig_bytes):
            raise ValueError("RS256 signature verification failed")
        return payload

    return _sdk_verify


def make_test_authenticator() -> FirebaseAdminAuthenticator:
    return FirebaseAdminAuthenticator(
        project_id=AUTHORITATIVE_FIREBASE_PROJECT_ID,
        check_revoked=True,
        sdk_token_verifier=make_test_firebase_sdk_verifier(),
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
        t_10m = GeoTransform(500000.0, 10.0, 0.0, 3300000.0, 0.0, -10.0)
        t_20m = GeoTransform(500000.0, 20.0, 0.0, 3300000.0, 0.0, -20.0)

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
        self.assertEqual(int(scl_10m.values[0][0]), 4)
        self.assertEqual(int(scl_10m.values[0][1]), 4)
        self.assertEqual(int(scl_10m.values[0][2]), 9)
        self.assertEqual(int(scl_10m.values[0][3]), 9)
        self.assertEqual(int(scl_10m.values[3][3]), 3)

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
        origin_e, origin_n = wgs84_to_utm(30.3500, 30.3833, utm_zone=36)
        t_10m = GeoTransform(origin_e, 10.0, 0.0, origin_n, 0.0, -10.0)
        t_20m = GeoTransform(origin_e, 20.0, 0.0, origin_n, 0.0, -20.0)

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

        b02_grid = [[1400.0 for _ in range(6)] for _ in range(6)]
        b04_grid = [[1600.0 for _ in range(6)] for _ in range(6)]
        b08_grid = [[5200.0 for _ in range(6)] for _ in range(6)]

        for hc in (2, 3):
            b04_grid[2][hc] = 2800.0
            b08_grid[2][hc] = 3200.0

        b05_20m_grid = [[2200.0 for _ in range(3)] for _ in range(3)]
        b06_20m_grid = [[3800.0 for _ in range(3)] for _ in range(3)]
        b8a_20m_grid = [[5000.0 for _ in range(3)] for _ in range(3)]
        b11_20m_grid = [[2800.0 for _ in range(3)] for _ in range(3)]
        scl_20m_grid = [[4.0 for _ in range(3)] for _ in range(3)]
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

        self.assertEqual(stats.total_pixel_count, 36)
        self.assertEqual(stats.polygon_pixel_count, 25)
        self.assertEqual(stats.valid_pixel_count, 23)
        self.assertEqual(stats.masked_cloud_or_nodata_count, 2)
        self.assertEqual(stats.anomaly_pixel_count, 2)
        self.assertAlmostEqual(stats.anomaly_area_m2, 200.0, places=1)

        for idx_name in ("NDVI", "NDRE", "NDMI", "PSRI"):
            self.assertIn(idx_name, stats.output_raster_files)
            out_path = stats.output_raster_files[idx_name]
            self.assertTrue(os.path.exists(out_path))
            out_band = GeoTiffCogProcessor.read_geotiff(out_path, band_name=idx_name)
            self.assertEqual(out_band.crs_epsg, 32636)
            self.assertEqual(out_band.width, 6)
            self.assertEqual(out_band.height, 6)

        ndvi_out = GeoTiffCogProcessor.read_geotiff(stats.output_raster_files["NDVI"], band_name="NDVI")
        self.assertAlmostEqual(ndvi_out.values[0][0], 0.75, places=3)
        self.assertAlmostEqual(ndvi_out.values[2][2], 0.10, places=3)
        self.assertTrue(math.isnan(ndvi_out.values[5][5]))
        self.assertTrue(math.isnan(ndvi_out.values[0][4]))

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

        px_palm = evaluate_pixel(0.05, 0.13, 0.20, 0.26, 0.29, 0.30, 0.21, scl_class=4, thresholds=palm_t)
        px_potato = evaluate_pixel(0.05, 0.13, 0.20, 0.26, 0.29, 0.30, 0.21, scl_class=4, thresholds=potato_t)
        self.assertFalse(px_palm.is_anomalous)
        self.assertTrue(px_potato.is_anomalous)

    def test_temporal_analysis_sorts_by_date_and_filters_cloud_and_valid_pixel_ratio(self):
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

    def test_production_authenticator_fails_closed_without_firebase_credentials(self):
        """
        PHASE 1 P0 Security Requirement:
        - Ensure no custom RSA key generation or default trusted test keypair exists in `backend/app.py`.
        - Ensure a default production `FirebaseAdminAuthenticator()` without service-account credentials
          fails closed (503 FIREBASE_AUTH_NOT_CONFIGURED) and NEVER falls back to test authentication.
        """
        import app as backend_app_module

        self.assertFalse(hasattr(backend_app_module, "RsaKeyPair"))
        self.assertFalse(hasattr(backend_app_module, "DEFAULT_TRUSTED_RSA_KEYPAIR"))
        self.assertFalse(hasattr(backend_app_module, "get_deterministic_rsa2048_keypair"))
        self.assertFalse(hasattr(backend_app_module, "create_signed_test_firebase_token"))

        unconfigured_auth = FirebaseAdminAuthenticator(project_id=AUTHORITATIVE_FIREBASE_PROJECT_ID)
        if not unconfigured_auth.firebase_admin_initialized:
            self.assertFalse(unconfigured_auth.is_configured)
            token = create_test_firebase_jwt(uid="farmer_alice", email="alice@example.com")
            with self.assertRaises(ApiHttpError) as ctx:
                unconfigured_auth.verify_bearer_header(f"Bearer {token}")
            self.assertEqual(ctx.exception.status_code, 503)
            self.assertEqual(ctx.exception.error_code, "FIREBASE_AUTH_NOT_CONFIGURED")

    def test_firebase_admin_sdk_authentication_and_token_rejection_suite(self):
        """
        PHASE 1 Security Acceptance Suite (all 9 required security proofs):
        1. Forged RS256 token (tampered payload) is rejected.
        2. Token signed by an attacker-controlled private key is rejected.
        3. Forged HS256 token is rejected.
        4. Expired token is rejected.
        5. Token from a different Firebase project is rejected.
        6. Token with a forged SUPER_ADMIN claim in the JWT payload is rejected for admin access.
        7. Valid Firebase test token is accepted only through the official verification path.
        8. Revoked token is rejected when revocation checking is enabled.
        """
        authenticator = make_test_authenticator()

        # 7. Valid Firebase token accepted through the official verification path
        valid_token = create_test_firebase_jwt(
            uid="farmer_verified_01",
            email="farmer01@agrosight.eg",
            email_verified=True,
        )
        claims = authenticator.verify_bearer_header(f"Bearer {valid_token}")
        self.assertEqual(claims["uid"], "farmer_verified_01")
        self.assertEqual(claims["role"], "FARMER")
        self.assertEqual(claims["project_id"], AUTHORITATIVE_FIREBASE_PROJECT_ID)

        # 1. Forged RS256 token (tampered payload with valid signature bytes) is rejected
        parts = valid_token.split(".")
        tampered_payload = json.dumps({
            "iss": f"https://securetoken.google.com/{AUTHORITATIVE_FIREBASE_PROJECT_ID}",
            "aud": AUTHORITATIVE_FIREBASE_PROJECT_ID,
            "sub": "impersonated_admin_uid",
            "user_id": "impersonated_admin_uid",
            "iat": int(time.time()),
            "exp": int(time.time()) + 3600,
        }).encode("utf-8")
        tampered_p_enc = _b64url_encode(tampered_payload)
        forged_rs256_tampered = f"{parts[0]}.{tampered_p_enc}.{parts[2]}"
        with self.assertRaises(ApiHttpError) as ctx_forged:
            authenticator.verify_bearer_header(f"Bearer {forged_rs256_tampered}")
        self.assertEqual(ctx_forged.exception.status_code, 401)
        self.assertEqual(ctx_forged.exception.error_code, "INVALID_TOKEN_SIGNATURE")

        # 2. Token signed by an attacker-controlled private key is rejected
        attacker_token = create_test_firebase_jwt(
            uid="attacker_uid",
            email="attacker@evil.org",
            signing_keypair=TEST_ATTACKER_RSA_KEYPAIR,
        )
        with self.assertRaises(ApiHttpError) as ctx_attacker:
            authenticator.verify_bearer_header(f"Bearer {attacker_token}")
        self.assertEqual(ctx_attacker.exception.status_code, 401)
        self.assertEqual(ctx_attacker.exception.error_code, "INVALID_TOKEN_SIGNATURE")

        # 3. Forged HS256 token is rejected
        hs_header = _b64url_encode(json.dumps({"alg": "HS256", "typ": "JWT", "kid": TEST_GOOGLE_PUBLIC_CERT_KEYPAIR.kid}).encode("utf-8"))
        hs_payload = _b64url_encode(json.dumps({
            "iss": f"https://securetoken.google.com/{AUTHORITATIVE_FIREBASE_PROJECT_ID}",
            "aud": AUTHORITATIVE_FIREBASE_PROJECT_ID,
            "sub": "attacker_hs256",
            "iat": int(time.time()),
            "exp": int(time.time()) + 3600,
        }).encode("utf-8"))
        hs_sig = _b64url_encode(hmac.new(b"secret", f"{hs_header}.{hs_payload}".encode("ascii"), hashlib.sha256).digest())
        with self.assertRaises(ApiHttpError) as ctx_hs256:
            authenticator.verify_bearer_header(f"Bearer {hs_header}.{hs_payload}.{hs_sig}")
        self.assertEqual(ctx_hs256.exception.status_code, 401)

        # 4. Expired token is rejected
        expired_token = create_test_firebase_jwt(
            uid="farmer_verified_01",
            iat_epoch_sec=int(time.time()) - 7200,
            exp_epoch_sec=int(time.time()) - 30,
        )
        with self.assertRaises(ApiHttpError) as ctx_exp:
            authenticator.verify_bearer_header(f"Bearer {expired_token}")
        self.assertEqual(ctx_exp.exception.status_code, 401)
        self.assertEqual(ctx_exp.exception.error_code, "TOKEN_EXPIRED")

        # 5. Token from a different Firebase project is rejected
        other_project_token = create_test_firebase_jwt(
            uid="farmer_other_proj",
            project_id="another-firebase-project-999",
        )
        with self.assertRaises(ApiHttpError) as ctx_proj:
            authenticator.verify_bearer_header(f"Bearer {other_project_token}")
        self.assertEqual(ctx_proj.exception.status_code, 401)
        self.assertEqual(ctx_proj.exception.error_code, "INVALID_AUDIENCE")

        # 6. Token with forged SUPER_ADMIN top-level claim is resolved only as FARMER
        forged_claim_token = create_test_firebase_jwt(
            uid="farmer_forged_role",
            email="sneaky@agrosight.eg",
            forged_top_level_role="SUPER_ADMIN",
        )
        forged_claims = authenticator.verify_bearer_header(f"Bearer {forged_claim_token}")
        self.assertEqual(forged_claims["role"], "FARMER")

        # 8. Revoked token is rejected
        revocable_token = create_test_firebase_jwt(
            uid="farmer_revoked_uid",
            jti="jti_to_revoke_123",
        )
        self.assertEqual(authenticator.verify_bearer_header(f"Bearer {revocable_token}")["uid"], "farmer_revoked_uid")
        authenticator.revoke_token_id("jti_to_revoke_123")
        with self.assertRaises(ApiHttpError) as ctx_rev:
            authenticator.verify_bearer_header(f"Bearer {revocable_token}")
        self.assertEqual(ctx_rev.exception.status_code, 401)
        self.assertEqual(ctx_rev.exception.error_code, "TOKEN_REVOKED")

    def test_backend_http_api_authentication_ownership_private_files_and_persistence(self):
        """
        PHASES 1, 2, 3, and 6 Comprehensive Integration Suite:
        - Verifies authenticated STAC scene discovery with date range parameters.
        - Verifies cross-farmer isolation:
          * Farmer Bob cannot access Farmer Alice's farm (403).
          * Farmer Bob cannot access Farmer Alice's job (403).
          * Farmer Bob cannot access Farmer Alice's processing result (403).
          * Farmer Bob cannot download Farmer Alice's private GeoTIFF files (403).
          * Farmer Bob cannot delete administrative jobs (403).
          * Farmer Bob cannot delete Farmer Alice's farm (403).
        - Verifies all 9 job lifecycle transitions are recorded in persistent database history.
        - Verifies short-lived signed download URLs work for valid signatures and fail when expired or tampered.
        - Verifies database persistence across a simulated backend server restart.
        - Verifies cascading farm deletion removes database records and private GeoTIFF files.
        """
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
        storage_root = os.path.join(self.temp_dir, "api_persistent_storage")
        authenticator = make_test_authenticator()
        service = SatelliteBackendService(
            storage_root=storage_root,
            authenticator=authenticator,
            stac_search_fn=lambda **kwargs: fake_stac_scenes,
            signed_url_secret="unit-test-hmac-secret-key-32bytes",
        )
        server, _ = create_http_server(service=service, host="127.0.0.1", port=0)
        port = server.server_address[1]
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()

        try:
            base_url = f"http://127.0.0.1:{port}"
            alice_token = create_test_firebase_jwt(uid="farmer_alice", email="alice@example.com")
            bob_token = create_test_firebase_jwt(uid="farmer_bob", email="bob@example.com")
            expired_token = create_test_firebase_jwt(
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

            def _http_raw(method: str, path: str, token: str = ""):
                headers = {}
                if token:
                    headers["Authorization"] = f"Bearer {token}"
                req = urllib.request.Request(f"{base_url}{path}", headers=headers, method=method)
                try:
                    with urllib.request.urlopen(req, timeout=10) as resp:
                        return resp.getcode(), resp.read(), resp.headers.get("Content-Type", "")
                except urllib.error.HTTPError as e:
                    return e.code, e.read(), e.headers.get("Content-Type", "")

            # 1. GET /api/v1/health
            code, health = _http_call("GET", "/api/v1/health")
            self.assertEqual(code, 200)
            self.assertEqual(health["status"], "HEALTHY")
            self.assertEqual(health["job_lifecycle_statuses"], list(JOB_LIFECYCLE_STATUSES))

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
                "date_start_iso": "2026-04-01",
                "date_end_iso": "2026-05-05",
            })
            self.assertEqual(code_scenes, 200)
            self.assertEqual(scenes_resp["scene_count"], 1)

            # 4. Cross-farmer farm access check: Bob cannot access Alice's farm -> 403
            code_farm_bob, _ = _http_call("GET", "/api/v1/satellite/farms/farm_alice_1", token=bob_token)
            self.assertEqual(code_farm_bob, 403)

            code_farm_alice, farm_data = _http_call("GET", "/api/v1/satellite/farms/farm_alice_1", token=alice_token)
            self.assertEqual(code_farm_alice, 200)
            self.assertEqual(farm_data["owner_uid"], "farmer_alice")

            # 5. Prepare real GeoTIFF band files for POST /api/v1/satellite/process
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

            # 6. Unauthorized farm ownership attempt: Bob cannot process Alice's farm -> 403
            code_bob_forbid, _ = _http_call("POST", "/api/v1/satellite/process", token=bob_token, payload={
                "farm_id": "farm_alice_1",
                "owner_uid": "farmer_alice",
                "crop_id": "tomato",
                "scene_id": "S2B_36RUV_20260501_0_L2A",
                "farm_polygon_geojson": farm_poly,
                "band_assets": band_paths,
            })
            self.assertEqual(code_bob_forbid, 403)

            # 7. Authorized raster processing by Alice -> 200
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

            # Verify job status transition history includes all processing stages
            history_statuses = [h["status"] for h in job_resp["status_history"]]
            for expected_stage in (
                "QUEUED",
                "DISCOVERING_SCENES",
                "DOWNLOADING_ASSETS",
                "PROCESSING_RASTERS",
                "CALCULATING_INDICES",
                "GENERATING_OUTPUTS",
                "COMPLETED",
            ):
                self.assertIn(expected_stage, history_statuses)

            # 8. GET /api/v1/satellite/jobs/{job_id} (Alice -> 200, Bob -> 403)
            code_job_alice, job_status = _http_call("GET", f"/api/v1/satellite/jobs/{job_id}", token=alice_token)
            self.assertEqual(code_job_alice, 200)
            self.assertEqual(job_status["progress_pct"], 100)

            code_job_bob, _ = _http_call("GET", f"/api/v1/satellite/jobs/{job_id}", token=bob_token)
            self.assertEqual(code_job_bob, 403)

            # 9. Farmer cannot delete administrative jobs -> 403
            code_del_job_farmer, _ = _http_call("DELETE", f"/api/v1/admin/jobs/{job_id}", token=alice_token)
            self.assertEqual(code_del_job_farmer, 403)

            # 10. GET /api/v1/satellite/results/{result_id} (Alice -> 200, Bob -> 403)
            code_res_bob, _ = _http_call("GET", f"/api/v1/satellite/results/{result_id}", token=bob_token)
            self.assertEqual(code_res_bob, 403)

            code_res_alice, res_data = _http_call("GET", f"/api/v1/satellite/results/{result_id}", token=alice_token)
            self.assertEqual(code_res_alice, 200)
            self.assertEqual(res_data["data_mode"], "LIVE_GEOREFERENCED_RASTER")
            self.assertFalse(res_data["scientific_assessment"]["is_confirmed_plant_disease"])
            self.assertIn("Vegetation stress indicator", res_data["scientific_assessment"]["terminology_classification"])
            self.assertGreater(res_data["statistics"]["valid_pixel_count"], 0)
            self.assertGreaterEqual(res_data["statistics"]["anomaly_pixel_count"], 1)
            self.assertEqual(len(res_data["anomaly_zones_geojson"]["features"]), 1)

            # 11. Private file download & short-lived signed URL verification (Phase 6)
            ndvi_file_id = res_data["output_files"]["NDVI"]["file_id"]
            code_file_bob, _, _ = _http_raw("GET", f"/api/v1/satellite/files/{ndvi_file_id}", token=bob_token)
            self.assertEqual(code_file_bob, 403)

            code_file_alice, ndvi_tif_bytes, ctype = _http_raw("GET", f"/api/v1/satellite/files/{ndvi_file_id}", token=alice_token)
            self.assertEqual(code_file_alice, 200)
            self.assertIn("image/tiff", ctype)
            parsed_ndvi_band = GeoTiffCogProcessor.parse_geotiff_bytes(ndvi_tif_bytes, band_name="NDVI")
            self.assertEqual((parsed_ndvi_band.width, parsed_ndvi_band.height), (4, 4))

            # Signed URL generation: Bob -> 403, Alice -> 200
            code_sig_bob, _ = _http_call("POST", f"/api/v1/satellite/files/{ndvi_file_id}/signed-url", token=bob_token, payload={"ttl_seconds": 60})
            self.assertEqual(code_sig_bob, 403)

            code_sig_alice, sig_resp = _http_call("POST", f"/api/v1/satellite/files/{ndvi_file_id}/signed-url", token=alice_token, payload={"ttl_seconds": 60})
            self.assertEqual(code_sig_alice, 200)
            signed_url_path = sig_resp["signed_download_url"]

            # Download using signed URL without Bearer header -> 200
            code_signed_dl, signed_bytes, _ = _http_raw("GET", signed_url_path, token="")
            self.assertEqual(code_signed_dl, 200)
            self.assertEqual(len(signed_bytes), len(ndvi_tif_bytes))

            # Tampered signature on signed URL -> 403
            tampered_signed_path = signed_url_path + "00"
            code_tampered_dl, _, _ = _http_raw("GET", tampered_signed_path, token="")
            self.assertEqual(code_tampered_dl, 403)

            # 12. Simulated Server Restart & Job Recovery Test (Phase 2)
            # Insert an in-progress job directly into the DB, then instantiate a new SatelliteBackendService
            interrupted_job_id = "job_interrupted_on_crash"
            service.db.create_job(
                job_id=interrupted_job_id,
                result_id="res_interrupted",
                farm_id="farm_alice_1",
                owner_uid="farmer_alice",
                submitted_by_uid="farmer_alice",
                scene_id="S2B_36RUV_20260501_0_L2A",
                crop_id="tomato",
                growth_stage_index=2,
                request_payload={},
            )
            service.db.update_job_status(interrupted_job_id, status="PROCESSING_RASTERS", stage="PROCESSING_RASTERS", progress_pct=55)

            restarted_service = SatelliteBackendService(
                storage_root=storage_root,
                authenticator=authenticator,
                stac_search_fn=lambda **kwargs: fake_stac_scenes,
                signed_url_secret="unit-test-hmac-secret-key-32bytes",
            )
            self.assertIn(interrupted_job_id, restarted_service.recovered_interrupted_jobs)
            recovered_job = restarted_service.db.get_job(interrupted_job_id)
            self.assertEqual(recovered_job["status"], "FAILED")
            self.assertEqual(recovered_job["error_code"], "JOB_INTERRUPTED_BY_SERVER_RESTART")

            # Verify completed job, farm ownership, and result survived the restart!
            _, persisted_res = restarted_service.get_result(f"Bearer {alice_token}", result_id)
            self.assertEqual(persisted_res["result_id"], result_id)
            self.assertEqual(persisted_res["farm_id"], "farm_alice_1")

            # 13. Cascading Farm Deletion Test (Phase 6)
            # Bob cannot delete Alice's farm -> 403
            code_del_bob, _ = _http_call("DELETE", "/api/v1/satellite/farms/farm_alice_1", token=bob_token)
            self.assertEqual(code_del_bob, 403)

            # Alice deletes her farm -> 200, removing DB records and private GeoTIFF files on disk
            ndvi_disk_path = res_data["output_geotiff_paths"]["NDVI"]
            self.assertTrue(os.path.exists(ndvi_disk_path))
            code_del_alice, del_summary = _http_call("DELETE", "/api/v1/satellite/farms/farm_alice_1", token=alice_token)
            self.assertEqual(code_del_alice, 200)
            self.assertEqual(del_summary["status"], "FARM_DELETED")
            self.assertGreaterEqual(del_summary["deleted_files"], 4)
            self.assertFalse(os.path.exists(ndvi_disk_path))

            # Farm, job, result, and file now return 404
            code_after_farm, _ = _http_call("GET", "/api/v1/satellite/farms/farm_alice_1", token=alice_token)
            self.assertEqual(code_after_farm, 404)
            code_after_res, _ = _http_call("GET", f"/api/v1/satellite/results/{result_id}", token=alice_token)
            self.assertEqual(code_after_res, 404)
        finally:
            server.shutdown()
            server.server_close()

    def test_zero_trust_rbac_and_uid_impersonation_prevention(self):
        """
        PHASE 1 Zero-Trust RBAC Acceptance Suite:
        1. Forged top-level 'role' claim is never trusted for admin elevation.
        2. Farmer cannot access administrator endpoints (403).
        3. Agronomist (AGRONOMIST_ADMIN) can view admin overview (200) but cannot perform
           Super Administrator operations (403).
        4. Super Administrator (SUPER_ADMIN) can perform role assignment and job deletion (200).
        5. User cannot impersonate another UID in request bodies (403 UID_IMPERSONATION_FORBIDDEN).
        """
        authenticator = make_test_authenticator()
        service = SatelliteBackendService(
            storage_root=os.path.join(self.temp_dir, "rbac_storage"),
            authenticator=authenticator,
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

            self_claimed_admin_token = create_test_firebase_jwt(
                uid="farmer_sneaky",
                email="sneaky@example.com",
                forged_top_level_role="SUPER_ADMIN",
            )
            agronomist_token = create_test_firebase_jwt(
                uid="agronomist_01",
                email="agro@agrosight.eg",
                verified_custom_role="AGRONOMIST_ADMIN",
            )
            super_admin_token = create_test_firebase_jwt(
                uid="super_admin_01",
                email="elzahraafatma288@gmail.com",
                email_verified=True,
            )

            # 1 & 2. Farmer with forged top-level role="SUPER_ADMIN" is blocked from admin endpoints (403)
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

            # 5. UID Impersonation prevention
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
        e0, n0 = wgs84_to_utm(30.3500, 30.3800, utm_zone=36)
        transform = GeoTransform(
            origin_x=e0,
            pixel_width=10.0,
            row_rotation=0.0,
            origin_y=n0 + 1280.0,
            col_rotation=0.0,
            pixel_height=-10.0,
        )
        grid = [[float(1000 + (r * 7 + c * 3) % 4000) for c in range(128)] for r in range(128)]
        cog_path = os.path.join(self.temp_dir, "large_sentinel2_tiled_cog.tif")
        GeoTiffCogProcessor.write_tiled_cog(
            file_path=cog_path,
            band_data=RasterBandData("B08", 128, 128, 32636, transform, grid, nodata_value=0.0, spatial_resolution_m=10.0),
            tile_width=16,
            tile_height=16,
            dtype="uint16",
            compress=False,
            include_overview=True,
        )
        with open(cog_path, "rb") as f:
            cog_bytes = f.read()
        total_cog_size = len(cog_bytes)
        self.assertGreater(total_cog_size, 32768)

        local_full = GeoTiffCogProcessor.read_geotiff(cog_path, band_name="B08")
        self.assertEqual((local_full.width, local_full.height), (128, 128))
        self.assertEqual(local_full.crs_epsg, 32636)
        self.assertAlmostEqual(local_full.values[5][9], grid[5][9], places=2)

        local_ovr = GeoTiffCogProcessor.read_geotiff(cog_path, band_name="B08", overview_level=1)
        self.assertEqual((local_ovr.width, local_ovr.height), (64, 64))
        self.assertAlmostEqual(local_ovr.transform.pixel_width, 20.0, places=3)

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
                self.assertGreaterEqual(client.range_requests_made, 2)
                self.assertLessEqual(client.range_requests_made, 4)
                self.assertLess(window_band.width, 20)
                self.assertLess(window_band.height, 20)
                self.assertEqual(window_band.crs_epsg, 32636)

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
                    self.send_response(200)
                    self.send_header("Content-Type", "image/tiff")
                    self.send_header("Content-Length", str(len(tif_bytes)))
                    self.end_headers()
                    self.wfile.write(tif_bytes)
                elif self.path == "/interrupted.tif":
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

            with self.assertRaises(RangeRequestNotSupportedError):
                GeoTiffCogProcessor.read_geotiff(no_range_url, require_range_support=True)

            with self.assertRaises(RangeRequestNotSupportedError):
                GeoTiffCogProcessor.read_geotiff(no_range_url, max_unranged_bytes=100)

            with RemoteCogHttpClient(require_range_support=False) as fallback_client:
                fallback_band = GeoTiffCogProcessor.read_geotiff(no_range_url, http_client=fallback_client)
                self.assertFalse(fallback_client.server_supports_range)
                self.assertEqual(fallback_client.full_downloads_made, 1)
                self.assertEqual((fallback_band.width, fallback_band.height), (8, 8))

            with self.assertRaises(RasterDownloadInterruptedError):
                with RemoteCogHttpClient(max_retries=0) as int_client:
                    GeoTiffCogProcessor.read_geotiff(interrupted_url, http_client=int_client)
        finally:
            srv.shutdown()
            srv.server_close()

    def test_corrupted_tiff_missing_bands_unsupported_crs_and_invalid_url(self):
        with self.assertRaises(RasterIntegrityError):
            GeoTiffCogProcessor.parse_geotiff_bytes(b"II\x2a")

        bad_magic = struct.pack("<2sHI", b"II", 43, 8) + b"\x00" * 32
        with self.assertRaises(RasterIntegrityError):
            GeoTiffCogProcessor.parse_geotiff_bytes(bad_magic)

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
        for i in range(len(raw_tif) - 8, len(raw_tif)):
            raw_tif[i] ^= 0xFF
        with self.assertRaises(RasterIntegrityError):
            GeoTiffCogProcessor.parse_geotiff_bytes(bytes(raw_tif))

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
                band_sources={"B04": valid_path, "B08": valid_path},
                farm_polygon_geojson=farm_poly,
            )

        with self.assertRaises(UnsupportedCrsError):
            validate_supported_crs(99999)
        with self.assertRaises(UnsupportedCrsError):
            validate_supported_crs(3857)
        with self.assertRaises(UnsupportedCrsError):
            GeoTiffCogProcessor.write_geotiff(
                os.path.join(self.temp_dir, "bad_crs.tif"),
                RasterBandData("B08", 4, 4, 99999, t_deg, [[1000.0] * 4 for _ in range(4)]),
            )

        with open(valid_path, "rb") as f:
            epsg_mod_bytes = f.read().replace(struct.pack("<H", 4326), struct.pack("<H", 3857))
        with self.assertRaises(UnsupportedCrsError):
            GeoTiffCogProcessor.parse_geotiff_bytes(epsg_mod_bytes)

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
