package top.rootu.dddplayer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import top.rootu.dddplayer.R
import top.rootu.dddplayer.utils.AppLogCollector
import top.rootu.dddplayer.utils.DeviceInfoCollector

/**
 * Диагностика устройства: экран и HDR, аудиовыходы и проброс, видео- и аудиодекодеры, лог приложения.
 * Тот же экран, что у лога фатальной ошибки: выгрузка на termbin с QR-кодом и копирование.
 */
class DeviceInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_error_log)

        // Список декодеров и звуковые возможности читаются медленно - не в главном потоке
        val report = lifecycleScope.async(Dispatchers.Default) {
            val sections = DeviceInfoCollector.collect(this@DeviceInfoActivity) +
                // Лог работы приложения: что происходило перед проблемой, даже если ничего не упало
                DeviceInfoCollector.Section(getString(R.string.diag_sec_app_log), AppLogCollector.read())
            DeviceInfoCollector.toText(sections)
        }
        val view = LogReportView(
            activity = this,
            root = window.decorView,
            title = getString(R.string.pref_diagnostics),
            textProvider = { report.await() },
            onClose = { finish() }
        )
        view.setText("…")
        lifecycleScope.launch { view.setText(report.await()) }
        view.focusDefault()
    }
}
