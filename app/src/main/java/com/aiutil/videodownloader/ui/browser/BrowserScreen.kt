package com.aiutil.videodownloader.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    holder: WebViewHolder,
    modifier: Modifier = Modifier,
) {
    val detected by viewModel.detectedVideos.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(viewModel.pendingLoad) {
        val url = viewModel.pendingLoad ?: return@LaunchedEffect
        val webView = holder.webView ?: return@LaunchedEffect
        focusManager.clearFocus()
        webView.loadUrl(url)
        viewModel.consumePendingLoad()
    }

    BackHandler(enabled = viewModel.canGoBack) {
        holder.webView?.goBack()
    }

    Column(modifier) {
        AddressBar(viewModel = viewModel, holder = holder)

        if (viewModel.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { context -> holder.getOrCreate(context, viewModel) },
                modifier = Modifier.fillMaxSize(),
            )

            if (viewModel.currentUrl.isBlank()) {
                StartPage(
                    onOpen = viewModel::open,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (detected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { sheetOpen = true },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    text = { Text("영상 ${detected.size}개") },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
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

    if (sheetOpen) {
        DetectedVideoSheet(
            videos = detected,
            onDownload = {
                viewModel.onDownloadRequested(it)
                sheetOpen = false
            },
            onClear = {
                viewModel.clearDetections()
                sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
        )
    }

    viewModel.qualityPrompt?.let { prompt ->
        QualityPickerDialog(
            variants = prompt.variants,
            onPick = viewModel::chooseVariant,
            onDismiss = viewModel::dismissQualityPrompt,
        )
    }
}

@Composable
private fun AddressBar(viewModel: BrowserViewModel, holder: WebViewHolder) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { holder.webView?.goBack() },
                enabled = viewModel.canGoBack,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }

            TextField(
                value = viewModel.addressText,
                onValueChange = { viewModel.addressText = it },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { viewModel.onAddressFocusChanged(it.isFocused) },
                singleLine = true,
                placeholder = { Text("주소 또는 검색어") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.submitAddress() }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            IconButton(onClick = {
                if (viewModel.isLoading) holder.webView?.stopLoading() else holder.webView?.reload()
            }) {
                Icon(
                    imageVector = if (viewModel.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                    contentDescription = if (viewModel.isLoading) "중지" else "새로고침",
                )
            }
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
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
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
