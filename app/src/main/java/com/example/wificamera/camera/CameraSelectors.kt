package com.example.wificamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo

/**
 * 镜头选择工具：
 *  - 默认自动选视场角最广的后置镜头；
 *  - 支持列出所有后置镜头（含 cameraId、焦段、物理子镜头），手动指定。
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(ExperimentalCamera2Interop::class)
object CameraSelectors {
    private const val TAG = "CameraSelectors"

    data class BackCamera(
        val cameraId: String,
        val focal: Float,
        val physicalIds: Collection<String>,
        val description: String
    )

    /** 用原生 CameraManager 列出所有后置镜头（信息最全）。 */
    fun listBackCameras(context: Context): List<BackCamera> {
        val result = ArrayList<BackCamera>()
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in mgr.cameraIdList) {
            val chars = mgr.getCameraCharacteristics(id)
            if (chars[CameraCharacteristics.LENS_FACING] != CameraMetadata.LENS_FACING_BACK) continue
            val focals = chars[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
            val physicalIds = runCatching { chars.physicalCameraIds }.getOrNull().orEmpty()
            val focal = focals?.minOrNull() ?: Float.MAX_VALUE
            result.add(
                BackCamera(
                    id, focal, physicalIds,
                    "镜头ID=$id 焦段=${focals?.joinToString()} 物理子镜头=${physicalIds.joinToString()}"
                )
            )
        }
        return result
    }

    /** 取某个镜头的最小焦距。 */
    fun minFocal(info: CameraInfo): Float {
        val focals = runCatching {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        }.getOrNull()
        return focals?.minOrNull() ?: Float.MAX_VALUE
    }

    /** 按 cameraId 精确选择。 */
    fun byCameraId(targetId: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                val match = infos.filter {
                    runCatching { Camera2CameraInfo.from(it).cameraId == targetId }.getOrDefault(false)
                }
                if (match.isNotEmpty()) match else infos
            }
            .build()
    }

    /** 自动选择焦距最小的后置镜头。 */
    fun widestBack(): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { cameraInfos: List<CameraInfo> ->
                val backs = cameraInfos.filter { info ->
                    val lens = runCatching {
                        Camera2CameraInfo.from(info)
                            .getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                    }.getOrNull()
                    lens == CameraMetadata.LENS_FACING_BACK
                }
                val sorted = backs.sortedBy { minFocal(it) }
                if (sorted.isNotEmpty()) {
                    Log.i(TAG, "chosen widest focal: ${minFocal(sorted.first())}")
                    listOf(sorted.first())
                } else {
                    cameraInfos
                }
            }
            .build()
    }
}
