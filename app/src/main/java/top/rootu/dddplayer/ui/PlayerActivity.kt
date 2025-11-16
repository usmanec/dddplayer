package top.rootu.dddplayer.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import top.rootu.dddplayer.R

class PlayerActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.player_activity)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, PlayerFragment.newInstance())
                .commitNow()
        }
    }
}
