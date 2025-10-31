package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MyVpnService : VpnService() {

    companion object {
        private const val TAG = "MyVpnService"
        private const val NOTIF_CHANNEL_ID = "singbox_vpn_min"
        private const val NOTIF_ID = 1
        const val EXTRA_VPN_CONFIG = "vpn_config"

        // факт поднятого TUN
        private val _vpnEstablished = AtomicBoolean(false)

        /** True, если TUN-интерфейс создан. */
        fun isEstablished(): Boolean {
            val v = _vpnEstablished.get()
            Log.i(TAG, "isEstablished(): $v")
            return v
        }

        /** "Подключено": равносильно isEstablished() (трафик проверяем теперь в Activity). */
        fun isConnected(): Boolean = isEstablished()
    }
    private var vpnInterface: ParcelFileDescriptor? = null
    private var boxService: BoxService? = null
    private var boxStarted = false

    private lateinit var cm: ConnectivityManager
    private lateinit var platform: AndroidPlatformInterface

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating VPN service")
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("VPN запущен"))

        // libbox базовая инициализация
        val workDir = File(filesDir, "singbox").apply { mkdirs() }
        val tmpDir = File(cacheDir, "singbox_tmp").apply { mkdirs() }
        val stderrPath = File(workDir, "libbox_stderr.log").absolutePath
        try {
            val opts = SetupOptions().apply {
                setBasePath(filesDir.absolutePath)
                setWorkingPath(workDir.absolutePath)
                setTempPath(tmpDir.absolutePath)
                setUsername("")
                setFixAndroidStack(true)
            }
            Libbox.setup(opts)
            Libbox.redirectStderr(stderrPath)
            Libbox.setMemoryLimit(true)
            Log.i(TAG, "Libbox version: ${Libbox.version()}")
        } catch (e: Exception) {
            Log.w(TAG, "Libbox.setup/log redirect failed: ${e.message}")
        }

        // Платформенный интерфейс; fd отдаём только после establish()
        platform = AndroidPlatformInterface(
            service = this,
            cm = cm,
            tunFdSupplier = { vpnInterface?.fd ?: -1 }
        )

        // Подготовим/проверим config.json ОДИН раз здесь
        val rawConfig = try {
            assets.open("config.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config.json: ${e.message}")
            stopSelf(); return
        }
        if (rawConfig.isBlank()) { stopSelf(); return }

        val config = try {
            val root = JSONObject(rawConfig)
            root.optJSONObject("route")?.put("auto_detect_interface", false)
            root.optJSONObject("dns")?.optJSONArray("servers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val addr = s.optString("address", "")
                    if (!addr.startsWith("rcode://")) s.put("detour", "Select")
                }
            }
            val exp = root.optJSONObject("experimental") ?: JSONObject().also { root.put("experimental", it) }
            val cache = exp.optJSONObject("cache_file") ?: JSONObject().also { exp.put("cache_file", it) }
            cache.put("enabled", true)
            cache.put("path", File(workDir, "cache.db").absolutePath)
            cache.remove("cache_id")
            root.toString()
        } catch (_: Exception) { rawConfig }

        Log.i(TAG, "Checking sing-box config…")
        Libbox.checkConfig(config)

        // Создаём boxService, НО НЕ запускаем!
        boxService = Libbox.newService(config, platform)
        platform.setBoxService(boxService)
        boxStarted = false
        Log.i(TAG, "BoxService created, waiting for TUN to be established before start()")

        // сетевой колбэк
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    try {
                        boxService?.resetNetwork()
                        if (boxService?.needWIFIState() == true) boxService?.updateWIFIState()
                    } catch (_: Exception) {}
                }
            })
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Конфиг обязателен: если его нет — не поднимаем TUN и не стартуем box
        val cfg = intent?.getSerializableExtra(EXTRA_VPN_CONFIG) as? VpnConfig
        if (cfg == null) {
            Log.e(TAG, "onStartCommand(): VpnConfig is REQUIRED but missing. Skipping TUN/box start.")
            return START_STICKY
        }
        Log.i(TAG, "Applying VpnConfig from Activity: $cfg")

        // 1) Поднимаем/пересоздаём TUN
        applyVpnConfig(cfg)

        // 2) Только теперь запускаем sing-box (если ещё не запущен)
        if (!boxStarted) {
            try {
                boxService?.start()
                boxStarted = true
                Log.i(TAG, "Sing-box started successfully AFTER TUN establish")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start sing-box: ${e.message}")
            }
        } else {
            try { boxService?.resetNetwork() } catch (_: Exception) {}
        }
        return START_STICKY
    }

    private fun applyVpnConfig(cfg: VpnConfig) {
        // закрываем старый TUN
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        _vpnEstablished.set(false)

        val b = Builder()
            .setSession(cfg.session)
            .setMtu(cfg.mtu)

        try { b.addAddress(cfg.address, cfg.prefix) } catch (e: Exception) {
            Log.e(TAG, "addAddress failed for ${cfg.address}/${cfg.prefix}: ${e.message}")
        }

        for (route in cfg.routes) {
            val parts = route.trim().split("/")
            if (parts.size == 2) {
                val addr = parts[0]
                val pfx = parts[1].toIntOrNull()
                if (pfx != null) {
                    try { b.addRoute(addr, pfx) } catch (e: Exception) {
                        Log.w(TAG, "addRoute failed for $route: ${e.message}")
                    }
                }
            }
        }

        for (dns in cfg.dns) {
            try { b.addDnsServer(dns) } catch (e: Exception) {
                Log.w(TAG, "addDnsServer failed for $dns: ${e.message}")
            }
        }

        try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}

        val pfd = b.establish()
        if (pfd == null) {
            Log.e(TAG, "Failed to establish VPN interface with provided config")
            return
        }
        vpnInterface = pfd
        _vpnEstablished.set(true)
        Log.i(TAG, "VPN established with fd=${pfd.fd}")

        try {
            val active = cm.activeNetwork
            @Suppress("DEPRECATION")
            cm.bindProcessToNetwork(active)
            setUnderlyingNetworks(if (active != null) arrayOf(active) else null)
        } catch (e: Exception) {
            Log.w(TAG, "bind/setUnderlying failed: ${e.message}")
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping VPN service")
        try { @Suppress("DEPRECATION") cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        try { setUnderlyingNetworks(null) } catch (_: Exception) {}
        try { boxService?.pause() } catch (_: Exception) {}
        try { boxService?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        _vpnEstablished.set(false)
        boxStarted = false
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "VPN service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                description = "Foreground for VPN"
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
}
