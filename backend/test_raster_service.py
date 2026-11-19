import json
import math
import unittest

from raster_service import (
    aggregate_field_raster,
    analyze_temporal_observations,
    dn_to_reflectance,
    evaluate_pixel,
    is_scl_masked,
    safe_normalized_difference,
    safe_psri,
)


class TestSentinel2RasterService(unittest.TestCase):
    def test_dn_to_reflectance_and_nodata(self):
        self.assertAlmostEqual(dn_to_reflectance(5000), 0.5, places=4)
        self.assertTrue(math.isnan(dn_to_reflectance(0)))
        self.assertTrue(math.isnan(dn_to_reflectance(65535)))

    def test_spectral_indices_mathematics(self):
        # Healthy crop canopy pixel:
        # B02=0.04, B04=0.06, B05=0.12, B06=0.28, B08=0.42, B8A=0.40, B11=0.18, SCL=4 (Vegetation)
        ev = evaluate_pixel(
            b02_blue=0.04,
            b04_red=0.06,
            b05_red_edge1=0.12,
            b06_red_edge2=0.28,
            b08_nir=0.42,
            b8a_narrow_nir=0.40,
            b11_swir1=0.18,
            scl_class=4,
        )
        self.assertTrue(ev.is_valid)
        self.assertFalse(ev.is_cloud_or_shadow_masked)
        # NDVI = (0.42 - 0.06) / (0.42 + 0.06) = 0.36 / 0.48 = 0.75
        self.assertAlmostEqual(ev.ndvi, 0.75, places=4)
        # NDRE = (0.40 - 0.12) / (0.40 + 0.12) = 0.28 / 0.52 = 0.53846
        self.assertAlmostEqual(ev.ndre, 0.28 / 0.52, places=4)
        # NDMI = (0.42 - 0.18) / (0.42 + 0.18) = 0.24 / 0.60 = 0.40
        self.assertAlmostEqual(ev.ndmi, 0.40, places=4)
        # PSRI = (0.06 - 0.04) / 0.28 = 0.02 / 0.28 = 0.0714
        self.assertAlmostEqual(ev.psri, 0.02 / 0.28, places=4)

    def test_cloud_and_shadow_masking(self):
        for cloud_scl in (0, 1, 3, 8, 9, 10, 11):
            self.assertTrue(is_scl_masked(cloud_scl))
            ev = evaluate_pixel(0.04, 0.06, 0.12, 0.28, 0.42, 0.40, 0.18, scl_class=cloud_scl)
            self.assertFalse(ev.is_valid)
            self.assertTrue(ev.is_cloud_or_shadow_masked)

    def test_zero_denominator_safety(self):
        self.assertTrue(math.isnan(safe_normalized_difference(0.0, 0.0)))
        self.assertTrue(math.isnan(safe_psri(0.05, 0.03, 0.0)))

    def test_aggregate_field_raster_and_geojson_output(self):
        healthy_px = evaluate_pixel(0.04, 0.06, 0.12, 0.28, 0.42, 0.40, 0.18, scl_class=4)
        stressed_px = evaluate_pixel(0.06, 0.16, 0.18, 0.22, 0.26, 0.23, 0.24, scl_class=5)
        cloud_px = evaluate_pixel(0.35, 0.36, 0.38, 0.40, 0.45, 0.44, 0.35, scl_class=9)

        stats = aggregate_field_raster(
            pixels=[healthy_px, healthy_px, stressed_px, cloud_px],
            center_lat=30.3833,
            center_lon=30.3500,
        )
        self.assertEqual(stats.total_pixel_count, 4)
        self.assertEqual(stats.valid_pixel_count, 3)
        self.assertEqual(stats.masked_cloud_or_nodata_count, 1)
        self.assertGreaterEqual(stats.anomaly_pixel_count, 1)

        parsed_geojson = json.loads(stats.anomaly_zones_geojson)
        self.assertEqual(parsed_geojson["type"], "FeatureCollection")
        self.assertEqual(len(parsed_geojson["features"]), 1)

    def test_temporal_analysis_excludes_cloudy_scenes_from_decline_alerts(self):
        scenes = [
            {"scene_id": "S2A_CLOUDY", "cloud_cover_pct": 65.0, "ndvi_mean": 0.12, "ndre_mean": 0.08, "ndmi_mean": 0.05},
            {"scene_id": "S2B_CLEAR_NEW", "cloud_cover_pct": 4.0, "ndvi_mean": 0.61, "ndre_mean": 0.30, "ndmi_mean": 0.28},
            {"scene_id": "S2A_CLEAR_OLD", "cloud_cover_pct": 2.5, "ndvi_mean": 0.72, "ndre_mean": 0.38, "ndmi_mean": 0.34},
        ]
        result = analyze_temporal_observations(scenes)
        self.assertEqual(result["usable_clear_scene_count"], 2)
        self.assertEqual(result["cloud_gapped_scene_count"], 1)
        self.assertAlmostEqual(result["ndvi_delta_from_previous_clear_pass"], -0.11, places=4)
        self.assertTrue(result["is_sudden_chlorophyll_drop_detected"])


if __name__ == "__main__":
    unittest.main()
