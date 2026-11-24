package com.fastup.model

data class NetworkSimulationConfig(
    val enabled: Boolean = false,
    val profile: String = "OFF", // "OFF", "CROWDED_WIFI", "HIGH_LATENCY_3G", "VERY_SLOW_2G", "CUSTOM"
    val latencyMs: Long = 0L,
    val dropRatePercent: Int = 0,
    val throttleKbps: Long = 0L
) {
    companion object {
        val OFF = NetworkSimulationConfig(
            enabled = false,
            profile = "OFF",
            latencyMs = 0L,
            dropRatePercent = 0,
            throttleKbps = 0L
        )

        val CROWDED_WIFI = NetworkSimulationConfig(
            enabled = true,
            profile = "CROWDED_WIFI",
            latencyMs = 350L,
            dropRatePercent = 20,
            throttleKbps = 1200L // 1.2 MB/s
        )

        val HIGH_LATENCY_3G = NetworkSimulationConfig(
            enabled = true,
            profile = "HIGH_LATENCY_3G",
            latencyMs = 800L,
            dropRatePercent = 8,
            throttleKbps = 380L // 380 KB/s
        )

        val VERY_SLOW_2G = NetworkSimulationConfig(
            enabled = true,
            profile = "VERY_SLOW_2G",
            latencyMs = 1600L,
            dropRatePercent = 12,
            throttleKbps = 80L // 80 KB/s
        )
    }
}
