package com.example.myapp.singbox

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.PlatformInterface
import android.util.Log

object SingBoxManager {
    private const val TAG = "SingBoxManager"
    private var service: BoxService? = null

    init {
        Log.d(TAG, "AHAHA")
        System.loadLibrary("box")
    }

    fun start(context: Context) {
        try {
            val configContent = readAsset(context, "singbox/config.json")
            Libbox.checkConfig(configContent)

            val platform = object : PlatformInterface {
                override fun autoDetectInterfaceControl(fd: Int) {}
                override fun clearDNSCache() {}
                override fun closeDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.InterfaceUpdateListener) {}
                override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): Int = -1
                override fun getInterfaces(): io.nekohasekai.libbox.NetworkInterfaceIterator = throw NotImplementedError()
                override fun includeAllNetworks(): Boolean = true
                override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport = throw NotImplementedError()
                override fun openTun(options: io.nekohasekai.libbox.TunOptions): Int = throw NotImplementedError()
                override fun packageNameByUid(uid: Int): String = "unknown"
                override fun readWIFIState(): io.nekohasekai.libbox.WIFIState = throw NotImplementedError()
                override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {}
                override fun startDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.InterfaceUpdateListener) {}
                override fun systemCertificates(): io.nekohasekai.libbox.StringIterator = throw NotImplementedError()
                override fun uidByPackageName(packageName: String): Int = -1
                override fun underNetworkExtension(): Boolean = false
                override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
                override fun useProcFS(): Boolean = false
                override fun writeLog(message: String) {
                    println("Libbox log: $message")
                }
            }

            service = Libbox.newService(configContent, platform)
            service?.start()

            println("SingBox service started")

        } catch (e: Exception) {
            Log.i(TAG, "OOOO")
            e.printStackTrace()
        }
    }

    private fun readAsset(context: Context, path: String): String {
        context.assets.open(path).use { input ->
            return input.bufferedReader().readText()
        }
    }

    fun stop() {
        service?.close()
        service = null
    }
}
