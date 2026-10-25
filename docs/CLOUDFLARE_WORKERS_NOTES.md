# Cloudflare Workers 운영·배포 메모

확인일: 2026-10-25 (한국 시각). 대상은 기존 `run-or-walk.willy0723.workers.dev`의 `/health`, `/subway?station=강남`이다. 이 문서는 준비한 소스의 운영 조건이며 **실제 Cloudflare 배포·계정 설정 검증 완료를 뜻하지 않는다.** 기존 `SEOUL_SUBWAY_KEY`는 Cloudflare Worker Secret을 그대로 사용한다. 키 재입력·재발급은 필요하지 않다.

서울 버스·서울 소속 광역버스 어댑터는 [별도 연동 안내](SEOUL_BUS_INTEGRATION.md)를 참고한다. `SEOUL_BUS_KEY` Secret과 `SEOUL_BUS_COORDINATOR` 바인딩·`v2` migration이 추가됐으며 아래 지하철 장부·`v1`은 유지한다. 버스는 정류소정보·도착정보에 독립 예산을 사용하고 새 24시간 초기 대기를 추가하지 않는다.

## 캐시와 원본 호출 예산

- 모든 역 요청은 `SUBWAY_COORDINATOR` 바인딩의 `SubwayCoordinator` 클래스에서 `idFromName("global-v1")`로 같은 SQLite Durable Object에 전달한다. 정규화한 역별로 30초 캐시를 저장하고, 같은 역의 진행 중 조회를 합친다. 캐시 응답의 `fetchedAt`은 원본을 실제 조회한 시각을 보존한다. 만료된 캐시를 새 조회 결과로 반환하지 않는다.
- 원본 오류는 별도 SQLite 테이블에서 역별 10초 backoff한다. 그 안의 반복 요청은 같은 오류와 남은 Retry-After를 반환하며 원본 시도를 추가하지 않는다. 오류를 성공한 도착정보로 저장하지 않는다.
- 원본 조회 전 SQLite에서 최근 24시간 시도 기록을 정리하고 900건 예산을 확인·예약한다. 예약을 디스크에 동기화한 후 원본을 호출한다. 캐시 적중·동일 조회 합류는 새 예산을 사용하지 않는다. 실패한 원본 시도도 예산에서 빼지 않으며 자동 재시도로 추가 호출하지 않는다.
- SQL 예약은 `storage.transactionSync()`로 묶는다. 콜백은 동기 함수여야 하며, 예외 시 롤백한다. SQL의 `BEGIN TRANSACTION`을 직접 실행하지 않는다. 저장 실패 시 원본 호출 없이 실패한다. 예약 직후 프로세스가 종료되면 실제 호출 없이 예산 한 건이 남을 수 있다. 외부 HTTP 요청과 SQLite를 하나의 트랜잭션으로 묶을 수 없으므로 과소 집계를 막는 보수적인 처리다. [공식 SQLite Storage API](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/#transactionsync)
- 이 상한은 **같은 Object를 거치는 이 Worker의 요청**에 적용된다. 같은 키를 쓰는 다른 서비스와 이전 직접 호출은 새 장부에 없으므로 전체 계정의 원본 사용량까지 보장하지 않는다. 실제 사용을 막던 **초기 24시간 대기 정책은 제거했다.** 최초 요청부터 30초 캐시·동시 요청 병합·최근 24시간 900회 상한을 적용한다. 업그레이드는 `ledger_metadata.upstream_not_before`에 남은 이전 대기만 0으로 바꾸며 `upstream_attempts`, 캐시, 클래스·네임스페이스는 유지한다. `BUDGET_BOOTSTRAP_HOLD_SECONDS`는 더 이상 사용하지 않으며 추가 설정 없이 기존 대기가 해제된다. 새 소스는 `BUDGET_WARMUP`을 반환하지 않는다. 실제 900회 상한이나 제공기관 한도에 도달했을 때만 재시도 대기가 필요하다.
- 재배포 때 클래스·네임스페이스·`global-v1`을 유지한다. 이름 변경, namespace 삭제, 저장소 초기화, 과거 시점 복원은 예산 기록을 잃거나 되돌릴 수 있으므로 운영 중 호출 예산을 초기화하는 방법으로 사용하지 않는다.

### Cache API를 전제로 삼지 않는 이유

[공식 Cache API 문서](https://developers.cloudflare.com/workers/runtime-apis/cache/)는 데이터가 저장된 데이터센터 밖으로 복제되지 않는다고 명시한다. Custom Domain의 Worker와 Pages Functions에는 실제 캐시 동작을 명시하며, Dashboard Editor·Playground 미리보기는 캐시 작업이 효과가 없고 Cloudflare Access 앞에서는 사용할 수 없다고 설명한다.

현재 문서만으로 `workers.dev` 전체가 미지원이라고 단정할 수는 없다. 문서에는 `workers.dev`의 캐시 키 동작도 언급되어 있다. 다만 **이 기존 배포에서 Cache API 적중·지속성을 실제 확인한 적은 없으며**, 동작하더라도 데이터센터별 캐시로는 전역 예산·전역 중복 조회 병합을 보장할 수 없다. 따라서 이 구현은 Cache API에 의존하지 않는 singleton Durable Object를 채택했다. 기존 `/health`, `/subway` 응답만으로 Cache API 지원을 검증했다고 주장하지 않는다.

## IP 제한과 무료 플랜 제약

`IP_RATE_LIMITER`는 IP당 `limit: 60`, `period: 60` 설정이다. Cloudflare 위치별 근사 제한으로 요청 수가 임계값을 넘을 수 있으며, 위치 간 엄격한 전역 제한이 아니다. 글로벌 원본 예산은 위 SQLite Object가 별도로 담당한다. IP 공유망 사용자는 같은 제한을 공유할 수 있고, IP 변경으로 다른 카운터를 사용할 수 있다.

Wrangler 4.36.0 이상이 필요하다. `namespace_id`는 **양의 정수를 문자열로 표현한 값**이어야 하며, `period`는 `10` 또는 `60`초만 가능하다. 현재 값 `"1001"`은 예시 계정 식별자이므로 실제 계정의 다른 Worker·바인딩과 충돌하지 않는지 확인한다. 같은 계정에서 같은 namespace ID와 key를 사용하면 위치별 카운터를 공유한다. IP 제한 응답은 429와 재시도 안내를 제공하며, 필수 바인딩 실패 때는 원본을 호출하지 않고 503으로 처리한다. [공식 Rate Limiting API](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)

SQLite Durable Objects는 **Workers Free 플랜에서도 사용 가능**하다. KV backend와 혼동하지 않는다. 계정 전체의 다른 사용량도 함께 집계된다.

| 무료 한도 | 값 |
| --- | --- |
| Durable Object 요청 | 100,000건/일 |
| 실행 시간 | 13,000 GB-s/일 |
| SQLite 읽은 행 | 5,000,000행/일 |
| SQLite 쓴 행 | 100,000행/일 |
| 저장 데이터 | 계정 합계 5GB |
| SQLite Object 한 개 저장 한도 | 10GB; Free 계정의 5GB 합계 한도가 먼저 적용됨 |
| Durable Object 클래스 | 계정당 100개 |

일일 무료 한도는 **00:00 UTC**에 초기화한다. 어느 한 종류의 한도를 넘으면 그 종류의 추가 작업이 오류로 실패한다. 저장 데이터 한도는 일일 초기화 대상이 아니다. 캐시 적중도 Worker·Object 요청과 SQL 읽기 사용량은 소비하며, SQL 삭제와 인덱스 갱신도 쓰기 사용량에 영향을 준다. 따라서 원본 900건 제한이 Cloudflare 무료 사용량까지 보장하지는 않는다. 서비스가 작아도 무제한 무료·무중단을 보장하지 않는다. Object 장애·무료 한도 초과 시 원본 직통 우회 없이 오류로 처리한다. [공식 가격](https://developers.cloudflare.com/durable-objects/platform/pricing/), [공식 한도](https://developers.cloudflare.com/durable-objects/platform/limits/)

## Wrangler 설정과 기존 URL 유지

실제 설정은 [`../worker/wrangler.jsonc`](../worker/wrangler.jsonc)를 기준으로 한다. 핵심은 다음과 같다.

```jsonc
{
  "name": "run-or-walk",
  "main": "src/index.ts",
  "compatibility_date": "2026-10-24",
  "workers_dev": true,
  "durable_objects": {
    "bindings": [
      { "name": "SUBWAY_COORDINATOR", "class_name": "SubwayCoordinator" }
    ]
  },
  "migrations": [
    { "tag": "v1", "new_sqlite_classes": ["SubwayCoordinator"] }
  ],
  "ratelimits": [
    {
      "name": "IP_RATE_LIMITER",
      "namespace_id": "1001",
      "simple": { "limit": 60, "period": 60 }
    }
  ]
}
```

`new_classes`는 SQLite migration의 대체 표현이 아니다. 새 클래스는 `new_sqlite_classes`를 사용하고 코드에서 `SubwayCoordinator`를 export해야 한다. 기존 Worker에 migration 이력이 있다면 이력을 보존하고 충돌 없는 새 tag를 추가한다. 이미 적용된 tag의 내용을 바꾸지 않는다. 현재 공식 문서는 새 프로젝트에는 선언형 `exports`를 권장하지만, 기존 Worker의 `migrations`는 계속 지원한다. 두 방식을 한 설정에 섞지 않는다. [공식 legacy migration 설정](https://developers.cloudflare.com/durable-objects/reference/durable-object-class-migrations-legacy/)

같은 `willy0723` 계정에서 이름 `run-or-walk`와 `workers_dev: true`를 유지해야 기존 URL을 유지한다. 다른 계정으로 배포하거나 `--env staging` 같은 이름 있는 환경으로 배포하면 기존 서비스 배포가 아니다. routes·Custom Domain·계정 subdomain 변경은 이 작업에 필요하지 않다. [공식 workers.dev URL 규칙](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/)

Wrangler는 일반 배포에서 기존 encrypted Secret을 삭제하지 않는다. `wrangler secret delete`를 실행하지 않으며, `SEOUL_SUBWAY_KEY`를 `vars`, 소스, Android BuildConfig, APK, 로그, 테스트 fixture에 넣지 않는다. Dashboard의 일반 변수를 보존해야 한다면 `keep_vars: true`를 사용한다. Secret 보존과 일반 변수 보존은 구분한다. [공식 설정 source of truth](https://developers.cloudflare.com/workers/wrangler/configuration/#source-of-truth), [공식 Secrets](https://developers.cloudflare.com/workers/configuration/secrets/)

## 로컬 검증과 실제 배포 명령

Node.js 22 이상과 npm을 사용한다. 저장소 루트에서:

```bash
cd worker
npm ci
npm run check
npm run build
```

`npm run check`는 TypeScript 정적 검사이고 `npm run build`는 `wrangler deploy --dry-run --outdir dist`이며 업로드가 아니다. 이 두 명령만으로 계정 권한·실제 바인딩·서울시 응답·배포 성공을 검증할 수는 없다.

배포는 기존 계정에 대한 실제 Cloudflare 인증이 준비된 환경에서 다음과 같이 수행한다. 인증은 사용자 OAuth 로그인 또는 안전하게 설정한 `CLOUDFLARE_API_TOKEN`을 사용한다. 토큰 값을 명령문·문서·채팅에 넣지 않는다.

```bash
cd worker
npx --no-install wrangler login
npx --no-install wrangler whoami
npx --no-install wrangler deploy
```

`whoami`로 원래 Worker가 있는 계정인지 확인하고, 여러 계정이면 비밀값이 아닌 `CLOUDFLARE_ACCOUNT_ID`로 그 계정을 선택한다. 배포 전에 Dashboard에서 기존 Worker의 Secret **이름·존재**, 기존 migration 이력, `1001` namespace 충돌 여부와 이전 호출량을 확인한다. 기존 키를 다시 `secret put`할 필요가 없다. 이 작업 환경에서는 인증 가능한 Cloudflare 계정 연결이 확인되지 않아 실제 배포를 실행하지 않았다.

현재 공식 권한 기준으로 기존 Worker 배포에는 해당 Worker의 **Editor** 권한이 필요하다. 기존 account-level `Workers Scripts Edit`도 계속 지원된다. Durable Object는 이를 구현한 Worker의 권한을 따르며 별도 DO 권한은 없다. 현재 workers.dev를 유지하는 배포에는 zone의 route 변경 권한이 필요하지 않다. 새로운 Worker를 만드는 경우에는 product-level Admin이 필요하므로, 계정 선택 오류로 다른 Worker를 새로 만들지 않는다. [Workers 권한](https://developers.cloudflare.com/workers/authorization/workers/), [Durable Objects 권한](https://developers.cloudflare.com/workers/authorization/durable-objects/), [Wrangler 인증 변수](https://developers.cloudflare.com/workers/wrangler/system-environment-variables/)

배포 후 `/health`와 정상 역 조회를 소량 확인하고, 응답의 원본 `fetchedAt`·캐시 표시·전역 예산이 기대대로 변하는지 확인한다. 배포된 서비스에서 실제 900회 소진 테스트를 하지 않는다. 초과·동시 요청·캐시 만료·원본 오류·재시작 후 예산 보존은 모의 테스트로 검증하고, 로컬 검증 결과와 실제 배포 검증 결과를 별도로 기록한다.

## 남는 통신 위험

클라이언트와 Worker는 HTTPS로 연결하지만, 서울시 문서의 원본 경로는 HTTP다. Worker→서울시 사이에는 API 키와 응답이 평문으로 전송될 위험이 남는다. 이 구현은 고정된 원본 호스트·경로만 구성하고 `redirect: "manual"`로 리다이렉트를 거절하며, 키·원본 URL·원본 오류 문자열을 클라이언트에 보내지 않는다. Android의 고정 토큰은 강한 인증으로 취급하지 않는다. 제공기관의 지원 HTTPS 주소를 확인하기 전에는 임의로 HTTPS를 보장하거나 TLS 검증을 끄지 않는다. 서울시 원본 명세와 필드 해석 근거는 [REALTIME_API_RESEARCH.md](REALTIME_API_RESEARCH.md)를 참고한다.
