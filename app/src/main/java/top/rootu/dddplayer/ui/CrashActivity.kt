package top.rootu.dddplayer.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.rootu.dddplayer.R
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.URL
import kotlin.system.exitProcess

class CrashActivity : AppCompatActivity() {

    private var errorDialog: Dialog? = null
    private var errorDetails: String = ""
    private var uploadedLogUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)

        errorDetails = intent.getStringExtra("errorDetails") ?: "No details available"

        findViewById<Button>(R.id.btRestartApp).setOnClickListener {
            restartApp()
        }

        findViewById<Button>(R.id.btShowErrorLogs).setOnClickListener {
            showErrorLogDialog()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitProcess(0)
            }
        })
    }

    private fun restartApp() {
        val packageManager = packageManager
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val componentName = intent?.component
        val mainIntent = Intent.makeRestartActivityTask(componentName)
        startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }

    private fun showErrorLogDialog() {
        errorDialog?.dismiss()

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_error_log)

        val textLog = dialog.findViewById<TextView>(R.id.tvErrorLogs)
        textLog.text = errorDetails

        val btnClose = dialog.findViewById<Button>(R.id.btnClose)
        val btnUpload = dialog.findViewById<Button>(R.id.btnUpload)
        val progress = dialog.findViewById<ProgressBar>(R.id.uploadProgress)
        val qrImage = dialog.findViewById<ImageView>(R.id.qrImage)
        val urlText = dialog.findViewById<TextView>(R.id.urlText)

        urlText.setOnClickListener {
            try {
                val clipData = ClipData.newPlainText("label", urlText.text)
                val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboardManager.setPrimaryClip(clipData)
                Toast.makeText(this@CrashActivity, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("Clipboard", "Failed to copy to clipboard", e)
            }
        }

        // Логика отображения состояния
        fun showUploadedState(url: String) {
            progress.visibility = View.GONE
            btnUpload.visibility = View.GONE

            urlText.text = url
            urlText.visibility = View.VISIBLE

            loadQrCode(url, qrImage)

            btnClose.requestFocus()
        }

        // Если уже загружали - показываем результат сразу
        if (uploadedLogUrl != null) {
            showUploadedState(uploadedLogUrl!!)
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        btnUpload.setOnClickListener {
            btnUpload.isEnabled = false
            btnUpload.text = "Загрузка..."
            progress.visibility = View.VISIBLE

            lifecycleScope.launch(Dispatchers.IO) {
                val url = uploadToTermbin(errorDetails)
                withContext(Dispatchers.Main) {
                    if (url != null) {
                        uploadedLogUrl = url // Сохраняем
                        showUploadedState(url)
                    } else {
                        progress.visibility = View.GONE
                        btnUpload.text = "Ошибка. Повторить?"
                        btnUpload.isEnabled = true
                        Toast.makeText(this@CrashActivity, "Ошибка соединения с termbin.com", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        errorDialog = dialog
        dialog.show()
        // Если кнопка загрузки скрыта (уже загрузили), фокус на Close, иначе на Upload
        if (btnUpload.isVisible) {
            btnUpload.requestFocus()
        } else {
            btnClose.requestFocus()
        }
    }

    private fun uploadToTermbin(text: String): String? {
        return try {
            val socket = Socket("termbin.com", 9999)
            socket.soTimeout = 10000 // Таймаут 10 сек

            val writer = OutputStreamWriter(socket.getOutputStream())
            writer.write(text)
            writer.flush()

            val reader = socket.getInputStream().bufferedReader()
            val response = reader.readLine()

            writer.close()
            reader.close()
            socket.close()

            response?.trim()?.replace("\u0000", "")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun loadQrCode(content: String, imageView: ImageView) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apiUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=$content"
                val stream = URL(apiUrl).openStream()
                val bitmap = BitmapFactory.decodeStream(stream)
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        errorDialog?.dismiss()
        errorDialog = null
    }
}