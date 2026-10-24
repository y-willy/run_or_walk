# PaceNudge

현재 위치, 실제 도보시간과 서울 지하철 도착정보를 비교하는 Android 앱이다. Kotlin·Jetpack Compose·Material 3·Kakao Maps를 사용한다.

현재 구현은 두 탭, Kakao 단일 경로 검색, 최대 세 경로, 공식 역·호선·방향·차량 선택, WALK/RUN/NEXT/UNKNOWN 판단과 선택형 진동 알림을 제공한다. **HTTPS 중계 서버 배포와 서울 전용 키 설정은 아직 필요하다.** 이 조건 전에는 실시간 판단을 확정하지 않는다. 경기권은 서울 API가 실제 제공하는 역별로 추가 검증해야 하며 버스 실시간 정보는 아직 미지원이다.

## 빌드

JDK 25, Android SDK 37.0, 저장소의 Gradle Wrapper를 사용한다. Windows에서는 한글이 없는 전체 경로(예: `C:\dev\run_or_walk`)에서 연다.

Git에서 제외된 프로젝트 루트의 `local.properties`에 `sdk.dir`(Android Studio가 설정), `KAKAO_NATIVE_KEY`, `KAKAO_REST_KEY`를 안전하게 설정한다. Kakao Developers Android 등록은 패키지 `com.example.run_or_walk`와 **실제 설치 APK의 서명 키 해시**에 맞춰야 한다. 실제 키·로컬 설정·서명 파일을 커밋하지 않는다.

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Linux/macOS는 `bash gradlew`를 사용한다. 디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다.

## 실시간 연결

[HTTPS 중계 서버 배포 안내](gateway/README.md)에 따라 서울 실시간 지하철 전용 키를 **서버에만** 설정한다. 공개 HTTPS 서버를 검증한 다음 앱의 로컬 설정에 `SUBWAY_GATEWAY_URL`, `SUBWAY_GATEWAY_TOKEN`을 설정해 다시 빌드한다. 앱에는 서울 인증키가 포함되지 않는다. 샘플 데이터는 테스트 전용이며 실제 차량 정보로 표시하지 않는다.

```bash
python3 -m unittest discover -s gateway -p 'test_*.py' -v
```

[핵심 재설계·검증 보고](docs/CORE_REDESIGN.md)와 [공식 API·지원 지역 조사](docs/REALTIME_API_RESEARCH.md)에 구현 범위와 미검증 사항을 기록했다. 컴파일·단위 테스트 성공은 스마트폰의 지도 렌더링·실제 운행·GPS·진동 검증과 구분한다.
