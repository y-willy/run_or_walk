package com.example.run_or_walk

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
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
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("RUN_or_WALK", style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-.7).sp),
            fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
        Text("도착에 맞춰, 가볍게 이동하세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FeatureModeSelector(selectedIndex: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Surface(shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("경로 찾기", "정류장 찾기").forEachIndexed { index, title ->
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
                    Text(title, style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                         modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp))
                }
            }
        }
    }
}

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f))) {
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
    unknownTitle: String = "자동 판단을 준비하고 있어요",
    arrivalInformation: String? = null,
    vehicleLabel: String = "열차",
    movementHint: String = "역 내부 이동과 신호 대기는 확인해 주세요.",
    informationReceived: Boolean = false,
    arrivalLabel: String = "차량 도착",
    arrivalPrecision: ArrivalTimePrecision = ArrivalTimePrecision.SECONDS,
    estimateLabel: String? = null,
    nextArrivalPrecision: ArrivalTimePrecision = arrivalPrecision,
    walkingUnavailableLabel: String = "확인 필요",
    paceStatus: String? = null
) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val title = movementGuidanceTitle(assessment,nowEpochMillis)
    val accent = when (assessment.status) {
        AutoGuidanceStatus.WALK -> Color(0xFF77E4CC)
        AutoGuidanceStatus.FAST_WALK -> Color(0xFFFFD28D)
        AutoGuidanceStatus.NEXT_VEHICLE -> Color(0xFFFFB5BA)
        AutoGuidanceStatus.UNKNOWN -> Color(0xFFCAD5E3)
    }
    val muted = Color(0xFFB5C4D5)
    Surface(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(26.dp), color = Color(0xFF122437),contentColor = Color.White) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                Text("지금 이동 안내",style=MaterialTheme.typography.labelMedium,color=muted)
                Surface(color=accent.copy(alpha=.12f),shape=RoundedCornerShape(50)) {
                    Text(if (assessment.status==AutoGuidanceStatus.UNKNOWN) "확인 중" else "이동 가이드",
                        Modifier.padding(horizontal=10.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=accent)
                }
            }
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=accent)
                if (serviceLabel.isNotBlank()) Text(serviceLabel,style=MaterialTheme.typography.bodyMedium,color=Color.White)
                boardingLabel?.let { Text("승차 · $it",style=MaterialTheme.typography.bodySmall,color=muted) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val arrival = arrivalInformation ?: arrivalText(firstArrivalAtEpochMillis,nowEpochMillis,arrivalPrecision)
                val walk = walkingSeconds?.let { durationText(it.toLong()) } ?: walkingUnavailableLabel
                if (maxWidth / LocalDensity.current.fontScale < 280.dp) {
                    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        GuidanceMetric("도착까지",arrival,Modifier.fillMaxWidth(),accent)
                        GuidanceMetric("승차점까지 도보",walk,Modifier.fillMaxWidth(),Color.White)
                    }
                } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    GuidanceMetric("도착까지",arrival,Modifier.weight(1f).fillMaxHeight(),accent)
                    GuidanceMetric("승차점까지 도보",walk,Modifier.weight(1f).fillMaxHeight(),Color.White)
                }
            }
            if (assessment.status==AutoGuidanceStatus.NEXT_VEHICLE) {
                Text(nextVehicleGuidanceText(assessment,nowEpochMillis),style=MaterialTheme.typography.bodySmall,color=muted)
            } else nextArrivalAtEpochMillis?.let {
                Text("다음 $vehicleLabel · ${arrivalText(it,nowEpochMillis,nextArrivalPrecision)}",style=MaterialTheme.typography.bodySmall,color=muted)
            }
            TextButton(onClick={ detailsExpanded=!detailsExpanded },
                modifier=Modifier.defaultMinSize(minHeight=48.dp),
                colors=androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor=muted),
                contentPadding=androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if(detailsExpanded) "상세정보 접기  −" else "상세정보  +")
            }
            if(detailsExpanded) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    paceStatus?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=muted) }
                    if(assessment.status==AutoGuidanceStatus.UNKNOWN && unknownMessage.isNotBlank())
                        Text(unknownMessage,style=MaterialTheme.typography.bodySmall,color=muted)
                    if(assessment.status!=AutoGuidanceStatus.UNKNOWN)
                        Text("여유 ${settingsBufferLabel(assessment.bufferSeconds)} 포함 · $movementHint",style=MaterialTheme.typography.bodySmall,color=muted)
                    arrivalFetchedAtEpochMillis?.let {
                        Text("최근 정보 · ${((nowEpochMillis-it)/1000).coerceAtLeast(0)}초 전",style=MaterialTheme.typography.labelSmall,color=muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidanceMetric(label:String,value:String,modifier:Modifier,color:Color) {
    Surface(modifier=modifier.heightIn(min=100.dp),color=Color.White.copy(alpha=.07f),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(label,style=MaterialTheme.typography.labelSmall,color=Color(0xFFB5C4D5))
            Text(value,style=if (value.contains('\n') || value.length>10) MaterialTheme.typography.bodyLarge
                else MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold,color=color)
        }
    }
}

private fun arrivalText(arrival: Long?, now: Long, precision: ArrivalTimePrecision = ArrivalTimePrecision.SECONDS): String = when {
    arrival == null -> "정보 없음"
    arrival <= now -> "도착 시각 경과 · 갱신 필요"
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
