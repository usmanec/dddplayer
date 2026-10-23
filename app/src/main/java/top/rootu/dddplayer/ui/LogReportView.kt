package top.rootu.dddplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.rootu.dddplayer.R
import top.rootu.dddplayer.logic.LogUploadCache
import top.rootu.dddplayer.utils.QrBitmap
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * Экран лога (layout dialog_error_log): текст, выгрузка на termbin.com со ссылкой и QR-кодом (QR рисуется локально, QrBitmap)
 * (удобно на ТВ: сфотографировать телефоном), копирование долгим нажатием.
 * Последняя выгрузка кешируется (LogUploadCache): тот же текст сразу показывает прошлую ссылку и QR.
 * Используется экраном фатальной ошибки (CrashActivity) и диагностикой устройства (DeviceInfoActivity).
 *
 * @param textProvider полный текст для выгрузки; suspend - диагностика может еще собираться
 */
class LogReportView(
    private val activity: AppCompatActivity,
    root: View,
    title: String,
    private val textProvider: suspend () -> String,
    onClose: () -> Unit
) {
    private val log: TextView = root.findViewById(R.id.tvErrorLogs)
    private val close: Button = root.findViewById(R.id.btnClose)
    private val upload: Button = root.findViewById(R.id.btnUpload)
    private val progress: ProgressBar = root.findViewById(R.id.uploadProgress)
    private val qrImage: ImageView = root.findViewById(R.id.qrImage)
    private val urlText: TextView = root.findViewById(R.id.urlText)
    private val urlBlock: View = root.findViewById(R.id.urlBlock)

    // Отдельный файл настроек: экран ошибки работает в процессе :crash, без синглтона SettingsRepository
    private val cachePrefs by lazy { activity.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE) }

    init {
        root.findViewById<TextView>(R.id.tvLogTitle).text = title
        close.setOnClickListener { onClose() }
        upload.setOnClickListener { startUpload() }
        urlBlock.setOnClickListener { copy(urlText.text.toString()) }
        // Копирование всего текста долгим нажатием (на пульте - долгий OK на области лога)
        val copyAll = View.OnLongClickListener {
            activity.lifecycleScope.launch { copy(textProvider()) }
            true
        }
        log.setOnLongClickListener(copyAll)
        (log.parent as? View)?.setOnLongClickListener(copyAll)
        // Этот же лог уже выгружали - показываем ссылку без повторной выгрузки
        activity.lifecycleScope.launch {
            val text = textProvider()
            val url = withContext(Dispatchers.Default) { LogUploadCache.cachedUrl(readCache(), LogUploadCache.hash(text), System.currentTimeMillis()) }
            if (url != null && upload.visibility == View.VISIBLE && upload.isEnabled) showUploaded(url)
        }
    }

    private fun readCache(): LogUploadCache.Entry? {
        val hash = cachePrefs.getString(KEY_HASH, null) ?: return null
        val url = cachePrefs.getString(KEY_URL, null) ?: return null
        return LogUploadCache.Entry(hash, url, cachePrefs.getLong(KEY_TIME, 0L))
    }

    private fun writeCache(hash: String, url: String) {
        cachePrefs.edit().putString(KEY_HASH, hash).putString(KEY_URL, url)
            .putLong(KEY_TIME, System.currentTimeMillis()).apply()
    }

    /** Текст на экране (диагностика дописывается, когда соберется) */
    fun setText(text: String) {
        log.text = text
    }

    /** Ссылка уже выгруженного лога: показываем результат без повторной выгрузки */
    fun showUploaded(url: String) {
        progress.visibility = View.GONE
        upload.visibility = View.GONE
        urlText.text = url
        urlBlock.visibility = View.VISIBLE
        loadQrCode(url)
        close.requestFocus()
    }

    fun focusDefault() {
        if (upload.visibility == View.VISIBLE) upload.requestFocus() else close.requestFocus()
    }

    private fun startUpload() {
        upload.isEnabled = false
        upload.setText(R.string.log_uploading)
        progress.visibility = View.VISIBLE
        activity.lifecycleScope.launch {
            val text = textProvider()
            val url = withContext(Dispatchers.IO) { uploadToTermbin(text) }
            if (url != null) {
                writeCache(LogUploadCache.hash(text), url)
                showUploaded(url)
            } else {
                progress.visibility = View.GONE
                upload.setText(R.string.log_upload_retry)
                upload.isEnabled = true
                Toast.makeText(activity, R.string.log_upload_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copy(text: String) {
        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("log", text))
            Toast.makeText(activity, R.string.log_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }

    private fun uploadToTermbin(text: String): String? = try {
        Socket("termbin.com", 9999).use { socket ->
            socket.soTimeout = 10000
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write(text)
            writer.flush()
            socket.getInputStream().bufferedReader().readLine()?.trim()?.replace("\u0000", "")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Upload to termbin failed", e)
        null
    }

    private fun loadQrCode(content: String) {
        activity.lifecycleScope.launch {
            // QR рисуем сами: внешний сервис не нужен, и ссылка на лог никуда не уходит
            val bitmap = withContext(Dispatchers.Default) {
                try {
                    QrBitmap.create(content)
                } catch (e: Exception) {
                    Log.w(TAG, "QR code failed", e)
                    null
                }
            } ?: return@launch
            qrImage.setImageBitmap(bitmap)
            qrImage.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val TAG = "LogReportView"
        private const val CACHE_PREFS = "log_upload_cache"
        private const val KEY_HASH = "hash"
        private const val KEY_URL = "url"
        private const val KEY_TIME = "uploaded_at"
    }
}
