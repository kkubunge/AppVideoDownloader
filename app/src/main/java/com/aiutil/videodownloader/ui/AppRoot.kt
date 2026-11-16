package com.aiutil.videodownloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiutil.videodownloader.App
import com.aiutil.videodownloader.browser.TabManager
import com.aiutil.videodownloader.model.DetectedVideo
import com.aiutil.videodownloader.ui.browser.BrowserScreen
import com.aiutil.videodownloader.ui.browser.BrowserViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import com.aiutil.videodownloader.ui.downloads.DownloadsScreen
import com.aiutil.videodownloader.ui.library.LibraryScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    BROWSER("브라우저", Icons.Default.Public),
    DOWNLOADS("다운로드", Icons.Default.Download),
    LIBRARY("보관함", Icons.Default.VideoLibrary),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppRoot(viewModel: BrowserViewModel, tabManager: TabManager) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val items by App.store.items.collectAsStateWithLifecycle()
    val activeCount = items.count { it.status.isActive }

    // 받기 버튼은 하단 막대에 둔다. 페이지 위에 떠 있으면 내용을 가린다.
    val activeTab = tabManager.activeTab
    val detectionFlow = remember(activeTab?.id) {
        activeTab?.detections?.videos ?: MutableStateFlow(emptyList<DetectedVideo>())
    }
    val detected by detectionFlow.collectAsStateWithLifecycle()
    val onScreenFlow = remember(activeTab?.id) {
        activeTab?.detections?.onScreenVideos ?: MutableStateFlow(emptyList<DetectedVideo>())
    }
    val onScreen by onScreenFlow.collectAsStateWithLifecycle()

    val showDownloadAction = selected == Tab.BROWSER.ordinal && detected.isNotEmpty()

    LaunchedEffect(viewModel.toast) {
        viewModel.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    // 공유로 들어온 링크는 브라우저 탭에서 열려야 한다.
    LaunchedEffect(viewModel.pendingLoad) {
        if (viewModel.pendingLoad != null) selected = Tab.BROWSER.ordinal
    }

    // 키보드가 올라오면 하단 탭 바를 치운다. 좁은 화면에서 입력란까지 가리는 것을 막는다.
    val keyboardVisible = WindowInsets.isImeVisible

    /**
     * 다운로드 항목의 출처로 이동한다. 새 탭에서 열어 보고 있던 페이지를 덮지 않는다.
     * 탭이 가득 찼으면 현재 탭에서 연다.
     */
    val openLink: (String) -> Unit = { url ->
        if (tabManager.openTab(context, url) == null) viewModel.open(url)
        selected = Tab.BROWSER.ordinal
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            if (!keyboardVisible) {
                NavigationBar {
                    Tab.entries.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            label = { Text(tab.label) },
                            icon = {
                                if (tab == Tab.DOWNLOADS && activeCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$activeCount") } }) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                        )
                    }

                    if (showDownloadAction) {
                        val count = if (onScreen.isNotEmpty()) onScreen.size else detected.size
                        NavigationBarItem(
                            selected = false,
                            onClick = {
                                // 화면에 보이는 것이 있으면 바로 받고, 없으면 목록을 연다.
                                val primary = onScreen.firstOrNull()
                                if (primary != null) {
                                    viewModel.onDownloadRequested(primary, onScreen + detected)
                                } else {
                                    viewModel.sheet = BrowserViewModel.Sheet.DETECTED
                                }
                            },
                            label = { Text("받기") },
                            icon = {
                                BadgedBox(badge = { Badge { Text("$count") } }) {
                                    Icon(Icons.Default.Download, contentDescription = "영상 받기")
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (Tab.entries[selected]) {
                Tab.BROWSER -> BrowserScreen(viewModel, tabManager, Modifier.fillMaxSize())

                Tab.DOWNLOADS -> DownloadsScreen(
                    onOpenLink = openLink,
                    modifier = Modifier.fillMaxSize(),
                )

                Tab.LIBRARY -> LibraryScreen(
                    onOpenLink = openLink,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 기본 위치(화면 아래)는 방금 누른 '받기' 버튼을 덮는다. 위쪽에 띄운다.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
