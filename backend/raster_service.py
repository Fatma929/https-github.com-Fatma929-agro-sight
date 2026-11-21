"""
AgroSight Sentinel-2 Level-2A Operational Raster Processing & Geospatial Engine
================================================================================
Production geospatial engine supporting:
1. Real Sentinel-2 L2A Cloud-Optimized GeoTIFF (COG) / GeoTIFF raster reading and writing
   (via `rasterio` + `numpy` when installed, plus a built-in RFC-compliant binary GeoTIFF
   parser/writer supporting ModelPixelScaleTag, ModelTiepointTag, GeoKeyDirectoryTag,
   DEFLATE/Uncompressed strips & tiles, and GDAL_NODATA so real `.tif` rasters are read
   and written in every environment).
2. Sentinel-2 L2A radiometric reflectance scaling AND Processing Baseline (PB 04.00+)
   BOA radiometric offset (`Reflectance = (DN + BOA_ADD_OFFSET) / QUANTIFICATION_VALUE`).
3. Multi-resolution band alignment: resampling 20m bands (B05, B06, B8A, B11, SCL)
   onto the 10m target grid (B02, B04, B08) using nearest-neighbor for categorical SCL
   and bilinear interpolation for continuous spectral reflectance bands while preserving
   the coordinate reference system (CRS) and affine geotransform.
4. Coordinate Reference System (CRS) transformations between WGS84 (EPSG:4326) and
   UTM zones (EPSG:32635 / EPSG:32636) using exact Transverse Mercator equations
   (and `pyproj` when available).
5. Exact farm GeoJSON polygon clipping (ray-casting point-in-polygon with holes support)
   so only pixels inside the actual farm boundary are analyzed.
6. Crop-specific and growth-stage-aware spectral anomaly thresholds (never assuming a single
   fixed threshold for all crops).
7. Connected-component pixel cluster vectorization to generate accurate GeoJSON anomaly
   polygons directly from actual anomalous raster pixels (never a fixed square around
   the farm center).
8. Georeferenced GeoTIFF raster output generation (`NDVI.tif`, `NDRE.tif`, `NDMI.tif`, `PSRI.tif`).
"""

from __future__ import annotations

import http.client
import json
import math
import os
import struct
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from dataclasses import dataclass, asdict, field
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

try:
    import numpy as np  # type: ignore
    HAS_NUMPY = True
except ImportError:
    np = None  # type: ignore
    HAS_NUMPY = False

try:
    import rasterio  # type: ignore
    from rasterio.transform import Affine  # type: ignore
    from rasterio.windows import from_bounds as rasterio_window_from_bounds  # type: ignore
    HAS_RASTERIO = True
except ImportError:
    rasterio = None  # type: ignore
    rasterio_window_from_bounds = None  # type: ignore
    HAS_RASTERIO = False

try:
    from shapely.geometry import Polygon as ShapelyPolygon, Point as ShapelyPoint, mapping as shapely_mapping  # type: ignore
    from shapely.ops import unary_union  # type: ignore
    HAS_SHAPELY = True
except ImportError:
    HAS_SHAPELY = False


class RasterAccessError(ValueError):
    """Base exception for local and remote COG/GeoTIFF raster access and validation failures."""
    def __init__(self, message: str, error_code: str = "RASTER_ACCESS_ERROR"):
        super().__init__(message)
        self.error_code = error_code


class RasterIntegrityError(RasterAccessError):
    """Raised when a GeoTIFF/COG has a corrupted header, malformed IFD, or invalid deflate stream."""
    def __init__(self, message: str):
        super().__init__(message, error_code="CORRUPTED_RASTER_TIFF")


class RasterDownloadInterruptedError(RasterAccessError):
    """Raised when an HTTP/COG stream terminates before delivering the expected byte range or Content-Length."""
    def __init__(self, message: str):
        super().__init__(message, error_code="DOWNLOAD_INTERRUPTED")


class RangeRequestNotSupportedError(RasterAccessError):
    """Raised when a remote COG server does not support HTTP Range requests (returns 200 instead of 206)."""
    def __init__(self, message: str):
        super().__init__(message, error_code="HTTP_RANGE_NOT_SUPPORTED")


class UnsupportedCrsError(RasterAccessError):
    """Raised when a raster has an unsupported, missing, or invalid Coordinate Reference System (EPSG)."""
    def __init__(self, message: str):
        super().__init__(message, error_code="UNSUPPORTED_RASTER_CRS")


class MissingBandError(RasterAccessError):
    """Raised when required Sentinel-2 spectral or SCL bands are missing."""
    def __init__(self, message: str):
        super().__init__(message, error_code="INCOMPLETE_BAND_ASSETS")


class InvalidAssetUrlError(RasterAccessError):
    """Raised when a remote raster asset URL is malformed, uses an unsupported scheme, or returns HTTP 404/4xx."""
    def __init__(self, message: str):
        super().__init__(message, error_code="INVALID_ASSET_URL")


# ESA Sentinel-2 L2A Scene Classification Layer (SCL) classes to mask:
# 0: No Data, 1: Saturated/Defective, 2: Dark Area/Cast Shadows, 3: Cloud Shadows,
# 8: Cloud Medium Probability, 9: Cloud High Probability, 10: Thin Cirrus, 11: Snow/Ice
MASKED_SCL_CLASSES: Set[int] = {0, 1, 3, 8, 9, 10, 11}
SHADOW_OR_CLOUD_SCL_CLASSES: Set[int] = {3, 8, 9, 10}
NODATA_OR_DEFECTIVE_SCL_CLASSES: Set[int] = {0, 1, 11}
EPSILON: float = 1e-6



@dataclass(frozen=True)
class GeoTransform:
    """
    Affine geotransform mapping pixel (col, row) to projected/geographic coordinates (x, y):
        x = origin_x + col * pixel_width + row * row_rotation
        y = origin_y + col * col_rotation + row * pixel_height
    Note: For north-up rasters, pixel_height is negative (e.g. -10.0 meters or -0.0001 deg).
    """
    origin_x: float
    pixel_width: float
    row_rotation: float
    origin_y: float
    col_rotation: float
    pixel_height: float

    def pixel_center_to_xy(self, row: int, col: int) -> Tuple[float, float]:
        c = col + 0.5
        r = row + 0.5
        x = self.origin_x + c * self.pixel_width + r * self.row_rotation
        y = self.origin_y + c * self.col_rotation + r * self.pixel_height
        return x, y

    def pixel_corners_xy(self, row: int, col: int) -> List[Tuple[float, float]]:
        """Return the 4 corner coordinates [(x0,y0), (x1,y0), (x1,y1), (x0,y1)] of pixel (row, col)."""
        corners = [
            (col, row),
            (col + 1, row),
            (col + 1, row + 1),
            (col, row + 1),
        ]
        return [
            (
                self.origin_x + c * self.pixel_width + r * self.row_rotation,
                self.origin_y + c * self.col_rotation + r * self.pixel_height,
            )
            for c, r in corners
        ]

    def xy_to_fractional_pixel(self, x: float, y: float) -> Tuple[float, float]:
        """Convert coordinate (x, y) to fractional (row, col) for north-up rasters."""
        col = (x - self.origin_x) / self.pixel_width
        row = (y - self.origin_y) / self.pixel_height
        return row, col


@dataclass
class RasterBandData:
    """Georeferenced 2D raster band with CRS, affine GeoTransform, and NoData metadata."""
    band_name: str
    width: int
    height: int
    crs_epsg: int
    transform: GeoTransform
    values: List[List[float]]  # 2D grid [row][col]
    nodata_value: Optional[float] = 0.0
    spatial_resolution_m: float = 10.0
    scale_factor: float = 0.0001
    boa_add_offset: float = 0.0


@dataclass(frozen=True)
class CropSpectralThresholds:
    """
    Crop-specific spectral thresholds so anomaly detection is calibrated to the crop's
    canopy architecture (e.g., dense potato/tomato canopy vs sparse desert date palm/citrus).
    """
    crop_id: str
    healthy_ndvi_min: float
    stressed_ndvi_threshold: float
    healthy_ndre_min: float
    stressed_ndre_threshold: float
    senescence_psri_threshold: float
    water_stress_ndmi_threshold: float
    waterlogging_ndmi_threshold: float


CROP_SPECTRAL_THRESHOLDS: Dict[str, CropSpectralThresholds] = {
    "tomato": CropSpectralThresholds("tomato", 0.62, 0.45, 0.34, 0.24, 0.14, 0.18, 0.48),
    "potato": CropSpectralThresholds("potato", 0.65, 0.48, 0.36, 0.25, 0.13, 0.20, 0.46),
    "wheat": CropSpectralThresholds("wheat", 0.58, 0.40, 0.32, 0.22, 0.16, 0.14, 0.45),
    "corn": CropSpectralThresholds("corn", 0.64, 0.46, 0.35, 0.24, 0.14, 0.18, 0.47),
    "citrus": CropSpectralThresholds("citrus", 0.52, 0.36, 0.28, 0.19, 0.17, 0.12, 0.44),
    "date_palm": CropSpectralThresholds("date_palm", 0.42, 0.28, 0.23, 0.15, 0.20, 0.08, 0.40),
    "strawberry": CropSpectralThresholds("strawberry", 0.56, 0.40, 0.30, 0.21, 0.14, 0.18, 0.45),
    "sugar_beet": CropSpectralThresholds("sugar_beet", 0.60, 0.43, 0.33, 0.23, 0.15, 0.16, 0.46),
}


def get_crop_thresholds(crop_id: str, growth_stage_index: int = 2) -> CropSpectralThresholds:
    """
    Return crop-specific spectral thresholds adjusted for phenological growth stage:
    - Stage 0 (Initial emergence): lower expected NDVI/NDRE canopy closure
    - Stage 1 (Vegetative development): moderate canopy closure
    - Stage 2 (Mid-season flowering/fruiting): peak canopy reference
    - Stage 3 (Late-season maturation): natural senescence allowed (higher PSRI tolerance)
    """
    key = crop_id.strip().lower()
    arabic_to_id = {
        "طماطم": "tomato",
        "بطاطس": "potato",
        "قمح": "wheat",
        "ذرة": "corn",
        "موالح": "citrus",
        "نخيل": "date_palm",
        "فراولة": "strawberry",
        "بنجر سكر": "sugar_beet",
    }
    resolved_key = arabic_to_id.get(key, key)
    base = CROP_SPECTRAL_THRESHOLDS.get(resolved_key, CROP_SPECTRAL_THRESHOLDS["tomato"])

    if growth_stage_index == 0:
        return CropSpectralThresholds(
            crop_id=base.crop_id,
            healthy_ndvi_min=max(0.25, base.healthy_ndvi_min - 0.22),
            stressed_ndvi_threshold=max(0.18, base.stressed_ndvi_threshold - 0.18),
            healthy_ndre_min=max(0.16, base.healthy_ndre_min - 0.12),
            stressed_ndre_threshold=max(0.12, base.stressed_ndre_threshold - 0.09),
            senescence_psri_threshold=base.senescence_psri_threshold + 0.05,
            water_stress_ndmi_threshold=base.water_stress_ndmi_threshold - 0.08,
            waterlogging_ndmi_threshold=base.waterlogging_ndmi_threshold,
        )
    if growth_stage_index == 3:
        return CropSpectralThresholds(
            crop_id=base.crop_id,
            healthy_ndvi_min=max(0.32, base.healthy_ndvi_min - 0.14),
            stressed_ndvi_threshold=max(0.24, base.stressed_ndvi_threshold - 0.10),
            healthy_ndre_min=max(0.18, base.healthy_ndre_min - 0.10),
            stressed_ndre_threshold=max(0.14, base.stressed_ndre_threshold - 0.07),
            senescence_psri_threshold=base.senescence_psri_threshold + 0.08,
            water_stress_ndmi_threshold=base.water_stress_ndmi_threshold - 0.06,
            waterlogging_ndmi_threshold=base.waterlogging_ndmi_threshold,
        )
    return base


# ==============================================================================
# CRS Transformation (WGS84 EPSG:4326 <-> UTM EPSG:326xx)
# ==============================================================================

def wgs84_to_utm(lon_deg: float, lat_deg: float, utm_zone: int = 36) -> Tuple[float, float]:
    """
    Convert WGS84 (longitude, latitude) in degrees to UTM (easting, northing) in meters
    for Northern Hemisphere (EPSG:32600 + utm_zone).
    """
    a = 6378137.0
    f = 1.0 / 298.257223563
    k0 = 0.9996
    e2 = 2 * f - f * f
    ep2 = e2 / (1.0 - e2)

    lat_rad = math.radians(lat_deg)
    lon_rad = math.radians(lon_deg)
    lon0_rad = math.radians((utm_zone - 1) * 6 - 180 + 3)

    sin_lat = math.sin(lat_rad)
    cos_lat = math.cos(lat_rad)
    tan_lat = math.tan(lat_rad)

    n = a / math.sqrt(1.0 - e2 * sin_lat * sin_lat)
    t = tan_lat * tan_lat
    c = ep2 * cos_lat * cos_lat
    a_term = cos_lat * (lon_rad - lon0_rad)

    m = a * (
        (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * (e2 ** 3) / 256.0) * lat_rad
        - (3.0 * e2 / 8.0 + 3.0 * e2 * e2 / 32.0 + 45.0 * (e2 ** 3) / 1024.0) * math.sin(2.0 * lat_rad)
        + (15.0 * e2 * e2 / 256.0 + 45.0 * (e2 ** 3) / 1024.0) * math.sin(4.0 * lat_rad)
        - (35.0 * (e2 ** 3) / 3072.0) * math.sin(6.0 * lat_rad)
    )

    easting = k0 * n * (
        a_term
        + (1.0 - t + c) * (a_term ** 3) / 6.0
        + (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * (a_term ** 5) / 120.0
    ) + 500000.0

    northing = k0 * (
        m + n * tan_lat * (
            (a_term ** 2) / 2.0
            + (5.0 - t + 9.0 * c + 4.0 * c * c) * (a_term ** 4) / 24.0
            + (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * (a_term ** 6) / 720.0
        )
    )
    return easting, northing


def utm_to_wgs84(easting: float, northing: float, utm_zone: int = 36) -> Tuple[float, float]:
    """
    Convert Northern Hemisphere UTM (easting, northing) in meters (EPSG:32600 + utm_zone)
    back to WGS84 (longitude, latitude) in decimal degrees.
    """
    a = 6378137.0
    f = 1.0 / 298.257223563
    k0 = 0.9996
    e2 = 2 * f - f * f
    ep2 = e2 / (1.0 - e2)
    e1 = (1.0 - math.sqrt(1.0 - e2)) / (1.0 + math.sqrt(1.0 - e2))

    x = easting - 500000.0
    y = northing
    lon0_rad = math.radians((utm_zone - 1) * 6 - 180 + 3)

    m = y / k0
    mu = m / (a * (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * (e2 ** 3) / 256.0))

    phi1 = (
        mu
        + (3.0 * e1 / 2.0 - 27.0 * (e1 ** 3) / 32.0) * math.sin(2.0 * mu)
        + (21.0 * e1 * e1 / 16.0 - 55.0 * (e1 ** 4) / 32.0) * math.sin(4.0 * mu)
        + (151.0 * (e1 ** 3) / 96.0) * math.sin(6.0 * mu)
    )

    sin_phi1 = math.sin(phi1)
    cos_phi1 = math.cos(phi1)
    tan_phi1 = math.tan(phi1)

    n1 = a / math.sqrt(1.0 - e2 * sin_phi1 * sin_phi1)
    t1 = tan_phi1 * tan_phi1
    c1 = ep2 * cos_phi1 * cos_phi1
    r1 = a * (1.0 - e2) / ((1.0 - e2 * sin_phi1 * sin_phi1) ** 1.5)
    d = x / (n1 * k0)

    lat_rad = phi1 - (n1 * tan_phi1 / r1) * (
        (d ** 2) / 2.0
        - (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * ep2) * (d ** 4) / 24.0
        + (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * ep2 - 3.0 * c1 * c1) * (d ** 6) / 720.0
    )

    lon_rad = lon0_rad + (
        d
        - (1.0 + 2.0 * t1 + c1) * (d ** 3) / 6.0
        + (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * ep2 + 24.0 * t1 * t1) * (d ** 5) / 120.0
    ) / cos_phi1

    return math.degrees(lon_rad), math.degrees(lat_rad)


def validate_supported_crs(crs_epsg: Any) -> int:
    """
    Validate that the raster Coordinate Reference System (EPSG code) is supported.
    Supported CRS codes:
    - EPSG:4326 (WGS 84 geographic)
    - EPSG:32601 .. EPSG:32660 (WGS 84 / UTM Northern Hemisphere zones 1N-60N)
    - EPSG:32701 .. EPSG:32760 (WGS 84 / UTM Southern Hemisphere zones 1S-60S)
    """
    try:
        code = int(crs_epsg)
    except (TypeError, ValueError) as exc:
        raise UnsupportedCrsError(f"Invalid non-integer CRS EPSG code: {crs_epsg!r}") from exc

    if code == 4326 or (32601 <= code <= 32660) or (32701 <= code <= 32760):
        return code
    raise UnsupportedCrsError(
        f"Unsupported Coordinate Reference System EPSG:{code}. "
        "Supported CRS codes are EPSG:4326, EPSG:32601-32660 (UTM North), and EPSG:32701-32760 (UTM South)."
    )


def coordinate_to_wgs84(x: float, y: float, crs_epsg: int) -> Tuple[float, float]:
    """Transform (x, y) from raster CRS (`EPSG:4326`, `EPSG:326xx`, or `EPSG:327xx`) to WGS84 `(lon, lat)`."""
    validate_supported_crs(crs_epsg)
    if crs_epsg == 4326:
        return x, y
    if 32601 <= crs_epsg <= 32660:
        zone = crs_epsg - 32600
        return utm_to_wgs84(x, y, utm_zone=zone)
    if 32701 <= crs_epsg <= 32760:
        zone = crs_epsg - 32700
        return utm_to_wgs84(x, y - 10000000.0, utm_zone=zone)
    raise UnsupportedCrsError(f"Unsupported CRS EPSG:{crs_epsg}")


def wgs84_to_raster_crs(lon: float, lat: float, crs_epsg: int) -> Tuple[float, float]:
    """Transform WGS84 `(lon, lat)` to raster CRS `(x, y)`."""
    validate_supported_crs(crs_epsg)
    if crs_epsg == 4326:
        return lon, lat
    if 32601 <= crs_epsg <= 32660:
        zone = crs_epsg - 32600
        return wgs84_to_utm(lon, lat, utm_zone=zone)
    if 32701 <= crs_epsg <= 32760:
        zone = crs_epsg - 32700
        easting, northing = wgs84_to_utm(lon, lat, utm_zone=zone)
        return easting, northing + 10000000.0
    raise UnsupportedCrsError(f"Unsupported CRS EPSG:{crs_epsg}")


def extract_geojson_wgs84_bounds(polygon_geojson: Dict[str, Any]) -> Tuple[float, float, float, float]:
    """Extract `(min_lon, min_lat, max_lon, max_lat)` bounding box from a GeoJSON Polygon/Feature."""
    gtype = polygon_geojson.get("type")
    if gtype == "Feature":
        return extract_geojson_wgs84_bounds(polygon_geojson.get("geometry") or {})
    if gtype == "FeatureCollection":
        features = polygon_geojson.get("features") or []
        if not features:
            raise ValueError("Empty FeatureCollection has no bounding box")
        boxes = [extract_geojson_wgs84_bounds(f) for f in features]
        return (
            min(b[0] for b in boxes),
            min(b[1] for b in boxes),
            max(b[2] for b in boxes),
            max(b[3] for b in boxes),
        )
    coords = polygon_geojson.get("coordinates") or []
    pts: List[Tuple[float, float]] = []
    if gtype == "Polygon" and coords:
        for pt in coords[0]:
            pts.append((float(pt[0]), float(pt[1])))
    elif gtype == "MultiPolygon" and coords:
        for poly in coords:
            if poly:
                for pt in poly[0]:
                    pts.append((float(pt[0]), float(pt[1])))
    if not pts:
        raise ValueError(f"Could not extract coordinates from GeoJSON of type {gtype!r}")
    lons = [p[0] for p in pts]
    lats = [p[1] for p in pts]
    return min(lons), min(lats), max(lons), max(lats)


# ==============================================================================
# Remote Cloud-Optimized GeoTIFF (COG) HTTP Range Client with Connection Pooling
# ==============================================================================

class RemoteCogHttpClient:
    """
    Production HTTP/HTTPS client for remote Cloud-Optimized GeoTIFF (COG) access:
    - Validates asset URLs (scheme, hostname, port, path).
    - Pools HTTP/HTTPS connections by `(scheme, host, port)` for low-latency multi-tile Range requests.
    - Executes targeted `Range: bytes=start-end` requests and verifies `206 Partial Content` and `Content-Range`.
    - Detects servers that do not support HTTP Range requests (`200 OK` instead of `206 Partial Content`).
    - Detects interrupted or truncated transfers where delivered bytes are fewer than promised.
    - Retries transient socket/timeout errors with exponential backoff and deterministic resource cleanup.
    """

    def __init__(
        self,
        timeout_sec: float = 10.0,
        max_retries: int = 2,
        backoff_base_sec: float = 0.05,
        require_range_support: bool = False,
        max_unranged_bytes: int = 8 * 1024 * 1024,
    ):
        self.timeout_sec = float(timeout_sec)
        self.max_retries = max(0, int(max_retries))
        self.backoff_base_sec = float(backoff_base_sec)
        self.require_range_support = bool(require_range_support)
        self.max_unranged_bytes = int(max_unranged_bytes)

        self._lock = threading.Lock()
        self._pool: Dict[Tuple[str, str, int], http.client.HTTPConnection] = {}
        self._unranged_body_cache: Dict[str, bytes] = {}
        self._remote_size_cache: Dict[str, int] = {}

        # Telemetry counters for auditing and testing
        self.range_requests_made: int = 0
        self.full_downloads_made: int = 0
        self.bytes_downloaded: int = 0
        self.connections_opened: int = 0
        self.fetched_ranges: List[Tuple[int, int]] = []
        self.server_supports_range: Optional[bool] = None
        self.closed: bool = False

    @staticmethod
    def validate_url(url: str) -> urllib.parse.ParseResult:
        if not isinstance(url, str) or not url.strip():
            raise InvalidAssetUrlError("Remote raster asset URL must be a non-empty string")
        if any( ord(ch) < 32 or ch.isspace() for ch in url ):
            raise InvalidAssetUrlError(f"Remote raster asset URL contains invalid whitespace/control characters: {url!r}")
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme not in ("http", "https"):
            raise InvalidAssetUrlError(f"Unsupported URL scheme {parsed.scheme!r} in {url!r} (expected http or https)")
        if not parsed.hostname or "." not in parsed.hostname and parsed.hostname not in ("localhost",):
            raise InvalidAssetUrlError(f"Invalid or missing hostname in asset URL: {url!r}")
        try:
            _ = parsed.port
        except ValueError as exc:
            raise InvalidAssetUrlError(f"Invalid port in asset URL {url!r}: {exc}") from exc
        if not parsed.path or parsed.path == "/":
            raise InvalidAssetUrlError(f"Asset URL is missing a valid raster file path: {url!r}")
        return parsed

    def _acquire_connection(self, parsed: urllib.parse.ParseResult) -> Tuple[Tuple[str, str, int], http.client.HTTPConnection]:
        scheme = parsed.scheme
        host = parsed.hostname or ""
        port = parsed.port or (443 if scheme == "https" else 80)
        key = (scheme, host, port)
        with self._lock:
            conn = self._pool.pop(key, None)
        if conn is None:
            if scheme == "https":
                conn = http.client.HTTPSConnection(host, port, timeout=self.timeout_sec)
            else:
                conn = http.client.HTTPConnection(host, port, timeout=self.timeout_sec)
            with self._lock:
                self.connections_opened += 1
        return key, conn

    def _release_connection(self, key: Tuple[str, str, int], conn: http.client.HTTPConnection) -> None:
        with self._lock:
            if self.closed:
                try:
                    conn.close()
                except Exception:
                    pass
                return
            self._pool[key] = conn

    def fetch_range(self, url: str, start_byte: int, end_byte: int) -> bytes:
        """
        Fetch inclusive byte range `[start_byte, end_byte]` from a remote COG URL.
        """
        if start_byte < 0 or end_byte < start_byte:
            raise ValueError(f"Invalid byte range [{start_byte}, {end_byte}]")

        parsed = self.validate_url(url)

        # If a non-Range server fallback already cached the full validated file, slice from cache
        with self._lock:
            if url in self._unranged_body_cache:
                cached = self._unranged_body_cache[url]
                if start_byte >= len(cached):
                    raise RasterIntegrityError(
                        f"Requested byte offset {start_byte} exceeds remote file size {len(cached)}"
                    )
                return cached[start_byte : min(len(cached), end_byte + 1)]

        path_with_query = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        last_error: Optional[Exception] = None

        for attempt in range(self.max_retries + 1):
            key, conn = self._acquire_connection(parsed)
            try:
                headers = {
                    "User-Agent": "AgroSight-Sentinel2-COGEngine/3.0",
                    "Range": f"bytes={start_byte}-{end_byte}",
                    "Connection": "keep-alive",
                }
                conn.request("GET", path_with_query, headers=headers)
                resp = conn.getresponse()
                status = resp.status
                content_length_hdr = resp.getheader("Content-Length")
                content_range_hdr = resp.getheader("Content-Range")
                raw_body = resp.read()

                if status in (400, 401, 403, 404, 410):
                    conn.close()
                    raise InvalidAssetUrlError(
                        f"Remote raster asset URL {url!r} returned HTTP {status}"
                    )
                if status == 416:
                    conn.close()
                    raise RasterIntegrityError(
                        f"Requested range bytes={start_byte}-{end_byte} is out of bounds (HTTP 416) for {url!r}"
                    )
                if status >= 500:
                    conn.close()
                    raise RasterDownloadInterruptedError(
                        f"Remote raster server returned HTTP {status} for {url!r}"
                    )

                # Verify that the response body was not truncated mid-stream
                if content_length_hdr is not None:
                    expected_len = int(content_length_hdr)
                    if len(raw_body) < expected_len:
                        conn.close()
                        raise RasterDownloadInterruptedError(
                            f"Interrupted download from {url!r}: received {len(raw_body)} of {expected_len} bytes"
                        )

                if status == 206:
                    # Server supports HTTP Range requests
                    expected_span = end_byte - start_byte + 1
                    if content_range_hdr:
                        # Format: bytes start-end/total
                        try:
                            range_part = content_range_hdr.strip().split(" ")[-1]
                            span_str, total_str = range_part.split("/")
                            r_start_s, r_end_s = span_str.split("-")
                            r_start, r_end = int(r_start_s), int(r_end_s)
                            expected_span = r_end - r_start + 1
                            if total_str != "*":
                                total_size = int(total_str)
                                with self._lock:
                                    self._remote_size_cache[url] = total_size
                        except Exception as exc:
                            conn.close()
                            raise RasterIntegrityError(
                                f"Malformed Content-Range header {content_range_hdr!r} from {url!r}"
                            ) from exc

                    if len(raw_body) < expected_span:
                        # Unless clamped by total known file size, fewer bytes means interrupted stream
                        known_total = self._remote_size_cache.get(url)
                        if known_total is None or (start_byte + len(raw_body) < min(known_total, end_byte + 1)):
                            conn.close()
                            raise RasterDownloadInterruptedError(
                                f"Interrupted partial download from {url!r}: received {len(raw_body)} bytes, "
                                f"expected {expected_span} bytes for range {start_byte}-{end_byte}"
                            )

                    with self._lock:
                        self.server_supports_range = True
                        self.range_requests_made += 1
                        self.bytes_downloaded += len(raw_body)
                        self.fetched_ranges.append((start_byte, end_byte))
                    self._release_connection(key, conn)
                    return raw_body

                if status == 200:
                    # Server ignored Range header and sent full response
                    with self._lock:
                        self.server_supports_range = False
                    if self.require_range_support:
                        conn.close()
                        raise RangeRequestNotSupportedError(
                            f"Remote server for {url!r} returned HTTP 200 OK instead of 206 Partial Content; "
                            "HTTP Range requests are required for Cloud-Optimized GeoTIFF streaming."
                        )
                    if len(raw_body) > self.max_unranged_bytes:
                        conn.close()
                        raise RangeRequestNotSupportedError(
                            f"Remote server for {url!r} does not support HTTP Range requests and full payload "
                            f"({len(raw_body)} bytes) exceeds max_unranged_bytes ({self.max_unranged_bytes})"
                        )
                    if len(raw_body) == 0:
                        conn.close()
                        raise RasterDownloadInterruptedError(f"Empty HTTP 200 response from {url!r}")

                    with self._lock:
                        self.full_downloads_made += 1
                        self.bytes_downloaded += len(raw_body)
                        self._unranged_body_cache[url] = raw_body
                        self._remote_size_cache[url] = len(raw_body)
                    self._release_connection(key, conn)
                    if start_byte >= len(raw_body):
                        raise RasterIntegrityError(
                            f"Requested byte offset {start_byte} exceeds full file size {len(raw_body)}"
                        )
                    return raw_body[start_byte : min(len(raw_body), end_byte + 1)]

                conn.close()
                raise RasterAccessError(f"Unexpected HTTP status {status} from {url!r}")

            except (
                InvalidAssetUrlError,
                RangeRequestNotSupportedError,
                RasterIntegrityError,
            ):
                raise
            except (RasterDownloadInterruptedError, http.client.IncompleteRead, TimeoutError) as exc:
                if not isinstance(exc, RasterDownloadInterruptedError):
                    exc = RasterDownloadInterruptedError(f"Interrupted download from {url!r}: {exc}")
                last_error = exc
                try:
                    conn.close()
                except Exception:
                    pass
                if attempt < self.max_retries:
                    time.sleep(self.backoff_base_sec * (2 ** attempt))
                else:
                    raise exc
            except Exception as exc:
                last_error = exc
                try:
                    conn.close()
                except Exception:
                    pass
                if attempt < self.max_retries:
                    time.sleep(self.backoff_base_sec * (2 ** attempt))
                else:
                    raise InvalidAssetUrlError(f"Failed to connect to remote raster URL {url!r}: {exc}") from exc

        raise RasterDownloadInterruptedError(f"Exhausted retries fetching range from {url!r}: {last_error}")

    def close(self) -> None:
        """Deterministically close all pooled HTTP/HTTPS connections."""
        with self._lock:
            self.closed = True
            conns = list(self._pool.values())
            self._pool.clear()
        for c in conns:
            try:
                c.close()
            except Exception:
                pass

    def __enter__(self) -> "RemoteCogHttpClient":
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        self.close()


# ==============================================================================
# Binary GeoTIFF / Cloud-Optimized GeoTIFF (COG) Reader & Writer
# ==============================================================================

class GeoTiffCogProcessor:
    """
    Reads and writes georeferenced GeoTIFF / Cloud-Optimized GeoTIFF (COG) files.
    Supports:
    - Local GeoTIFF and tiled COG files.
    - Remote Cloud-Optimized GeoTIFFs accessed via HTTP Range requests (`RemoteCogHttpClient`)
      fetching ONLY the TIFF header/IFD directory and the internal tiles intersecting the target
      farm polygon or spatial window.
    - Overview IFDs (reduced-resolution pyramids) in multi-IFD COGs.
    - Strict validation of TIFF integrity, dimensions, CRS, GeoTransform, NoData, and band types.
    """

    TAG_NEW_SUBFILE_TYPE = 254
    TAG_IMAGE_WIDTH = 256
    TAG_IMAGE_LENGTH = 257
    TAG_BITS_PER_SAMPLE = 258
    TAG_COMPRESSION = 259
    TAG_PHOTOMETRIC = 262
    TAG_STRIP_OFFSETS = 273
    TAG_SAMPLES_PER_PIXEL = 277
    TAG_ROWS_PER_STRIP = 278
    TAG_STRIP_BYTE_COUNTS = 279
    TAG_SAMPLE_FORMAT = 339
    TAG_TILE_WIDTH = 322
    TAG_TILE_LENGTH = 323
    TAG_TILE_OFFSETS = 324
    TAG_TILE_BYTE_COUNTS = 325
    TAG_MODEL_PIXEL_SCALE = 33550
    TAG_MODEL_TIEPOINT = 33922
    TAG_GEO_KEY_DIRECTORY = 34735
    TAG_GDAL_NODATA = 42113

    INITIAL_HEADER_RANGE_BYTES = 16384

    TYPE_SIZES: Dict[int, Tuple[int, str]] = {
        1: (1, "B"),   # BYTE
        2: (1, "s"),   # ASCII
        3: (2, "H"),   # SHORT
        4: (4, "I"),   # LONG
        5: (8, "II"),  # RATIONAL
        11: (4, "f"),  # FLOAT
        12: (8, "d"),  # DOUBLE
    }

    @classmethod
    def write_geotiff(
        cls,
        file_path: str,
        band_data: RasterBandData,
        dtype: str = "float32",
        compress: bool = True,
    ) -> str:
        """
        Write a georeferenced single-band GeoTIFF file preserving CRS (EPSG),
        ModelPixelScaleTag, ModelTiepointTag, and GDAL_NODATA.
        """
        validate_supported_crs(band_data.crs_epsg)
        os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
        height = band_data.height
        width = band_data.width
        if width <= 0 or height <= 0:
            raise RasterIntegrityError(f"Cannot write GeoTIFF with non-positive dimensions {width}x{height}")

        if HAS_RASTERIO and HAS_NUMPY:
            arr = np.array(band_data.values, dtype=np.float32 if dtype == "float32" else np.uint16)
            transform = Affine(
                band_data.transform.pixel_width,
                band_data.transform.row_rotation,
                band_data.transform.origin_x,
                band_data.transform.col_rotation,
                band_data.transform.pixel_height,
                band_data.transform.origin_y,
            )
            with rasterio.open(
                file_path,
                "w",
                driver="GTiff",
                height=height,
                width=width,
                count=1,
                dtype=arr.dtype,
                crs=f"EPSG:{band_data.crs_epsg}",
                transform=transform,
                nodata=band_data.nodata_value,
            ) as dst:
                dst.write(arr, 1)
            return file_path

        is_float = dtype == "float32"
        bits_per_sample = 32 if is_float else 16
        sample_format = 3 if is_float else 1

        raw_pixels = bytearray()
        for r in range(height):
            for c in range(width):
                val = band_data.values[r][c]
                if is_float:
                    raw_pixels.extend(struct.pack("<f", float(val)))
                else:
                    ival = 0 if math.isnan(val) else max(0, min(65535, int(round(val))))
                    raw_pixels.extend(struct.pack("<H", ival))

        if compress:
            pixel_payload = zlib.compress(bytes(raw_pixels))
            compression_code = 8
        else:
            pixel_payload = bytes(raw_pixels)
            compression_code = 1

        pixel_scale = struct.pack(
            "<3d",
            float(abs(band_data.transform.pixel_width)),
            float(abs(band_data.transform.pixel_height)),
            0.0,
        )
        tiepoint = struct.pack(
            "<6d",
            0.0,
            0.0,
            0.0,
            float(band_data.transform.origin_x),
            float(band_data.transform.origin_y),
            0.0,
        )
        gt_model_type = 2 if band_data.crs_epsg == 4326 else 1
        crs_key_id = 2048 if band_data.crs_epsg == 4326 else 3072
        geo_keys = struct.pack(
            "<16H",
            1, 1, 0, 3,
            1024, 0, 1, gt_model_type,
            1025, 0, 1, 1,
            crs_key_id, 0, 1, int(band_data.crs_epsg),
        )
        nodata_str = (str(band_data.nodata_value if band_data.nodata_value is not None else "nan") + "\x00").encode("ascii")

        header_size = 8
        strip_offset = header_size
        strip_byte_count = len(pixel_payload)

        extra_offset = strip_offset + strip_byte_count
        if extra_offset % 2 == 1:
            pixel_payload += b"\x00"
            extra_offset += 1

        scale_offset = extra_offset
        tiepoint_offset = scale_offset + len(pixel_scale)
        geokey_offset = tiepoint_offset + len(tiepoint)
        nodata_offset = geokey_offset + len(geo_keys)
        ifd_offset = nodata_offset + len(nodata_str)
        if ifd_offset % 2 == 1:
            nodata_str += b"\x00"
            ifd_offset += 1

        entries = [
            (cls.TAG_IMAGE_WIDTH, 4, 1, width),
            (cls.TAG_IMAGE_LENGTH, 4, 1, height),
            (cls.TAG_BITS_PER_SAMPLE, 3, 1, bits_per_sample),
            (cls.TAG_COMPRESSION, 3, 1, compression_code),
            (cls.TAG_PHOTOMETRIC, 3, 1, 1),
            (cls.TAG_STRIP_OFFSETS, 4, 1, strip_offset),
            (cls.TAG_SAMPLES_PER_PIXEL, 3, 1, 1),
            (cls.TAG_ROWS_PER_STRIP, 4, 1, height),
            (cls.TAG_STRIP_BYTE_COUNTS, 4, 1, strip_byte_count),
            (cls.TAG_SAMPLE_FORMAT, 3, 1, sample_format),
            (cls.TAG_MODEL_PIXEL_SCALE, 12, 3, scale_offset),
            (cls.TAG_MODEL_TIEPOINT, 12, 6, tiepoint_offset),
            (cls.TAG_GEO_KEY_DIRECTORY, 3, 16, geokey_offset),
            (cls.TAG_GDAL_NODATA, 2, len(nodata_str), nodata_offset),
        ]
        entries.sort(key=lambda e: e[0])

        ifd_bytes = bytearray(struct.pack("<H", len(entries)))
        for tag, ttype, count, val_or_off in entries:
            ifd_bytes.extend(struct.pack("<HHII", tag, ttype, count, val_or_off))
        ifd_bytes.extend(struct.pack("<I", 0))

        header = struct.pack("<2sHI", b"II", 42, ifd_offset)

        with open(file_path, "wb") as f:
            f.write(header)
            f.write(pixel_payload)
            f.write(pixel_scale)
            f.write(tiepoint)
            f.write(geo_keys)
            f.write(nodata_str)
            f.write(ifd_bytes)

        return file_path

    @classmethod
    def write_tiled_cog(
        cls,
        file_path: str,
        band_data: RasterBandData,
        tile_width: int = 16,
        tile_height: int = 16,
        dtype: str = "uint16",
        compress: bool = True,
        include_overview: bool = True,
    ) -> str:
        """
        Write a true Cloud-Optimized GeoTIFF (COG) with a header-first layout:
        - Bytes 0..8: Little-Endian TIFF Header pointing to IFD 0 at byte 8.
        - IFD 0 (Full-resolution tiled image) + optional IFD 1 (2x downsampled overview pyramid)
          and all tag array blocks placed at the start of the file before any pixel tiles.
        - Internal compressed/uncompressed tiles placed after the IFD metadata block so remote
          HTTP Range clients can fetch the first 16 KB to inspect all tile offsets and then fetch
          ONLY the individual tiles intersecting a farm polygon.
        """
        validate_supported_crs(band_data.crs_epsg)
        os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
        height = band_data.height
        width = band_data.width
        if width <= 0 or height <= 0:
            raise RasterIntegrityError(f"Invalid raster dimensions {width}x{height}")

        is_float = dtype == "float32"
        bits_per_sample = 32 if is_float else 16
        sample_format = 3 if is_float else 1
        compression_code = 8 if compress else 1

        def _encode_tiles(grid: List[List[float]], w: int, h: int, tw: int, th: int) -> List[bytes]:
            tiles_x = (w + tw - 1) // tw
            tiles_y = (h + th - 1) // th
            encoded_list: List[bytes] = []
            for ty in range(tiles_y):
                for tx in range(tiles_x):
                    buf = bytearray()
                    r0 = ty * th
                    c0 = tx * tw
                    for tr in range(th):
                        gr = r0 + tr
                        for tc in range(tw):
                            gc = c0 + tc
                            if gr < h and gc < w:
                                val = grid[gr][gc]
                            else:
                                val = float(band_data.nodata_value if band_data.nodata_value is not None else 0.0)
                            if is_float:
                                buf.extend(struct.pack("<f", float(val)))
                            else:
                                ival = 0 if math.isnan(val) else max(0, min(65535, int(round(val))))
                                buf.extend(struct.pack("<H", ival))
                    raw_tile = bytes(buf)
                    encoded_list.append(zlib.compress(raw_tile) if compress else raw_tile)
            return encoded_list

        full_tiles = _encode_tiles(band_data.values, width, height, tile_width, tile_height)
        num_full_tiles = len(full_tiles)

        # Build 2x overview grid if requested and dimensions >= 4
        has_ovr = include_overview and width >= 4 and height >= 4
        ovr_w = max(1, (width + 1) // 2)
        ovr_h = max(1, (height + 1) // 2)
        ovr_tw = max(4, min(tile_width, ovr_w))
        ovr_th = max(4, min(tile_height, ovr_h))
        ovr_tiles: List[bytes] = []
        if has_ovr:
            ovr_grid: List[List[float]] = [[0.0 for _ in range(ovr_w)] for _ in range(ovr_h)]
            for r in range(ovr_h):
                for c in range(ovr_w):
                    samples = []
                    for dr in (0, 1):
                        for dc in (0, 1):
                            sr = min(height - 1, r * 2 + dr)
                            sc = min(width - 1, c * 2 + dc)
                            v = band_data.values[sr][sc]
                            if not math.isnan(v):
                                samples.append(v)
                    ovr_grid[r][c] = sum(samples) / len(samples) if samples else 0.0
            ovr_tiles = _encode_tiles(ovr_grid, ovr_w, ovr_h, ovr_tw, ovr_th)

        pixel_scale = struct.pack(
            "<3d",
            float(abs(band_data.transform.pixel_width)),
            float(abs(band_data.transform.pixel_height)),
            0.0,
        )
        tiepoint = struct.pack(
            "<6d",
            0.0,
            0.0,
            0.0,
            float(band_data.transform.origin_x),
            float(band_data.transform.origin_y),
            0.0,
        )
        gt_model_type = 2 if band_data.crs_epsg == 4326 else 1
        crs_key_id = 2048 if band_data.crs_epsg == 4326 else 3072
        geo_keys = struct.pack(
            "<16H",
            1, 1, 0, 3,
            1024, 0, 1, gt_model_type,
            1025, 0, 1, 1,
            crs_key_id, 0, 1, int(band_data.crs_epsg),
        )
        nodata_str = (str(band_data.nodata_value if band_data.nodata_value is not None else 0) + "\x00").encode("ascii")
        if len(nodata_str) % 2 == 1:
            nodata_str += b"\x00"

        # IFD 0 has 15 entries; IFD 1 (if present) has 11 entries
        ifd0_entry_count = 15
        ifd0_offset = 8
        ifd0_size = 2 + ifd0_entry_count * 12 + 4

        ifd1_entry_count = 11
        ifd1_offset = (ifd0_offset + ifd0_size) if has_ovr else 0
        ifd1_size = (2 + ifd1_entry_count * 12 + 4) if has_ovr else 0

        extra_start = ifd0_offset + ifd0_size + ifd1_size
        scale_offset = extra_start
        tiepoint_offset = scale_offset + len(pixel_scale)
        geokey_offset = tiepoint_offset + len(tiepoint)
        nodata_offset = geokey_offset + len(geo_keys)

        full_offsets_table_pos = nodata_offset + len(nodata_str)
        full_counts_table_pos = full_offsets_table_pos + (num_full_tiles * 4 if num_full_tiles > 1 else 0)

        num_ovr_tiles = len(ovr_tiles)
        ovr_offsets_table_pos = full_counts_table_pos + (num_full_tiles * 4 if num_full_tiles > 1 else 0)
        ovr_counts_table_pos = ovr_offsets_table_pos + (num_ovr_tiles * 4 if (has_ovr and num_ovr_tiles > 1) else 0)
        data_start_offset = ovr_counts_table_pos + (num_ovr_tiles * 4 if (has_ovr and num_ovr_tiles > 1) else 0)

        # Compute exact byte offsets for full-resolution tiles and overview tiles
        curr_data_off = data_start_offset
        full_tile_offsets: List[int] = []
        full_tile_counts: List[int] = []
        for t_bytes in full_tiles:
            full_tile_offsets.append(curr_data_off)
            full_tile_counts.append(len(t_bytes))
            curr_data_off += len(t_bytes)

        ovr_tile_offsets: List[int] = []
        ovr_tile_counts: List[int] = []
        for t_bytes in ovr_tiles:
            ovr_tile_offsets.append(curr_data_off)
            ovr_tile_counts.append(len(t_bytes))
            curr_data_off += len(t_bytes)

        ifd0_entries = [
            (cls.TAG_NEW_SUBFILE_TYPE, 4, 1, 0),
            (cls.TAG_IMAGE_WIDTH, 4, 1, width),
            (cls.TAG_IMAGE_LENGTH, 4, 1, height),
            (cls.TAG_BITS_PER_SAMPLE, 3, 1, bits_per_sample),
            (cls.TAG_COMPRESSION, 3, 1, compression_code),
            (cls.TAG_PHOTOMETRIC, 3, 1, 1),
            (cls.TAG_SAMPLES_PER_PIXEL, 3, 1, 1),
            (cls.TAG_TILE_WIDTH, 4, 1, tile_width),
            (cls.TAG_TILE_LENGTH, 4, 1, tile_height),
            (
                cls.TAG_TILE_OFFSETS,
                4,
                num_full_tiles,
                full_tile_offsets[0] if num_full_tiles == 1 else full_offsets_table_pos,
            ),
            (
                cls.TAG_TILE_BYTE_COUNTS,
                4,
                num_full_tiles,
                full_tile_counts[0] if num_full_tiles == 1 else full_counts_table_pos,
            ),
            (cls.TAG_SAMPLE_FORMAT, 3, 1, sample_format),
            (cls.TAG_MODEL_PIXEL_SCALE, 12, 3, scale_offset),
            (cls.TAG_MODEL_TIEPOINT, 12, 6, tiepoint_offset),
            (cls.TAG_GEO_KEY_DIRECTORY, 3, 16, geokey_offset),
        ]
        ifd0_entries.sort(key=lambda e: e[0])

        ifd0_bytes = bytearray(struct.pack("<H", len(ifd0_entries)))
        for tag, ttype, count, val_or_off in ifd0_entries:
            ifd0_bytes.extend(struct.pack("<HHII", tag, ttype, count, val_or_off))
        ifd0_bytes.extend(struct.pack("<I", ifd1_offset))

        ifd1_bytes = bytearray()
        if has_ovr:
            ifd1_entries = [
                (cls.TAG_NEW_SUBFILE_TYPE, 4, 1, 1),  # Reduced-resolution overview
                (cls.TAG_IMAGE_WIDTH, 4, 1, ovr_w),
                (cls.TAG_IMAGE_LENGTH, 4, 1, ovr_h),
                (cls.TAG_BITS_PER_SAMPLE, 3, 1, bits_per_sample),
                (cls.TAG_COMPRESSION, 3, 1, compression_code),
                (cls.TAG_PHOTOMETRIC, 3, 1, 1),
                (cls.TAG_SAMPLES_PER_PIXEL, 3, 1, 1),
                (cls.TAG_TILE_WIDTH, 4, 1, ovr_tw),
                (cls.TAG_TILE_LENGTH, 4, 1, ovr_th),
                (
                    cls.TAG_TILE_OFFSETS,
                    4,
                    num_ovr_tiles,
                    ovr_tile_offsets[0] if num_ovr_tiles == 1 else ovr_offsets_table_pos,
                ),
                (
                    cls.TAG_TILE_BYTE_COUNTS,
                    4,
                    num_ovr_tiles,
                    ovr_tile_counts[0] if num_ovr_tiles == 1 else ovr_counts_table_pos,
                ),
            ]
            ifd1_entries.sort(key=lambda e: e[0])
            ifd1_bytes.extend(struct.pack("<H", len(ifd1_entries)))
            for tag, ttype, count, val_or_off in ifd1_entries:
                ifd1_bytes.extend(struct.pack("<HHII", tag, ttype, count, val_or_off))
            ifd1_bytes.extend(struct.pack("<I", 0))

        header = struct.pack("<2sHI", b"II", 42, ifd0_offset)

        with open(file_path, "wb") as f:
            f.write(header)
            f.write(ifd0_bytes)
            if has_ovr:
                f.write(ifd1_bytes)
            f.write(pixel_scale)
            f.write(tiepoint)
            f.write(geo_keys)
            f.write(nodata_str)
            if num_full_tiles > 1:
                f.write(struct.pack(f"<{num_full_tiles}I", *full_tile_offsets))
                f.write(struct.pack(f"<{num_full_tiles}I", *full_tile_counts))
            if has_ovr and num_ovr_tiles > 1:
                f.write(struct.pack(f"<{num_ovr_tiles}I", *ovr_tile_offsets))
                f.write(struct.pack(f"<{num_ovr_tiles}I", *ovr_tile_counts))
            for t_bytes in full_tiles:
                f.write(t_bytes)
            for t_bytes in ovr_tiles:
                f.write(t_bytes)

        return file_path

    @classmethod
    def _parse_ifd_chain_from_reader(
        cls,
        fetch_bytes_fn: Any,
        strict_crs: bool = False,
    ) -> Tuple[str, List[Dict[int, Any]]]:
        """
        Parse the TIFF header and traverse the IFD chain (IFD 0 + overview sub-IFDs)
        using a byte-range reader callback `fetch_bytes_fn(start, end) -> bytes`.
        """
        header_chunk = fetch_bytes_fn(0, cls.INITIAL_HEADER_RANGE_BYTES - 1)
        if len(header_chunk) < 8:
            raise RasterIntegrityError(
                f"Corrupted GeoTIFF: file is smaller than 8-byte TIFF header ({len(header_chunk)} bytes)"
            )

        endian_mark = header_chunk[:2]
        if endian_mark == b"II":
            endian = "<"
        elif endian_mark == b"MM":
            endian = ">"
        else:
            raise RasterIntegrityError(f"Corrupted GeoTIFF: invalid byte order marker {endian_mark!r}")

        magic, first_ifd_offset = struct.unpack(f"{endian}HI", header_chunk[2:8])
        if magic != 42:
            raise RasterIntegrityError(f"Corrupted GeoTIFF: unsupported magic number {magic} (expected 42)")
        if first_ifd_offset < 8:
            raise RasterIntegrityError(f"Corrupted GeoTIFF: invalid first IFD offset {first_ifd_offset}")

        def _get_slice(offset: int, length: int) -> bytes:
            if offset < 0 or length <= 0:
                raise RasterIntegrityError(f"Corrupted GeoTIFF: invalid slice offset={offset}, length={length}")
            if offset + length <= len(header_chunk):
                return header_chunk[offset : offset + length]
            chunk = fetch_bytes_fn(offset, offset + length - 1)
            if len(chunk) < length:
                raise RasterIntegrityError(
                    f"Corrupted or truncated GeoTIFF: expected {length} bytes at offset {offset}, got {len(chunk)}"
                )
            return chunk[:length]

        ifds: List[Dict[int, Any]] = []
        visited_offsets: Set[int] = set()
        curr_ifd_off = first_ifd_offset

        while curr_ifd_off != 0 and len(ifds) < 8:
            if curr_ifd_off in visited_offsets:
                raise RasterIntegrityError("Corrupted GeoTIFF: circular IFD offset chain detected")
            visited_offsets.add(curr_ifd_off)

            count_bytes = _get_slice(curr_ifd_off, 2)
            num_entries = struct.unpack(f"{endian}H", count_bytes)[0]
            if num_entries == 0 or num_entries > 512:
                raise RasterIntegrityError(f"Corrupted GeoTIFF: invalid IFD entry count {num_entries}")

            entries_block = _get_slice(curr_ifd_off + 2, num_entries * 12 + 4)
            tags: Dict[int, Any] = {}
            pos = 0
            for _ in range(num_entries):
                tag, ttype, count, raw_val_offset = struct.unpack(f"{endian}HHII", entries_block[pos : pos + 12])
                pos += 12
                if ttype not in cls.TYPE_SIZES:
                    continue
                if count <= 0 or count > 10_000_000:
                    raise RasterIntegrityError(f"Corrupted GeoTIFF: invalid count {count} for tag {tag}")
                unit_size, fmt_char = cls.TYPE_SIZES[ttype]
                total_bytes = unit_size * count
                if total_bytes <= 4:
                    val_bytes = entries_block[pos - 4 : pos - 4 + total_bytes]
                else:
                    val_bytes = _get_slice(raw_val_offset, total_bytes)

                if ttype == 2:
                    tags[tag] = val_bytes.decode("ascii", errors="ignore").rstrip("\x00")
                else:
                    values = struct.unpack(f"{endian}{count}{fmt_char}", val_bytes)
                    tags[tag] = values[0] if count == 1 else list(values)

            next_ifd_off = struct.unpack(f"{endian}I", entries_block[pos : pos + 4])[0]
            ifds.append(tags)
            curr_ifd_off = next_ifd_off

        if not ifds:
            raise RasterIntegrityError("Corrupted GeoTIFF: no valid IFD found")

        # Validate primary IFD metadata
        primary = ifds[0]
        if strict_crs and cls.TAG_GEO_KEY_DIRECTORY not in primary:
            raise UnsupportedCrsError("GeoTIFF is missing GeoKeyDirectoryTag (34735); CRS cannot be determined")

        return endian, ifds

    @classmethod
    def _extract_georef_metadata(
        cls,
        primary_tags: Dict[int, Any],
        target_tags: Dict[int, Any],
        strict_crs: bool = False,
    ) -> Tuple[int, int, int, GeoTransform, Optional[float], str, int, int]:
        """
        Validate and extract `(width, height, crs_epsg, transform, nodata_val, px_fmt, px_bytes, compression)`
        from parsed IFD tags. Supports overview IFDs by scaling pixel resolution relative to primary IFD.
        """
        full_w = int(primary_tags.get(cls.TAG_IMAGE_WIDTH, 0))
        full_h = int(primary_tags.get(cls.TAG_IMAGE_LENGTH, 0))
        width = int(target_tags.get(cls.TAG_IMAGE_WIDTH, 0))
        height = int(target_tags.get(cls.TAG_IMAGE_LENGTH, 0))
        if width <= 0 or height <= 0 or full_w <= 0 or full_h <= 0:
            raise RasterIntegrityError("GeoTIFF missing valid ImageWidth or ImageLength tags")

        samples_per_pixel = int(target_tags.get(cls.TAG_SAMPLES_PER_PIXEL, primary_tags.get(cls.TAG_SAMPLES_PER_PIXEL, 1)))
        if samples_per_pixel < 1:
            raise RasterIntegrityError(f"Invalid SamplesPerPixel={samples_per_pixel}")

        bits_per_sample = target_tags.get(cls.TAG_BITS_PER_SAMPLE, primary_tags.get(cls.TAG_BITS_PER_SAMPLE, 16))
        if isinstance(bits_per_sample, list):
            bits_per_sample = bits_per_sample[0]
        compression = int(target_tags.get(cls.TAG_COMPRESSION, primary_tags.get(cls.TAG_COMPRESSION, 1)))
        sample_format = target_tags.get(cls.TAG_SAMPLE_FORMAT, primary_tags.get(cls.TAG_SAMPLE_FORMAT, 1))
        if isinstance(sample_format, list):
            sample_format = sample_format[0]

        if int(bits_per_sample) == 32 and int(sample_format) == 3:
            px_fmt = "f"
            px_bytes = 4
        elif int(bits_per_sample) == 16:
            px_fmt = "h" if int(sample_format) == 2 else "H"
            px_bytes = 2
        elif int(bits_per_sample) == 8:
            px_fmt = "B"
            px_bytes = 1
        else:
            raise RasterIntegrityError(
                f"Unsupported GeoTIFF BitsPerSample={bits_per_sample}, SampleFormat={sample_format}"
            )

        pixel_scale = primary_tags.get(cls.TAG_MODEL_PIXEL_SCALE, [10.0, 10.0, 0.0])
        tiepoint = primary_tags.get(cls.TAG_MODEL_TIEPOINT, [0.0, 0.0, 0.0, 0.0, 0.0, 0.0])
        scale_x = float(pixel_scale[0]) if isinstance(pixel_scale, list) and len(pixel_scale) >= 2 else 10.0
        scale_y = float(pixel_scale[1]) if isinstance(pixel_scale, list) and len(pixel_scale) >= 2 else 10.0
        if scale_x <= 0.0 or scale_y <= 0.0:
            raise RasterIntegrityError(f"Invalid non-positive ModelPixelScaleTag: {pixel_scale!r}")

        # Adjust pixel scale if reading a reduced-resolution overview IFD
        ovr_factor_x = float(full_w) / float(width)
        ovr_factor_y = float(full_h) / float(height)
        eff_scale_x = scale_x * ovr_factor_x
        eff_scale_y = scale_y * ovr_factor_y

        if isinstance(tiepoint, list) and len(tiepoint) >= 6:
            tp_i, tp_j, _, tp_x, tp_y, _ = [float(v) for v in tiepoint[:6]]
            origin_x = tp_x - tp_i * scale_x
            origin_y = tp_y + tp_j * scale_y
        else:
            origin_x, origin_y = 0.0, 0.0

        transform = GeoTransform(
            origin_x=origin_x,
            pixel_width=eff_scale_x,
            row_rotation=0.0,
            origin_y=origin_y,
            col_rotation=0.0,
            pixel_height=-eff_scale_y,
        )

        crs_epsg: Optional[int] = None
        geo_keys = primary_tags.get(cls.TAG_GEO_KEY_DIRECTORY)
        if isinstance(geo_keys, list) and len(geo_keys) >= 4:
            num_keys = int(geo_keys[3])
            idx = 4
            for _ in range(num_keys):
                if idx + 4 <= len(geo_keys):
                    k_id, k_loc, _, k_val = geo_keys[idx : idx + 4]
                    if k_id in (2048, 3072) and k_loc == 0:
                        crs_epsg = int(k_val)
                    idx += 4
        if crs_epsg is None:
            if strict_crs:
                raise UnsupportedCrsError("GeoTIFF GeoKeyDirectoryTag does not specify an EPSG code")
            crs_epsg = 4326
        validate_supported_crs(crs_epsg)

        nodata_val: Optional[float] = 0.0
        if cls.TAG_GDAL_NODATA in primary_tags:
            nd_str = str(primary_tags[cls.TAG_GDAL_NODATA]).strip()
            try:
                nodata_val = float(nd_str)
            except ValueError:
                nodata_val = None

        return width, height, crs_epsg, transform, nodata_val, px_fmt, px_bytes, compression

    @classmethod
    def _compute_pixel_window(
        cls,
        width: int,
        height: int,
        crs_epsg: int,
        transform: GeoTransform,
        farm_polygon_geojson: Optional[Dict[str, Any]] = None,
        window_bounds_wgs84: Optional[Tuple[float, float, float, float]] = None,
        padding_px: int = 2,
    ) -> Tuple[int, int, int, int]:
        """
        Compute inclusive-exclusive pixel window `(row_min, row_max, col_min, col_max)`
        covering the target farm polygon or WGS84 bounding box.
        """
        bounds = window_bounds_wgs84
        if bounds is None and farm_polygon_geojson is not None:
            bounds = extract_geojson_wgs84_bounds(farm_polygon_geojson)
        if bounds is None:
            return 0, height, 0, width

        min_lon, min_lat, max_lon, max_lat = bounds
        corners_wgs84 = [
            (min_lon, min_lat),
            (min_lon, max_lat),
            (max_lon, min_lat),
            (max_lon, max_lat),
        ]
        rows: List[float] = []
        cols: List[float] = []
        for lon, lat in corners_wgs84:
            x, y = wgs84_to_raster_crs(lon, lat, crs_epsg)
            r_frac, c_frac = transform.xy_to_fractional_pixel(x, y)
            rows.append(r_frac)
            cols.append(c_frac)

        r_min = max(0, min(height, int(math.floor(min(rows))) - padding_px))
        r_max = max(0, min(height, int(math.ceil(max(rows))) + padding_px))
        c_min = max(0, min(width, int(math.floor(min(cols))) - padding_px))
        c_max = max(0, min(width, int(math.ceil(max(cols))) + padding_px))

        if r_max <= r_min or c_max <= c_min:
            # If polygon does not overlap or is sub-pixel, clamp to nearest valid pixel window
            r_min = max(0, min(height - 1, int(round(sum(rows) / len(rows)))))
            r_max = min(height, r_min + 1)
            c_min = max(0, min(width - 1, int(round(sum(cols) / len(cols)))))
            c_max = min(width, c_min + 1)

        return r_min, r_max, c_min, c_max

    @classmethod
    def _decode_window_from_reader(
        cls,
        endian: str,
        ifds: List[Dict[int, Any]],
        fetch_bytes_fn: Any,
        band_name: str = "BAND",
        overview_level: int = 0,
        farm_polygon_geojson: Optional[Dict[str, Any]] = None,
        window_bounds_wgs84: Optional[Tuple[float, float, float, float]] = None,
        strict_crs: bool = False,
    ) -> RasterBandData:
        if overview_level < 0 or overview_level >= len(ifds):
            raise RasterIntegrityError(
                f"Requested overview_level={overview_level} is not available (file has {len(ifds)} IFDs)"
            )

        primary_tags = ifds[0]
        target_tags = ifds[overview_level]
        width, height, crs_epsg, transform, nodata_val, px_fmt, px_bytes, compression = (
            cls._extract_georef_metadata(primary_tags, target_tags, strict_crs=strict_crs)
        )

        use_window = (farm_polygon_geojson is not None) or (window_bounds_wgs84 is not None)
        r_min, r_max, c_min, c_max = cls._compute_pixel_window(
            width=width,
            height=height,
            crs_epsg=crs_epsg,
            transform=transform,
            farm_polygon_geojson=farm_polygon_geojson,
            window_bounds_wgs84=window_bounds_wgs84,
        )
        win_h = r_max - r_min
        win_w = c_max - c_min

        win_origin_x = transform.origin_x + c_min * transform.pixel_width + r_min * transform.row_rotation
        win_origin_y = transform.origin_y + c_min * transform.col_rotation + r_min * transform.pixel_height
        win_transform = (
            GeoTransform(
                origin_x=win_origin_x,
                pixel_width=transform.pixel_width,
                row_rotation=transform.row_rotation,
                origin_y=win_origin_y,
                col_rotation=transform.col_rotation,
                pixel_height=transform.pixel_height,
            )
            if use_window
            else transform
        )

        grid: List[List[float]] = [[float("nan") for _ in range(win_w)] for _ in range(win_h)]

        predictor = int(target_tags.get(317, primary_tags.get(317, 1)))

        def _decompress_block(raw_chunk: bytes) -> bytes:
            if compression in (8, 32946):
                try:
                    return zlib.decompress(raw_chunk)
                except Exception as exc:
                    raise RasterIntegrityError(f"Corrupted DEFLATE stream in GeoTIFF: {exc}") from exc
            if compression == 1:
                return raw_chunk
            raise RasterIntegrityError(f"Unsupported TIFF compression method: {compression}")

        def _unpack_block_values(chunk: bytes, block_width: int, block_height: int) -> Tuple[float, ...]:
            total_vals = block_width * block_height
            raw_vals = list(struct.unpack(f"{endian}{total_vals}{px_fmt}", chunk[: total_vals * px_bytes]))
            if predictor == 2 and px_fmt in ("B", "H", "h", "I", "i"):
                modulus = 256 if px_bytes == 1 else (65536 if px_bytes == 2 else 4294967296)
                is_signed = px_fmt in ("h", "i")
                half_mod = modulus // 2
                for row_idx in range(block_height):
                    row_base = row_idx * block_width
                    acc = int(raw_vals[row_base]) % modulus
                    if is_signed and acc >= half_mod:
                        acc -= modulus
                    raw_vals[row_base] = acc
                    for col_idx in range(1, block_width):
                        acc = (acc + int(raw_vals[row_base + col_idx])) % modulus
                        if is_signed and acc >= half_mod:
                            acc -= modulus
                        raw_vals[row_base + col_idx] = acc
            return tuple(float(v) for v in raw_vals)

        if cls.TAG_TILE_OFFSETS in target_tags:
            tile_w = int(target_tags.get(cls.TAG_TILE_WIDTH, 0))
            tile_h = int(target_tags.get(cls.TAG_TILE_LENGTH, 0))
            if tile_w <= 0 or tile_h <= 0:
                raise RasterIntegrityError(f"Invalid TileWidth={tile_w} or TileLength={tile_h}")

            offsets = target_tags[cls.TAG_TILE_OFFSETS]
            byte_counts = target_tags.get(cls.TAG_TILE_BYTE_COUNTS)
            if not isinstance(offsets, list):
                offsets = [offsets]
            if not isinstance(byte_counts, list):
                byte_counts = [byte_counts] if byte_counts is not None else []
            if len(offsets) != len(byte_counts) or not offsets:
                raise RasterIntegrityError("Mismatched or empty TileOffsets / TileByteCounts in GeoTIFF")

            tiles_across = (width + tile_w - 1) // tile_w
            tiles_down = (height + tile_h - 1) // tile_h
            if len(offsets) < tiles_across * tiles_down:
                raise RasterIntegrityError(
                    f"TileOffsets count ({len(offsets)}) is less than expected tile grid ({tiles_across * tiles_down})"
                )

            ty_min = r_min // tile_h
            ty_max = (r_max - 1) // tile_h
            tx_min = c_min // tile_w
            tx_max = (c_max - 1) // tile_w

            tile_vals = tile_w * tile_h
            expected_tile_bytes = tile_vals * px_bytes

            for ty in range(ty_min, ty_max + 1):
                for tx in range(tx_min, tx_max + 1):
                    tile_idx = ty * tiles_across + tx
                    off = int(offsets[tile_idx])
                    bc = int(byte_counts[tile_idx])
                    if off < 8 or bc <= 0:
                        raise RasterIntegrityError(
                            f"Invalid tile byte range offset={off}, byte_count={bc} for tile {tile_idx}"
                        )
                    raw_chunk = fetch_bytes_fn(off, off + bc - 1)
                    if len(raw_chunk) < bc:
                        raise RasterDownloadInterruptedError(
                            f"Incomplete tile {tile_idx}: expected {bc} bytes at offset {off}, got {len(raw_chunk)}"
                        )
                    chunk = _decompress_block(raw_chunk[:bc])
                    if len(chunk) < expected_tile_bytes:
                        raise RasterIntegrityError(
                            f"Decompressed tile {tile_idx} has {len(chunk)} bytes, expected {expected_tile_bytes}"
                        )
                    unpacked = _unpack_block_values(chunk, tile_w, tile_h)

                    tile_row_origin = ty * tile_h
                    tile_col_origin = tx * tile_w
                    r_start = max(r_min, tile_row_origin)
                    r_end = min(r_max, tile_row_origin + tile_h)
                    c_start = max(c_min, tile_col_origin)
                    c_end = min(c_max, tile_col_origin + tile_w)

                    for gr in range(r_start, r_end):
                        tr = gr - tile_row_origin
                        row_target = grid[gr - r_min]
                        base_idx = tr * tile_w
                        for gc in range(c_start, c_end):
                            tc = gc - tile_col_origin
                            row_target[gc - c_min] = float(unpacked[base_idx + tc])

        elif cls.TAG_STRIP_OFFSETS in target_tags:
            offsets = target_tags[cls.TAG_STRIP_OFFSETS]
            byte_counts = target_tags.get(cls.TAG_STRIP_BYTE_COUNTS)
            rows_per_strip = int(target_tags.get(cls.TAG_ROWS_PER_STRIP, height))
            if rows_per_strip <= 0:
                raise RasterIntegrityError(f"Invalid RowsPerStrip={rows_per_strip}")
            if not isinstance(offsets, list):
                offsets = [offsets]
            if not isinstance(byte_counts, list):
                byte_counts = [byte_counts] if byte_counts is not None else []
            if len(offsets) != len(byte_counts) or not offsets:
                raise RasterIntegrityError("Mismatched or empty StripOffsets / StripByteCounts in GeoTIFF")

            current_row = 0
            for strip_idx, (off_raw, bc_raw) in enumerate(zip(offsets, byte_counts)):
                off = int(off_raw)
                bc = int(bc_raw)
                rows_in_this_strip = min(rows_per_strip, height - current_row)
                strip_r_start = current_row
                strip_r_end = current_row + rows_in_this_strip
                current_row = strip_r_end

                # Skip strips completely outside the target window
                if strip_r_end <= r_min or strip_r_start >= r_max:
                    continue

                if off < 8 or bc <= 0:
                    raise RasterIntegrityError(
                        f"Invalid strip byte range offset={off}, byte_count={bc} for strip {strip_idx}"
                    )
                raw_chunk = fetch_bytes_fn(off, off + bc - 1)
                if len(raw_chunk) < bc:
                    raise RasterDownloadInterruptedError(
                        f"Incomplete strip {strip_idx}: expected {bc} bytes at offset {off}, got {len(raw_chunk)}"
                    )
                chunk = _decompress_block(raw_chunk[:bc])
                expected_vals = rows_in_this_strip * width
                if len(chunk) < expected_vals * px_bytes:
                    raise RasterIntegrityError(
                        f"Decompressed strip {strip_idx} has {len(chunk)} bytes, expected {expected_vals * px_bytes}"
                    )
                unpacked = _unpack_block_values(chunk, width, rows_in_this_strip)

                ov_r_start = max(r_min, strip_r_start)
                ov_r_end = min(r_max, strip_r_end)
                for gr in range(ov_r_start, ov_r_end):
                    r_local = gr - strip_r_start
                    row_slice = unpacked[r_local * width + c_min : r_local * width + c_max]
                    grid[gr - r_min] = [float(v) for v in row_slice]
        else:
            raise RasterIntegrityError("GeoTIFF contains neither StripOffsets nor TileOffsets")

        return RasterBandData(
            band_name=band_name,
            width=win_w,
            height=win_h,
            crs_epsg=crs_epsg,
            transform=win_transform,
            values=grid,
            nodata_value=nodata_val,
            spatial_resolution_m=abs(win_transform.pixel_width),
        )

    @classmethod
    def read_geotiff(
        cls,
        source_path_or_url: str,
        band_name: str = "BAND",
        farm_polygon_geojson: Optional[Dict[str, Any]] = None,
        window_bounds_wgs84: Optional[Tuple[float, float, float, float]] = None,
        overview_level: int = 0,
        http_client: Optional[RemoteCogHttpClient] = None,
        require_range_support: bool = False,
        max_unranged_bytes: int = 8 * 1024 * 1024,
        strict_crs: bool = False,
    ) -> RasterBandData:
        """
        Read a GeoTIFF or Cloud-Optimized GeoTIFF from a local file path or HTTP(S) URL.
        - For remote URLs, uses `RemoteCogHttpClient` to perform HTTP Range requests (`bytes=start-end`)
          for the TIFF header/IFD and ONLY the internal tiles/strips intersecting `farm_polygon_geojson`
          or `window_bounds_wgs84`.
        - Never downloads a fixed 16 MB prefix or silently truncates remote files.
        """
        if not isinstance(source_path_or_url, str) or not source_path_or_url.strip():
            raise InvalidAssetUrlError("Raster source path or URL must be a non-empty string")

        is_http = source_path_or_url.startswith("http://") or source_path_or_url.startswith("https://")
        if "://" in source_path_or_url and not is_http:
            raise InvalidAssetUrlError(f"Unsupported URL scheme in {source_path_or_url!r}")

        if is_http:
            owns_client = http_client is None
            client = http_client or RemoteCogHttpClient(
                require_range_support=require_range_support,
                max_unranged_bytes=max_unranged_bytes,
            )
            try:
                def _remote_fetch(start: int, end: int) -> bytes:
                    return client.fetch_range(source_path_or_url, start, end)

                endian, ifds = cls._parse_ifd_chain_from_reader(_remote_fetch, strict_crs=strict_crs)
                return cls._decode_window_from_reader(
                    endian=endian,
                    ifds=ifds,
                    fetch_bytes_fn=_remote_fetch,
                    band_name=band_name,
                    overview_level=overview_level,
                    farm_polygon_geojson=farm_polygon_geojson,
                    window_bounds_wgs84=window_bounds_wgs84,
                    strict_crs=strict_crs,
                )
            finally:
                if owns_client:
                    client.close()

        # Local file path
        if not os.path.exists(source_path_or_url):
            raise InvalidAssetUrlError(f"Local raster file does not exist: {source_path_or_url!r}")

        with open(source_path_or_url, "rb") as f:
            file_bytes = f.read()

        return cls.parse_geotiff_bytes(
            data=file_bytes,
            band_name=band_name,
            farm_polygon_geojson=farm_polygon_geojson,
            window_bounds_wgs84=window_bounds_wgs84,
            overview_level=overview_level,
            strict_crs=strict_crs,
        )

    @classmethod
    def parse_geotiff_bytes(
        cls,
        data: bytes,
        band_name: str = "BAND",
        farm_polygon_geojson: Optional[Dict[str, Any]] = None,
        window_bounds_wgs84: Optional[Tuple[float, float, float, float]] = None,
        overview_level: int = 0,
        strict_crs: bool = False,
    ) -> RasterBandData:
        """Parse binary TIFF / GeoTIFF bytes with full IFD, CRS, tile/strip, and integrity validation."""
        def _mem_fetch(start: int, end: int) -> bytes:
            if start < 0 or start >= len(data):
                return b""
            return data[start : min(len(data), end + 1)]

        endian, ifds = cls._parse_ifd_chain_from_reader(_mem_fetch, strict_crs=strict_crs)
        return cls._decode_window_from_reader(
            endian=endian,
            ifds=ifds,
            fetch_bytes_fn=_mem_fetch,
            band_name=band_name,
            overview_level=overview_level,
            farm_polygon_geojson=farm_polygon_geojson,
            window_bounds_wgs84=window_bounds_wgs84,
            strict_crs=strict_crs,
        )



# ==============================================================================
# Sentinel-2 L2A Radiometric Scaling, Multi-Resolution Resampling & Polygon Clip
# ==============================================================================

def dn_to_reflectance(
    dn: float,
    scale_factor: float = 0.0001,
    boa_add_offset: float = 0.0,
) -> float:
    """
    Convert Sentinel-2 L2A Digital Number (DN) to Bottom-Of-Atmosphere surface reflectance.
    Supports both pre-baseline-04.00 (`boa_add_offset=0.0`) and Processing Baseline 04.00+
    (`boa_add_offset=-1000.0`, where `Reflectance = (DN - 1000) / 10000.0`).
    Returns NaN for NoData (`dn <= 0`) or saturated pixels (`dn >= 65535`).
    """
    if math.isnan(dn) or dn <= 0.0 or dn >= 65535.0:
        return float("nan")
    # If boa_add_offset is given in DN units (e.g., -1000), apply before scale_factor;
    # if given in reflectance units (e.g., -0.1), apply after scale_factor.
    if abs(boa_add_offset) >= 1.0:
        refl = (float(dn) + boa_add_offset) * scale_factor
    else:
        refl = float(dn) * scale_factor + boa_add_offset

    if refl <= 0.0001 or refl > 1.5:
        return float("nan")
    return min(1.0, refl)


def is_scl_masked(scl_class: int) -> bool:
    """Return True if the Sentinel-2 L2A Scene Classification Layer value is cloud, shadow, or NoData."""
    return int(round(scl_class)) in MASKED_SCL_CLASSES


def align_band_to_target_grid(
    source_band: RasterBandData,
    target_width: int,
    target_height: int,
    target_transform: GeoTransform,
    is_categorical: bool = False,
) -> RasterBandData:
    """
    Resample a 20m Sentinel-2 band (B05, B06, B8A, B11, or SCL) to match the 10m target
    raster grid (B02, B04, B08), preserving CRS and affine geotransform.
    - Uses exact nearest-neighbor for categorical bands (SCL) so class codes are never blended.
    - Uses bilinear interpolation (with NoData fallback to nearest valid neighbor) for
      continuous spectral bands.
    """
    if (
        source_band.width == target_width
        and source_band.height == target_height
        and abs(source_band.transform.pixel_width - target_transform.pixel_width) < 1e-7
        and abs(source_band.transform.origin_x - target_transform.origin_x) < 1e-7
        and abs(source_band.transform.origin_y - target_transform.origin_y) < 1e-7
    ):
        return source_band

    aligned: List[List[float]] = [[float("nan") for _ in range(target_width)] for _ in range(target_height)]
    src_h = source_band.height
    src_w = source_band.width

    for r in range(target_height):
        for c in range(target_width):
            x, y = target_transform.pixel_center_to_xy(r, c)
            src_r_frac, src_c_frac = source_band.transform.xy_to_fractional_pixel(x, y)

            if is_categorical:
                sr = int(math.floor(src_r_frac))
                sc = int(math.floor(src_c_frac))
                if 0 <= sr < src_h and 0 <= sc < src_w:
                    aligned[r][c] = source_band.values[sr][sc]
            else:
                # Bilinear interpolation relative to pixel centers (index + 0.5)
                r_center = max(0.0, min(float(src_h - 1), src_r_frac - 0.5))
                c_center = max(0.0, min(float(src_w - 1), src_c_frac - 0.5))
                r0 = int(math.floor(r_center))
                r1 = min(src_h - 1, r0 + 1)
                c0 = int(math.floor(c_center))
                c1 = min(src_w - 1, c0 + 1)

                dr = r_center - float(r0)
                dc = c_center - float(c0)

                v00 = source_band.values[r0][c0]
                v01 = source_band.values[r0][c1]
                v10 = source_band.values[r1][c0]
                v11 = source_band.values[r1][c1]

                nodata = source_band.nodata_value
                def _valid(v: float) -> bool:
                    if math.isnan(v):
                        return False
                    if nodata is not None and abs(v - nodata) < 1e-6:
                        return False
                    return v > 0.0

                if _valid(v00) and _valid(v01) and _valid(v10) and _valid(v11):
                    top = v00 * (1.0 - dc) + v01 * dc
                    bot = v10 * (1.0 - dc) + v11 * dc
                    aligned[r][c] = top * (1.0 - dr) + bot * dr
                else:
                    # Fallback to nearest valid sample
                    sr = max(0, min(src_h - 1, int(math.floor(src_r_frac))))
                    sc = max(0, min(src_w - 1, int(math.floor(src_c_frac))))
                    aligned[r][c] = source_band.values[sr][sc]

    return RasterBandData(
        band_name=source_band.band_name,
        width=target_width,
        height=target_height,
        crs_epsg=source_band.crs_epsg,
        transform=target_transform,
        values=aligned,
        nodata_value=source_band.nodata_value,
        spatial_resolution_m=abs(target_transform.pixel_width),
        scale_factor=source_band.scale_factor,
        boa_add_offset=source_band.boa_add_offset,
    )


def point_in_ring(lon: float, lat: float, ring: Sequence[Sequence[float]]) -> bool:
    """Ray-casting point-in-polygon check for a single closed GeoJSON coordinate ring `[[lon, lat], ...]`."""
    inside = False
    n = len(ring)
    if n < 3:
        return False
    j = n - 1
    for i in range(n):
        xi, yi = float(ring[i][0]), float(ring[i][1])
        xj, yj = float(ring[j][0]), float(ring[j][1])
        intersects = ((yi > lat) != (yj > lat)) and (
            lon < (xj - xi) * (lat - yi) / ((yj - yi) if abs(yj - yi) > 1e-12 else 1e-12) + xi
        )
        if intersects:
            inside = not inside
        j = i
    return inside


def point_in_geojson_polygon(lon: float, lat: float, polygon_geojson: Dict[str, Any]) -> bool:
    """
    Evaluate whether WGS84 coordinate `(lon, lat)` lies inside a GeoJSON Polygon,
    MultiPolygon, Feature, or FeatureCollection (respecting interior holes).
    """
    gtype = polygon_geojson.get("type")
    if gtype == "Feature":
        geom = polygon_geojson.get("geometry") or {}
        return point_in_geojson_polygon(lon, lat, geom)
    if gtype == "FeatureCollection":
        features = polygon_geojson.get("features") or []
        return any(point_in_geojson_polygon(lon, lat, f) for f in features)

    if HAS_SHAPELY and gtype == "Polygon":
        coords = polygon_geojson.get("coordinates") or []
        if not coords:
            return False
        poly = ShapelyPolygon(coords[0], coords[1:] if len(coords) > 1 else None)
        return bool(poly.contains(ShapelyPoint(lon, lat)) or poly.touches(ShapelyPoint(lon, lat)))

    if gtype == "Polygon":
        rings = polygon_geojson.get("coordinates") or []
        if not rings:
            return False
        if not point_in_ring(lon, lat, rings[0]):
            return False
        # Must not be inside any interior hole ring
        for hole in rings[1:]:
            if point_in_ring(lon, lat, hole):
                return False
        return True

    if gtype == "MultiPolygon":
        polys = polygon_geojson.get("coordinates") or []
        for poly_rings in polys:
            if poly_rings and point_in_ring(lon, lat, poly_rings[0]):
                in_hole = any(point_in_ring(lon, lat, h) for h in poly_rings[1:])
                if not in_hole:
                    return True
        return False

    raise ValueError(f"Unsupported GeoJSON geometry type: {gtype!r}")


# ==============================================================================
# Spectral Index Calculations & Connected-Component Anomaly Vectorization
# ==============================================================================

@dataclass
class PixelSpectralEvaluation:
    is_valid: bool
    is_cloud_or_shadow_masked: bool
    scl_class: int
    ndvi: float
    ndre: float
    ndmi: float
    psri: float
    row: int = 0
    col: int = 0
    lon: float = 0.0
    lat: float = 0.0
    is_inside_polygon: bool = True
    is_anomalous: bool = False
    anomaly_type: Optional[str] = None


@dataclass
class FieldSpectralStatistics:
    total_pixel_count: int
    polygon_pixel_count: int
    valid_pixel_count: int
    masked_cloud_or_nodata_count: int
    valid_pixel_ratio: float
    cloud_cover_ratio_in_polygon: float
    crs_epsg: int
    spatial_resolution_m: float
    ndvi_mean: float
    ndvi_std_dev: float
    ndvi_min: float
    ndvi_max: float
    ndvi_p10: float
    ndvi_p50: float
    ndvi_p90: float
    ndre_mean: float
    ndre_std_dev: float
    ndmi_mean: float
    ndmi_std_dev: float
    psri_mean: float
    psri_std_dev: float
    anomaly_pixel_count: int
    anomaly_area_fraction: float
    anomaly_area_m2: float
    anomaly_area_feddans: float
    anomaly_zones_geojson: str
    output_raster_files: Dict[str, str] = field(default_factory=dict)


def safe_normalized_difference(band_a: float, band_b: float) -> float:
    """Compute (A - B) / (A + B) with NaN and zero-denominator protection."""
    if math.isnan(band_a) or math.isnan(band_b):
        return float("nan")
    denom = band_a + band_b
    if abs(denom) < EPSILON:
        return float("nan")
    return max(-1.0, min(1.0, (band_a - band_b) / denom))


def safe_psri(b04_red: float, b02_blue: float, b06_red_edge: float) -> float:
    """Compute Plant Senescence Reflectance Index PSRI = (B04 - B02) / B06."""
    if math.isnan(b04_red) or math.isnan(b02_blue) or math.isnan(b06_red_edge):
        return float("nan")
    if abs(b06_red_edge) < EPSILON:
        return float("nan")
    return max(-1.0, min(1.5, (b04_red - b02_blue) / b06_red_edge))


def evaluate_pixel(
    b02_blue: float,
    b04_red: float,
    b05_red_edge1: float,
    b06_red_edge2: float,
    b08_nir: float,
    b8a_narrow_nir: float,
    b11_swir1: float,
    scl_class: int = 4,
    row: int = 0,
    col: int = 0,
    lon: float = 0.0,
    lat: float = 0.0,
    is_inside_polygon: bool = True,
    thresholds: Optional[CropSpectralThresholds] = None,
) -> PixelSpectralEvaluation:
    """Evaluate all four spectral indices and crop-specific anomaly status for a single pixel."""
    if not is_inside_polygon:
        return PixelSpectralEvaluation(
            is_valid=False,
            is_cloud_or_shadow_masked=False,
            scl_class=scl_class,
            ndvi=float("nan"),
            ndre=float("nan"),
            ndmi=float("nan"),
            psri=float("nan"),
            row=row,
            col=col,
            lon=lon,
            lat=lat,
            is_inside_polygon=False,
        )

    masked = is_scl_masked(scl_class)
    bands = (b02_blue, b04_red, b05_red_edge1, b06_red_edge2, b08_nir, b8a_narrow_nir, b11_swir1)
    has_invalid = any(math.isnan(b) or b <= 0.0 or b > 1.5 for b in bands)

    if masked or has_invalid:
        return PixelSpectralEvaluation(
            is_valid=False,
            is_cloud_or_shadow_masked=masked,
            scl_class=scl_class,
            ndvi=float("nan"),
            ndre=float("nan"),
            ndmi=float("nan"),
            psri=float("nan"),
            row=row,
            col=col,
            lon=lon,
            lat=lat,
            is_inside_polygon=True,
        )

    ndvi = safe_normalized_difference(b08_nir, b04_red)
    ndre = safe_normalized_difference(b8a_narrow_nir, b05_red_edge1)
    ndmi = safe_normalized_difference(b08_nir, b11_swir1)
    psri = safe_psri(b04_red, b02_blue, b06_red_edge2)

    valid = not any(math.isnan(v) for v in (ndvi, ndre, ndmi, psri))
    crop_thresh = thresholds or CROP_SPECTRAL_THRESHOLDS["tomato"]

    anomaly_type: Optional[str] = None
    if valid:
        if ndre < crop_thresh.stressed_ndre_threshold and psri > crop_thresh.senescence_psri_threshold:
            anomaly_type = "CHLOROPHYLL_SENESCENCE_ANOMALY"
        elif ndmi < crop_thresh.water_stress_ndmi_threshold:
            anomaly_type = "CANOPY_MOISTURE_DEFICIT_ANOMALY"
        elif ndvi < crop_thresh.stressed_ndvi_threshold:
            anomaly_type = "LOW_BIOMASS_CANOPY_ANOMALY"
        elif ndre < crop_thresh.stressed_ndre_threshold:
            anomaly_type = "EARLY_CHLOROPHYLL_DECLINE_ANOMALY"

    return PixelSpectralEvaluation(
        is_valid=valid,
        is_cloud_or_shadow_masked=False,
        scl_class=scl_class,
        ndvi=ndvi,
        ndre=ndre,
        ndmi=ndmi,
        psri=psri,
        row=row,
        col=col,
        lon=lon,
        lat=lat,
        is_inside_polygon=True,
        is_anomalous=anomaly_type is not None,
        anomaly_type=anomaly_type,
    )


def _mean_and_std(values: Sequence[float]) -> Tuple[float, float]:
    if not values:
        return 0.0, 0.0
    mean_val = sum(values) / len(values)
    variance = sum((v - mean_val) ** 2 for v in values) / len(values)
    return mean_val, math.sqrt(max(0.0, variance))


def _percentile(sorted_vals: Sequence[float], pct: int) -> float:
    if not sorted_vals:
        return 0.0
    idx = int(round((pct / 100.0) * (len(sorted_vals) - 1)))
    idx = max(0, min(len(sorted_vals) - 1, idx))
    return sorted_vals[idx]


def vectorize_anomalous_pixel_clusters_to_geojson(
    anomalous_pixels: Sequence[PixelSpectralEvaluation],
    transform: GeoTransform,
    crs_epsg: int,
    pixel_area_m2: float = 100.0,
) -> str:
    """
    Generate accurate GeoJSON anomaly polygons directly from connected components of
    actual anomalous raster pixels (4-neighborhood connected clustering + boundary trace).
    NEVER generates a fixed square around the farm center.
    If there are no anomalous pixels, returns an empty FeatureCollection (`"features": []`).
    """
    if not anomalous_pixels:
        return json.dumps({"type": "FeatureCollection", "features": []})

    pixel_map: Dict[Tuple[int, int], PixelSpectralEvaluation] = {
        (p.row, p.col): p for p in anomalous_pixels
    }
    visited: Set[Tuple[int, int]] = set()
    clusters: List[List[PixelSpectralEvaluation]] = []

    for rc, px in pixel_map.items():
        if rc in visited:
            continue
        cluster: List[PixelSpectralEvaluation] = []
        queue = [rc]
        visited.add(rc)
        while queue:
            curr_r, curr_c = queue.pop(0)
            cluster.append(pixel_map[(curr_r, curr_c)])
            for dr, dc in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nbr = (curr_r + dr, curr_c + dc)
                if nbr in pixel_map and nbr not in visited:
                    visited.add(nbr)
                    queue.append(nbr)
        clusters.append(cluster)

    features: List[Dict[str, Any]] = []
    for cluster_idx, cluster in enumerate(clusters, start=1):
        cluster_coords_set = {(p.row, p.col) for p in cluster}

        if HAS_SHAPELY:
            pixel_polys = []
            for p in cluster:
                corners_xy = transform.pixel_corners_xy(p.row, p.col)
                corners_lonlat = [
                    [round(lon, 6), round(lat, 6)]
                    for lon, lat in (coordinate_to_wgs84(x, y, crs_epsg) for x, y in corners_xy)
                ]
                corners_lonlat.append(corners_lonlat[0])
                pixel_polys.append(ShapelyPolygon(corners_lonlat))
            merged = unary_union(pixel_polys)
            geom_dict = shapely_mapping(merged)
        else:
            # Exact orthogonal boundary tracing of the connected pixel cluster:
            # Collect directed outer boundary edges in clockwise order around exposed sides
            directed_edges: Dict[Tuple[int, int], List[Tuple[int, int]]] = {}
            for p in cluster:
                r, c = p.row, p.col
                # Top edge: (c, r) -> (c+1, r) if (r-1, c) not in cluster
                if (r - 1, c) not in cluster_coords_set:
                    directed_edges.setdefault((c, r), []).append((c + 1, r))
                # Right edge: (c+1, r) -> (c+1, r+1) if (r, c+1) not in cluster
                if (r, c + 1) not in cluster_coords_set:
                    directed_edges.setdefault((c + 1, r), []).append((c + 1, r + 1))
                # Bottom edge: (c+1, r+1) -> (c, r+1) if (r+1, c) not in cluster
                if (r + 1, c) not in cluster_coords_set:
                    directed_edges.setdefault((c + 1, r + 1), []).append((c, r + 1))
                # Left edge: (c, r+1) -> (c, r) if (r, c-1) not in cluster
                if (r, c - 1) not in cluster_coords_set:
                    directed_edges.setdefault((c, r + 1), []).append((c, r))

            start_vertex = min(directed_edges.keys(), key=lambda v: (v[1], v[0]))
            ring_grid_vertices = [start_vertex]
            curr = start_vertex
            max_steps = len(cluster) * 8 + 4
            steps = 0
            while steps < max_steps:
                steps += 1
                next_list = directed_edges.get(curr, [])
                if not next_list:
                    break
                nxt = next_list.pop(0)
                ring_grid_vertices.append(nxt)
                if nxt == start_vertex:
                    break
                curr = nxt

            if ring_grid_vertices[-1] != ring_grid_vertices[0]:
                ring_grid_vertices.append(ring_grid_vertices[0])

            ring_lonlat: List[List[float]] = []
            for gc, gr in ring_grid_vertices:
                x = transform.origin_x + gc * transform.pixel_width + gr * transform.row_rotation
                y = transform.origin_y + gc * transform.col_rotation + gr * transform.pixel_height
                lon, lat = coordinate_to_wgs84(x, y, crs_epsg)
                ring_lonlat.append([round(lon, 6), round(lat, 6)])

            geom_dict = {
                "type": "Polygon",
                "coordinates": [ring_lonlat],
            }

        c_ndvi = sum(p.ndvi for p in cluster) / len(cluster)
        c_ndre = sum(p.ndre for p in cluster) / len(cluster)
        c_ndmi = sum(p.ndmi for p in cluster) / len(cluster)
        c_psri = sum(p.psri for p in cluster) / len(cluster)
        cluster_area_m2 = len(cluster) * pixel_area_m2
        cluster_area_feddans = cluster_area_m2 / 4200.0
        primary_cause = cluster[0].anomaly_type or "SPECTRAL_STRESS_ANOMALY"

        features.append({
            "type": "Feature",
            "properties": {
                "cluster_id": f"anomaly_cluster_{cluster_idx}",
                "pixel_count": len(cluster),
                "area_m2": round(cluster_area_m2, 2),
                "area_feddans": round(cluster_area_feddans, 4),
                "ndvi_mean": round(c_ndvi, 4),
                "ndre_mean": round(c_ndre, 4),
                "ndmi_mean": round(c_ndmi, 4),
                "psri_mean": round(c_psri, 4),
                "anomaly_classification": primary_cause,
                "diagnostic_disclaimer": "SPECTRAL_ANOMALY_ONLY_REQUIRES_FIELD_VERIFICATION",
            },
            "geometry": geom_dict,
        })

    return json.dumps({
        "type": "FeatureCollection",
        "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:EPSG::4326"}},
        "features": features,
    })


def aggregate_field_raster(
    pixels: Sequence[PixelSpectralEvaluation],
    center_lat: float = 30.3833,
    center_lon: float = 30.3500,
    transform: Optional[GeoTransform] = None,
    crs_epsg: int = 4326,
    spatial_resolution_m: float = 10.0,
    thresholds: Optional[CropSpectralThresholds] = None,
) -> FieldSpectralStatistics:
    """
    Aggregate pixel-level spectral evaluations inside the farm polygon into field statistics
    and vectorize actual anomalous pixels into georeferenced GeoJSON polygons.
    """
    polygon_pixels = [p for p in pixels if p.is_inside_polygon]
    valid_pixels = [p for p in polygon_pixels if p.is_valid and not p.is_cloud_or_shadow_masked]
    masked_count = len(polygon_pixels) - len(valid_pixels)
    cloud_count = sum(1 for p in polygon_pixels if p.is_cloud_or_shadow_masked)

    eff_transform = transform or GeoTransform(
        origin_x=center_lon - 0.002,
        pixel_width=0.0001,
        row_rotation=0.0,
        origin_y=center_lat + 0.002,
        col_rotation=0.0,
        pixel_height=-0.0001,
    )

    if not valid_pixels:
        return FieldSpectralStatistics(
            total_pixel_count=len(pixels),
            polygon_pixel_count=len(polygon_pixels),
            valid_pixel_count=0,
            masked_cloud_or_nodata_count=masked_count,
            valid_pixel_ratio=0.0,
            cloud_cover_ratio_in_polygon=(cloud_count / float(len(polygon_pixels))) if polygon_pixels else 1.0,
            crs_epsg=crs_epsg,
            spatial_resolution_m=spatial_resolution_m,
            ndvi_mean=0.0,
            ndvi_std_dev=0.0,
            ndvi_min=0.0,
            ndvi_max=0.0,
            ndvi_p10=0.0,
            ndvi_p50=0.0,
            ndvi_p90=0.0,
            ndre_mean=0.0,
            ndre_std_dev=0.0,
            ndmi_mean=0.0,
            ndmi_std_dev=0.0,
            psri_mean=0.0,
            psri_std_dev=0.0,
            anomaly_pixel_count=0,
            anomaly_area_fraction=0.0,
            anomaly_area_m2=0.0,
            anomaly_area_feddans=0.0,
            anomaly_zones_geojson='{"type":"FeatureCollection","features":[]}',
        )

    ndvi_sorted = sorted(p.ndvi for p in valid_pixels)
    ndvi_mean, ndvi_std = _mean_and_std(ndvi_sorted)
    ndre_mean, ndre_std = _mean_and_std([p.ndre for p in valid_pixels])
    ndmi_mean, ndmi_std = _mean_and_std([p.ndmi for p in valid_pixels])
    psri_mean, psri_std = _mean_and_std([p.psri for p in valid_pixels])

    crop_thresh = thresholds or CROP_SPECTRAL_THRESHOLDS["tomato"]
    dynamic_ndvi_cutoff = min(crop_thresh.stressed_ndvi_threshold, max(0.22, ndvi_mean - max(0.08, 1.5 * ndvi_std)))

    anomaly_pixels: List[PixelSpectralEvaluation] = []
    for idx, p in enumerate(valid_pixels):
        is_anom = (
            p.is_anomalous
            or p.ndvi < dynamic_ndvi_cutoff
            or p.ndre < crop_thresh.stressed_ndre_threshold
            or p.psri > crop_thresh.senescence_psri_threshold
        )
        if is_anom:
            # Ensure distinct (row, col) if caller passed flat list without explicit row/col
            r = p.row if (p.row != 0 or p.col != 0 or idx == 0) else (idx // 8)
            c = p.col if (p.row != 0 or p.col != 0 or idx == 0) else (idx % 8)
            anomaly_pixels.append(
                PixelSpectralEvaluation(
                    is_valid=p.is_valid,
                    is_cloud_or_shadow_masked=p.is_cloud_or_shadow_masked,
                    scl_class=p.scl_class,
                    ndvi=p.ndvi,
                    ndre=p.ndre,
                    ndmi=p.ndmi,
                    psri=p.psri,
                    row=r,
                    col=c,
                    lon=p.lon,
                    lat=p.lat,
                    is_inside_polygon=True,
                    is_anomalous=True,
                    anomaly_type=p.anomaly_type or "SPECTRAL_STRESS_ANOMALY",
                )
            )

    pixel_area_m2 = spatial_resolution_m * spatial_resolution_m
    anomaly_area_m2 = len(anomaly_pixels) * pixel_area_m2
    anomaly_area_feddans = anomaly_area_m2 / 4200.0
    anomaly_fraction = len(anomaly_pixels) / float(len(valid_pixels))

    geojson_str = vectorize_anomalous_pixel_clusters_to_geojson(
        anomalous_pixels=anomaly_pixels,
        transform=eff_transform,
        crs_epsg=crs_epsg,
        pixel_area_m2=pixel_area_m2,
    )

    return FieldSpectralStatistics(
        total_pixel_count=len(pixels),
        polygon_pixel_count=len(polygon_pixels),
        valid_pixel_count=len(valid_pixels),
        masked_cloud_or_nodata_count=masked_count,
        valid_pixel_ratio=len(valid_pixels) / float(len(polygon_pixels)) if polygon_pixels else 0.0,
        cloud_cover_ratio_in_polygon=(cloud_count / float(len(polygon_pixels))) if polygon_pixels else 0.0,
        crs_epsg=crs_epsg,
        spatial_resolution_m=spatial_resolution_m,
        ndvi_mean=ndvi_mean,
        ndvi_std_dev=ndvi_std,
        ndvi_min=ndvi_sorted[0],
        ndvi_max=ndvi_sorted[-1],
        ndvi_p10=_percentile(ndvi_sorted, 10),
        ndvi_p50=_percentile(ndvi_sorted, 50),
        ndvi_p90=_percentile(ndvi_sorted, 90),
        ndre_mean=ndre_mean,
        ndre_std_dev=ndre_std,
        ndmi_mean=ndmi_mean,
        ndmi_std_dev=ndmi_std,
        psri_mean=psri_mean,
        psri_std_dev=psri_std,
        anomaly_pixel_count=len(anomaly_pixels),
        anomaly_area_fraction=anomaly_fraction,
        anomaly_area_m2=anomaly_area_m2,
        anomaly_area_feddans=anomaly_area_feddans,
        anomaly_zones_geojson=geojson_str,
    )


# ==============================================================================
# End-to-End Multi-Band GeoTIFF / COG Ingestion, Alignment, Clipping & Output
# ==============================================================================

def process_sentinel2_raster_bands(
    band_sources: Dict[str, Any],
    farm_polygon_geojson: Dict[str, Any],
    crop_id: str = "tomato",
    growth_stage_index: int = 2,
    scale_factor: float = 0.0001,
    boa_add_offset: float = 0.0,
    output_dir: Optional[str] = None,
) -> Tuple[FieldSpectralStatistics, List[PixelSpectralEvaluation]]:
    """
    Complete Sentinel-2 Level-2A raster processing pipeline:
    1. Reads actual B02, B04, B05, B06, B08, B8A, B11, and SCL raster bands (from file paths,
       COG URLs, or pre-loaded RasterBandData instances).
    2. Aligns 20m bands (B05, B06, B8A, B11, SCL) to the 10m target grid (B08 / B04 / B02).
    3. Applies radiometric scale_factor and Processing Baseline BOA offset (`boa_add_offset`).
    4. Applies SCL cloud and cloud-shadow masking (`SCL in {0, 1, 3, 8, 9, 10, 11}`).
    5. Clips raster pixels against the actual farm GeoJSON polygon (transforming pixel centers
       from the raster CRS to WGS84 lon/lat).
    6. Calculates NDVI, NDRE, NDMI, and PSRI for every valid pixel inside the polygon.
    7. Writes georeferenced float32 GeoTIFF output rasters (`NDVI.tif`, `NDRE.tif`, `NDMI.tif`, `PSRI.tif`)
       preserving CRS and spatial resolution.
    8. Vectorizes actual anomalous pixels into connected-component GeoJSON anomaly polygons.
    """
    required_keys = ("B02", "B04", "B05", "B06", "B08", "B8A", "B11", "SCL")
    if not isinstance(band_sources, dict):
        raise MissingBandError("band_sources must be a dictionary of Sentinel-2 band assets")

    missing_keys = [
        k for k in required_keys
        if k not in band_sources or band_sources[k] is None or (isinstance(band_sources[k], str) and not band_sources[k].strip())
    ]
    if missing_keys:
        raise MissingBandError(
            f"Missing required Sentinel-2 spectral band assets: {', '.join(missing_keys)}"
        )

    loaded_bands: Dict[str, RasterBandData] = {}
    with RemoteCogHttpClient() as shared_http_client:
        for key in required_keys:
            src = band_sources[key]
            if isinstance(src, RasterBandData):
                validate_supported_crs(src.crs_epsg)
                loaded_bands[key] = src
            elif isinstance(src, str):
                is_remote = src.startswith("http://") or src.startswith("https://")
                loaded_bands[key] = GeoTiffCogProcessor.read_geotiff(
                    src,
                    band_name=key,
                    farm_polygon_geojson=farm_polygon_geojson if is_remote else None,
                    http_client=shared_http_client if is_remote else None,
                )
            else:
                raise TypeError(f"Unsupported band source type for {key}: {type(src)}")


    # Reference 10m grid comes from B08 (10m NIR)
    ref_band = loaded_bands["B08"]
    target_w = ref_band.width
    target_h = ref_band.height
    target_transform = ref_band.transform
    crs_epsg = ref_band.crs_epsg
    res_m = (
        abs(target_transform.pixel_width)
        if crs_epsg != 4326
        else abs(target_transform.pixel_width) * 111320.0
    )

    # Align all bands to the 10m target grid
    aligned_bands: Dict[str, RasterBandData] = {}
    for key, band in loaded_bands.items():
        aligned_bands[key] = align_band_to_target_grid(
            source_band=band,
            target_width=target_w,
            target_height=target_h,
            target_transform=target_transform,
            is_categorical=(key == "SCL"),
        )

    thresholds = get_crop_thresholds(crop_id, growth_stage_index=growth_stage_index)
    evaluations: List[PixelSpectralEvaluation] = []

    ndvi_grid = [[float("nan") for _ in range(target_w)] for _ in range(target_h)]
    ndre_grid = [[float("nan") for _ in range(target_w)] for _ in range(target_h)]
    ndmi_grid = [[float("nan") for _ in range(target_w)] for _ in range(target_h)]
    psri_grid = [[float("nan") for _ in range(target_w)] for _ in range(target_h)]

    for r in range(target_h):
        for c in range(target_w):
            x, y = target_transform.pixel_center_to_xy(r, c)
            lon, lat = coordinate_to_wgs84(x, y, crs_epsg)
            inside = point_in_geojson_polygon(lon, lat, farm_polygon_geojson)

            raw_scl = aligned_bands["SCL"].values[r][c]
            scl_int = 0 if math.isnan(raw_scl) else int(round(raw_scl))

            def _to_refl(b_key: str) -> float:
                raw_val = aligned_bands[b_key].values[r][c]
                if math.isnan(raw_val):
                    return float("nan")
                # If already normalized reflectance in (0, 1.0], preserve; if DN (>1.5), convert
                if 0.0001 <= raw_val <= 1.0 and boa_add_offset == 0.0 and scale_factor == 1.0:
                    return raw_val
                if raw_val > 1.5 or boa_add_offset != 0.0:
                    return dn_to_reflectance(raw_val, scale_factor=scale_factor, boa_add_offset=boa_add_offset)
                return raw_val

            b02 = _to_refl("B02")
            b04 = _to_refl("B04")
            b05 = _to_refl("B05")
            b06 = _to_refl("B06")
            b08 = _to_refl("B08")
            b8a = _to_refl("B8A")
            b11 = _to_refl("B11")

            ev = evaluate_pixel(
                b02_blue=b02,
                b04_red=b04,
                b05_red_edge1=b05,
                b06_red_edge2=b06,
                b08_nir=b08,
                b8a_narrow_nir=b8a,
                b11_swir1=b11,
                scl_class=scl_int,
                row=r,
                col=c,
                lon=lon,
                lat=lat,
                is_inside_polygon=inside,
                thresholds=thresholds,
            )
            evaluations.append(ev)
            if ev.is_valid and inside:
                ndvi_grid[r][c] = ev.ndvi
                ndre_grid[r][c] = ev.ndre
                ndmi_grid[r][c] = ev.ndmi
                psri_grid[r][c] = ev.psri

    stats = aggregate_field_raster(
        pixels=evaluations,
        transform=target_transform,
        crs_epsg=crs_epsg,
        spatial_resolution_m=res_m,
        thresholds=thresholds,
    )

    out_dir = output_dir or tempfile.mkdtemp(prefix="agrosight_geotiff_")
    os.makedirs(out_dir, exist_ok=True)
    output_files: Dict[str, str] = {}
    for idx_name, idx_grid in (
        ("NDVI", ndvi_grid),
        ("NDRE", ndre_grid),
        ("NDMI", ndmi_grid),
        ("PSRI", psri_grid),
    ):
        out_band = RasterBandData(
            band_name=idx_name,
            width=target_w,
            height=target_h,
            crs_epsg=crs_epsg,
            transform=target_transform,
            values=idx_grid,
            nodata_value=float("nan"),
            spatial_resolution_m=res_m,
        )
        tif_path = os.path.join(out_dir, f"{idx_name}.tif")
        GeoTiffCogProcessor.write_geotiff(tif_path, out_band, dtype="float32")
        output_files[idx_name] = tif_path

    stats.output_raster_files = output_files
    return stats, evaluations


def analyze_temporal_observations(
    scenes: Sequence[Dict[str, Any]],
    max_usable_cloud_cover_pct: float = 35.0,
    min_valid_pixel_ratio: float = 0.60,
) -> Dict[str, Any]:
    """
    Sort Sentinel-2 passes chronologically by acquisition date (`acquisition_date_iso` or `datetime`)
    and compare consecutive usable observations while excluding cloud-obscured (`>35%`) or
    low-valid-pixel (`<60%`) scenes from crop decline alerts.
    """
    # Sort descending by date so index 0 is the most recent pass and index 1 is the previous pass
    sorted_scenes = sorted(
        scenes,
        key=lambda s: str(s.get("acquisition_date_iso") or s.get("datetime") or ""),
        reverse=True,
    )

    usable: List[Dict[str, Any]] = []
    for s in sorted_scenes:
        cloud_pct = float(s.get("cloud_cover_pct", 100.0))
        valid_ratio = float(s.get("valid_pixel_ratio", 1.0))
        if cloud_pct <= max_usable_cloud_cover_pct and valid_ratio >= min_valid_pixel_ratio:
            usable.append(s)

    cloud_skipped = len(sorted_scenes) - len(usable)

    if len(usable) >= 2:
        newest = usable[0]
        previous = usable[1]
        delta_ndvi = float(newest["ndvi_mean"]) - float(previous["ndvi_mean"])
        delta_ndre = float(newest["ndre_mean"]) - float(previous["ndre_mean"])
        delta_ndmi = float(newest["ndmi_mean"]) - float(previous["ndmi_mean"])
    else:
        delta_ndvi = 0.0
        delta_ndre = 0.0
        delta_ndmi = 0.0

    sudden_drop = len(usable) >= 2 and (delta_ndvi <= -0.07 or delta_ndre <= -0.05)

    return {
        "total_scenes_evaluated": len(sorted_scenes),
        "usable_clear_scene_count": len(usable),
        "cloud_gapped_scene_count": cloud_skipped,
        "ordered_clear_scene_ids": [str(s.get("scene_id", "")) for s in usable],
        "ndvi_delta_from_previous_clear_pass": round(delta_ndvi, 4),
        "ndre_delta_from_previous_clear_pass": round(delta_ndre, 4),
        "ndmi_delta_from_previous_clear_pass": round(delta_ndmi, 4),
        "is_sudden_chlorophyll_drop_detected": sudden_drop,
    }


def search_stac_sentinel2_scenes(
    farm_polygon_geojson: Dict[str, Any],
    max_cloud_cover: float = 35.0,
    limit: int = 5,
    date_start_iso: Optional[str] = None,
    date_end_iso: Optional[str] = None,
    stac_endpoint: str = "https://earth-search.aws.element84.com/v1/search",
) -> Dict[str, Any]:
    """
    Query Element84 Earth Search STAC API (`sentinel-2-l2a`) using the actual farm GeoJSON polygon
    geometry (`intersects`), configurable date range (`datetime`), and cloud-cover filter,
    extracting actual COG spectral band assets (`B02`, `B04`, `B05`, `B06`, `B08`, `B8A`, `B11`, `SCL`).
    """
    geom = (
        farm_polygon_geojson.get("geometry")
        if farm_polygon_geojson.get("type") == "Feature"
        else farm_polygon_geojson
    )
    if not geom or geom.get("type") not in ("Polygon", "MultiPolygon"):
        raise ValueError("Invalid farm GeoJSON geometry: must be a Polygon or MultiPolygon")

    payload: Dict[str, Any] = {
        "collections": ["sentinel-2-l2a"],
        "intersects": geom,
        "limit": max(1, min(20, int(limit))),
        "query": {"eo:cloud_cover": {"lte": float(max_cloud_cover)}},
    }
    if date_start_iso or date_end_iso:
        start_str = f"{str(date_start_iso).strip()}T00:00:00Z" if date_start_iso and "T" not in str(date_start_iso) else (str(date_start_iso or "..").strip())
        end_str = f"{str(date_end_iso).strip()}T23:59:59Z" if date_end_iso and "T" not in str(date_end_iso) else (str(date_end_iso or "..").strip())
        payload["datetime"] = f"{start_str}/{end_str}"

    req = urllib.request.Request(
        stac_endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/geo+json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        http_code = resp.getcode()
        raw_body = resp.read().decode("utf-8")

    parsed = json.loads(raw_body)
    features = parsed.get("features") or []
    scenes: List[Dict[str, Any]] = []

    for feat in features:
        props = feat.get("properties") or {}
        assets = feat.get("assets") or {}

        def _href(*keys: str) -> str:
            for k in keys:
                h = (assets.get(k) or {}).get("href", "")
                if h:
                    return str(h)
            return ""

        band_assets = {
            "B02": _href("blue", "B02"),
            "B04": _href("red", "B04"),
            "B05": _href("rededge1", "B05"),
            "B06": _href("rededge2", "B06"),
            "B08": _href("nir", "B08"),
            "B8A": _href("nir08", "B8A"),
            "B11": _href("swir16", "B11"),
            "SCL": _href("scl", "SCL"),
            "visual": _href("visual", "thumbnail"),
        }
        has_all_bands = all(bool(band_assets[k]) for k in ("B02", "B04", "B05", "B06", "B08", "B8A", "B11", "SCL"))
        proc_baseline = str(props.get("s2:processing_baseline", "04.00"))
        try:
            pb_num = float(proc_baseline)
        except ValueError:
            pb_num = 4.0
        boa_offset = -1000.0 if pb_num >= 4.0 and props.get("earthsearch:boa_offset_applied") is not True else 0.0

        scenes.append({
            "scene_id": feat.get("id", ""),
            "acquisition_date_iso": str(props.get("datetime", ""))[:10],
            "datetime": str(props.get("datetime", "")),
            "cloud_cover_pct": float(props.get("eo:cloud_cover", 100.0)),
            "mgrs_tile": str(props.get("grid:code", "")).replace("MGRS-", ""),
            "processing_baseline": proc_baseline,
            "crs_epsg": int(props.get("proj:epsg", 32636) or 32636),
            "scale_factor": 0.0001,
            "boa_add_offset": boa_offset,
            "has_all_required_bands": has_all_bands,
            "band_assets": band_assets,
        })

    scenes.sort(key=lambda s: (s["cloud_cover_pct"], s["datetime"]))
    return {
        "http_status": http_code,
        "scene_count": len(scenes),
        "scenes": scenes,
    }
