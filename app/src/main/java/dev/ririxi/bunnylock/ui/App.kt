@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.data.Release
import dev.ririxi.bunnylock.modem.Access
import dev.ririxi.bunnylock.modem.RootState
import dev.ririxi.bunnylock.modem.SimInfo
import dev.ririxi.bunnylock.ui.design.SxDialog
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private val LIVE_REFRESH_PAUSE = 500.milliseconds

private val RAIL_MIN_WIDTH = 600.dp

private val RAIL_MIN_HEIGHT = 480.dp

private val MAX_CONTENT_WIDTH = 720.dp

// Fase 2: one navigation, four tabs (superprompt §3.1). The old Bands,
// Cell lock, Tools and Settings tabs did not disappear: Cell lock is the
// Antenas tab, and Bands, Tools and Settings live inside Herramientas
// (ToolsHub). Nothing is orphaned.
enum class Tab(
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    INICIO(R.string.shell_tab_inicio, Icons.Rounded.Home),
    EXPLORAR(R.string.shell_tab_explorar, Icons.Rounded.Search),
    ANTENAS(R.string.shell_tab_antenas, BunnyIcons.Tower),
    HERRAMIENTAS(R.string.shell_tab_herramientas, Icons.Rounded.Build),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BunnyApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(Tab.INICIO) }
    // Sub-destination inside Herramientas, kept by name so it survives
    // process death; null means the Herramientas hub itself.
    var toolsDestinationName by rememberSaveable { mutableStateOf<String?>(null) }
    val toolsDestination = ToolsDestination.entries.firstOrNull { it.name == toolsDestinationName }
    // Section inside Antenas, kept by name so it survives process death;
    // null means the catalog (Fase 4). The section survives tab switches
    // like the Herramientas destination; explicit navigation (goToTab /
    // goToCellLock) picks the section for its purpose.
    var antennasSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val antennasSection = AntennasSection.entries.firstOrNull { it.name == antennasSectionName } ?: AntennasSection.CATALOG
    if (state.modem.root == RootState.Granted && (selectedTab == Tab.INICIO || selectedTab == Tab.EXPLORAR || selectedTab == Tab.ANTENAS)) LiveRefresh(vm)
    val snackbar = remember { SnackbarHostState() }
    val appBarStates = Tab.entries.associateWith { rememberTopAppBarState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarStates.getValue(selectedTab))
    val tabStates = rememberSaveableStateHolder()
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    var pulledToRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(state.modem.refreshing) { if (!state.modem.refreshing) pulledToRefresh = false }
    // Refreshes the app starts itself, like the one after every change, show in the app bar so the screen doesn't jump.
    val bunnyRefreshing = state.modem.refreshing && pulledToRefresh
    val showTabs = state.modem.root == RootState.Granted
    val windowSize = LocalWindowInfo.current.containerDpSize
    val showRail = showTabs && windowSize.width >= RAIL_MIN_WIDTH && windowSize.height >= RAIL_MIN_HEIGHT
    val showFloatingBar = showTabs && !showRail
    val floatingBarSpace = if (showFloatingBar) FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset else 0.dp
    val pendingToolbarSpace = if (selectedTab == Tab.HERRAMIENTAS && toolsDestination == ToolsDestination.BANDS && state.changedBandCount > 0) PENDING_TOOLBAR_SPACE else 0.dp
    // Tapping the Herramientas tab while already on it goes back to its
    // hub; switching tabs keeps the sub-destination so coming back restores it.
    val onSelectTab: (Tab) -> Unit = { tab ->
        if (tab == selectedTab && tab == Tab.HERRAMIENTAS) toolsDestinationName = null else selectedTab = tab
    }
    // Fase 3: Inicio and Explorar link to the auto-collection setting
    // (Herramientas > Settings) from their state line.
    val goToSettings: () -> Unit = {
        selectedTab = Tab.HERRAMIENTAS
        toolsDestinationName = ToolsDestination.SETTINGS.name
    }
    // Navigation to a tab from another screen. Landing on Antenas from
    // Inicio (or anywhere without a lock in hand) shows the catalog.
    val goToTab: (Tab) -> Unit = { tab ->
        if (tab == Tab.ANTENAS) antennasSectionName = AntennasSection.CATALOG.name
        selectedTab = tab
    }
    // Explorar's "View in Antennas" fills the cell-lock form first, so it
    // lands on the Cell lock section, not the catalog.
    val goToCellLock: () -> Unit = {
        antennasSectionName = AntennasSection.CELL_LOCK.name
        selectedTab = Tab.ANTENAS
    }
    // Antenas' "View on map" / "Choose on map": the target (or the eNB
    // being placed) is set first, then the map opens on it.
    val goToOrientation: () -> Unit = {
        selectedTab = Tab.HERRAMIENTAS
        toolsDestinationName = ToolsDestination.ORIENTATION.name
    }
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val contentSides = if (showRail) WindowInsetsSides.End else WindowInsetsSides.Horizontal
    val layoutDirection = LocalLayoutDirection.current

    state.update?.let { UpdateDialog(it, onSkip = vm::skipUpdate, onDismiss = vm::dismissUpdate) }

    Row(Modifier.fillMaxSize()) {
        if (showRail) {
            TabRail(
                selected = selectedTab,
                onSelect = onSelectTab,
                windowInsets = safeInsets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
            )
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            contentWindowInsets = safeInsets.only(contentSides + WindowInsetsSides.Vertical),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Wordmark() },
                    subtitle = { Text(topBarSubtitle(state)) },
                    actions = {
                        if (state.modem.settling) TextButton(onClick = vm::stopWaiting, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shell_stop_waiting)) }
                        if (state.modem.sims.size > 1) SimChip(state.modem.sims, state.modem.sim, enabled = !state.busy, onSelect = vm::setSim)
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            when {
                                state.busy || (state.modem.refreshing && !pulledToRefresh) || state.modem.root == RootState.Checking -> {
                                    LoadingIndicator(Modifier.size(36.dp))
                                }

                                state.modem.root == RootState.Granted -> {
                                    IconButton(onClick = vm::refresh, shapes = IconButtonDefaults.shapes()) {
                                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.shell_refresh))
                                    }
                                }
                            }
                        }
                    },
                    windowInsets = safeInsets.only(contentSides + WindowInsetsSides.Top),
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = floatingBarSpace + pendingToolbarSpace)) },
        ) { innerPadding ->
            when (val root = state.modem.root) {
                RootState.Checking -> {
                    Centered(innerPadding) {
                        HoppingBunny()
                        Text(state.modem.connectStep, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                }

                is RootState.Denied -> {
                    Centered(innerPadding) {
                        DroopyBunny()
                        Text(stringResource(R.string.shell_denied_title), style = MaterialTheme.typography.headlineMediumEmphasized)
                        Text(
                            root.reason,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = vm::connect, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shell_try_again)) }
                        TextButton(onClick = vm::copyDiagnostics, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_copy_diagnostics)) }
                    }
                }

                RootState.Granted -> {
                    val pullToRefreshState = rememberPullToRefreshState()
                    val motion = MaterialTheme.motionScheme
                    Box(Modifier.fillMaxSize()) {
                        PullToRefreshBox(
                            isRefreshing = bunnyRefreshing,
                            onRefresh = {
                                pulledToRefresh = true
                                vm.refresh()
                            },
                            state = pullToRefreshState,
                            modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                            indicator = {
                                BunnyRefreshIndicator(
                                    state = pullToRefreshState,
                                    isRefreshing = bunnyRefreshing,
                                    modifier = Modifier.align(Alignment.TopCenter),
                                )
                            },
                        ) {
                            MaxWidthContent(innerPadding, extraBottom = floatingBarSpace) { contentPadding ->
                                // Inside the refresh box so the app bar expands before an overscroll becomes a refresh.
                                AnimatedContent(
                                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).belowBunnyRefresh(pullToRefreshState),
                                    targetState = selectedTab,
                                    transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                                    label = "tab",
                                ) { tab ->
                                    tabStates.SaveableStateProvider(tab.name) {
                                        when (tab) {
                                            Tab.INICIO ->
                                                HomeScreen(
                                                    state,
                                                    vm,
                                                    contentPadding,
                                                    goTo = goToTab,
                                                    goToBands = {
                                                        selectedTab = Tab.HERRAMIENTAS
                                                        toolsDestinationName = ToolsDestination.BANDS.name
                                                    },
                                                    goToSettings = goToSettings,
                                                )

                                            Tab.EXPLORAR -> ExplorarScreen(state, vm, contentPadding, goToCellLock = goToCellLock, goToSettings = goToSettings)
                                            Tab.ANTENAS ->
                                                AntennasScreen(
                                                    state, vm, contentPadding, antennasSection,
                                                    onSection = { antennasSectionName = it.name },
                                                    onViewOnMap = { row ->
                                                        vm.selectAntennaTarget(row)
                                                        goToOrientation()
                                                    },
                                                    onPlaceOnMap = { enb ->
                                                        vm.setPlacingEnb(enb)
                                                        goToOrientation()
                                                    },
                                                )
                                            Tab.HERRAMIENTAS ->
                                                ToolsHub(state, vm, contentPadding, toolsDestination, { toolsDestinationName = it?.name }, goToAntennas = { goToTab(Tab.ANTENAS) })
                                        }
                                    }
                                }
                            }
                        }
                        if (showFloatingBar) {
                            FloatingTabBar(
                                selected = selectedTab,
                                onSelect = onSelectTab,
                                modifier =
                                    Modifier.align(Alignment.BottomCenter).padding(
                                        start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                                        end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                                        bottom = innerPadding.calculateBottomPadding() + FloatingToolbarDefaults.ScreenOffset,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaxWidthContent(
    innerPadding: PaddingValues,
    extraBottom: Dp,
    content: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val start = innerPadding.calculateStartPadding(layoutDirection)
        val end = innerPadding.calculateEndPadding(layoutDirection)
        val gutter = ((maxWidth - start - end - MAX_CONTENT_WIDTH) / 2).coerceAtLeast(0.dp)
        content(PaddingValues(start = start + gutter, end = end + gutter, bottom = innerPadding.calculateBottomPadding() + extraBottom))
    }
}

@Composable
private fun UpdateDialog(
    release: Release,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // One dialog pattern for the whole app (Fase 7): the three actions
    // map onto SxDialog — Download is the confirm, Later the dismiss,
    // "Skip this version" the secondary action.
    SxDialog(
        title = stringResource(R.string.shell_update_title),
        confirmLabel = stringResource(R.string.shell_update_download),
        onConfirm = {
            runCatching { uriHandler.openUri(release.url) }
            onDismiss()
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.shell_update_later),
        secondaryLabel = stringResource(R.string.shell_update_skip),
        onSecondary = onSkip,
        content = {
            Text(stringResource(R.string.shell_update_message, release.version))
            if (release.notes.isNotEmpty()) Text(release.notes, style = MaterialTheme.typography.bodyMedium)
        },
    )
}

@Composable
private fun LiveRefresh(vm: MainViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshConnection()
                delay(LIVE_REFRESH_PAUSE)
            }
        }
    }
}

@Composable
private fun topBarSubtitle(state: UiState): String {
    val throughShizuku = state.modem.access == Access.SHIZUKU_ADB || state.modem.access == Access.SHIZUKU_ROOT
    val purpose = stringResource(state.modem.backend?.label ?: R.string.shell_subtitle_default)
    return if (throughShizuku) stringResource(R.string.shell_subtitle_shizuku, purpose) else purpose
}

/** [selected] counts from 1. */
@Composable
private fun SimChip(
    sims: List<SimInfo>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = sims.firstOrNull { it.slot + 1 == selected } ?: return
    val chipDescription = stringResource(R.string.shell_sim_chip_description, selected, current.name)
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(start = 8.dp, end = 6.dp),
            modifier = Modifier.semantics { contentDescription = chipDescription },
        ) {
            SimNumber(selected)
            Spacer(Modifier.width(8.dp))
            Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sims.forEach { sim ->
                val number = sim.slot + 1
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shell_sim_menu_item, number, sim.name)) },
                    leadingIcon = { SimNumber(number) },
                    trailingIcon = { if (number == selected) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.shell_sim_selected)) },
                    onClick = {
                        expanded = false
                        if (number != selected) onSelect(number)
                    },
                )
            }
        }
    }
}

@Composable
private fun SimNumber(number: Int) {
    val colors = MaterialTheme.colorScheme
    val container = if (number == 1) colors.primary else colors.tertiary
    val content = if (number == 1) colors.onPrimary else colors.onTertiary
    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(container), contentAlignment = Alignment.Center) {
        Text("$number", color = content, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Centered(
    innerPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
        }
    }
}

@Composable
fun PaddingValues.withScreenMargins(extraBottom: Dp = 0.dp): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(layoutDirection) + 16.dp,
        end = calculateEndPadding(layoutDirection) + 16.dp,
        top = calculateTopPadding() + 4.dp,
        bottom = calculateBottomPadding() + 24.dp + extraBottom,
    )
}
