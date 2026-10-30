package com.example.wificamera.net

import com.example.wificamera.util.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP 发送器：把 H.264 编码后的帧按 [PacketProto] 分包后发往对端。
 *
 * 关键点：**所有 socket 发送都在单独的发送线程上执行**。
 * MediaCodec 的回调默认跑在创建 codec 的线程（本项目是主线程）上，
 * 如果在主线程里直接 socket.send 会抛 NetworkOnMainThreadException，一帧都发不出去。
 * 所以这里用单线程队列串行发送，任何线程调用都安全。
 *
 * 同时缓存 SPS/PPS：设好对端地址后立刻补发，避免对方解码器晚启动时黑屏。
 */
class UdpTransmitter {
    private var socket: DatagramSocket? = null

    @Volatile
    private var target: InetAddress? = null

    private var targetPort = PacketProto.PORT
    private val frameIdGen = AtomicInteger(0)

    /** 最近一次的 SPS/PPS，用于补发。 */
    @Volatile
    private var lastConfig: ByteArray? = null

    @Volatile
    var bytesSent: Long = 0
        private set

    @Volatile
    var framesDropped: Long = 0
        private set

    private val pending = AtomicInteger(0)

    private val sender = Executors.newSingleThreadExecutor { r ->
        Thread(r, "udp-send").apply { isDaemon = true }
    }

    fun start() {
        if (socket != null) return
        socket = DatagramSocket().apply {
            sendBufferSize = 256 * 1024
        }
    }

    fun setTarget(host: String, port: Int = PacketProto.PORT) {
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return
        target = addr
        targetPort = port
        AppLogger.d(TAG, "target set to $host:$port")
        resendConfig()
    }

    fun isReady(): Boolean = socket != null && target != null

    fun sendConfig(csd: ByteArray) {
        if (csd.isNotEmpty()) lastConfig = csd
        enqueue(csd, CONFIG_FLAGS)
    }

    /** 重新发送缓存的 SPS/PPS（对端解码器晚启动时用）。 */
    fun resendConfig() {
        val cfg = lastConfig ?: return
        enqueue(cfg, CONFIG_FLAGS)
    }

    fun sendFrame(frame: ByteArray) {
        enqueue(frame, 0)
    }

    /**
     * 把一次发送丢进后台队列。
     * 队列积压超过 [MAX_PENDING] 时直接丢帧（比无限堆积导致延迟膨胀好）。
     */
    private fun enqueue(data: ByteArray, fixedFlags: Int) {
        if (!isReady()) return
        if (pending.get() >= MAX_PENDING) {
            framesDropped++
            return
        }
        pending.incrementAndGet()
        runCatching {
            sender.execute {
                try {
                    sendFrameInternal(data, fixedFlags)
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "send task failed", t)
                } finally {
                    pending.decrementAndGet()
                }
            }
        }.onFailure {
            pending.decrementAndGet()
        }
    }

    private fun sendFrameInternal(data: ByteArray, fixedFlags: Int) {
        val sock = socket ?: return
        val dst = target ?: return
        val frameId = frameIdGen.getAndIncrement()
        var offset = 0
        var chunk = 0
        try {
            while (offset < data.size) {
                val len = minOf(PacketProto.MAX_PAYLOAD, data.size - offset)
                var flags = 0
                if (fixedFlags != 0) {
                    flags = fixedFlags
                } else {
                    if (offset == 0) flags = flags or PacketProto.FLAG_START
                    if (offset + len == data.size) flags = flags or PacketProto.FLAG_END
                }
                val pkt = PacketProto.buildPacket(flags, frameId, data.size, chunk, data, offset, len)
                sock.send(DatagramPacket(pkt, pkt.size, dst, targetPort))
                bytesSent += pkt.size
                offset += len
                chunk++
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "send failed to $dst:$targetPort", e)
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        target = null
    }

    private companion object {
        const val TAG = "UdpTransmitter"
        const val CONFIG_FLAGS = PacketProto.FLAG_CONFIG or PacketProto.FLAG_START or PacketProto.FLAG_END

        /** 最多允许几帧排队等待发送，超出就丢帧，避免上行动作越来越慢。 */
        const val MAX_PENDING = 4
    }
}
