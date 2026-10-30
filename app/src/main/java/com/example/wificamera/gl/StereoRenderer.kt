package com.example.wificamera.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import kotlin.math.abs
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * VR 立体渲染器（双输入）：
 *  - **本机预览**：摄像头画面 -> localSurface -> OES 纹理；连接前就用它显示左右分屏；
 *  - **远端画面**：MediaCodec 解码 -> videoSurface -> OES 纹理；连上后切到这一路；
 *  - 两路都做宽高比校正 + 桶形畸变，左右眼各画一次。
 *
 * 方向处理：
 *  - 必须应用 SurfaceTexture 的变换矩阵（uStMatrix），否则画面上下颠倒；
 *  - 若矩阵含 90°/270° 旋转（解码输出是竖的），则显示宽高比要取倒数，
 *    否则会按"竖图"做信箱化，导致左右两幅变窄、中间空出一条黑边（"眼距太宽"）。
 */
class StereoRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val onSurfaceDestroyed: () -> Unit,
    private val onFrameAvailable: () -> Unit
) : GLSurfaceView.Renderer {

    /** 桶形畸变强度，0 = 关闭（默认关闭，完整显示原画）。 */
    @Volatile
    var distortion: Float = 0f

    companion object {
        /**
         * 显示宽高比增益：1.0 = 原始比例；>1 表示在宽度不动的前提下把高度压短。
         * 真机连续反馈"偏高"，目前 1.28（相对 16:9 高度约 -22%）。
         */
        const val DISPLAY_ASPECT_GAIN = 1.28f

        /** 左右两幅图整体等比缩小到该比例（围绕屏幕中心），用来收窄眼距。 */
        const val STEREO_SCALE = 0.85f
    }

    /** true = 画本机摄像头预览；false = 画远端解码画面。 */
    @Volatile
    var showLocal: Boolean = true

    /** 远端视频宽高比（按解码器报告的 buffer 尺寸），默认 16:9。 */
    @Volatile
    private var videoAspect: Float = 16f / 9f

    /** 本机预览实际宽高比（由 CameraX 回报），默认 16:9。 */
    @Volatile
    private var localAspect: Float = 16f / 9f

    fun setVideoAspect(aspect: Float) {
        if (aspect > 0.1f && aspect < 10f) videoAspect = aspect
    }

    fun setLocalAspect(aspect: Float) {
        if (aspect > 0.1f && aspect < 10f) localAspect = aspect
    }

    // 远端（解码器输出）
    private var remoteTextureId = 0
    private var remoteSurfaceTexture: SurfaceTexture? = null
    var videoSurface: Surface? = null
        private set

    // 本机（摄像头输入）
    private var localTextureId = 0
    private var localSurfaceTexture: SurfaceTexture? = null
    var localSurface: Surface? = null
        private set

    /** 左右眼画面各自向中间平移多少像素（瞳距微调）；会被可用空隙限制，避免两张图重叠。 */
    @Volatile
    var eyeShiftPx: Float = 0f

    /** 画面旋转档位：0=不转，1=90°，2=180°，3=270°（真机实测 90° 正确）。 */
    @Volatile
    var rotationSteps: Int = 1

    private var uRot = 0

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0
    private var uDistortion = 0
    private var uStMatrix = 0

    private val remoteMatrix = FloatArray(16)
    private val localMatrix = FloatArray(16)

    private val vertices = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )
    private val texCoords = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    private val vertexShader = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uStMatrix;
        uniform float uRot;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vec2 p = (uStMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            if (uRot > 2.5) {
                p = vec2(p.y, 1.0 - p.x);          // 270°
            } else if (uRot > 1.5) {
                p = vec2(1.0 - p.x, 1.0 - p.y);    // 180°
            } else if (uRot > 0.5) {
                p = vec2(1.0 - p.y, p.x);          // 90°
            }
            vTexCoord = p;
        }
    """.trimIndent()

    private val fragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        uniform float uDistortion;
        void main() {
            vec2 uv = vTexCoord;
            vec2 cc = uv - 0.5;
            float r2 = dot(cc, cc);
            // 桶形畸变：半径越大，向外拉伸越多（补偿凸透镜的枕形畸变）
            float factor = 1.0 + uDistortion * (r2 + 0.5 * r2 * r2);
            vec2 duv = 0.5 + cc * factor;
            if (duv.x < 0.0 || duv.x > 1.0 || duv.y < 0.0 || duv.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
            } else {
                gl_FragColor = texture2D(uTexture, duv);
            }
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(vertexShader, fragmentShader)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uDistortion = GLES20.glGetUniformLocation(program, "uDistortion")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uRot = GLES20.glGetUniformLocation(program, "uRot")

        remoteTextureId = createExternalTexture()
        val remoteSt = SurfaceTexture(remoteTextureId)
        remoteSt.setOnFrameAvailableListener { onFrameAvailable() }
        remoteSurfaceTexture = remoteSt
        val remoteSurface = Surface(remoteSt)
        videoSurface = remoteSurface

        localTextureId = createExternalTexture()
        val localSt = SurfaceTexture(localTextureId)
        // 关键：不设默认缓冲尺寸时 CameraX 只会给 640x480（4:3），
        // 铺到 16:9 眼窗就变成横向拉伸 + 糊成 360p。
        localSt.setDefaultBufferSize(1920, 1080)
        localSt.setOnFrameAvailableListener { onFrameAvailable() }
        localSurfaceTexture = localSt
        localSurface = Surface(localSt)

        onSurfaceReady(remoteSurface)
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val local = showLocal
        val st = if (local) localSurfaceTexture else remoteSurfaceTexture
        val textureId = if (local) localTextureId else remoteTextureId
        val matrix = if (local) localMatrix else remoteMatrix
        if (st == null) return
        st.updateTexImage()
        st.getTransformMatrix(matrix)

        // 显示宽高比：横向铺满不变，按 DISPLAY_ASPECT_GAIN 把纵向压缩一点
        // （真机反馈 16:9 时纵向偏长，这个系数就是“把高度压短”的旋钮）
        val base = if (local) localAspect else videoAspect
        val aspect = base * DISPLAY_ASPECT_GAIN

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1f(uDistortion, distortion)
        GLES20.glUniform1f(uRot, rotationSteps.toFloat())
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, matrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        val w = if (viewWidth > 0) viewWidth else 1
        val h = if (viewHeight > 0) viewHeight else 1

        val eyeW = w / 2
        val eyeH = h
        val viewAspect = eyeW.toFloat() / eyeH
        var vpW = eyeW
        var vpH = eyeH
        if (aspect > viewAspect) {
            vpH = (eyeW / aspect).toInt().coerceAtLeast(1)
        } else {
            vpW = (eyeH * aspect).toInt().coerceAtLeast(1)
        }
        val xOff = ((eyeW - vpW) / 2).coerceAtLeast(0)
        val yOff = ((eyeH - vpH) / 2).coerceAtLeast(0)

        // 瞳距微调：两张图各自向中间挪，最多挪到刚好贴边（不重叠）
        val maxShift = ((eyeW - vpW) / 2).coerceAtLeast(0).toFloat()
        val shift = eyeShiftPx.coerceIn(0f, maxShift).toInt()

        // 以屏幕中心为基准整体等比缩小（同时收窄眼距）
        val cx = w / 2f
        val cy = h / 2f
        val s = STEREO_SCALE
        fun scaledViewport(x: Int, y: Int, vw: Int, vh: Int) {
            val nx = cx + (x - cx) * s
            val ny = cy + (y - cy) * s
            GLES20.glViewport(nx.toInt(), ny.toInt(), (vw * s).toInt().coerceAtLeast(1), (vh * s).toInt().coerceAtLeast(1))
        }

        scaledViewport(xOff + shift, yOff, vpW, vpH)
        drawQuad()
        scaledViewport(w / 2 + xOff - shift, yOff, vpW, vpH)
        drawQuad()
    }

    /** 变换矩阵里是否含 90°/270° 旋转（保留备用）。 */
    private fun isQuarterTurn(m: FloatArray): Boolean =
        abs(m[0]) < 0.01f && abs(m[5]) < 0.01f

    private var viewWidth = 0
    private var viewHeight = 0

    fun setViewSize(w: Int, h: Int) {
        viewWidth = w
        viewHeight = h
    }

    private val vertexBuf = java.nio.ByteBuffer
        .allocateDirect(vertices.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(vertices)
    private val texBuf = java.nio.ByteBuffer
        .allocateDirect(texCoords.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(texCoords)

    private fun drawQuad() {
        vertexBuf.position(0)
        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    fun release() {
        onSurfaceDestroyed()
        videoSurface?.release()
        remoteSurfaceTexture?.release()
        localSurface?.release()
        localSurfaceTexture?.release()
        videoSurface = null
        remoteSurfaceTexture = null
        localSurface = null
        localSurfaceTexture = null
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            throw RuntimeException("shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    private fun createProgram(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, loadShader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, loadShader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            throw RuntimeException("program link error: ${GLES20.glGetProgramInfoLog(p)}")
        }
        return p
    }
}
