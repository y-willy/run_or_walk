package com.example.run_or_walk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneySettingsPanel(settings: JourneySettings, onSettingsChange: (JourneySettings) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text("설정")
        }
        if (expanded) ModalBottomSheet(onDismissRequest={ expanded=false },containerColor=MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            SectionCard("내 걸음속도", "변경사항은 저장되고 이동 안내에도 반영돼요.") {
                Text("평소 걸음속도", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("느린 편", "보통", "빠른 편").forEachIndexed { index, label ->
                        val preset = JourneySettings.PRESET_SPEED_KMH[index]
                        OutlinedButton(onClick = { onSettingsChange(settings.copy(
                            useProviderWalkingTime = false, speedKmh = preset)) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(if (kotlin.math.abs(settings.speedKmh - preset) < 0.01 &&
                                !settings.useProviderWalkingTime) "✓ $label" else label)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("GPS 자동 보정", style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("정확한 이동 속도가 측정되면 반영해요.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.gpsAutoCalibrationEnabled,
                        onCheckedChange = { onSettingsChange(settings.copy(gpsAutoCalibrationEnabled = it)) },
                        modifier = Modifier.semantics { contentDescription = "GPS 걸음속도 자동 보정" })
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "세부 설정 접기" else "안전 여유·기존 지도 시간 설정")
                }
                if (advanced) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("지도 도보시간 우선", modifier = Modifier.weight(1f))
                        Switch(checked = settings.useProviderWalkingTime,
                            onCheckedChange = { onSettingsChange(settings.copy(useProviderWalkingTime = it)) })
                    }
                    SettingStepper("안전 여유시간", settingsBufferLabel(settings.bufferSeconds),
                        canDecrease = settings.bufferSeconds > JourneySettings.MIN_BUFFER_SECONDS,
                        canIncrease = settings.bufferSeconds < JourneySettings.MAX_BUFFER_SECONDS,
                        onDecrease = { onSettingsChange(settings.copy(bufferSeconds = settings.bufferSeconds - 30)) },
                        onIncrease = { onSettingsChange(settings.copy(bufferSeconds = settings.bufferSeconds + 30)) })
                }
            }
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
