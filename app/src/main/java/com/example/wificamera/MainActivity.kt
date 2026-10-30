package com.example.wificamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.wificamera.camera.CameraCapture
import com.example.wificamera.camera.CameraSelectors
import com.example.wificamera.gl.StereoRenderer
import com.example.wificamera.media.VideoDecoder
import com.example.wificamera.media.VideoEncoder
import com.example.wificamera.net.LanDiscovery
import com.example.wificamera.net.UdpReceiver
import com.example.wificamera.net.UdpTransmitter
import com.example.wificamera.util.AppLogger
import java.net.InetAddress

/**
 * 现场双向摄像头：自动发现 → 自动连接 → 双向互传。
 *
 * 画面始终由 [StereoRenderer] 输出左右分屏：
 *  - 连接前：本机摄像头预览（先摆好眼镜盒、预览 VR 效果）；
 *  - 连接后：自动切到对端画面。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvDiag: TextView
    private lateinit var vrView: android.opengl.GLSurfaceView
    private lateinit var panelTop: View
    private lateinit var panelBottom: View
    private lateinit var immersiveCatcher: View
    private lateinit var renderer: StereoRenderer

    private val targetFps = 30

    /** 画质档位：默认 1080P，可切到 720P（两端最好选同一档）。 */
    private var use1080p = true

    private val encodeWidth: Int get() = if (use1080p) 1920 else 1280
    private val encodeHeight: Int get() = if (use1080p) 1080 else 720

    private fun makeEncoder(): VideoEncoder {
        val bitRate = if (use1080p) 8_000_000 else 2_500_000
        return VideoEncoder(encodeWidth, encodeHeight, targetFps, bitRate) { data, isConfig ->
            if (isConfig) transmitter.sendConfig(data) else transmitter.sendFrame(data)
        }
    }
    private var encoder: VideoEncoder = makeEncoder()
    private val decoder = VideoDecoder { w, h ->
        // 解码器报告真实分辨率后，让渲染层按宽高比做信箱化，避免拉伸
        if (::renderer.isInitialized) renderer.setVideoAspect(w.toFloat() / h)
    }
    private val transmitter = UdpTransmitter()
    private lateinit var receiver: UdpReceiver
    private var lanDiscovery: LanDiscovery? = null
    private var camera: CameraCapture? = null

    private var cameraStarted = false
    private var cameraRetries = 0
    private var chosenSelector: androidx.camera.core.CameraSelector = CameraSelectors.widestBack()

    @Volatile
    private var connected = false

    private var immersive = false
    private var receiverIsRunning = false

    /** 画面旋转档位（0/1/2/3 = 0°/90°/180°/270°）；真机实测 90° 正确。 */
    private var rotationIndex = 1

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastEncFrames = 0L
    private var lastDecFrames = 0L
    private var lastRxBytes = 0L
    private var tick = 0

    private val fpsRunnable = object : Runnable {
        override fun run() {
            val encNow = encoder.framesEncoded
            val decNow = decoder.framesDecoded
            tvFps.text = "发送 FPS：${encNow - lastEncFrames}  接收 FPS：${decNow - lastDecFrames}"
            lastEncFrames = encNow
            lastDecFrames = decNow

            val rxBytes = receiver.bytesReceived
            val kbps = ((rxBytes - lastRxBytes) * 8 / 1000).coerceAtLeast(0)
            lastRxBytes = rxBytes
            tvDiag.text = "已发送：${transmitter.bytesSent / 1024} KB  " +
                "接收：${kbps} kbps  丢帧：${receiver.packetsDropped}"

            // 每 3 秒补发一次 SPS/PPS：对方解码器可能比本机编码器晚启动
            tick++
            if (connected && tick % 3 == 0) transmitter.resendConfig()

            mainHandler.postDelayed(this, 1000)
        }
    }

    /** 对端掉线看门狗：连着但 6 秒没包 → 重新搜索。 */
    private val watchdog = object : Runnable {
        override fun run() {
            if (connected) {
                val last = receiver.lastPacketAt
                if (last > 0 && System.currentTimeMillis() - last > PEER_TIMEOUT_MS) {
                    AppLogger.w(TAG, "peer lost (no packet for ${System.currentTimeMillis() - last}ms)")
                    connected = false
                    renderer.showLocal = true
                    camera?.setLocalPreviewEnabled(true)
                    stopDiscovery()
                    tvStatus.text = "状态：对方画面中断，正在重新搜索…"
                    startDiscovery()
                }
            }
            mainHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppLogger.init(this)
        AppLogger.d(TAG, "app onCreate, sdk=${Build.VERSION.SDK_INT}")
        setContentView(R.layout.activity_main)

        // 户外使用：保持屏幕常亮 + 最高亮度
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1.0f }

        tvStatus = findViewById(R.id.tvStatus)
        tvFps = findViewById(R.id.tvFps)
        tvDiag = findViewById(R.id.tvDiag)
        vrView = findViewById(R.id.vrView)
        panelTop = findViewById(R.id.panelTop)
        panelBottom = findViewById(R.id.panelBottom)
        immersiveCatcher = findViewById(R.id.immersiveCatcher)

        receiver = UdpReceiver(
            onFrame = { data, isConfig ->
                decoder.enqueue(data, isConfig, System.nanoTime() / 1000)
                // 只在还没切到远端画面时投一次，避免每帧都往主线程塞任务
                if (!isConfig && renderer.showLocal) {
                    mainHandler.post { onFirstRemoteFrame() }
                }
            },
            onPeerLearned = { addr -> handlePeer(addr, fromPacket = true) }
        )

        renderer = StereoRenderer(
            onSurfaceReady = { surface ->
                // MediaCodec 回调依赖 Looper，切回主线程配置解码器
                mainHandler.post { decoder.start(surface, encodeWidth, encodeHeight) }
            },
            onSurfaceDestroyed = {
                mainHandler.post { decoder.stop() }
            },
            onFrameAvailable = { vrView.requestRender() }
        )
        vrView.setEGLContextClientVersion(2)
        vrView.setRenderer(renderer)
        vrView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY
        vrView.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            renderer.setViewSize(r - l, b - t)
        }
        applyIpd()

        findViewById<Button>(R.id.btnStartCamera).setOnClickListener {
            requestPermissionsThen { startCamera() }
        }
        findViewById<Button>(R.id.btnSelectCamera).setOnClickListener {
            requestPermissionsThen { showCameraPicker() }
        }
        findViewById<Button>(R.id.btnResolution).setOnClickListener {
            requestPermissionsThen { showQualityPicker() }
        }
        findViewById<Button>(R.id.btnOpenHotspot).setOnClickListener { openHotspotSettings() }
        findViewById<Button>(R.id.btnConnectHotspot).setOnClickListener { openWifiSettings() }
        findViewById<Button>(R.id.btnAutoConnect).setOnClickListener {
            requestPermissionsThen {
                connected = false
                renderer.showLocal = true
                camera?.setLocalPreviewEnabled(true)
                stopDiscovery()
                startDiscovery()
                toast("正在搜索对方…")
            }
        }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.btnImmersive).setOnClickListener { toggleImmersive() }
        findViewById<Button>(R.id.btnRotate).setOnClickListener { cycleRotation() }
        findViewById<Button>(R.id.btnLog).setOnClickListener { showLog() }
        immersiveCatcher.setOnClickListener { toggleImmersive() }

        mainHandler.post(fpsRunnable)
        mainHandler.postDelayed(watchdog, 2000)
        // 启动即自动搜索：用户只需要点"开摄像头"
        requestPermissionsThen { startDiscovery() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------- 瞳距 ----------------

    /**
     * 固定把左右两幅图各向中间挪 [EYE_SHIFT_MM] 毫米（比 17 版略窄），
     * 挪到头会被"刚好贴边不重叠"限制住。
     */
    private fun applyIpd() {
        val pxPerMm = resources.displayMetrics.xdpi / 25.4f
        renderer.eyeShiftPx = EYE_SHIFT_MM * pxPerMm
        vrView.requestRender()
    }

    // ---------------- 沉浸模式 ----------------

    /** 画面旋转：0°/90°/180°/270° 循环。 */
    private fun cycleRotation() {
        rotationIndex = (rotationIndex + 1) % 4
        renderer.rotationSteps = rotationIndex
        vrView.requestRender()
        toast("画面旋转 ${rotationIndex * 90}°（记住这个数值告诉我）")
    }

    private fun toggleImmersive() {
        immersive = !immersive
        panelTop.visibility = if (immersive) View.GONE else View.VISIBLE
        panelBottom.visibility = if (immersive) View.GONE else View.VISIBLE
        immersiveCatcher.visibility = if (immersive) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnImmersive).text = if (immersive) "退出" else "沉浸"
        if (immersive) hideSystemBars()
    }

    // ---------------- 权限 ----------------

    private fun requiredPermissions(): Array<String> = arrayOf(Manifest.permission.CAMERA)

    private var pendingAction: (() -> Unit)? = null

    private fun requestPermissionsThen(action: () -> Unit) {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ensureReceiverRunning()
            action()
            return
        }
        pendingAction = action
        requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        val denied = permissions.filterIndexed { i, _ ->
            grantResults[i] != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            ensureReceiverRunning()
            pendingAction?.invoke()
        } else {
            AlertDialog.Builder(this)
                .setTitle("需要摄像头权限")
                .setMessage("摄像头是本应用的核心权限，没有它无法采集画面。请在系统设置中手动授予。")
                .setPositiveButton("重试") { _, _ -> requestPermissionsThen { pendingAction?.invoke() } }
                .setNegativeButton("取消", null)
                .show()
        }
        pendingAction = null
    }

    // ---------------- 摄像头 ----------------

    private fun startCamera() {
        if (cameraStarted) {
            Toast.makeText(this, "摄像头已开启", Toast.LENGTH_SHORT).show()
            return
        }
        val localSurface = renderer.localSurface
        if (localSurface == null) {
            // GL 表面还没准备好（刚启动时可能），稍后重试
            if (cameraRetries < 10) {
                cameraRetries++
                mainHandler.postDelayed({ startCamera() }, 300)
            } else {
                toast("画面初始化失败，请重试")
            }
            return
        }
        cameraRetries = 0
        try {
            encoder.start()
            val surface = encoder.inputSurface
                ?: run { toast("编码器初始化失败"); return }
            val capture = CameraCapture(
                context = this,
                owner = this,
                encoderSurface = surface,
                localSurface = localSurface,
                cameraSelector = chosenSelector,
                encodeSize = android.util.Size(encodeWidth, encodeHeight),
                showLocalPreview = renderer.showLocal
            )
            capture.start(
                onReady = { _ ->
                    cameraStarted = true
                    if (!connected) tvStatus.text = "状态：预览中，正在搜索对方…"
                    if (connected) encoder.requestSyncFrame()
                },
                onError = { msg ->
                    toast("摄像头启动失败：$msg")
                    encoder.stop()
                },
                onPreviewResolution = { w, h ->
                    renderer.setLocalAspect(w.toFloat() / h)
                    vrView.requestRender()
                }
            )
            camera = capture
        } catch (e: Exception) {
            toast("启动失败：${e.message}")
        }
    }

    /** 收到第一帧远端画面：从本机预览切到对方画面。 */
    private fun onFirstRemoteFrame() {
        if (renderer.showLocal) {
            renderer.showLocal = false
            camera?.setLocalPreviewEnabled(false)
            vrView.requestRender()
            tvStatus.text = "状态：已连接，正在显示对方第一视角"
        }
    }

    /** 画质切换：1080P（默认）/ 720P。切换会重建编码器与解码器并重启采集。 */
    private fun showQualityPicker() {
        val options = arrayOf("1080P（清晰，默认）", "720P（省带宽、更稳）")
        val checked = if (use1080p) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("选择画质（当前：${if (use1080p) "1080P" else "720P"}）")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val newUse1080 = which == 0
                if (newUse1080 != use1080p) {
                    use1080p = newUse1080
                    updateQualityLabel()
                    restartPipeline()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateQualityLabel() {
        findViewById<Button>(R.id.btnResolution).text =
            "画质：${if (use1080p) "1080P" else "720P"}"
    }

    /** 画质变化：重建编码器 / 解码器，并重启采集。 */
    private fun restartPipeline() {
        val wasRunning = cameraStarted
        camera?.stop()
        camera = null
        cameraStarted = false
        encoder.stop()
        encoder = makeEncoder()
        val surface = renderer.videoSurface
        decoder.stop()
        if (surface != null) decoder.start(surface, encodeWidth, encodeHeight)
        if (wasRunning) startCamera()
    }

    /** 列出所有后置镜头，手动选择要使用的镜头（可强制切到超广角）。 */
    private fun showCameraPicker() {
        val backs = CameraSelectors.listBackCameras(this)
        if (backs.isEmpty()) {
            toast("未识别到后置镜头")
            return
        }
        val labels = backs.map {
            val tag = if (it.focal < 3f) "（广角/超广角）" else ""
            "${it.description} $tag"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择后置镜头（共 ${backs.size} 个）")
            .setItems(labels) { _, which ->
                val picked = backs[which]
                chosenSelector = CameraSelectors.byCameraId(picked.cameraId)
                tvStatus.text = "状态：已选择 ${picked.description}"
                if (cameraStarted) {
                    camera?.stop()
                    encoder.stop()
                    cameraStarted = false
                    startCamera()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 连接 ----------------

    private fun ensureReceiverRunning() {
        if (receiverIsRunning) return
        try {
            receiver.start()
            transmitter.start()
            receiverIsRunning = true
            AppLogger.d(TAG, "receiver & transmitter started")
        } catch (e: Exception) {
            AppLogger.e(TAG, "receiver start failed", e)
            tvStatus.text = "状态：端口 8888 被占用，请关闭其他正在使用它的应用"
        }
    }

    private fun startDiscovery() {
        if (!receiverIsRunning) ensureReceiverRunning()
        if (lanDiscovery == null) {
            lanDiscovery = LanDiscovery(
                onPeer = { addr -> handlePeer(addr, fromPacket = false) },
                onStatus = { msg ->
                    if (!connected) tvStatus.text = "状态：$msg"
                }
            )
        }
        lanDiscovery?.start()
    }

    private fun stopDiscovery() {
        lanDiscovery?.stop()
        lanDiscovery = null
    }

    /** 发现对端（广播或收到对方数据包均会调用，可能来自任意线程）。 */
    private fun handlePeer(addr: InetAddress, fromPacket: Boolean) {
        if (connected) return
        val host = addr.hostAddress ?: return
        if (host == "127.0.0.1") return
        connected = true
        AppLogger.d(TAG, "peer connected: $host (fromPacket=$fromPacket)")
        transmitter.setTarget(host)        // 内部会补发缓存的 SPS/PPS
        // 停止发现、开摄像头、请求关键帧都放到主线程：发现回调发生在发现线程上，
        // 在其中调用 stop() 会 join 自己导致卡死。
        mainHandler.post {
            stopDiscovery()
            tvStatus.text = "状态：已连接 $host，正在同步画面…"
            if (!cameraStarted) startCamera()
            encoder.requestSyncFrame()     // 立刻产出一个 IDR，对端马上有画面
        }
    }

    private fun disconnect() {
        connected = false
        stopDiscovery()
        renderer.showLocal = true
        camera?.setLocalPreviewEnabled(true)
        if (!cameraStarted) startCamera()
        vrView.requestRender()
        tvStatus.text = "状态：已断开，显示本机预览"
    }

    // ---------------- 热点设置入口 ----------------

    /** 打开手机热点设置（系统不允许第三方 App 直接开启热点）。 */
    private fun openHotspotSettings() {
        val intents = listOf(
            Intent("android.intent.action.MAIN_TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (i in intents) {
            try {
                startActivity(i)
                tvStatus.text = "状态：请开启【个人热点】，设好密码后回到本 App"
                return
            } catch (_: Exception) { /* 尝试下一个 */ }
        }
        toast("无法打开热点设置，请手动进入 设置 → 个人热点")
    }

    /** 打开 WiFi 设置，让另一台手机连接热点。 */
    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            tvStatus.text = "状态：请连接对方手机的热点，连上后回到本 App"
        } catch (_: Exception) {
            toast("无法打开WiFi设置，请手动进入")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        AppLogger.w(TAG, msg)
    }

    /** 查看日志：可清空或通过系统分享导出 */
    private fun showLog() {
        val text = AppLogger.read()
        AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setMessage(text)
            .setPositiveButton("分享导出") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "双人成行运行日志")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, "导出日志"))
            }
            .setNeutralButton("清空") { _, _ -> AppLogger.clear() }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        camera?.stop()
        renderer.release()
        encoder.stop()
        decoder.stop()
        transmitter.stop()
        receiver.stop()
        stopDiscovery()
    }

    private companion object {
        const val REQ_PERMISSIONS = 1001
        const val TAG = "MainActivity"
        const val PEER_TIMEOUT_MS = 6_000L

        /** 左右眼画面各自向中间平移的毫米数（在 17 版基础上再收 15mm）。 */
        const val EYE_SHIFT_MM = 0f
    }
}
