package top.rootu.dddplayer.renderer

object ShaderSource {
    const val VERTEX_SHADER = """
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

    const val FRAGMENT_SHADER = """
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
                tex_tc_l = vec2(content_tc_l.x, content_tc_l.y * 0.5);
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