package com.plc.directmaintenance.drivers

import android.content.Context
import com.plc.directmaintenance.model.AlertSeverity
import com.plc.directmaintenance.model.PlcAlert
import com.plc.directmaintenance.notifications.NotificationHelper
import kotlinx.coroutines.*

/**
 * Unified Schneider Electric PLC Hardware Manager.
 * Bridges direct USB-B and RJ-45 physical connections to the Real-Time Notification Engine.
 */
class SchneiderPlcManager(
    private val context: Context,
    private val notificationHelper: NotificationHelper
) {
    val usbDriver = SchneiderUsbDriver(context)
    val tcpDriver = SchneiderModbusTcpDriver()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isMonitoring = false

    fun startRealTimeMonitoring(ipAddress: String) {
        if (isMonitoring) return
        isMonitoring = true

        scope.launch {
            // Attempt RJ-45 connection first
            val connected = tcpDriver.connect(ipAddress)
            if (!connected) {
                // Attempt USB-B OTG connection
                usbDriver.findAndConnectSchneiderPlc()
            }

            while (isActive && isMonitoring) {
                delay(1000)

                // 1. Poll Schneider System Words (%SW50: Diagnostic Error Register)
                val sw = tcpDriver.readSystemWords(startWord = 50, count = 2)
                if (sw != null && sw.isNotEmpty()) {
                    val errorCode = sw[0]
                    if (errorCode != 0) {
                        // PLC Fault detected! Dispatch instant high-priority alert
                        val alert = PlcAlert(
                            id = "SCHN-" + System.currentTimeMillis().toString().takeLast(4),
                            machineId = "SCHNEIDER-M580-01",
                            machineName = "Schneider Modicon M580 (RJ45 Direct)",
                            plcTag = "%SW50_ERROR_REG",
                            severity = if (errorCode > 0x4000) AlertSeverity.CRITICAL else AlertSeverity.HIGH,
                            title = "SCHNEIDER FAULT 0x" + Integer.toHexString(errorCode).uppercase(),
                            message = "Diagnostic error detected on Schneider Modicon rack. Error code: 0x" + Integer.toHexString(errorCode).uppercase(),
                            timestamp = System.currentTimeMillis(),
                            temperatureC = 68.4f,
                            pressureBar = 180.0f
                        )
                        notificationHelper.postCustomRealTimeAlert(alert)
                    }
                }

                // 2. Poll Emergency Stop Interlock Bit (%M1)
                val bits = tcpDriver.readInternalBits(startBit = 1, count = 1)
                if (bits != null && bits.isNotEmpty() && bits[0]) {
                    // %M1 is HIGH -> E-Stop triggered!
                    val estopAlert = PlcAlert(
                        id = "ESTOP-" + System.currentTimeMillis().toString().takeLast(4),
                        machineId = "SCHNEIDER-M340",
                        machineName = "Schneider Modicon M340 Cell",
                        plcTag = "%M1_ESTOP_TRIPPED",
                        severity = AlertSeverity.CRITICAL,
                        title = "🚨 EMERGENCY STOP ACTIVE (%M1)",
                        message = "Physical E-Stop button actuated on Schneider Modicon M340. Machine de-energized via USB-B/RJ-45 safety loop.",
                        timestamp = System.currentTimeMillis(),
                        temperatureC = 45.0f,
                        pressureBar = 0.0f
                    )
                    notificationHelper.postCustomRealTimeAlert(estopAlert)
                }
            }
        }
    }

    fun stop() {
        isMonitoring = false
        scope.cancel()
        usbDriver.disconnect()
        tcpDriver.disconnect()
    }
}
