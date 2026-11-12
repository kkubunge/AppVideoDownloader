package com.aiutil.videodownloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.aiutil.videodownloader.browser.TabManager
import com.aiutil.videodownloader.ui.browser.BrowserViewModel
import com.aiutil.videodownloader.ui.theme.VideoDownloaderTheme

class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    /**
     * 탭은 WebView(= Activity Context)를 쥐고 있어 ViewModel에 둘 수 없다.
     * Activity와 수명을 같이한다.
     */
    private val tabManager by lazy {
        TabManager(onDownloadRequested = { viewModel.onDownloadRequested(it) })
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 거부해도 다운로드는 동작한다 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 키보드 인셋을 Compose가 직접 다루게 한다. 매니페스트의 adjustResize와 짝이다.
        enableEdgeToEdge()

        // 탭을 먼저 만들어 두면 공유로 들어온 주소를 첫 화면에서 바로 열 수 있다.
        tabManager.ensureInitialTab(this)

        askNotificationPermissionIfNeeded()
        handleIncoming(intent)

        setContent {
            VideoDownloaderTheme {
                AppRoot(viewModel = viewModel, tabManager = tabManager)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        super.onDestroy()
    }

    /** 공유로 들어온 링크는 새 탭에서 연다 — 보고 있던 페이지를 덮어쓰지 않는다. */
    private fun handleIncoming(intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return
        if (tabManager.openTab(this, url) == null) {
            // 탭이 가득 찼으면 현재 탭에서 연다.
            viewModel.open(url)
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_URL = "com.aiutil.videodownloader.extra.URL"
    }
}
