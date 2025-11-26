package top.rootu.dddplayer.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import top.rootu.dddplayer.R
import top.rootu.dddplayer.utils.CardboardParamsParser

class VrConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI не нужен, сразу обрабатываем Intent
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data

        if (data != null && data.host == "google.com" && data.path?.startsWith("/cardboard/cfg") == true) {
            val profile = CardboardParamsParser.parse(data)
            if (profile != null) {
                saveProfile(profile)
                showSuccessDialog(profile.k1, profile.k2)
            } else {
                showErrorDialog()
            }
        } else {
            finish()
        }
    }

    private fun saveProfile(profile: top.rootu.dddplayer.utils.CardboardProfile) {
        val prefs = getSharedPreferences("global_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("vr_k1", profile.k1)
            .putFloat("vr_k2", profile.k2)
            // Можно сохранить IPD, если нужно использовать его как базу для screenSeparation
            // .putFloat("vr_ipd", profile.interLensDistance)
            .apply()
    }

    private fun showSuccessDialog(k1: Float, k2: Float) {
        AlertDialog.Builder(this)
            .setTitle(R.string.vr_config_title)
            .setMessage(getString(R.string.vr_config_success, k1, k2))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                finish()
            }
            .setOnDismissListener {
                finish()
            }
            .show()
    }

    private fun showErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.vr_config_title)
            .setMessage(R.string.vr_config_error)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                finish()
            }
            .setOnDismissListener {
                finish()
            }
            .show()
    }
}