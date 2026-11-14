package com.bandkey.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UpdateCheckResult(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val notes: String,
    val apkName: String?,
    val apkUrl: String?,
    val apkSize: Long,
    val checksumsUrl: String?
) {
    val hasUpdate: Boolean
        get() = compareVersions(latestVersion, currentVersion) > 0
}

private fun compareVersions(left: String, right: String): Int {
    val leftParts = versionParts(left)
    val rightParts = versionParts(right)
    val count = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until count) {
        val diff = leftParts.getOrElse(index) { 0 } - rightParts.getOrElse(index) { 0 }
        if (diff != 0) {
            return diff
        }
    }
    return 0
}

private fun versionParts(value: String): List<Int> {
    return value.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
}

class UpdateCheckException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

object UpdateManager {
    const val REPOSITORY_URL = "https://github.com/QINGYUAI/BandKey"
    const val DOCS_URL = "https://github.com/QINGYUAI/BandKey/tree/main/docs"
    const val ISSUES_URL = "https://github.com/QINGYUAI/BandKey/issues"
    const val RELEASES_URL = "https://github.com/QINGYUAI/BandKey/releases/latest"

    private const val API_URL =
        "https://api.github.com/repos/QINGYUAI/BandKey/releases/latest"
    private const val PREFS = "bandkey"
    private const val KEY_LAST_CHECK = "last_update_check"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    @Volatile
    var latestCheck: UpdateCheckResult? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "BandKey-Android-Updater/${BuildConfig.VERSION_NAME}")
            .build()

        val json = try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        404 -> "无法访问 GitHub 仓库（404），请确认仓库已公开"
                        403 -> "GitHub API 访问频率受限，请稍后重试"
                        else -> "GitHub 返回 HTTP ${response.code}"
                    }
                    throw UpdateCheckException(reason)
                }
                JSONObject(body)
            }
        } catch (error: UpdateCheckException) {
            throw error
        } catch (error: Exception) {
            throw UpdateCheckException("网络连接失败：${error.message}", error)
        }

        val tag = json.optString("tag_name").trim()
        if (tag.isBlank()) {
            throw UpdateCheckException("Release 缺少 tag_name")
        }
        val latestVersion = tag.removePrefix("v").removePrefix("V")
        val releaseUrl = json.optString(
            "html_url",
            "$REPOSITORY_URL/releases/tag/$tag"
        )
        val notes = json.optString("body", "")
        val assets = json.optJSONArray("assets")
        var apkName: String? = null
        var apkUrl: String? = null
        var apkSize = 0L
        var checksumsUrl: String? = null

        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val download = asset.optString("browser_download_url")
                when {
                    name == "BandKey-$latestVersion-android.apk" -> {
                        apkName = name
                        apkUrl = download
                        apkSize = asset.optLong("size")
                    }

                    name == "SHA256SUMS.txt" -> checksumsUrl = download
                }
            }
        }

        val result = UpdateCheckResult(
            currentVersion = BuildConfig.VERSION_NAME,
            latestVersion = latestVersion,
            releaseUrl = releaseUrl,
            notes = notes,
            apkName = apkName,
            apkUrl = apkUrl,
            apkSize = apkSize,
            checksumsUrl = checksumsUrl
        )
        latestCheck = result
        result
    }

    suspend fun autoCheckIfDue(context: Context): UpdateCheckResult? {
        if (!isAutoCheckDue(context)) {
            return latestCheck
        }

        return try {
            checkForUpdate().also { markCheckCompleted(context) }
        } catch (_: Exception) {
            null
        }
    }

    fun isAutoCheckDue(context: Context): Boolean {
        val lastCheck = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS
    }

    fun markCheckCompleted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    suspend fun downloadAndVerify(
        context: Context,
        check: UpdateCheckResult,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val apkUrl = check.apkUrl
            ?: throw UpdateCheckException("最新版本未提供 Android 安装包")
        val checksumsUrl = check.checksumsUrl
            ?: throw UpdateCheckException("最新版本缺少 SHA256SUMS.txt，无法校验安装包")
        val apkName = File(check.apkName ?: "BandKey-${check.latestVersion}-android.apk").name

        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val directory = File(downloads, "updates").apply { mkdirs() }
        val target = File(directory, apkName)
        val temporary = File(directory, "$apkName.part")
        if (temporary.exists()) {
            temporary.delete()
        }

        try {
            val downloadRequest = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "BandKey-Android-Updater/${BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(downloadRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateCheckException("下载安装包失败：HTTP ${response.code}")
                }
                val body = response.body
                    ?: throw UpdateCheckException("下载响应为空")
                val total = body.contentLength().takeIf { it > 0 } ?: check.apkSize
                var received = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            received += read
                            if (total > 0) {
                                val percent = (received * 100L / total)
                                    .coerceIn(0L, 100L)
                                    .toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            }

            val expected = readExpectedChecksum(checksumsUrl, apkName)
            val actual = sha256(temporary)
            if (!expected.equals(actual, ignoreCase = true)) {
                throw UpdateCheckException(
                    "APK SHA-256 校验失败（期望 ${expected.take(12)}…，实际 ${actual.take(12)}…）"
                )
            }

            if (target.exists()) {
                target.delete()
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            onProgress(100)
            target
        } catch (error: Exception) {
            runCatching { temporary.delete() }
            throw error
        }
    }

    fun installApk(context: Context, apk: File): Boolean {
        if (!apk.exists()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(settingsIntent)
                false
            }.getOrDefault(false)
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openUrl(context: Context, url: String): Boolean {
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    private fun readExpectedChecksum(url: String, apkName: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "BandKey-Android-Updater/${BuildConfig.VERSION_NAME}")
            .build()
        val content = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateCheckException("下载校验文件失败：HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
        content.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 &&
                parts[1].removePrefix("*").equals(apkName, ignoreCase = true) &&
                parts[0].length == 64 &&
                parts[0].all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            ) {
                return parts[0]
            }
        }
        throw UpdateCheckException("SHA256SUMS.txt 中找不到 $apkName")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}
