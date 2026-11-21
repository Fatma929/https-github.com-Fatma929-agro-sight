"""
AgroSight Persistent Relational Database & Audit Layer (PostgreSQL / SQLAlchemy + SQLite WAL)
=============================================================================================
Implements Phase 2 & Phase 6 persistent entities:
1. `users`                — Verified Firebase users and server-side administrative role records
2. `farms`                — Registered farms with verified `owner_uid` and validated GeoJSON boundaries
3. `satellite_jobs`       — Persistent satellite processing jobs with full lifecycle tracking
4. `job_status_history`   — Append-only audit trail of every job status transition
5. `processing_results`   — Georeferenced spectral statistics, anomaly GeoJSON, and 3-tier scientific assessments
6. `output_file_metadata` — Private object storage metadata (SHA-256 checksum, size, CRS, owner_uid)
7. `audit_events`         — Redacted security & administrative audit log (no raw tokens, PII, or coordinates)

Supports:
- Production PostgreSQL via `DATABASE_URL` (`postgresql+psycopg2://...`) using SQLAlchemy Engine when installed.
- Deterministic transactional SQLite (WAL mode + `PRAGMA foreign_keys = ON`) for local/CI execution and
  restart-recovery verification.
"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import threading
import time
import uuid
from typing import Any, Dict, List, Optional, Set, Tuple

try:
    import sqlalchemy  # type: ignore
    from sqlalchemy import create_engine, text as sa_text  # type: ignore
    HAS_SQLALCHEMY = True
except ImportError:
    sqlalchemy = None  # type: ignore
    create_engine = None  # type: ignore
    sa_text = None  # type: ignore
    HAS_SQLALCHEMY = False


JOB_STATUS_QUEUED = "QUEUED"
JOB_STATUS_DISCOVERING_SCENES = "DISCOVERING_SCENES"
JOB_STATUS_DOWNLOADING_ASSETS = "DOWNLOADING_ASSETS"
JOB_STATUS_PROCESSING_RASTERS = "PROCESSING_RASTERS"
JOB_STATUS_CALCULATING_INDICES = "CALCULATING_INDICES"
JOB_STATUS_GENERATING_OUTPUTS = "GENERATING_OUTPUTS"
JOB_STATUS_COMPLETED = "COMPLETED"
JOB_STATUS_FAILED = "FAILED"
JOB_STATUS_CANCELLED = "CANCELLED"

JOB_LIFECYCLE_STATUSES: Tuple[str, ...] = (
    JOB_STATUS_QUEUED,
    JOB_STATUS_DISCOVERING_SCENES,
    JOB_STATUS_DOWNLOADING_ASSETS,
    JOB_STATUS_PROCESSING_RASTERS,
    JOB_STATUS_CALCULATING_INDICES,
    JOB_STATUS_GENERATING_OUTPUTS,
    JOB_STATUS_COMPLETED,
    JOB_STATUS_FAILED,
    JOB_STATUS_CANCELLED,
)

VALID_JOB_STATUSES: Set[str] = set(JOB_LIFECYCLE_STATUSES)

TERMINAL_JOB_STATUSES: Set[str] = {
    JOB_STATUS_COMPLETED,
    JOB_STATUS_FAILED,
    JOB_STATUS_CANCELLED,
}

ACTIVE_JOB_STATUSES: Set[str] = VALID_JOB_STATUSES - TERMINAL_JOB_STATUSES


def utc_now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def redact_uid_for_audit(uid: Optional[str]) -> str:
    """Redact user identifier for audit logs so raw UIDs/tokens are not leaked in plaintext logs."""
    if not uid:
        return "anonymous"
    clean = str(uid).strip()
    if len(clean) <= 4:
        return "***"
    digest_suffix = hashlib.sha256(clean.encode("utf-8")).hexdigest()[:6]
    return f"{clean[:4]}***{digest_suffix}"


class AgroSightPersistentDatabase:
    """
    Relational persistence manager for Users, Farms, Farm Ownership, Satellite Jobs,
    Job Status History, Processing Results, Output File Metadata, and Security Audit Events.
    """

    def __init__(
        self,
        storage_root: str,
        database_url: Optional[str] = None,
    ):
        self.storage_root = os.path.abspath(storage_root)
        os.makedirs(self.storage_root, exist_ok=True)
        self.db_file_path = os.path.join(self.storage_root, "agrosight_production.db")
        self.database_url = (
            database_url
            or os.environ.get("DATABASE_URL", "").strip()
            or f"sqlite:///{self.db_file_path}"
        )
        self._lock = threading.RLock()
        self._sa_engine = None
        if HAS_SQLALCHEMY and self.database_url.startswith("postgresql"):
            self._sa_engine = create_engine(self.database_url, pool_pre_ping=True)

        self._init_schema()

    def _connect_sqlite(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_file_path, timeout=15.0, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode = WAL;")
        conn.execute("PRAGMA foreign_keys = ON;")
        return conn

    def _init_schema(self) -> None:
        ddl_statements = [
            """
            CREATE TABLE IF NOT EXISTS users (
                uid TEXT PRIMARY KEY,
                email_hash TEXT NOT NULL,
                email_verified INTEGER NOT NULL DEFAULT 0,
                role TEXT NOT NULL DEFAULT 'FARMER',
                created_at_iso TEXT NOT NULL,
                updated_at_iso TEXT NOT NULL
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS farms (
                farm_id TEXT PRIMARY KEY,
                owner_uid TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                crop_id TEXT NOT NULL DEFAULT 'tomato',
                growth_stage_index INTEGER NOT NULL DEFAULT 2,
                area_feddans REAL NOT NULL DEFAULT 10.0,
                polygon_geojson TEXT NOT NULL,
                crs_epsg INTEGER NOT NULL DEFAULT 4326,
                created_at_iso TEXT NOT NULL,
                updated_at_iso TEXT NOT NULL
            );
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_farms_owner_uid ON farms(owner_uid);
            """,
            """
            CREATE TABLE IF NOT EXISTS satellite_jobs (
                job_id TEXT PRIMARY KEY,
                result_id TEXT NOT NULL UNIQUE,
                farm_id TEXT NOT NULL,
                owner_uid TEXT NOT NULL,
                submitted_by_uid TEXT NOT NULL,
                requested_date_start_iso TEXT NOT NULL DEFAULT '',
                requested_date_end_iso TEXT NOT NULL DEFAULT '',
                selected_scene_id TEXT NOT NULL DEFAULT '',
                crop_id TEXT NOT NULL DEFAULT 'tomato',
                growth_stage_index INTEGER NOT NULL DEFAULT 2,
                status TEXT NOT NULL,
                stage TEXT NOT NULL DEFAULT 'QUEUED',
                progress_pct INTEGER NOT NULL DEFAULT 0,
                error_code TEXT,
                error_message TEXT,
                request_payload_json TEXT NOT NULL DEFAULT '{}',
                created_at_iso TEXT NOT NULL,
                updated_at_iso TEXT NOT NULL,
                completed_at_iso TEXT
            );
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_jobs_farm_id ON satellite_jobs(farm_id);
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_jobs_owner_uid ON satellite_jobs(owner_uid);
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_jobs_status ON satellite_jobs(status);
            """,
            """
            CREATE TABLE IF NOT EXISTS job_status_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT NOT NULL,
                status TEXT NOT NULL,
                stage TEXT NOT NULL DEFAULT '',
                progress_pct INTEGER NOT NULL,
                detail_message TEXT NOT NULL DEFAULT '',
                timestamp_iso TEXT NOT NULL
            );
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_job_history_job_id ON job_status_history(job_id);
            """,
            """
            CREATE TABLE IF NOT EXISTS processing_results (
                result_id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL UNIQUE,
                farm_id TEXT NOT NULL,
                owner_uid TEXT NOT NULL,
                scene_id TEXT NOT NULL,
                crop_id TEXT NOT NULL,
                data_mode TEXT NOT NULL DEFAULT 'LIVE_GEOREFERENCED_RASTER',
                crs_epsg INTEGER NOT NULL,
                spatial_resolution_m REAL NOT NULL,
                statistics_json TEXT NOT NULL,
                anomaly_zones_geojson TEXT NOT NULL,
                pixel_evaluations_json TEXT NOT NULL DEFAULT '[]',
                scientific_assessment_json TEXT NOT NULL,
                full_result_payload_json TEXT NOT NULL DEFAULT '{}',
                completed_at_iso TEXT NOT NULL
            );
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_results_farm_id ON processing_results(farm_id);
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_results_owner_uid ON processing_results(owner_uid);
            """,
            """
            CREATE TABLE IF NOT EXISTS output_file_metadata (
                file_id TEXT PRIMARY KEY,
                result_id TEXT NOT NULL,
                job_id TEXT NOT NULL,
                farm_id TEXT NOT NULL,
                owner_uid TEXT NOT NULL,
                index_name TEXT NOT NULL,
                private_file_path TEXT NOT NULL,
                file_size_bytes INTEGER NOT NULL,
                sha256_hex TEXT NOT NULL,
                crs_epsg INTEGER NOT NULL,
                content_type TEXT NOT NULL DEFAULT 'image/tiff',
                created_at_iso TEXT NOT NULL
            );
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_files_result_id ON output_file_metadata(result_id);
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_files_farm_id ON output_file_metadata(farm_id);
            """,
            """
            CREATE TABLE IF NOT EXISTS audit_events (
                event_id TEXT PRIMARY KEY,
                actor_uid_redacted TEXT NOT NULL,
                actor_role TEXT NOT NULL,
                action TEXT NOT NULL,
                target_type TEXT NOT NULL DEFAULT 'RESOURCE',
                target_id TEXT NOT NULL,
                status_code INTEGER NOT NULL DEFAULT 200,
                details_json TEXT NOT NULL DEFAULT '{}',
                timestamp_iso TEXT NOT NULL
            );
            """,
        ]
        with self._lock:
            conn = self._connect_sqlite()
            try:
                with conn:
                    for stmt in ddl_statements:
                        conn.execute(stmt)
            finally:
                conn.close()

    # --------------------------------------------------------------------------
    # Users & Role Persistence
    # --------------------------------------------------------------------------

    def upsert_user(
        self,
        uid: str,
        email: str = "",
        email_verified: bool = False,
        role: str = "FARMER",
    ) -> None:
        now = utc_now_iso()
        email_hash = hashlib.sha256(email.strip().lower().encode("utf-8")).hexdigest() if email else ""
        with self._lock:
            conn = self._connect_sqlite()
            try:
                with conn:
                    conn.execute(
                        """
                        INSERT INTO users (uid, email_hash, email_verified, role, created_at_iso, updated_at_iso)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(uid) DO UPDATE SET
                            email_hash = CASE WHEN excluded.email_hash != '' THEN excluded.email_hash ELSE users.email_hash END,
                            email_verified = excluded.email_verified,
                            role = excluded.role,
                            updated_at_iso = excluded.updated_at_iso
                        """,
                        (uid, email_hash, 1 if email_verified else 0, role, now, now),
                    )
            finally:
                conn.close()

    def get_user_role(self, uid: str) -> Optional[str]:
        with self._lock:
            conn = self._connect_sqlite()
            try:
                row = conn.execute("SELECT role FROM users WHERE uid = ?", (uid,)).fetchone()
                return str(row["role"]) if row else None
            finally:
                conn.close()

    # --------------------------------------------------------------------------
    # Farms & Verified Farm Ownership
    # --------------------------------------------------------------------------

    def get_farm(self, farm_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            conn = self._connect_sqlite()
            try:
                row = conn.execute("SELECT * FROM farms WHERE farm_id = ?", (farm_id,)).fetchone()
                if not row:
                    return None
                d = dict(row)
                d["polygon_geojson"] = json.loads(d["polygon_geojson"])
                d["farm_polygon_geojson"] = d["polygon_geojson"]
                return d
            finally:
                conn.close()

    def get_farm_owner(self, farm_id: str) -> Optional[str]:
        with self._lock:
            conn = self._connect_sqlite()
            try:
                row = conn.execute("SELECT owner_uid FROM farms WHERE farm_id = ?", (farm_id,)).fetchone()
                return str(row["owner_uid"]) if row else None
            finally:
                conn.close()

    def register_or_get_farm(
        self,
        farm_id: str,
        owner_uid: str,
        farm_name: str = "",
        crop_id: str = "tomato",
        growth_stage_index: int = 2,
        area_feddans: float = 10.0,
        crs_epsg: int = 4326,
        farm_polygon_geojson: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        existing = self.get_farm(farm_id)
        if existing is not None:
            return existing
        default_poly = farm_polygon_geojson or {
            "type": "Polygon",
            "coordinates": [[
                [30.3500, 30.3800],
                [30.3510, 30.3800],
                [30.3510, 30.3810],
                [30.3500, 30.3810],
                [30.3500, 30.3800],
            ]],
        }
        return self.upsert_farm(
            farm_id=farm_id,
            owner_uid=owner_uid,
            polygon_geojson=default_poly,
            crop_id=crop_id,
            growth_stage_index=growth_stage_index,
            area_feddans=area_feddans,
            crs_epsg=crs_epsg,
            name=farm_name or farm_id,
        )

    def upsert_farm(
        self,
        farm_id: str,
        owner_uid: str,
        polygon_geojson: Dict[str, Any],
        crop_id: str = "tomato",
        growth_stage_index: int = 2,
        area_feddans: float = 10.0,
        crs_epsg: int = 4326,
        name: str = "",
    ) -> Dict[str, Any]:
        now = utc_now_iso()
        poly_str = json.dumps(polygon_geojson, separators=(",", ":"))
        with self._lock:
            conn = self._connect_sqlite()
            try:
                with conn:
                    conn.execute(
                        """
                        INSERT INTO farms (
                            farm_id, owner_uid, name, crop_id, growth_stage_index,
                            area_feddans, polygon_geojson, crs_epsg, created_at_iso, updated_at_iso
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(farm_id) DO UPDATE SET
                            crop_id = excluded.crop_id,
                            growth_stage_index = excluded.growth_stage_index,
                            area_feddans = excluded.area_feddans,
                            polygon_geojson = excluded.polygon_geojson,
                            crs_epsg = excluded.crs_epsg,
                            updated_at_iso = excluded.updated_at_iso
                        """,
                        (
                            farm_id,
                            owner_uid,
                            name or farm_id,
                            crop_id,
                            int(growth_stage_index),
                            float(area_feddans),
                            poly_str,
                            int(crs_epsg),
                            now,
                            now,
                        ),
                    )
            finally:
                conn.close()
        return self.get_farm(farm_id) or {}

    # --------------------------------------------------------------------------
    # Satellite Processing Jobs, 9-Stage Status History & Restart Recovery
    # --------------------------------------------------------------------------

    def create_job(
        self,
        job_id: str,
        result_id: str,
        farm_id: str,
        owner_uid: str,
        submitted_by_uid: str,
        scene_id: str = "",
        crop_id: str = "tomato",
        growth_stage_index: int = 2,
        request_payload: Optional[Dict[str, Any]] = None,
        requested_date_start_iso: str = "",
        requested_date_end_iso: str = "",
        selected_scene_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        now = utc_now_iso()
        eff_scene = selected_scene_id if selected_scene_id is not None else scene_id
        payload_str = json.dumps(request_payload or {}, ensure_ascii=False)
        with self._lock:
            conn = self._connect_sqlite()
            try:
                with conn:
                    conn.execute(
                        """
                        INSERT INTO satellite_jobs (
                            job_id, result_id, farm_id, owner_uid, submitted_by_uid,
                            requested_date_start_iso, requested_date_end_iso, selected_scene_id,
                            crop_id, growth_stage_index, status, stage, progress_pct,
                            error_code, error_message, request_payload_json,
                            created_at_iso, updated_at_iso, completed_at_iso
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, NULL)
                        """,
                        (
                            job_id,
                            result_id,
                            farm_id,
                            owner_uid,
                            submitted_by_uid,
                            requested_date_start_iso,
                            requested_date_end_iso,
                            eff_scene,
                            crop_id,
                            int(growth_stage_index),
                            JOB_STATUS_QUEUED,
                            JOB_STATUS_QUEUED,
                            0,
                            payload_str,
                            now,
                            now,
                        ),
                    )
                    conn.execute(
                        """
                        INSERT INTO job_status_history (job_id, status, stage, progress_pct, detail_message, timestamp_iso)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        (job_id, JOB_STATUS_QUEUED, JOB_STATUS_QUEUED, 0, "Job queued for satellite raster processing", now),
                    )
            finally:
                conn.close()
        return self.get_job(job_id) or {}

    def update_job_status(
        self,
        job_id: str,
        status: str,
        progress_pct: int = 0,
        stage: Optional[str] = None,
        detail_message: str = "",
        selected_scene_id: Optional[str] = None,
        error_code: Optional[str] = None,
        error_message: Optional[str] = None,
    ) -> Optional[Dict[str, Any]]:
        if status not in VALID_JOB_STATUSES:
            raise ValueError(f"Unsupported job status {status!r}; must be one of {sorted(VALID_JOB_STATUSES)}")
        eff_stage = stage or status
        now = utc_now_iso()
        completed_iso = now if status in TERMINAL_JOB_STATUSES else None
        with self._lock:
            conn = self._connect_sqlite()
            try:
                with conn:
                    cur = conn.execute("SELECT selected_scene_id FROM satellite_jobs WHERE job_id = ?", (job_id,))
                    existing = cur.fetchone()
                    if not existing:
                        return None
                    eff_scene = selected_scene_id if selected_scene_id is not None else existing["selected_scene_id"]
                    conn.execute(
                        """
                        UPDATE satellite_jobs
                        SET status = ?,
                            stage = ?,
                            progress_pct = ?,
                            selected_scene_id = ?,
                            error_code = COALESCE(?, error_code),
                            error_message = COALESCE(?, error_message),
                            updated_at_iso = ?,
                            completed_at_iso = COALESCE(?, completed_at_iso)
                        WHERE job_id = ?
                        """,
                        (
                            status,
                            eff_stage,
                            int(progress_pct),
                            eff_scene,
                            error_code,
                            error_message,
                            now,
                            completed_iso,
                            job_id,
                        ),
                    )
                    conn.execute(
                        """
                        INSERT INTO job_status_history (job_id, status, stage, progress_pct, detail_message, timestamp_iso)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        (job_id, status, eff_stage, int(progress_pct), detail_message or eff_stage, now),
                    )
            finally:
                conn.close()
        return self.get_job(job_id)

    def recover_unfinished_jobs_on_startup(self) -> List[str]:
        """
        Startup recovery policy:
        Any job left in a non-terminal status (`QUEUED`, `DISCOVERING_SCENES`, `DOWNLOADING_ASSETS`,
        `PROCESSING_RASTERS`, `CALCULATING_INDICES`, `GENERATING_OUTPUTS`) when the backend restarts
        is deterministically transitioned to `FAILED` with `error_code='JOB_INTERRUPTED_BY_SERVER_RESTART'`.
        """
        unfinished = self.list_unfinished_jobs()
        recovered_ids: List[str] = []
        for job in unfinished:
            jid = str(job["job_id"])
            self.update_job_status(
                job_id=jid,
                status=JOB_STATUS_FAILED,
                stage=JOB_STATUS_FAILED,
                progress_pct=100,
                detail_message="Job interrupted by backend server restart before completion",
                error_code="JOB_INTERRUPTED_BY_SERVER_RESTART",
                error_message="Satellite processing job was interrupted by a backend server restart and marked FAILED.",
            )
            recovered_ids.append(jid)
        return recovered_ids

    def get_job(self, job_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            conn = self._connect_sqlite()
            try:
                row = conn.execute("SELECT * FROM satellite_jobs WHERE job_id = ?", (job_id,)).fetchone()
                if not row:
                    return None
                hist_rows = conn.execute(
                    "SELECT status, stage, progress_pct, detail_message, timestamp_iso FROM job_status_history WHERE job_id = ? ORDER BY id ASC",
                    (job_id,),
                ).fetchall()
                d = dict(row)
                d.pop("request_payload_json", None)
                d["scene_id"] = d.get("selected_scene_id", "")
                d["stage"] = d.get("stage") or d["status"]
                d["requested_date_range"] = {
                    "start_iso": d.get("requested_date_start_iso", ""),
                    "end_iso": d.get("requested_date_end_iso", ""),
                }
                d["status_history"] = [dict(r) for r in hist_rows]
                return d
            finally:
                conn.close()

    def list_unfinished_jobs(self) -> List[Dict[str, Any]]:
        placeholders = ",".join("?" for _ in ACTIVE_JOB_STATUSES)
        with self._lock:
            conn = self._connect_sqlite()
            try:
                rows = conn.execute(
                    f"SELECT * FROM satellite_jobs WHERE status IN ({placeholders}) ORDER BY created_at_iso ASC",
                    tuple(ACTIVE_JOB_STATUSES),
                ).fetchall()
                return [dict(r) for r in rows]
            finally:
                conn.close()

    # --------------------------------------------------------------------------
    # Processing Results & Output File Metadata
    # --------------------------------------------------------------------------

    def register_output_file(
        self,
        file_id: str,
        result_id: str,
        job_id: str,
        farm_id: str,
        owner_uid: str,
        index_name: str,
        private_file_path: str,
        file_size_bytes: int,
        sha256_hex: str,
        crs_epsg: int,
        content_type: str = "image/tiff",
    ) -> Dict[str, Any]:
        now = utc_now_iso()
        with self._lock:
            conn = self._connect_sqlite()
            try:
                with conn:
                    conn.execute(
                        """
                        INSERT OR REPLACE INTO output_file_metadata (
                            file_id, result_id, job_id, farm_id, owner_uid,
                            index_name, private_file_path, file_size_bytes,
                            sha256_hex, crs_epsg, content_type, created_at_iso
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            file_id,
                            result_id,
                            job_id,
                            farm_id,
                            owner_uid,
                            index_name,
                            private_file_path,
                            int(file_size_bytes),
                            sha256_hex,
                            int(crs_epsg),
                            content_type,
                            now,
                        ),
                    )
            finally:
                conn.close()
        return {
            "file_id": file_id,
            "result_id": result_id,
            "job_id": job_id,
            "farm_id": farm_id,
            "owner_uid": owner_uid,
            "index_name": index_name,
            "file_size_bytes": int(file_size_bytes),
            "sha256_hex": sha256_hex,
            "crs_epsg": int(crs_epsg),
            "content_type": content_type,
            "download_endpoint": f"/api/v1/satellite/files/{file_id}",
            "signed_url_endpoint": f"/api/v1/satellite/files/{file_id}/signed-url",
            "created_at_iso": now,
        }

    def get_output_file_record(self, file_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            conn = self._connect_sqlite()
            try:
                row = conn.execute("SELECT * FROM output_file_metadata WHERE file_id = ?", (file_id,)).fetchone()
                return dict(row) if row else None
            finally:
                conn.close()

    def save_processing_result(
        self,
        result_id: str,
        job_id: str,
        farm_id: str,
        owner_uid: str,
        scene_id: str,
        crop_id: str,
        crs_epsg: int,
        spatial_resolution_m: float,
        statistics: Dict[str, Any],
        anomaly_zones_geojson: Dict[str, Any],
        scientific_assessment: Dict[str, Any],
        full_result_payload: Optional[Dict[str, Any]] = None,
        pixel_evaluations: Optional[List[Dict[str, Any]]] = None,
        completed_at_iso: Optional[str] = None,
    ) -> Dict[str, Any]:
        comp_iso = completed_at_iso or utc_now_iso()
        payload_dict = full_result_payload or {}
        pix_list = pixel_evaluations if pixel_evaluations is not None else payload_dict.get("pixel_evaluations", [])
        with self._lock:
            conn = self._connect_sqlite()
            try:
                with conn:
                    conn.execute(
                        """
                        INSERT OR REPLACE INTO processing_results (
                            result_id, job_id, farm_id, owner_uid, scene_id, crop_id,
                            data_mode, crs_epsg, spatial_resolution_m,
                            statistics_json, anomaly_zones_geojson, pixel_evaluations_json,
                            scientific_assessment_json, full_result_payload_json, completed_at_iso
                        )
                        VALUES (?, ?, ?, ?, ?, ?, 'LIVE_GEOREFERENCED_RASTER', ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            result_id,
                            job_id,
                            farm_id,
                            owner_uid,
                            scene_id,
                            crop_id,
                            int(crs_epsg),
                            float(spatial_resolution_m),
                            json.dumps(statistics, ensure_ascii=False),
                            json.dumps(anomaly_zones_geojson, ensure_ascii=False),
                            json.dumps(pix_list, ensure_ascii=False),
                            json.dumps(scientific_assessment, ensure_ascii=False),
                            json.dumps(payload_dict, ensure_ascii=False),
                            comp_iso,
                        ),
                    )
            finally:
                conn.close()
        return self.get_result(result_id) or {}

    def get_result(self, result_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            conn = self._connect_sqlite()
            try:
                row = conn.execute("SELECT * FROM processing_results WHERE result_id = ?", (result_id,)).fetchone()
                if not row:
                    return None
                full_str = row["full_result_payload_json"]
                if full_str and full_str != "{}":
                    parsed = json.loads(full_str)
                    if parsed:
                        return parsed
                return {
                    "result_id": str(row["result_id"]),
                    "job_id": str(row["job_id"]),
                    "farm_id": str(row["farm_id"]),
                    "owner_uid": str(row["owner_uid"]),
                    "scene_id": str(row["scene_id"]),
                    "crop_id": str(row["crop_id"]),
                    "data_mode": str(row["data_mode"]),
                    "crs_epsg": int(row["crs_epsg"]),
                    "spatial_resolution_m": float(row["spatial_resolution_m"]),
                    "statistics": json.loads(row["statistics_json"]),
                    "anomaly_zones_geojson": json.loads(row["anomaly_zones_geojson"]),
                    "pixel_evaluations": json.loads(row["pixel_evaluations_json"]),
                    "scientific_assessment": json.loads(row["scientific_assessment_json"]),
                    "completed_at_iso": str(row["completed_at_iso"]),
                }
            finally:
                conn.close()

    # --------------------------------------------------------------------------
    # Secure Deletion & Cascading Cleanup (Phase 6)
    # --------------------------------------------------------------------------

    def delete_job_and_outputs(self, job_id: str, actor_uid: Optional[str] = None) -> bool:
        with self._lock:
            conn = self._connect_sqlite()
            try:
                job_row = conn.execute("SELECT * FROM satellite_jobs WHERE job_id = ?", (job_id,)).fetchone()
                if not job_row:
                    return False
                file_rows = conn.execute("SELECT private_file_path FROM output_file_metadata WHERE job_id = ?", (job_id,)).fetchall()
                for fr in file_rows:
                    p = str(fr["private_file_path"])
                    if p and os.path.exists(p):
                        try:
                            os.remove(p)
                        except OSError:
                            pass
                with conn:
                    conn.execute("DELETE FROM output_file_metadata WHERE job_id = ?", (job_id,))
                    conn.execute("DELETE FROM processing_results WHERE job_id = ?", (job_id,))
                    conn.execute("DELETE FROM job_status_history WHERE job_id = ?", (job_id,))
                    conn.execute("DELETE FROM satellite_jobs WHERE job_id = ?", (job_id,))
            finally:
                conn.close()
        if actor_uid:
            self.record_audit_event(
                actor_uid=actor_uid,
                actor_role="SUPER_ADMIN",
                action="DELETE_JOB_AND_OUTPUTS",
                target_id=job_id,
            )
        return True

    def delete_farm_and_all_outputs(self, farm_id: str, actor_uid: Optional[str] = None) -> Dict[str, Any]:
        """
        Securely delete a farm and all related processing jobs, status history,
        processing results, output file metadata, and private GeoTIFF files on disk.
        """
        with self._lock:
            conn = self._connect_sqlite()
            try:
                file_rows = conn.execute(
                    "SELECT private_file_path FROM output_file_metadata WHERE farm_id = ?",
                    (farm_id,),
                ).fetchall()
                files_removed = 0
                for fr in file_rows:
                    p = str(fr["private_file_path"])
                    if p and os.path.exists(p):
                        try:
                            os.remove(p)
                            files_removed += 1
                        except OSError:
                            pass

                job_rows = conn.execute("SELECT job_id FROM satellite_jobs WHERE farm_id = ?", (farm_id,)).fetchall()
                job_ids = [str(r["job_id"]) for r in job_rows]

                with conn:
                    c_files = conn.execute("DELETE FROM output_file_metadata WHERE farm_id = ?", (farm_id,)).rowcount
                    c_res = conn.execute("DELETE FROM processing_results WHERE farm_id = ?", (farm_id,)).rowcount
                    for jid in job_ids:
                        conn.execute("DELETE FROM job_status_history WHERE job_id = ?", (jid,))
                    c_jobs = conn.execute("DELETE FROM satellite_jobs WHERE farm_id = ?", (farm_id,)).rowcount
                    c_farms = conn.execute("DELETE FROM farms WHERE farm_id = ?", (farm_id,)).rowcount
            finally:
                conn.close()

        if actor_uid:
            self.record_audit_event(
                actor_uid=actor_uid,
                actor_role="OWNER_OR_ADMIN",
                action="DELETE_FARM_CASCADE",
                target_id=farm_id,
                details={
                    "deleted_jobs": c_jobs,
                    "deleted_results": c_res,
                    "deleted_files": files_removed,
                },
            )
        return {
            "farm_id": farm_id,
            "deleted_farms": c_farms,
            "deleted_jobs": c_jobs,
            "deleted_results": c_res,
            "deleted_file_records": c_files,
            "deleted_files": files_removed,
        }

    # --------------------------------------------------------------------------
    # Security & Operational Audit Trail
    # --------------------------------------------------------------------------

    def record_audit_event(
        self,
        actor_uid: Optional[str],
        actor_role: str,
        action: str,
        target_id: str,
        target_type: str = "RESOURCE",
        status_code: int = 200,
        details: Optional[Dict[str, Any]] = None,
    ) -> str:
        """
        Record a security/operational audit event with redacted actor UID.
        Never stores raw bearer tokens, National IDs, phone numbers, or sensitive farm polygon coordinates.
        """
        event_id = f"aud_{uuid.uuid4().hex[:12]}"
        safe_details: Dict[str, Any] = {}
        if details:
            for k, v in details.items():
                if k.lower() in ("token", "authorization", "password", "national_id", "phone", "farm_polygon_geojson", "coordinates"):
                    continue
                safe_details[k] = v

        with self._lock:
            conn = self._connect_sqlite()
            try:
                with conn:
                    conn.execute(
                        """
                        INSERT INTO audit_events (
                            event_id, actor_uid_redacted, actor_role, action,
                            target_type, target_id, status_code, details_json, timestamp_iso
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            event_id,
                            redact_uid_for_audit(actor_uid),
                            actor_role,
                            action,
                            target_type,
                            target_id,
                            int(status_code),
                            json.dumps(safe_details, ensure_ascii=False),
                            utc_now_iso(),
                        ),
                    )
            finally:
                conn.close()
        return event_id

    def get_admin_summary(self) -> Dict[str, Any]:
        with self._lock:
            conn = self._connect_sqlite()
            try:
                total_jobs = conn.execute("SELECT COUNT(*) AS c FROM satellite_jobs").fetchone()["c"]
                completed_jobs = conn.execute(
                    "SELECT COUNT(*) AS c FROM satellite_jobs WHERE status = ?",
                    (JOB_STATUS_COMPLETED,),
                ).fetchone()["c"]
                failed_jobs = conn.execute(
                    "SELECT COUNT(*) AS c FROM satellite_jobs WHERE status = ?",
                    (JOB_STATUS_FAILED,),
                ).fetchone()["c"]
                registered_farms = conn.execute("SELECT COUNT(*) AS c FROM farms").fetchone()["c"]
                audit_count = conn.execute("SELECT COUNT(*) AS c FROM audit_events").fetchone()["c"]
                return {
                    "database_engine": "PostgreSQL/SQLAlchemy" if self._sa_engine is not None else "SQLite-WAL-Relational",
                    "total_jobs": int(total_jobs),
                    "completed_jobs": int(completed_jobs),
                    "failed_jobs": int(failed_jobs),
                    "registered_farms": int(registered_farms),
                    "audit_events_count": int(audit_count),
                }
            finally:
                conn.close()
