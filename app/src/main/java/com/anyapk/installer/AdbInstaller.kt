package com.anyapk.installer

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

object AdbInstaller {

    private const val LOCALHOST = "127.0.0.1"
    private const val DEFAULT_PORT = 5555
    
    // Constants for YagniLauncher
    private const val YAGNI_LAUNCHER_FILENAME = "YagniLauncher-v0.4.3-alpha.apk"

    enum class ConnectionStatus {
        NOT_CONNECTED,
        CONNECTED,
        NEEDS_PAIRING,
        ERROR
    }

    // Keep track of connection state without constantly reconnecting
    @Volatile
    private var lastConnectionCheck: Long = 0
    @Volatile
    private var lastConnectionStatus: ConnectionStatus = ConnectionStatus.NEEDS_PAIRING
    private const val CONNECTION_CHECK_CACHE_MS = 2000 // Cache for 2 seconds

    fun getConnectionStatus(context: Context, forceCheck: Boolean = false): ConnectionStatus {
        // Use cached status if recent (unless forced)
        val now = System.currentTimeMillis()
        if (!forceCheck && (now - lastConnectionCheck) < CONNECTION_CHECK_CACHE_MS) {
            return lastConnectionStatus
        }

        var stream: AdbStream? = null
        val status = try {
            val manager = AdbConnectionManager.getInstance(context)

            // Try to auto-connect using service discovery (works after pairing)
            if (!manager.autoConnect(context, 3000)) {
                ConnectionStatus.NEEDS_PAIRING
            } else {
                // Actually test the connection with a simple command
                try {
                    stream = manager.openStream("shell:echo test")
                    val buffer = ByteArray(128)
                    val bytesRead = stream.openInputStream().read(buffer)
                    stream.close()

                    // If we got a response, we're connected and authorized
                    if (bytesRead > 0) {
                        ConnectionStatus.CONNECTED
                    } else {
                        ConnectionStatus.NEEDS_PAIRING
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        stream?.close()
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                    ConnectionStatus.NEEDS_PAIRING
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ConnectionStatus.NEEDS_PAIRING
        }

        lastConnectionCheck = now
        lastConnectionStatus = status
        return status
    }

    suspend fun pair(context: Context, pairingCode: String, pairingPort: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            val manager = AdbConnectionManager.getInstance(context)
            manager.pair(LOCALHOST, pairingPort, pairingCode)
            Result.success(true)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun testConnection(context: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        return@withContext try {
            val manager = AdbConnectionManager.getInstance(context)

            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Could not connect to ADB. Make sure wireless debugging is enabled."))
            }

            stream = manager.openStream("shell:echo test")
            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(128)
            var bytesRead: Int

            var totalWait = 0
            while (totalWait < 5000) {
                if (inputStream.available() > 0) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                        break
                    }
                }
                kotlinx.coroutines.delay(100)
                totalWait += 100
            }

            stream.close()
            manager.close()

            if (output.contains("test")) {
                Result.success(true)
            } else {
                Result.failure(Exception("Connection test failed. Did you authorize the prompt?"))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            try {
                stream?.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            Result.failure(Exception("Authorization required. Check for 'Allow USB debugging?' prompt and tap 'Always allow'."))
        }
    }

    /**
     * Copy YagniLauncher APK from assets to internal storage
     * @return Path to the APK file in internal storage
     */
    private fun copyYagniLauncherToInternal(context: Context): String {
        val internalFile = File(context.filesDir, YAGNI_LAUNCHER_FILENAME)
        
        // If file already exists, check if it's valid
        if (internalFile.exists()) {
            if (internalFile.length() > 0) {
                return internalFile.absolutePath
            } else {
                // Delete corrupted file
                internalFile.delete()
            }
        }
        
        try {
            // Copy from assets to internal storage
            context.assets.open(YAGNI_LAUNCHER_FILENAME).use { inputStream ->
                FileOutputStream(internalFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    outputStream.flush()
                }
            }
            
            // Verify the copy was successful
            if (!internalFile.exists() || internalFile.length() == 0L) {
                throw Exception("Failed to copy APK file")
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Error copying YagniLauncher APK: ${e.message}")
        }
        
        return internalFile.absolutePath
    }

    /**
     * Install YagniLauncher from internal storage
     * This is the main function to call from your UI - NO PACKAGE NAME NEEDED!
     */
    suspend fun installYagniLauncher(context: Context): Result<String> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        var manager: io.github.muntashirakon.adb.AbsAdbConnectionManager? = null
        
        return@withContext try {
            // Step 1: Check if APK exists in assets
            try {
                context.assets.list("")?.find { it == YAGNI_LAUNCHER_FILENAME }
                    ?: throw Exception("YagniLauncher APK not found in assets folder")
            } catch (e: Exception) {
                return@withContext Result.failure(Exception(
                    "Please place YagniLauncher-v0.4.3-alpha.apk in app/src/main/assets/"
                ))
            }
            
            // Step 2: Copy APK to internal storage
            val internalApkPath = copyYagniLauncherToInternal(context)
            
            // Step 3: Invalidate cache before install
            lastConnectionCheck = 0

            // Step 4: Create connection manager
            manager = object : io.github.muntashirakon.adb.AbsAdbConnectionManager() {
                private val delegate = AdbConnectionManager.getInstance(context)
                override fun getPrivateKey() = delegate.getPrivateKey()
                override fun getCertificate() = delegate.getCertificate()
                override fun getDeviceName() = delegate.getDeviceName()
            }
            manager.setApi(android.os.Build.VERSION.SDK_INT)

            // Step 5: Connect to ADB
            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception(
                    "Failed to connect to ADB. Make sure wireless debugging is enabled and you've paired."
                ))
            }

            // Step 6: Prepare installation
            val apkFile = File(internalApkPath)
            val apkSize = apkFile.length()
            
            if (apkSize == 0L) {
                return@withContext Result.failure(Exception("APK file is empty or corrupted"))
            }

            // Step 7: Open install stream - NO PACKAGE NAME NEEDED HERE
            stream = manager.openStream("exec:cmd package install -S $apkSize")

            // Step 8: Stream the APK data
            val outputStream = stream.openOutputStream()
            java.io.FileInputStream(apkFile).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesWritten = 0L
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesWritten += bytesRead
                }
                outputStream.flush()
            }

            // Step 9: Read the response
            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            var totalWait = 0
            val maxWait = 30000

            while (totalWait < maxWait) {
                if (inputStream.available() > 0) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                    }
                    if (bytesRead == -1) break
                } else {
                    kotlinx.coroutines.delay(100)
                    totalWait += 100
                    
                    val currentOutput = output.toString()
                    if (currentOutput.contains("Success") || currentOutput.contains("Failure")) {
                        break
                    }
                }
            }

            val result = output.toString().trim()
            stream.close()

            // Step 10: Check result - we don't need package name, just look for "Success"
            if (result.contains("Success", ignoreCase = true)) {
                lastConnectionCheck = System.currentTimeMillis()
                lastConnectionStatus = ConnectionStatus.CONNECTED
                
                Result.success("YagniLauncher installed successfully!")
            } else {
                // Parse common error messages
                val errorMsg = when {
                    result.contains("INSTALL_FAILED_ALREADY_EXISTS", true) -> 
                        "App is already installed"
                    result.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", true) -> 
                        "Insufficient storage space"
                    result.contains("INSTALL_FAILED_INVALID_APK", true) -> 
                        "Invalid APK file"
                    result.contains("INSTALL_FAILED_DUPLICATE_PACKAGE", true) -> 
                        "App with same package name already exists"
                    else -> result.ifEmpty { "Unknown installation error" }
                }
                Result.failure(Exception(errorMsg))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            try {
                stream?.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            try {
                manager?.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            Result.failure(Exception("Installation failed: ${e.message}"))
        } finally {
            try {
                manager?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Simple install function that just takes any APK file path
     * Use this if you want more flexibility
     */
    suspend fun installApk(context: Context, apkPath: String): Result<String> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        var manager: io.github.muntashirakon.adb.AbsAdbConnectionManager? = null
        
        return@withContext try {
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                return@withContext Result.failure(Exception("APK file not found: $apkPath"))
            }
            
            val apkSize = apkFile.length()
            
            lastConnectionCheck = 0
            manager = object : io.github.muntashirakon.adb.AbsAdbConnectionManager() {
                private val delegate = AdbConnectionManager.getInstance(context)
                override fun getPrivateKey() = delegate.getPrivateKey()
                override fun getCertificate() = delegate.getCertificate()
                override fun getDeviceName() = delegate.getDeviceName()
            }
            manager.setApi(android.os.Build.VERSION.SDK_INT)

            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Failed to connect to ADB"))
            }

            stream = manager.openStream("exec:cmd package install -S $apkSize")

            val outputStream = stream.openOutputStream()
            java.io.FileInputStream(apkFile).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
            }

            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            var totalWait = 0
            val maxWait = 30000

            while (totalWait < maxWait) {
                if (inputStream.available() > 0) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                    }
                    if (bytesRead == -1) break
                } else {
                    delay(100)
                    totalWait += 100
                }
            }

            val result = output.toString().trim()
            stream.close()

            if (result.contains("Success", ignoreCase = true)) {
                Result.success("Installation successful")
            } else {
                Result.failure(Exception(result.ifEmpty { "Installation failed" }))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Get information about the YagniLauncher APK in assets
     */
    fun getYagniLauncherInfo(context: Context): Map<String, Any> {
        return try {
            val apkInAssets = context.assets.open(YAGNI_LAUNCHER_FILENAME)
            val size = apkInAssets.available()
            apkInAssets.close()
            
            val internalFile = File(context.filesDir, YAGNI_LAUNCHER_FILENAME)
            
            mapOf(
                "filename" to YAGNI_LAUNCHER_FILENAME,
                "inAssets" to true,
                "assetSize" to size,
                "inInternalStorage" to internalFile.exists(),
                "internalPath" to (if (internalFile.exists()) internalFile.absolutePath else "Not copied yet"),
                "internalSize" to (if (internalFile.exists()) internalFile.length() else 0)
            )
        } catch (e: Exception) {
            mapOf(
                "filename" to YAGNI_LAUNCHER_FILENAME,
                "inAssets" to false,
                "error" to e.message
            )
        }
    }
}
