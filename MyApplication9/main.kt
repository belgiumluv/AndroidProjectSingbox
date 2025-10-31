package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.json.JSONObject
import java.io.File

class MyVpnService : VpnService() {

    companion object {
        private const val TAG = "MyVpnService"
        private const val NOTIF_CHANNEL_ID = "singbox_vpn_min"
        private const val NOTIF_ID = 1
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var boxService: BoxService? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating VPN service")

        // ---- Foreground ----
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("VPN работает"))

        // ---- Пути/логи для libbox ----
        val workDir = File(filesDir, "singbox").apply { mkdirs() }
        val tmpDir = File(cacheDir, "singbox_tmp").apply { mkdirs() }
        val stderrPath = File(workDir, "libbox_stderr.log").absolutePath

        try {
            val opts = SetupOptions().apply {
                setBasePath(filesDir.absolutePath)
                setWorkingPath(workDir.absolutePath)
                setTempPath(tmpDir.absolutePath)
                setUsername("")              // важно: не провоцируем chown
                setFixAndroidStack(true)
            }
            Libbox.setup(opts)
            Libbox.redirectStderr(stderrPath)
            Libbox.setMemoryLimit(true)
            Log.i(TAG, "Libbox version: ${Libbox.version()}")
        } catch (e: Exception) {
            Log.w(TAG, "Libbox.setup/log redirect failed: ${e.message}")
        }

        // ---- VPN-интерфейс ----
        val builder = Builder()
            .setSession("Sing-Box VPN")
            .setMtu(1400)
            .addAddress("172.19.0.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addRoute("::", 0)
            .addDnsServer("2606:4700:4700::1111")

        // Не пускаем саму апку внутрь VPN
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf(); return
        }
        Log.i(TAG, "VPN established, fd=${vpnInterface!!.fd}")

        // ---- PlatformInterface для libbox ----
        val platformInterface = AndroidPlatformInterface(
            vpnService = this,
            cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
            onDefaultNetworkChanged = { hasDefault ->
                // Любое изменение «дефолтной» сети — просим libbox перечитать интерфейсы
                try {
                    boxService?.resetNetwork()
                    if (boxService?.needWIFIState() == true) boxService?.updateWIFIState()
                    Log.i(TAG, "resetNetwork() on default network change (hasDefault=$hasDefault)")
                } catch (e: Exception) {
                    Log.w(TAG, "resetNetwork/updateWIFIState failed: ${e.message}")
                }
            },
            tunFdProvider = { vpnInterface!!.fd }
        )

        // ---- Чтение конфига ----
        val rawConfig = try {
            assets.open("config.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config.json: ${e.message}")
            updateNotification("Ошибка конфигурации (нет файла)")
            stopSelf(); return
        }
        if (rawConfig.isBlank()) {
            Log.e(TAG, "Config is blank or empty!")
            updateNotification("Пустой config.json")
            stopSelf(); return
        }

        // ---- Патч конфига: cache в приватный путь, финал/детуры/strict ----
        val configPrepared = try {
            val root = JSONObject(rawConfig)

            // experimental.cache_file -> включаем с абсолютным путём
            val experimental = root.optJSONObject("experimental") ?: JSONObject().also {
                root.put("experimental", it)
            }
            val cache = experimental.optJSONObject("cache_file") ?: JSONObject().also {
                experimental.put("cache_file", it)
            }
            cache.put("enabled", true)
            cache.put("path", File(workDir, "cache.db").absolutePath)
            cache.remove("cache_id")

            // DNS servers -> detour к Select (кроме rcode)
            root.optJSONObject("dns")?.optJSONArray("servers")?.let { servers ->
                for (i in 0 until servers.length()) {
                    val s = servers.optJSONObject(i) ?: continue
                    val addr = s.optString("address", "")
                    if (!addr.startsWith("rcode://")) s.put("detour", "Select")
                }
            }
            // DNS rules: убираем явный outbound=direct
            root.optJSONObject("dns")?.optJSONArray("rules")?.let { rules ->
                for (i in 0 until rules.length()) {
                    val r = rules.optJSONObject(i) ?: continue
                    if (r.optString("outbound") == "direct") r.remove("outbound")
                }
            }

            // route.final -> Select; чистим outbound=direct в правилах
            root.optJSONObject("route")?.put("final", "Select")
            root.optJSONObject("route")?.optJSONArray("rules")?.let { rules ->
                for (i in 0 until rules.length()) {
                    val r = rules.optJSONObject(i) ?: continue
                    if (r.optString("outbound") == "direct") r.remove("outbound")
                }
            }

            root.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Config patch failed: ${e.message} — using raw")
            rawConfig
        }

        Log.i(TAG, "Config prepared (cache ON), len=${configPrepared.length}")
        Log.d(TAG, "Config head: " + configPrepared.take(240))
        Log.d(TAG, "route.auto_detect_interface=true")

        // ---- Запуск sing-box ----
        fun startBox(config: String) {
            Log.i(TAG, "Checking sing-box config…")
            Libbox.checkConfig(config)
            Log.i(TAG, "Starting sing-box service…")
            boxService = Libbox.newService(config, platformInterface)
            boxService?.start()
            Log.i(TAG, "Sing-box started successfully")
            updateNotification("Подключено")
        }

        try {
            startBox(configPrepared)
        } catch (e: Exception) {
            val svcErr = try { Libbox.readServiceError()?.toString() ?: "" } catch (_: Exception) { "" }
            val msgAll = (e.message ?: "") + " " + svcErr
            Log.e(TAG, "Start with cache FAILED: $msgAll", e)

            val looksLikeCacheFail =
                msgAll.contains("cache-file", true) ||
                        msgAll.contains("chown", true) ||
                        msgAll.contains("read-only", true) ||
                        msgAll.contains("cache.db", true)

            if (looksLikeCacheFail) {
                // повтор без cache_file
                var noCache = configPrepared
                try {
                    val root = JSONObject(configPrepared)
                    root.optJSONObject("experimental")?.let { exp ->
                        exp.remove("cache_file")
                        exp.remove("cache_id")
                        if (exp.length() == 0) root.remove("experimental")
                    }
                    noCache = root.toString()
                } catch (_: Exception) {}
                Log.w(TAG, "Retry starting WITHOUT cache…")
                try {
                    startBox(noCache)
                } catch (e2: Exception) {
                    val svcErr2 = try { Libbox.readServiceError()?.toString() ?: "" } catch (_: Exception) { "" }
                    Log.e(TAG, "Failed to start even without cache: ${e2.message}${if (svcErr2.isNotEmpty()) " | $svcErr2" else ""}", e2)
                    try { Libbox.clearServiceError() } catch (_: Exception) {}
                    updateNotification("Ошибка запуска")
                    stopSelf(); return
                }
            } else {
                try { Libbox.clearServiceError() } catch (_: Exception) {}
                updateNotification("Ошибка запуска")
                stopSelf(); return
            }
        }

        // ---- Подстраховка: системный callback (для старых Android или если libbox не дернул монитор) ----
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 24) {
            val hasPerm = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_NETWORK_STATE
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "Network available -> resetNetwork() [fallback]")
                        try {
                            boxService?.resetNetwork()
                            if (boxService?.needWIFIState() == true) boxService?.updateWIFIState()
                        } catch (e: Exception) {
                            Log.w(TAG, "resetNetwork/updateWIFIState failed: ${e.message}")
                        }
                    }
                    override fun onLost(network: Network) {
                        Log.i(TAG, "Network lost -> resetNetwork() [fallback]")
                        try { boxService?.resetNetwork() } catch (e: Exception) {
                            Log.w(TAG, "resetNetwork failed: ${e.message}")
                        }
                    }
                }
                try { connectivityManager?.registerDefaultNetworkCallback(networkCallback!!) }
                catch (e: Exception) { Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}") }
            } else {
                Log.w(TAG, "ACCESS_NETWORK_STATE not granted; skipping network callback")
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping VPN service")
        if (Build.VERSION.SDK_INT >= 24) {
            try { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
            catch (e: Exception) { Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}") }
        }
        try { boxService?.pause() } catch (_: Exception) {}
        try { boxService?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---- уведомления ----
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Sing-Box VPN (тихое)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                description = "Служебное уведомление VPN"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val iconId = resources.getIdentifier("ic_vpn_transparent", "drawable", packageName)
            .takeIf { it != 0 } ?: R.mipmap.ic_launcher
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(iconId)
            .setContentTitle("VPN")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}














нормальная версия

package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.TunOptions
import org.json.JSONObject
import java.io.File

class MyVpnService : VpnService() {

    companion object {
        private const val TAG = "MyVpnService"
        private const val NOTIF_CHANNEL_ID = "singbox_vpn_min"
        private const val NOTIF_ID = 1
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var boxService: BoxService? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating VPN service")

        // ---- Foreground (тихое уведомление) ----
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("VPN работает"))

        // ---- Пути/логи для libbox ----
        val workDir = File(filesDir, "singbox").apply { mkdirs() }
        val tmpDir = File(cacheDir, "singbox_tmp").apply { mkdirs() }
        val stderrPath = File(workDir, "libbox_stderr.log").absolutePath

        try {
            val opts = SetupOptions().apply {
                setBasePath(filesDir.absolutePath)
                setWorkingPath(workDir.absolutePath)
                setTempPath(tmpDir.absolutePath)
                setUsername("")              // важно: не провоцируем chown на "user"
                setFixAndroidStack(true)
            }
            Libbox.setup(opts)
            Libbox.redirectStderr(stderrPath)
            Libbox.setMemoryLimit(true)
            Log.i(TAG, "Libbox version: ${Libbox.version()}")
        } catch (e: Exception) {
            Log.w(TAG, "Libbox.setup/log redirect failed: ${e.message}")
        }

        // ---- VPN-интерфейс ----
        val builder = Builder()
            .setSession("Sing-Box VPN")
            .setMtu(1400)
            .addAddress("172.19.0.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addRoute("::", 0)
            .addDnsServer("2606:4700:4700::1111")

        //try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf(); return
        }
        Log.i(TAG, "VPN established, fd=${vpnInterface!!.fd}")

        // ---- PlatformInterface для libbox ----
        val platformInterface = object : DummyPlatformInterface() {
            // ВАЖНО: просим ядро звать наш колбэк, и в нём делаем VpnService.protect(fd)
            override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
            override fun autoDetectInterfaceControl(fd: Int) {
                val ok = this@MyVpnService.protect(fd)
                Log.i(TAG, "protect($fd) -> $ok")
            }

            override fun useProcFS(): Boolean = false
            override fun includeAllNetworks(): Boolean = false // иначе sing-tun ругается на system/mixed stack
            override fun openTun(options: TunOptions): Int {
                val fd = vpnInterface!!.fd
                Log.i(TAG, "openTun -> fd=$fd")
                return fd
            }
        }

        // ---- Чтение конфига ----
        val rawConfig = try {
            assets.open("config.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config.json: ${e.message}")
            updateNotification("Ошибка конфигурации (нет файла)")
            stopSelf(); return
        }
        if (rawConfig.isBlank()) {
            Log.e(TAG, "Config is blank or empty!")
            updateNotification("Пустой config.json")
            stopSelf(); return
        }

        // ---- Патчим конфиг: cache.path в приватной папке + force auto_detect_interface + убираем direct ----
        val configPatched = try {
            val root = JSONObject(rawConfig)

            // experimental.cache_file -> ON с абсолютным путём
            val experimental = root.optJSONObject("experimental") ?: JSONObject().also {
                root.put("experimental", it)
            }
            val cache = experimental.optJSONObject("cache_file") ?: JSONObject().also {
                experimental.put("cache_file", it)
            }
            cache.put("enabled", true)
            cache.put("path", File(workDir, "cache.db").absolutePath)
            cache.remove("cache_id")

            // гарантируем авто-детект и обход android vpn
            val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
            route.put("auto_detect_interface", true)
            route.put("override_android_vpn", true)
            // final оставляем как в файле; если там direct — заменим на Select (унификация)
            if (route.optString("final") == "direct") route.put("final", "Select")

            // DNS detour → Select, никаких direct
            root.optJSONObject("dns")?.optJSONArray("servers")?.let { servers ->
                for (i in 0 until servers.length()) {
                    val s = servers.optJSONObject(i) ?: continue
                    val addr = s.optString("address", "")
                    if (!addr.startsWith("rcode://")) s.put("detour", "Select")
                }
            }
            root.optJSONObject("dns")?.optJSONArray("rules")?.let { rules ->
                for (i in 0 until rules.length()) {
                    val r = rules.optJSONObject(i) ?: continue
                    if (r.optString("outbound") == "direct") r.remove("outbound")
                }
            }

            root.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Config patch failed: ${e.message} — using raw")
            rawConfig
        }

        Log.i(TAG, "Config prepared (cache ON, no-direct), len=${configPatched.length}")
        Log.d(TAG, "Config head: " + configPatched.take(240))
        runCatching {
            val dbg = JSONObject(configPatched).optJSONObject("route")?.optBoolean("auto_detect_interface")
            Log.d(TAG, "route.auto_detect_interface=$dbg")
        }

        // ---- Функция запуска ----
        fun startBox(config: String) {
            Log.i(TAG, "Checking sing-box config…")
            Libbox.checkConfig(config)
            Log.i(TAG, "Starting sing-box service…")
            boxService = Libbox.newService(config, platformInterface)
            boxService?.start()
            Log.i(TAG, "Sing-box started successfully")
            try {
                boxService?.resetNetwork()
                if (boxService?.needWIFIState() == true) {
                    boxService?.updateWIFIState()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Initial resetNetwork/updateWIFIState failed: ${e.message}")
            }
            updateNotification("Подключено")
        }



        // ---- Стартуем с кэшем; при cache/chown проблеме — авто-фолбэк без кэша ----
        try {
            startBox(configPatched)
        } catch (e: Exception) {
            val svcErr = try { Libbox.readServiceError()?.toString() ?: "" } catch (_: Exception) { "" }
            val msgAll = (e.message ?: "") + " " + svcErr
            Log.e(TAG, "Start with cache FAILED: $msgAll", e)

            val looksLikeCacheFail =
                msgAll.contains("cache-file", true) ||
                        msgAll.contains("chown", true) ||
                        msgAll.contains("read-only", true) ||
                        msgAll.contains("cache.db", true)

            if (looksLikeCacheFail) {
                var noCache = configPatched
                try {
                    val root = JSONObject(configPatched)
                    root.optJSONObject("experimental")?.let { exp ->
                        exp.remove("cache_file")
                        exp.remove("cache_id")
                        if (exp.length() == 0) root.remove("experimental")
                    }
                    noCache = root.toString()
                } catch (_: Exception) { /* ignore */ }
                noCache = noCache
                    .replace(Regex("""(?s),\s*"cache_file"\s*:\s*\{.*?\}"""), "")
                    .replace(Regex("""(?s)"cache_file"\s*:\s*\{.*?\}\s*,?"""), "")
                    .replace(Regex("""\s*"cache_id"\s*:\s*"(?:\\.|[^"]*)"\s*,?"""), "")
                    .replace("cache.db", "cache-disabled.db")

                Log.w(TAG, "Retry starting WITHOUT cache…")
                try {
                    startBox(noCache)
                } catch (e2: Exception) {
                    val svcErr2 = try { Libbox.readServiceError()?.toString() ?: "" } catch (_: Exception) { "" }
                    Log.e(TAG, "Failed to start even without cache: ${e2.message}${if (svcErr2.isNotEmpty()) " | $svcErr2" else ""}", e2)
                    try { Libbox.clearServiceError() } catch (_: Exception) {}
                    updateNotification("Ошибка запуска")
                    stopSelf(); return
                }
            } else {
                try { Libbox.clearServiceError() } catch (_: Exception) {}
                updateNotification("Ошибка запуска")
                stopSelf(); return
            }
        }

        // ---- Слежение за сетью ----
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 24) {
            val hasPerm = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_NETWORK_STATE
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "Network available -> resetNetwork()")
                        try {
                            boxService?.resetNetwork()
                            if (boxService?.needWIFIState() == true) boxService?.updateWIFIState()
                        } catch (e: Exception) {
                            Log.w(TAG, "resetNetwork/updateWIFIState failed: ${e.message}")
                        }
                    }
                    override fun onLost(network: Network) {
                        Log.i(TAG, "Network lost -> resetNetwork()")
                        try { boxService?.resetNetwork() } catch (e: Exception) {
                            Log.w(TAG, "resetNetwork failed: ${e.message}")
                        }
                    }
                }
                try { connectivityManager?.registerDefaultNetworkCallback(networkCallback!!) }
                catch (e: Exception) { Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}") }
            } else {
                Log.w(TAG, "ACCESS_NETWORK_STATE not granted; skipping network callback")
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping VPN service")
        if (Build.VERSION.SDK_INT >= 24) {
            try { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
            catch (e: Exception) { Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}") }
        }
        try { boxService?.pause() } catch (_: Exception) {}
        try { boxService?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---- уведомления ----
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Sing-Box VPN (тихое)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                description = "Служебное уведомление VPN"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val iconId = resources.getIdentifier("ic_vpn_transparent", "drawable", packageName)
            .takeIf { it != 0 } ?: R.mipmap.ic_launcher
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(iconId)
            .setContentTitle("VPN")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
