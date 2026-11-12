package com.aiutil.videodownloader.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 이어 붙인 TS 스트림을 MP4로 다시 담는다. 재인코딩 없이 컨테이너만 바꾸므로 빠르고
 * 화질 손실이 없다.
 *
 * TS 파일도 대부분의 재생기가 열 수 있지만 갤러리 앱이 인식하지 못하는 경우가 많아,
 * 가능하면 MP4로 바꿔 둔다. 실패하면 호출자가 원본을 그대로 쓰면 된다.
 */
object TsRemuxer {

    private const val TAG = "TsRemuxer"

    /** 조각 하나가 통째로 담길 만큼 넉넉히 잡는다. 작으면 읽다가 터진다. */
    private const val MIN_BUFFER = 2 shl 20

    /** @return 성공하면 true. 실패 시 [dst]는 정리된다. */
    fun remux(src: File, dst: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            extractor.setDataSource(src.absolutePath)
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = HashMap<Int, Int>()
            val videoTracks = HashSet<Int>()
            var bufferSize = MIN_BUFFER

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (mime.startsWith("video/")) videoTracks += i
                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(format)
            }
            check(trackMap.isNotEmpty()) { "옮길 영상/음성 트랙이 없다" }

            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()

            // TS의 PTS는 0이 아닌 값에서 시작한다. 그대로 쓰면 앞에 긴 공백이 생긴다.
            var ptsOffset = -1L

            /**
             * 조각을 이어 붙인 TS는 경계에서 타임스탬프가 뒤로 가는 일이 흔하다.
             * MediaMuxer는 그걸 거부하고 변환 전체가 실패하므로, 트랙별로 마지막 값을
             * 기억해 두고 되돌아가면 살짝 밀어 단조증가를 맞춰 준다.
             */
            val lastPts = HashMap<Int, Long>()
            var written = 0
            var seenKeyFrame = false

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                val inputTrack = extractor.sampleTrackIndex
                val outputTrack = trackMap[inputTrack]
                if (outputTrack == null) {
                    if (!extractor.advance()) break
                    continue
                }

                val isSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0

                // 첫 키프레임 이전의 영상 샘플은 혼자서는 디코딩되지 않는다. 버린다.
                if (inputTrack in videoTracks) {
                    if (!seenKeyFrame && !isSync) {
                        if (!extractor.advance()) break
                        continue
                    }
                    seenKeyFrame = true
                }

                val time = extractor.sampleTime
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

                if (!extractor.advance()) break
            }

            check(written > 0) { "옮긴 샘플이 하나도 없다" }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "MP4 변환 실패, 원본을 그대로 쓴다", t)
            false
        } finally {
            runCatching { extractor.release() }
            muxer?.let { m ->
                runCatching { if (muxerStarted) m.stop() }
                runCatching { m.release() }
            }
        }.also { success ->
            if (!success) dst.delete()
        }
    }
}
