package com.example.run_or_walk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Official stop/route selection stays separate from a Kakao place and its estimated itinerary. */
@Composable
internal fun SeoulBusSelectionPanel(
    screen: JourneyScreenState,
    nowEpochMillis: Long,
    onQueryChange: (String) -> Unit,
    onSearchStops: () -> Unit,
    onNearbyStops: () -> Unit,
    onChooseStop: (OfficialSeoulBusStop) -> Unit,
    onChooseRoute: (OfficialSeoulBusRoute) -> Unit,
    onRefresh: () -> Unit
) {
    val enabled = !screen.busy && !screen.liveLoading
    var changingStop by remember(screen.selectedBusStop?.stopId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("서울 버스 · 서울 소속 광역버스", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold)
        if (screen.selectedBusStop != null && !changingStop) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                val stop = requireNotNull(screen.selectedBusStop)
                Text("${stop.name} · ${seoulBusArsLabel(stop.arsId)}",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { changingStop = true }, enabled = enabled) { Text("정류장 변경") }
            }
        } else {
            NoticeCard("같은 이름의 맞은편 정류장이 있을 수 있어요. 정류소 번호와 노선의 방면을 확인해 주세요.")
            OutlinedTextField(
                value = screen.busStopQuery,
                onValueChange = onQueryChange,
                label = { Text("공식 정류장 이름 또는 ARS 번호") },
                placeholder = { Text("예: 서울역버스환승센터 또는 02004") },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (enabled && screen.busStopQuery.isNotBlank()) onSearchStops()
                }),
                trailingIcon = {
                    TextButton(onClick = onSearchStops,
                        enabled = enabled && screen.busStopQuery.isNotBlank()) { Text("찾기") }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = onNearbyStops,
                enabled = enabled && screen.boarding != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("승차 장소 주변 정류장")
            }

            val stops = screen.busStopOptions
            var stopPage by remember(stops) { mutableIntStateOf(0) }
            stops.drop(stopPage * 3).take(3).forEach { stop ->
                key(stop.stopId) {
                    val canChoose = stop.point != null && stop.arsId.matches(Regex("\\d{5}")) &&
                        stop.arsId != "00000"
                    PlaceOptionCard(stop.name,
                        "정류소 ${seoulBusArsLabel(stop.arsId)}" +
                            if (canChoose) " · 승차 방향을 노선에서 확인" else " · 위치 정보 확인 필요",
                        selected = screen.selectedBusStop?.stopId == stop.stopId,
                        enabled = enabled && canChoose) {
                            changingStop = false
                            onChooseStop(stop)
                        }
                }
            }
            BusOptionPages(stopPage, stops.size, onPage = { stopPage = it })
            if (screen.busStopsSearched && stops.isEmpty() && !screen.liveLoading) {
                NoticeCard("일치하는 공식 정류장이 없어요. 정류소 이름이나 5자리 ARS 번호로 다시 찾아 주세요.")
            }
        }

        screen.selectedBusStop?.let { stop ->
            Text("탑승할 노선·방면", style = MaterialTheme.typography.labelMedium)
            val routes = screen.busRouteOptions
            if (routes.isEmpty() && !screen.liveLoading) {
                TextButton(onClick = { onChooseStop(stop) }, enabled = enabled) { Text("노선 다시 조회") }
            }
            var routePage by remember(routes) { mutableIntStateOf(0) }
            routes.drop(routePage * 3).take(3).forEach { route ->
                key(route.routeId, route.stopOrder) {
                    val label = if (route.routeType == "6") " · 서울 광역" else ""
                    val detail = if (route.supported) route.direction.ifBlank { "방면 정보 확인 필요" }
                        else "서울 소속 실시간 지원 대상이 아닌 노선이에요."
                    PlaceOptionCard(route.name + label, detail,
                        selected = screen.selectedBusRoute?.identity == route.identity,
                        enabled = enabled && route.supported) { onChooseRoute(route) }
                }
            }
            BusOptionPages(routePage, routes.size, onPage = { routePage = it })
            if (routes.isNotEmpty() && routes.none { it.supported }) {
                NoticeCard("이 정류장의 노선은 현재 지원 범위에 없어요. 경기·인천 소속 버스는 별도 연동이 필요해요.")
            }
        }

        screen.selectedBusRoute?.let { route ->
            Text("${route.name} · ${route.direction}", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            val arrivals = screen.busArrivalOptions.filter { it.identity == route.identity }
            arrivals.take(2).forEachIndexed { index, arrival ->
                key(arrival.identity, arrival.vehicleId, index) {
                    BusArrivalInformation(arrival, index, nowEpochMillis)
                }
            }
            TextButton(onClick = onRefresh, enabled = enabled) { Text("버스 도착정보 새로고침") }
        }
    }
}

@Composable
private fun BusArrivalInformation(arrival: OfficialSeoulBusArrival, index: Int, now: Long) {
    val usable = arrival.isUsableBusArrival(now)
    val arrivalText = if (usable) {
        val seconds = ceil((arrival.snapshot.firstArrivalAtEpochMillis - now) / 1_000.0).toLong()
        if (seconds < 60L) "${seconds}초 후" else "${seconds / 60}분 ${seconds % 60}초 후"
    } else "예정시간 확인 중"
    Surface(shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("도착정보 ${index + 1} · $arrivalText", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Text(seoulBusArrivalDisplayStatus(arrival, now),
                style = MaterialTheme.typography.bodySmall)
            arrival.snapshot.fetchedAtEpochMillis.takeIf { it > 0L && it <= now }?.let {
                Text("원본 제공 ${(now - it) / 1_000L}초 전", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BusOptionPages(page: Int, count: Int, onPage: (Int) -> Unit) {
    if (count <= 3) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Text("이전") }
        Text("${page + 1} / ${(count + 2) / 3}", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { onPage(page + 1) }, enabled = (page + 1) * 3 < count) { Text("다음") }
    }
}

private fun seoulBusArsLabel(arsId: String): String =
    if (arsId.length == 5) "${arsId.take(2)}-${arsId.drop(2)}" else "번호 미제공"
