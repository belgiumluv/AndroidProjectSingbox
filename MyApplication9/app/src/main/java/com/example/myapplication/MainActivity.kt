package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_REQUEST_CODE = 0x0F
    }

    private var pendingConfig: VpnConfig? = null

    // --- Поле для подсчёта скорости в Activity ---
    private var prevTs: Long = 0L
    private var prevRx: Long = -1L
    private var prevTx: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Activity created")

        // Готовим свой конфиг
        val cfg = VpnConfig(
            session = "MySession",
            mtu = 1500,
            address = "10.23.0.2",
            prefix = 24,
            routes = arrayListOf("0.0.0.0/0", "::/0"),
            dns = arrayListOf("8.8.8.8", "2001:4860:4860::8888")
        )
        startVpn(cfg)

        // Инициализируем базовые значения для подсчёта скорости
        initSpeedBaseline()

        // Пример: периодический лог скорости из АКТИВИТИ (раз в секунду)
        window.decorView.postDelayed(speedLogger, 2000)

        // Пример: проверка статуса туннеля (без учёта трафика)
        window.decorView.postDelayed({
            val connected = MyVpnService.isConnected()
            Log.i(TAG, "VPN connected (TUN)? $connected")
        }, 3000)
    }

    fun isConnected(): Boolean {
        val connected = MyVpnService.isConnected()
        return connected
    }


    // --- ПУБЛИЧНАЯ функция Activity: получить текущую скорость (B/s) ---
    fun getSpeedBps(): Pair<Long, Long>? {
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)

        if (rx < 0 || tx < 0) return null

        val now = SystemClock.elapsedRealtime()
        if (prevTs == 0L || prevRx < 0 || prevTx < 0) {
            // первая инициализация — нет дельты
            prevTs = now
            prevRx = rx
            prevTx = tx
            return null
        }

        val dtMs = max(1L, now - prevTs)
        val dRx = rx - prevRx
        val dTx = tx - prevTx

        prevTs = now
        prevRx = rx
        prevTx = tx

        val downBps = if (dRx >= 0) (dRx * 1000L) / dtMs else 0L
        val upBps = if (dTx >= 0) (dTx * 1000L) / dtMs else 0L

        return downBps to upBps
    }

    // Инициализация базовой точки для подсчёта скорости
    private fun initSpeedBaseline() {
        val uid = android.os.Process.myUid()
        prevRx = TrafficStats.getUidRxBytes(uid)
        prevTx = TrafficStats.getUidTxBytes(uid)
        prevTs = SystemClock.elapsedRealtime()
    }

    // Периодический логгер скорости из Activity (не из сервиса!)
    private val speedLogger = object : Runnable {
        override fun run() {
            val sp = getSpeedBps()
            if (sp != null) {
                val (down, up) = sp
                Log.i(TAG, "Speed (Activity): ↓$down B/s ↑$up B/s")
            } else {
                Log.i(TAG, "Speed is not ready yet (Activity)")
            }
            window.decorView.postDelayed(this, 1000)
        }
    }

    /** Запускает VPN ТОЛЬКО с переданным конфигом */
    fun startVpn(config: VpnConfig) {
        Log.i(TAG, "Запуск VPN c config=$config")
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingConfig = config
            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
        } else {
            startVpnService(config)
        }
    }

    /** Останавливает VPN  */
    fun stopVpn(view: View? = null) {
        Log.i(TAG, "Остановка VPN")
        val stopIntent = Intent(this, MyVpnService::class.java)
        stopService(stopIntent)
    }

    @Deprecated("Using legacy onActivityResult for VpnService.prepare flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "VPN permission granted by user")
                val cfg = pendingConfig
                if (cfg != null) {
                    startVpnService(cfg)
                } else {
                    Log.e(TAG, "No pending VpnConfig after permission, aborting start.")
                }
            } else {
                Log.e(TAG, "VPN permission denied by user")
            }
            pendingConfig = null
        }
    }

    private fun startVpnService(config: VpnConfig) {
        Log.i(TAG, "Запуск службы MyVpnService с config=$config")
        val serviceIntent = Intent(this, MyVpnService::class.java).apply {
            putExtra(MyVpnService.EXTRA_VPN_CONFIG, config)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onDestroy() {
        // Остановим периодический логгер
        window.decorView.removeCallbacks(speedLogger)
        super.onDestroy()
    }
}
