package com.example.wificamera.net

import android.os.Build
import android.util.Log
import com.example.wificamera.util.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * 现场自动发现（热点 / 同一路由器）：
 *
 * 双方周期性向广播地址发送 HELLO，收到对端 HELLO 即回调 [onPeer]。
 *
 * 为了"连得快、连得稳"做了几件事：
 *  - 同时发 **全局广播 255.255.255.255** 和 **每个网卡的子网广播地址**（不少机型会丢弃前者）；
 *  - 本机地址/广播地址缓存 3 秒刷新一次，不再每个包都遍历网卡；
 *  - 前 4 秒用 400ms 间隔快速找，之后降到 900ms；
 *  - 排除自己的广播回环（按本机 IPv4 集合判断）。
 */
class LanDiscovery(
    private val onPeer: (InetAddress) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var found = false

    private var localAddresses: Set<String> = emptySet()
    private var broadcastAddresses: List<InetAddress> = emptyList()
    private var lastRefresh = 0L

    /** 会话代际：stop 后自增，旧线程看到代际变化就退出，避免"旧线程继续跑/把新会话的 running 置 false"。 */
    private var generation = 0

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        found = false
        val gen = ++generation
        refreshAddresses()
        thread = Thread({ loop(gen) }, "lan-discovery").apply {
            isDaemon = true
            start()
        }
        onStatus("正在自动搜索对方…（两台手机需连同一 WiFi 或热点）")
    }

    private fun loop(gen: Int) {
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
            }
            sock.bind(InetSocketAddress(DISCOVERY_PORT))
            socket = sock
            val buf = ByteArray(256)
            var lastSend = 0L
            val startedAt = System.currentTimeMillis()

            while (running && gen == generation) {
                val now = System.currentTimeMillis()
                if (now - lastRefresh > ADDRESS_REFRESH_MS) refreshAddresses()

                val fastPhase = now - startedAt < FAST_PHASE_MS
                val interval = if (fastPhase) FAST_INTERVAL_MS else SLOW_INTERVAL_MS
                if (now - lastSend >= interval) {
                    sendHello(sock)
                    lastSend = now
                }

                sock.soTimeout = 150
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length)
                    if (!text.startsWith(HELLO)) continue
                    val sender = pkt.address
                    if (sender.hostAddress in localAddresses) continue // 自己的广播回环
                    if (!found) {
                        found = true
                        AppLogger.d(TAG, "peer found ${sender.hostAddress}, locals=$localAddresses")
                        onPeer(sender)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // 正常超时，继续广播
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "discovery receive error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "discovery failed", e)
            onStatus("自动发现失败：${e.message}")
        } finally {
            runCatching { sock?.close() }
            // 只有"当前代际"的线程才有资格清理状态，否则会误杀刚启动的新会话
            if (gen == generation) {
                socket = null
                running = false
            }
        }
    }

    private fun sendHello(sock: DatagramSocket) {
        try {
            val localIp = localAddresses.firstOrNull() ?: ""
            val data = "$HELLO|$localIp|${Build.MODEL}".toByteArray()
            for (addr in broadcastAddresses) {
                runCatching {
                    sock.send(DatagramPacket(data, data.size, addr, DISCOVERY_PORT))
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "send hello failed: ${e.message}")
        }
    }

    private fun refreshAddresses() {
        lastRefresh = System.currentTimeMillis()
        val ips = mutableSetOf<String>()
        val broadcasts = mutableListOf<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address) {
                        addr.hostAddress?.let { ips.add(it) }
                        ia.broadcast?.let { broadcasts.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "enumerate interfaces failed", e)
        }
        if (broadcasts.isEmpty()) {
            runCatching { broadcasts.add(InetAddress.getByName("255.255.255.255")) }
        }
        localAddresses = ips
        broadcastAddresses = broadcasts.distinct()
    }

    fun stop() {
        running = false
        generation++ // 让旧线程立刻失效（即使它还在 receive 里）
        runCatching { socket?.close() }
        socket = null
        val t = thread
        thread = null
        // 等旧线程退出，避免新旧两个线程同时抢 8889 端口；注意不要在发现线程内部调用
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(500) }
        }
    }

    private companion object {
        const val DISCOVERY_PORT = 8889
        const val HELLO = "WIFICAM_HELLO_v1"
        const val FAST_PHASE_MS = 4_000L
        const val FAST_INTERVAL_MS = 400L
        const val SLOW_INTERVAL_MS = 900L
        const val ADDRESS_REFRESH_MS = 3_000L
        const val TAG = "LanDiscovery"
    }
}
