package com.aiutil.videodownloader.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.aiutil.videodownloader.core.DebugLog
import java.io.File
import java.nio.ByteBuffer

/**
 * 이어 붙인 스트림을 일반 MP4로 다시 담는다. 재인코딩 없이 컨테이너만 바꾸므로
 * 빠르고 화질 손실이 없다.
 *
 * 두 가지 입력을 모두 다룬다.
 *  - TS: 갤러리 앱이 인식하지 못하는 경우가 많다.
 *  - 조각난 MP4(fMP4): 조각을 이어 붙이면 확장자는 mp4지만 색인(moov)이 없어
 *    갤러리와 기본 재생기가 열지 못한다. 겉보기엔 멀쩡한 MP4라 더 헷갈린다.
 *
 * 둘 다 여기서 색인이 있는 보통의 MP4로 바뀐다. 실패하면 호출자가 원본을 쓴다.
 */
object Mp4Remuxer {

    private const val TAG = "Mp4Remuxer"

    /** 조각 하나가 통째로 담길 만큼 넉넉히 잡는다. 작으면 읽다가 터진다. */
    private const val MIN_BUFFER = 2 shl 20

    /**
     * 마지막 변환이 왜 실패했는지. 재생이 안 될 때 원인을 가리기 위해 남긴다.
     * 로그만으로는 기기에서 확인할 수 없다.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * [sources]의 트랙을 모아 [dst] 하나로 담는다.
     *
     * 영상과 소리가 따로 온 스트림은 파일이 둘이다. 같은 종류의 트랙은 처음 것만
     * 쓴다 — 영상 쪽에 소리가 이미 들어 있는데 소리 파일까지 더하면 두 번 들어간다.
     *
     * @return 성공하면 true. 실패 시 [dst]는 정리된다.
     */
    fun remux(sources: List<File>, dst: File): Boolean {
        val inputs = sources.filter { it.exists() && it.length() > 0 }
        if (inputs.isEmpty()) {
            lastFailure = "합칠 원본이 없다"
            return false
        }
        DebugLog.log("변환", "시작 · 원본 ${inputs.size}개 · ${inputs.sumOf { it.length() }}바이트")

        val extractors = inputs.map { MediaExtractor() }
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 1단계: 모든 원본의 트랙을 먼저 등록한다. start() 뒤에는 추가할 수 없다.
            val plans = ArrayList<SourcePlan>(inputs.size)
            val takenKinds = HashSet<String>()
            var bufferSize = MIN_BUFFER

            inputs.forEachIndexed { index, file ->
                val extractor = extractors[index]
                extractor.setDataSource(file.absolutePath)

                val trackMap = HashMap<Int, Int>()
                val videoTracks = HashSet<Int>()

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val kind = mime.substringBefore('/')
                    if (kind != "video" && kind != "audio") continue
                    if (!takenKinds.add(kind)) continue

                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        bufferSize =
                            maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    if (kind == "video") videoTracks += i
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(format)
                    DebugLog.log("변환", "트랙 $mime (원본 ${index + 1})")
                }
                plans += SourcePlan(extractor, trackMap, videoTracks)
            }

            check(plans.any { it.trackMap.isNotEmpty() }) { "옮길 영상/음성 트랙이 없다" }

            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            var written = 0
            plans.forEach { written += copySamples(it, muxer, buffer, info) }

            check(written > 0) { "옮긴 샘플이 하나도 없다" }
            lastFailure = null
            DebugLog.log("변환", "성공 · 샘플 ${written}개 · ${dst.length()}바이트")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "MP4 변환 실패, 원본을 그대로 쓴다", t)
            lastFailure = "${t::class.java.simpleName}: ${t.message ?: "원인 불명"}"
            DebugLog.log("변환", "실패 · $lastFailure")
            false
        } finally {
            extractors.forEach { runCatching { it.release() } }
            muxer?.let { m ->
                runCatching { if (muxerStarted) m.stop() }
                runCatching { m.release() }
            }
        }.also { success ->
            if (!success) dst.delete()
        }
    }

    private class SourcePlan(
        val extractor: MediaExtractor,
        val trackMap: Map<Int, Int>,
        val videoTracks: Set<Int>,
    )

    /** 원본 하나의 샘플을 전부 옮긴다. @return 옮긴 샘플 수. */
    private fun copySamples(
        plan: SourcePlan,
        muxer: MediaMuxer,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ): Int {
        if (plan.trackMap.isEmpty()) return 0

        // TS의 PTS는 0이 아닌 값에서 시작한다. 그대로 쓰면 앞에 긴 공백이 생긴다.
        var ptsOffset = -1L

        /**
         * 조각을 이어 붙인 스트림은 경계에서 타임스탬프가 뒤로 가는 일이 흔하다.
         * MediaMuxer는 그걸 거부하고 변환 전체가 실패하므로, 트랙별로 마지막 값을
         * 기억해 두고 되돌아가면 살짝 밀어 단조증가를 맞춰 준다.
         */
        val lastPts = HashMap<Int, Long>()
        var written = 0
        var seenKeyFrame = false

        while (true) {
            val size = plan.extractor.readSampleData(buffer, 0)
            if (size < 0) break

            val inputTrack = plan.extractor.sampleTrackIndex
            val outputTrack = plan.trackMap[inputTrack]
            if (outputTrack == null) {
                if (!plan.extractor.advance()) break
                continue
            }

            val isSync = plan.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0

            // 첫 키프레임 이전의 영상 샘플은 혼자서는 디코딩되지 않는다. 버린다.
            if (inputTrack in plan.videoTracks) {
                if (!seenKeyFrame && !isSync) {
                    if (!plan.extractor.advance()) break
                    continue
                }
                seenKeyFrame = true
            }

            val time = plan.extractor.sampleTime
            if (ptsOffset < 0) ptsOffset = if (time > 0) time else 0

            var pts = (time - ptsOffset).coerceAtLeast(0L)
            val previous = lastPts[outputTrack]
            if (previous != null && pts <= previous) pts = previous + 1

            info.offset = 0
            info.size = size
            info.presentationTimeUs = pts
            info.flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

            muxer.writeSampleData(outputTrack, buffer, info)
            lastPts[outputTrack] = pts
            written++

            if (!plan.extractor.advance()) break
        }
        return written
    }
}
