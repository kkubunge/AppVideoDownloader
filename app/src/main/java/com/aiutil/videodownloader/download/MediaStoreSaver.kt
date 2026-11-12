package com.aiutil.videodownloader.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 완성된 파일을 사용자가 볼 수 있는 곳(Movies/동영상 다운로더)으로 옮긴다.
 *
 * 받는 동안에는 앱 전용 캐시에 두고, 끝났을 때만 공용 저장소로 옮긴다. 그래야 중간에 끊긴
 * 파일이 갤러리에 나타나지 않고, 이어받기 로직도 단순해진다.
 */
object MediaStoreSaver {

    private const val FOLDER = "동영상 다운로더"

    /** 이름 충돌로 거절당했을 때 번호를 바꿔 다시 시도할 횟수. */
    private const val NAME_ATTEMPTS = 5

    data class Saved(val uri: Uri, val displayPath: String)

    @Throws(IOException::class)
    fun save(context: Context, source: File, displayName: String, mimeType: String): Saved {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return saveToPublicDirectory(context, source, displayName)
        }

        // 같은 이름이 이미 있으면 저장소가 "Failed to build unique file"로 거절한다.
        // 번호를 붙여 몇 번 더 시도한다.
        var lastError: Throwable? = null
        repeat(NAME_ATTEMPTS) { attempt ->
            val name = if (attempt == 0) displayName else numbered(displayName, attempt)
            try {
                return saveViaMediaStore(context, source, name, mimeType)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IOException(
            "저장소에 파일을 만들지 못했습니다: ${lastError?.message ?: "알 수 없는 오류"}",
            lastError,
        )
    }

    private fun numbered(displayName: String, index: Int): String {
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        return if (ext.isEmpty()) "$base-$index" else "$base-$index.$ext"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): Saved {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_MOVIES}/$FOLDER"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("저장소에 항목을 만들지 못했다")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            } ?: throw IOException("저장소에 쓸 수 없다")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }, null, null)

        source.delete()
        return Saved(uri, "$relativePath/$displayName")
    }

    /** Android 9 이하. 공용 Movies 폴더에 직접 쓰고 미디어 스캐너에 알린다. */
    private fun saveToPublicDirectory(context: Context, source: File, displayName: String): Saved {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("폴더를 만들지 못했다: $dir")

        val target = uniqueFile(dir, displayName)
        FileOutputStream(target).use { out ->
            FileInputStream(source).use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
        }
        source.delete()

        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        return Saved(Uri.fromFile(target), target.absolutePath)
    }

    /** 같은 이름이 있으면 `이름 (1).mp4` 처럼 번호를 붙인다. */
    private fun uniqueFile(dir: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"

        var candidate = File(dir, displayName)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter)$suffix")
            counter++
        }
        return candidate
    }
}
