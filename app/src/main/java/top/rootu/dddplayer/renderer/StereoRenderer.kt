package top.rootu.dddplayer.renderer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

interface OnFpsUpdatedListener {
    fun onFpsUpdated(fps: Int)
}

interface OnSurfaceReadyListener {
    fun onSurfaceReady(surface: Surface)
}

class StereoRenderer(
    private val glSurfaceView: GLSurfaceView,
    private val surfaceReadyListener: OnSurfaceReadyListener,
    private val fpsUpdatedListener: OnFpsUpdatedListener
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val TAG = "StereoRenderer"

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureHandle: Int = 0
    private var texMatrixHandle: Int = 0
    private var lRow1Handle: Int = 0
    private var lRow2Handle: Int = 0
    private var lRow3Handle: Int = 0
    private var rRow1Handle: Int = 0
    private var rRow2Handle: Int = 0
    private var rRow3Handle: Int = 0
    private var inputTypeHandle: Int = 0
    private var outputModeHandle: Int = 0
    private var swapEyesHandle: Int = 0
    private var videoDimensionsHandle: Int = 0
    private var screenAspectRatioHandle: Int = 0
    private var singleFrameDimensionsHandle: Int = 0

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    @Volatile private var currentInputType: StereoInputType = StereoInputType.NONE
    @Volatile private var currentOutputMode: StereoOutputMode = StereoOutputMode.ANAGLYPH
    @Volatile private var currentAnaglyphType: AnaglyphType = AnaglyphType.DUBOIS
    @Volatile private var swapEyes: Boolean = false
    @Volatile private var videoWidth: Int = 1920
    @Volatile private var videoHeight: Int = 1080
    private var screenAspectRatio: Float = 16f / 9f
    @Volatile private var singleFrameWidth: Float = 1920f
    @Volatile private var singleFrameHeight: Float = 1080f

    private val textureId = IntArray(1)
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null

    private val texMatrix = FloatArray(16)
    @Volatile
    private var frameAvailable = false

    private var frameCount = 0
    private var lastTime: Long = 0
    private var currentFps = 0

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

    init {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(vertices).position(0)
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        texCoordBuffer.put(texCoords).position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        setupShaders()
        setupTexture()

        surfaceTexture = SurfaceTexture(textureId[0])
        surfaceTexture?.setOnFrameAvailableListener(this)
        videoSurface = Surface(surfaceTexture)

        lastTime = System.nanoTime()
        videoSurface?.let { surfaceReadyListener.onSurfaceReady(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        screenAspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
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
        lRow1Handle = GLES20.glGetUniformLocation(program, "u_l_row1")
        lRow2Handle = GLES20.glGetUniformLocation(program, "u_l_row2")
        lRow3Handle = GLES20.glGetUniformLocation(program, "u_l_row3")
        rRow1Handle = GLES20.glGetUniformLocation(program, "u_r_row1")
        rRow2Handle = GLES20.glGetUniformLocation(program, "u_r_row2")
        rRow3Handle = GLES20.glGetUniformLocation(program, "u_r_row3")
        inputTypeHandle = GLES20.glGetUniformLocation(program, "u_inputType")
        outputModeHandle = GLES20.glGetUniformLocation(program, "u_outputMode")
        swapEyesHandle = GLES20.glGetUniformLocation(program, "u_swapEyes")
        videoDimensionsHandle = GLES20.glGetUniformLocation(program, "u_videoDimensions")
        screenAspectRatioHandle = GLES20.glGetUniformLocation(program, "u_screenAspectRatio")
        singleFrameDimensionsHandle = GLES20.glGetUniformLocation(program, "u_singleFrameDimensions")

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

    fun setInputType(type: StereoInputType) { currentInputType = type }
    fun setOutputMode(mode: StereoOutputMode) { currentOutputMode = mode }
    fun setAnaglyphType(type: AnaglyphType) { currentAnaglyphType = type }
    fun setSwapEyes(swap: Boolean) { swapEyes = swap }
    fun setVideoDimensions(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }
    fun setSingleFrameDimensions(width: Float, height: Float) {
        this.singleFrameWidth = width
        this.singleFrameHeight = height
    }

    private fun updateShaderUniforms() {
        GLES20.glUniform1i(inputTypeHandle, currentInputType.ordinal)
        GLES20.glUniform1i(outputModeHandle, currentOutputMode.ordinal)
        GLES20.glUniform1i(swapEyesHandle, if (swapEyes) 1 else 0)
        GLES20.glUniform2f(videoDimensionsHandle, videoWidth.toFloat(), videoHeight.toFloat())
        GLES20.glUniform1f(screenAspectRatioHandle, screenAspectRatio)
        GLES20.glUniform2f(singleFrameDimensionsHandle, singleFrameWidth, singleFrameHeight)

        if (currentOutputMode == StereoOutputMode.ANAGLYPH) {
            val matrices = anaglyphModes[currentAnaglyphType]
            if (matrices != null) {
                GLES20.glUniform3f(lRow1Handle, matrices.l[0], matrices.l[1], matrices.l[2])
                GLES20.glUniform3f(lRow2Handle, matrices.l[3], matrices.l[4], matrices.l[5])
                GLES20.glUniform3f(lRow3Handle, matrices.l[6], matrices.l[7], matrices.l[8])
                GLES20.glUniform3f(rRow1Handle, matrices.r[0], matrices.r[1], matrices.r[2])
                GLES20.glUniform3f(rRow2Handle, matrices.r[3], matrices.r[4], matrices.r[5])
                GLES20.glUniform3f(rRow3Handle, matrices.r[6], matrices.r[7], matrices.r[8])
            }
        }
    }

    private fun calculateFps() {
        frameCount++
        val currentTime = System.nanoTime()
        val elapsedTime = currentTime - lastTime
        if (elapsedTime >= 1_000_000_000) {
            currentFps = frameCount
            frameCount = 0
            lastTime = currentTime
            fpsUpdatedListener.onFpsUpdated(currentFps)
        }
    }

    fun release() {
        // Освобождаем Java-объекты Surface, чтобы ExoPlayer перестал в них писать
        surfaceTexture?.release()
        surfaceTexture = null
        videoSurface?.release()
        videoSurface = null

        // Примечание: glDeleteProgram и glDeleteTextures должны вызываться в GL потоке.
        // Обычно GLSurfaceView сам уничтожает контекст при детаче, поэтому явное удаление
        // текстур здесь может быть избыточным или вызвать ошибку, если контекст уже мертв.
        // Главное - освободить SurfaceTexture.
    }

    enum class AnaglyphType { DUBOIS, RC_HALF_COLOR, RC_COLOR, RC_MONO, RC_OPTIMIZED, YB_HALF_COLOR, YB_COLOR, YB_MONO, RB_MONO }
    data class AnaglyphMatrices(val l: FloatArray, val r: FloatArray)

    companion object {
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

            varying vec2 v_texCoord;
            uniform samplerExternalOES u_texture;
            
            uniform int u_inputType;
            uniform int u_outputMode;
            uniform bool u_swapEyes;
            uniform vec2 u_videoDimensions;
            uniform vec2 u_singleFrameDimensions;
            uniform float u_screenAspectRatio;

            uniform vec3 u_l_row1; uniform vec3 u_l_row2; uniform vec3 u_l_row3;
            uniform vec3 u_r_row1; uniform vec3 u_r_row2; uniform vec3 u_r_row3;

            const int INPUT_NONE = 0;
            const int INPUT_SBS = 1;
            const int INPUT_TB = 2;
            const int INPUT_INTERLACED = 3;
            const int INPUT_TILED_1080P = 4;

            const int OUTPUT_ANAGLYPH = 0;
            const int OUTPUT_LEFT_ONLY = 1;
            const int OUTPUT_RIGHT_ONLY = 2;
            const int OUTPUT_CARDBOARD = 3;

            vec2 fitToScreen(vec2 tc, float videoAR, float screenAR) {
                float arScale = videoAR / screenAR;
                vec2 final_tc = tc;
                if (arScale > 1.0) {
                    final_tc.y = (tc.y - 0.5) / arScale + 0.5;
                } else {
                    final_tc.x = (tc.x - 0.5) * arScale + 0.5;
                }
                return final_tc;
            }

            void main() {
                vec2 left_tc;
                vec2 right_tc;

                if (u_inputType == INPUT_SBS) {
                    left_tc = vec2(v_texCoord.x * 0.5, v_texCoord.y);
                    right_tc = vec2(v_texCoord.x * 0.5 + 0.5, v_texCoord.y);
                } else if (u_inputType == INPUT_TB) {
                    left_tc = vec2(v_texCoord.x, v_texCoord.y * 0.5);
                    right_tc = vec2(v_texCoord.x, v_texCoord.y * 0.5 + 0.5);
                } else if (u_inputType == INPUT_INTERLACED) {
                    left_tc = v_texCoord;
                    right_tc = v_texCoord;
                } else if (u_inputType == INPUT_TILED_1080P) {
                    left_tc = v_texCoord * vec2(1280.0/1920.0, 720.0/1080.0);
                    right_tc = v_texCoord;
                } else { // INPUT_NONE
                    left_tc = v_texCoord;
                    right_tc = v_texCoord;
                }

                float singleFrameAR = u_singleFrameDimensions.x / u_singleFrameDimensions.y;

                left_tc = fitToScreen(left_tc, singleFrameAR, u_screenAspectRatio);
                right_tc = fitToScreen(right_tc, singleFrameAR, u_screenAspectRatio);

                if (left_tc.x < 0.0 || left_tc.x > 1.0 || left_tc.y < 0.0 || left_tc.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }

                if (u_swapEyes) {
                    vec2 temp = left_tc; left_tc = right_tc; right_tc = temp;
                }

                vec3 left_color;
                vec3 right_color;

                if (u_inputType == INPUT_INTERLACED) {
                    float is_odd_line = mod(gl_FragCoord.y, 2.0);
                    if (is_odd_line > 0.5) {
                        left_color = texture2D(u_texture, v_texCoord - vec2(0.0, 1.0/u_videoDimensions.y)).rgb;
                        right_color = texture2D(u_texture, v_texCoord).rgb;
                    } else {
                        left_color = texture2D(u_texture, v_texCoord).rgb;
                        right_color = texture2D(u_texture, v_texCoord + vec2(0.0, 1.0/u_videoDimensions.y)).rgb;
                    }
                } else if (u_inputType == INPUT_TILED_1080P) {
                    left_color = texture2D(u_texture, left_tc).rgb;
                    if (v_texCoord.x < 0.5) {
                        vec2 tc = vec2(1280.0/1920.0 + v_texCoord.x * (640.0/1920.0), v_texCoord.y * (720.0/1080.0));
                        right_color = texture2D(u_texture, tc).rgb;
                    } else {
                        if (v_texCoord.y < 0.5) {
                            vec2 tc = vec2((v_texCoord.x - 0.5) * (640.0/1920.0), 720.0/1080.0 + v_texCoord.y * (360.0/1080.0));
                            right_color = texture2D(u_texture, tc).rgb;
                        } else {
                            vec2 tc = vec2(640.0/1920.0 + (v_texCoord.x - 0.5) * (640.0/1920.0), 720.0/1080.0 + (v_texCoord.y - 0.5) * (360.0/1080.0));
                            right_color = texture2D(u_texture, tc).rgb;
                        }
                    }
                } else {
                    left_color = texture2D(u_texture, left_tc).rgb;
                    right_color = texture2D(u_texture, right_tc).rgb;
                }

                if (u_outputMode == OUTPUT_LEFT_ONLY || u_inputType == INPUT_NONE) {
                    gl_FragColor = vec4(left_color, 1.0);
                } else if (u_outputMode == OUTPUT_RIGHT_ONLY) {
                    gl_FragColor = vec4(right_color, 1.0);
                } else if (u_outputMode == OUTPUT_CARDBOARD) {
                    if (v_texCoord.x < 0.5) {
                        gl_FragColor = vec4(left_color, 1.0);
                    } else {
                        gl_FragColor = vec4(right_color, 1.0);
                    }
                } else { // OUTPUT_ANAGLYPH
                    float r = dot(u_l_row1, left_color) + dot(u_r_row1, right_color);
                    float g = dot(u_l_row2, left_color) + dot(u_r_row2, right_color);
                    float b = dot(u_l_row3, left_color) + dot(u_r_row3, right_color);
                    gl_FragColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);
                }
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