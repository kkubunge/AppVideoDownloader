package com.aiutil.videodownloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.aiutil.videodownloader.ui.browser.BrowserViewModel
import com.aiutil.videodownloader.ui.browser.WebViewHolder
import com.aiutil.videodownloader.ui.theme.VideoDownloaderTheme

class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()
    private val webViewHolder = WebViewHolder()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 거부해도 다운로드는 동작한다 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WebView를 먼저 만들어 두면 공유로 들어온 주소를 첫 화면에서 바로 열 수 있다.
        webViewHolder.getOrCreate(this, viewModel)

        askNotificationPermissionIfNeeded()
        handleIncoming(intent)

        setContent {
            VideoDownloaderTheme {
                AppRoot(viewModel = viewModel, holder = webViewHolder)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        webViewHolder.destroy()
        super.onDestroy()
    }

    private fun handleIncoming(intent: Intent?) {
        intent?.getStringExtra(EXTRA_URL)
            ?.takeIf { it.isNotBlank() }
            ?.let { viewModel.open(it) }
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
