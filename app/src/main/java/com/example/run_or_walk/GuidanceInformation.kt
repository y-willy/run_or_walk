package com.example.run_or_walk

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val guidanceBands=listOf(
    Triple("여유 있어요","4분 초과","승차까지 시간적 여유가 있어요"),
    Triple("걸으세요","2분 초과 ~ 4분 이하","일반 도보로 이동하세요"),
    Triple("놓칠 수 있어요","0초 초과 ~ 2분 이하","승차 준비 여유가 부족해요"),
    Triple("뛰세요","-90초 이상 ~ 0초 이하","일반 도보로는 준비시간이 부족해요"),
    Triple("다음 차량을 탑승하세요","-90초 미만","확인된 다음 차량을 안내해요"))
internal data class GuidanceDataSource(val name:String,val url:String,val terms:String)
internal val guidanceDataSources=listOf(
    GuidanceDataSource("카카오맵","https://developers.kakao.com/docs/latest/ko/kakaomap-android/common","카카오 개발자 약관·지도 이용조건 적용"),
    GuidanceDataSource("서울특별시 TOPIS","https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do","공공누리 1유형 · 출처표시"),
    GuidanceDataSource("서울 버스 정류소·노선·도착정보","https://www.data.go.kr/data/15000314/openapi.do","공식 페이지의 이용허락 조건 확인"),
    GuidanceDataSource("경기도 버스 정류소·도착정보","https://www.data.go.kr/data/15080346/openapi.do","공식 페이지의 이용허락 조건 확인"),
    GuidanceDataSource("서울교통공사 공식 시간표","https://www.data.go.kr/data/15098251/fileData.do","공식 페이지의 이용허락 조건 확인"),
    GuidanceDataSource("코레일 공식 시간표","https://www.korail.com/ticket/guest/notice/25642","공식 공지의 이용조건 확인 · 재배포 권리 별도 확인"),
    GuidanceDataSource("정부 공휴일 자료","https://www.korea.kr/briefing/pressReleaseView.do?newsId=156768474","정부 월력요항 · 해당 원문 이용조건 확인"))

internal fun displayedWalkingSpeed(location:GuidanceLocation?,now:Long):Double? = location?.takeIf {
    locationSampleFailure(it,now)==null && it.speedAccuracyMetersPerSecond?.let { accuracy -> accuracy in 0.0..0.75 }==true
}?.speedMetersPerSecond?.takeIf { it.isFinite() && it in 0.0..4.5 }

@Composable
internal fun GuidanceHeader(onInfo:()->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {PaceNudgeHeader()}
        IconButton(onClick=onInfo) {AppIcon(R.drawable.ui_info,"앱 정보")}
    }
}

@Composable
internal fun GuidanceInformationContent(settings:JourneySettings,currentSpeed:Double?,averageSpeedKmh:Double?,
    calibratedSpeed:Double?,onWalkingAnalysis:()->Unit={}) {
    val context=LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionCard("이동 안내 기준") {
            Text("이동 여유시간 = 차량 ETA - 도보시간 - 승차 준비 여유시간",style=MaterialTheme.typography.bodySmall)
            guidanceBands.forEach { (title,range,meaning) ->
                Text(title,fontWeight=FontWeight.Bold);Text("$range · $meaning",style=MaterialTheme.typography.bodySmall)
            }
            Text("다음 차량이 확인되지 않으면 ‘다음 차량 정보 확인 필요’로 표시해요.",style=MaterialTheme.typography.bodySmall)
            Text("승차 준비 여유 ${settingsBufferLabel(settings.bufferSeconds)} · 초 단위 원본으로 판단해요.",style=MaterialTheme.typography.bodySmall)
        }
        SectionCard("진동별 의미") {
            val hapticStates = listOf("여유 있어요" to AutoGuidanceStatus.RELAXED,
                "걸으세요" to AutoGuidanceStatus.WALK,"놓칠 수 있어요" to AutoGuidanceStatus.CAUTION,
                "뛰세요" to AutoGuidanceStatus.FAST_WALK,"다음 차량 안내" to AutoGuidanceStatus.NEXT_VEHICLE,
                "정보 부족" to AutoGuidanceStatus.UNKNOWN)
            hapticStates.forEach { (label,status) -> Text("$label: ${movementVibration(status).description}") }
            Text("짧은 진동 1회는 100ms, 반복 간격은 150ms예요. 다음 차량은 끊김 없이 2초 동안 진동해요. 최초 상태 설정은 조용하며, 변경된 상태가 3초 이상 유지된 뒤 진동해요.",style=MaterialTheme.typography.bodySmall)
            Text("실제 진동 후 30초 동안 모든 새 진동을 차단해요. 긴급도가 높아져도 동일하며 화면 문구는 즉시 바뀌어요. 차단된 진동을 나중에 몰아서 실행하지 않아요.",style=MaterialTheme.typography.bodySmall)
            Text("화면과 백그라운드가 같은 제한을 공유해요. 진동 설정을 끄면 발생하지 않아요.",style=MaterialTheme.typography.bodySmall)
        }
        SectionCard("내 걷기 분석") {
            fun speed(value:Double?)=value?.let { String.format(java.util.Locale.KOREA,"%.1f km/h",it) } ?: "저장된 측정값 없음"
            Text("현재 이동속도: ${speed(currentSpeed?.times(3.6) ?: 0.0)}")
            Text("개인 평균 걷기 속도: ${speed(averageSpeedKmh)}")
            val personal=settings.personalWalkingSpeedKmh?.takeIf { settings.usePersonalWalkingSpeed }
            val calibrated=calibratedSpeed?.takeIf { settings.gpsAutoCalibrationEnabled }
            Text("도보 계산: "+if(settings.useProviderWalkingTime && personal==null && calibrated==null) "경로 API 도보시간 기준"
                else speed(settings.walkingSpeedMetersPerSecond(calibrated)*3.6))
            Text("GPS 부족 시 기본 설정: ${speed(personal ?: settings.speedKmh)}")
            Text("정지 중에도 설정 속도로 안내해요. 정확한 GPS 속도는 30%만 참고하고 설정 속도의 ±20% 안에서 보정해요. 속도 누락·부정확·오래된 측정은 설정 속도를 사용해요.",style=MaterialTheme.typography.bodySmall)
            Text("표시용 0.0 km/h는 정지 판정이나 도보 계산 입력이 아니에요.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=onWalkingAnalysis) {Text("내 걷기 분석 열기")}
        }
        SectionCard("기본 안내") {
            Text("공식 실시간 ETA를 우선 사용해요. ETA가 없으면 공식 열차 위치·시간표를 연결한 추정 또는 시간표 예정임을 구분해요.")
            Text("GPS는 별도로 수신하고, 도보 거리·시간은 30초 주기로 계산해요. 설정 속도와 검증된 GPS 보정을 사용하며 실제 경로에서 진행도를 계산해요.")
            Text("유효한 ETA·도보값은 갱신 중에도 유지해요. 차량 통과·만료·선택 변경 때는 이전 값을 사용하지 않아요. 수신 예정시각에 경과시간을 반영하며 분 단위 자료를 초 단위로 꾸미지 않아요.")
            Text("이동 안내 시작을 눌러야 화면 꺼짐 안내가 시작돼요. 알림에서 종료할 수 있으며 최대 60분 후 종료돼요. 위치·알림 권한과 OS 절전 설정에 영향을 받아요.")
            Text("최초 조회·조회 실패·유효기간 만료·운행정보 없음을 구분해 표시해요.")
        }
        SectionCard("데이터 출처") {
            guidanceDataSources.forEach { source ->
                Text(source.name,fontWeight=FontWeight.Bold)
                Text(source.terms,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(source.url))) }) {
                    Text(source.url,style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
@Composable
internal fun GuidanceInformationPage(settings:JourneySettings,currentSpeed:Double?,averageSpeedKmh:Double?,
    calibratedSpeed:Double?,onDismiss:()->Unit,onWalkingAnalysis:()->Unit) {
    Surface(Modifier.fillMaxSize().safeDrawingPadding(),color=MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("앱 정보",Modifier.padding(top=14.dp),style=MaterialTheme.typography.titleLarge)
                TextButton(onClick=onDismiss) {Text("닫기")}
            }
            Box(Modifier.weight(1f)) {GuidanceInformationContent(settings,currentSpeed,averageSpeedKmh,calibratedSpeed,onWalkingAnalysis)}
        }
    }
}
@Composable
internal fun GuidanceInformationDialog(settings:JourneySettings,currentSpeed:Double?,averageSpeedKmh:Double?,
    calibratedSpeed:Double?,onDismiss:()->Unit,onWalkingAnalysis:()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        GuidanceInformationPage(settings,currentSpeed,averageSpeedKmh,calibratedSpeed,onDismiss,onWalkingAnalysis)
    }
}
