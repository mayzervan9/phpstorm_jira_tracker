package com.mayze.jiratimetracker.util

fun formatSeconds(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
}

fun formatSecondsShort(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return "${h}h ${m}m"
}
