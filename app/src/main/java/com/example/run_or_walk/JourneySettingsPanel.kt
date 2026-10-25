package com.example.run_or_walk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun JourneySettingsPanel(settings: JourneySettings, onSettingsChange: (JourneySettings) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (expanded) "보행·여유시간 설정 접기" else "보행·여유시간 설정")
        }
        if (expanded) {
            SectionCard("내 보행·안전 여유", "설정은 저장되고 현재 이동 안내에도 반영돼요.") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("지도 기본 도보시간", style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(if (settings.useProviderWalkingTime) "지도에서 받은 예상시간을 사용해요."
                            else "실제 경로 거리와 내 보행 속도로 추정해요.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.useProviderWalkingTime,
                        onCheckedChange = { onSettingsChange(settings.copy(useProviderWalkingTime = it)) },
                        modifier = Modifier.semantics { contentDescription = "지도 기본 도보시간 사용" })
                }
                if (!settings.useProviderWalkingTime) {
                    SettingStepper("내 보행 속도", String.format(Locale.KOREA, "%.1f km/h", settings.speedKmh),
                        canDecrease = settings.speedKmh > JourneySettings.MIN_SPEED_KMH,
                        canIncrease = settings.speedKmh < JourneySettings.MAX_SPEED_KMH,
                        onDecrease = { onSettingsChange(settings.copy(speedKmh = settings.speedKmh - .5)) },
                        onIncrease = { onSettingsChange(settings.copy(speedKmh = settings.speedKmh + .5)) })
                    NoticeCard("2.0~7.0 km/h 사이의 보행 추정이에요. 경로 거리 정보가 없으면 지도 시간을 사용해요. 신호 대기·계단·역 내부 이동은 아래 안전 여유에 포함해 주세요.")
                }
                SettingStepper("안전 여유시간", settingsBufferLabel(settings.bufferSeconds),
                    canDecrease = settings.bufferSeconds > JourneySettings.MIN_BUFFER_SECONDS,
                    canIncrease = settings.bufferSeconds < JourneySettings.MAX_BUFFER_SECONDS,
                    onDecrease = { onSettingsChange(settings.copy(bufferSeconds = settings.bufferSeconds - 30)) },
                    onIncrease = { onSettingsChange(settings.copy(bufferSeconds = settings.bufferSeconds + 30)) })
                NoticeCard("30초~10분을 30초 단위로 설정해요. 평소 이동 속도와 승강장까지 거리를 고려해 주세요.")
            }
        }
    }
}

@Composable
private fun SettingStepper(label: String, value: String, canDecrease: Boolean, canIncrease: Boolean,
    onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDecrease, enabled = canDecrease,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label 줄이기" }) {
                Text("−")
            }
            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onIncrease, enabled = canIncrease,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label 늘리기" }) {
                Text("+")
            }
        }
    }
}

internal fun settingsBufferLabel(seconds: Int): String = when {
    seconds < 60 -> "${seconds}초"
    seconds % 60 == 0 -> "${seconds / 60}분"
    else -> "${seconds / 60}분 ${seconds % 60}초"
}
