package com.plc.directmaintenance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.plc.directmaintenance.drivers.SchneiderPlcManager
import com.plc.directmaintenance.model.AlertSeverity
import com.plc.directmaintenance.model.PlcAlert
import com.plc.directmaintenance.notifications.NotificationHelper
import com.plc.directmaintenance.service.DirectMaintenanceService

class MainActivity : ComponentActivity() {

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var schneiderManager: SchneiderPlcManager

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // High-importance notification channels active
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannels()

        schneiderManager = SchneiderPlcManager(this, notificationHelper)

        checkAndRequestPermissions()
        startDirectMaintenanceService()

        // Automatically listen to Schneider USB-B or RJ-45 to Type-C connection
        schneiderManager.startRealTimeMonitoring("192.168.1.10")

        setContent {
            DirectMaintenanceTheme {
                MainScreen(
                    onTriggerTestAlert = { severity, title, msg, tag ->
                        val alert = PlcAlert(
                            id = "ALT-" + System.currentTimeMillis().toString().takeLast(4),
                            machineId = "LINE-01-PR01",
                            machineName = "Schneider Modicon M580 (USB-B/RJ-45)",
                            plcTag = tag,
                            severity = severity,
                            title = title,
                            message = msg,
                            timestamp = System.currentTimeMillis(),
                            temperatureC = 88.5f,
                            pressureBar = 240.0f
                        )
                        notificationHelper.postCustomRealTimeAlert(alert)
                    }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startDirectMaintenanceService() {
        val serviceIntent = Intent(this, DirectMaintenanceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        schneiderManager.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onTriggerTestAlert: (AlertSeverity, String, String, String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PLC DIRECT MAINTENANCE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "● Schneider USB-B & RJ-45 to Type-C Drivers Active",
                            color = Color(0xFF10B981),
                            fontSize = 11.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF020617)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Schneider Hardware Status Banner
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "SCHNEIDER ELECTRIC HARDWARE INTERFACE",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• USB-B Port: CDC-ACM / FTDI 19200 8-E-1 Ready\n• RJ-45 to Type-C: Modbus TCP (Port 502) Active\n• Registers: %MW (Holding), %SW (System), %M (Coils)",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Diagnostic Trigger Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onTriggerTestAlert(
                            AlertSeverity.CRITICAL,
                            "SCHNEIDER %M1 E-STOP TRIPPED",
                            "Emergency Stop button pushed on Modicon M580 rack via USB-B safety bus.",
                            "%M1_ESTOP_ACTIVE"
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🚨 %M1 E-Stop", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        onTriggerTestAlert(
                            AlertSeverity.HIGH,
                            "SCHNEIDER %SW50 FAULT 0x4210",
                            "Modicon system word %SW50 reported internal I/O bus communication error.",
                            "%SW50_DIAG_ERR"
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("⚠️ %SW50 Fault", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun DirectMaintenanceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFF59E0B),
            background = Color(0xFF020617),
            surface = Color(0xFF0F172A)
        ),
        content = content
    )
}
