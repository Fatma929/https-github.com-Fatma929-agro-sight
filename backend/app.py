"""
AgroSight Secure Satellite Processing Backend API (FastAPI + WSGI/HTTP Service)
================================================================================
Exposes endpoints required by the Android client, private file storage, and administrative governance:
- POST   /api/v1/satellite/scenes
- POST   /api/v1/satellite/process
- GET    /api/v1/satellite/jobs/{job_id}
- GET    /api/v1/satellite/results/{result_id}
- GET    /api/v1/satellite/files/{file_id}
- POST   /api/v1/satellite/files/{file_id}/signed-url
- GET    /api/v1/satellite/farms/{farm_id}
- DELETE /api/v1/satellite/farms/{farm_id}
- GET    /api/v1/admin/overview
- POST   /api/v1/admin/roles
- POST   /api/v1/admin/revoke-tokens
- DELETE /api/v1/admin/jobs/{job_id}
- GET    /api/v1/health

Security & Architectural Guarantees (Phase 1, Phase 2, Phase 6):
- Official Firebase Admin SDK (`firebase_admin.auth.verify_id_token(..., check_revoked=True)`)
  is the ONLY production Firebase ID-token verification mechanism.
- Zero custom RSA key generation, zero deterministic private-key generation, and zero default
  trusted test keys in production runtime.
- Fail-closed behavior: if Firebase Admin SDK credentials are missing or invalid in production,
  authentication fails securely (`503 FIREBASE_AUTH_NOT_CONFIGURED`) and NEVER silently falls
  back to test authentication.
- Strict validation of Firebase project identity (`aud == project_id`), issuer
  (`iss == https://securetoken.google.com/<project_id>`), expiration (`exp`), issued-at (`iat`),
  subject UID (`sub`), and token revocation status.
- Zero-trust role resolution (`FARMER`, `AGRONOMIST_ADMIN`, `SUPER_ADMIN`) read exclusively
  from verified Firebase Custom Claims or verified server-side role assignments. Never accepts
  client-supplied roles or unverified top-level role claims.
- Persistent relational database (`AgroSightPersistentDatabase` via PostgreSQL / SQLite WAL)
  storing users, farms, ownership, 9-stage job lifecycle history, results, private file metadata,
  and redacted audit events across server restarts.
- Private object storage for generated GeoTIFF rasters with authenticated ownership checks,
  short-lived HMAC-SHA256 signed download URLs, and cascading farm/file deletion.
"""

from __future__ import annotations

import concurrent.futures
import hashlib
import hmac
import json
import os
import secrets
import tempfile
import threading
import time
import uuid
from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, List, Optional, Set, Tuple
from urllib.parse import parse_qs, urlparse

from database import (
    JOB_LIFECYCLE_STATUSES,
    TERMINAL_JOB_STATUSES,
    AgroSightPersistentDatabase,
    redact_uid_for_audit,
)
from raster_service import (
    HAS_NUMPY,
    HAS_RASTERIO,
    HAS_SHAPELY,
    InvalidAssetUrlError,
    MissingBandError,
    RangeRequestNotSupportedError,
    RasterAccessError,
    RasterDownloadInterruptedError,
    RasterIntegrityError,
    UnsupportedCrsError,
    get_crop_thresholds,
    process_sentinel2_raster_bands,
    search_stac_sentinel2_scenes,
)

try:
    import firebase_admin  # type: ignore
    from firebase_admin import auth as fb_admin_auth  # type: ignore
    from firebase_admin import credentials as fb_admin_credentials  # type: ignore
    HAS_FIREBASE_ADMIN_SDK = True
except ImportError:
    firebase_admin = None  # type: ignore
    fb_admin_auth = None  # type: ignore
    fb_admin_credentials = None  # type: ignore
    HAS_FIREBASE_ADMIN_SDK = False


BOOTSTRAPPED_SUPER_ADMIN_EMAIL = "elzahraafatma288@gmail.com"
DEFAULT_FIREBASE_PROJECT_ID = "gen-lang-client-0569917869"
SUPPORTED_ROLES: Set[str] = {"FARMER", "AGRONOMIST_ADMIN", "SUPER_ADMIN"}


def _load_project_id_from_google_services() -> str:
    """Read the authoritative Firebase project_id from environment or app/google-services.json."""
    env_proj = os.environ.get("FIREBASE_PROJECT_ID", "").strip()
    if env_proj:
        return env_proj
    candidates = [
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "google-services.json"),
        os.path.join(os.getcwd(), "app", "google-services.json"),
    ]
    for path in candidates:
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                pid = str((data.get("project_info") or {}).get("project_id") or "").strip()
                if pid:
                    return pid
            except Exception:
                pass
    return DEFAULT_FIREBASE_PROJECT_ID


AUTHORITATIVE_FIREBASE_PROJECT_ID = _load_project_id_from_google_services()


class ApiHttpError(Exception):
    def __init__(self, status_code: int, error_code: str, message: str):
        super().__init__(message)
        self.status_code = status_code
        self.error_code = error_code
        self.message = message


# ==============================================================================
# Official Firebase Admin SDK ID-Token Authenticator (Zero Custom RSA / Fail-Closed)
# ==============================================================================

class FirebaseAdminAuthenticator:
    """
    Production Firebase ID Token authenticator using ONLY the official Firebase Admin SDK
    (`firebase_admin.auth.verify_id_token(token, check_revoked=True)`).

    Security guarantees:
    1. No custom RSA key generation, deterministic prime generation, or default trusted test
       keys exist in production runtime.
    2. Fails closed (`503 FIREBASE_AUTH_NOT_CONFIGURED`) if Firebase Admin SDK credentials
       are missing or invalid in production. Never silently falls back to test authentication.
    3. Validates `aud == project_id`, `iss == https://securetoken.google.com/<project_id>`,
       token expiration (`exp`), issued-at (`iat`), non-empty UID (`sub`), and token revocation.
    4. Never accepts a client-supplied role or unverified JWT payload role claim; reads roles
       exclusively from verified Firebase Custom Claims (`custom_claims` set via Firebase Admin)
       or verified server-side role assignments.
    """

    def __init__(
        self,
        project_id: str = AUTHORITATIVE_FIREBASE_PROJECT_ID,
        check_revoked: bool = True,
        sdk_token_verifier: Optional[Callable[[str, bool], Dict[str, Any]]] = None,
    ):
        if not project_id or not project_id.strip():
            raise ValueError("Firebase project_id must be a non-empty string")
        self.project_id = project_id.strip()
        self.expected_issuer = f"https://securetoken.google.com/{self.project_id}"
        self.check_revoked = bool(check_revoked)
        self._sdk_token_verifier = sdk_token_verifier

        self._lock = threading.Lock()
        self._revoked_token_ids: Set[str] = set()
        self._revoked_before_epoch_by_uid: Dict[str, int] = {}
        self._verified_custom_claims_by_uid: Dict[str, Dict[str, Any]] = {}

        self.firebase_admin_initialized = False
        self._firebase_app: Any = None
        if self._sdk_token_verifier is None and HAS_FIREBASE_ADMIN_SDK:
            self._try_init_firebase_admin()

    def _try_init_firebase_admin(self) -> None:
        """
        Initialize the official Firebase Admin SDK ONLY when valid credentials or explicit
        Firebase Auth Emulator configuration are present. Never falls back to a test key.
        """
        try:
            cred_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "").strip()
            emulator_host = os.environ.get("FIREBASE_AUTH_EMULATOR_HOST", "").strip()
            service_account_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip()

            try:
                app = firebase_admin.get_app()
                self._firebase_app = app
                self.firebase_admin_initialized = True
                return
            except ValueError:
                pass

            if cred_path and os.path.exists(cred_path):
                cred = fb_admin_credentials.Certificate(cred_path)
                self._firebase_app = firebase_admin.initialize_app(cred, {"projectId": self.project_id})
                self.firebase_admin_initialized = True
            elif service_account_json:
                cert_dict = json.loads(service_account_json)
                cred = fb_admin_credentials.Certificate(cert_dict)
                self._firebase_app = firebase_admin.initialize_app(cred, {"projectId": self.project_id})
                self.firebase_admin_initialized = True
            elif emulator_host:
                self._firebase_app = firebase_admin.initialize_app(options={"projectId": self.project_id})
                self.firebase_admin_initialized = True
            else:
                self.firebase_admin_initialized = False
        except Exception:
            self.firebase_admin_initialized = False

    @property
    def is_configured(self) -> bool:
        with self._lock:
            return self.firebase_admin_initialized or (self._sdk_token_verifier is not None)

    def set_verified_custom_claims(self, uid: str, custom_claims: Dict[str, Any]) -> None:
        """
        Assign verified Firebase Custom Claims for `uid` via `firebase_admin.auth.set_custom_user_claims`.
        """
        clean_uid = str(uid).strip()
        if not clean_uid:
            raise ValueError("uid must be non-empty")
        role = str(custom_claims.get("role") or "FARMER").strip().upper()
        if role not in SUPPORTED_ROLES:
            raise ValueError(f"Unsupported role in custom claims: {role!r}")
        with self._lock:
            self._verified_custom_claims_by_uid[clean_uid] = dict(custom_claims, role=role)
        if self.firebase_admin_initialized and HAS_FIREBASE_ADMIN_SDK:
            try:
                fb_admin_auth.set_custom_user_claims(clean_uid, {"role": role}, app=self._firebase_app)
            except Exception:
                pass

    def revoke_refresh_tokens(self, uid: str, revoked_at_epoch: Optional[int] = None) -> None:
        """Revoke all tokens issued for `uid` at or before `revoked_at_epoch`."""
        ts = int(revoked_at_epoch if revoked_at_epoch is not None else time.time())
        with self._lock:
            self._revoked_before_epoch_by_uid[uid] = ts
        if self.firebase_admin_initialized and HAS_FIREBASE_ADMIN_SDK:
            try:
                fb_admin_auth.revoke_refresh_tokens(uid, app=self._firebase_app)
            except Exception:
                pass

    def revoke_token_id(self, token_or_jti: str) -> None:
        """Explicitly mark a specific token or `jti` as revoked."""
        digest = hashlib.sha256(token_or_jti.encode("utf-8")).hexdigest()
        with self._lock:
            self._revoked_token_ids.add(token_or_jti)
            self._revoked_token_ids.add(digest)

    def _resolve_authoritative_role(
        self,
        uid: str,
        email: str,
        email_verified: bool,
        verified_custom_claims: Optional[Dict[str, Any]] = None,
    ) -> str:
        """
        Resolve the user's authoritative role using zero-trust rules:
        1. Bootstrapped Super Admin email (`elzahraafatma288@gmail.com` with `email_verified=True`).
        2. Server-side verified Firebase Custom Claims registry (`_verified_custom_claims_by_uid`).
        3. Verified Firebase Custom Claims sub-object (`firebase_custom_claims`) returned by
           `firebase_admin.auth.verify_id_token`.
        4. NEVER trusts a client-supplied role parameter or an unverified top-level `role` claim.
        """
        if email == BOOTSTRAPPED_SUPER_ADMIN_EMAIL and email_verified:
            return "SUPER_ADMIN"

        with self._lock:
            server_claims = self._verified_custom_claims_by_uid.get(uid)
        if server_claims and server_claims.get("role") in SUPPORTED_ROLES:
            return str(server_claims["role"])

        if isinstance(verified_custom_claims, dict):
            claim_role = str(verified_custom_claims.get("role") or "").strip().upper()
            if claim_role in SUPPORTED_ROLES:
                return claim_role

        return "FARMER"

    def verify_bearer_header(self, authorization_header: Optional[str]) -> Dict[str, Any]:
        """
        Verify a Firebase `Authorization: Bearer <id_token>` header using the official
        Firebase Admin SDK `verify_id_token` path and return authoritative claims.
        Fails closed (503) if Firebase Admin SDK is not configured.
        """
        if not authorization_header or not isinstance(authorization_header, str):
            raise ApiHttpError(401, "UNAUTHENTICATED", "Missing Authorization Bearer header")
        if not authorization_header.startswith("Bearer "):
            raise ApiHttpError(401, "UNAUTHENTICATED", "Authorization header must use Bearer scheme")

        token = authorization_header[len("Bearer ") :].strip()
        if not token:
            raise ApiHttpError(401, "UNAUTHENTICATED", "Bearer token is empty")

        if not self.is_configured:
            raise ApiHttpError(
                503,
                "FIREBASE_AUTH_NOT_CONFIGURED",
                "Firebase Admin SDK is not configured with valid credentials. Failing closed.",
            )

        # Verify token through Firebase Admin SDK (or injected test/emulator SDK verifier)
        try:
            if self._sdk_token_verifier is not None:
                decoded_claims = self._sdk_token_verifier(token, self.check_revoked)
            elif self.firebase_admin_initialized and HAS_FIREBASE_ADMIN_SDK:
                decoded_claims = fb_admin_auth.verify_id_token(
                    token,
                    app=self._firebase_app,
                    check_revoked=self.check_revoked,
                )
            else:
                raise ApiHttpError(
                    503,
                    "FIREBASE_AUTH_NOT_CONFIGURED",
                    "Firebase Admin SDK is not initialized. Refusing to fall back to test authentication.",
                )
        except ApiHttpError:
            raise
        except Exception as exc:
            err_name = type(exc).__name__
            err_msg = str(exc)
            if "Revoked" in err_name or "revoked" in err_msg.lower():
                raise ApiHttpError(401, "TOKEN_REVOKED", "Firebase ID token has been revoked") from exc
            if "Expired" in err_name or "expired" in err_msg.lower():
                raise ApiHttpError(401, "TOKEN_EXPIRED", "Firebase ID token has expired") from exc
            if "CertificateFetchError" in err_name:
                raise ApiHttpError(503, "FIREBASE_CERT_FETCH_FAILED", f"Could not fetch Google public certs: {exc}") from exc
            raise ApiHttpError(
                401,
                "INVALID_TOKEN_SIGNATURE",
                f"Firebase Admin SDK token verification failed: {exc}",
            ) from exc

        if not isinstance(decoded_claims, dict):
            raise ApiHttpError(401, "MALFORMED_TOKEN_CLAIMS", "Verified token claims must be a dictionary")

        now_epoch = int(time.time())

        # Validate expiration (exp)
        exp = decoded_claims.get("exp")
        if exp is None:
            raise ApiHttpError(401, "MISSING_EXP_CLAIM", "Firebase ID token is missing 'exp' claim")
        try:
            exp_int = int(exp)
        except (TypeError, ValueError) as exc:
            raise ApiHttpError(401, "MALFORMED_TOKEN_CLAIMS", "Invalid 'exp' claim type") from exc
        if exp_int <= now_epoch:
            raise ApiHttpError(401, "TOKEN_EXPIRED", "Firebase ID token has expired")

        # Validate issued-at (iat)
        iat = decoded_claims.get("iat")
        if iat is None:
            raise ApiHttpError(401, "MISSING_IAT_CLAIM", "Firebase ID token is missing 'iat' claim")
        try:
            iat_int = int(iat)
        except (TypeError, ValueError) as exc:
            raise ApiHttpError(401, "MALFORMED_TOKEN_CLAIMS", "Invalid 'iat' claim type") from exc
        if iat_int > now_epoch + 60:
            raise ApiHttpError(401, "INVALID_ISSUED_AT", "Firebase ID token 'iat' is in the future")

        # Validate Audience (aud) & Firebase Project Identity
        aud = str(decoded_claims.get("aud") or "").strip()
        if aud != self.project_id:
            raise ApiHttpError(
                401,
                "INVALID_AUDIENCE",
                f"Token audience {aud!r} does not match Firebase project {self.project_id!r}",
            )

        # Validate Issuer (iss)
        iss = str(decoded_claims.get("iss") or "").strip()
        if iss != self.expected_issuer:
            raise ApiHttpError(
                401,
                "INVALID_ISSUER",
                f"Token issuer {iss!r} does not match expected issuer {self.expected_issuer!r}",
            )

        # Validate Subject / UID (sub / uid)
        sub = str(decoded_claims.get("sub") or decoded_claims.get("uid") or "").strip()
        if not sub or len(sub) > 128:
            raise ApiHttpError(401, "MISSING_UID_CLAIM", "Token 'sub' claim must be a non-empty UID (<=128 chars)")
        user_id_claim = str(decoded_claims.get("user_id") or sub).strip()
        if user_id_claim != sub:
            raise ApiHttpError(401, "MISMATCHED_UID_CLAIM", "Token 'user_id' claim does not match 'sub'")

        # Check revocation when revocation checking is enabled
        if self.check_revoked:
            token_digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
            jti = str(decoded_claims.get("jti") or "").strip()
            auth_time_int = int(decoded_claims.get("auth_time", iat_int))
            with self._lock:
                is_jti_revoked = (jti and jti in self._revoked_token_ids) or (token_digest in self._revoked_token_ids)
                revoked_before = self._revoked_before_epoch_by_uid.get(sub)
            if is_jti_revoked or (revoked_before is not None and min(iat_int, auth_time_int) <= revoked_before):
                raise ApiHttpError(401, "TOKEN_REVOKED", f"Firebase ID token for UID '{redact_uid_for_audit(sub)}' has been revoked")

        email = str(decoded_claims.get("email") or "").strip().lower()
        email_verified = bool(decoded_claims.get("email_verified", False))
        verified_custom_claims = decoded_claims.get("firebase_custom_claims")

        role = self._resolve_authoritative_role(
            uid=sub,
            email=email,
            email_verified=email_verified,
            verified_custom_claims=verified_custom_claims if isinstance(verified_custom_claims, dict) else None,
        )

        return {
            "uid": sub,
            "email": email,
            "email_verified": email_verified,
            "role": role,
            "project_id": self.project_id,
            "iss": iss,
            "aud": aud,
        }


def verify_firebase_bearer_token(
    authorization_header: Optional[str],
    authenticator: Optional[FirebaseAdminAuthenticator] = None,
) -> Dict[str, Any]:
    """Verify a Firebase ID token from the Authorization Bearer header using FirebaseAdminAuthenticator."""
    auth_engine = authenticator or FirebaseAdminAuthenticator(
        project_id=AUTHORITATIVE_FIREBASE_PROJECT_ID,
    )
    return auth_engine.verify_bearer_header(authorization_header)


def validate_farm_polygon_geojson(polygon_geojson: Any) -> Dict[str, Any]:
    """Validate that a GeoJSON object is a well-formed, closed Polygon with valid coordinates."""
    if not isinstance(polygon_geojson, dict):
        raise ApiHttpError(422, "INVALID_GEOJSON", "farm_polygon_geojson must be a JSON object")

    geom = (
        polygon_geojson.get("geometry")
        if polygon_geojson.get("type") == "Feature"
        else polygon_geojson
    )
    if not isinstance(geom, dict) or geom.get("type") != "Polygon":
        raise ApiHttpError(422, "INVALID_GEOMETRY_TYPE", "GeoJSON geometry must be of type 'Polygon'")

    coords = geom.get("coordinates")
    if not isinstance(coords, list) or not coords or not isinstance(coords[0], list):
        raise ApiHttpError(422, "EMPTY_POLYGON_COORDINATES", "Polygon coordinates ring is missing")

    outer_ring = coords[0]
    if len(outer_ring) < 4:
        raise ApiHttpError(422, "TOO_FEW_VERTICES", "A valid closed GeoJSON Polygon ring requires at least 4 vertices")

    for pt in outer_ring:
        if not isinstance(pt, (list, tuple)) or len(pt) < 2:
            raise ApiHttpError(422, "INVALID_VERTEX", "Each polygon vertex must be [longitude, latitude]")
        lon, lat = float(pt[0]), float(pt[1])
        if not (-180.0 <= lon <= 180.0 and -90.0 <= lat <= 90.0):
            raise ApiHttpError(422, "COORDINATE_OUT_OF_RANGE", f"Coordinate [{lon}, {lat}] is out of WGS84 bounds")

    if [float(outer_ring[0][0]), float(outer_ring[0][1])] != [float(outer_ring[-1][0]), float(outer_ring[-1][1])]:
        raise ApiHttpError(422, "UNCLOSED_POLYGON_RING", "Polygon outer ring first and last coordinates must match")

    return geom


# ==============================================================================
# Persistent Operational Backend Service with Bounded Worker Pool, RBAC & Signed Files
# ==============================================================================

class SatelliteBackendService:
    """
    Production operational backend service managing:
    - Official Firebase Admin SDK authentication & Role-Based Access Control (RBAC).
    - Persistent relational database (`AgroSightPersistentDatabase`) storing users, farms,
      farm ownership, 9-stage job status transitions, processing results, output file metadata,
      and redacted audit logs across server restarts.
    - Automatic recovery/marking of unfinished jobs on backend startup.
    - Private object storage for generated GeoTIFF rasters (`private_objects/<owner_uid>/<result_id>/<index>.tif`),
      authenticated download endpoint (`/api/v1/satellite/files/{file_id}`), and short-lived
      signed download URLs.
    - Cascading deletion of farm data and private raster files (`DELETE /api/v1/satellite/farms/{farm_id}`).
    """

    def __init__(
        self,
        storage_root: Optional[str] = None,
        database: Optional[AgroSightPersistentDatabase] = None,
        database_url: Optional[str] = None,
        authenticator: Optional[FirebaseAdminAuthenticator] = None,
        stac_search_fn: Callable[..., Dict[str, Any]] = search_stac_sentinel2_scenes,
        max_workers: int = 4,
        signed_url_secret: Optional[str] = None,
    ):
        self.storage_root = storage_root or tempfile.mkdtemp(prefix="agrosight_backend_storage_")
        os.makedirs(self.storage_root, exist_ok=True)
        self._private_objects_root = os.path.join(self.storage_root, "private_objects")
        os.makedirs(self._private_objects_root, exist_ok=True)

        self.db = database or AgroSightPersistentDatabase(
            storage_root=self.storage_root,
            database_url=database_url,
        )
        self.authenticator = authenticator or FirebaseAdminAuthenticator(
            project_id=AUTHORITATIVE_FIREBASE_PROJECT_ID,
        )
        self.stac_search_fn = stac_search_fn
        self._signed_url_secret = (
            signed_url_secret
            or os.environ.get("AGROSIGHT_SIGNED_URL_SECRET", "").strip()
            or secrets.token_hex(32)
        ).encode("utf-8")

        self._lock = threading.Lock()
        self._executor = concurrent.futures.ThreadPoolExecutor(
            max_workers=max(1, int(max_workers)),
            thread_name_prefix="agrosight_raster_worker",
        )

        # Recover unfinished jobs left over from a previous server instance
        self.recovered_interrupted_jobs: List[str] = self.db.recover_unfinished_jobs_on_startup()

    def register_farm_owner(
        self,
        farm_id: str,
        owner_uid: str,
        farm_name: str = "",
        crop_id: str = "tomato",
        growth_stage_index: int = 2,
        farm_polygon_geojson: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        return self.db.register_or_get_farm(
            farm_id=farm_id,
            owner_uid=owner_uid,
            farm_name=farm_name,
            crop_id=crop_id,
            growth_stage_index=growth_stage_index,
            farm_polygon_geojson=farm_polygon_geojson,
        )

    def _verify_auth(self, auth_header: Optional[str]) -> Dict[str, Any]:
        claims = self.authenticator.verify_bearer_header(auth_header)
        self.db.upsert_user(
            uid=claims["uid"],
            email=claims.get("email", ""),
            email_verified=bool(claims.get("email_verified", False)),
            role=claims.get("role", "FARMER"),
        )
        return claims

    def _authorize_farm_access(
        self,
        auth_claims: Dict[str, Any],
        farm_id: str,
        requested_owner_uid: Optional[str] = None,
        farm_polygon_geojson: Optional[Dict[str, Any]] = None,
        crop_id: str = "tomato",
        growth_stage_index: int = 2,
        auto_register_if_missing: bool = True,
    ) -> str:
        """
        Enforce strict farm ownership & prevent UID impersonation against persistent database:
        - A FARMER cannot specify an `owner_uid` different from their verified `auth_claims['uid']`.
        - A FARMER cannot access a `farm_id` registered to another `owner_uid`.
        - AGRONOMIST_ADMIN and SUPER_ADMIN may inspect registered farms.
        """
        caller_uid = auth_claims["uid"]
        caller_role = auth_claims["role"]
        clean_requested = str(requested_owner_uid or "").strip()

        if clean_requested and clean_requested != caller_uid and caller_role not in ("SUPER_ADMIN", "AGRONOMIST_ADMIN"):
            self.db.record_audit_event(
                actor_uid=caller_uid,
                actor_role=caller_role,
                action="UID_IMPERSONATION_BLOCKED",
                target_id=farm_id,
                details={"requested_owner_redacted": redact_uid_for_audit(clean_requested)},
            )
            raise ApiHttpError(
                403,
                "UID_IMPERSONATION_FORBIDDEN",
                f"User '{redact_uid_for_audit(caller_uid)}' (role={caller_role}) cannot impersonate another owner_uid",
            )

        existing_farm = self.db.get_farm(farm_id)
        if existing_farm is None:
            if not auto_register_if_missing:
                raise ApiHttpError(404, "FARM_NOT_FOUND", f"Farm '{farm_id}' does not exist")
            target_owner = clean_requested or caller_uid
            if caller_uid != target_owner and caller_role != "SUPER_ADMIN":
                raise ApiHttpError(
                    403,
                    "FORBIDDEN_FARM_ACCESS",
                    "Not authorized to register farm for another user",
                )
            existing_farm = self.db.register_or_get_farm(
                farm_id=farm_id,
                owner_uid=target_owner,
                crop_id=crop_id,
                growth_stage_index=growth_stage_index,
                farm_polygon_geojson=farm_polygon_geojson,
            )

        effective_owner = str(existing_farm["owner_uid"])
        if caller_uid != effective_owner and caller_role not in ("SUPER_ADMIN", "AGRONOMIST_ADMIN"):
            self.db.record_audit_event(
                actor_uid=caller_uid,
                actor_role=caller_role,
                action="UNAUTHORIZED_FARM_ACCESS_BLOCKED",
                target_id=farm_id,
            )
            raise ApiHttpError(
                403,
                "FORBIDDEN_FARM_ACCESS",
                f"User is not authorized to access farm '{farm_id}'",
            )
        return effective_owner

    def _require_admin_role(self, auth_claims: Dict[str, Any], require_super_admin: bool = False) -> None:
        role = auth_claims.get("role", "FARMER")
        if require_super_admin:
            if role != "SUPER_ADMIN":
                raise ApiHttpError(
                    403,
                    "FORBIDDEN_SUPER_ADMIN_ONLY",
                    f"Role '{role}' is not authorized for Super Administrator operations",
                )
        else:
            if role not in ("AGRONOMIST_ADMIN", "SUPER_ADMIN"):
                raise ApiHttpError(
                    403,
                    "FORBIDDEN_ADMIN_ENDPOINT",
                    f"Role '{role}' is not authorized to access administrator endpoints",
                )

    def get_health(self) -> Tuple[int, Dict[str, Any]]:
        db_stats = self.db.get_admin_summary()
        return 200, {
            "status": "HEALTHY",
            "service": "AgroSight-Sentinel2-Raster-Backend",
            "version": "4.0.0",
            "firebase_project_id": self.authenticator.project_id,
            "firebase_auth_configured": self.authenticator.is_configured,
            "firebase_admin_sdk_installed": HAS_FIREBASE_ADMIN_SDK,
            "firebase_admin_sdk_initialized": self.authenticator.firebase_admin_initialized,
            "database_engine": db_stats["database_engine"],
            "job_lifecycle_statuses": list(JOB_LIFECYCLE_STATUSES),
            "capabilities": {
                "geotiff_cog_reader_writer": True,
                "remote_cog_http_range_windowing": True,
                "official_firebase_admin_sdk_verification": True,
                "persistent_relational_database": True,
                "private_signed_raster_storage": True,
                "rasterio_installed": HAS_RASTERIO,
                "numpy_installed": HAS_NUMPY,
                "shapely_installed": HAS_SHAPELY,
                "supported_bands": ["B02", "B04", "B05", "B06", "B08", "B8A", "B11", "SCL"],
                "supported_indices": ["NDVI", "NDRE", "NDMI", "PSRI"],
                "crs_support": ["EPSG:4326", "EPSG:32601-32660", "EPSG:32701-32760"],
            },
        }

    def get_farm(
        self,
        auth_header: Optional[str],
        farm_id: str,
    ) -> Tuple[int, Dict[str, Any]]:
        claims = self._verify_auth(auth_header)
        self._authorize_farm_access(claims, farm_id=farm_id, auto_register_if_missing=False)
        farm = self.db.get_farm(farm_id)
        if not farm:
            raise ApiHttpError(404, "FARM_NOT_FOUND", f"Farm '{farm_id}' does not exist")
        return 200, farm

    def delete_farm(
        self,
        auth_header: Optional[str],
        farm_id: str,
    ) -> Tuple[int, Dict[str, Any]]:
        """
        Cascading deletion of farm data:
        - Verifies that the caller is the farm owner or `SUPER_ADMIN`.
        - Deletes all private GeoTIFF files on disk, file metadata records, processing results,
          job status history, jobs, and the farm record itself.
        """
        claims = self._verify_auth(auth_header)
        farm = self.db.get_farm(farm_id)
        if not farm:
            raise ApiHttpError(404, "FARM_NOT_FOUND", f"Farm '{farm_id}' does not exist")

        if claims["uid"] != farm["owner_uid"] and claims["role"] != "SUPER_ADMIN":
            raise ApiHttpError(
                403,
                "FORBIDDEN_FARM_ACCESS",
                f"User is not authorized to delete farm '{farm_id}'",
            )

        summary = self.db.delete_farm_and_all_outputs(farm_id=farm_id, actor_uid=claims["uid"])
        return 200, {
            "status": "FARM_DELETED",
            **summary,
        }

    def post_search_scenes(
        self,
        auth_header: Optional[str],
        body: Dict[str, Any],
    ) -> Tuple[int, Dict[str, Any]]:
        claims = self._verify_auth(auth_header)
        farm_id = str(body.get("farm_id") or "").strip()
        if not farm_id:
            raise ApiHttpError(422, "MISSING_FARM_ID", "farm_id is required")

        geom = validate_farm_polygon_geojson(body.get("farm_polygon_geojson"))
        requested_owner = body.get("owner_uid")
        effective_owner = self._authorize_farm_access(
            claims,
            farm_id=farm_id,
            requested_owner_uid=requested_owner,
            farm_polygon_geojson=geom,
        )

        max_cloud = float(body.get("max_cloud_cover", 35.0))
        if not (0.0 <= max_cloud <= 100.0):
            raise ApiHttpError(422, "INVALID_CLOUD_COVER", "max_cloud_cover must be between 0 and 100")

        limit = int(body.get("limit", 5))
        date_start_iso = body.get("date_start_iso")
        date_end_iso = body.get("date_end_iso")

        try:
            stac_res = self.stac_search_fn(
                farm_polygon_geojson=geom,
                max_cloud_cover=max_cloud,
                limit=limit,
                date_start_iso=date_start_iso,
                date_end_iso=date_end_iso,
            )
        except TypeError:
            # Backward compatibility for custom 3-arg test lambdas
            stac_res = self.stac_search_fn(
                farm_polygon_geojson=geom,
                max_cloud_cover=max_cloud,
                limit=limit,
            )

        return 200, {
            "farm_id": farm_id,
            "owner_uid": effective_owner,
            "stac_status": "VALID_SCENE_WITH_BAND_ASSETS" if stac_res["scene_count"] > 0 else "NO_MATCHING_SCENES",
            "scene_count": stac_res["scene_count"],
            "scenes": stac_res["scenes"],
        }

    def post_process_raster(
        self,
        auth_header: Optional[str],
        body: Dict[str, Any],
        run_async: bool = False,
    ) -> Tuple[int, Dict[str, Any]]:
        claims = self._verify_auth(auth_header)
        farm_id = str(body.get("farm_id") or "").strip()
        if not farm_id:
            raise ApiHttpError(422, "MISSING_FARM_ID", "farm_id is required")

        geom = validate_farm_polygon_geojson(body.get("farm_polygon_geojson"))
        crop_id = str(body.get("crop_id") or "tomato").strip()
        growth_stage_index = int(body.get("growth_stage_index", 2))
        scene_id = str(body.get("scene_id") or "S2B_L2A_SCENE").strip()
        scale_factor = float(body.get("scale_factor", 0.0001))
        boa_add_offset = float(body.get("boa_add_offset", 0.0))

        requested_owner = body.get("owner_uid")
        effective_owner = self._authorize_farm_access(
            claims,
            farm_id=farm_id,
            requested_owner_uid=requested_owner,
            farm_polygon_geojson=geom,
            crop_id=crop_id,
            growth_stage_index=growth_stage_index,
        )

        band_sources = body.get("band_assets")
        if not isinstance(band_sources, dict):
            raise ApiHttpError(
                422,
                "MISSING_BAND_ASSETS",
                "band_assets dictionary with B02, B04, B05, B06, B08, B8A, B11, and SCL is required",
            )
        required_bands = ("B02", "B04", "B05", "B06", "B08", "B8A", "B11", "SCL")
        missing = [b for b in required_bands if not band_sources.get(b)]
        if missing:
            raise ApiHttpError(
                422,
                "INCOMPLETE_BAND_ASSETS",
                f"Missing required Sentinel-2 spectral band assets: {', '.join(missing)}",
            )

        job_id = f"job_{uuid.uuid4().hex[:12]}"
        result_id = f"res_{uuid.uuid4().hex[:12]}"

        self.db.create_job(
            job_id=job_id,
            result_id=result_id,
            farm_id=farm_id,
            owner_uid=effective_owner,
            submitted_by_uid=claims["uid"],
            scene_id=scene_id,
            crop_id=crop_id,
            growth_stage_index=growth_stage_index,
            request_payload={
                "farm_id": farm_id,
                "scene_id": scene_id,
                "crop_id": crop_id,
                "growth_stage_index": growth_stage_index,
                "scale_factor": scale_factor,
                "boa_add_offset": boa_add_offset,
            },
        )

        def _execute_pipeline() -> None:
            try:
                self.db.update_job_status(job_id, status="DISCOVERING_SCENES", stage="DISCOVERING_SCENES", progress_pct=15)
                self.db.update_job_status(job_id, status="DOWNLOADING_ASSETS", stage="DOWNLOADING_ASSETS", progress_pct=30)

                private_out_dir = os.path.join(self._private_objects_root, effective_owner, result_id)
                os.makedirs(private_out_dir, exist_ok=True)

                self.db.update_job_status(job_id, status="PROCESSING_RASTERS", stage="PROCESSING_RASTERS", progress_pct=55)
                stats, evaluations = process_sentinel2_raster_bands(
                    band_sources=band_sources,
                    farm_polygon_geojson=geom,
                    crop_id=crop_id,
                    growth_stage_index=growth_stage_index,
                    scale_factor=scale_factor,
                    boa_add_offset=boa_add_offset,
                    output_dir=private_out_dir,
                )

                self.db.update_job_status(job_id, status="CALCULATING_INDICES", stage="CALCULATING_INDICES", progress_pct=75)
                self.db.update_job_status(job_id, status="GENERATING_OUTPUTS", stage="GENERATING_OUTPUTS", progress_pct=90)

                # Register private output files in database and expose only authenticated download endpoints
                file_records: Dict[str, Dict[str, Any]] = {}
                authenticated_download_urls: Dict[str, str] = {}
                for idx_name, private_disk_path in stats.output_raster_files.items():
                    file_id = f"file_{result_id}_{idx_name.lower()}"
                    size_bytes = os.path.getsize(private_disk_path) if os.path.exists(private_disk_path) else 0
                    sha256_hex = ""
                    if os.path.exists(private_disk_path):
                        with open(private_disk_path, "rb") as rf:
                            sha256_hex = hashlib.sha256(rf.read()).hexdigest()
                    f_meta = self.db.register_output_file(
                        file_id=file_id,
                        result_id=result_id,
                        job_id=job_id,
                        farm_id=farm_id,
                        owner_uid=effective_owner,
                        index_name=idx_name,
                        private_file_path=private_disk_path,
                        file_size_bytes=size_bytes,
                        sha256_hex=sha256_hex,
                        crs_epsg=stats.crs_epsg,
                    )
                    file_records[idx_name] = f_meta
                    authenticated_download_urls[idx_name] = f_meta["download_endpoint"]

                valid_evals = [e for e in evaluations if e.is_inside_polygon]
                pixel_cells = [
                    {
                        "row": e.row,
                        "col": e.col,
                        "lon": round(e.lon, 6),
                        "lat": round(e.lat, 6),
                        "is_valid": e.is_valid,
                        "is_cloud_masked": e.is_cloud_or_shadow_masked,
                        "scl_class": e.scl_class,
                        "ndvi": None if not e.is_valid else round(e.ndvi, 4),
                        "ndre": None if not e.is_valid else round(e.ndre, 4),
                        "ndmi": None if not e.is_valid else round(e.ndmi, 4),
                        "psri": None if not e.is_valid else round(e.psri, 4),
                        "is_anomalous": e.is_anomalous,
                        "anomaly_type": e.anomaly_type,
                    }
                    for e in valid_evals
                ]

                crop_thresholds = get_crop_thresholds(crop_id, growth_stage_index=growth_stage_index)
                has_stress = stats.anomaly_pixel_count > 0
                stress_summary_ar = (
                    f"مؤشر إجهاد نباتي وشذوذ طيفي (Vegetation stress indicator / Spectral anomaly) في {stats.anomaly_pixel_count} بكسل "
                    f"({round(stats.anomaly_area_feddans, 3)} فدان) داخل حدود مضلع المزرعة "
                    f"(متوسط NDVI={round(stats.ndvi_mean, 3)}، NDRE={round(stats.ndre_mean, 3)})."
                    if has_stress
                    else f"الغطاء النباتي متجانس داخل مضلع المزرعة (NDVI={round(stats.ndvi_mean, 3)}، "
                    f"بكسلات صالحة {round(stats.valid_pixel_ratio * 100, 1)}%)."
                )
                risk_summary_ar = (
                    "احتمال مشكلة صحية للمحصول أو مؤشر إجهاد رطوبي (Possible crop health concern / Moisture stress indicator): "
                    "انخفاض الكلوروفيل الحافي (NDRE) أو تغير رطوبة المجموع الخضري (NDMI) يتطلب فحصاً ميدانياً لمضلع الشذوذ (GeoJSON)."
                    if has_stress
                    else "مؤشر المخاطر الزراعية منخفض بناءً على القراءات الطيفية الحالية."
                )
                confirmed_diagnosis_ar = (
                    "غير مؤكد — المؤشرات الطيفية للأقمار الصناعية ترصد شذوذ الانعكاس الضوئي للغطاء النباتي "
                    "(Spectral anomaly / Vegetation stress indicator) ولا تُعد تشخيصاً مرضياً مؤكداً إلا بعد المعاينة الحقلية."
                )

                completed_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                result_payload = {
                    "result_id": result_id,
                    "job_id": job_id,
                    "farm_id": farm_id,
                    "owner_uid": effective_owner,
                    "scene_id": scene_id,
                    "crop_id": crop_id,
                    "growth_stage_index": growth_stage_index,
                    "data_mode": "LIVE_GEOREFERENCED_RASTER",
                    "crs_epsg": stats.crs_epsg,
                    "spatial_resolution_m": stats.spatial_resolution_m,
                    "statistics": asdict(stats),
                    "anomaly_zones_geojson": json.loads(stats.anomaly_zones_geojson),
                    "output_files": file_records,
                    "output_download_urls": authenticated_download_urls,
                    "output_geotiff_paths": stats.output_raster_files,
                    "pixel_evaluations": pixel_cells,
                    "threshold_assumptions": {
                        "crop_id": crop_thresholds.crop_id,
                        "growth_stage_index": growth_stage_index,
                        "healthy_ndvi_min": crop_thresholds.healthy_ndvi_min,
                        "stressed_ndvi_threshold": crop_thresholds.stressed_ndvi_threshold,
                        "healthy_ndre_min": crop_thresholds.healthy_ndre_min,
                        "stressed_ndre_threshold": crop_thresholds.stressed_ndre_threshold,
                        "water_stress_ndmi_threshold": crop_thresholds.water_stress_ndmi_threshold,
                        "senescence_psri_threshold": crop_thresholds.senescence_psri_threshold,
                        "note": "Thresholds are empirical phenological reference bounds and must be calibrated with local agronomic scouting.",
                    },
                    "scientific_assessment": {
                        "terminology_classification": (
                            "Vegetation stress indicator / Spectral anomaly / Moisture stress indicator / Possible crop health concern"
                            if has_stress
                            else "Normal canopy reflectance"
                        ),
                        "is_confirmed_plant_disease": False,
                        "vegetation_stress_detection_ar": stress_summary_ar,
                        "agricultural_risk_assessment_ar": risk_summary_ar,
                        "confirmed_disease_diagnosis_status_ar": confirmed_diagnosis_ar,
                    },
                    "completed_at_iso": completed_iso,
                }

                self.db.save_processing_result(
                    result_id=result_id,
                    job_id=job_id,
                    farm_id=farm_id,
                    owner_uid=effective_owner,
                    scene_id=scene_id,
                    crop_id=crop_id,
                    crs_epsg=stats.crs_epsg,
                    spatial_resolution_m=stats.spatial_resolution_m,
                    statistics=asdict(stats),
                    anomaly_zones_geojson=json.loads(stats.anomaly_zones_geojson),
                    scientific_assessment=result_payload["scientific_assessment"],
                    full_result_payload=result_payload,
                )
                self.db.update_job_status(job_id, status="COMPLETED", stage="COMPLETED", progress_pct=100)
            except RasterAccessError as r_err:
                self.db.update_job_status(
                    job_id,
                    status="FAILED",
                    stage="FAILED",
                    progress_pct=100,
                    error_code=r_err.error_code,
                    error_message=str(r_err),
                )
            except Exception as exc:
                self.db.update_job_status(
                    job_id,
                    status="FAILED",
                    stage="FAILED",
                    progress_pct=100,
                    error_code="RASTER_PROCESSING_FAILED",
                    error_message=str(exc),
                )

        if run_async:
            self._executor.submit(_execute_pipeline)
            job_snap = self.db.get_job(job_id)
            return 202, dict(job_snap or {})
        else:
            _execute_pipeline()
            job_snap = self.db.get_job(job_id)
            if not job_snap:
                raise ApiHttpError(500, "JOB_PERSISTENCE_ERROR", "Job record was not persisted")
            if job_snap["status"] == "FAILED":
                err_code = str(job_snap.get("error_code") or "RASTER_PROCESSING_FAILED")
                status_map = {
                    "INCOMPLETE_BAND_ASSETS": 422,
                    "UNSUPPORTED_RASTER_CRS": 422,
                    "INVALID_ASSET_URL": 422,
                    "CORRUPTED_RASTER_TIFF": 422,
                    "HTTP_RANGE_NOT_SUPPORTED": 502,
                    "DOWNLOAD_INTERRUPTED": 502,
                }
                http_code = status_map.get(err_code, 500)
                raise ApiHttpError(http_code, err_code, str(job_snap["error_message"]))
            return 200, job_snap

    def get_job_status(
        self,
        auth_header: Optional[str],
        job_id: str,
    ) -> Tuple[int, Dict[str, Any]]:
        claims = self._verify_auth(auth_header)
        job = self.db.get_job(job_id)
        if not job:
            raise ApiHttpError(404, "JOB_NOT_FOUND", f"Processing job '{job_id}' does not exist")

        self._authorize_farm_access(
            claims,
            farm_id=job["farm_id"],
            requested_owner_uid=job["owner_uid"],
            auto_register_if_missing=False,
        )
        return 200, job

    def get_result(
        self,
        auth_header: Optional[str],
        result_id: str,
    ) -> Tuple[int, Dict[str, Any]]:
        claims = self._verify_auth(auth_header)
        res = self.db.get_result(result_id)
        if not res:
            raise ApiHttpError(404, "RESULT_NOT_FOUND", f"Processing result '{result_id}' does not exist")

        self._authorize_farm_access(
            claims,
            farm_id=res["farm_id"],
            requested_owner_uid=res["owner_uid"],
            auto_register_if_missing=False,
        )
        return 200, res

    # --------------------------------------------------------------------------
    # Phase 6: Private File Storage, Signed URLs & Authenticated File Download
    # --------------------------------------------------------------------------

    def _compute_file_signature(self, file_id: str, owner_uid: str, expires_epoch: int) -> str:
        msg = f"{file_id}:{owner_uid}:{expires_epoch}".encode("utf-8")
        return hmac.new(self._signed_url_secret, msg, hashlib.sha256).hexdigest()

    def post_create_signed_file_url(
        self,
        auth_header: Optional[str],
        file_id: str,
        ttl_seconds: int = 300,
    ) -> Tuple[int, Dict[str, Any]]:
        """
        Generate a short-lived HMAC-SHA256 signed download URL for an authorized private raster file.
        """
        claims = self._verify_auth(auth_header)
        f_rec = self.db.get_output_file_record(file_id)
        if not f_rec:
            raise ApiHttpError(404, "FILE_NOT_FOUND", f"Output raster file '{file_id}' does not exist")

        self._authorize_farm_access(
            claims,
            farm_id=f_rec["farm_id"],
            requested_owner_uid=f_rec["owner_uid"],
            auto_register_if_missing=False,
        )
        clamped_ttl = max(10, min(3600, int(ttl_seconds)))
        expires_epoch = int(time.time()) + clamped_ttl
        sig = self._compute_file_signature(file_id=file_id, owner_uid=f_rec["owner_uid"], expires_epoch=expires_epoch)
        signed_path = f"/api/v1/satellite/files/{file_id}?expires={expires_epoch}&signature={sig}"
        return 200, {
            "file_id": file_id,
            "index_name": f_rec["index_name"],
            "expires_epoch": expires_epoch,
            "ttl_seconds": clamped_ttl,
            "signed_download_url": signed_path,
        }

    def get_private_output_file(
        self,
        auth_header: Optional[str],
        file_id: str,
        query_params: Optional[Dict[str, str]] = None,
    ) -> Tuple[int, bytes, str, str]:
        """
        Serve a private GeoTIFF file ONLY after verifying either:
        1. Valid Firebase Bearer ID token with ownership of the farm/file, OR
        2. Unexpired short-lived HMAC-SHA256 signed URL (`?expires=...&signature=...`).
        """
        f_rec = self.db.get_output_file_record(file_id)
        if not f_rec:
            raise ApiHttpError(404, "FILE_NOT_FOUND", f"Output raster file '{file_id}' does not exist")

        qp = query_params or {}
        sig_param = str(qp.get("signature") or "").strip()
        exp_param = str(qp.get("expires") or "").strip()

        if sig_param and exp_param:
            try:
                exp_int = int(exp_param)
            except ValueError as exc:
                raise ApiHttpError(401, "INVALID_SIGNED_URL", "Invalid 'expires' timestamp in signed URL") from exc
            if exp_int < int(time.time()):
                raise ApiHttpError(401, "SIGNED_URL_EXPIRED", "Signed download URL has expired")
            expected_sig = self._compute_file_signature(
                file_id=file_id,
                owner_uid=f_rec["owner_uid"],
                expires_epoch=exp_int,
            )
            if not hmac.compare_digest(sig_param, expected_sig):
                raise ApiHttpError(403, "INVALID_SIGNED_URL_SIGNATURE", "Signed URL cryptographic signature mismatch")
        else:
            claims = self._verify_auth(auth_header)
            self._authorize_farm_access(
                claims,
                farm_id=f_rec["farm_id"],
                requested_owner_uid=f_rec["owner_uid"],
                auto_register_if_missing=False,
            )

        disk_path = f_rec["private_file_path"]
        if not os.path.exists(disk_path):
            raise ApiHttpError(404, "FILE_NOT_FOUND_ON_STORAGE", f"Raster file '{file_id}' is missing from storage")

        with open(disk_path, "rb") as rf:
            raw_bytes = rf.read()

        filename = f"{f_rec['result_id']}_{f_rec['index_name']}.tif"
        return 200, raw_bytes, f_rec.get("content_type", "image/tiff"), filename

    # --------------------------------------------------------------------------
    # Administrative & Super-Admin Governance Endpoints (Enforcing RBAC)
    # --------------------------------------------------------------------------

    def get_admin_overview(self, auth_header: Optional[str]) -> Tuple[int, Dict[str, Any]]:
        """Accessible to AGRONOMIST_ADMIN and SUPER_ADMIN; forbidden (403) for FARMER."""
        claims = self._verify_auth(auth_header)
        self._require_admin_role(claims, require_super_admin=False)
        summary = self.db.get_admin_summary()
        return 200, {
            "admin_uid": claims["uid"],
            "admin_role": claims["role"],
            **summary,
        }

    def post_admin_assign_role(
        self,
        auth_header: Optional[str],
        body: Dict[str, Any],
    ) -> Tuple[int, Dict[str, Any]]:
        """Accessible ONLY to SUPER_ADMIN; forbidden (403) for AGRONOMIST_ADMIN and FARMER."""
        claims = self._verify_auth(auth_header)
        self._require_admin_role(claims, require_super_admin=True)

        target_uid = str(body.get("target_uid") or "").strip()
        new_role = str(body.get("role") or "").strip().upper()
        if not target_uid or new_role not in SUPPORTED_ROLES:
            raise ApiHttpError(422, "INVALID_ROLE_ASSIGNMENT", "target_uid and valid role are required")

        self.authenticator.set_verified_custom_claims(target_uid, {"role": new_role})
        self.db.upsert_user(uid=target_uid, role=new_role)
        self.db.record_audit_event(
            actor_uid=claims["uid"],
            actor_role=claims["role"],
            action="ASSIGN_USER_ROLE",
            target_id=redact_uid_for_audit(target_uid),
            details={"assigned_role": new_role},
        )
        return 200, {
            "updated_by_uid": claims["uid"],
            "target_uid": target_uid,
            "assigned_role": new_role,
            "status": "CUSTOM_CLAIMS_UPDATED",
        }

    def delete_admin_job(
        self,
        auth_header: Optional[str],
        job_id: str,
    ) -> Tuple[int, Dict[str, Any]]:
        """Accessible ONLY to SUPER_ADMIN; forbidden (403) for AGRONOMIST_ADMIN and FARMER."""
        claims = self._verify_auth(auth_header)
        self._require_admin_role(claims, require_super_admin=True)
        deleted = self.db.delete_job_and_outputs(job_id=job_id, actor_uid=claims["uid"])
        if not deleted:
            raise ApiHttpError(404, "JOB_NOT_FOUND", f"Job '{job_id}' not found")
        return 200, {"deleted_job_id": job_id, "deleted_by_uid": claims["uid"]}


# ==============================================================================
# HTTP Server & FastAPI Router Binding
# ==============================================================================

def create_http_server(
    service: Optional[SatelliteBackendService] = None,
    host: str = "127.0.0.1",
    port: int = 0,
) -> Tuple[ThreadingHTTPServer, SatelliteBackendService]:
    """
    Create a thread-safe HTTP server exposing all satellite, private storage, farm deletion, and admin endpoints.
    """
    svc = service or SatelliteBackendService()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args: Any) -> None:
            pass  # Suppress noisy stdout logging during automated tests

        def _send_json(self, status_code: int, payload: Dict[str, Any]) -> None:
            raw = json.dumps(payload).encode("utf-8")
            self.send_response(status_code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _send_binary(self, status_code: int, data: bytes, content_type: str, filename: str) -> None:
            self.send_response(status_code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "private, no-store")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/")
            qs = {k: v[0] for k, v in parse_qs(parsed.query).items() if v}
            auth_hdr = self.headers.get("Authorization")
            try:
                if path == "/api/v1/health":
                    code, data = svc.get_health()
                    self._send_json(code, data)
                elif path == "/api/v1/admin/overview":
                    code, data = svc.get_admin_overview(auth_hdr)
                    self._send_json(code, data)
                elif path.startswith("/api/v1/satellite/jobs/"):
                    job_id = path.split("/")[-1]
                    code, data = svc.get_job_status(auth_hdr, job_id)
                    self._send_json(code, data)
                elif path.startswith("/api/v1/satellite/results/"):
                    result_id = path.split("/")[-1]
                    code, data = svc.get_result(auth_hdr, result_id)
                    self._send_json(code, data)
                elif path.startswith("/api/v1/satellite/files/"):
                    file_id = path.split("/")[-1]
                    code, raw_bytes, ctype, fname = svc.get_private_output_file(auth_hdr, file_id, query_params=qs)
                    self._send_binary(code, raw_bytes, ctype, fname)
                elif path.startswith("/api/v1/satellite/farms/"):
                    farm_id = path.split("/")[-1]
                    code, data = svc.get_farm(auth_hdr, farm_id)
                    self._send_json(code, data)
                else:
                    self._send_json(404, {"error_code": "NOT_FOUND", "message": f"Unknown endpoint {path}"})
            except ApiHttpError as err:
                self._send_json(err.status_code, {"error_code": err.error_code, "message": err.message})
            except Exception as exc:
                self._send_json(500, {"error_code": "INTERNAL_ERROR", "message": str(exc)})

        def do_POST(self) -> None:
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/")
            auth_hdr = self.headers.get("Authorization")
            content_len = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(content_len).decode("utf-8") if content_len > 0 else "{}"
            try:
                body = json.loads(raw_body) if raw_body.strip() else {}
                if path == "/api/v1/satellite/scenes":
                    code, data = svc.post_search_scenes(auth_hdr, body)
                    self._send_json(code, data)
                elif path == "/api/v1/satellite/process":
                    code, data = svc.post_process_raster(auth_hdr, body, run_async=bool(body.get("async", False)))
                    self._send_json(code, data)
                elif path.startswith("/api/v1/satellite/files/") and path.endswith("/signed-url"):
                    parts = path.split("/")
                    file_id = parts[-2]
                    ttl = int(body.get("ttl_seconds", 300))
                    code, data = svc.post_create_signed_file_url(auth_hdr, file_id, ttl_seconds=ttl)
                    self._send_json(code, data)
                elif path == "/api/v1/admin/roles":
                    code, data = svc.post_admin_assign_role(auth_hdr, body)
                    self._send_json(code, data)
                else:
                    self._send_json(404, {"error_code": "NOT_FOUND", "message": f"Unknown endpoint {path}"})
            except ApiHttpError as err:
                self._send_json(err.status_code, {"error_code": err.error_code, "message": err.message})
            except Exception as exc:
                self._send_json(500, {"error_code": "INTERNAL_ERROR", "message": str(exc)})

        def do_DELETE(self) -> None:
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/")
            auth_hdr = self.headers.get("Authorization")
            try:
                if path.startswith("/api/v1/admin/jobs/"):
                    job_id = path.split("/")[-1]
                    code, data = svc.delete_admin_job(auth_hdr, job_id)
                    self._send_json(code, data)
                elif path.startswith("/api/v1/satellite/farms/"):
                    farm_id = path.split("/")[-1]
                    code, data = svc.delete_farm(auth_hdr, farm_id)
                    self._send_json(code, data)
                else:
                    self._send_json(404, {"error_code": "NOT_FOUND", "message": f"Unknown endpoint {path}"})
            except ApiHttpError as err:
                self._send_json(err.status_code, {"error_code": err.error_code, "message": err.message})
            except Exception as exc:
                self._send_json(500, {"error_code": "INTERNAL_ERROR", "message": str(exc)})

    server = ThreadingHTTPServer((host, port), Handler)
    return server, svc


try:
    from fastapi import FastAPI, Header, Request  # type: ignore
    from fastapi.responses import JSONResponse, Response  # type: ignore

    default_service = SatelliteBackendService()
    fastapi_app = FastAPI(title="AgroSight Sentinel-2 Raster Processing API", version="4.0.0")

    @fastapi_app.get("/api/v1/health")
    async def health_endpoint() -> JSONResponse:
        code, data = default_service.get_health()
        return JSONResponse(status_code=code, content=data)

    @fastapi_app.post("/api/v1/satellite/scenes")
    async def search_scenes_endpoint(
        request: Request,
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            body = await request.json()
            code, data = default_service.post_search_scenes(authorization, body)
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.post("/api/v1/satellite/process")
    async def process_raster_endpoint(
        request: Request,
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            body = await request.json()
            code, data = default_service.post_process_raster(authorization, body, run_async=bool(body.get("async", False)))
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.get("/api/v1/satellite/jobs/{job_id}")
    async def get_job_endpoint(
        job_id: str,
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            code, data = default_service.get_job_status(authorization, job_id)
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.get("/api/v1/satellite/results/{result_id}")
    async def get_result_endpoint(
        result_id: str,
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            code, data = default_service.get_result(authorization, result_id)
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.get("/api/v1/satellite/files/{file_id}")
    async def get_private_file_endpoint(
        file_id: str,
        request: Request,
        authorization: Optional[str] = Header(default=None),
    ) -> Response:
        try:
            qp = dict(request.query_params)
            code, raw_bytes, ctype, fname = default_service.get_private_output_file(authorization, file_id, query_params=qp)
            return Response(
                content=raw_bytes,
                status_code=code,
                media_type=ctype,
                headers={"Content-Disposition": f'attachment; filename="{fname}"', "Cache-Control": "private, no-store"},
            )
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.post("/api/v1/satellite/files/{file_id}/signed-url")
    async def post_signed_file_url_endpoint(
        file_id: str,
        request: Request,
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            body = await request.json()
            ttl = int(body.get("ttl_seconds", 300))
            code, data = default_service.post_create_signed_file_url(authorization, file_id, ttl_seconds=ttl)
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.get("/api/v1/satellite/farms/{farm_id}")
    async def get_farm_endpoint(
        farm_id: str,
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            code, data = default_service.get_farm(authorization, farm_id)
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.delete("/api/v1/satellite/farms/{farm_id}")
    async def delete_farm_endpoint(
        farm_id: str,
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            code, data = default_service.delete_farm(authorization, farm_id)
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.get("/api/v1/admin/overview")
    async def get_admin_overview_endpoint(
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            code, data = default_service.get_admin_overview(authorization)
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.post("/api/v1/admin/roles")
    async def post_admin_roles_endpoint(
        request: Request,
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            body = await request.json()
            code, data = default_service.post_admin_assign_role(authorization, body)
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

    @fastapi_app.delete("/api/v1/admin/jobs/{job_id}")
    async def delete_admin_job_endpoint(
        job_id: str,
        authorization: Optional[str] = Header(default=None),
    ) -> JSONResponse:
        try:
            code, data = default_service.delete_admin_job(authorization, job_id)
            return JSONResponse(status_code=code, content=data)
        except ApiHttpError as err:
            return JSONResponse(status_code=err.status_code, content={"error_code": err.error_code, "message": err.message})

except ImportError:
    fastapi_app = None
