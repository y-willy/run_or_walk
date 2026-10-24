"""Offline gateway regression tests. Provider records are fixtures, never live user data."""
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, ProxyHandler
from http.server import ThreadingHTTPServer

from server import GatewayFailure, SubwayGateway, handler_for, safe_payload

TOKEN = "offline-test-token-not-a-credential-000000"
KEY = "offline-provider-key-not-a-credential"
ROOT = Path(__file__).resolve().parents[1]


class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.payload = json.loads((ROOT / "app/src/test/resources/seoul_public_sample_arrivals_20261024.json").read_text())
        self.calls = []
        self.now = 100.0
        self.clock = 1_792_864_800.0

    def service(self, **kwargs):
        def fetch(key, station):
            self.calls.append((key, station))
            return self.payload
        return SubwayGateway(KEY, TOKEN, fetcher=fetch, monotonic=lambda: self.now,
                             wall_clock=lambda: self.clock, **kwargs)

    def test_missing_config_never_calls_provider(self):
        service = self.service()
        service.api_key = ""
        self.assertFalse(service.ready)
        with self.assertRaises(GatewayFailure) as caught:
            service.arrivals("서울")
        self.assertEqual(503, caught.exception.status)
        self.assertEqual([], self.calls)

    def test_only_official_station_names_are_allowed(self):
        with self.assertRaises(GatewayFailure) as caught:
            self.service().arrivals("http://example.com/arbitrary")
        self.assertEqual(400, caught.exception.status)
        self.assertEqual([], self.calls)

    def test_polling_cache_preserves_source_clock_and_coalesces_calls(self):
        service = self.service()
        first = service.arrivals("서울")
        self.now += 29
        self.assertIs(first, service.arrivals("서울"))
        self.assertEqual(1, len(self.calls))
        self.assertEqual(self.payload["realtimeArrivalList"][0]["recptnDt"],
                         first["realtimeArrivalList"][0]["recptnDt"])
        self.now += 1
        service.arrivals("서울")
        self.assertEqual(2, len(self.calls))

    def test_daily_budget_persists_across_restart_and_counts_attempts(self):
        with tempfile.TemporaryDirectory() as tmp:
            database = str(Path(tmp) / "budget.sqlite3")
            service = self.service(database=database, daily_limit=1)
            service.arrivals("서울")
            restarted = self.service(database=database, daily_limit=1)
            self.now += 31
            with self.assertRaises(GatewayFailure) as caught:
                restarted.arrivals("서울")
            self.assertEqual("DAILY_LIMIT", caught.exception.code)
            self.assertEqual(1, len(self.calls))

    def test_different_stations_cannot_bypass_global_request_interval(self):
        service = self.service()
        service.arrivals("서울")
        with self.assertRaises(GatewayFailure) as caught:
            service.arrivals("강남")
        self.assertEqual(429, caught.exception.status)
        self.assertEqual(1, len(self.calls))

    def test_failure_is_sanitized_and_throttled(self):
        service = self.service()
        def fail(key, _station):
            self.calls.append("attempt")
            raise RuntimeError("http://provider/" + key)
        service.fetcher = fail
        for _ in range(2):
            with self.assertRaises(GatewayFailure) as caught:
                service.arrivals("서울")
            self.assertNotIn(KEY, str(caught.exception))
        self.assertEqual(1, len(self.calls))

    def test_provider_messages_and_key_echo_never_escape(self):
        self.payload["errorMessage"]["developerMessage"] = KEY
        self.assertNotIn(KEY, json.dumps(safe_payload(self.payload, KEY)))
        self.payload["realtimeArrivalList"][0]["arvlMsg2"] = KEY
        with self.assertRaises(GatewayFailure):
            safe_payload(self.payload, KEY)

    def test_invalid_key_and_malformed_payload_are_not_successes(self):
        for payload in ({"errorMessage": {"code": "INFO-100"}},
                        {"errorMessage": {"status": 200, "code": "INFO-000"},
                         "realtimeArrivalList": "invalid"}, {}):
            with self.assertRaises(GatewayFailure):
                safe_payload(payload, KEY)

    def test_http_handler_authorization_validation_and_functional_response(self):
        service = self.service()
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler_for(service))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        opener = build_opener(ProxyHandler({}))  # Loopback-only fixture server, not an outbound request.
        base = "http://127.0.0.1:" + str(server.server_port)
        try:
            with opener.open(base + "/healthz", timeout=3) as response:
                self.assertEqual({"status": "ready"}, json.load(response))
            url = base + "/v1/subway/arrivals?" + urlencode({"stationName": "서울"})
            with self.assertRaises(HTTPError) as denied:
                opener.open(url, timeout=3)
            self.assertEqual(401, denied.exception.code)
            self.assertEqual([], self.calls)
            request = Request(url, headers={"Authorization": "Bearer " + TOKEN})
            with opener.open(request, timeout=3) as response:
                body = json.load(response)
                self.assertEqual(5, len(body["realtimeArrivalList"]))
                self.assertNotIn(KEY, json.dumps(body))
            bad = Request(base + "/v1/subway/arrivals?" + urlencode({"stationName": "서울", "extra": "1"}),
                          headers={"Authorization": "Bearer " + TOKEN})
            with self.assertRaises(HTTPError) as invalid:
                opener.open(bad, timeout=3)
            self.assertEqual(400, invalid.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
