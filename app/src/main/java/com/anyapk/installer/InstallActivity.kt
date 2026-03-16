package com.anyapk.installer

import android.content.Context
import android.widget.Toast
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.*

object AdbInstaller {

    private const val LOCALHOST = "127.0.0.1"
    private const val DEFAULT_PORT = 5555

    enum class ConnectionStatus {
        NOT_CONNECTED,
        CONNECTED,
        NEEDS_PAIRING,
        ERROR
    }

    @Volatile
    private var lastConnectionCheck: Long = 0

    @Volatile
    private var lastConnectionStatus: ConnectionStatus = ConnectionStatus.NEEDS_PAIRING

    private const val CONNECTION_CHECK_CACHE_MS = 2000


    private suspend fun debugToast(context: Context, msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "ADB DEBUG: $msg", Toast.LENGTH_SHORT).show()
        }
    }


    fun getConnectionStatus(context: Context, forceCheck: Boolean = false): ConnectionStatus {

        val now = System.currentTimeMillis()

        if (!forceCheck && (now - lastConnectionCheck) < CONNECTION_CHECK_CACHE_MS) {
            return lastConnectionStatus
        }

        var stream: AdbStream? = null

        val status = try {

            val manager = AdbConnectionManager.getInstance(context)

            if (!manager.autoConnect(context, 3000)) {

                lastConnectionStatus = ConnectionStatus.NEEDS_PAIRING
                lastConnectionCheck = now
                return ConnectionStatus.NEEDS_PAIRING
            }

            try {

                stream = manager.openStream("shell:echo test")

                val buffer = ByteArray(128)

                val bytesRead = stream.openInputStream().read(buffer)

                stream.close()

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

        } catch (e: Exception) {

            e.printStackTrace()
            ConnectionStatus.ERROR
        }

        lastConnectionCheck = now
        lastConnectionStatus = status

        return status
    }


    suspend fun pair(context: Context, pairingCode: String, pairingPort: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {

            try {

                debugToast(context, "Starting pairing")

                val manager = AdbConnectionManager.getInstance(context)

                debugToast(context, "Pairing with port $pairingPort")

                manager.pair(LOCALHOST, pairingPort, pairingCode)

                debugToast(context, "Pairing successful")

                Result.success(true)

            } catch (e: Exception) {

                e.printStackTrace()

                debugToast(context, "Pairing failed: ${e.message}")

                Result.failure(e)
            }
        }


    suspend fun testConnection(context: Context): Result<Boolean> =
        withContext(Dispatchers.IO) {

            var stream: AdbStream? = null

            try {

                debugToast(context, "Testing ADB connection")

                val manager = AdbConnectionManager.getInstance(context)

                if (!manager.autoConnect(context, 10000)) {

                    debugToast(context, "Auto connect failed")

                    return@withContext Result.failure(
                        Exception("Could not connect to ADB")
                    )
                }

                debugToast(context, "Connected, testing shell")

                stream = manager.openStream("shell:echo test")

                val output = StringBuilder()

                val inputStream = stream.openInputStream()

                val buffer = ByteArray(128)

                var totalWait = 0

                while (totalWait < 5000) {

                    if (inputStream.available() > 0) {

                        val bytesRead = inputStream.read(buffer)

                        if (bytesRead > 0) {

                            output.append(String(buffer, 0, bytesRead))

                            break
                        }
                    }

                    delay(100)

                    totalWait += 100
                }

                stream.close()

                manager.close()

                if (output.contains("test")) {

                    debugToast(context, "Connection verified")

                    Result.success(true)

                } else {

                    debugToast(context, "Authorization missing")

                    Result.failure(Exception("Authorization failed"))
                }

            } catch (e: Exception) {

                e.printStackTrace()

                try {
                    stream?.close()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }

                debugToast(context, "Connection error: ${e.message}")

                Result.failure(e)
            }
        }


    suspend fun install(context: Context, apkPath: String): Result<String> =
        withContext(Dispatchers.IO) {

            var stream: AdbStream? = null

            var manager: io.github.muntashirakon.adb.AbsAdbConnectionManager? = null

            try {

                debugToast(context, "Starting APK install")

                lastConnectionCheck = 0

                manager = object :
                    io.github.muntashirakon.adb.AbsAdbConnectionManager() {

                    private val delegate =
                        AdbConnectionManager.getInstance(context)

                    override fun getPrivateKey() = delegate.getPrivateKey()

                    override fun getCertificate() = delegate.getCertificate()

                    override fun getDeviceName() = delegate.getDeviceName()
                }

                manager.setApi(android.os.Build.VERSION.SDK_INT)

                debugToast(context, "Connecting to ADB")

                if (!manager.autoConnect(context, 10000)) {

                    debugToast(context, "ADB connect failed")

                    return@withContext Result.failure(
                        Exception("Failed to connect to ADB")
                    )
                }

                val apkFile = java.io.File(apkPath)

                val apkSize = apkFile.length()

                debugToast(context, "APK Size: $apkSize bytes")

                stream = manager.openStream("exec:cmd package install -S $apkSize")

                val outputStream = stream.openOutputStream()

                debugToast(context, "Streaming APK")

                java.io.FileInputStream(apkFile).use { input ->

                    val buffer = ByteArray(8192)

                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {

                        outputStream.write(buffer, 0, bytesRead)
                    }

                    outputStream.flush()
                }

                debugToast(context, "APK sent, waiting response")

                val output = StringBuilder()

                val inputStream = stream.openInputStream()

                val buffer = ByteArray(1024)

                var totalWait = 0

                val maxWait = 30000

                while (totalWait < maxWait) {

                    if (inputStream.available() > 0) {

                        val bytesRead = inputStream.read(buffer)

                        if (bytesRead > 0) {
                            output.append(String(buffer, 0, bytesRead))
                        }

                        if (bytesRead == -1) break
                    } else {

                        delay(100)

                        totalWait += 100

                        val currentOutput = output.toString()

                        if (currentOutput.contains("Success") ||
                            currentOutput.contains("Failure")
                        ) {
                            break
                        }
                    }
                }

                val result = output.toString().trim()

                stream.close()

                debugToast(context, "Install response: $result")

                if (result.contains("Success", true)) {

                    lastConnectionCheck = System.currentTimeMillis()
                    lastConnectionStatus = ConnectionStatus.CONNECTED

                    debugToast(context, "Install SUCCESS")

                    Result.success("Installation successful")

                } else {

                    debugToast(context, "Install FAILED")

                    Result.failure(Exception(result.ifEmpty { "Unknown error" }))
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

                debugToast(context, "Install error: ${e.message}")

                Result.failure(e)

            } finally {

                try {
                    manager?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
}
