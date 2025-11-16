package top.rootu.dddplayer.renderer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// Добавляем новый интерфейс для FPS
interface OnFpsUpdatedListener {
    fun onFpsUpdated(fps: Int)
}
interface OnSurfaceReadyListener {
    fun onSurfaceReady(surface: Surface)
}
class AnaglyphShader(
    private val glSurfaceView: GLSurfaceView,
    private val surfaceReadyListener: OnSurfaceReadyListener,
    private val fpsUpdatedListener: OnFpsUpdatedListener
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val TAG = "AnaglyphShader"

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureHandle: Int = 0
    private var texMatrixHandle: Int = 0
    private var texScaleHandle: Int = 0
    private var leftOffsetHandle: Int = 0
    private var rightOffsetHandle: Int = 0
    private var lRow1Handle: Int = 0
    private var lRow2Handle: Int = 0
    private var lRow3Handle: Int = 0
    private var rRow1Handle: Int = 0
    private var rRow2Handle: Int = 0
    private var rRow3Handle: Int = 0
    private var isVRHandle: Int = 0

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    private var currentStereoMode: StereoMode = StereoMode.NONE
    private var currentAnaglyphType: AnaglyphType = AnaglyphType.DUBOIS

    private val textureId = IntArray(1)
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var videoSurface: Surface

    private val texMatrix = FloatArray(16)
    @Volatile
    private var frameAvailable = false

    private val vertices = floatArrayOf(
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f,  1.0f,
        1.0f,  1.0f
    )

    private val texCoords = floatArrayOf(
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    )

    // Переменные для подсчета FPS
    private var frameCount = 0
    private var lastTime: Long = 0
    private var currentFps = 0

    init {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(vertices).position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        texCoordBuffer.put(texCoords).position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        setupShaders()
        setupTexture()

        surfaceTexture = SurfaceTexture(textureId[0])
        surfaceTexture.setOnFrameAvailableListener(this)
        videoSurface = Surface(surfaceTexture)

        lastTime = System.nanoTime()
        surfaceReadyListener.onSurfaceReady(videoSurface)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (frameAvailable) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                frameAvailable = false
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId[0])
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        updateShaderUniforms()

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        calculateFps()
    }

    private fun calculateFps() {
        frameCount++
        val currentTime = System.nanoTime()
        val elapsedTime = currentTime - lastTime
        if (elapsedTime >= 1_000_000_000) { // 1 секунда в наносекундах
            currentFps = frameCount
            frameCount = 0
            lastTime = currentTime
            // Вызываем callback с новым значением FPS
            fpsUpdatedListener.onFpsUpdated(currentFps)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(this) {
            frameAvailable = true
            glSurfaceView.requestRender()
        }
    }

    private fun setupTexture() {
        GLES20.glGenTextures(1, textureId, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun setupShaders() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            program = 0
            return
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_texCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "u_texture")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "u_texMatrix")
        texScaleHandle = GLES20.glGetUniformLocation(program, "u_texScale")
        leftOffsetHandle = GLES20.glGetUniformLocation(program, "u_leftOffset")
        rightOffsetHandle = GLES20.glGetUniformLocation(program, "u_rightOffset")
        lRow1Handle = GLES20.glGetUniformLocation(program, "u_l_row1")
        lRow2Handle = GLES20.glGetUniformLocation(program, "u_l_row2")
        lRow3Handle = GLES20.glGetUniformLocation(program, "u_l_row3")
        rRow1Handle = GLES20.glGetUniformLocation(program, "u_r_row1")
        rRow2Handle = GLES20.glGetUniformLocation(program, "u_r_row2")
        rRow3Handle = GLES20.glGetUniformLocation(program, "u_r_row3")
        isVRHandle = GLES20.glGetUniformLocation(program, "u_isVR")

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Could not compile shader $type: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    fun setStereoMode(mode: StereoMode) {
        currentStereoMode = mode
    }

    fun setAnaglyphType(type: AnaglyphType) {
        currentAnaglyphType = type
    }

    private fun updateShaderUniforms() {
        val texScale = floatArrayOf(1.0f, 1.0f)
        val leftOffset = floatArrayOf(0.0f, 0.0f)
        val rightOffset = floatArrayOf(0.0f, 0.0f)
        var isVR = false

        when (currentStereoMode) {
            StereoMode.NONE -> {
                isVR = true
            }
            StereoMode.SIDE_BY_SIDE -> {
                texScale[0] = 0.5f
                leftOffset[0] = 0.0f
                rightOffset[0] = 0.5f
            }
            StereoMode.TOP_BOTTOM -> {
                texScale[1] = 0.5f
                leftOffset[1] = 0.0f
                rightOffset[1] = 0.5f
            }
        }

        GLES20.glUniform2fv(texScaleHandle, 1, texScale, 0)
        GLES20.glUniform2fv(leftOffsetHandle, 1, leftOffset, 0)
        GLES20.glUniform2fv(rightOffsetHandle, 1, rightOffset, 0)
        GLES20.glUniform1i(isVRHandle, if (isVR) 1 else 0)

        val matrices = anaglyphModes[currentAnaglyphType]
        if (matrices != null) {
            GLES20.glUniform3f(lRow1Handle, matrices.l[0], matrices.l[1], matrices.l[2])
            GLES20.glUniform3f(lRow2Handle, matrices.l[3], matrices.l[4], matrices.l[5])
            GLES20.glUniform3f(lRow3Handle, matrices.l[6], matrices.l[7], matrices.l[8])
            GLES20.glUniform3f(rRow1Handle, matrices.r[0], matrices.r[1], matrices.r[2])
            GLES20.glUniform3f(rRow2Handle, matrices.r[3], matrices.r[4], matrices.r[5])
            GLES20.glUniform3f(rRow3Handle, matrices.r[6], matrices.r[7], matrices.r[8])
        } else {
            Log.e(TAG, "Anaglyph matrices not found for type: $currentAnaglyphType")
        }
    }

    fun release() {
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, textureId, 0)
        surfaceTexture.release()
        videoSurface.release()
    }

    enum class StereoMode {
        NONE,
        SIDE_BY_SIDE,
        TOP_BOTTOM
    }

    enum class AnaglyphType {
        DUBOIS,
        RC_HALF_COLOR,
        RC_COLOR,
        RC_MONO,
        RC_OPTIMIZED,
        YB_HALF_COLOR,
        YB_COLOR,
        YB_MONO,
        RB_MONO
    }

    data class AnaglyphMatrices(val l: FloatArray, val r: FloatArray)

    companion object {
        private const val FLOAT_SIZE_BYTES = 4

        private const val VERTEX_SHADER_CODE = """
            attribute vec4 a_position;
            attribute vec2 a_texCoord;
            uniform mat4 u_texMatrix;
            varying vec2 v_texCoord;
            void main() {
                gl_Position = a_position;
                // Переворачиваем координату Y
                vec2 flippedTexCoord = vec2(a_texCoord.x, 1.0 - a_texCoord.y);
                v_texCoord = (u_texMatrix * vec4(flippedTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_texture;
            uniform vec2 u_texScale;
            uniform vec2 u_leftOffset;
            uniform vec2 u_rightOffset;
            varying vec2 v_texCoord;
            uniform vec3 u_l_row1;
            uniform vec3 u_l_row2;
            uniform vec3 u_l_row3;
            uniform vec3 u_r_row1;
            uniform vec3 u_r_row2;
            uniform vec3 u_r_row3;
            uniform bool u_isVR;

            void main() {
                vec2 lTC = v_texCoord * u_texScale + u_leftOffset;
                vec2 rTC = v_texCoord * u_texScale + u_rightOffset;
                vec3 lC = texture2D(u_texture, lTC).rgb;

                if (u_isVR) {
                    gl_FragColor = vec4(lC, 1.0);
                    return;
                }

                vec3 rC = texture2D(u_texture, rTC).rgb;
                float fR = dot(u_l_row1, lC) + dot(u_r_row1, rC);
                float fG = dot(u_l_row2, lC) + dot(u_r_row2, rC);
                float fB = dot(u_l_row3, lC) + dot(u_r_row3, rC);
                gl_FragColor = vec4(clamp(vec3(fR, fG, fB), 0.0, 1.0), 1.0);
            }
        """

        val anaglyphModes = mapOf(
            AnaglyphType.DUBOIS to AnaglyphMatrices(
                l = floatArrayOf(0.456f, 0.500f, 0.176f, -0.040f, -0.038f, -0.016f, -0.015f, -0.021f, -0.005f),
                r = floatArrayOf(-0.043f, -0.088f, -0.002f, 0.378f, 0.734f, -0.018f, -0.072f, -0.013f, 1.226f)
            ),
            AnaglyphType.RC_HALF_COLOR to AnaglyphMatrices(
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0f, 0f, 0f),
                r = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            ),
            AnaglyphType.RC_COLOR to AnaglyphMatrices(
                l = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                r = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            ),
            AnaglyphType.RC_MONO to AnaglyphMatrices(
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0f, 0f, 0f),
                r = floatArrayOf(0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f)
            ),
            AnaglyphType.RC_OPTIMIZED to AnaglyphMatrices(
                l = floatArrayOf(0f, 0.450f, 1.105f, 0f, 0f, 0f, 0f, 0f, 0f),
                r = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            ),
            AnaglyphType.YB_HALF_COLOR to AnaglyphMatrices(
                l = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
                r = floatArrayOf(0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f, 0f, 0f, 0f)
            ),
            AnaglyphType.YB_COLOR to AnaglyphMatrices(
                l = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
                r = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
            ),
            AnaglyphType.YB_MONO to AnaglyphMatrices(
                l = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f),
                r = floatArrayOf(0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f, 0f, 0f, 0f)
            ),
            AnaglyphType.RB_MONO to AnaglyphMatrices(
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0f, 0f, 0f),
                r = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f)
            )
        )
    }
}
