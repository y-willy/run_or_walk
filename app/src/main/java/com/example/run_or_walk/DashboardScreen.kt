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

@Composable
internal fun DashboardNavigation(selected: Int,onSelect: (Int)->Unit) {
    val compact=LocalConfiguration.current.screenWidthDp<360 || LocalDensity.current.fontScale>1.15f
    NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=2.dp) {
        listOf("경로 찾기","정류장 찾기","자주 타는 버스","설정").forEachIndexed { i,label ->
            NavigationBarItem(modifier=Modifier.semantics { contentDescription=label },selected=selected==i,onClick={onSelect(i)},icon={ AppIcon(listOf(R.drawable.ui_route,R.drawable.ui_bus,R.drawable.ui_star,R.drawable.ui_settings)[i]) },
                colors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=MaterialTheme.colorScheme.primary,indicatorColor=MaterialTheme.colorScheme.secondaryContainer,unselectedIconColor=MaterialTheme.colorScheme.onSurfaceVariant,unselectedTextColor=MaterialTheme.colorScheme.onSurfaceVariant),
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
            session.screens.getValue(session.mode).invalidateRequests(preserveTimetable=true)
            session.mode=FeatureMode.entries[selected]
        }
        tab=selected
    } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                analysis -> WalkingAnalysisScreen(session.walkingAnalysis,lifecycleOwner,session.settings,{ session.updateSettings(it)
                    JourneyMonitoringController.updateSettings(context,it,JourneyMonitoringController.state.value.request?.vibrationEnabled ?: false) },{analysis=false})
                tab<2 -> JourneyDashboardContent(lifecycleOwner,session)
                tab==2 -> FavoriteBusesScreen(session.favorites,lifecycleOwner,onAdd={if(session.mode!=FeatureMode.STOP) session.screens.getValue(session.mode).invalidateRequests(preserveTimetable=true);tab=1;session.mode=FeatureMode.STOP}) { selected ->
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
internal fun FavoriteBusesScreen(store: FavoriteBusStore,lifecycleOwner: LifecycleOwner,arrivalLoader: FavoriteBusArrivalLoader? = null,onAdd:()->Unit={},onSelect: (FavoriteBus)->Unit) {
    val loader=remember(arrivalLoader) { arrivalLoader ?: FavoriteBusArrivalLoader() }
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
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(value.key,retry,lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    result=loader.load(value)
                    while(true) { now=System.currentTimeMillis();kotlinx.coroutines.delay(5000) }
                }
            }
            var menuExpanded by remember { mutableStateOf(false) }
            Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        AppIcon(R.drawable.ui_bus,modifier=Modifier.size(30.dp),tint=if(value.request.isGyeonggi) Color(0xFF08A255) else Color(0xFF1684ED))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${value.routeName} · ${value.alias.ifBlank { value.request.boardingName }}",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                            Text(value.direction.ifBlank { "공식 승차 순서 ${value.request.identity.directionId}" },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick={store.remove(value.key)},modifier=Modifier.size(40.dp)) { AppIcon(R.drawable.ui_star,"즐겨찾기 해제") }
                        Box {
                            IconButton(onClick={menuExpanded=true},modifier=Modifier.size(40.dp)) { AppIcon(R.drawable.ui_more,"즐겨찾기 관리") }
                            DropdownMenu(expanded=menuExpanded,onDismissRequest={menuExpanded=false}) {
                                DropdownMenuItem(text={Text("위로 이동")},enabled=index>0,onClick={store.moveUp(value.key);menuExpanded=false})
                                DropdownMenuItem(text={Text("표시 이름 변경")},onClick={editing=true;menuExpanded=false})
                                DropdownMenuItem(text={Text("도착정보 새로고침")},onClick={retry++;menuExpanded=false})
                            }
                        }
                    }
                    if(value.alias.isNotBlank()) Text(value.request.boardingName,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    val snapshot=result?.snapshot?.takeIf { it.hasUsableOfficialEta(now) }
                    val etaText=snapshot?.let { officialEtaText(it,now) } ?: if(result==null) "도착정보 조회 중" else if(result?.snapshot!=null) "갱신이 필요해요" else result?.message.orEmpty()
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
                                Text("이동 안내",style=MaterialTheme.typography.labelLarge);AppIcon(R.drawable.ui_chevron,modifier=Modifier.size(18.dp))
                            }
                        }
                        if(compact) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { status(Modifier.fillMaxWidth());action() }
                        else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { status(Modifier.weight(1f));action() }
                    }
                }
            }
            if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("표시 이름")},text={OutlinedTextField(alias,{ if(it.length<=80) alias=it },singleLine=true)},
                confirmButton={TextButton(onClick={store.rename(value.key,alias);editing=false}){Text("저장")}},dismissButton={TextButton(onClick={editing=false}){Text("취소")}})
        }
    }
}
