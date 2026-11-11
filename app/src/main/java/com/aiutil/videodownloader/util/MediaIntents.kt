package com.aiutil.videodownloader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object MediaIntents {

    /**
     * Android 9 이하에서는 파일을 공용 폴더에 직접 쓰므로 file:// URI가 나온다.
     * 그대로 다른 앱에 넘기면 FileUriExposedException이 나기 때문에 FileProvider로 감싼다.
     */
    private fun shareable(context: Context, raw: String): Uri {
        val uri = Uri.parse(raw)
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return uri
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(path),
        )
    }

    fun play(context: Context, outputUri: String?, mimeType: String = "video/*") {
        if (outputUri.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(shareable(context, outputUri), mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, "재생할 앱 선택"), "재생할 수 있는 앱이 없습니다")
    }

    fun share(context: Context, outputUri: String?, mimeType: String = "video/*") {
        if (outputUri.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareable(context, outputUri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, "공유"), "공유할 수 있는 앱이 없습니다")
    }

    private fun launch(context: Context, intent: Intent, failureMessage: String) {
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show() }
    }
}
