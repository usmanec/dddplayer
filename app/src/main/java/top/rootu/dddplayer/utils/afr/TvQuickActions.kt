package top.rootu.dddplayer.utils.afr

import android.content.Context
import android.content.Intent
import android.util.Log

object TvQuickActions {
    private const val TAG = "TvQuickActions"
    private const val PACKAGE = "dev.vodik7.tvquickactions"

    fun sendStartAFR(context: Context?, frameRate: Float, height: Int) {
        if (context == null) return

        try {
            val intent = Intent()
            intent.setPackage(PACKAGE)
            intent.action = "$PACKAGE.START_AFR"
            intent.putExtra("fps", frameRate)
            intent.putExtra("height", height)
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent Start AFR broadcast: $frameRate fps, ${height}p")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Start AFR broadcast", e)
        }
    }

    fun sendStopAFR(context: Context?) {
        if (context == null) return

        try {
            val intent = Intent()
            intent.setPackage(PACKAGE)
            intent.action = "$PACKAGE.STOP_AFR"
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent Stop AFR broadcast")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Stop AFR broadcast", e)
        }
    }
}