package top.rootu.dddplayer.ui

import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import top.rootu.dddplayer.R

class PlayerActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.player_activity)

        if (savedInstanceState == null) {
            // Получаем URI из Intent, который запустил Activity
            val videoUri: Uri? = intent?.data

            // Создаем фрагмент, передавая URI в качестве аргумента
            val fragment = PlayerFragment.newInstance(videoUri)

            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commitNow()
        }
    }
}