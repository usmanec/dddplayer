package top.rootu.dddplayer

import android.app.Application
import top.rootu.dddplayer.utils.CrashHandler

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        CrashHandler.init(this)
    }
}