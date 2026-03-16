package com.anyapk.installer

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object UpdateManager {
    
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val isCritical: Boolean = false
    )
    
    data class UpdateCheckResult(
        val hasUpdate: Boolean,
        val updateInfo: UpdateInfo? = null,
        val error: String? = null
    )
    
    private const val UPDATE_CHECK_URL = "https://api.example.com/anyapk/updates.json" // Replace with actual URL
    private const val CONNECTION_TIMEOUT = 10000
    private const val READ_TIMEOUT = 30000

    /**
     * Check for app updates
     */
    suspend fun checkForUpdates(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            // Get current version
            val currentVersionCode = getCurrentVersionCode(context)
            
            // Fetch update info
            val updateInfo = fetchUpdateInfo() ?: return@withContext UpdateCheckResult(false)
            
            // Compare versions
            val hasUpdate = updateInfo.versionCode > currentVersionCode
            
            UpdateCheckResult(
                hasUpdate = hasUpdate,
                updateInfo = if (hasUpdate) updateInfo else null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            UpdateCheckResult(
                hasUpdate = false,
                error = "Failed to check for updates: ${e.message}"
            )
        }
    }

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            0
        }
    }

    private fun fetchUpdateInfo(): UpdateInfo? {
        var connection: HttpURLConnection? = null
        
        return try {
            val url = URL(UPDATE_CHECK_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECTION_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseUpdateInfo(response)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseUpdateInfo(jsonString: String): UpdateInfo? {
        return try {
            val json = JSONObject(jsonString)
            UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                downloadUrl = json.getString("downloadUrl"),
                releaseNotes = json.getString("releaseNotes"),
                isCritical = json.optBoolean("isCritical", false)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Download and install update
     */
    suspend fun downloadAndInstallUpdate(
        context: Context,
        updateInfo: UpdateInfo,
        progressListener: ((Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        
        try {
            // Create temp file
            val tempFile = File(context.cacheDir, "anyapk_update_${System.currentTimeMillis()}.apk")
            
            // Download APK
            val url = URL(updateInfo.downloadUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECTION_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            
            val fileLength = connection.contentLength
            
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Report progress
                        if (fileLength > 0) {
                            val progress = (totalBytesRead * 100 / fileLength)
                            progressListener?.invoke(progress)
                        }
                    }
                    output.flush()
                }
            }
            
            connection.disconnect()
            
            // Verify download
            if (!tempFile.exists() || tempFile.length() == 0L) {
                return@withContext Result.failure(Exception("Download failed: File is empty"))
            }
            
            // Install using ADB
            val installResult = AdbInstaller.install(context, tempFile.absolutePath)
            
            // Clean up temp file on success
            installResult.onSuccess {
                tempFile.delete()
            }
            
            installResult
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Get release notes for current version
     */
    suspend fun getReleaseNotes(context: Context): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val updateInfo = fetchUpdateInfo()
            updateInfo?.releaseNotes
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Check if critical update is available
     */
    suspend fun isCriticalUpdateAvailable(context: Context): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val updateInfo = fetchUpdateInfo()
            updateInfo?.isCritical == true && 
            updateInfo.versionCode > getCurrentVersionCode(context)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
