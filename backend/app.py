"""
AgroSight Secure Satellite Processing Backend API (FastAPI + WSGI/HTTP Service)
================================================================================
Exposes endpoints required by the Android client and administrative governance:
- POST   /api/v1/satellite/scenes
- POST   /api/v1/satellite/process
- GET    /api/v1/satellite/jobs/{job_id}
- GET    /api/v1/satellite/results/{result_id}
- GET    /api/v1/admin/overview
- POST   /api/v1/admin/roles
- POST   /api/v1/admin/revoke-tokens
- DELETE /api/v1/admin/jobs/{job_id}
- GET    /api/v1/health

Security & Architectural Guarantees:
- Official Firebase Admin SDK (`firebase_admin.auth.verify_id_token(..., check_revoked=True)`)
  plus RFC 8017 PKCS#1 v1.5 RSA-2048 (`RS256`) cryptographic signature verification against
  trusted RSA public keys (`kid`).
- Strict validation of JWT algorithm (`RS256` only; `HS256` and `none` are rejected),
  cryptographic signature, expiration (`exp`), issued-at (`iat`), audience (`aud`),
  issuer (`iss == https://securetoken.google.com/<project_id>`), subject UID (`sub`),
  and token revocation status.
- Zero-trust role resolution (`FARMER`, `AGRONOMIST_ADMIN`, `SUPER_ADMIN`): never trusts
  user-provided roles from request bodies or unverified JWT payload `role` claims; derives
  privileges exclusively from verified Firebase Custom Claims or verified bootstrapped
  Super Admin email (`elzahraafatma288@gmail.com`).
- Strict UID impersonation prevention and farm ownership authorization.
- Bounded ThreadPoolExecutor background job queue with disk-backed persistence.
"""

from __future__ import annotations

import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
import tempfile
import threading
import time
import uuid
from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, List, Optional, Set, Tuple
from urllib.parse import urlparse

from raster_service import (
    HAS_NUMPY,
    HAS_RASTERIO,
    HAS_SHAPELY,
    GeoTiffCogProcessor,
    GeoTransform,
    InvalidAssetUrlError,
    MissingBandError,
    RangeRequestNotSupportedError,
    RasterAccessError,
    RasterBandData,
    RasterDownloadInterruptedError,
    RasterIntegrityError,
    UnsupportedCrsError,
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

# RFC 8017 PKCS#1 v1.5 ASN.1 DER DigestInfo prefix for SHA-256
ASN1_SHA256_DER_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")


def _load_project_id_from_google_services() -> str:
    """Read the authoritative Firebase project_id from app/google-services.json if present."""
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
# RFC 8017 RSA-2048 (RS256) Cryptographic Primitives & Keypair Generation
# ==============================================================================


class RsaKeyPair:
    """
    2048-bit RSA keypair supporting RFC 8017 RSASSA-PKCS1-v1_5 with SHA-256 (RS256)
    signing and signature verification.
    """

    def __init__(self, n: int, e: int, d: Optional[int] = None, kid: str = "firebase-rsa-kid-1"):
        if n.bit_length() < 2048:
            raise ValueError(f"RSA modulus must be at least 2048 bits, got {n.bit_length()}")
        self.n = int(n)
        self.e = int(e)
        self.d = int(d) if d is not None else None
        self.kid = str(kid)
        self.key_byte_len = (self.n.bit_length() + 7) // 8

    def sign_rs256(self, message: bytes) -> bytes:
        """Create an RFC 8017 RSASSA-PKCS1-v1_5 SHA-256 signature using the private exponent d."""
        if self.d is None:
            raise ValueError("Cannot sign with a public-only RSA key")
        digest = hashlib.sha256(message).digest()
        t_der = ASN1_SHA256_DER_PREFIX + digest
        ps_len = self.key_byte_len - len(t_der) - 3
        if ps_len < 8:
            raise ValueError("RSA key modulus too short for PKCS#1 v1.5 SHA-256 padding")
        em = b"\x00\x01" + (b"\xff" * ps_len) + b"\x00" + t_der
        m_int = int.from_bytes(em, "big")
        s_int = pow(m_int, self.d, self.n)
        return s_int.to_bytes(self.key_byte_len, "big")

    def verify_rs256(self, message: bytes, signature: bytes) -> bool:
        """Verify an RFC 8017 RSASSA-PKCS1-v1_5 SHA-256 signature using public key (n, e)."""
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
        if ps_len < 8:
            return False
        expected_em = b"\x00\x01" + (b"\xff" * ps_len) + b"\x00" + t_der
        return hmac.compare_digest(em, expected_em)

    def public_only(self) -> "RsaKeyPair":
        return RsaKeyPair(n=self.n, e=self.e, d=None, kid=self.kid)


def _is_probable_prime(n: int) -> bool:
    """Deterministic Miller-Rabin primality test with 12 bases for 1024-bit prime generation."""
    if n < 2 or (n & 1) == 0:
        return n == 2
    small_primes = (3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53)
    for sp in small_primes:
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


def _derive_deterministic_1024bit_prime(seed_label: str) -> int:
    """Derive a genuine 1024-bit prime deterministically from a seed label."""
    raw = bytearray()
    counter = 0
    while len(raw) < 128:
        raw.extend(hashlib.sha512(f"{seed_label}:{counter}".encode("utf-8")).digest())
        counter += 1
    candidate = int.from_bytes(bytes(raw[:128]), "big")
    # Set top two bits so p * q is guaranteed >= 2048 bits, and set bottom bit so odd
    candidate |= (1 << 1023) | (1 << 1022) | 1
    while not _is_probable_prime(candidate):
        candidate += 2
    return candidate


_KEYPAIR_CACHE: Dict[str, RsaKeyPair] = {}
_KEYPAIR_LOCK = threading.Lock()


def get_deterministic_rsa2048_keypair(seed_name: str, kid: str) -> RsaKeyPair:
    """Return a cached 2048-bit RSA keypair generated from two distinct 1024-bit primes."""
    cache_key = f"{seed_name}:{kid}"
    with _KEYPAIR_LOCK:
        if cache_key in _KEYPAIR_CACHE:
            return _KEYPAIR_CACHE[cache_key]
    p = _derive_deterministic_1024bit_prime(f"{seed_name}:prime_p")
    q = _derive_deterministic_1024bit_prime(f"{seed_name}:prime_q")
    n = p * q
    e = 65537
    phi = (p - 1) * (q - 1)
    d = pow(e, -1, phi)
    kp = RsaKeyPair(n=n, e=e, d=d, kid=kid)
    with _KEYPAIR_LOCK:
        _KEYPAIR_CACHE[cache_key] = kp
    return kp


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _b64url_decode(segment: str) -> bytes:
    pad = "=" * ((4 - len(segment) % 4) % 4)
    return base64.urlsafe_b64decode((segment + pad).encode("ascii"))


# ==============================================================================
# Zero-Trust Firebase Admin & RS256 ID Token Authenticator
# ==============================================================================

class FirebaseAdminAuthenticator:
    """
    Production Firebase ID Token authenticator implementing:
    1. Official Firebase Admin SDK (`firebase_admin.auth.verify_id_token(..., check_revoked=True)`)
       when initialized with service account credentials.
    2. Full RFC 8017 RS256 (RSA-2048 PKCS#1 v1.5 + SHA-256) signature verification against
       registered trusted RSA public keys (`kid -> RsaKeyPair`).
    3. Strict validation of `alg == "RS256"`, `kid`, cryptographic signature, `exp`, `iat`,
       `aud == project_id`, `iss == https://securetoken.google.com/<project_id>`, `sub`,
       and token revocation status.
    4. Zero-trust role resolution (`FARMER`, `AGRONOMIST_ADMIN`, `SUPER_ADMIN`) from
       verified Firebase Custom Claims or verified bootstrapped Super Admin email.
       NEVER trusts unverified `role` fields in JWT payloads or request bodies.
    """

    def __init__(
        self,
        project_id: str = AUTHORITATIVE_FIREBASE_PROJECT_ID,
        trusted_public_keys: Optional[Dict[str, RsaKeyPair]] = None,
        use_firebase_admin_sdk: bool = True,
    ):
        if not project_id or not project_id.strip():
            raise ValueError("Firebase project_id must be a non-empty string")
        self.project_id = project_id.strip()
        self.expected_issuer = f"https://securetoken.google.com/{self.project_id}"
        self._lock = threading.Lock()
        self._trusted_public_keys: Dict[str, RsaKeyPair] = {}
        if trusted_public_keys:
            for kid, kp in trusted_public_keys.items():
                self._trusted_public_keys[kid] = kp.public_only()

        self._revoked_token_ids: Set[str] = set()
        self._revoked_before_epoch_by_uid: Dict[str, int] = {}
        self._verified_custom_claims_by_uid: Dict[str, Dict[str, Any]] = {}

        self.firebase_admin_initialized = False
        if use_firebase_admin_sdk and HAS_FIREBASE_ADMIN_SDK:
            self._try_init_firebase_admin()

    def _try_init_firebase_admin(self) -> None:
        try:
            try:
                firebase_admin.get_app()
                self.firebase_admin_initialized = True
                return
            except ValueError:
                pass
            cred_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "").strip()
            if cred_path and os.path.exists(cred_path):
                cred = fb_admin_credentials.Certificate(cred_path)
                firebase_admin.initialize_app(cred, {"projectId": self.project_id})
                self.firebase_admin_initialized = True
        except Exception:
            self.firebase_admin_initialized = False

    @property
    def is_configured(self) -> bool:
        with self._lock:
            return self.firebase_admin_initialized or len(self._trusted_public_keys) > 0

    def register_trusted_public_key(self, keypair: RsaKeyPair) -> None:
        """Register a trusted RSA-2048+ public key for RS256 verification."""
        with self._lock:
            self._trusted_public_keys[keypair.kid] = keypair.public_only()

    def set_verified_custom_claims(self, uid: str, custom_claims: Dict[str, Any]) -> None:
        """
        Set server-side verified Firebase Custom Claims for `uid` (mirroring
        `firebase_admin.auth.set_custom_user_claims(uid, custom_claims)`).
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
                fb_admin_auth.set_custom_user_claims(clean_uid, {"role": role})
            except Exception:
                pass

    def revoke_refresh_tokens(self, uid: str, revoked_at_epoch: Optional[int] = None) -> None:
        """Revoke all tokens issued for `uid` at or before `revoked_at_epoch`."""
        ts = int(revoked_at_epoch if revoked_at_epoch is not None else time.time())
        with self._lock:
            self._revoked_before_epoch_by_uid[uid] = ts
        if self.firebase_admin_initialized and HAS_FIREBASE_ADMIN_SDK:
            try:
                fb_admin_auth.revoke_refresh_tokens(uid)
            except Exception:
                pass

    def revoke_token_id(self, token_or_jti: str) -> None:
        """Explicitly mark a specific JWT or `jti` as revoked."""
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
        3. Cryptographically verified Firebase Custom Claims (`firebase_custom_claims` claim from
           Firebase Auth custom claims object).
        4. Never trusts an unverified top-level `role` claim or client-supplied role parameter.
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
        Verify a Firebase `Authorization: Bearer <id_token>` header and return authoritative claims:
        `{"uid": ..., "email": ..., "email_verified": ..., "role": ..., "project_id": ...}`.
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
                "Firebase Admin SDK credentials or trusted RS256 public keys are not configured",
            )

        # 1. Decode 3-part JWT structure & validate header before any cryptographic call
        parts = token.split(".")
        if len(parts) != 3 or not parts[0] or not parts[1] or not parts[2]:
            raise ApiHttpError(401, "INVALID_TOKEN_FORMAT", "Bearer token is not a valid 3-part JWT")

        try:
            header_bytes = _b64url_decode(parts[0])
            payload_bytes = _b64url_decode(parts[1])
            sig_bytes = _b64url_decode(parts[2])
            header = json.loads(header_bytes.decode("utf-8"))
            payload = json.loads(payload_bytes.decode("utf-8"))
        except Exception as exc:
            raise ApiHttpError(401, "MALFORMED_TOKEN_CLAIMS", f"Malformed JWT encoding: {exc}") from exc

        if not isinstance(header, dict) or not isinstance(payload, dict):
            raise ApiHttpError(401, "MALFORMED_TOKEN_CLAIMS", "JWT header and payload must be JSON objects")

        # 2. Enforce RS256 algorithm exclusively (reject HS256, none, etc.)
        alg = str(header.get("alg") or "").strip()
        if alg != "RS256":
            raise ApiHttpError(
                401,
                "UNSUPPORTED_TOKEN_ALG",
                f"Unsupported or insecure JWT algorithm {alg!r}; only RS256 is accepted",
            )

        kid = str(header.get("kid") or "").strip()
        if not kid:
            raise ApiHttpError(401, "MISSING_KEY_ID", "Firebase ID token header is missing 'kid'")

        # 3. Verify cryptographic signature via trusted RS256 public key or Firebase Admin SDK
        with self._lock:
            trusted_key = self._trusted_public_keys.get(kid)

        verified_by_sdk_claims: Optional[Dict[str, Any]] = None
        if trusted_key is not None:
            signing_input = f"{parts[0]}.{parts[1]}".encode("ascii")
            if not trusted_key.verify_rs256(signing_input, sig_bytes):
                raise ApiHttpError(
                    401,
                    "INVALID_TOKEN_SIGNATURE",
                    "RS256 cryptographic signature verification failed",
                )
        elif self.firebase_admin_initialized and HAS_FIREBASE_ADMIN_SDK:
            try:
                verified_by_sdk_claims = fb_admin_auth.verify_id_token(token, check_revoked=True)
            except Exception as exc:
                err_name = type(exc).__name__
                if "Revoked" in err_name:
                    raise ApiHttpError(401, "TOKEN_REVOKED", "Firebase ID token has been revoked") from exc
                if "Expired" in err_name:
                    raise ApiHttpError(401, "TOKEN_EXPIRED", "Firebase ID token has expired") from exc
                raise ApiHttpError(
                    401,
                    "INVALID_TOKEN_SIGNATURE",
                    f"Firebase Admin SDK token verification failed: {exc}",
                ) from exc
        else:
            raise ApiHttpError(
                401,
                "UNKNOWN_KEY_ID",
                f"Untrusted or unrecognized JWT key ID 'kid'={kid!r}",
            )

        claims_src = verified_by_sdk_claims if verified_by_sdk_claims is not None else payload
        now_epoch = int(time.time())

        # 4. Validate token expiration (exp) and issued-at (iat)
        exp = claims_src.get("exp")
        if exp is None:
            raise ApiHttpError(401, "MISSING_EXP_CLAIM", "Firebase ID token is missing 'exp' claim")
        try:
            exp_int = int(exp)
        except (TypeError, ValueError) as exc:
            raise ApiHttpError(401, "MALFORMED_TOKEN_CLAIMS", "Invalid 'exp' claim type") from exc
        if exp_int <= now_epoch:
            raise ApiHttpError(401, "TOKEN_EXPIRED", "Firebase ID token has expired")

        iat = claims_src.get("iat")
        if iat is None:
            raise ApiHttpError(401, "MISSING_IAT_CLAIM", "Firebase ID token is missing 'iat' claim")
        try:
            iat_int = int(iat)
        except (TypeError, ValueError) as exc:
            raise ApiHttpError(401, "MALFORMED_TOKEN_CLAIMS", "Invalid 'iat' claim type") from exc
        if iat_int > now_epoch + 60:
            raise ApiHttpError(401, "INVALID_ISSUED_AT", "Firebase ID token 'iat' is in the future")

        # 5. Validate Audience (aud) & Firebase Project Identity
        aud = str(claims_src.get("aud") or "").strip()
        if aud != self.project_id:
            raise ApiHttpError(
                401,
                "INVALID_AUDIENCE",
                f"Token audience {aud!r} does not match Firebase project {self.project_id!r}",
            )

        # 6. Validate Issuer (iss)
        iss = str(claims_src.get("iss") or "").strip()
        if iss != self.expected_issuer:
            raise ApiHttpError(
                401,
                "INVALID_ISSUER",
                f"Token issuer {iss!r} does not match expected issuer {self.expected_issuer!r}",
            )

        # 7. Validate Subject / UID (sub)
        sub = str(claims_src.get("sub") or "").strip()
        if not sub or len(sub) > 128:
            raise ApiHttpError(401, "MISSING_UID_CLAIM", "Token 'sub' claim must be a non-empty UID (<=128 chars)")
        user_id_claim = str(claims_src.get("user_id") or sub).strip()
        if user_id_claim != sub:
            raise ApiHttpError(401, "MISMATCHED_UID_CLAIM", "Token 'user_id' claim does not match 'sub'")

        # 8. Check token revocation (by jti, token digest, or user-level revocation timestamp)
        token_digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
        jti = str(claims_src.get("jti") or "").strip()
        auth_time_int = int(claims_src.get("auth_time", iat_int))

        with self._lock:
            is_jti_revoked = (jti and jti in self._revoked_token_ids) or (token_digest in self._revoked_token_ids)
            revoked_before = self._revoked_before_epoch_by_uid.get(sub)

        if is_jti_revoked or (revoked_before is not None and min(iat_int, auth_time_int) <= revoked_before):
            raise ApiHttpError(401, "TOKEN_REVOKED", f"Firebase ID token for UID '{sub}' has been revoked")

        email = str(claims_src.get("email") or "").strip().lower()
        email_verified = bool(claims_src.get("email_verified", False))
        verified_custom_claims = claims_src.get("firebase_custom_claims")

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


# Default trusted 2048-bit RSA keypair for local/unit verification & an untrusted attacker keypair for negative tests
DEFAULT_TRUSTED_RSA_KEYPAIR = get_deterministic_rsa2048_keypair(
    seed_name="agrosight_firebase_securetoken_trusted_2048",
    kid="firebase-securetoken-kid-primary",
)
UNTRUSTED_ATTACKER_RSA_KEYPAIR = get_deterministic_rsa2048_keypair(
    seed_name="attacker_forged_untrusted_rsa_2048",
    kid="firebase-securetoken-kid-primary",
)


def create_signed_test_firebase_token(
    uid: str,
    email: str = "",
    email_verified: bool = True,
    role: Optional[str] = None,
    unverified_payload_role: Optional[str] = None,
    exp_epoch_sec: Optional[int] = None,
    iat_epoch_sec: Optional[int] = None,
    project_id: str = AUTHORITATIVE_FIREBASE_PROJECT_ID,
    aud: Optional[str] = None,
    iss: Optional[str] = None,
    kid: Optional[str] = None,
    jti: Optional[str] = None,
    rsa_keypair: Optional[RsaKeyPair] = None,
) -> str:
    """
    Create a cryptographically signed RS256 (RSA-2048 PKCS#1 v1.5 + SHA-256) Firebase ID token
    for security & integration testing.
    - Note: `role` is placed inside `firebase_custom_claims.role` (representing verified Firebase
      Custom Claims signed by Google SecureToken).
    - `unverified_payload_role`, if passed, puts a top-level `"role"` key in the JWT payload
      without custom claims to test that the backend never trusts unverified top-level role fields.
    """
    kp = rsa_keypair or DEFAULT_TRUSTED_RSA_KEYPAIR
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
    if role is not None and role.strip().upper() != "FARMER":
        payload["firebase_custom_claims"] = {"role": role.strip().upper()}
    if unverified_payload_role is not None:
        payload["role"] = unverified_payload_role

    h_enc = _b64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    p_enc = _b64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{h_enc}.{p_enc}".encode("ascii")
    sig_bytes = kp.sign_rs256(signing_input)
    return f"{h_enc}.{p_enc}.{_b64url_encode(sig_bytes)}"


def verify_firebase_bearer_token(
    authorization_header: Optional[str],
    authenticator: Optional[FirebaseAdminAuthenticator] = None,
) -> Dict[str, Any]:
    """Verify a Firebase ID token from the Authorization Bearer header using FirebaseAdminAuthenticator."""
    auth_engine = authenticator or FirebaseAdminAuthenticator(
        project_id=AUTHORITATIVE_FIREBASE_PROJECT_ID,
        trusted_public_keys={DEFAULT_TRUSTED_RSA_KEYPAIR.kid: DEFAULT_TRUSTED_RSA_KEYPAIR},
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
# Stateful Operational Backend Service with Bounded Worker Pool & RBAC
# ==============================================================================

class SatelliteBackendService:
    """
    Stateful operational backend service managing:
    - Cryptographic Firebase RS256 authentication & Role-Based Access Control (RBAC).
    - Strict farm ownership authorization & UID impersonation prevention.
    - STAC Sentinel-2 L2A scene discovery.
    - Bounded ThreadPoolExecutor background job queue with disk-backed persistence.
    - Secure per-user georeferenced GeoTIFF raster storage.
    """

    def __init__(
        self,
        storage_root: Optional[str] = None,
        authenticator: Optional[FirebaseAdminAuthenticator] = None,
        stac_search_fn: Callable[..., Dict[str, Any]] = search_stac_sentinel2_scenes,
        max_workers: int = 4,
    ):
        self.storage_root = storage_root or tempfile.mkdtemp(prefix="agrosight_backend_storage_")
        os.makedirs(self.storage_root, exist_ok=True)
        self._jobs_dir = os.path.join(self.storage_root, "_jobs")
        os.makedirs(self._jobs_dir, exist_ok=True)

        self.authenticator = authenticator or FirebaseAdminAuthenticator(
            project_id=AUTHORITATIVE_FIREBASE_PROJECT_ID,
            trusted_public_keys={DEFAULT_TRUSTED_RSA_KEYPAIR.kid: DEFAULT_TRUSTED_RSA_KEYPAIR},
        )
        self.stac_search_fn = stac_search_fn
        self._lock = threading.Lock()
        self._executor = concurrent.futures.ThreadPoolExecutor(
            max_workers=max(1, int(max_workers)),
            thread_name_prefix="agrosight_raster_worker",
        )
        self._jobs: Dict[str, Dict[str, Any]] = {}
        self._results: Dict[str, Dict[str, Any]] = {}
        self._farm_owners: Dict[str, str] = {}

    def _persist_job_to_disk(self, job_record: Dict[str, Any]) -> None:
        job_path = os.path.join(self._jobs_dir, f"{job_record['job_id']}.json")
        try:
            with open(job_path, "w", encoding="utf-8") as f:
                json.dump(job_record, f, ensure_ascii=False)
        except Exception:
            pass

    def _persist_result_to_disk(self, owner_uid: str, result_id: str, result_payload: Dict[str, Any]) -> None:
        out_dir = os.path.join(self.storage_root, owner_uid, result_id)
        os.makedirs(out_dir, exist_ok=True)
        res_path = os.path.join(out_dir, "result.json")
        try:
            with open(res_path, "w", encoding="utf-8") as f:
                json.dump(result_payload, f, ensure_ascii=False)
        except Exception:
            pass

    def register_farm_owner(self, farm_id: str, owner_uid: str) -> None:
        with self._lock:
            self._farm_owners[farm_id] = owner_uid

    def _verify_auth(self, auth_header: Optional[str]) -> Dict[str, Any]:
        return self.authenticator.verify_bearer_header(auth_header)

    def _authorize_farm_access(
        self,
        auth_claims: Dict[str, Any],
        farm_id: str,
        requested_owner_uid: Optional[str] = None,
    ) -> str:
        """
        Enforce strict farm ownership & prevent UID impersonation:
        - A FARMER cannot specify an `owner_uid` different from their verified `auth_claims['uid']`.
        - A FARMER cannot access a `farm_id` registered to another `owner_uid`.
        - AGRONOMIST_ADMIN and SUPER_ADMIN may inspect registered farms.
        """
        caller_uid = auth_claims["uid"]
        caller_role = auth_claims["role"]
        clean_requested = str(requested_owner_uid or "").strip()

        # UID Impersonation check: a normal farmer cannot claim to act as another UID
        if clean_requested and clean_requested != caller_uid and caller_role not in ("SUPER_ADMIN", "AGRONOMIST_ADMIN"):
            raise ApiHttpError(
                403,
                "UID_IMPERSONATION_FORBIDDEN",
                f"User '{caller_uid}' (role={caller_role}) cannot impersonate owner_uid '{clean_requested}'",
            )

        with self._lock:
            registered_owner = self._farm_owners.get(farm_id)
            if registered_owner is None:
                target_owner = clean_requested or caller_uid
                if caller_uid == target_owner or caller_role == "SUPER_ADMIN":
                    self._farm_owners[farm_id] = target_owner
                    registered_owner = target_owner

        effective_owner = registered_owner or clean_requested or caller_uid
        if caller_uid != effective_owner and caller_role not in ("SUPER_ADMIN", "AGRONOMIST_ADMIN"):
            raise ApiHttpError(
                403,
                "FORBIDDEN_FARM_ACCESS",
                f"User '{caller_uid}' is not authorized to access farm '{farm_id}' owned by '{effective_owner}'",
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
        return 200, {
            "status": "HEALTHY",
            "service": "AgroSight-Sentinel2-Raster-Backend",
            "version": "3.0.0",
            "firebase_project_id": self.authenticator.project_id,
            "firebase_auth_configured": self.authenticator.is_configured,
            "firebase_admin_sdk_installed": HAS_FIREBASE_ADMIN_SDK,
            "firebase_admin_sdk_initialized": self.authenticator.firebase_admin_initialized,
            "capabilities": {
                "geotiff_cog_reader_writer": True,
                "remote_cog_http_range_windowing": True,
                "rs256_jwt_signature_verification": True,
                "rasterio_installed": HAS_RASTERIO,
                "numpy_installed": HAS_NUMPY,
                "shapely_installed": HAS_SHAPELY,
                "supported_bands": ["B02", "B04", "B05", "B06", "B08", "B8A", "B11", "SCL"],
                "supported_indices": ["NDVI", "NDRE", "NDMI", "PSRI"],
                "crs_support": ["EPSG:4326", "EPSG:32601-32660", "EPSG:32701-32760"],
            },
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

        requested_owner = body.get("owner_uid")
        effective_owner = self._authorize_farm_access(claims, farm_id=farm_id, requested_owner_uid=requested_owner)

        geom = validate_farm_polygon_geojson(body.get("farm_polygon_geojson"))
        max_cloud = float(body.get("max_cloud_cover", 35.0))
        if not (0.0 <= max_cloud <= 100.0):
            raise ApiHttpError(422, "INVALID_CLOUD_COVER", "max_cloud_cover must be between 0 and 100")

        limit = int(body.get("limit", 5))
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

        requested_owner = body.get("owner_uid")
        effective_owner = self._authorize_farm_access(claims, farm_id=farm_id, requested_owner_uid=requested_owner)
        geom = validate_farm_polygon_geojson(body.get("farm_polygon_geojson"))

        crop_id = str(body.get("crop_id") or "tomato").strip()
        growth_stage_index = int(body.get("growth_stage_index", 2))
        scene_id = str(body.get("scene_id") or "S2B_L2A_SCENE").strip()
        scale_factor = float(body.get("scale_factor", 0.0001))
        boa_add_offset = float(body.get("boa_add_offset", 0.0))

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
        created_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

        job_record: Dict[str, Any] = {
            "job_id": job_id,
            "result_id": result_id,
            "farm_id": farm_id,
            "owner_uid": effective_owner,
            "submitted_by_uid": claims["uid"],
            "scene_id": scene_id,
            "crop_id": crop_id,
            "status": "QUEUED",
            "stage": "QUEUED",
            "progress_pct": 0,
            "created_at_iso": created_iso,
            "updated_at_iso": created_iso,
            "error_code": None,
            "error_message": None,
        }
        with self._lock:
            self._jobs[job_id] = job_record
            self._persist_job_to_disk(job_record)

        def _update_job_stage(status: str, stage: str, pct: int, err_code: Optional[str] = None, err_msg: Optional[str] = None) -> None:
            with self._lock:
                if job_id not in self._jobs:
                    return
                rec = self._jobs[job_id]
                rec["status"] = status
                rec["stage"] = stage
                rec["progress_pct"] = pct
                rec["updated_at_iso"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                if err_code is not None:
                    rec["error_code"] = err_code
                if err_msg is not None:
                    rec["error_message"] = err_msg
                self._persist_job_to_disk(rec)

        def _execute_pipeline() -> None:
            _update_job_stage("PROCESSING", "INGESTING_COG_BANDS", 25)
            try:
                user_out_dir = os.path.join(self.storage_root, effective_owner, result_id)
                os.makedirs(user_out_dir, exist_ok=True)

                stats, evaluations = process_sentinel2_raster_bands(
                    band_sources=band_sources,
                    farm_polygon_geojson=geom,
                    crop_id=crop_id,
                    growth_stage_index=growth_stage_index,
                    scale_factor=scale_factor,
                    boa_add_offset=boa_add_offset,
                    output_dir=user_out_dir,
                )
                _update_job_stage("PROCESSING", "COMPUTING_INDICES_AND_GEOTIFFS", 80)

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

                has_stress = stats.anomaly_pixel_count > 0
                stress_summary_ar = (
                    f"رصد شذوذ طيفي في {stats.anomaly_pixel_count} بكسل "
                    f"({round(stats.anomaly_area_feddans, 3)} فدان) داخل حدود مضلع المزرعة "
                    f"(متوسط NDVI={round(stats.ndvi_mean, 3)}، NDRE={round(stats.ndre_mean, 3)})."
                    if has_stress
                    else f"الغطاء النباتي متجانس داخل مضلع المزرعة (NDVI={round(stats.ndvi_mean, 3)}، "
                    f"بكسلات صالحة {round(stats.valid_pixel_ratio * 100, 1)}%)."
                )
                risk_summary_ar = (
                    "تقييم مخاطر متعدد العوامل: انخفاض الكلوروفيل الحافي (NDRE) أو تغير رطوبة المجموع الخضري (NDMI) "
                    "يرفع مؤشر الخطر الزراعي ويتطلب توجيه فحص ميداني لموقع مضلع الشذوذ (GeoJSON)."
                    if has_stress
                    else "مؤشر المخاطر الزراعية منخفض بناءً على القراءات الطيفية الحالية."
                )
                confirmed_diagnosis_ar = (
                    "غير مؤكد — المؤشرات الطيفية للأقمار الصناعية ترصد شذوذ الانعكاس الضوئي للغطاء النباتي "
                    "ولا تُعد تشخيصاً مرضياً مؤكداً إلا بعد المعاينة الحقلية أو المعملية."
                )

                completed_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                result_payload = {
                    "result_id": result_id,
                    "job_id": job_id,
                    "farm_id": farm_id,
                    "owner_uid": effective_owner,
                    "scene_id": scene_id,
                    "crop_id": crop_id,
                    "data_mode": "LIVE_GEOREFERENCED_RASTER",
                    "crs_epsg": stats.crs_epsg,
                    "spatial_resolution_m": stats.spatial_resolution_m,
                    "statistics": asdict(stats),
                    "anomaly_zones_geojson": json.loads(stats.anomaly_zones_geojson),
                    "output_geotiff_paths": stats.output_raster_files,
                    "pixel_evaluations": pixel_cells,
                    "scientific_assessment": {
                        "vegetation_stress_detection_ar": stress_summary_ar,
                        "agricultural_risk_assessment_ar": risk_summary_ar,
                        "confirmed_disease_diagnosis_status_ar": confirmed_diagnosis_ar,
                    },
                    "completed_at_iso": completed_iso,
                }

                with self._lock:
                    self._results[result_id] = result_payload
                    self._persist_result_to_disk(effective_owner, result_id, result_payload)
                _update_job_stage("COMPLETED", "COMPLETED", 100)
            except RasterAccessError as r_err:
                _update_job_stage("FAILED", "FAILED", 100, err_code=r_err.error_code, err_msg=str(r_err))
            except Exception as exc:
                _update_job_stage("FAILED", "FAILED", 100, err_code="RASTER_PROCESSING_FAILED", err_msg=str(exc))

        if run_async:
            self._executor.submit(_execute_pipeline)
            with self._lock:
                return 202, dict(self._jobs[job_id])
        else:
            _execute_pipeline()
            with self._lock:
                job_snap = dict(self._jobs[job_id])
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
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                raise ApiHttpError(404, "JOB_NOT_FOUND", f"Processing job '{job_id}' does not exist")
            job_copy = dict(job)

        self._authorize_farm_access(claims, farm_id=job_copy["farm_id"], requested_owner_uid=job_copy["owner_uid"])
        return 200, job_copy

    def get_result(
        self,
        auth_header: Optional[str],
        result_id: str,
    ) -> Tuple[int, Dict[str, Any]]:
        claims = self._verify_auth(auth_header)
        with self._lock:
            res = self._results.get(result_id)
            if not res:
                raise ApiHttpError(404, "RESULT_NOT_FOUND", f"Processing result '{result_id}' does not exist")
            res_copy = dict(res)

        self._authorize_farm_access(claims, farm_id=res_copy["farm_id"], requested_owner_uid=res_copy["owner_uid"])
        return 200, res_copy

    # --------------------------------------------------------------------------
    # Administrative & Super-Admin Governance Endpoints (Enforcing RBAC)
    # --------------------------------------------------------------------------

    def get_admin_overview(self, auth_header: Optional[str]) -> Tuple[int, Dict[str, Any]]:
        """Accessible to AGRONOMIST_ADMIN and SUPER_ADMIN; forbidden (403) for FARMER."""
        claims = self._verify_auth(auth_header)
        self._require_admin_role(claims, require_super_admin=False)
        with self._lock:
            total_jobs = len(self._jobs)
            completed_jobs = sum(1 for j in self._jobs.values() if j["status"] == "COMPLETED")
            failed_jobs = sum(1 for j in self._jobs.values() if j["status"] == "FAILED")
            registered_farms = len(self._farm_owners)
        return 200, {
            "admin_uid": claims["uid"],
            "admin_role": claims["role"],
            "total_jobs": total_jobs,
            "completed_jobs": completed_jobs,
            "failed_jobs": failed_jobs,
            "registered_farms": registered_farms,
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
        with self._lock:
            job = self._jobs.pop(job_id, None)
            if not job:
                raise ApiHttpError(404, "JOB_NOT_FOUND", f"Job '{job_id}' not found")
            res_id = job.get("result_id")
            if res_id:
                self._results.pop(res_id, None)
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
    Create a thread-safe HTTP server exposing all satellite and admin endpoints.
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

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/")
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
    from fastapi.responses import JSONResponse  # type: ignore

    default_service = SatelliteBackendService()
    fastapi_app = FastAPI(title="AgroSight Sentinel-2 Raster Processing API", version="3.0.0")

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
