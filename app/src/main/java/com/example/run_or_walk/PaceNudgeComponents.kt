package com.example.run_or_walk

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.run_or_walk.ui.theme.PaceMint
import com.example.run_or_walk.ui.theme.PaceNavy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

@Composable
fun PaceNudgeHeader() {
    Text("PaceNudge", style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
fun FeatureModeSelector(selectedIndex: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Surface(shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("경로로 찾기", "정류장 찾기").forEachIndexed { index, title ->
                val selected = selectedIndex == index
                val background by animateColorAsState(
                    if (selected) PaceMint else Color.Transparent, label = "tabColor")
                Surface(
                    color = background, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                        .selectable(selected, enabled = enabled, role = Role.Tab,
                            onClick = { onSelect(index) })
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) PaceNavy else MaterialTheme.colorScheme.onSurfaceVariant,
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
    guidanceLabel: String = "추정 · 실시간 미연결", onSelect: () -> Unit) {
    Surface(onClick = onSelect, enabled = enabled,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)
            else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${durationMinutes}분 · ${serviceName.ifBlank { "노선 확인 필요" }}",
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text("승차: ${boardingName.ifBlank { "위치 확인 필요" }}",
                style = MaterialTheme.typography.bodySmall)
            Text(guidanceLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected && detail.isNotBlank()) Text(detail,
                style = MaterialTheme.typography.bodySmall,
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
    arrivalInformation: String? = null
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val (symbol, title, tint) = when (assessment.status) {
        AutoGuidanceStatus.WALK -> Triple("✓", "걸어가세요", if (dark) Color(0xFF86EFAC) else Color(0xFF23734C))
        AutoGuidanceStatus.FAST_WALK -> Triple("»", "빠른 걸음으로 이동하세요", if (dark) Color(0xFFFDBA74) else Color(0xFF9C5800))
        AutoGuidanceStatus.NEXT_TRAIN -> Triple("→", "확인된 다음 열차를 이용하세요", MaterialTheme.colorScheme.error)
        AutoGuidanceStatus.UNKNOWN -> Triple("", unknownTitle, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tint.copy(alpha = .6f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (serviceLabel.isNotBlank()) Text(serviceLabel,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            boardingLabel?.let { Text("승차: $it", style = MaterialTheme.typography.bodySmall) }
            Text(listOf(symbol, title).filter(String::isNotBlank).joinToString(" "), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = tint)
            if (assessment.status == AutoGuidanceStatus.UNKNOWN) Text(unknownMessage,
                style = MaterialTheme.typography.bodyMedium)
            Text("차량 도착: ${arrivalInformation ?: arrivalText(firstArrivalAtEpochMillis, nowEpochMillis)}",
                style = MaterialTheme.typography.bodyMedium)
            walkingSeconds?.let {
                Text("승차 지점까지: 도보 ${durationText(it.toLong())}",
                    style = MaterialTheme.typography.bodyMedium)
            }
            nextArrivalAtEpochMillis?.let {
                Text("다음 차량: ${arrivalText(it, nowEpochMillis)}",
                    style = MaterialTheme.typography.bodySmall)
            }
            arrivalFetchedAtEpochMillis?.let {
                val age = ((nowEpochMillis - it) / 1000L).coerceAtLeast(0)
                Text("마지막 정보 갱신: ${age}초 전", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (assessment.status != AutoGuidanceStatus.UNKNOWN) {
                Text("안전 여유 ${assessment.bufferSeconds / 60}분 포함 · 역 내부 이동과 신호 대기는 확인해 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (assessment.status == AutoGuidanceStatus.FAST_WALK) {
                Text("빠른 걸음 ${durationText((assessment.fastTravelSeconds ?: 0).toLong())} · 추정 " +
                    "(도보시간의 ${(assessment.fastTravelRatio * 100).toInt()}%). 신호와 안전한 보행을 우선하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun arrivalText(arrival: Long?, now: Long): String = when {
    arrival == null -> "정보 없음"
    arrival <= now -> "도착 시각 경과 · 갱신 필요"
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
    Text(message, modifier = Modifier.fillMaxWidth()
        .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun durationText(seconds: Long): String = if (seconds < 60) "${seconds}초"
    else "${seconds / 60}분 ${seconds % 60}초"
