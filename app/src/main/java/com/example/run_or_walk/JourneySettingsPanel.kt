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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Inline settings: the containing settings tab owns scrolling, without nested sheets. */
@Composable
fun JourneySettingsPanel(settings: JourneySettings, onWalkingAnalysis: () -> Unit = {}, onSettingsChange: (JourneySettings) -> Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionCard("내 걸음속도","변경사항은 저장되고 이동 안내에도 반영돼요.") {
            Text("평소 걸음속도",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("느린 편","보통","빠른 편").forEachIndexed {index,label ->
                    val preset=JourneySettings.PRESET_SPEED_KMH[index]
                    OutlinedButton(onClick={onSettingsChange(settings.copy(useProviderWalkingTime=false,
                        usePersonalWalkingSpeed=false,speedKmh=preset))},
                        modifier=Modifier.weight(1f).heightIn(min=48.dp),
                        contentPadding=androidx.compose.foundation.layout.PaddingValues(horizontal=6.dp)) {
                        Text(if(kotlin.math.abs(settings.speedKmh-preset)<0.01 && !settings.useProviderWalkingTime &&
                            !settings.usePersonalWalkingSpeed) "✓ $label" else label,
                            style=MaterialTheme.typography.labelSmall,maxLines=1)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("GPS 자동 보정",style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold)
                    Text("내 걸음속도를 기본으로 정확한 GPS 속도를 참고해요. 정지·수신 불안정 시 설정 속도를 사용해요.",style=MaterialTheme.typography.bodySmall)
                }
                Switch(checked=settings.gpsAutoCalibrationEnabled,
                    onCheckedChange={onSettingsChange(settings.copy(gpsAutoCalibrationEnabled=it))},
                    modifier=Modifier.semantics {contentDescription="GPS 걸음속도 자동 보정"})
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("내 걷기 속도 적용")
                    if(settings.personalWalkingSpeedKmh==null) Text("내 걷기 분석을 저장하면 사용할 수 있어요.",style=MaterialTheme.typography.bodySmall)
                }
                Switch(checked=settings.usePersonalWalkingSpeed,enabled=settings.personalWalkingSpeedKmh!=null,
                    onCheckedChange={onSettingsChange(settings.copy(usePersonalWalkingSpeed=it))},
                    modifier=Modifier.semantics {contentDescription="분석한 내 걷기 속도 사용"})
            }
            TextButton(onClick=onWalkingAnalysis) {Text("내 걷기 분석")}
        }
        SectionCard("도보시간과 안전 여유","지도 시간 또는 내 걸음속도에 승차 준비시간을 반영해요.") {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("지도 도보시간 우선",modifier=Modifier.weight(1f))
                Switch(checked=settings.useProviderWalkingTime,
                    onCheckedChange={onSettingsChange(settings.copy(useProviderWalkingTime=it,
                        usePersonalWalkingSpeed=if(it) false else settings.usePersonalWalkingSpeed))},
                    modifier=Modifier.semantics {contentDescription="지도 도보시간 우선"})
            }
            SettingStepper("안전 여유시간",settingsBufferLabel(settings.bufferSeconds),
                canDecrease=settings.bufferSeconds>JourneySettings.MIN_BUFFER_SECONDS,
                canIncrease=settings.bufferSeconds<JourneySettings.MAX_BUFFER_SECONDS,
                onDecrease={onSettingsChange(settings.copy(bufferSeconds=settings.bufferSeconds-30))},
                onIncrease={onSettingsChange(settings.copy(bufferSeconds=settings.bufferSeconds+30))})
        }
        SectionCard("달리기 속도","직접 측정한 값이 있다면 저장하세요. 이동 판단 기준은 유지돼요.") {
            var runningText by androidx.compose.runtime.remember(settings.measuredRunningSpeedKmh) {
                mutableStateOf(settings.measuredRunningSpeedKmh?.toString().orEmpty())
            }
            androidx.compose.material3.OutlinedTextField(value=runningText,onValueChange={runningText=it},
                label={Text("직접 측정한 달리기 속도 (km/h · 선택)")},singleLine=true,
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),
                supportingText={Text("3~12 km/h. 입력하지 않아도 기존 이동 안내를 사용할 수 있어요.")},modifier=Modifier.fillMaxWidth())
            val runningValue=runningText.toDoubleOrNull()?.takeIf {it.isFinite() && it in 3.0..12.0}
            TextButton(onClick={onSettingsChange(settings.copy(measuredRunningSpeedKmh=runningValue))},
                enabled=runningText.isBlank() || runningValue!=null) {Text("달리기 속도 저장")}
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
