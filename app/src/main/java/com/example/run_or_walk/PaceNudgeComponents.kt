package com.example.run_or_walk

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.run_or_walk.ui.theme.PaceMint
import com.example.run_or_walk.ui.theme.PaceNavy
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import java.util.Locale

/** Small, shared building blocks. They display supplied API/state values only. */
@Composable
fun PaceNudgeHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                .background(PaceNavy),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(28.dp)) {
                val path = Path().apply {
                    moveTo(size.width * .16f, size.height * .78f)
                    lineTo(size.width * .45f, size.height * .22f)
                    lineTo(size.width * .69f, size.height * .22f)
                    quadraticTo(size.width * .96f, size.height * .22f,
                        size.width * .82f, size.height * .48f)
                    lineTo(size.width * .61f, size.height * .48f)
                }
                drawPath(path, PaceMint, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(Color.White, 2.dp.toPx(), Offset(size.width * .66f, size.height * .77f))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("PaceNudge", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Text("지금, 나에게 맞는 이동 속도", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FeatureModeSelector(selectedIndex: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
    ) {
        Row(
            Modifier.fillMaxWidth().selectableGroup().padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf("경로로 찾기", "정류장 찾기").forEachIndexed { index, title ->
                val chosen = index == selectedIndex
                val background by animateColorAsState(
                    if (chosen) PaceMint else Color.Transparent, tween(220), label = "modeBackground"
                )
                val foreground by animateColorAsState(
                    if (chosen) PaceNavy else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(220), label = "modeForeground"
                )
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .background(background)
                        .selectable(chosen, enabled = enabled, role = Role.Tab,
                            onClick = { onSelect(index) })
                        .semantics { contentDescription = "기능 ${index + 1} · $title" }
                        .defaultMinSize(minHeight = 64.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("기능 ${index + 1}", style = MaterialTheme.typography.labelSmall,
                        color = foreground)
                    Text(title, style = MaterialTheme.typography.labelLarge,
                        color = foreground, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                if (!subtitle.isNullOrBlank()) Text(subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun PlaceOptionCard(
    title: String,
    detail: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        tween(200), label = "placeSelection"
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp),
        shape = RoundedCornerShape(16.dp), color = tint,
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f),
        border = BorderStroke(1.dp,
            if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(if (selected) "✓" else "↗", color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics {
                        contentDescription = if (selected) "선택됨" else "장소 선택"
                    })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun TransitRouteCard(
    durationMinutes: Int,
    serviceName: String,
    boardingName: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit
) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
        tween(220), label = "routeBorder"
    )
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f)
            else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("예상 소요시간", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${durationMinutes}분", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold)
                }
                if (selected) StatusPill("선택한 경로", MaterialTheme.colorScheme.secondary)
            }
            Text(serviceName.ifBlank { "노선 정보 없음" }, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("첫 승차 · ${boardingName.ifBlank { "승차 장소 확인 필요" }}",
                style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onSelect, enabled = enabled,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
                Text(if (selected) "선택한 경로 · 다시 확인" else "이 경로로 이동")
            }
        }
    }
}

@Composable
fun BoardingStopCard(name: String, detail: String, notice: String? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = .3f))
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("선택한 승차 지점", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary)
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!notice.isNullOrBlank()) NoticeCard(notice)
        }
    }
}

@Composable
fun WalkingSummaryCard(route: WalkRoute?, loading: Boolean) {
    when {
        loading -> LoadingStateCard("현재 위치에서 도보 경로를 확인하고 있어요")
        route == null -> EmptyStateCard("승차 지점까지 얼마나 걸릴까요?", "장소를 선택한 뒤 도보 경로를 확인해 주세요.")
        else -> Surface(
            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("예상 도보시간", "${minuteText(route.durationSeconds / 60.0)}분", Modifier.weight(1f))
                Metric("도보 거리", if (route.distanceMeters < 1000) "${route.distanceMeters}m"
                    else String.format(Locale.KOREA, "%.1fkm", route.distanceMeters / 1000.0),
                    Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun UrgencyResultCard(
    urgency: Urgency,
    arrivalMinutes: Double,
    walkingMinutes: Double,
    bufferMinutes: Double,
    slackMinutes: Double,
    vibrationSupported: Boolean
) {
    AnimatedContent(
        targetState = urgency,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "urgencyResult"
    ) { state ->
        val presentation = urgencyPresentation(state)
        val tint by animateColorAsState(presentation.color, tween(220), label = "urgencyColor")
        Surface(
            modifier = Modifier.fillMaxWidth().animateContentSize(tween(220))
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tint.copy(alpha = .4f)), shadowElevation = 2.dp
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusSymbol(state, tint)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("나의 이동 페이스", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusPill(presentation.badge, tint)
                    }
                }
                Text(presentation.message, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(presentation.guidance, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Metric("차량 도착 · 수동 입력", "${minuteText(arrivalMinutes)}분", Modifier.weight(1f))
                            Metric("예상 도보", "${minuteText(walkingMinutes)}분", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Metric("안전 여유시간", "${minuteText(bufferMinutes)}분", Modifier.weight(1f))
                            Metric("승차 준비 후 남는 시간", "${minuteText(slackMinutes)}분", Modifier.weight(1f))
                        }
                    }
                }
                val vibration = when (state) {
                    Urgency.LOW -> 1
                    Urgency.MEDIUM -> 2
                    Urgency.HIGH -> 3
                    else -> 0
                }
                Text(
                    if (vibrationSupported) "진동 안내 · ${vibration}회"
                    else "진동 안내 · ${vibration}회 패턴 · 이 기기는 진동을 지원하지 않아요",
                    style = MaterialTheme.typography.labelLarge, color = tint
                )
            }
        }
    }
}

@Composable
fun LoadingStateCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.secondary)
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EmptyStateCard(title: String, message: String) {
    Surface(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NoticeCard(message: String, isError: Boolean = false) {
    val tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
        .semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isError) "!" else "i", fontWeight = FontWeight.Bold, color = tint,
                modifier = Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = tint,
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusPill(label: String, tint: Color) {
    Surface(color = tint.copy(alpha = .12f), shape = RoundedCornerShape(50)) {
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

private data class UrgencyPresentation(
    val badge: String, val message: String, val guidance: String, val color: Color
)

@Composable
private fun urgencyPresentation(urgency: Urgency): UrgencyPresentation {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (urgency) {
        Urgency.NONE -> UrgencyPresentation("여유 있음", "여유 있어요", "주변을 살피며 편안하게 이동하세요.",
            if (dark) Color(0xFF86EFAC) else Color(0xFF166534))
        Urgency.LOW -> UrgencyPresentation("보통 걸음", "평소 속도로 걸어도 괜찮아요", "안전 여유시간을 포함해 계산했어요.",
            if (dark) Color(0xFF93C5FD) else Color(0xFF1D4ED8))
        Urgency.MEDIUM -> UrgencyPresentation("조금 빠르게", "조금 빠르게 걸어주세요", "횡단보도와 주변 보행자를 확인하세요.",
            if (dark) Color(0xFFFDE68A) else Color(0xFF92400E))
        Urgency.HIGH -> UrgencyPresentation("매우 촉박", "매우 촉박해요", "무리하게 뛰지 말고, 다음 차량도 확인해 주세요.",
            if (dark) Color(0xFFFDBA74) else Color(0xFF9A3412))
        Urgency.MISSED -> UrgencyPresentation("다음 차량 확인", "이번 차량은 탑승이 어려울 수 있어요", "안전을 먼저 생각하고 다음 차량을 확인해 주세요.",
            if (dark) Color(0xFFFDA4AF) else Color(0xFF9F1239))
    }
}

@Composable
private fun StatusSymbol(state: Urgency, tint: Color) {
    Canvas(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = .1f)).padding(11.dp)) {
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        when (state) {
            Urgency.NONE, Urgency.LOW -> {
                val check = Path().apply {
                    moveTo(size.width * .08f, size.height * .52f)
                    lineTo(size.width * .38f, size.height * .82f)
                    lineTo(size.width * .92f, size.height * .2f)
                }
                drawPath(check, tint, style = stroke)
            }
            Urgency.MEDIUM -> repeat(2) { index ->
                val x = size.width * (.1f + index * .45f)
                drawPath(Path().apply {
                    moveTo(x, size.height * .15f)
                    lineTo(x + size.width * .3f, size.height * .5f)
                    lineTo(x, size.height * .85f)
                }, tint, style = stroke)
            }
            Urgency.HIGH -> {
                drawLine(tint, Offset(size.width * .5f, size.height * .08f),
                    Offset(size.width * .5f, size.height * .58f), stroke.width, StrokeCap.Round)
                drawCircle(tint, 1.5.dp.toPx(), Offset(size.width * .5f, size.height * .86f))
            }
            Urgency.MISSED -> drawLine(tint, Offset(0f, size.height * .5f),
                Offset(size.width, size.height * .5f), stroke.width, StrokeCap.Round)
        }
    }
}

private fun minuteText(value: Double): String = String.format(Locale.KOREA, "%.1f", value)

@Preview(name = "미리보기 · 작은 화면", widthDp = 320, fontScale = 1.2f, showBackground = true)
@Composable
private fun PaceNudgeComponentsPreview() {
    RUN_or_WALKTheme(darkTheme = false) {
        Column(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PaceNudgeHeader()
            FeatureModeSelector(selectedIndex = 0, onSelect = {})
            UrgencyResultCard(Urgency.MEDIUM, 8.0, 5.5, 2.0, .5, true)
        }
    }
}

@Preview(name = "미리보기 · 다크 모드", widthDp = 360, showBackground = true)
@Composable
private fun PaceNudgeDarkResultPreview() {
    RUN_or_WALKTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            UrgencyResultCard(Urgency.HIGH, 4.0, 3.0, 2.0, -1.0, false)
        }
    }
}
