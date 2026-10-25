# Worker 연동·실시간 정확성 검증 보고

2026-10-25 실기기 오류 수정은 [위치·지도·승차역·도착정보 진단 보고](DEVICE_ISSUES_20261025.md)를 참고한다. 아래 85개 앱 테스트·133개 Worker 테스트는 최초 연동 시점의 기록이며, 이번 앱 수정 후에는 109개 테스트를 검증했다.

## 1. 변경 파일

- `worker/`: 기존 `run-or-walk` Worker 소스, SQLite Durable Object, 정규화·신뢰도, Wrangler/Rate Limiting/migration 설정, 잠금 파일과 실제 Workers 런타임 테스트.
- `SeoulSubwayRepository.kt`, `app/build.gradle.kts`: 기본 주소와 `/subway?station=` 계약 연결, 선택적 토큰, 앱 식별자, 응답 파싱·Retry-After 공유 제한.
- `AutoGuidance.kt`, `ExactSubwayArrivalMatching.kt`: 네 가지 상태, 원본 신뢰도, 검증된 역·호선·방향·운행 종류만 매칭, 유효한 다음 열차 조건.
- `JourneyScreenState.kt`, `MainActivity.kt`: 유일한 실제 승차 장소·공식 역 자동 연결, 명시적 방향 확인 뒤 자동 열차 선택·foreground 갱신, 이전 요청 버전 차단, 탭·역 변경에도 요청 제한 보존.
- `PaceNudgeComponents.kt`: 빠른 걸음 안내와 초 단위 도착/도보시간·정보 나이, 불확실한 행의 선택 차단.
- 관련 단위 테스트·실제 사용자 강남역 응답 fixture, README와 배포 문서.
- `BrandLaunchScreen.kt`, 런처/테마 리소스와 PNG: 제공한 Run_or_Walk 로고를 바탕으로 앱 아이콘과 시작 화면 적용. 작은 아이콘은 발자국·경로 그래픽을 사용하며, 전체 포스터는 시작 시 650ms 표시하고 복원·회전 때 반복하지 않는다.

## 2. 기능과 정확성

사용자가 제공한 원래 Worker는 캐시·IP 제한·전역 예산이 없었고, 원본 응답에서 `arvlCd`, `btrainNo` 등 식별 필드를 버렸다. 이를 보존하고 기존 `/health`, `/subway`, `station`·`destination` 등 별칭의 의미를 유지한다. HTTP 성공이나 `realtime:true` 자체는 데이터 신뢰도 보장이 아니다.

앱은 Seoul Secret을 요구하지 않는다. 정확한 역 이름·호선과 실제 장소가 유일하면 자동 연결하고, 모호하면 선택을 요청한다. 역·호선 연결 시 비동기로 조회하며, 방향·행선지를 사용자가 확인한 후 **공식 역 ID + 호선 ID + 방향 + 운행 종류/종착/방면**이 정확히 일치하는 유효한 실제 열차 중 가장 이른 것을 사용한다. 이름만으로 첫 열차를 고르지 않는다. Kakao 결과에 매칭 근거가 없으면 최소한의 방향 확인을 유지한다. 버스 도착정보를 지하철 데이터로 대체하지 않는다.

판단은 `WALK`, `FAST_WALK`, `NEXT_TRAIN`, `UNKNOWN`이다. 빠른 걸음 소요시간은 기존 0.75 비율 **추정**이며 달리기 안내를 하지 않는다. 다음 열차는 실제 응답으로 확인되고 일반 보행+안전 여유로 탑승 가능한 경우에만 추천한다. 다음 정보가 없거나 불확실하면 UNKNOWN이다. 기존 수동 Urgency 경계값과 진동 파형은 유지하며 자동 알림은 명시적 동의·중복 억제를 따른다.

### 시각·0초 검증

`fetchedAt`은 ISO UTC, `receivedAt/recptnDt`는 한국 시각으로 해석한다. 사용자가 제공한 실제 응답의 `2026-10-24T21:47:51.056Z`는 **2026-10-25 06:47:51.056 KST**다. 신분당선 생성 시각 `06:52:18`은 그보다 **266,944ms 미래**다. 이전 예시의 `21:40:52.482Z`와 `06:45:37` 차이도 **284,518ms 미래**다.

원인이 확인되지 않은 미래 데이터는 `FUTURE_TIMESTAMP`로 격리한다. 캐시가 오래돼 실제 시간이 따라잡아도 원래 조회 시각보다 미래였다는 사실을 지우지 않는다. `0초 + 전역 메시지`는 현재 승차역 도착으로 해석하지 않고 UNKNOWN으로 남긴다. 음수·NaN·Infinity·누락·유효하지 않은 상태 코드로 시간을 생성하지 않는다.

데이터 신뢰도는 VALID/STALE/FUTURE_TIMESTAMP/INVALID_ETA/UNKNOWN이며 앱도 서버의 VALID를 그대로 신뢰하지 않고 재검증한다. 생성 후 60초는 보수적인 앱 정책이고 제공기관 보장치가 아니다. 두 열차를 묶으면 더 먼저 만료되는 생성 시각의 유효기간을 적용한다. 현재 승강장 접근·역 내부 이동시간은 측정하지 못하므로 기본 120초 안전 여유 가정과 한계를 표시한다.

## 3. 캐시·제한 정책

동일 공식 역 이름은 공유 SQLite Object에서 30초 캐시·동시 조회 병합을 사용한다. 캐시의 원래 `fetchedAt`을 보존하고 만료 응답을 새 실시간 결과처럼 반환하지 않는다. IP당 60회/분은 Cloudflare 위치별 근사 제한이며, 별도의 singleton Object가 **최근 24시간 원본 시도 최대 900건**을 관리한다. 실패·시간 초과·리다이렉트도 한 번의 시도로 계산하고 자동 재시도하지 않는다. 캐시 적중은 원본 예산을 사용하지 않는다.

새 장부는 이전 Worker의 호출 이력이 없으므로 **최초 Object 생성/역 조회부터 24시간 보호 대기**한다. 기존 서비스의 최근 24시간 호출이 확인된 0건일 때만 초기 대기 생략을 검토할 수 있다. 다른 서비스가 같은 키를 사용하면 이 Worker가 전체 키 사용량을 통제할 수 없다. 바인딩·장부 삭제나 다른 네임스페이스로 배포해서 한도를 초기화하면 안 된다.

## 4. 실제 검증

앱의 `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`와 단위 테스트 **85개**를 실행했다. Worker는 TypeScript 검사, 실제 workerd/SQLite 기반 테스트 **133개**, Wrangler dry bundle을 통과했다. Worker 모의 테스트는 실제 서울 API 키를 사용하지 않는다. 아이콘·시작 화면 추가 후에도 네 가지 Gradle 대상을 다시 통과했으며, 최종 린트는 오류 0개·경고 29개·힌트 1개다.

시작 테마 검사에서 API 27 전용 탐색 막대 옵션을 발견해 API 26 기본 테마와 `values-v27` 리소스로 분리했다. 검사 억제나 최소 지원 버전 변경으로 우회하지 않았다. 아이콘 마스크·시작 화면·회전 복귀의 실제 스마트폰 동작은 별도 확인이 필요하다.

현재 배포된 기존 서비스는 앱 식별자 `PaceNudge/1.0 (Android)`로 `/health`, 강남역 조회 모두 HTTP 200을 확인했다. 기본 Python 클라이언트 식별자로는 403을 관측해 앱에 명시적 식별자를 사용한다. 실제 강남 응답 7행 중 미래 생성 3행·0초 3행, 원본 상태 코드/열차번호 0행을 관측했다. Worker의 `fetchedAt`과 HTTP `Date`는 같은 UTC 초였다. 시간대 변환을 확인했지만 원본 시계 차이·생성 시각 의미의 오류 원인을 확정하지 않았다. **이것은 새 Worker 배포 검증이 아니다.**

## 5. 미검증·외부 제약

Cloudflare 계정·권한·배포 후 실제 바인딩, 새 캐시의 운영 적중률과 실제 사용량, 실제 데이터의 시계 불일치 원인, 스마트폰 지도·GPS·승강장 매칭·진동·키보드·TalkBack은 미검증이다. 현재 공개 Worker가 옛 필드만 반환하므로, 새 소스를 배포하기 전에는 앱이 이를 확정 판단하지 않는다.

## 6. 추가 바인딩·비밀값

`SUBWAY_COORDINATOR` → `SubwayCoordinator` SQLite Durable Object, `IP_RATE_LIMITER` → 60/60초 설정을 추가한다. migration은 `new_sqlite_classes`를 사용한다. 기존 **`SEOUL_SUBWAY_KEY` Secret은 그대로 유지**하며 값 요청·복사·재등록은 하지 않는다. 이름·계정·기존 migration/namespace 충돌을 확인한다. 무료 플랜 제약과 정확한 설정은 [운영 메모](CLOUDFLARE_WORKERS_NOTES.md)에 있다.

## 7. Cloudflare 배포

이 환경의 `wrangler whoami`는 인증되지 않았다고 보고했다. 실제 Cloudflare 배포를 수행하지 않았다. 원래 `willy0723` 계정에서 실행한다.

```bash
cd worker
npm ci
npm run check
npm test
npm run build
npx --no-install wrangler login
npx --no-install wrangler whoami
npx --no-install wrangler deploy
```

이름 `run-or-walk`, `workers_dev: true`를 유지하고 임의의 `--env`나 임시 preview 계정에 배포하지 않는다. 기존 Secret은 일반 deploy로 유지된다. 새 장부의 첫 조회는 BUDGET_WARMUP과 Retry-After를 반환할 수 있다. 실제 900회 소진 검증은 모의 테스트에서만 수행한다.

## 8. APK·스마트폰 확인

Windows는 영문 프로젝트 경로에서 `gradlew.bat :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`, Linux는 `bash gradlew`를 실행한다. 기본 URL은 이미 설정돼 있고 서울 Secret은 APK에 들어가지 않는다. 기존 Kakao 로컬 키·설치 APK 서명 등록을 유지한다.

새 Worker 배포 후 원본 필드·신뢰도와 캐시/Retry-After를 소량 확인한다. 기기에서는 강남 2호선 내/외선과 신분당선 상/하행 구분, 다른 경로·탭으로 전환 중 늦은 응답 거절, 미래·0초·오래된 응답의 UNKNOWN, 유효한 실제 다음 열차 조건, GPS/도보시간 누락·알림 동의·중복 진동 억제를 확인한다. 컴파일 성공을 실기기 성공으로 보고하지 않는다.
