# 공식 시간표 데이터

실시간 API의 양수 ETA가 우선이다. 이 파일들은 ETA가 없는 열차의 공식 위치와
동일 열차의 실제 정차 경로를 대조하는 별도 추정에 사용한다. 추정을 실시간 ETA로
표시하거나 전역 개수에 고정 분을 곱하지 않는다.

## 출처와 범위

- 서울교통공사 [서울 도시철도 열차운행시각표](https://www.data.go.kr/data/15098251/fileData.do):
  2026-09-01 기준, 2026-09-14 게시 CSV, 1~9호선. 원본 SHA-256
  `fd7e7b6edc18a5ac4f40f53b594d460d5cbd96d90919aaeb54b957890080d606`.
- 한국철도공사 [2026-08-22 운행 조정 공지](https://www.korail.com/ticket/guest/notice/25642):
  [수인분당선 XLSX](https://www.korail.com/file/cubedata/COMMON/jfile/202608/21/202608211a023449d0b840.xlsx),
  2026-08-18 게시, 2026-08-22 시행. 원본 SHA-256
  `44543f0280afb17acbcdb12aefcd40ec79ff28fc28b6c1e1f80d9743ee3d14d0`.
  원본의 평일·휴일만 수록하며 토요일 시트 적용 근거가 없어 토요일 추정은 보류한다.
- 날짜별 공휴일 출처는 상위 `official_service_calendar.json`에 보관한다. 시간표 개정과
  임시 운행 조정은 별도로 갱신해야 한다. 9호선 평일은 공지된 개정일 전날인
  2026-11-15까지만 이 버전을 사용한다.

29개 노선·요일 압축 파일에 12,698개 열차 운행과 429,036개 정차 행이 포함되어 있다.
서울 원본 중 시각이 모순되는 646개 운행은 제외했다. 구체적인 입력·제외·매핑 수치는
`build_report.json`, 파일 해시와 색인은 `manifest.json`을 따른다. 진접·오남·별내별가람은
앱의 기존 TOPIS 목록에 대응 ID가 없어 원본 네임스페이스로 보존하며 자동 연결하지 않는다.

수인분당선은 779개 운행, 25,529개 정차 행을 포함한다. 원본 통과 시각 168개는 정차로
취급하지 않는다. 역 약칭 12개는 동일 노선 공식 역 목록과 대조한 명시적 별칭을 사용한다.
K/S 접두사 후보는 원본 번호를 유지하고 노선·요일·방향·종착역·정차 경로가 하나로
일치할 때만 연결한다. 번호 중복·순환 경로·통과역·시간표 유효기간 등 모호함은 추정을
만들지 않는다. 전체 수도권의 모든 운행이 포함된다는 의미는 아니다.

## 재생성

원본 파일을 각 공식 링크에서 내려받은 뒤 저장소 루트에서 실행한다. API 키는 필요 없다.

```bash
python3 scripts/normalize_korail_suin.py --xlsx /path/to/suin.xlsx --output /tmp/suin
python3 scripts/build_official_timetables.py \
  --seoul-csv /path/to/seoul.csv --seoul-as-of 2026-09-01 --seoul-published 2026-09-14 \
  --korail-csv /tmp/suin/korail_suin_bundang_20260822_stops.csv
```

원본 시간을 임의로 채우지 않는다. 출발역의 출발 시각과 종착역의 도착 시각만 있는 경우도
그대로 유지한다. 24시 이후 시각은 이전 운행일의 24:xx로 보존한다.
