# 이동 안내 시작 비활성화·파란 접근 경로 보완

브랜치 `fix/monitor-start-blue-access-20261030`. main `4cede9a`와 이전 지도 수정 `27b2016`을 모두 포함한다. 운영 main에는 27b2016이 아직 없었으며, 사용자가 설치한 APK의 커밋은 제공되지 않아 설치본 반영 여부는 확인하지 못했다.

## 실제 원인

1. N37의 공식 유형 15를 서울 Repository에서는 허용하지만 `JourneyMonitoringRequest.isValid()`에서는 2~6만 허용했다. 따라서 공식 승차정보·ETA가 있어도 `monitoringRequest()`가 null이 되어 시작 버튼이 비활성화됐다.
2. `JourneySessionStore.busRoute()`에도 같은 유형 누락이 있어, 저장한 심야 노선을 복원할 때 공식 노선과 방향 연결이 사라졌다.
3. 시작 버튼과 파란 접근 경로 요청이 `screen.busy`에 함께 묶였다. 별도 도보/GPS 재조회가 진행 중이어도 이미 선택된 공식 승차정보로 서비스 시작 또는 독립적인 지도 경로 조회를 진행할 수 있는데, 기존 코드는 둘 다 막았다.
4. 지도는 별도 `mapAccessSegments`를 준비하는 비동기 작업에만 의존했다. 이미 유효한 도보 좌표가 있거나 추적이 서비스로 넘어가 서비스가 최신 좌표를 보유해도, 이 좌표를 즉시 지도에 전달하지 않았다.

N37 서비스 요청 거절과 세션 복원 누락은 수정 전 테스트 2건에서 모두 재현했다. 사용자의 기기 로그를 읽은 것은 아니며, 다른 이유로 공식 방향 연결이 실패한 상황까지 동일 원인으로 단정하지 않는다.

## 변경 파일

- `SeoulBusModels.kt`: 공식 서울 지원 유형을 공통 함수 `isSupportedSeoulBusRouteType`으로 정의(기존 2~6 + 심야 15).
- `SeoulBusRepository.kt`, `JourneyMonitoringRequest.kt`, `JourneySessionStore.kt`: 조회·서비스 검증·저장 선택 복원에 같은 유형 정책 사용. 타기관 유형 8 등을 이름만으로 허용하지 않는다.
- `MainActivity.kt`: 시작 버튼에서 불필요한 전역 busy 잠금 제거. 공식 선택 유효성, 권한 요청 중, 시작 처리 중 잠금은 유지한다. 지도 접근 경로는 자체 요청 게이트로 조회하며, 이미 선택된 지도 구간은 다른 도보 재조회 때문에 막지 않는다. 실제 표시할 파란 좌표를 동기적으로 계산해 KakaoMapPanel에 전달한다.
- `WalkingAccessPolicy.kt`: 유효한 원본 도보 좌표를 즉시 표시하고, 같은 공식 승차 지점에 해당하는 서비스의 걷기 좌표도 재사용한다. 이전 정류소/방향·다른 출발지·만료 경로를 가져오지 않는다. 수신 시각이나 유효기간은 갱신하지 않는다.

이전 27b2016의 파란 선 z-order/카메라 범위/도로 보정 좌표 재사용 수정도 포함한다. 직선·가상 좌표를 추가하지 않는다. 실시간 ETA, 이동 판단 계산식, 진동, Worker 정책은 변경하지 않았다.

## 검증 구분

### 단위·Compose·서비스 테스트 (모의 입력)

- `SeoulNightBusTest`: 실제 기록 응답에 기반해 서비스 요청 인코딩/디코딩 및 검증된 심야 선택의 저장·복원 추가 검증.
- `MonitoringStartUiRegressionTest`: 실제 Compose 시작 버튼 활성화/클릭/시작 중 표시, 별도 도보 로딩 중의 시작 가능 여부. OS의 RESUMED 경계 이전까지만 진행하며 네트워크를 호출하지 않는다.
- `NightBusServiceStartTest`: Robolectric에서 실제 Controller → Intent Codec → Service.onStartCommand → foreground 알림/실행 상태 전환 및 종료. 위치·ETA 조회는 제어된 모의 입력이다. 실제 휴대폰 서비스 구동 검증으로 표시하지 않는다.
- `MapAccessDisplayRegressionTest`: 별도 조회 전 즉시 파란 좌표 제공, 서비스 전환 시 좌표 유지, 타 서비스/만료/다른 출발지 제외, 유효 캐시 유지, 독립 요청의 로딩 정책 등 7건.
- 기존 지도/버스/지하철/걷기/진동/화면·서비스 회귀 테스트 포함.

### 실제 운영 API 프로브

[실제 호출 결과](validation/monitor-start-blue-20261030/live-api-proof.txt), 2026-10-30 01:39 KST:

- 종각역 → 종로2가.삼일교 → 강남역 2호선의 실제 공개 API 응답 사용.
- N37 유형 15, 공식 승차 방향 확인, 서비스 요청 유효성 및 Intent 데이터 인코딩/디코딩 성공.
- 실제 도보 588m / 557초 / 좌표 25개, 파란 구간 1개 + 초록 구간 1개.
- 별도 지도 조회 이전에 파란 좌표 제공, 서비스로 걷기 데이터를 넘긴 후에도 동일 좌표 제공, 카메라 범위 및 표시 순서 확인.
- GPS는 공개 역 위치를 사용한 모의 입력. 이 프로브에서는 새 실제 버스 ETA를 조회하거나 실제 휴대폰 지도를 캡처하지 않았다.

## 적용

최종 테스트·컴파일·APK 결과는 [validation.json](validation/monitor-start-blue-20261030/validation.json)에 기록한다. 이 브랜치로 새 APK를 빌드·설치해야 한다. main 병합 및 Worker 운영 배포는 수행하지 않는다. 실제 휴대폰에서 위치·알림 권한 허용 후 버튼 시작/종료와 파란 선 렌더링을 추가 확인해야 한다.
