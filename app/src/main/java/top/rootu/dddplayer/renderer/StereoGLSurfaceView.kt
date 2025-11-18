package top.rootu.dddplayer.renderer

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * Простой наследник GLSurfaceView. Не требует дополнительной логики.
 * Мы используем его, чтобы в XML было понятно, что это за View.
 */
class StereoGLSurfaceView(context: Context, attrs: AttributeSet) : GLSurfaceView(context, attrs) {
    // Никакого кода здесь не нужно.
}