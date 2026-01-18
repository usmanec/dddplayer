package top.rootu.dddplayer.logic

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class UpdateInfo(
    val version: String,
    val description: String,
    val downloadUrl: String,
    val size: Long
)

class UpdateManager(private val context: Context) {

    private val client = OkHttpClient()
    private val repoUrl = "https://api.github.com/repos/usmanec/dddplayer/releases"

    suspend fun checkForUpdates(currentVersionName: String?): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(repoUrl).build()
            val response = client.newCall(request).execute()
            val json = response.body?.string() ?: return@withContext null

            val releases = JSONArray(json)
            if (releases.length() > 0) {
                val latest = releases.getJSONObject(0)
                val tagName = latest.getString("tag_name") // e.g. "v1.0.1"

                // Сравниваем версии (простая проверка на неравенство, можно усложнить)
                // Убираем 'v' если есть
                val cleanTag = tagName.removePrefix("v")
                val cleanCurrent = currentVersionName?.removePrefix("v")

                if (cleanTag != cleanCurrent) {
                    val assets = latest.getJSONArray("assets")
                    if (assets.length() > 0) {
                        val apkAsset = assets.getJSONObject(0) // Берем первый ассет (обычно APK)
                        // Лучше поискать по имени .apk
                        var downloadUrl = apkAsset.getString("browser_download_url")
                        var size = apkAsset.getLong("size")

                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                size = asset.getLong("size")
                                break
                            }
                        }

                        return@withContext UpdateInfo(
                            version = tagName,
                            description = latest.optString("body", "").ifBlank { "Список изменений не предоставлен." },
                            downloadUrl = downloadUrl,
                            size = size
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun downloadApk(url: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body ?: return@withContext null

            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(file)
            val totalLength = body.contentLength()

            val data = ByteArray(4096)
            var count: Int
            var total: Long = 0

            while (inputStream.read(data).also { count = it } != -1) {
                total += count
                outputStream.write(data, 0, count)
                if (totalLength > 0) {
                    onProgress(((total * 100) / totalLength).toInt())
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            return@withContext file
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun deleteUpdateFile() {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (file.exists()) {
            file.delete()
        }
    }

    fun toJson(info: UpdateInfo): String {
        val json = JSONObject()
        json.put("version", info.version)
        json.put("description", info.description)
        json.put("downloadUrl", info.downloadUrl)
        json.put("size", info.size)
        return json.toString()
    }

    fun fromJson(jsonStr: String): UpdateInfo? {
        return try {
            val json = JSONObject(jsonStr)
            UpdateInfo(
                json.getString("version"),
                json.getString("description"),
                json.getString("downloadUrl"),
                json.getLong("size")
            )
        } catch (e: Exception) { null }
    }

    fun isNewer(remote: String, current: String): Boolean {
        return remote.removePrefix("v") != current.removePrefix("v")
    }
}