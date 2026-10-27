# 실시간 이동 안내 갱신 안정화 — 2026-10-27 (KST)

- 기준: `feature/final-ui-polish-20261027` / `64faca1`
- 작업 브랜치: `fix/live-guidance-refresh-stability-20261027`
- 목적: 정상 갱신 중에도 **원래 유효기간 안의 실제 ETA·도보 근거**를 유지한다. 만료/통과/선택 변경은 그대로 제거한다.

## 1. 확인된 원인과 재현 범위

실제 Kotlin/Compose, ViewModel, Repository, Foreground Service를 읽었다. 다음은 **소스에서 확인하고 모의 데이터로 재현한 문제**이며, 사용자의 휴대폰에서 발생한 모든 현상의 원인이 하나로 확정됐다는 뜻은 아니다.

| 관련 함수 | 확인 결과 |
| --- | --- |
| `JourneyMetrics` | 속도 인자가 null이면 `측정 중`을 표시했다. Main의 정확도/신선도 필터에서 제외된 표본도 이 분기로 들어갔다. |
| `parseWorkerSeoulBusArrivals`, `parseWorkerSubwayArrivals`, `chooseMatchingGyeonggiArrival` | 첫 차량 snapshot의 만료를 첫/다음 차량 만료의 최솟값으로 만들었다. 다음 차량만 먼저 만료돼도 정상 첫 차량이 무효화됐다. 서울·경기 버스 재현 테스트 2개와 최종 검토에서 추가한 지하철 재현 테스트 1개가 각각 수정 전 실패했다. |
| `refreshBusLive`, `refreshGyeonggiLive`, 서비스 `monitor` | 응답 목록 전체를 새 목록으로 대체했다. 일부 차량만 들어온 정상 응답에서 다른 차량의 유효한 예측이 사라질 수 있었다. 서비스는 원본 차량별 목록도 보관하지 않았다. |
| `JourneyMonitoringService.updateWalkingLocation` | 경로 진행도 계산이 null이면 도보 경로를 null로 교체했다. 부정확한 GPS 표본 하나로 유효한 경로가 사라지는 분기를 재현했다. 위치 스트림의 일반 오류도 위치와 경로를 함께 비웠다. |
| `refreshWalkingSnapshot` | 전면 화면의 GPS 수신이 도보 API 조회 및 다음 20초 대기와 직렬로 묶여 있었다. API가 느리면 GPS 30초 유효기간을 넘길 수 있는 구조였다. 이 지연의 실제 단말 발생 빈도는 측정하지 않았다. |
| `DashboardNavigation` → `invalidateRequests` | 탭 이동 시 요청 취소와 함께 시간표 분석 결과까지 지웠다. ViewModel 자체가 재구성 때 항상 초기화되는 문제는 아니었다. |
| `startMonitoring`, 서비스 `onStartCommand` | 화면에서 받은 유효한 정보가 서비스 시작으로 직접 전달되지 않아 서비스 첫 응답 전 빈 상태가 표시될 수 있었다. |
| `refreshLive`의 선택 열차 누락 분기 | 저장된 열차 선택이 있으면 새 응답에 열차가 없는데도 이전 `selectedArrival`을 남겼다. 이제 완료된 응답에서 빠진 열차의 실제 관측/분석 자료만 제거하고 사용자 선택 선호는 보관한다. 요청 시작 시 삭제하는 동작은 아니다. |
| 서비스 재조회 대기 분기 | 유효한 첫/다음 차량 정보가 있어도 NEXT_VEHICLE의 다음 차량 추천 시각을 별도로 비웠다. |

`beginLiveRequest()`는 원래 데이터 삭제 없이 요청 번호·로딩 플래그만 바꿨다. `liveLoading=true` 자체가 기존 ETA 카드를 숨기거나 판단을 UNKNOWN으로 만드는 코드도 없었다. 실제 원인은 위의 응답 교체·만료 결합·도보 근거 손실 등이었다.

추가 캐시 회귀 테스트에서, 시계 오차 허용 구간의 동일 경기 캐시 표본이 원래 정규화 만료 이후에도 선택 후보로 남는 문제를 확인해 원래 만료시각을 선택 단계에서도 적용했다. 이 경계 상황은 모의 데이터 검증이며 운영 휴대폰에서 관측한 오류라고 보고하지 않는다.

## 2. 수정 파일

| 파일 | 핵심 변경 |
| --- | --- |
| `PaceNudgeComponents.kt` | 미측정 속도는 `0.0 km/h`. 최초/실패/만료/운행 없음 문구를 구분. 기존 디자인 유지. 상세에 제공기관·갱신 오류·경과시간 반영 안내 표시. |
| `ArrivalRefreshRetention.kt` (추가) | 실제 차량 ID 또는 공식 슬롯으로 일부 갱신을 병합. 다른 차량의 원래 관측/수신/만료시각 유지. 슬롯 교체·통과·명시적 종료·만차·방향 불일치 제외. 빈 정상 응답은 기존 목록 제거. 오래된 응답/같은 캐시가 최신 결과를 덮거나 만료 데이터를 되살리지 않도록 처리. |
| `AutoGuidance.kt` | snapshot에 다음/후속 차량 자체의 만료시각 추가, 각 ETA 조회 시 독립 만료 적용. WALK/RUN/대기 계산 조건·여유시간·속도 기준은 변경하지 않음. |
| `SeoulBusModels.kt`, `SeoulBusRepository.kt` | 원본 공식 슬롯·ETA 보존, 첫 차량의 자체 유효기간 유지, 유효한 현재/다음 차량을 매 시각 재선택. 동일 차량 중복 제외. |
| `SeoulSubwayRepository.kt`, `SubwayGuidanceSelection.kt` | 지하철 숫자 ETA도 첫/다음/후속 차량별 만료를 유지. 다음 열차가 먼저 만료돼도 첫 열차를 제거하지 않으며, 이미 만료된 후속 열차를 추천하지 않음. 위치+시간표 추정 알고리즘은 유지. |
| `GyeonggiArrivalMatching.kt`, `GyeonggiBusRepository.kt` | 첫/다음 차량 독립 만료, 원래 정규화 만료 확인, 공식 운행 종료 사실 보존. 선택기관/정류소/노선/순서 검증 유지. |
| `JourneyScreenState.kt` | 갱신 플래그와 차량 데이터 분리 유지. 응답 병합 함수 연결, 현재 시각의 유효 차량 선택, 실제 도보 경로 추적기 보관. 요청 세대 검사와 선택 변경 시 삭제 유지. |
| `MainActivity.kt` | 기존 목록 유지하며 갱신, 전면 화면 GPS 스트림을 느린 도보 요청과 분리, 공통 경로 유지 함수 사용, 오래된 위치의 도보 응답 적용 방지. 같은 선택의 유효 데이터를 서비스에 전달. |
| `WalkingRouteProgress.kt` | 전면/백그라운드 공용 `updateLiveWalkingEvidence`. 불확실한 GPS에서 이전 유효 근거 유지, 실제 경로 이탈·만료는 제외. 기존 GPS/경로 투영 알고리즘 및 기준값 유지. |
| `JourneyMonitoringController.kt`, `JourneyMonitoringService.kt` | 차량별 원본 목록 유지 및 GPS/건강 점검 시 현재 차량 재선택. 재조회 대기에도 유효한 다음 차량 유지. 화면→서비스 전달은 프로세스 메모리에서만 수행하고 원래 시각으로 재검증. 일반 위치 오류와 권한/서비스 비활성화를 구분. 오류 상세 별도 보관. |
| `DashboardScreen.kt` | 탭 이동은 이전 요청을 무효화하되 유효한 시간표 분석 자료를 보존. 선택 변경의 삭제 동작은 유지. |
| `GuidanceDisplayClock.kt` (추가) | UTC 기준시각 + Android `elapsedRealtime`으로 표시 시각 진행. 화면/서비스 카운트다운은 기기 수면을 포함한 단조 경과시간을 사용. 틱마다 ETA를 새로 생성하거나 기준시각을 갱신하지 않음. |
| 테스트 3개 추가 | `ArrivalRefreshStabilityTest`, `WalkingEvidenceStabilityTest`, `GuidanceRefreshStabilityUiTest`. |
| 기존 UI 테스트 2개 | `GuidanceRefreshUiTest`, `FinalUiPresentationTest`의 실제 무효 데이터 문구 기대값 변경. |

## 3. 화면 갱신 비교

다음은 실제 수정한 Compose 컴포넌트에 **모의 공식 응답**을 넣어 검증한 결과다. 사용자 실제 GPS/기기 캡처가 아니다. 승차 준비 여유는 1분이며, 달리기 속도를 입력한 조건에서 기존 계산기를 사용했다.

| 단계 | 안내 | 첫 버스 | 다음 버스 | 도보 |
| --- | --- | --- | --- | --- |
| 갱신 전 | 걸으세요 | 5분 | 13분 | 약 3분 |
| 갱신 중, 15초 경과 | 걸으세요 | 4분 45초 | 12분 45초 | 약 3분 |
| 첫 버스의 새 ETA 수신 후 | 빠르게 이동하세요 | 3분 5초 | 12분 45초 | 약 3분 |

다음 차량을 다시 수신한 것으로 처리하지 않고 원래 수신시각을 유지했다. 임시 실패 시에도 유효기간 안에서는 같은 안내를 유지하고, 만료 뒤에는 실패/최신 정보 필요를 표시했다.

속도 표시: 미측정 `0.0 km/h` → 유효한 1.4m/s 표본 `5.0 km/h` → 측정값 없음 `0.0 km/h`. 화면의 0.0은 실제 정지 판정이나 도보 계산 입력으로 사용하지 않는다. 보정값이 없으면 기존 사용자 설정 속도/설정된 도보 정책을 사용한다. 예: 3.24km/h 설정으로 180m는 200초.

- [세 단계 비교 이미지](proof/refresh-stability-20261027/refresh-comparison.png)
- [갱신 전](proof/refresh-stability-20261027/refresh-before.png)
- [갱신 중](proof/refresh-stability-20261027/refresh-during.png)
- [새 ETA 반영 후](proof/refresh-stability-20261027/refresh-after.png)

## 4. 데이터 유지의 제한

- 기존 기준 유지: GPS 30초, 도보 경로 120초, 공식 ETA 원본 최대 600초/수신 최대 60초. 실제 snapshot의 더 짧은 만료도 그대로 적용한다.
- 첫/다음 차량은 각각의 원본 시각·수신 시각·정밀도·만료시각을 유지한다. 한 차량의 갱신으로 다른 차량의 수명이 늘지 않는다.
- 차량 도착 절대시각을 유지하며 표시 경과시간만 반영한다. 같은 Worker 캐시를 새 관측으로 바꾸지 않는다. 분 단위 응답과 시간표 추정은 초 단위 실시간 ETA처럼 표시하지 않는다.
- 첫 차량 통과/만료 시 유효한 다음 차량만 승격한다. 정류소·노선·방향 변경 시 이전 선택의 ETA는 즉시 제외한다.
- 도보 진행도는 실제 경로 좌표와 GPS 변화에서만 계산한다. 정지 중 시간 경과만으로 남은 거리를 줄이지 않는다. 부정확한 표본에서 기존 위치를 유지하더라도 그 표본의 원래 30초 수명은 늘리지 않는다.
- 권한 거부/위치 서비스 비활성화, 실제 경로 이탈 및 만료를 숨기지 않는다.
- 최초 `도착정보 확인 중`, 실패 `도착정보를 불러오지 못했어요`, 만료 `최신 도착정보 확인 필요`, 최근 성공한 빈 응답/명시적 종료 `운행정보 없음`을 구분한다.

## 5. 테스트·빌드 결과

최종 Android 명령:

```bash
source /workspace/.cloud-setup/activate.sh
bash gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug \
  -PproofDir=/workspace/.cloud-setup/refresh-stability-proof \
  --init-script /workspace/.cloud-setup/timetable-verification.gradle \
  --no-daemon --no-configuration-cache --max-workers=4 --console=plain
```

- **208개 통과, 실패/오류/건너뜀 0**, Kotlin 컴파일 및 APK 빌드 성공 (`BUILD SUCCESSFUL`, 1분 11초).
- 저장소 테스트 **101개** + 클라우드 기존 회귀/운영 응답 재생 테스트 **107개**. init script를 제외하면 저장소 테스트를 실행할 수 있다. 클라우드 추가 테스트는 앱/APK 코드가 아니다.
- 새 테스트: 독립 만료, 일부 차량만 갱신, 통과/종료/만차/잘못된 방향, 동일 ID 중복, ID 없는 공식 슬롯, 오래된 응답/같은 캐시, 요청 세대 역전, 선택 변경, 화면→서비스 전달, 재조회 대기, 탭 이동, GPS 튐/정지/누락/정확도 부족/경로 이탈/만료, 단조 시계, UI 0.0 기본값, 실제 Compose의 갱신 전/중/후 및 오류 상세.
- 기존 서울·경기 버스, 1호선·수인분당선 위치+공식 시간표, 다음 차량 판단, 지도 좌표/카메라 계산, 개인 보행 분석, 진동 전환 및 자원 해제 회귀 테스트 포함.
- Worker: `npm run check` 성공, `npm run build`의 **dry-run만** 성공. 현재 소스를 컴파일한 로컬 정규화/캐시 payload/예산 상수 테스트 **6개 통과, 실패 0**. 세 제공기관의 기존 900회/24시간·30초 캐시 설정, M5107 4/8분 정규화, 캐시 수신시각 보존 및 만료, 선택 메타데이터 누락, 지하철 60초 이상 원본/통과/비정상 ETA, 명시적 운행 종료 확인. Durable Object 예산 저장소의 실제 동시성·소진 실험은 수행하지 않았다.
- Worker 소스·예산·캐시·API 키 설정 변경 없음. 실제 서버 배포 없음.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (58,647,245 bytes).
- SHA-256: `2268d386757a248045f498be46a9eb2b1047e0aa454933c642c397fb37b5f875`.
- [기계 판독 집계](proof/refresh-stability-20261027/validation.json).

## 6. 실제 운영 응답과 모의 테스트 구분

2026-10-27 **19:50 KST**, 앱과 같은 User-Agent를 사용해 운영 Worker에 GET 요청 2개를 보냈다. 둘 다 **HTTP 200**이었다. 추가 키를 변경/등록하거나 Worker를 배포하지 않았다.

1. 서울 버스 `/bus/arrivals?stopId=105900050&routeId=105900003&stopOrder=4`: 실제 응답 2건. 원본 ETA 361초인 차량을 앱 파서가 사용했고, 원본 생성시각부터 경과한 시간을 반영하면 **수신 당시 약 315초**였다. ETA 0초인 다른 차량은 다음 차량으로 생성하지 않았다. [재생 검증 결과](proof/refresh-stability-20261027/bus-app-proof.json).
2. 망포 `/subway?station=망포`: 실제 위치 응답 7건, 공식 숫자 ETA는 모두 0초. 원본을 수신 당시 시각으로 앱의 실제 파서·내장 공식 시간표 계산기에 넣어 **위치+공식 시간표 기반 약 8분 추정**이 연결되는 것을 확인했다. 3분 도보·1분 여유를 넣은 검증 조건에서는 WALK를 산출했다. **공식 API가 8분 숫자 ETA를 제공했다거나 실제 GPS/승차/도착을 관측했다는 뜻이 아니다.** [재생 검증 결과](proof/refresh-stability-20261027/subway-app-proof.json).

운영 원본은 당시 수신시각으로 재생했다. 현재 시각에도 유효한 값인 것처럼 다시 사용하는 검증이 아니다. M5107은 이번에 새 운영 요청을 하지 않았고 4/8분 제공 응답 구조의 모의 정규화/선택 및 기존 회귀 데이터로 검증했다. 위 UI 이미지는 별도의 모의 공식 응답으로 렌더했다.

## 7. 실기기에서 남은 확인

- 실제 Fused GPS 속도/정확도 수신, 실외 보행·정지·실내 전환·GPS 끊김 및 권한/위치 서비스 회수.
- 실제 네트워크 지연/일시 중단/복귀 중 첫·다음 차량과 도보시간의 표시 유지 및 만료 후 안내.
- 탭 이동·화면 회전·백그라운드 복귀, 이동 안내 시작/종료 및 화면 꺼짐 상태의 Foreground Service/GPS 콜백 해제.
- 기존 1/2/3회 진동·상태 안정화·상향 긴급도 우선 정책의 실제 하드웨어 동작.
- 실제 Kakao Map 인증/타일·마커·현재 위치·파란/초록 경로와 카메라 조작. 네이티브 지도 렌더를 단위 테스트로 검증했다고 보고하지 않는다.
- 실제 차량 도착과 시간표 추정의 오차, 운영 Worker 예산 저장소의 동시 요청/소진 동작.

`main` 병합과 운영 Worker 배포는 수행하지 않았다. 테스트와 결과 보고 후 별도 작업 브랜치만 push한다.
