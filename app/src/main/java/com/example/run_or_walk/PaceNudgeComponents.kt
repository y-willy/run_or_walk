package com.example.run_or_walk

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

@Composable
fun PaceNudgeHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.brand_mark),
            contentDescription=androidx.compose.ui.res.stringResource(R.string.brand_logo_description),modifier=Modifier.size(40.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(androidx.compose.ui.res.stringResource(R.string.app_name),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.ExtraBold)
            Text("도착에 맞춰 가볍게",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FeatureModeSelector(selectedIndex: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Surface(shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("경로 검색", "정류소 직접 검색").forEachIndexed { index, title ->
                val selected = selectedIndex == index
                val background by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.surface else Color.Transparent, label = "tabColor")
                Surface(
                    color = background, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                        .selectable(selected, enabled = enabled, role = Role.Tab,
                            onClick = { onSelect(index) })
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    BasicText(title, style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center),maxLines=1,softWrap=false,
                        autoSize=TextAutoSize.StepBased(minFontSize=11.sp,maxFontSize=14.sp,stepSize=0.5.sp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp))
                }
            }
        }
    }
}

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun PlaceOptionCard(title: String, detail: String, selected: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((if (selected) "✓ " else "") + title,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** At most three compact alternatives; only the selected alternative expands. */
@Composable
fun TransitRouteCard(durationMinutes: Int, serviceName: String, boardingName: String,
    detail: String, selected: Boolean, enabled: Boolean = true,
    guidanceLabel: String = "추정 · 실시간 미연결",
    modifier: Modifier = Modifier.fillMaxWidth(), onSelect: () -> Unit) {
    Surface(onClick = onSelect, enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)
            else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp,
            if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected) Text("✓ 선택한 경로", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(durationMinutes.toString() + "분", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text(serviceName.ifBlank { "노선 확인 필요" },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("첫 승차 · " + boardingName.ifBlank { "위치 확인 필요" },
                style = MaterialTheme.typography.bodySmall)
            if (selected && detail.isNotBlank()) Text(detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(guidanceLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AutomaticGuidanceCard(
    assessment: AutoGuidanceAssessment,
    serviceLabel: String,
    boardingLabel: String?,
    unknownMessage: String,
    nowEpochMillis: Long,
    firstArrivalAtEpochMillis: Long?,
    nextArrivalAtEpochMillis: Long?,
    arrivalFetchedAtEpochMillis: Long?,
    walkingSeconds: Int?,
    unknownTitle: String = "도착정보 확인 중",
    arrivalInformation: String? = null,
    vehicleLabel: String = "열차",
    movementHint: String = "역 내부 이동과 신호 대기는 확인해 주세요.",
    informationReceived: Boolean = false,
    arrivalLabel: String = "차량 도착",
    arrivalPrecision: ArrivalTimePrecision = ArrivalTimePrecision.SECONDS,
    estimateLabel: String? = null,
    nextArrivalPrecision: ArrivalTimePrecision = arrivalPrecision,
    walkingUnavailableLabel: String = "확인 필요",
    paceStatus: String? = null,
    remainingDistanceMeters: Int? = null,
    arrivalSource: GuidanceArrivalSource = assessment.arrivalSource,
    validatedEta: Boolean? = null,
    refreshError: String? = null,
    providerLabel: String? = null,
    favoriteBusSaved: Boolean = false,
    onToggleFavoriteBus: (() -> Unit)? = null
) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    // Presentation only: the existing assessment/ETA selection remains authoritative.
    val title=if(assessment.status==AutoGuidanceStatus.UNKNOWN) unknownTitle else movementGuidanceTitle(assessment,nowEpochMillis)
    val accent=when(assessment.status) {
        AutoGuidanceStatus.RELAXED -> Color(0xFFB0F6E6)
        AutoGuidanceStatus.WALK -> Color(0xFFACF5BE)
        AutoGuidanceStatus.CAUTION -> Color(0xFFFFD099)
        AutoGuidanceStatus.FAST_WALK -> Color(0xFFFFB5BE)
        AutoGuidanceStatus.NEXT_VEHICLE -> Color(0xFFBEDAFF)
        AutoGuidanceStatus.UNKNOWN -> Color(0xFFCDD5E0)
    }
    val cardColors=when(assessment.status) {
        AutoGuidanceStatus.CAUTION -> listOf(Color(0xFF80511D),Color(0xFF573612))
        AutoGuidanceStatus.FAST_WALK -> listOf(Color(0xFF912C3C),Color(0xFF5D1D2B))
        AutoGuidanceStatus.NEXT_VEHICLE -> listOf(Color(0xFF28597F),Color(0xFF153852))
        AutoGuidanceStatus.UNKNOWN -> listOf(Color(0xFF475569),Color(0xFF293548))
        else -> listOf(Color(0xFF087B70),Color(0xFF064C46))
    }
    val usableEta = validatedEta ?: (firstArrivalAtEpochMillis?.let { it > nowEpochMillis } == true &&
        arrivalFetchedAtEpochMillis?.let { nowEpochMillis-it in 0L..600_000L } == true)
    Surface(modifier = Modifier.fillMaxWidth().testTag("guidance-card"),
        shape = RoundedCornerShape(22.dp), color = Color(0xFF075C53),contentColor = Color.White,
        shadowElevation=3.dp) {
        Column(Modifier.background(Brush.linearGradient(cardColors))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("이동가이드",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
                if (onToggleFavoriteBus != null) TextButton(
                    onClick = onToggleFavoriteBus,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    AppIcon(R.drawable.ui_star, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (favoriteBusSaved) "자주 타는 버스 해제" else "자주 타는 버스 등록",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(title,Modifier.semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.ExtraBold,color=accent)
            if(assessment.status==AutoGuidanceStatus.FAST_WALK) Text("주변 안전을 먼저 확인해 주세요. 탑승을 보장하는 안내는 아니에요.",style=MaterialTheme.typography.labelSmall)
            if(assessment.status==AutoGuidanceStatus.NEXT_VEHICLE) {
                Text("현재 차량 · 탑승 어려움",style=MaterialTheme.typography.labelSmall)
                if(assessment.recommendedArrivalAtEpochMillis==null) Text(
                    if(assessment.nextVehicleState==NextVehicleState.ETA_PENDING) "다음 차량 · 공식 도착정보 조회 중" else nextVehicleGuidanceText(assessment,nowEpochMillis),
                    style=MaterialTheme.typography.labelSmall)
            }
            Text(listOfNotNull(serviceLabel.takeIf { it.isNotBlank() },boardingLabel).joinToString(" · "),
                style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val recommended=assessment.recommendedArrivalAtEpochMillis?.takeIf {
                    assessment.status==AutoGuidanceStatus.NEXT_VEHICLE && assessment.nextVehicleState==NextVehicleState.ETA_AVAILABLE && it>nowEpochMillis }
                val metricVehicle=if(assessment.status==AutoGuidanceStatus.NEXT_VEHICLE) (if(recommended!=null) "다음 " else "현재 ")+vehicleLabel else vehicleLabel
                val arrival = recommended?.let { arrivalText(it,nowEpochMillis,assessment.recommendedArrivalPrecision) }
                    ?: arrivalInformation ?: arrivalText(firstArrivalAtEpochMillis,nowEpochMillis,arrivalPrecision)
                val walk = walkingSeconds?.let { compactDuration(it.toLong()) } ?: walkingUnavailableLabel
                val compact = maxWidth / LocalDensity.current.fontScale < 235.dp
                if (compact) Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    GuidanceMetric(if(vehicleLabel=="버스") R.drawable.ui_bus else R.drawable.ui_train,"$metricVehicle $arrival",Modifier.fillMaxWidth())
                    GuidanceMetric(R.drawable.ui_walk,"도보 $walk",Modifier.fillMaxWidth())
                } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    GuidanceMetric(if(vehicleLabel=="버스") R.drawable.ui_bus else R.drawable.ui_train,"$metricVehicle $arrival",Modifier.weight(1f))
                    GuidanceMetric(R.drawable.ui_walk,"도보 $walk",Modifier.weight(1f))
                }
            }
            ArrivalComparisonCard(assessment,nowEpochMillis,firstArrivalAtEpochMillis.takeIf { usableEta },walkingSeconds,
                arrivalSource,detailsExpanded) { detailsExpanded=!detailsExpanded }
            if(!usableEta || walkingSeconds==null) TextButton(onClick={detailsExpanded=!detailsExpanded},
                colors=androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor=Color.White)) {
                Text(if(detailsExpanded) "상세정보 접기" else "상세정보")
            }
            if(detailsExpanded) Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(arrivalSourceLabel(arrivalSource),style=MaterialTheme.typography.labelSmall)
                if(arrivalSource==GuidanceArrivalSource.OFFICIAL_REALTIME && usableEta)
                    Text("남은 시간은 수신 예정시각에 경과시간을 반영해요.",style=MaterialTheme.typography.labelSmall)
                providerLabel?.let { Text("제공기관 · $it",style=MaterialTheme.typography.labelSmall) }
                refreshError?.let { Text("최근 갱신 오류 · $it",style=MaterialTheme.typography.bodySmall,color=Color(0xFFFFD0A1)) }
                if(assessment.status==AutoGuidanceStatus.NEXT_VEHICLE)
                    Text(nextVehicleGuidanceText(assessment,nowEpochMillis),style=MaterialTheme.typography.bodySmall)
                else nextArrivalAtEpochMillis?.let { Text("다음 $vehicleLabel · ${arrivalText(it,nowEpochMillis,nextArrivalPrecision)}",style=MaterialTheme.typography.bodySmall) }
                remainingDistanceMeters?.let { Text("승차점까지 ${it}m",style=MaterialTheme.typography.bodySmall) }
                paceStatus?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                if(assessment.status==AutoGuidanceStatus.UNKNOWN && unknownMessage.isNotBlank())
                    Text(unknownMessage,style=MaterialTheme.typography.bodySmall)
                Text("승차 준비 ${settingsBufferLabel(assessment.bufferSeconds)} 포함 · $movementHint",style=MaterialTheme.typography.bodySmall)
                arrivalFetchedAtEpochMillis?.let { Text("최근 정보 · ${((nowEpochMillis-it)/1000).coerceAtLeast(0)}초 전",style=MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
internal fun AppIcon(@DrawableRes resource: Int,description: String?=null,modifier: Modifier=Modifier.size(24.dp),tint: Color=androidx.compose.material3.LocalContentColor.current) {
    Icon(painterResource(resource),description,modifier,tint=tint)
}

@Composable
private fun GuidanceMetric(@DrawableRes icon:Int,value:String,modifier:Modifier) {
    val walking=icon==R.drawable.ui_walk
    val background=if(walking) Color(0xFFDDF8ED) else Color(0xFFFFF0D8)
    val foreground=if(walking) Color(0xFF075F46) else Color(0xFF704311)
    Surface(modifier=modifier.heightIn(min=54.dp),color=background,contentColor=foreground,shape=RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            AppIcon(icon,modifier=Modifier.size(20.dp))
            Text(value,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
        }
    }
}

private fun arrivalText(arrival: Long?, now: Long, precision: ArrivalTimePrecision = ArrivalTimePrecision.SECONDS): String = when {
    arrival == null -> "확인 중"
    arrival <= now -> "갱신 중"
    precision == ArrivalTimePrecision.MINUTES -> "약 " + ((arrival - now + 59_999L) / 60_000L).coerceAtLeast(1) + "분 후"
    else -> "${durationText(ceil((arrival - now) / 1000.0).toLong())} 후"
}

@Composable
fun LoadingStateCard(message: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)
        .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp,
            modifier = Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                .padding(2.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun EmptyStateCard(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NoticeCard(message: String, isError: Boolean = false) {
    Surface(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(message, modifier = Modifier.padding(horizontal=16.dp,vertical=12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun durationText(seconds: Long): String = if (seconds < 60) "${seconds}초"
    else "${seconds / 60}분 ${seconds % 60}초"

internal fun arrivalSourceLabel(source: GuidanceArrivalSource): String = when(source) {
    GuidanceArrivalSource.OFFICIAL_REALTIME -> "공식 실시간"
    GuidanceArrivalSource.OFFICIAL_TIMETABLE_POSITION -> "열차 위치 · 시간표 추정"
    GuidanceArrivalSource.OFFICIAL_TIMETABLE_SCHEDULE -> "시간표 예정"
}
private fun compactDuration(seconds: Long): String = if(seconds<60) "${seconds.coerceAtLeast(0)}초" else "약 ${(seconds+30)/60}분"

@Composable
internal fun ArrivalComparisonCard(assessment: AutoGuidanceAssessment,now: Long,vehicleAt: Long?,walkSeconds: Int?,
    source: GuidanceArrivalSource = assessment.arrivalSource,expanded:Boolean=false,onExpand:()->Unit={}) {
    if(vehicleAt == null || vehicleAt <= now || walkSeconds == null || walkSeconds < 0) return
    val slackMillis=vehicleAt-now-(walkSeconds.toLong()+assessment.bufferSeconds)*1000L
    val slackMagnitude=kotlin.math.abs(slackMillis)
    val slackDuration=if(slackMagnitude in 1L..999L) "1초 미만" else compactDuration((slackMagnitude+999L)/1000L)
    val warning=if(slackMillis<=0) Color(0xFFFFD0A1) else Color.White
    Surface(onClick=onExpand,color=Color.White.copy(alpha=.08f),shape=RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=12.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            AppIcon(R.drawable.ui_clock,tint=warning,modifier=Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text((if(assessment.status==AutoGuidanceStatus.NEXT_VEHICLE) "현재 차량 · " else "")+
                    if(slackMillis>=0) "이동 여유 $slackDuration" else "이동시간 $slackDuration 부족",
                    style=MaterialTheme.typography.labelLarge,color=warning)
                Text(arrivalSourceLabel(source),style=MaterialTheme.typography.labelSmall,color=Color.White.copy(alpha=.75f))
            }
            AppIcon(R.drawable.ui_chevron,if(expanded) "상세정보 접기" else "상세정보 펼치기",Modifier.size(20.dp))
        }
    }
    if(expanded) {
        val formatter=java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        fun clock(value: Long)=formatter.format(java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault()))
        Text("차량 예정 ${clock(vehicleAt)} · 내 도착 ${clock(now+walkSeconds*1000L)}",style=MaterialTheme.typography.bodySmall)
    }
}

/** The production map is always supplied by KakaoMapPanel; this only frames its controls. */
@Composable
internal fun JourneyMapCard(expanded: Boolean,onToggle:()->Unit,content:@Composable ()->Unit) {
    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min=40.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("이동 경로",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                TextButton(onClick=onToggle) { Text(if(expanded) "지도 줄이기" else "지도 펼치기",style=MaterialTheme.typography.labelSmall) }
            }
            content()
        }
    }
}

@Composable
internal fun JourneyMetrics(speed: Double?,distance: Int?,distanceStatus:String="확인 중") {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("journey-metrics")) {
        val cards: @Composable (Modifier,Boolean)->Unit = { modifier,isSpeed ->
            Surface(modifier=modifier,shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(if(isSpeed) "현재 이동속도" else "승차점까지",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(isSpeed) AppIcon(R.drawable.ui_pin,if(speed==null) "GPS 속도 정보 없음. 도보 계산에는 기본 설정을 사용합니다." else "유효한 GPS 속도 수신",
                            Modifier.size(12.dp),tint=if(speed==null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    AppIcon(if(isSpeed) R.drawable.ui_walk else R.drawable.ui_pin,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicText(if(isSpeed) speed?.let { String.format(java.util.Locale.KOREA,"%.1f km/h",it*3.6) } ?: "0.0 km/h"
                        else distance?.let { "${it}m" } ?: distanceStatus,
                        modifier=Modifier.weight(1f),maxLines=1,softWrap=false,
                        style=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.Bold,color=androidx.compose.material3.LocalContentColor.current),
                        autoSize=TextAutoSize.StepBased(minFontSize=14.sp,maxFontSize=22.sp,stepSize=1.sp))
                    }
                }
            }
        }
        if(maxWidth / LocalDensity.current.fontScale < 280.dp) Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            cards(Modifier.fillMaxWidth(),true);cards(Modifier.fillMaxWidth(),false)
        } else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { cards(Modifier.weight(1f),true);cards(Modifier.weight(1f),false) }
    }
}

/** Consistent touch targets for secondary route/location actions; callbacks stay with the screen. */
@Composable
internal fun JourneyActionButton(label: String,@DrawableRes icon: Int,onClick: () -> Unit,enabled: Boolean=true) {
    androidx.compose.material3.FilledTonalButton(onClick=onClick,enabled=enabled,
        modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp),
        contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)) {
        AppIcon(icon,modifier=Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label,style=MaterialTheme.typography.labelLarge,textAlign=TextAlign.Center)
    }
}
