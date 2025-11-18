package top.rootu.dddplayer.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import top.rootu.dddplayer.R

class PlayerActivity : FragmentActivity() {

    private var playerFragment: PlayerFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.player_activity)

        if (savedInstanceState == null) {
            val videoUri: Uri? = intent?.data
            val fragment = PlayerFragment.newInstance(videoUri)
            playerFragment = fragment // Сохраняем ссылку на фрагмент

            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commitNow()
        } else {
            // Восстанавливаем ссылку после пересоздания Activity
            playerFragment = supportFragmentManager.findFragmentById(R.id.container) as? PlayerFragment
        }
    }

    /**
     * Центральный обработчик всех нажатий кнопок.
     * Это самый надежный способ управлять UI плеера.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Передаем событие во фрагмент для обработки.
        // Если фрагмент его обработал (вернул true), то мы прекращаем дальнейшее распространение.
        if (playerFragment?.handleKeyEvent(event) == true) {
            return true
        }
        // В противном случае, используем стандартное поведение.
        return super.dispatchKeyEvent(event)
    }
}