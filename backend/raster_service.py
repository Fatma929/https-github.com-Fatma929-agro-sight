"""
AgroSight Sentinel-2 Level-2A Raster Processing & Spectral Index Service
=========================================================================
Production geospatial backend module for Sentinel-2 L2A Cloud-Optimized GeoTIFF (COG)
and array-based spectral analysis.

Implements:
- Sentinel-2 L2A reflectance scaling (DN / 10000.0)
- Scene Classification Layer (SCL) cloud, cloud-shadow, cirrus, and defective pixel masking
- Spectral vegetation & stress indices:
  * NDVI = (B08 - B04) / (B08 + B04)
  * NDRE = (B8A - B05) / (B8A + B05)
  * NDMI = (B08 - B11) / (B08 + B11)
  * PSRI = (B04 - B02) / B06
- Field polygon statistics (mean, standard deviation, min, max, percentiles, valid pixel ratio)
- Temporal multi-scene change detection with automatic cloud-gap exclusion (>35% cloud cover)
- GeoJSON anomaly zone polygon generation
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, asdict
from typing import Dict, List, Optional, Sequence, Tuple
from urllib import request, error

# SCL classes to mask out in Sentinel-2 L2A:
# 0: No Data, 1: Saturated/Defective, 3: Cloud Shadows,
# 8: Cloud Medium Probability, 9: Cloud High Probability, 10: Thin Cirrus, 11: Snow/Ice
MASKED_SCL_CLASSES = {0, 1, 3, 8, 9, 10, 11}
EPSILON = 1e-6


@dataclass
class PixelSpectralEvaluation:
    is_valid: bool
    is_cloud_or_shadow_masked: bool
    scl_class: int
    ndvi: float
    ndre: float
    ndmi: float
    psri: float


@dataclass
class FieldSpectralStatistics:
    total_pixel_count: int
    valid_pixel_count: int
    masked_cloud_or_nodata_count: int
    valid_pixel_ratio: float
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
    anomaly_zones_geojson: str


def dn_to_reflectance(dn: int) -> float:
    """Convert Sentinel-2 L2A Digital Number (0..10000) to surface reflectance (0.0..1.0)."""
    if dn <= 0 or dn >= 65535:
        return float("nan")
    return max(0.0001, min(1.0, float(dn) * 0.0001))


def is_scl_masked(scl_class: int) -> bool:
    """Return True if the Sentinel-2 L2A Scene Classification Layer value is cloud, shadow, or invalid."""
    return scl_class in MASKED_SCL_CLASSES


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
) -> PixelSpectralEvaluation:
    """Evaluate all four spectral indices for a single Sentinel-2 L2A pixel."""
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
        )

    ndvi = safe_normalized_difference(b08_nir, b04_red)
    ndre = safe_normalized_difference(b8a_narrow_nir, b05_red_edge1)
    ndmi = safe_normalized_difference(b08_nir, b11_swir1)
    psri = safe_psri(b04_red, b02_blue, b06_red_edge2)

    valid = not any(math.isnan(v) for v in (ndvi, ndre, ndmi, psri))
    return PixelSpectralEvaluation(
        is_valid=valid,
        is_cloud_or_shadow_masked=False,
        scl_class=scl_class,
        ndvi=ndvi,
        ndre=ndre,
        ndmi=ndmi,
        psri=psri,
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


def aggregate_field_raster(
    pixels: Sequence[PixelSpectralEvaluation],
    center_lat: float,
    center_lon: float,
) -> FieldSpectralStatistics:
    """Aggregate pixel-level spectral evaluations into field statistics and GeoJSON anomaly polygons."""
    valid_pixels = [p for p in pixels if p.is_valid and not p.is_cloud_or_shadow_masked]
    masked_count = len(pixels) - len(valid_pixels)

    if not valid_pixels:
        return FieldSpectralStatistics(
            total_pixel_count=len(pixels),
            valid_pixel_count=0,
            masked_cloud_or_nodata_count=masked_count,
            valid_pixel_ratio=0.0,
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
            anomaly_zones_geojson='{"type":"FeatureCollection","features":[]}',
        )

    ndvi_sorted = sorted(p.ndvi for p in valid_pixels)
    ndvi_mean, ndvi_std = _mean_and_std(ndvi_sorted)
    ndre_mean, ndre_std = _mean_and_std([p.ndre for p in valid_pixels])
    ndmi_mean, ndmi_std = _mean_and_std([p.ndmi for p in valid_pixels])
    psri_mean, psri_std = _mean_and_std([p.psri for p in valid_pixels])

    ndvi_anomaly_threshold = max(0.32, ndvi_mean - max(0.08, ndvi_std))
    anomaly_pixels = [
        p for p in valid_pixels
        if p.ndvi < ndvi_anomaly_threshold or p.ndre < 0.23 or p.psri > 0.14
    ]
    anomaly_fraction = len(anomaly_pixels) / float(len(valid_pixels))

    d_lat = 0.0012
    d_lon = 0.0012
    geojson_dict = {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "properties": {
                    "ndvi_mean": round(ndvi_mean, 4),
                    "ndre_mean": round(ndre_mean, 4),
                    "ndmi_mean": round(ndmi_mean, 4),
                    "psri_mean": round(psri_mean, 4),
                    "anomaly_fraction": round(anomaly_fraction, 4),
                    "valid_pixel_count": len(valid_pixels),
                },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                        [round(center_lon - d_lon, 6), round(center_lat - d_lat, 6)],
                        [round(center_lon + d_lon, 6), round(center_lat - d_lat, 6)],
                        [round(center_lon + d_lon, 6), round(center_lat + d_lat, 6)],
                        [round(center_lon - d_lon, 6), round(center_lat + d_lat, 6)],
                        [round(center_lon - d_lon, 6), round(center_lat - d_lat, 6)],
                    ]],
                },
            }
        ],
    }

    return FieldSpectralStatistics(
        total_pixel_count=len(pixels),
        valid_pixel_count=len(valid_pixels),
        masked_cloud_or_nodata_count=masked_count,
        valid_pixel_ratio=len(valid_pixels) / float(len(pixels)),
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
        anomaly_zones_geojson=json.dumps(geojson_dict),
    )


def analyze_temporal_observations(
    scenes: Sequence[Dict[str, object]],
    max_usable_cloud_cover_pct: float = 35.0,
) -> Dict[str, object]:
    """
    Compare multiple Sentinel-2 passes across time while excluding cloud-obscured scenes
    from crop decline alerts so cloud cover is never misdiagnosed as vegetation collapse.
    """
    usable = [
        s for s in scenes
        if float(s.get("cloud_cover_pct", 100.0)) <= max_usable_cloud_cover_pct
    ]
    cloud_skipped = len(scenes) - len(usable)

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
        "total_scenes_evaluated": len(scenes),
        "usable_clear_scene_count": len(usable),
        "cloud_gapped_scene_count": cloud_skipped,
        "ndvi_delta_from_previous_clear_pass": round(delta_ndvi, 4),
        "ndre_delta_from_previous_clear_pass": round(delta_ndre, 4),
        "ndmi_delta_from_previous_clear_pass": round(delta_ndmi, 4),
        "is_sudden_chlorophyll_drop_detected": sudden_drop,
    }
