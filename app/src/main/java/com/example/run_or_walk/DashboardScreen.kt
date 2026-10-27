package com.example.run_or_walk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun DashboardNavigation(selected: Int,onSelect: (Int)->Unit) {
    val compact=LocalConfiguration.current.screenWidthDp<360 || LocalDensity.current.fontScale>1.15f
    NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=2.dp) {
        listOf("경로 찾기","정류장 찾기","자주 타는 버스","설정").forEachIndexed { i,label ->
            NavigationBarItem(modifier=Modifier.semantics { contentDescription=label },selected=selected==i,onClick={onSelect(i)},icon={ Text(listOf("↗","⌕","☆","⚙")[i]) },
                label={ Text(if(compact) listOf("경로","정류장","즐겨찾기","설정")[i] else label,style=MaterialTheme.typography.labelSmall,maxLines=1) })
        }
    }
}
@Composable
internal fun PaceNudgeScreen(lifecycleOwner: LifecycleOwner,session: JourneySessionViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(session.mode.ordinal) }
    var analysis by rememberSaveable { mutableStateOf(false) }
    val context=LocalContext.current
    androidx.activity.compose.BackHandler(enabled=tab>=2 || analysis) { if(analysis) analysis=false else tab=session.mode.ordinal }
    Scaffold(bottomBar={ DashboardNavigation(tab) { selected ->
        analysis=false
        if(selected<2 && session.mode!=FeatureMode.entries[selected]) {
            session.screens.getValue(session.mode).invalidateRequests()
            session.mode=FeatureMode.entries[selected]
        }
        tab=selected
    } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                analysis -> WalkingAnalysisScreen(session.walkingAnalysis,lifecycleOwner,session.settings,{ session.updateSettings(it)
                    JourneyMonitoringController.updateSettings(context,it,JourneyMonitoringController.state.value.request?.vibrationEnabled ?: false) },{analysis=false})
                tab<2 -> JourneyDashboardContent(lifecycleOwner,session)
                tab==2 -> FavoriteBusesScreen(session.favorites,lifecycleOwner) { selected ->
                    JourneyMonitoringController.stop(context)
                    restoreFavoriteBus(session.screens.getValue(FeatureMode.STOP),selected)
                    session.mode=FeatureMode.STOP;tab=1
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    PaceNudgeHeader();Text("설정",style=MaterialTheme.typography.headlineMedium)
                    SectionCard("내 이동 설정","내 속도와 승차 여유시간을 설정하세요.") {
                        JourneySettingsPanel(session.settings,onWalkingAnalysis={analysis=true}) {
                            session.updateSettings(it)
                            JourneyMonitoringController.updateSettings(context,it,JourneyMonitoringController.state.value.request?.vibrationEnabled ?: false)
                        }
                        OutlinedButton(onClick={analysis=true}) { Text("내 걷기 분석") }
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FavoriteBusesScreen(store: FavoriteBusStore,lifecycleOwner: LifecycleOwner,arrivalLoader: FavoriteBusArrivalLoader? = null,onSelect: (FavoriteBus)->Unit) {
    val loader=remember(arrivalLoader) { arrivalLoader ?: FavoriteBusArrivalLoader() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=20.dp)) {
        item { PaceNudgeHeader();Text("자주 타는 버스",style=MaterialTheme.typography.headlineMedium) }
        if(store.items.isEmpty()) item { EmptyStateCard("아직 저장한 버스가 없어요","정류장·노선을 선택한 뒤 ☆ 버튼으로 등록해 주세요.") }
        itemsIndexed(store.items,key={_,it->it.key}) { index,value ->
            var result by remember(value) { mutableStateOf<FavoriteBusArrival?>(null) }
            var retry by remember { mutableIntStateOf(0) }
            var alias by remember(value) { mutableStateOf(value.alias) }
            var editing by remember { mutableStateOf(false) }
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(value,retry,lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    result=loader.load(value)
                    while(true) { now=System.currentTimeMillis();kotlinx.coroutines.delay(5000) }
                }
            }
            SectionCard(value.alias.ifBlank { "${value.routeName}번" },value.request.boardingName) {
                Text((if(value.request.isGyeonggi) "경기" else "서울")+" · "+value.direction.ifBlank { "공식 승차 순서 ${value.request.identity.directionId}" },style=MaterialTheme.typography.bodyMedium)
                val snapshot=result?.snapshot?.takeIf { it.hasUsableOfficialEta(now) }
                Text(snapshot?.let { officialEtaText(it,now) } ?: if(result==null) "도착정보 조회 중" else if(result?.snapshot!=null) "갱신이 필요해요" else result?.message.orEmpty(),
                    style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
                Button(onClick={result?.selection?.let(onSelect)},enabled=result?.selection!=null,modifier=Modifier.fillMaxWidth()) { Text("이 버스로 이동 안내") }
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick={store.moveUp(value.key)},enabled=index>0) { Text("위로") }
                    TextButton(onClick={editing=true}) { Text("이름") }
                    TextButton(onClick={result=null;retry++}) { Text("갱신") }
                    TextButton(onClick={store.remove(value.key)}) { Text("★ 해제") }
                }
            }
            if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("표시 이름")},text={OutlinedTextField(alias,{ if(it.length<=80) alias=it },singleLine=true)},
                confirmButton={TextButton(onClick={store.rename(value.key,alias);editing=false}){Text("저장")}},dismissButton={TextButton(onClick={editing=false}){Text("취소")}})
        }
    }
}
