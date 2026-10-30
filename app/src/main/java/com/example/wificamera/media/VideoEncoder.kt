package com.example.wificamera.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * H.264 硬编码器。CameraX 直接把画面推送到 [inputSurface]，
 * 编码输出（SPS/PPS 配置帧 + 视频帧）通过 [onEncoded] 回调。
 *
 * 回调必须跑在**独立线程**上：默认会投递到创建 codec 的线程（本项目是主线程），
 * 那样每帧的 socket 发送都会触发 NetworkOnMainThreadException，并且阻塞 UI。
 */
class VideoEncoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val frameRate: Int = 30,
    private val bitRate: Int = 2_500_000,
    private val onEncoded: (ByteArray, Boolean) -> Unit
) {
    private var codec: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    var inputSurface: Surface? = null
        private set

    @Volatile var framesEncoded: Long = 0
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 每 1 秒一个关键帧，丢包后快速恢复
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val thread = HandlerThread("video-encoder").apply { start() }
        codecThread = thread
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { /* Surface 输入，无需处理 */ }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                val buffer = codec.getOutputBuffer(index) ?: return
                val data = ByteArray(info.size)
                buffer.get(data)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                onEncoded(data, isConfig)
                if (!isConfig) framesEncoded++
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, Handler(thread.looper))
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
    }

    /** 请求立刻产出一个关键帧（IDR），让刚连上的对端马上有完整画面。 */
    fun requestSyncFrame() {
        val c = codec ?: return
        runCatching {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            c.setParameters(params)
        }.onFailure { Log.w(TAG, "requestSyncFrame failed: ${it.message}") }
    }

    fun stop() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        inputSurface?.release()
        inputSurface = null
        codec = null
        codecThread?.quitSafely()
        codecThread = null
    }

    private companion object {
        const val TAG = "VideoEncoder"
    }
}
