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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

@Composable
internal fun DashboardNavigation(selected: Int,onSelect: (Int)->Unit) {
    val compact=LocalConfiguration.current.screenWidthDp<360 || LocalDensity.current.fontScale>1.15f
    NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=2.dp) {
        listOf("경로 찾기","지도·도착정보","자주 타는 버스","설정").forEachIndexed { i,label ->
            NavigationBarItem(modifier=Modifier.semantics { contentDescription=label },selected=selected==i,onClick={onSelect(i)},icon={ AppIcon(listOf(R.drawable.ui_route,R.drawable.ui_map,R.drawable.ui_star,R.drawable.ui_settings)[i]) },
                colors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=MaterialTheme.colorScheme.primary,indicatorColor=MaterialTheme.colorScheme.secondaryContainer,unselectedIconColor=MaterialTheme.colorScheme.onSurfaceVariant,unselectedTextColor=MaterialTheme.colorScheme.onSurfaceVariant),
                label={ Text(if(compact) listOf("경로","지도·도착","즐겨찾기","설정")[i] else label,style=MaterialTheme.typography.labelSmall,maxLines=1) })
        }
    }
}
@Composable
internal fun PaceNudgeScreen(lifecycleOwner: LifecycleOwner,session: JourneySessionViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var analysis by rememberSaveable { mutableStateOf(false) }
    val context=LocalContext.current
    val monitoring by JourneyMonitoringController.state.collectAsState()
    var pendingFavorite by remember { mutableStateOf<FavoriteBus?>(null) }
    val changeScope=rememberCoroutineScope()
    var validatingChange by remember { mutableStateOf(false) }
    var changeRevision by remember { mutableLongStateOf(0L) }
    var favoriteChangeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun cancelFavoriteChange() {changeRevision++;favoriteChangeJob?.cancel();pendingFavorite=null;validatingChange=false}
    var changeError by remember { mutableStateOf<String?>(null) }
    fun openFavorite(selected:FavoriteBus) {
        restoreFavoriteBus(session.screens.getValue(FeatureMode.STOP),selected)
        session.mode=FeatureMode.STOP;tab=0
    }
    pendingFavorite?.let { selected ->
        AlertDialog(onDismissRequest=::cancelFavoriteChange,title={Text("이동 안내 변경")},
            text={Text(if(validatingChange) "새 경로의 공식 승차정보를 확인하고 있어요. 기존 안내는 계속됩니다."
                else "현재 다른 경로를 안내 중입니다. 새로운 경로로 변경하시겠어요?")},
            confirmButton={TextButton(enabled=!validatingChange,onClick={
                validatingChange=true;changeRevision++
                val token=changeRevision
                val previous=JourneyMonitoringController.state.value.request
                favoriteChangeJob=changeScope.launch {
                    try {
                        val checked=session.favoriteArrivals.load(selected)
                        if(token!=changeRevision || pendingFavorite?.key!=selected.key) return@launch
                        val active=JourneyMonitoringController.state.value
                        if(previous==null || !active.running || active.request?.isSameJourney(previous)!=true) {
                            pendingFavorite=null;return@launch
                        }
                        pendingFavorite=null
                        val validated=checked.selection
                        if(validated==null || checked.failed) {changeError=checked.message;return@launch}
                        val request=validated.request.copy(settings=session.settings,vibrationEnabled=active.request.vibrationEnabled)
                        if(JourneyMonitoringController.startAndAwait(context,request,canActivate={token==changeRevision})) openFavorite(validated.copy(request=request))
                        else changeError=JourneyMonitoringController.lastStartError
                    } finally {if(token==changeRevision) validatingChange=false}
                }
            }) {Text("새 경로로 변경")}},dismissButton={TextButton(onClick=::cancelFavoriteChange) {Text("기존 안내 유지")}})
    }
    changeError?.let { error -> AlertDialog(onDismissRequest={changeError=null},title={Text("기존 안내를 유지했어요")},text={Text(error)},
        confirmButton={TextButton(onClick={changeError=null}) {Text("확인")}}) }
    androidx.activity.compose.BackHandler(enabled=tab!=0 || analysis) { if(analysis) analysis=false else tab=0 }
    Scaffold(bottomBar={ DashboardNavigation(tab) { selected ->
        analysis=false
        tab=selected
    } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                analysis -> WalkingAnalysisScreen(session.walkingAnalysis,lifecycleOwner,session.settings,{ session.updateSettings(it)
                    JourneyMonitoringController.updateSettings(context,it,JourneyMonitoringController.state.value.request?.vibrationEnabled ?: false) },{analysis=false})
                tab<2 -> JourneyDashboardContent(lifecycleOwner,session,showMapAndArrivals=tab==1)
                tab==2 -> FavoriteBusesScreen(session.favorites,lifecycleOwner,arrivalLoader=session.favoriteArrivals,onAdd={if(session.mode!=FeatureMode.STOP) session.screens.getValue(session.mode).invalidateRequests(preserveTimetable=true);tab=0;session.mode=FeatureMode.STOP}) { selected ->
                    if(monitoring.running && monitoring.request?.isSameJourney(selected.request)!=true) pendingFavorite=selected
                    else openFavorite(selected)
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
internal fun FavoriteBusesScreen(store: FavoriteBusStore,lifecycleOwner: LifecycleOwner,arrivalLoader: FavoriteBusArrivalLoader? = null,onAdd:()->Unit={},arrivalClock:GuidanceDisplayClock?=null,onSelect: (FavoriteBus)->Unit) {
    val loader=remember(arrivalLoader) { arrivalLoader ?: FavoriteBusArrivalLoader() }
    val displayClock=remember(arrivalClock) {arrivalClock ?: GuidanceDisplayClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime(),android.os.SystemClock::elapsedRealtime)}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=20.dp)) {
        item {
            PaceNudgeHeader()
            Row(Modifier.fillMaxWidth().padding(top=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("자주 타는 버스",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                OutlinedButton(onClick=onAdd,contentPadding=PaddingValues(horizontal=12.dp)) { Text("+ 버스 추가",style=MaterialTheme.typography.labelSmall) }
            }
            Text("즐겨 찾는 버스를 등록하고 빠르게 이동 안내를 받아보세요.",Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(store.items.isEmpty()) item { EmptyStateCard("아직 저장한 버스가 없어요","정류장·노선을 선택한 뒤 ☆ 버튼으로 등록해 주세요.") }
        itemsIndexed(store.items,key={_,it->it.key}) { index,value ->
            var result by remember(value.key) { mutableStateOf<FavoriteBusArrival?>(null) }
            var retry by remember { mutableIntStateOf(0) }
            var alias by remember(value) { mutableStateOf(value.alias) }
            var editing by remember { mutableStateOf(false) }
            var clockPulse by remember { mutableLongStateOf(displayClock.now()) }
            LaunchedEffect(value.key,retry,lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            while(kotlinx.coroutines.currentCoroutineContext().isActive) {
                                clockPulse=displayClock.now();kotlinx.coroutines.delay(1000)
                            }
                        }
                        var configurationRecheck=true // Once per visible/resume/manual event, never every countdown tick.
                        while(kotlinx.coroutines.currentCoroutineContext().isActive) {
                            result=loader.load(value,recheckConfiguration=configurationRecheck)
                            configurationRecheck=false
                            kotlinx.coroutines.delay((loader.nextRefreshAt(value)-displayClock.now()).coerceAtLeast(1000L))
                        }
                    }
                }
            }
            var menuExpanded by remember { mutableStateOf(false) }
            var removing by remember { mutableStateOf(false) }
            Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        AppIcon(R.drawable.ui_bus,modifier=Modifier.size(30.dp),tint=if(value.request.isGyeonggi) Color(0xFF08A255) else Color(0xFF1684ED))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${value.routeName} · ${value.alias.ifBlank { value.request.boardingName }}",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                            Text(value.direction.ifBlank { "공식 승차 순서 ${value.request.identity.directionId}" },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick={removing=true},modifier=Modifier.size(40.dp)) { AppIcon(R.drawable.ui_star,"즐겨찾기 해제") }
                        Box {
                            IconButton(onClick={menuExpanded=true},modifier=Modifier.size(40.dp)) { AppIcon(R.drawable.ui_more,"즐겨찾기 관리") }
                            DropdownMenu(expanded=menuExpanded,onDismissRequest={menuExpanded=false}) {
                                DropdownMenuItem(text={Text("위로 이동")},enabled=index>0,onClick={store.moveUp(value.key);menuExpanded=false})
                                DropdownMenuItem(text={Text("아래로 이동")},enabled=index<store.items.lastIndex,onClick={store.moveDown(value.key);menuExpanded=false})
                                DropdownMenuItem(text={Text("표시 이름 변경")},onClick={editing=true;menuExpanded=false})
                                DropdownMenuItem(text={Text("도착정보 새로고침")},onClick={retry++;menuExpanded=false})
                            }
                        }
                    }
                    if(value.alias.isNotBlank()) Text(value.request.boardingName,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    val refreshed=result // Always subscribe to completion, even when a cached row exists.
                    val response=refreshed?.let {loader.cached(value) ?: it}
                    // Read the clock in the same restart scope as the new response, not just the parent item's tick.
                    val now=maxOf(clockPulse,displayClock.now())
                    val snapshot=response?.snapshot?.takeIf { it.hasUsableOfficialEta(now) }
                    val etaText=snapshot?.let { officialEtaText(it,now) } ?: if(result==null) "첫 도착정보를 연결하고 있어요" else response?.message.orEmpty()
                    if(snapshot!=null && response?.failed==true) Text(response.message,style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(snapshot==null && response?.selection!=null) Text("승차정보 확인됨 · 실시간 ETA가 있어야 이동 판단을 시작해요.",
                        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    snapshot?.receivedAtEpochMillis?.let { Text("공식 실시간 · 마지막 수신 ${((now-it)/1000).coerceAtLeast(0)}초 전",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    BoxWithConstraints {
                        val compact=maxWidth / LocalDensity.current.fontScale < 260.dp
                        val status: @Composable (Modifier)->Unit = { modifier ->
                            Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                AppIcon(R.drawable.ui_clock,modifier=Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(etaText,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val action: @Composable ()->Unit = {
                            FilledTonalButton(onClick={result?.selection?.let(onSelect)},enabled=result?.selection!=null,contentPadding=PaddingValues(horizontal=14.dp)) {
                                Text(if(snapshot==null && response?.selection!=null) "도착정보 없이 열기" else "이동 안내",style=MaterialTheme.typography.labelLarge);AppIcon(R.drawable.ui_chevron,modifier=Modifier.size(18.dp))
                            }
                        }
                        if(compact) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { status(Modifier.fillMaxWidth());action() }
                        else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { status(Modifier.weight(1f));action() }
                    }
                }
            }
            if(removing) AlertDialog(onDismissRequest={removing=false},title={Text("즐겨찾기를 삭제할까요?")},
                text={Text("${value.routeName} · ${value.request.boardingName}")},
                confirmButton={TextButton(onClick={store.remove(value.key);removing=false}) {Text("삭제")}},
                dismissButton={TextButton(onClick={removing=false}) {Text("유지")}})
            if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("표시 이름")},text={OutlinedTextField(alias,{ if(it.length<=80) alias=it },singleLine=true)},
                confirmButton={TextButton(onClick={store.rename(value.key,alias);editing=false}){Text("저장")}},dismissButton={TextButton(onClick={editing=false}){Text("취소")}})
        }
    }
}
