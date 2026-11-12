package com.aiutil.videodownloader.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 안에서 바로 보고 복사할 수 있는 기록.
 *
 * logcat은 PC와 개발자 도구가 있어야 볼 수 있다. 기기에서 바로 확인하고 그대로
 * 붙여 넣을 수 있어야 무엇이 잘못됐는지 주고받을 수 있다.
 */
object DebugLog {

    private const val MAX_LINES = 400
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val buffer = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(tag: String, message: String) {
        Log.d("VDL/$tag", message)
        synchronized(buffer) {
            buffer.addLast("${stamp.format(Date())} [$tag] $message")
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            _lines.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    /** 통째로 복사해 공유하기 위한 형태. */
    fun dump(): String = synchronized(buffer) {
        if (buffer.isEmpty()) "기록 없음" else buffer.joinToString("\n")
    }
}
