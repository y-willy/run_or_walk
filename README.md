# PaceNudge

현재 위치, 실제 도보시간과 서울 지하철 도착정보를 비교하는 Android 앱이다. Kotlin·Jetpack Compose·Material 3·Kakao Maps를 사용한다.

현재 구현은 두 탭, Kakao 단일 경로 검색, 최대 세 경로, 공식 역·호선·방향 확인, WALK/FAST_WALK/NEXT_TRAIN/UNKNOWN 판단과 선택형 진동 알림을 제공한다. 앱의 기본 중계 주소는 `https://run-or-walk.willy0723.workers.dev`다. **기존 Worker에 새 소스·바인딩을 배포해야 추가 원본 필드와 공유 캐시·요청 제한이 적용된다.** 불완전하거나 미래 시각인 데이터로 판단을 확정하지 않는다. 경기권은 역별 검증이 필요하며 버스 실시간 정보는 아직 미지원이다.

[실기기 오류 4건의 원인·수정·남은 설정](docs/DEVICE_ISSUES_20261025.md)을 정리했다. 지도 401 화면의 **지도 인증 설정**에서 실제 설치 APK의 패키지·서명 키 해시를 복사할 수 있다. 경로의 승차역과 공식 역·호선이 정확히 하나로 연결되면 자동 선택하며, 방향이 여러 개면 이용할 방향을 확인한다.

Run_or_Walk 로고 기반 아이콘과 짧은 시작 화면을 적용했다. 시작 포스터는 비율을 유지해 표시하며 화면 복원·회전 시 반복하지 않는다.

## 빌드

JDK 25, Android SDK 37.0, 저장소의 Gradle Wrapper를 사용한다. Windows에서는 한글이 없는 전체 경로(예: `C:\dev\run_or_walk`)에서 연다.

Git에서 제외된 프로젝트 루트의 `local.properties`에 `sdk.dir`(Android Studio가 설정), `KAKAO_NATIVE_KEY`, `KAKAO_REST_KEY`를 안전하게 설정한다. Kakao Developers Android 등록은 패키지 `com.example.run_or_walk`와 **실제 설치 APK의 서명 키 해시**에 맞춰야 한다. 실제 키·로컬 설정·서명 파일을 커밋하지 않는다.

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Linux/macOS는 `bash gradlew`를 사용한다. 디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다.

## 실시간 연결

[Cloudflare 운영·배포 안내](docs/CLOUDFLARE_WORKERS_NOTES.md)에 따라 기존 `run-or-walk` Worker에 배포한다. 이미 Cloudflare에 있는 **`SEOUL_SUBWAY_KEY` Secret을 그대로 사용**하며 키를 앱·로컬 소스에 복사하거나 재입력하지 않는다. `worker/`는 SQLite Durable Object 공유 캐시·최근 24시간 900건 원본 예산·IP 제한을 포함한다. 새 장부는 이전 사용량을 알 수 없어 최초 조회부터 24시간 보호 대기를 적용한다. 장부·네임스페이스를 초기화해서 예산을 우회하지 않는다.

```bash
cd worker
npm ci
npm run check
npm test
npm run build
```

기존 공개 주소에서는 앱 고정 토큰이 필수가 아니다. 다른 승인된 중계 주소를 사용할 때만 로컬 `SUBWAY_GATEWAY_URL`을 덮어쓴다. `SUBWAY_GATEWAY_TOKEN`은 선택적 호환 설정이며 강력한 앱 인증으로 간주하지 않는다. 샘플 데이터는 테스트 전용이며 현재 차량 정보로 표시하지 않는다.

```bash
python3 -m unittest discover -s gateway -p 'test_*.py' -v
```

[현재 Worker 연동 보고](docs/WORKER_INTEGRATION.md), [공식 API·지원 지역 조사](docs/REALTIME_API_RESEARCH.md)에 구현 범위와 미검증 사항을 기록했다. `gateway/`의 Python 서버는 이전 대안 구현이다. 컴파일·단위 테스트 성공은 스마트폰의 지도 렌더링·실제 운행·GPS·진동 검증과 구분한다.
