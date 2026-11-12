package com.aiutil.videodownloader.data

import android.content.Context
import com.aiutil.videodownloader.model.Bookmark
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** 즐겨찾기 주소 목록. 최근에 추가한 것이 위로 온다. */
class BookmarkStore(context: Context) : JsonListStore<Bookmark>(
    context = context,
    fileName = "bookmarks.json",
    serializer = ListSerializer(Bookmark.serializer()),
    tag = "BookmarkStore",
) {

    fun contains(url: String): Boolean = items.value.any { sameAddress(it.url, url) }

    fun add(title: String, url: String): Boolean {
        if (url.isBlank() || contains(url)) return false
        snapshot = listOf(
            Bookmark(id = UUID.randomUUID().toString(), title = title.trim(), url = url)
        ) + snapshot
        persist()
        return true
    }

    fun removeByUrl(url: String) {
        snapshot = snapshot.filterNot { sameAddress(it.url, url) }
        persist()
    }

    fun remove(id: String) {
        snapshot = snapshot.filterNot { it.id == id }
        persist()
    }

    fun rename(id: String, title: String) {
        snapshot = snapshot.map { if (it.id == id) it.copy(title = title.trim()) else it }
        persist()
    }

    /** 끝의 슬래시나 해시 차이로 같은 주소가 중복 등록되는 것을 막는다. */
    private fun sameAddress(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(url: String): String =
        url.substringBefore('#').trimEnd('/').lowercase()
}
