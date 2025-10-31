package com.example.myapplication

/** Человекочитаемое форматирование скорости (байт/с) */
fun formatSpeed(bps: Long): String {
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s", "TB/s")
    var v = bps.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return String.format("%.1f %s", v, units[i])
}
