package com.aiutil.videodownloader.ui.browser

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiutil.videodownloader.App
import com.aiutil.videodownloader.BuildConfig
import com.aiutil.videodownloader.browser.TabManager
import com.aiutil.videodownloader.model.DetectedVideo
import com.aiutil.videodownloader.model.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    tabManager: TabManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val activeTab = tabManager.activeTab

    // 탭이 바뀌면 감지 목록도 그 탭의 것으로 갈아탄다.
    val detectionFlow = remember(activeTab?.id) {
        activeTab?.detections?.videos ?: MutableStateFlow(emptyList<DetectedVideo>())
    }
    val detected by detectionFlow.collectAsStateWithLifecycle()

    val onScreenFlow = remember(activeTab?.id) {
        activeTab?.detections?.onScreenVideos ?: MutableStateFlow(emptyList<DetectedVideo>())
    }
    val onScreen by onScreenFlow.collectAsStateWithLifecycle()

    val bookmarks by App.bookmarks.items.collectAsStateWithLifecycle()
    val history by App.history.items.collectAsStateWithLifecycle()

    // 받기를 눌러 보기 전에 '받음' 표시를 하기 위한 주소 모음.
    // 페이지 주소까지 대조하면 같은 페이지의 모든 영상이 받은 것으로 표시되므로
    // 영상 주소만 쓴다.
    val downloadedUrls = remember(history) {
        history.asSequence()
            .filter { it.status == DownloadStatus.COMPLETED }
            .map { normalizeUrl(it.url) }
            .toSet()
    }

    LaunchedEffect(activeTab?.id, activeTab?.url) {
        viewModel.syncAddress(activeTab?.url.orEmpty())
    }

    LaunchedEffect(viewModel.pendingLoad, activeTab?.id) {
        val target = viewModel.pendingLoad ?: return@LaunchedEffect
        val tab = tabManager.activeTab ?: return@LaunchedEffect
        focusManager.clearFocus()
        tab.loadUrl(target)
        viewModel.consumePendingLoad()
    }

    BackHandler(enabled = activeTab?.canGoBack == true) {
        activeTab?.goBack()
    }

    Column(modifier) {
        // bookmarks를 remember 키로 쓰면 즐겨찾기가 바뀔 때 메뉴 문구도 따라 바뀐다.
        val isBookmarked = remember(bookmarks, activeTab?.url) {
            activeTab?.url?.let { App.bookmarks.contains(it) } == true
        }

        AddressBar(
            viewModel = viewModel,
            tabManager = tabManager,
            isBookmarked = isBookmarked,
        )

        // 페이지 내용과 주소창 사이의 경계. 사이트 배경이 밝으면 이 선이 없을 때
        // 주소창이 어디까지인지 알 수 없다.
        HorizontalDivider()

        // 진행률을 알 수 있으면 그대로 보여주고, 아직 모를 때만 불확정 막대를 쓴다.
        activeTab?.takeIf { it.isLoading }?.let { tab ->
            if (tab.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { tab.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        Box(Modifier.weight(1f)) {
            activeTab?.let { tab ->
                // key를 걸어야 탭을 바꿀 때 WebView가 실제로 교체된다.
                key(tab.id) {
                    AndroidView(
                        factory = {
                            tab.webView.also { view ->
                                (view.parent as? ViewGroup)?.removeView(view)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (activeTab == null || activeTab.url.isBlank()) {
                StartPage(onOpen = viewModel::open, modifier = Modifier.fillMaxSize())
            }

            // 화면에 보이는 영상이 있으면 그것 하나를 바로 받게 한다. 목록을 펼쳐
            // 고르는 과정이 피드에서는 오히려 번거롭다. 목록은 옆 버튼으로 남긴다.
            val primary = onScreen.firstOrNull()

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (detected.size > 1) {
                    FilledTonalIconButton(
                        onClick = { viewModel.sheet = BrowserViewModel.Sheet.DETECTED },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "영상 목록 ${detected.size}개",
                        )
                    }
                }

                when {
                    primary != null -> ExtendedFloatingActionButton(
                        onClick = { viewModel.onDownloadRequested(primary) },
                        icon = { Icon(Icons.Default.Download, contentDescription = null) },
                        text = { Text("이 영상 받기") },
                    )

                    detected.isNotEmpty() -> ExtendedFloatingActionButton(
                        onClick = { viewModel.sheet = BrowserViewModel.Sheet.DETECTED },
                        icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                        text = { Text("영상 ${detected.size}개") },
                    )
                }
            }

            if (viewModel.resolving) {
                Surface(
                    color = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    when (viewModel.sheet) {
        BrowserViewModel.Sheet.DETECTED -> DetectedVideoSheet(
            onScreenVideos = onScreen,
            allVideos = detected,
            downloadedUrls = downloadedUrls,
            onDownload = {
                viewModel.sheet = null
                viewModel.onDownloadRequested(it)
            },
            onClear = {
                activeTab?.detections?.clear()
                viewModel.sheet = null
            },
            onDismiss = { viewModel.sheet = null },
        )

        BrowserViewModel.Sheet.TABS -> TabsSheet(
            tabs = tabManager.tabs,
            activeTabId = tabManager.activeTabId,
            onSelect = {
                tabManager.select(it.id)
                viewModel.sheet = null
            },
            onClose = { tabManager.closeTab(context, it.id) },
            onNewTab = {
                if (tabManager.openTab(context) == null) {
                    viewModel.notify("탭은 최대 ${TabManager.MAX_TABS}개까지 열 수 있습니다")
                }
                viewModel.sheet = null
            },
            onDismiss = { viewModel.sheet = null },
        )

        BrowserViewModel.Sheet.BOOKMARKS -> BookmarksSheet(
            bookmarks = bookmarks,
            onOpen = {
                viewModel.open(it.url)
                viewModel.sheet = null
            },
            onDelete = { App.bookmarks.remove(it.id) },
            onDismiss = { viewModel.sheet = null },
        )

        BrowserViewModel.Sheet.HISTORY -> DownloadHistorySheet(
            entries = history,
            onClear = {
                App.history.clear()
                viewModel.sheet = null
            },
            onDismiss = { viewModel.sheet = null },
        )

        null -> Unit
    }

    viewModel.historyPrompt?.let { prompt ->
        DownloadHistoryDialog(
            entries = prompt.entries,
            onConfirm = viewModel::confirmHistoryDownload,
            onDismiss = viewModel::dismissHistoryPrompt,
        )
    }

    viewModel.downloadPlan?.let { plan ->
        DownloadConfirmSheet(
            plan = plan,
            selectedIndex = viewModel.selectedVariantIndex,
            onSelectVariant = viewModel::selectVariant,
            onConfirm = viewModel::confirmDownload,
            onDismiss = viewModel::dismissPlan,
        )
    }
}

@Composable
private fun AddressBar(
    viewModel: BrowserViewModel,
    tabManager: TabManager,
    isBookmarked: Boolean,
) {
    val context = LocalContext.current
    val activeTab = tabManager.activeTab
    var menuOpen by remember { mutableStateOf(false) }

    // 기기 배색(동적 색상)에 맡겼더니 막대 전체가 검게 칠해져 아이콘까지 묻히는
    // 경우가 있었다. 주소창은 길을 잃으면 앱을 쓸 수 없는 곳이라 색을 직접 지정한다.
    val dark = isSystemInDarkTheme()
    val barColor = if (dark) Color(0xFF2B3036) else Color(0xFFEFF1F3)
    val fieldColor = if (dark) Color(0xFF3C4043) else Color(0xFFFFFFFF)
    val barContentColor = if (dark) Color(0xFFE8EAED) else Color(0xFF202124)
    val outlineColor = if (dark) Color(0xFF5F6368) else Color(0xFFBDC1C6)

    Surface(color = barColor, contentColor = barContentColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { activeTab?.goBack() },
                enabled = activeTab?.canGoBack == true,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }

            val fieldShape = RoundedCornerShape(22.dp)
            TextField(
                value = viewModel.addressText,
                onValueChange = { viewModel.addressText = it },
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, outlineColor, fieldShape)
                    .onFocusChanged {
                        viewModel.onAddressFocusChanged(it.isFocused, activeTab?.url.orEmpty())
                    },
                singleLine = true,
                placeholder = {
                    Text("주소 또는 검색어", color = barContentColor.copy(alpha = 0.6f))
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = barContentColor)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.submitAddress() }),
                shape = fieldShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = fieldColor,
                    unfocusedContainerColor = fieldColor,
                    focusedTextColor = barContentColor,
                    unfocusedTextColor = barContentColor,
                    cursorColor = barContentColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )

            TabCountButton(
                count = tabManager.count,
                onClick = { viewModel.sheet = BrowserViewModel.Sheet.TABS },
            )

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "메뉴")
                }

                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (activeTab?.isLoading == true) "중지" else "새로고침") },
                        onClick = {
                            menuOpen = false
                            if (activeTab?.isLoading == true) activeTab.stopLoading()
                            else activeTab?.reload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("새 탭") },
                        onClick = {
                            menuOpen = false
                            if (tabManager.openTab(context) == null) {
                                viewModel.notify("탭은 최대 ${TabManager.MAX_TABS}개까지 열 수 있습니다")
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("탭 목록 (${tabManager.count})") },
                        onClick = {
                            menuOpen = false
                            viewModel.sheet = BrowserViewModel.Sheet.TABS
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isBookmarked) "즐겨찾기에서 제거" else "즐겨찾기 추가") },
                        onClick = {
                            menuOpen = false
                            viewModel.toggleBookmark(
                                url = activeTab?.url.orEmpty(),
                                title = activeTab?.displayTitle.orEmpty(),
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("즐겨찾기 목록") },
                        onClick = {
                            menuOpen = false
                            viewModel.sheet = BrowserViewModel.Sheet.BOOKMARKS
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("다운로드 이력") },
                        onClick = {
                            menuOpen = false
                            viewModel.sheet = BrowserViewModel.Sheet.HISTORY
                        },
                    )

                    HorizontalDivider()

                    // 설치된 것이 어느 빌드인지 바로 확인하기 위한 표시.
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "빌드 ${BuildConfig.BUILD_ID}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}

/** 크롬처럼 열린 탭 수를 네모 안에 보여주는 버튼. */
@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(2.dp, LocalContentColor.current, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current,
            )
        }
    }
}

/** 주소를 입력하기 전에 보여주는 시작 화면. 자주 쓰는 사이트를 바로 열 수 있다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartPage(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val shortcuts = remember {
        listOf(
            "네이버" to "https://m.naver.com",
            "구글" to "https://www.google.com",
            "다음" to "https://m.daum.net",
            "X (트위터)" to "https://x.com",
            "인스타그램" to "https://www.instagram.com",
            "페이스북" to "https://m.facebook.com",
            "비메오" to "https://vimeo.com",
            "데일리모션" to "https://www.dailymotion.com",
        )
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "영상이 있는 페이지를 열면\n받을 수 있는 영상을 자동으로 찾아 드립니다.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
            )
            Text(
                text = "DRM으로 보호된 영상과 YouTube는 지원하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            // 어느 빌드를 쓰고 있는지 화면만 봐도 알 수 있게 한다.
            Text(
                text = "빌드 ${BuildConfig.BUILD_ID}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(shortcuts) { (label, url) ->
                    ElevatedCard(onClick = { onOpen(url) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
