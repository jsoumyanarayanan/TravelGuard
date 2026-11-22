package com.travelguard.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GithubUpdateManager {
    private const val RELEASE_URL = "https://api.github.com/repos/jsoumyanarayanan/TravelGuard/releases/latest"
    private const val APK_NAME = "TravelGuard.apk"

    data class UpdateInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    sealed class CheckResult {
        data class Available(val update: UpdateInfo) : CheckResult()
        data class UpToDate(val installedVersion: String) : CheckResult()
        data class Failed(val reason: String) : CheckResult()
    }

    data class DownloadProgress(
        val status: Int,
        val percent: Int,
        val reason: String? = null
    )

    sealed class InstallResult {
        data object Started : InstallResult()
        data object PermissionRequired : InstallResult()
        data class Failed(val reason: String) : InstallResult()
    }

    suspend fun checkForUpdate(installedVersion: String): CheckResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "TravelGuard-Android")
            }
            if (connection.responseCode !in 200..299) {
                return@withContext CheckResult.Failed("GitHub returned HTTP ${connection.responseCode}.")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v").trim()
            if (tag.isBlank()) return@withContext CheckResult.Failed("The latest release has no version tag.")

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    if (asset.optString("name") == APK_NAME) {
                        apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                        break
                    }
                }
            }
            if (apkUrl == null) {
                return@withContext CheckResult.Failed("The latest GitHub Release does not contain $APK_NAME.")
            }

            if (compareVersions(tag, installedVersion) > 0) {
                CheckResult.Available(
                    UpdateInfo(tag, apkUrl, json.optString("body").take(4000))
                )
            } else {
                CheckResult.UpToDate(installedVersion)
            }
        } catch (error: Exception) {
            CheckResult.Failed(error.localizedMessage ?: "Unable to check GitHub Releases.")
        } finally {
            connection?.disconnect()
        }
    }

    fun enqueueDownload(context: Context, update: UpdateInfo): Long {
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl)).apply {
            setTitle("TravelGuard update v${update.versionName}")
            setDescription("Downloading the latest TravelGuard update")
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
        }
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    fun getDownloadProgress(context: Context, downloadId: Long): DownloadProgress? {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
            return DownloadProgress(status, percent, if (status == DownloadManager.STATUS_FAILED) "Download failed (code $reason)." else null)
        }
    }

    fun installDownloaded(context: Context, downloadId: Long): InstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return InstallResult.PermissionRequired
        }

        val uri = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
            .getUriForDownloadedFile(downloadId)
            ?: return InstallResult.Failed("The downloaded APK is no longer available. Download it again.")

        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            InstallResult.Started
        } catch (error: Exception) {
            InstallResult.Failed(error.localizedMessage ?: "Android could not open the package installer.")
        }
    }

    private fun compareVersions(first: String, second: String): Int {
        val a = first.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val b = second.removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}
