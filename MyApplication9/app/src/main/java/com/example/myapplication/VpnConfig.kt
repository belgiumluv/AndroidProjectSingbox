package com.example.myapplication

import java.io.Serializable

/** Конфиг системного VPN-интерфейса, который передаём из Activity в Service. */
data class VpnConfig(
    val session: String = "OnionVPN",
    val mtu: Int = 1400,
    val address: String = "172.19.0.2",
    val prefix: Int = 30,
    /** Маршруты вида "0.0.0.0/0", "10.0.0.0/8", "::/0", "2001:db8::/32" */
    val routes: ArrayList<String> = arrayListOf("0.0.0.0/0", "::/0"),
    /** Список DNS-серверов (IPv4/IPv6 строками) */
    val dns: ArrayList<String> = arrayListOf("1.1.1.1", "2606:4700:4700::1111")
) : Serializable
