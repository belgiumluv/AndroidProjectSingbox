package com.example.myapplication

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import io.nekohasekai.libbox.*

/**
 * Полноценная реализация io.nekohasekai.libbox.PlatformInterface для Android.
 * - Защищает исходящие сокеты ядра через VpnService.protect(fd)
 * - Отдаёт ядру текущий TUN fd (через tunFdSupplier)
 * - Следит за сменой default network и уведомляет ядро (InterfaceUpdateListener)
 * - (опционально) дёргает resetNetwork()/updateWIFIState() у BoxService
 */
class AndroidPlatformInterface(
    private val service: VpnService,
    private val cm: ConnectivityManager,
    /** Должен возвращать валидный fd TUN-интерфейса, созданный сервисом */
    private val tunFdSupplier: () -> Int
) : PlatformInterface {

    companion object {
        private const val TAG = "PlatformInterface"
    }

    /** Сюда передадим boxService из MyVpnService, чтобы уметь resetNetwork() */
    @Volatile
    private var boxService: BoxService? = null

    fun setBoxService(box: BoxService?) {
        boxService = box
    }

    private var callback: ConnectivityManager.NetworkCallback? = null

    // ---------- КРИТИЧЕСКИЕ ДЛЯ РАБОТЫ МЕТОДЫ ----------

    /**
     * ВАЖНО: возвращаем false, чтобы sing-box звал наш авто-контроль сокетов.
     * Тогда ядро перед каждой исходящей попыткой отдаёт сюда fd, а мы его protect().
     */
    override fun usePlatformAutoDetectInterfaceControl(): Boolean {
        Log.i(TAG, "usePlatformAutoDetectInterfaceControl() => false")
        return false
    }

    /** Собственно защита сокетов от зацикливания через VpnService.protect(fd) */
    override fun autoDetectInterfaceControl(fd: Int) {
        try {
            val ok = service.protect(fd)
            Log.i(TAG, "protect($fd) => $ok")
        } catch (e: Exception) {
            Log.e(TAG, "protect($fd) failed: ${e.message}", e)
        }
    }

    /** Отдаём sing-box уже созданный нами TUN-fd */
    override fun openTun(options: TunOptions): Int {
        return try {
            val fd = tunFdSupplier()
            Log.i(TAG, "openTun -> fd=$fd")
            fd
        } catch (e: Exception) {
            Log.e(TAG, "openTun: no fd: ${e.message}", e)
            -1
        }
    }

    /**
     * Следим за default network и уведомляем ядро.
     * Имя интерфейса можно не передавать (оставляем ""), чтобы избежать
     * ошибки "route ip+net : no such network interface" на некоторых девайсах.
     */
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        Log.i(TAG, "startDefaultInterfaceMonitor()")

        // Стартовое уведомление
        safeUpdate(listener, "", 0, false, false)

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "default network AVAILABLE: $network")
                rebindProcessNetwork(network)
                safeUpdate(listener, "", 0, isExpensive(network), isConstrained(network))
                tryResetNetwork()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "default network LOST: $network")
                rebindProcessNetwork(null)
                safeUpdate(listener, "", 0, false, false)
                tryResetNetwork()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                Log.i(TAG, "default network CAPS CHANGED")
                safeUpdate(listener, "", 0, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED))
            }
        }

        callback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}")
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        Log.i(TAG, "closeDefaultInterfaceMonitor()")
        try { callback?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        callback = null
    }

    // ---------- ВСПОМОГАТЕЛЬНОЕ ----------

    private fun rebindProcessNetwork(network: Network?) {
        try {
            @Suppress("DEPRECATION")
            cm.bindProcessToNetwork(network)
        } catch (_: Exception) {}
        try {
            service.setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
        } catch (_: Exception) {}
    }

    private fun tryResetNetwork() {
        try {
            boxService?.resetNetwork()
            if (boxService?.needWIFIState() == true) boxService?.updateWIFIState()
        } catch (e: Exception) {
            Log.w(TAG, "resetNetwork/updateWIFIState failed: ${e.message}")
        }
    }

    private fun isExpensive(n: Network): Boolean {
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isConstrained(n: Network): Boolean {
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
    }

    private fun safeUpdate(
        listener: InterfaceUpdateListener,
        iface: String,
        index: Int,
        expensive: Boolean,
        constrained: Boolean
    ) {
        try {
            listener.updateDefaultInterface(iface, index, expensive, constrained)
            Log.i(TAG, "updateDefaultInterface(iface=$iface, expensive=$expensive, constrained=$constrained)")
        } catch (e: Exception) {
            Log.w(TAG, "updateDefaultInterface failed: ${e.message}")
        }
    }

    // ---------- ПРОЧЕЕ (заглушки, если они тебе не нужны сейчас) ----------

    override fun clearDNSCache() {}
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?, sourcePort: Int,
        destinationAddress: String?, destinationPort: Int
    ): Int = 0

    override fun getInterfaces(): NetworkInterfaceIterator? = null
    override fun includeAllNetworks(): Boolean = false
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun packageNameByUid(uid: Int): String? = null
    override fun readWIFIState(): WIFIState? = null
    override fun sendNotification(notification: Notification) {
        Log.i(TAG, "sendNotification: ${notification.title}")
    }
    override fun systemCertificates(): StringIterator? = null
    override fun uidByPackageName(packageName: String?): Int = -1
    override fun underNetworkExtension(): Boolean = false
    override fun useProcFS(): Boolean = false
    override fun writeLog(message: String?) { Log.i(TAG, message ?: "") }
}
