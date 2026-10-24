"""TOPIS subway gateway. Run behind a trusted HTTPS reverse proxy; no third-party packages."""
from __future__ import annotations

import argparse
import hmac
import json
import os
from pathlib import Path
import sqlite3
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from zoneinfo import ZoneInfo

CATALOG = Path(__file__).resolve().parents[1] / "app/src/main/assets/seoul_subway_stations.json"
FIELDS = {
    "subwayId", "updnLine", "trainLineNm", "statnFid", "statnTid", "statnId", "statnNm",
    "btrainSttus", "barvlDt", "btrainNo", "bstatnId", "bstatnNm", "recptnDt", "arvlCd",
    "arvlMsg2", "arvlMsg3", "lstcarAt", "ordkey",
}


class GatewayFailure(Exception):
    def __init__(self, status: int, code: str):
        self.status, self.code = status, code
        super().__init__(code)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def fetch_official(key: str, station: str) -> dict:
    # This is the provider's documented HTTP endpoint. Client->gateway is HTTPS.
    # Keep the provider key server-side and never log this URL or a transport exception.
    url = "http://swopenapi.seoul.go.kr/api/subway/" + quote(key, safe="")
    url += "/json/realtimeStationArrival/0/1000/" + quote(station, safe="")
    try:
        opener = build_opener(ProxyHandler(), NoRedirect())
        with opener.open(Request(url, headers={"Accept": "application/json"}), timeout=15) as response:
            body = response.read(4_000_001)
        if len(body) > 4_000_000:
            raise ValueError("oversized")
        return json.loads(body)
    except Exception:
        raise GatewayFailure(502, "UPSTREAM_UNAVAILABLE") from None


def safe_payload(payload: dict, key: str) -> dict:
    """Only the documented data fields cross the gateway; no provider developerMessage/URL."""
    if not isinstance(payload, dict) or not isinstance(payload.get("errorMessage"), dict):
        raise GatewayFailure(502, "INVALID_UPSTREAM_RESPONSE")
    error = payload["errorMessage"]
    code = error.get("code")
    if code == "INFO-100":
        raise GatewayFailure(503, "UPSTREAM_AUTHENTICATION_REQUIRED")
    if code not in {"INFO-000", "INFO-200"}:
        raise GatewayFailure(502, "UPSTREAM_SERVICE_ERROR")
    if code == "INFO-000" and error.get("status") != 200:
        raise GatewayFailure(502, "INVALID_UPSTREAM_RESPONSE")
    rows = payload.get("realtimeArrivalList", [])
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise GatewayFailure(502, "INVALID_UPSTREAM_RESPONSE")
    result = {
        "errorMessage": {"status": 200, "code": code},
        "realtimeArrivalList": [{k: v for k, v in row.items() if k in FIELDS} for row in rows],
    }
    # Fail closed even if a provider unexpectedly echoes a key in a field we retained.
    if key and key in json.dumps(result, ensure_ascii=False):
        raise GatewayFailure(502, "INVALID_UPSTREAM_RESPONSE")
    return result


class SubwayGateway:
    def __init__(self, api_key: str, token: str, catalog: Path = CATALOG,
                 database: str = ":memory:", daily_limit: int = 900, fetcher=fetch_official,
                 wall_clock=time.time, monotonic=time.monotonic):
        self.api_key, self.token = api_key, token
        self.station_names = {s["name"] for s in json.loads(catalog.read_text(encoding="utf-8"))}
        self.daily_limit = max(1, min(daily_limit, 1000))
        self.fetcher, self.wall_clock, self.monotonic = fetcher, wall_clock, monotonic
        self.lock = threading.Lock()
        self.cache: dict[str, tuple[float, dict | GatewayFailure]] = {}
        self.last_outbound = float("-inf")
        self.db = sqlite3.connect(database, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS requests(day TEXT PRIMARY KEY, count INTEGER NOT NULL)")
        self.db.commit()

    @property
    def ready(self):
        return bool(self.api_key and self.api_key.lower() != "sample" and len(self.token) >= 32
                    and self.token.isascii() and not any(c.isspace() for c in self.token))

    def authorized(self, authorization: str):
        return self.ready and hmac.compare_digest(authorization, "Bearer " + self.token)

    def arrivals(self, station: str) -> dict:
        if station not in self.station_names:
            raise GatewayFailure(400, "UNKNOWN_STATION")
        if not self.ready:
            raise GatewayFailure(503, "CONFIGURATION_REQUIRED")
        # Serialize requests and coalesce polling across clients. Failures are cached too.
        with self.lock:
            now = self.monotonic()
            previous = self.cache.get(station)
            if previous and 0 <= now - previous[0] < 30:
                if isinstance(previous[1], GatewayFailure):
                    raise previous[1]
                return previous[1]
            # Global ceiling also limits attempts spread across many station names.
            if now - self.last_outbound < 1:
                raise GatewayFailure(429, "REQUEST_INTERVAL")
            day = datetime.fromtimestamp(self.wall_clock(), ZoneInfo("Asia/Seoul")).date().isoformat()
            with self.db:
                self.db.execute("INSERT OR IGNORE INTO requests VALUES (?, 0)", (day,))
                count = self.db.execute("SELECT count FROM requests WHERE day=?", (day,)).fetchone()[0]
                if count >= self.daily_limit:
                    raise GatewayFailure(429, "DAILY_LIMIT")
                # Count attempts, including failures and cancelled clients; provider may receive them.
                self.db.execute("UPDATE requests SET count=count+1 WHERE day=?", (day,))
                self.db.execute("DELETE FROM requests WHERE day < ?", (day,))
            self.last_outbound = now
            try:
                result = safe_payload(self.fetcher(self.api_key, station), self.api_key)
            except GatewayFailure as failure:
                self.cache[station] = (now, failure)
                raise
            except Exception:
                failure = GatewayFailure(502, "UPSTREAM_UNAVAILABLE")
                self.cache[station] = (now, failure)
                raise failure from None
            if len(self.cache) >= 128:
                self.cache.pop(next(iter(self.cache)))
            self.cache[station] = (now, result)
            return result


def handler_for(service: SubwayGateway):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, *_args):
            # Default HTTP logs include user queries; bearer tokens/upstream keys must never be logged.
            pass

        def respond(self, status: int, payload: dict):
            encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(encoded)))
            if status == 429:
                self.send_header("Retry-After", "30")
            self.end_headers()
            try:
                self.wfile.write(encoded)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            parsed = urlsplit(self.path)
            if parsed.path == "/healthz":
                self.respond(200 if service.ready else 503,
                             {"status": "ready" if service.ready else "configuration_required"})
                return
            if not service.authorized(self.headers.get("Authorization", "")):
                self.respond(401, {"error": "UNAUTHORIZED"})
                return
            if parsed.path != "/v1/subway/arrivals" or len(self.path) > 2048:
                self.respond(404, {"error": "NOT_FOUND"})
                return
            query = parse_qs(parsed.query, keep_blank_values=True)
            if set(query) != {"stationName"} or len(query["stationName"]) != 1:
                self.respond(400, {"error": "INVALID_QUERY"})
                return
            try:
                self.respond(200, service.arrivals(query["stationName"][0]))
            except GatewayFailure as failure:
                self.respond(failure.status, {"error": failure.code})
            except Exception:
                self.respond(503, {"error": "GATEWAY_UNAVAILABLE"})

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    database = os.environ.get("SUBWAY_GATEWAY_DB", "/tmp/pacenudge-gateway.sqlite3")
    service = SubwayGateway(os.environ.get("SEOUL_SUBWAY_API_KEY", ""),
                            os.environ.get("SUBWAY_GATEWAY_TOKEN", ""), database=database,
                            daily_limit=int(os.environ.get("SUBWAY_DAILY_LIMIT", "900")))
    server = ThreadingHTTPServer((args.bind, args.port), handler_for(service))
    server.daemon_threads = True
    print("PaceNudge gateway started; configuration=" + ("ready" if service.ready else "required"), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
