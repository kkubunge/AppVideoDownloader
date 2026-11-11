package com.aiutil.videodownloader.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast

/**
 * 다른 앱의 "공유하기"로 들어온 텍스트에서 주소를 뽑아 브라우저 탭으로 넘긴다.
 *
 * 공유되는 문구는 보통 "영상 보기 https://... 앱에서 확인" 처럼 설명이 섞여 있어서,
 * 텍스트 전체가 아니라 그 안의 URL을 찾아낸다.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = extractUrl(shared)

        if (url == null) {
            Toast.makeText(this, "공유된 내용에서 주소를 찾지 못했습니다", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_URL, url)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        finish()
    }

    private fun extractUrl(text: String): String? {
        val matcher = Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val candidate = matcher.group()
            if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) {
                return candidate
            }
        }
        // 스킴이 빠진 채 공유되는 경우도 있다.
        matcher.reset()
        return if (matcher.find()) "https://${matcher.group()}" else null
    }
}
