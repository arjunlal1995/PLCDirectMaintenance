package com.plc.directmaintenance.model

enum class AlertSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    INFO
}

data class PlcAlert(
    val id: String,
    val machineId: String,
    val machineName: String,
    val plcTag: String,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val timestamp: Long,
    val temperatureC: Float = 0f,
    val pressureBar: Float = 0f,
    val isAcknowledged: Boolean = false,
    val isResolved: Boolean = false
)
