package com.example.wificamera.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 后置摄像头采集，同时输出两路：
 *  - **本机预览**：直接推到 VR 渲染器的 SurfaceTexture（连接前就是左右分屏预览）；
 *  - **编码**：推送到编码器输入 Surface [encoderSurface]，旋转角强制横屏。
 *
 * 连接成功后可以调用 [setLocalPreviewEnabled] 关掉本机预览这一路，
 * 省电、省带宽，也避免无用的缓冲堆积。
 */
class CameraCapture(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val encoderSurface: Surface,
    private val localSurface: Surface,
    private val cameraSelector: CameraSelector,
    private val encodeSize: Size = Size(1280, 720),
    private val showLocalPreview: Boolean = true
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var localPreview: Preview? = null

    private val localProvider = Preview.SurfaceProvider { request ->
        request.provideSurface(localSurface, cameraExecutor) { }
    }

    fun start(
        onReady: (String) -> Unit = {},
        onError: (String) -> Unit = {},
        onPreviewResolution: (Int, Int) -> Unit = { _, _ -> }
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                // 本机预览 -> VR 双屏渲染
                // 注意：Camerax 1.3 里 setTargetResolution 已弃用且会被忽略（实测仍给 640x480），
                // 必须用 ResolutionSelector + ResolutionStrategy 才能真正拿到 1080P。
                val rotation = effectiveRotation()
                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            encodeSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(selector)
                    .setTargetRotation(rotation)
                    .build()
                localPreview = preview
                if (showLocalPreview) preview.setSurfaceProvider(localProvider)

                // 编码器输入：同样的分辨率与旋转
                val encoderPreview = Preview.Builder()
                    .setResolutionSelector(selector)
                    .setTargetRotation(rotation)
                    .build()
                encoderPreview.setSurfaceProvider { request ->
                    request.provideSurface(encoderSurface, cameraExecutor) { }
                }

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    owner,
                    cameraSelector,
                    preview,
                    encoderPreview
                )
                val boundFocal = CameraSelectors.minFocal(camera.cameraInfo)
                // 把 CameraX 实际使用的预览分辨率回报给渲染层，用于精确校正宽高比
                runCatching {
                    preview.resolutionInfo?.let {
                        Log.i(TAG, "preview resolution=${it.resolution}")
                        onPreviewResolution(it.resolution.width, it.resolution.height)
                    }
                }
                val report = "实际选中焦距=$boundFocal"
                Log.i(TAG, "camera bound rotation=$rotation $report")
                onReady(report)
            } catch (e: Exception) {
                onError(e.message ?: "摄像头启动失败")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 连接后关掉本机预览这一路，断开时再打开。 */
    fun setLocalPreviewEnabled(enabled: Boolean) {
        val p = localPreview ?: return
        runCatching {
            if (enabled) p.setSurfaceProvider(localProvider) else p.setSurfaceProvider(null)
        }
    }

    /**
     * CameraX 目标旋转角：直接用系统报告的显示旋转（不再做横屏换算）。
     *
     * 之前的"横屏换算"会在自动旋转关闭时把 0° 改成 90°，导致摄像头输出竖屏帧，
     * 渲染端再按 16:9 铺开就变成横向拉伸、观感严重掉画质。
     */
    private fun effectiveRotation(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay.rotation
    }

    fun stop() {
        provider?.unbindAll()
        cameraExecutor.shutdown()
    }

    private companion object {
        const val TAG = "CameraCapture"
    }
}
