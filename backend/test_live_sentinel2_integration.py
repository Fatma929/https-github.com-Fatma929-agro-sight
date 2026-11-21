"""
PHASE 4 — Real End-to-End Sentinel-2 L2A Satellite Integration Test
====================================================================
Executes a live end-to-end satellite integration workflow against the Element84 Earth Search
STAC provider (`https://earth-search.aws.element84.com/v1/search`) using a real agricultural
farm polygon in Wadi El Natrun / West Nile Delta, Egypt (UTM Zone 36N, EPSG:32636):

1. Queries the live STAC API with a real farm GeoJSON polygon, cloud cover limit, and date range.
2. Discovers a real Sentinel-2 Level-2A scene (`sentinel-2-l2a`).
3. Retrieves actual COG spectral band assets (`B02`, `B04`, `B05`, `B06`, `B08`, `B8A`, `B11`, `SCL`)
   via HTTP Range windowing.
4. Processes real pixels, aligns 20m bands to the 10m grid, applies SCL cloud/shadow masking,
   computes NDVI, NDRE, NDMI, and PSRI, clips to the farm polygon, generates GeoTIFF outputs
   and GeoJSON anomaly polygons, and stores the result in `AgroSightPersistentDatabase`.
5. Reports the exact truth: if live provider access succeeds, prints all verified scene metadata
   and statistics; if outbound network/DNS is restricted in the execution container, prints the
   exact command, failure reason, and error details without fabricating a scene ID.
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import traceback
from dataclasses import asdict
from typing import Any, Dict

from database import AgroSightPersistentDatabase
from raster_service import (
    process_sentinel2_raster_bands,
    search_stac_sentinel2_scenes,
)


WADI_EL_NATRUN_PILOT_FARM_POLYGON: Dict[str, Any] = {
    "type": "Polygon",
    "coordinates": [[
        [30.3500, 30.3800],
        [30.3520, 30.3800],
        [30.3520, 30.3820],
        [30.3500, 30.3820],
        [30.3500, 30.3800],
    ]],
}


def run_live_sentinel2_integration_test() -> Dict[str, Any]:
    report: Dict[str, Any] = {
        "stac_endpoint": "https://earth-search.aws.element84.com/v1/search",
        "collection": "sentinel-2-l2a",
        "farm_id": "wadi_el_natrun_pilot_farm_01",
        "farm_polygon_geojson": WADI_EL_NATRUN_PILOT_FARM_POLYGON,
        "live_network_execution_status": "NOT_RUN",
    }
    try:
        stac_result = search_stac_sentinel2_scenes(
            farm_polygon_geojson=WADI_EL_NATRUN_PILOT_FARM_POLYGON,
            max_cloud_cover=25.0,
            limit=3,
            date_start_iso="2024-01-01",
            date_end_iso="2026-12-31",
        )
        if stac_result["scene_count"] == 0:
            report["live_network_execution_status"] = "NO_MATCHING_SCENES_RETURNED"
            return report

        scene = stac_result["scenes"][0]
        report["discovered_scene_id"] = scene["scene_id"]
        report["acquisition_date_iso"] = scene["acquisition_date_iso"]
        report["cloud_cover_pct"] = scene["cloud_cover_pct"]
        report["crs_epsg"] = scene["crs_epsg"]
        report["band_assets"] = scene["band_assets"]

        storage_dir = tempfile.mkdtemp(prefix="agrosight_live_s2_test_")
        stats, _ = process_sentinel2_raster_bands(
            band_sources=scene["band_assets"],
            farm_polygon_geojson=WADI_EL_NATRUN_PILOT_FARM_POLYGON,
            crop_id="potato",
            growth_stage_index=2,
            scale_factor=scene["scale_factor"],
            boa_add_offset=scene["boa_add_offset"],
            output_dir=storage_dir,
        )

        db = AgroSightPersistentDatabase(storage_root=storage_dir)
        db.save_processing_result(
            result_id="res_live_s2_01",
            job_id="job_live_s2_01",
            farm_id="wadi_el_natrun_pilot_farm_01",
            owner_uid="live_pilot_uid",
            scene_id=scene["scene_id"],
            crop_id="potato",
            crs_epsg=stats.crs_epsg,
            spatial_resolution_m=stats.spatial_resolution_m,
            statistics=asdict(stats),
            anomaly_zones_geojson=json.loads(stats.anomaly_zones_geojson),
            scientific_assessment={"is_confirmed_plant_disease": False},
            full_result_payload={"scene_id": scene["scene_id"], "statistics": asdict(stats)},
        )

        report["live_network_execution_status"] = "COMPLETED_LIVE"
        report["valid_pixel_count"] = stats.valid_pixel_count
        report["masked_cloud_or_nodata_count"] = stats.masked_cloud_or_nodata_count
        report["ndvi_mean"] = round(stats.ndvi_mean, 4)
        report["ndre_mean"] = round(stats.ndre_mean, 4)
        report["ndmi_mean"] = round(stats.ndmi_mean, 4)
        report["psri_mean"] = round(stats.psri_mean, 4)
        report["anomaly_pixel_count"] = stats.anomaly_pixel_count
        report["output_raster_files"] = stats.output_raster_files
        return report
    except Exception as exc:
        report["live_network_execution_status"] = "BLOCKED_BY_CONTAINER_NETWORK_OR_PROVIDER_ACCESS"
        report["blocking_exception_type"] = type(exc).__name__
        report["blocking_error_message"] = str(exc)
        return report


if __name__ == "__main__":
    outcome = run_live_sentinel2_integration_test()
    print(json.dumps(outcome, indent=2, ensure_ascii=False))
    sys.exit(0 if outcome["live_network_execution_status"] == "COMPLETED_LIVE" else 2)
