package com.example.wificamera.net

import android.util.Log
import com.example.wificamera.util.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP 接收器：绑定本地端口，重组 H.264 帧并回调给解码器。
 *
 * 丢包容忍：任一帧分片缺失，该帧在 [FRAME_TIMEOUT_MS] 后被丢弃，等待下一个关键帧/I 帧恢复，
 * 表现为短暂花屏，不会卡死。
 */
class UdpReceiver(
    private val onFrame: (ByteArray, Boolean) -> Unit,
    private val onPeerLearned: (InetAddress) -> Unit = {}
) {
    @Volatile
    private var running = false
    private var socket: DatagramSocket? = null

    private class Assembly(val totalSize: Int) {
        val chunks = HashMap<Int, ByteArray>()
        var received = 0
        var lastSeen = System.currentTimeMillis()
    }

    private val assemblies = ConcurrentHashMap<Int, Assembly>()

    @Volatile
    var packetsReceived: Long = 0
        private set

    @Volatile
    var bytesReceived: Long = 0
        private set

    /** 最近一次收到数据包的时间（毫秒），用于判断对端是否掉线。 */
    @Volatile
    var lastPacketAt: Long = 0
        private set

    @Volatile
    var packetsDropped: Long = 0
        private set

    fun start(port: Int = PacketProto.PORT) {
        if (running) return
        running = true
        val sock = try {
            DatagramSocket(port).apply { receiveBufferSize = 512 * 1024 }
        } catch (e: Exception) {
            running = false
            AppLogger.e(TAG, "bind port $port failed", e)
            throw e
        }
        socket = sock
        AppLogger.d(TAG, "bound UDP port $port")
        Thread({ receiveLoop(sock) }, "udp-receiver").apply { isDaemon = true; start() }
    }

    private fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                packetsReceived++
                bytesReceived += pkt.length
                lastPacketAt = System.currentTimeMillis()
                if (packetsReceived == 1L) {
                    AppLogger.d(TAG, "first packet from ${pkt.address} len=${pkt.length}")
                }
                onPeerLearned(pkt.address)
                val parsed = PacketProto.parse(pkt.data, pkt.length)
                if (parsed == null) {
                    if (packetsReceived <= 3L)
                        AppLogger.w(TAG, "parse failed len=${pkt.length}")
                    continue
                }
                if ((parsed.flags and PacketProto.FLAG_CONFIG) != 0) {
                    // 配置帧（SPS/PPS）单包传输，直接投递
                    onFrame(parsed.payload, true)
                } else {
                    handle(parsed)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "receive error: ${e.message}")
            }
        }
    }

    private fun handle(p: PacketProto.Parsed) {
        val asm = assemblies.getOrPut(p.frameId) { Assembly(p.totalSize) }
        if (asm.chunks.containsKey(p.chunkIdx)) return
        asm.chunks[p.chunkIdx] = p.payload
        asm.received += p.payload.size
        asm.lastSeen = System.currentTimeMillis()

        val isEnd = (p.flags and PacketProto.FLAG_END) != 0
        if (isEnd && asm.received >= asm.totalSize) {
            val out = ByteArray(asm.totalSize)
            for ((idx, chunk) in asm.chunks) {
                val start = idx * PacketProto.MAX_PAYLOAD
                val len = minOf(chunk.size, asm.totalSize - start)
                if (start in out.indices) {
                    System.arraycopy(chunk, 0, out, start, minOf(len, out.size - start))
                }
            }
            assemblies.remove(p.frameId)
            onFrame(out, false)
        }
        cleanupStale()
    }

    private fun cleanupStale() {
        if (assemblies.isEmpty()) return
        val now = System.currentTimeMillis()
        val it = assemblies.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastSeen > FRAME_TIMEOUT_MS) {
                packetsDropped++
                it.remove()
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        assemblies.clear()
    }

    private companion object {
        const val TAG = "UdpReceiver"
        const val FRAME_TIMEOUT_MS = 1000L
    }
}
