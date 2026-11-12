package com.aiutil.videodownloader.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

object Clip {

    fun copy(context: Context, text: String, label: String = "주소") {
        if (text.isBlank()) return
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        manager.setPrimaryClip(ClipData.newPlainText(label, text))

        // Android 13부터는 시스템이 복사했다는 안내를 직접 띄운다. 중복으로 띄우지 않는다.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(context, "주소를 복사했습니다", Toast.LENGTH_SHORT).show()
        }
    }
}
