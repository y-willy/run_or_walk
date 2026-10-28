# 이동가이드 우선 배치 — 2026-10-28 (KST)

기준: `feature/guidance-threshold-navigation-refresh-20261027` / `b89fc3216abda9d532753a9741c4d7ba655fbaa9`.

- 선택 경로 요약 바로 아래에 이동가이드를 표시한다. 속도/거리, 다른 경로 보기, 오류/현재 위치·경로 다시 확인/위치 설정은 카드 아래로 옮겼다.
- 다른 경로를 펼쳐도 이동가이드가 먼저 보이고 경로 후보는 아래에 표시한다. 경로 선택 및 조회 함수는 그대로 재사용한다.
- `지금 이동 안내`를 `이동가이드`로 변경하고 우측의 정적인 `이동 가이드` 배지를 제거했다.
- 공식 승차정보가 확정된 서울·경기 버스는 같은 우측 위치에 `자주 타는 버스 등록`/`자주 타는 버스 해제` 버튼을 표시한다. 기존 `monitoringRequest` 유효성 확인과 `FavoriteBusStore.toggle`을 재사용하며, 검색창 아래의 독립 등록 버튼은 제거했다. 지하철에는 버스 등록 버튼을 표시하지 않는다.
- ETA 파서·이동 판단·GPS·서비스·진동·지도·Worker 소스 및 설정은 변경하지 않았다.

## 검증

```bash
bash gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug
```

2026-10-28: 저장소 테스트 **116개 통과, 실패/오류/건너뜀 0**, Kotlin 컴파일·APK 빌드 성공 (`BUILD SUCCESSFUL`, 1분 9초).

실제 루트 Compose 화면에 모의 공식 승차정보/모니터링 상태를 넣어 다음을 검증했다.

1. 선택 경로 요약과 카드 사이 간격 및 보조 버튼·속도 카드의 아래 배치.
2. 이전 두 문구 제거 및 `이동가이드` 표시.
3. 즐겨찾기 등록 버튼이 카드 안에 하나만 존재하며, 등록 시 공식 승차정보 저장/해제 시 삭제.
4. 다른 경로 펼침·접힘과 선택 경로 유지.
5. 지하철 카드에는 버스 등록 버튼 없음.

[수정된 실제 Compose 렌더](proof/guide-card-priority-20261028/route-guide-priority.png) — 모의 데이터/Robolectric 이미지로 휴대폰 캡처가 아니다. 실기기 터치·배치 확인은 별도로 필요하다. 이번에는 운영 API GET·Worker 배포 또는 클라우드 추가 107개 테스트를 재실행하지 않았다.

APK: `app/build/outputs/apk/debug/app-debug.apk`. 이번 UI 변경 적용에는 새 APK 설치가 필요하며 Worker 재배포는 필요 없다. API 키 파일/APK를 Git에 추가하지 않았다. `main` 병합은 하지 않는다.
