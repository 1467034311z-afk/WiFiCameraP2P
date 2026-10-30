package com.example.wificamera.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 硬解码器。接收 UDP 重组后的 SPS/PPS 与视频帧，
 * 解码后直接渲染到 VR 渲染器提供的 Surface。
 *
 * 回调放在独立 HandlerThread 上，避免每帧的 releaseOutputBuffer 占用主线程。
 */
class VideoDecoder(
    /** 解码输出格式变化时回调（宽, 高），用于让渲染层做宽高比校正。 */
    private val onFormat: (Int, Int) -> Unit = { _, _ -> }
) {
    private var codec: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    private var outputSurface: Surface? = null

    @Volatile var framesDecoded: Long = 0
        private set

    /** 必须在 VR 渲染器的 SurfaceTexture 可用后调用。 */
    fun start(surface: Surface, width: Int = 1920, height: Int = 1080) {
        if (codec != null) return
        outputSurface = surface
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // 初始不携带 csd，SPS/PPS 通过带 BUFFER_FLAG_CODEC_CONFIG 的输入缓冲下发
        decoder.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height),
            surface, null, 0)
        val thread = HandlerThread("video-decoder").apply { start() }
        codecThread = thread
        decoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // 帧由 enqueue 主动投递；输入缓冲空闲时无需处理
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                codec.releaseOutputBuffer(index, info.size > 0)
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    framesDecoded++
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "decoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "decoder output format: $format")
                val w = format.getInteger(MediaFormat.KEY_WIDTH, 0)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
                if (w > 0 && h > 0) onFormat(w, h)
            }
        }, Handler(thread.looper))
        decoder.start()
        codec = decoder
    }

    /** 投递一帧数据；isConfig=true 表示 SPS/PPS。 */
    fun enqueue(data: ByteArray, isConfig: Boolean, pts: Long) {
        val decoder = codec ?: return
        try {
            val index = decoder.dequeueInputBuffer(10_000)
            if (index < 0) return
            val input: ByteBuffer = decoder.getInputBuffer(index) ?: return
            input.clear()
            input.put(data)
            var flags = 0
            if (isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            decoder.queueInputBuffer(index, 0, data.size, if (isConfig) 0 else pts, flags)
        } catch (e: Exception) {
            Log.w(TAG, "enqueue failed: ${e.message}")
        }
    }

    fun stop() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        outputSurface = null
        codecThread?.quitSafely()
        codecThread = null
    }

    private companion object {
        const val TAG = "VideoDecoder"
    }
}
