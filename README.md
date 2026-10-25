# PaceNudge

현재 위치, 실제 도보시간과 서울 지하철·서울 소속 버스·광역버스 도착정보를 비교하는 Android 앱이다. Kotlin·Jetpack Compose·Material 3·Kakao Maps를 사용한다.

현재 구현은 두 탭, Kakao 단일 경로 검색, 최대 세 경로, 공식 역·호선·방향 확인, WALK/FAST_WALK/NEXT_TRAIN/UNKNOWN 판단과 선택형 진동 알림을 제공한다. 앱의 기본 중계 주소는 `https://run-or-walk.willy0723.workers.dev`다. **기존 Worker에 새 소스·바인딩을 배포해야 추가 원본 필드와 공유 캐시·요청 제한이 적용된다.** 불완전하거나 미래 시각인 데이터로 판단을 확정하지 않는다. 서울 버스 유형 2~6(마을·간선·지선·순환·광역)을 지원한다. 경기권 지하철은 역별 검증이 필요하며 경기·인천·공항 버스는 이번 지원 대상과 구분한다.

[실기기 오류 4건의 원인·수정·남은 설정](docs/DEVICE_ISSUES_20261025.md)을 정리했다. 지도 401 화면의 **지도 인증 설정**에서 실제 설치 APK의 패키지·서명 키 해시를 복사할 수 있다. 경로의 승차역과 공식 역·호선이 정확히 하나로 연결되면 자동 선택하며, 방향이 여러 개면 이용할 방향을 확인한다.

Run_or_Walk 로고 기반 아이콘과 짧은 시작 화면을 적용했다. 시작 포스터는 비율을 유지해 표시하며 화면 복원·회전 시 반복하지 않는다.

서울 버스 키는 Cloudflare의 **`SEOUL_BUS_KEY` Secret**에 공공데이터포털 **일반 인증키(Decoding)**로 등록한다. 버스도착정보조회와 정류소정보조회 모두 활용 승인이 필요하다. 키 위치·새 Worker 배포·앱 사용법은 [서울 버스 연동 안내](docs/SEOUL_BUS_INTEGRATION.md)에 있다.

배포 코드는 [GitHub ZIP](https://github.com/y-willy/run_or_walk/archive/refs/heads/main.zip) 또는 저장소의 **Code → Download ZIP**으로 받는다. 압축 해제 후 `run_or_walk-main/worker` 폴더를 사용한다.

## 빌드

JDK 25, Android SDK 37.0, 저장소의 Gradle Wrapper를 사용한다. Windows에서는 한글이 없는 전체 경로(예: `C:\dev\run_or_walk`)에서 연다.

Git에서 제외된 프로젝트 루트의 `local.properties`에 `sdk.dir`(Android Studio가 설정), `KAKAO_NATIVE_KEY`, `KAKAO_REST_KEY`를 안전하게 설정한다. Kakao Developers Android 등록은 패키지 `com.example.run_or_walk`와 **실제 설치 APK의 서명 키 해시**에 맞춰야 한다. 실제 키·로컬 설정·서명 파일을 커밋하지 않는다.

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug :app:lintDebug
```

Linux/macOS는 `bash gradlew`를 사용한다. 디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다.

## 실시간 연결

[Cloudflare 운영·배포 안내](docs/CLOUDFLARE_WORKERS_NOTES.md)에 따라 기존 `run-or-walk` Worker에 배포한다. 이미 Cloudflare에 있는 **`SEOUL_SUBWAY_KEY` Secret을 그대로 사용**하며 키를 앱·로컬 소스에 복사하거나 재입력하지 않는다. `worker/`는 SQLite Durable Object 공유 캐시·최근 24시간 900건 원본 예산·IP 제한을 포함한다. **지하철과 버스 모두 초기 24시간 대기 없이 조회한다.** 이전 버전이 저장한 초기 대기 시각은 업그레이드 때 해제하고 기존 실제 호출 기록은 보존한다. 버스는 별도 장부에서 정류소정보·도착정보 서비스 각각 최근 24시간 900회 원본 시도를 관리한다. 장부·네임스페이스를 초기화해서 예산을 우회하지 않는다.

```bash
cd worker
npm ci
npm run check
npm run build
```

기존 공개 주소에서는 앱 고정 토큰이 필수가 아니다. 다른 승인된 중계 주소를 사용할 때만 로컬 `SUBWAY_GATEWAY_URL`을 덮어쓴다. `SUBWAY_GATEWAY_TOKEN`은 선택적 호환 설정이며 강력한 앱 인증으로 간주하지 않는다. 과거 샘플 응답은 실제 차량 정보로 표시하지 않는다.

[현재 Worker 연동 보고](docs/WORKER_INTEGRATION.md), [공식 API·지원 지역 조사](docs/REALTIME_API_RESEARCH.md)에 구현 범위와 미검증 사항을 기록했다. `gateway/`의 Python 서버는 이전 대안 구현이다. 컴파일·단위 테스트 성공은 스마트폰의 지도 렌더링·실제 운행·GPS·진동 검증과 구분한다.
