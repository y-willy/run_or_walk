# 이동 안내 UNKNOWN 안정화 검증 보고서 — 2026-10-29

## 기준 및 범위

- 기준: `65966828ed31a3e4b58f6212beba1db1461b1822`.
- 작업 브랜치: `fix/guidance-unknown-flicker-20261029`.
- 시작 시 로컬 APK의 `BuildConfig.SOURCE_REVISION`은 `65966828ed31`이었다. 휴대폰의 설치 APK와 실제 GPS는 직접 확인하지 못했다.
- 원격 main의 `f371d7a`도 비교했다. 기존 main 병합에는 기준 커밋의 후속 수정이 모두 포함되어 있지 않아, 요청한 6596682의 기능을 유지한 채 작업했다.
- main 병합, 운영 Worker 배포, API 키 변경은 하지 않는다.

## 1. 수정 전에 재현한 원인

| 재현 테스트/실제 응답 | 원인 | 수정 |
|---|---|---|
| `gpsUncertaintyOverlappingTheRouteDoesNotEraseValidWalk` | 이동 후 GPS가 경로에서 30m 옆으로 흔들릴 때 정확도 15m를 고려하지 않고 이탈로 단정하여 거리·시간을 삭제 | 오차 범위가 기존 20m 경로 허용 구간과 겹치면 진행도로 쓰지 않고 원래 유효한 관측을 유지 |
| `rejectedGpsSpikeCannotPoisonTheFollowingGoodFix` | 거부된 GPS도 `lastFix`로 저장하여 다음 정상 좌표를 다시 점프로 거부 | 채택된 관측만 다음 변위 비교의 기준으로 저장 |
| `samePlatformDifferentTrainDestinationBindsPhysicalWalkWithoutWaitingForAnotherGps` | 같은 공식 역·노선·방향에서 열차 행선지 식별자가 바뀌면 물리적 도보 연결까지 null | 동일 공식 승차 위치/방향의 지하철에 한해 도보를 즉시 재연결. 차량 ETA·행선지 선택 검증은 유지 |
| `staleTrackerFromAnotherBoardingCannotPopulateCurrentWalkingEvidence` | 추적기 끝점이 승차점에서 150m 이내이면 이전 승차점의 경로를 현재 경로로 만들 수 있었음 | 실제 요청 원점·응답 경로·현재 승차점의 `validWalkingAccess` 검증을 함께 적용 |
| `authoritativeDistanceAndTimeCannotBeDisplayedWhileTheCalculatorGetsANullDistance` | 서비스의 거리 필드와 판단용 `walkingRoute.distanceMeters`가 달라 null 거리로 계산될 수 있음 | 동일 상태의 거리·시간을 화면, 설정 재계산, 서비스 계산 입력에 함께 복사 |
| 실제 루트 Compose 테스트 `monitoringCannotMixItsWalkingRouteWithAnotherForegroundLocation` | 서비스의 도보 경로에 더 최신인 다른 화면 GPS를 섞어 `WALKING_MISMATCH`를 만들고 거리만 사라지게 함 | 추적 중 안내는 서비스의 GPS·도보 묶음을 사용. 지도는 최신 공유 GPS를 계속 사용 |
| 실제 종로02 API 및 `routeServiceAuthenticationFailureCanUseOfficialDownstreamStopOrders` | ETA는 정상이지만 전체 경유정보 조회만 `BUS_UPSTREAM_KEY_REJECTED`; 자동 방향 확정이 막힘 | 별도로 정상 작동하는 공식 정류소별 노선 조회의 stopOrder로 실제 첫 하차점까지 순서를 확인 |

처음 다섯 도메인 테스트는 수정 전 5/5 실패했다. 실제 루트 Compose 재현과 방향 조회 인증 재현도 각각 실패한 뒤 수정했다. 일반적인 재조회 시작 자체가 값을 항상 지운다고 단정하지 않았다. 이미 존재하는 요청 세대 검사, 유효 ETA 유지, 첫/다음 차량 독립 만료 처리는 유지했다.

## 2. 변경 파일 및 함수

- `WalkingRouteProgress.kt`: `WalkingRouteTracker.update`, `updateLiveWalkingEvidence`. GPS 오차/점프, 현재 승차점과 추적기 연결 검증.
- `WalkingAccessPolicy.kt`: `guidanceWalkingAt`, `sameBoardingAccessAs`, `displayMetrics`. 공식 플랫폼 범위 내 재연결 및 거리·시간 공동 표시 검증.
- `JourneyMonitoringController.kt`: `monitoringStateWithSettings`. 계산 입력에 같은 상태의 거리·시간 반영.
- `JourneyMonitoringService.kt`: ETA 갱신 후 계산, `publishMovement`. 거리 필드 일치 및 상태 전환 진단.
- `MainActivity.kt`: 추적 중 GPS 선택, 도보 입력/표시, 서울 버스 방향 확인. 서로 다른 관측의 혼합 제거. 검증되지 않은 이전 판단용 경로로 되돌아가는 fallback 제거.
- `SeoulBusDirectionVerification.kt`: 전체 경유정보 인증 실패에 대한 공식 stopOrder 검증. 정상 전체 경유 순서가 잘못된 경우에는 대체 조회로 결과를 뒤집지 않는다.
- `GuidanceStateTrace.kt`: 디버그 빌드의 상태 변화 진단.
- `PaceNudgeComponents.kt`: 요청한 크림/민트 내부 색상. 버스·지하철 도착시간 `#FFF0D8/#704311`, 도보 `#DDF8ED/#075F46`.
- 신규 회귀 테스트: `GuidanceUnknownReproductionTest`, `GuidanceUnknownUiTest`, `GuidanceContinuitySimulationTest`, `GuidanceTraceTest`, `SeoulBusDirectionRecoveryTest`.

## 3. 데이터 유효성 및 기존 정책

- 도보 거리·시간의 재계산은 기존 30초 주기를 유지한다. 신규 유효 응답은 다음 틱을 기다리지 않고 반영한다.
- 위치 유효기간 30초, 도보 응답 유효기간 최대 120초와 원래 수신시각을 유지한다. 재표시·GPS 흔들림 때문에 수신시각을 갱신하지 않는다.
- 거부된 GPS와 오차 범위가 겹치는 좌표는 실제 진행도나 새 거리로 사용하지 않는다. 이전 위치가 만료되면 안내도 보류한다.
- 확인된 실제 이탈, 다른 승차점·방향, 차량 통과, 실제 만료는 이전 정보 유지의 예외다. UNKNOWN을 강제로 WALK/RUN으로 바꾸지 않았다.
- API 보정 시작점 약 53.9m 차이는 기존 요청 좌표/응답 경로 연결 검증을 유지한다. 전역 허용 거리를 무조건 확대하지 않았다.
- 공식 승차점/노선/방향이 다른 경로를 혼합하지 않는다. 같은 지하철 플랫폼의 다른 행선지 재연결은 물리적 접근 경로에만 적용하며 차량 선택 조건을 바꾸지 않는다.
- 도보 계산은 설정 속도와 기존 GPS 보정(설정 70%/검증된 측정 30%, ±20%)을 유지한다. 현재 속도 0은 판단 차단 조건으로 쓰지 않는다.
- 여유시간 임계값(4분/2분/0초/-90초), 안전 여유 설정, 진동 패턴과 30초 실제 진동 제한은 변경하지 않았다.
- 첫/다음 차량의 원래 만료시각, 절대 도착시각의 카운트다운, 공식/시간표 추정 출처를 유지한다.
- 대체 방향 확인도 동일 Repository의 캐시·동시 요청 제한·백오프를 사용한다. `REQUEST_LIMIT`/Retry-After는 대체 요청을 시작하지 않고 그대로 전달한다. 다른 기관이나 임의 노선으로 바꾸지 않는다.

## 4. 10분 가상 시계 결과

601개 1초 틱, 30초 계산, 7초 응답 지연, 매 네 번째 요청 실패, 일부 다음 차량 응답 생략, 정지/보행/GPS 오차, 첫 차량 통과 후 다음 차량 승격을 같은 생산 코드로 계산했다.

| 항목 | 이전 추적기 동작 | 수정 후 |
|---|---:|---:|
| 불필요한 UNKNOWN 상태 진입 | 1 | 0 |
| 불필요한 UNKNOWN 표시 틱 | 8 (84~91초) | 0 |
| 거리·도보시간 불일치 틱 | 8 | 0 |
| 첫 차량 통과 후 다음 차량 승격 | 1 | 1 |

이는 8번의 독립적인 상태 진입이 아니라 한 번의 잘못된 UNKNOWN이 8개 틱 동안 유지된 결과다. 이전 비교는 `WalkingRouteProgress.kt`만 6596682 버전으로 되돌린 통제 실행이며, 실행 후 현재 파일을 복구했다. 만료 후에는 `WALKING_STALE`이 되는 별도 단언도 유지한다.

- [이전 시뮬레이션 수치](validation/guidance-unknown-20261029/simulation-before.json)
- [수정 후 시뮬레이션 수치](validation/guidance-unknown-20261029/simulation.json)

## 5. 실제 운영 API 결과와 한계

이 환경에서 실제 네트워크 요청을 실행했다. 키는 기존 빌드 설정을 사용했고 출력/변경하지 않았다.

- 두 장소 검색 성공: 탐앤탐스 종로한올타워점 / 종각역 1호선.
- 실제 카카오 경로는 종로02, 1호선, 5005 후보 3개였다. 종로02의 첫 구간은 **종로2가.탑골공원 → 종각역.YMCA**였으며 임의 경로를 구성하지 않았다.
- 카카오 경로의 최초 핀까지 보행 응답: 158m/203초.
- 공식 승차점: stopId `100900121`, ARS `01596`, routeId `100900008`, stopOrder `13`.
- 공식 첫 하차점: stopId `100900116`, 같은 routeId의 stopOrder `14`.
- 전체 경유정보 API는 여전히 인증 거부였다. 공식 정류소별 노선 조회의 **13 → 14** 순서를 대조한 생산 함수는 `verified=true`를 반환했다.
- 실제 도착정보 2건은 검사 시점에 유효했으며 원본 ETA는 539초, 730초였다. 이 값은 해당 요청 시점의 응답이며 현재 도착시간이 아니다.
- 공식 정류소 좌표까지 다시 조회한 보행 응답은 155m/200초였다. 실제 응답을 저장·파싱·연결한 계산은 **WALK / reason=null**, 화면/서비스 계산 일치였다.
- 위 종합 계산의 GPS 입력은 공개 장소 좌표로 만든 모의 관측이다. **휴대폰 GPS·센서 검증이 아니다.**
- 이번 작업에서 경기버스 및 지하철 운영 ETA를 새로 호출한 것은 아니다. 해당 기능의 보존은 기존 회귀 테스트 범위다.

공개 장소명, 공식 ID, 비민감 수치만 보관한 [실제 API 관측 기록](validation/guidance-unknown-20261029/live-api-proof.txt)을 첨부했다. 인증키, 요청 헤더, 개인 위치 좌표는 포함하지 않는다.

전체 경유정보 API를 복구하려면 운영 키의 서울 버스 **노선정보조회서비스** 권한/승인과 Worker 연결을 별도로 확인해야 한다. 이번 클라이언트 수정은 이를 몰래 재배포하거나 키를 바꾸는 방식으로 해결하지 않았다.

## 6. 실제 Compose 렌더링

다음 이미지는 실제 Compose 코드와 모의 데이터를 Robolectric Native Graphics로 렌더링했다. 실기기 캡처나 생성형 이미지가 아니다.

- [6개 상태 비교 PNG](validation/guidance-unknown-20261029/comparison.png): 정상 / 도보 요청 / ETA 지연 / 신규 응답 / GPS 불안정 / 실제 만료.
- [변경 전 혼합 GPS 재현](validation/guidance-unknown-20261029/monitoring-before.png), [수정 후 실제 루트 화면](validation/guidance-unknown-20261029/monitoring-coherence.png). 전자는 GPS 선택만 이전 동작으로 되돌린 통제 재현이며 전체 이전 APK 캡처는 아니다.
- [적용한 크림·민트 카드 PNG](validation/guidance-unknown-20261029/cream-mint-card.png).

유효한 동안 거리와 시간이 함께 유지됨, ETA 카운트다운 진행, 신규 응답 즉시 반영, 실제 도보 만료 시 두 값 제거를 단언했다. 같은 승차점의 지하철 행선지 변경, 다른 방향/승차점 거부, 인증 실패 시 공식 순서 확인, 역방향/복수 후보 거부, 호출 제한 전달도 테스트했다.

## 7. 진단과 남은 실기기 확인

디버그 Logcat 태그: `RunOrWalkGuidance`.

상태가 바뀌거나 입력 묶음이 달라질 때 이전/새 상태, UNKNOWN 사유, GPS 측정시각/정확도, 도보 측정/만료/계산시각, 표시와 입력의 거리·시간, 첫/다음 차량 절대 시각과 만료, 공식 식별정보 일치 여부, 선택/요청 세대를 기록한다. 좌표·정류장/노선명·키·요청 URL은 출력하지 않는다. 릴리스 빌드는 기록하지 않는다.

추가 실기기 확인: 실제 종로02 승차점 보행, 건물/지하 GPS 손실과 복귀, 화면 꺼짐/재진입, OS 절전 중 서비스·알림·진동. 이번 자동 검증만으로 휴대폰의 모든 GPS·네트워크 상황이 해결됐다고 주장하지 않는다.

## 8. 빌드 및 테스트

최종 수치와 빌드 결과는 [validation.json](validation/guidance-unknown-20261029/validation.json)에 기록한다.

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug
```

Compose PNG를 다시 저장하려면 `-PproofDir=<출력 폴더>`를 추가할 수 있다. 운영 API 조회는 기본 회귀 테스트에 포함하지 않았다.
