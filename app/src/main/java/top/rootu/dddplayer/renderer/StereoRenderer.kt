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

    // Handles
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureHandle: Int = 0
    private var texMatrixHandle: Int = 0

    // Матрицы анаглифа
    private var lRow1Handle: Int = 0
    private var lRow2Handle: Int = 0
    private var lRow3Handle: Int = 0
    private var rRow1Handle: Int = 0
    private var rRow2Handle: Int = 0
    private var rRow3Handle: Int = 0

    // Параметры сцены
    private var inputTypeHandle: Int = 0
    private var outputModeHandle: Int = 0
    private var swapEyesHandle: Int = 0
    private var videoDimensionsHandle: Int = 0
    private var screenAspectRatioHandle: Int = 0
    private var singleFrameDimensionsHandle: Int = 0
    private var depthHandle: Int = 0

    // VR Handles
    private var k1Handle: Int = 0
    private var k2Handle: Int = 0
    private var distortScaleHandle: Int = 0
    private var screenSeparationHandle: Int = 0

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    @Volatile
    private var currentInputType: StereoInputType = StereoInputType.NONE

    @Volatile
    private var currentOutputMode: StereoOutputMode = StereoOutputMode.ANAGLYPH

    @Volatile
    private var currentAnaglyphType: AnaglyphType = AnaglyphType.RC_DUBOIS

    @Volatile
    private var swapEyes: Boolean = false

    @Volatile
    private var videoWidth: Int = 1920

    @Volatile
    private var videoHeight: Int = 1080
    private var screenAspectRatio: Float = 16f / 9f

    @Volatile
    private var singleFrameWidth: Float = 1920f

    @Volatile
    private var singleFrameHeight: Float = 1080f

    @Volatile
    private var rawDepth: Int = 0

    @Volatile
    private var screenSeparation: Float = 0f

    @Volatile
    private var currentDepth: Float = 0f

    // VR параметры
    @Volatile
    private var k1: Float = 0.34f

    @Volatile
    private var k2: Float = 0.10f

    @Volatile
    private var distortScale: Float = 1.2f

    // Текущие матрицы анаглифа (по умолчанию Identity/Red-Cyan placeholder)
    @Volatile
    private var matrixL: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Volatile
    private var matrixR: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

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
        -1.0f, 1.0f,
        1.0f, 1.0f
    )

    private val texCoords = floatArrayOf(
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    )

    init {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer.put(vertices).position(0)
        texCoordBuffer =
            ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer()
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
        glSurfaceView.requestRender()
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
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
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
        singleFrameDimensionsHandle =
            GLES20.glGetUniformLocation(program, "u_singleFrameDimensions")
        depthHandle = GLES20.glGetUniformLocation(program, "u_depth")

        k1Handle = GLES20.glGetUniformLocation(program, "u_k1")
        k2Handle = GLES20.glGetUniformLocation(program, "u_k2")
        distortScaleHandle = GLES20.glGetUniformLocation(program, "u_distortScale")
        screenSeparationHandle = GLES20.glGetUniformLocation(program, "u_screenSeparation")

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

    fun setInputType(type: StereoInputType) {
        currentInputType = type
        glSurfaceView.requestRender()
    }

    fun setOutputMode(mode: StereoOutputMode) {
        currentOutputMode = mode
        glSurfaceView.requestRender()
    }

    fun setAnaglyphType(type: AnaglyphType) {
        currentAnaglyphType = type
        glSurfaceView.requestRender()
    }

    fun setAnaglyphMatrices(l: FloatArray, r: FloatArray) {
        this.matrixL = l
        this.matrixR = r
        glSurfaceView.requestRender()
    }

    fun setSwapEyes(swap: Boolean) {
        swapEyes = swap
        glSurfaceView.requestRender()
    }

    fun setVideoDimensions(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        glSurfaceView.requestRender()
    }

    fun setSingleFrameDimensions(width: Float, height: Float) {
        this.singleFrameWidth = width
        this.singleFrameHeight = height
        updateDepthUniform()
        glSurfaceView.requestRender()
    }

    fun setDepth(depth: Int) {
        this.rawDepth = depth
        updateDepthUniform()
        glSurfaceView.requestRender()
    }

    fun setScreenSeparation(separation: Float) {
        this.screenSeparation = separation
        glSurfaceView.requestRender()
    }

    fun setDistortion(k1: Float, k2: Float, scale: Float) {
        this.k1 = k1
        this.k2 = k2
        this.distortScale = scale
        glSurfaceView.requestRender()
    }

    private fun updateDepthUniform() {
        if (singleFrameWidth > 0) {
            this.currentDepth = rawDepth.toFloat() / singleFrameWidth
        } else {
            this.currentDepth = 0f
        }
    }

    private fun updateShaderUniforms() {
        GLES20.glUniform1i(inputTypeHandle, currentInputType.ordinal)
        GLES20.glUniform1i(outputModeHandle, currentOutputMode.ordinal)
        GLES20.glUniform1i(swapEyesHandle, if (swapEyes) 1 else 0)
        GLES20.glUniform2f(videoDimensionsHandle, videoWidth.toFloat(), videoHeight.toFloat())
        GLES20.glUniform1f(screenAspectRatioHandle, screenAspectRatio)
        GLES20.glUniform2f(singleFrameDimensionsHandle, singleFrameWidth, singleFrameHeight)
        GLES20.glUniform1f(depthHandle, currentDepth)

        GLES20.glUniform1f(k1Handle, k1)
        GLES20.glUniform1f(k2Handle, k2)
        GLES20.glUniform1f(distortScaleHandle, distortScale)
        GLES20.glUniform1f(screenSeparationHandle, screenSeparation)

        if (currentOutputMode == StereoOutputMode.ANAGLYPH) {
            // Используем матрицы, переданные через setAnaglyphMatrices
            GLES20.glUniform3f(lRow1Handle, matrixL[0], matrixL[1], matrixL[2])
            GLES20.glUniform3f(lRow2Handle, matrixL[3], matrixL[4], matrixL[5])
            GLES20.glUniform3f(lRow3Handle, matrixL[6], matrixL[7], matrixL[8])
            GLES20.glUniform3f(rRow1Handle, matrixR[0], matrixR[1], matrixR[2])
            GLES20.glUniform3f(rRow2Handle, matrixR[3], matrixR[4], matrixR[5])
            GLES20.glUniform3f(rRow3Handle, matrixR[6], matrixR[7], matrixR[8])
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

    enum class AnaglyphType {
        // Red-Cyan
        RC_DUBOIS, RC_HALF_COLOR, RC_COLOR, RC_MONO, RC_OPTIMIZED, RC_CUSTOM,

        // Yellow-Blue
        YB_DUBOIS, YB_HALF_COLOR, YB_COLOR, YB_MONO, YB_CUSTOM,

        // Green-Magenta
        GM_DUBOIS, GM_HALF_COLOR, GM_COLOR, GM_MONO, GM_CUSTOM,
        RB_MONO
    }

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
            uniform float u_depth;

            uniform vec3 u_l_row1; uniform vec3 u_l_row2; uniform vec3 u_l_row3;
            uniform vec3 u_r_row1; uniform vec3 u_r_row2; uniform vec3 u_r_row3;
            
            uniform float u_k1;
            uniform float u_k2;
            uniform float u_distortScale;
            uniform float u_screenSeparation;

            const int INPUT_NONE = 0;
            const int INPUT_SBS = 1;
            const int INPUT_TB = 2;
            const int INPUT_INTERLACED = 3;
            const int INPUT_TILED_1080P = 4;

            const int OUTPUT_ANAGLYPH = 0;
            const int OUTPUT_LEFT_ONLY = 1;
            const int OUTPUT_RIGHT_ONLY = 2;
            const int OUTPUT_CARDBOARD = 3;

            vec2 distort(vec2 p) {
                float r2 = p.x * p.x + p.y * p.y;
                float f = 1.0 + u_k1 * r2 + u_k2 * r2 * r2;
                return p * f;
            }

            vec2 fitToScreen(vec2 tc, float videoAR, float screenAR) {
                float arScale = videoAR / screenAR;
                vec2 final_tc = tc;
                if (arScale > 1.0) {
                    final_tc.y = (tc.y - 0.5) * arScale + 0.5;
                } else {
                    final_tc.x = (tc.x - 0.5) / arScale + 0.5;
                }
                return final_tc;
            }

            void main() {
                vec2 viewport_tc = v_texCoord;
                float viewportAR = u_screenAspectRatio;
                bool isRightEye = false;

                if (u_outputMode == OUTPUT_CARDBOARD) {
                    viewportAR = u_screenAspectRatio / 2.0;
                    vec2 lensCenter;
                    
                    if (v_texCoord.x >= 0.5) {
                        isRightEye = true;
                        lensCenter = vec2(0.75 + u_screenSeparation, 0.5);
                    } else {
                        isRightEye = false;
                        lensCenter = vec2(0.25 - u_screenSeparation, 0.5);
                    }

                    vec2 p = (v_texCoord - lensCenter) * vec2(2.0, 1.0);
                    p.x *= viewportAR;
                    p /= u_distortScale;
                    p = distort(p);
                    p.x /= viewportAR;
                    vec2 distorted_tc_local = p + vec2(0.5, 0.5);

                    if (distorted_tc_local.x < 0.0 || distorted_tc_local.x > 1.0 || 
                        distorted_tc_local.y < 0.0 || distorted_tc_local.y > 1.0) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                        return;
                    }
                    viewport_tc = distorted_tc_local;

                } else if (u_outputMode == OUTPUT_RIGHT_ONLY) {
                     isRightEye = true;
                }

                float singleFrameAR = u_singleFrameDimensions.x / u_singleFrameDimensions.y;
                vec2 content_tc = fitToScreen(viewport_tc, singleFrameAR, viewportAR);

                if (content_tc.x < 0.0 || content_tc.x > 1.0 || content_tc.y < 0.0 || content_tc.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }

                vec2 content_tc_l = content_tc;
                vec2 content_tc_r = content_tc;
                
                content_tc_l.x += u_depth;
                content_tc_r.x -= u_depth;

                vec2 tex_tc_l;
                vec2 tex_tc_r;

                if (u_inputType == INPUT_TB) {
                    // С учетом переворота Y (1.0 - y) в Vertex Shader:
                    // Текстура приходит "нормально" ориентированной.
                    // Верхняя половина (0.5-1.0) - это Левый глаз.
                    // Нижняя половина (0.0-0.5) - это Правый глаз.
                    
                    // Левый глаз: берем из верхней половины
                    tex_tc_l = vec2(content_tc_l.x, content_tc_l.y * 0.5);
                    // Правый глаз: берем из нижней половины
                    tex_tc_r = vec2(content_tc_r.x, content_tc_r.y * 0.5 + 0.5);
                } else if (u_inputType == INPUT_SBS) {
                    tex_tc_l = vec2(content_tc_l.x * 0.5, content_tc_l.y);
                    tex_tc_r = vec2(content_tc_r.x * 0.5 + 0.5, content_tc_r.y);
                } else if (u_inputType == INPUT_INTERLACED) {
                    tex_tc_l = content_tc_l;
                    tex_tc_r = content_tc_r;
                } else if (u_inputType == INPUT_TILED_1080P) {
                    tex_tc_l = content_tc_l * vec2(1280.0/1920.0, 720.0/1080.0);
                    tex_tc_r = tex_tc_l; 
                } else { 
                    tex_tc_l = content_tc_l;
                    tex_tc_r = content_tc_r;
                }

                if (u_swapEyes) {
                    vec2 temp = tex_tc_l; tex_tc_l = tex_tc_r; tex_tc_r = temp;
                }

                vec3 color;

                if (u_outputMode == OUTPUT_CARDBOARD) {
                    if (isRightEye) {
                        color = texture2D(u_texture, tex_tc_r).rgb;
                    } else {
                        color = texture2D(u_texture, tex_tc_l).rgb;
                    }
                    gl_FragColor = vec4(color, 1.0);
                } else if (u_outputMode == OUTPUT_LEFT_ONLY) {
                    color = texture2D(u_texture, tex_tc_l).rgb;
                    gl_FragColor = vec4(color, 1.0);
                } else if (u_outputMode == OUTPUT_RIGHT_ONLY) {
                    color = texture2D(u_texture, tex_tc_r).rgb;
                    gl_FragColor = vec4(color, 1.0);
                } else { 
                    // Анаглиф
                    vec3 left_color;
                    vec3 right_color;
                    
                    if (u_inputType == INPUT_INTERLACED) {
                         float is_odd_line = mod(gl_FragCoord.y, 2.0);
                         if (is_odd_line > 0.5) {
                             left_color = texture2D(u_texture, tex_tc_l - vec2(0.0, 1.0/u_videoDimensions.y)).rgb;
                             right_color = texture2D(u_texture, tex_tc_r).rgb;
                         } else {
                             left_color = texture2D(u_texture, tex_tc_l).rgb;
                             right_color = texture2D(u_texture, tex_tc_r + vec2(0.0, 1.0/u_videoDimensions.y)).rgb;
                         }
                    } else {
                        left_color = texture2D(u_texture, tex_tc_l).rgb;
                        right_color = texture2D(u_texture, tex_tc_r).rgb;
                    }

                    float r = dot(u_l_row1, left_color) + dot(u_r_row1, right_color);
                    float g = dot(u_l_row2, left_color) + dot(u_r_row2, right_color);
                    float b = dot(u_l_row3, left_color) + dot(u_r_row3, right_color);
                    gl_FragColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);
                }
            }
        """
    }
}