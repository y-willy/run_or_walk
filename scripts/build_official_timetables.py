#!/usr/bin/env python3
"""Build deterministic, offline line/day/train/station indexes from official publications.

Only the Python standard library is needed. This does not fetch a timetable, guess a
holiday, remove train prefixes, fill missing endpoint times, or manufacture stations.
"""
from __future__ import annotations

import argparse
import collections
import csv
import datetime as dt
import gzip
import hashlib
import json
from pathlib import Path
import re

FIELDS = {
    "고유번호": "ROWNUM", "호선": "LINE", "역사코드": "SI_ID",
    "역사명": "STATION_NM", "주중주말": "WEEKTAG", "방향": "INOUTTAG",
    "급행여부": "GUBHANG", "열차코드": "TRAIN_NO",
    "열차도착시간": "STT", "열차출발시간": "EDT", "출발역": "ST_STT_NM",
    "도착역": "ED_STT_NM",
}
DAY_TYPES = {"DAY": "WEEKDAY", "SAT": "SATURDAY", "END": "HOLIDAY", "HOLIDAY": "HOLIDAY"}
DIRECTIONS = {"UP": "상행", "DOWN": "하행", "IN": "내선", "OUT": "외선"}
# Exact, line-scoped name changes/alternative official display names. Never match by digits.
ALIASES = {
    ("1001", "서울역"): "서울", ("1004", "서울역"): "서울",
    ("1001", "평택지제"): "지제", ("1007", "자양"): "뚝섬유원지",
    ("1007", "이수"): "총신대입구(이수)",
    ("1006", "응암"): "응암순환(상선)",
    ("1075", "로데오"): "압구정로데오", ("1075", "강남구"): "강남구청",
    ("1075", "구룡역"): "구룡", ("1075", "대모산"): "대모산입구",
    ("1075", "매탄권"): "매탄권선", ("1075", "수원시"): "수원시청",
    ("1075", "신수원"): "수원", ("1075", "신길온"): "신길온천",
    ("1075", "소래포"): "소래포구", ("1075", "인천논"): "인천논현",
    ("1075", "남동인"): "남동인더스파크", ("1075", "신인천"): "인천",
}


def compact_name(value: str) -> str:
    return re.sub(r"\s+", "", value)


def base_name(value: str) -> str:
    return re.sub(r"\([^()]*\)", "", compact_name(value))


def seconds(value: str) -> int | None:
    if not value:
        return None
    if not re.fullmatch(r"[0-4][0-9]:[0-5][0-9]:[0-5][0-9]", value):
        raise ValueError("invalid service-day time")
    hour, minute, second = map(int, value.split(":"))
    if hour > 47:
        raise ValueError("invalid service-day hour")
    return hour * 3600 + minute * 60 + second


def read_csv(path: Path):
    for encoding in ("utf-8-sig", "cp949"):
        try:
            with path.open(encoding=encoding, newline="") as stream:
                # Decode all the way before yielding, so a fallback never duplicates rows.
                rows = list(csv.DictReader(stream))
            return [{FIELDS.get(k, k): (v or "").strip() for k, v in row.items()}
                    for row in rows], encoding
        except UnicodeError:
            pass
    raise ValueError(f"Unsupported encoding: {path}")


def source_metadata(authority: str, dataset: str, version: str, published: str,
                    as_of: str, url: str, download_url: str, valid_from=None):
    return {
        "authority": authority, "dataset": dataset, "version": version,
        "publishedAtEpochMillis": int(dt.datetime.fromisoformat(published)
                                      .replace(tzinfo=dt.timezone(dt.timedelta(hours=9))).timestamp() * 1000),
        "scheduleAsOfDate": as_of, "validFromServiceDate": valid_from,
        "validThroughServiceDate": None, "timeZone": "Asia/Seoul",
        "url": url, "downloadUrl": download_url,
    }


def station_mapper(catalog: list[dict]):
    exact = collections.defaultdict(list)
    base = collections.defaultdict(list)
    for item in catalog:
        exact[(item["routeId"], compact_name(item["name"]))].append(item)
        base[(item["routeId"], base_name(item["name"]))].append(item)

    def lookup(route: str, name: str):
        alias = ALIASES.get((route, name), name)
        candidates = exact.get((route, compact_name(alias)), [])
        if len(candidates) == 1:
            return candidates[0], "explicit_alias" if alias != name else "exact"
        candidates = base.get((route, base_name(alias)), [])
        if len(candidates) == 1:
            return candidates[0], "unique_parenthetical_name"
        return None, "unmatched" if not candidates else "ambiguous"
    return lookup


def build_publication(rows, source, catalog, namespace: str):
    lookup = station_mapper(catalog)
    stats = collections.Counter()
    grouped = collections.defaultdict(list)
    invalid_groups = set()
    station_info = {}
    unmatched = collections.Counter()
    matches = collections.Counter()
    for row in rows:
        stats["inputRows"] += 1
        day = DAY_TYPES.get(row.get("WEEKTAG"))
        if day is None:
            stats["excludedUnknownDayRows"] += 1
            continue
        line = row.get("LINE", "")
        route = "1075" if line in ("수인분당", "1075") else (
            str(1000 + int(line)) if line in set("123456789") else None)
        if route is None or row.get("INOUTTAG") not in DIRECTIONS:
            stats["excludedUnknownLineOrDirectionRows"] += 1
            continue
        train = row.get("TRAIN_NO", "")
        name = row.get("STATION_NM", "")
        origin = row.get("ST_STT_NM", "")
        destination = row.get("ED_STT_NM", "")
        operation = row.get("GUBHANG", "")
        key = (route, day, train, DIRECTIONS[row["INOUTTAG"]], operation, origin, destination)
        if not all((train, name, origin, destination)):
            stats["excludedMissingIdentityRows"] += 1
            invalid_groups.add(key)
            continue
        try:
            arrival, departure = seconds(row.get("STT", "")), seconds(row.get("EDT", ""))
            if arrival is None and departure is None:
                raise ValueError("no stop time")
            if arrival is not None and departure is not None and arrival > departure:
                raise ValueError("departure before arrival")
        except ValueError:
            stats["excludedInvalidTimeRows"] += 1
            invalid_groups.add(key)
            continue
        mapped, method = lookup(route, name)
        source_id = row.get("SI_ID") or name
        stop_id = mapped["stopId"] if mapped else f"{namespace}:{route}:{source_id}"
        # Canonical TOPIS names accompany mapped IDs. Preserve the exact publisher name too.
        info = [stop_id, mapped["name"] if mapped else name, source_id, name, method]
        station_info[(route, source_id)] = info
        matches[method] += 1
        if mapped is None:
            unmatched[(route, source_id, name)] += 1
        grouped[key].append((info, arrival, departure))
    tables = collections.defaultdict(list)
    for key, stops in sorted(grouped.items()):
        if key in invalid_groups:
            stats["excludedInconsistentTrips"] += 1
            stats["excludedInconsistentTripRows"] += len(stops)
            continue
        # Time order is explicit source evidence; no route topology or station digits are guessed.
        stops.sort(key=lambda stop: (stop[1] if stop[1] is not None else stop[2],
                                     stop[2] if stop[2] is not None else stop[1], stop[0][0]))
        unique = []
        previous_time = None
        invalid = False
        for stop in stops:
            if unique and stop == unique[-1]:
                stats["excludedDuplicateRows"] += 1
                continue
            start = stop[1] if stop[1] is not None else stop[2]
            if previous_time is not None and start < previous_time:
                invalid = True
            previous_time = stop[2] if stop[2] is not None else stop[1]
            unique.append(stop)
        if invalid or len(unique) < 2:
            stats["excludedInconsistentTrips"] += 1
            stats["excludedInconsistentTripRows"] += len(unique)
            continue
        route, day, train, direction, operation, origin, destination = key
        # A stable identifier for this publication's explicit endpoints and actual call pattern.
        pattern_input = json.dumps([route, direction, operation, origin, destination,
                                    [s[0][2] for s in unique]], ensure_ascii=False, separators=(",", ":"))
        pattern = f"{namespace}:{hashlib.sha256(pattern_input.encode()).hexdigest()[:20]}"
        op_type = {"0": "LOCAL", "1": "EXPRESS"}.get(operation, "OTHER")
        variant = f"{namespace}:gubhang:{operation or 'unspecified'}"
        tables[(route, day)].append((train, direction, variant, op_type, pattern,
                                    origin, destination, unique))
        stats["includedTrips"] += 1
        stats["includedStopRows"] += len(unique)
    output = []
    for (route, day), trips in sorted(tables.items()):
        stations = sorted({tuple(s[0]) for trip in trips for s in trip[-1]})
        station_index = {s: i for i, s in enumerate(stations)}
        train_index, stop_index = collections.defaultdict(list), collections.defaultdict(list)
        compact_trips = []
        for i, trip in enumerate(trips):
            train_index[trip[0]].append(i)
            compact_stops = [[station_index[tuple(s[0])], s[1], s[2]] for s in trip[-1]]
            for stop_id in sorted({s[0][0] for s in trip[-1]}):
                stop_index[stop_id].append(i)
            compact_trips.append([*trip[:-1], compact_stops])
        payload = {"schemaVersion": 1, "routeId": route, "dayType": day, "source": source,
                   "stations": stations, "trips": compact_trips,
                   "trainIndex": train_index, "stationIndex": stop_index}
        output.append((route, day, payload))
    report = {"counts": dict(sorted(stats.items())), "stationMatches": dict(sorted(matches.items())),
              "unmatchedStations": [{"routeId": key[0], "sourceStopId": key[1], "name": key[2],
                                     "rows": count} for key, count in sorted(unmatched.items())]}
    return output, report


def write_json(path: Path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seoul-csv", required=True, type=Path)
    parser.add_argument("--seoul-as-of", required=True)
    parser.add_argument("--seoul-published", required=True)
    parser.add_argument("--seoul-download-url", default="https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000007671122&fileDetailSn=1&insertDataPrcus=N")
    parser.add_argument("--korail-csv", type=Path)
    parser.add_argument("--korail-as-of", default="2026-08-22")
    parser.add_argument("--korail-published", default="2026-08-18")
    parser.add_argument("--catalog", type=Path, default=Path("app/src/main/assets/seoul_subway_stations.json"))
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/timetables"))
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    seoul_source = source_metadata("서울교통공사", "서울 도시철도 열차운행시각표",
        f"seoulmetro-{args.seoul_as_of}", args.seoul_published, args.seoul_as_of,
        "https://www.data.go.kr/data/15098251/fileData.do", args.seoul_download_url)
    inputs = [(args.seoul_csv, seoul_source, "seoulmetro")]
    if args.korail_csv:
        korail_source = source_metadata("한국철도공사", "수인분당선 광역전철 시간표",
            f"korail-suinbundang-{args.korail_as_of}", args.korail_published, args.korail_as_of,
            "https://www.korail.com/ticket/guest/notice/25642",
            "https://www.korail.com/file/cubedata/COMMON/jfile/202608/21/202608211a023449d0b840.xlsx",
            args.korail_as_of)
        inputs.append((args.korail_csv, korail_source, "korail-suinbundang"))
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {"schemaVersion": 1, "identityAuthority": "seoul", "groups": [], "publications": []}
    reports = []
    expected = set()
    for path, source, namespace in inputs:
        rows, encoding = read_csv(path)
        tables, report = build_publication(rows, source, catalog, namespace)
        report.update({"source": source, "inputEncoding": encoding,
                       "normalizedInputSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                       "normalizedInputBytes": path.stat().st_size})
        manifest["publications"].append(source)
        reports.append(report)
        for route, day, payload in tables:
            if route == "1009" and day == "WEEKDAY":
                # An official weekday revision starts on 2026-11-16; this snapshot
                # must not silently stand in for the revised publication.
                payload["source"] = {**payload["source"], "validThroughServiceDate": "2026-11-15"}
            filename = f"{route}_{day}.json.gz"
            expected.add(filename)
            raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
            compressed = gzip.compress(raw, compresslevel=9, mtime=0)
            (args.output / filename).write_bytes(compressed)
            manifest["groups"].append({"routeId": route, "dayType": day, "asset": filename,
                "sha256": hashlib.sha256(compressed).hexdigest(), "compressedBytes": len(compressed),
                "uncompressedBytes": len(raw), "tripCount": len(payload["trips"]),
                "stopCount": sum(len(t[-1]) for t in payload["trips"])})
    # Remove only this builder's old line/day outputs; never remove source documentation.
    for path in args.output.glob("*.json.gz"):
        if path.name not in expected:
            path.unlink()
    write_json(args.output / "manifest.json", manifest)
    write_json(args.output / "build_report.json", {"schemaVersion": 1, "publications": reports})
    print(json.dumps({"groups": len(manifest["groups"]),
        "compressedBytes": sum(g["compressedBytes"] for g in manifest["groups"]),
        "trips": sum(g["tripCount"] for g in manifest["groups"]),
        "stopRows": sum(g["stopCount"] for g in manifest["groups"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
