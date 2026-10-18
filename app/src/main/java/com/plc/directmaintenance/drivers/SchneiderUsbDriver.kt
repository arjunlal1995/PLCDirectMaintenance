package com.plc.directmaintenance.drivers

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.*
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Android USB Driver for Schneider Electric Modicon PLCs (M221, M241, M251, M340, M580).
 * Handles direct USB-B / Mini-USB to USB-C connection using USB-OTG.
 *
 * Supports:
 * - CDC-ACM driver for built-in Modicon USB-B programming ports
 * - FTDI driver for Schneider TSX CUSB485 / TSXPCX3030 communication cables
 * - Baud rate: 19200 (Default Modbus), 8 data bits, Parity Even, 1 stop bit
 */
class SchneiderUsbDriver(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private var onDataReceivedListener: ((ByteArray) -> Unit)? = null
    private var onConnectionStatusChanged: ((Boolean, String) -> Unit)? = null

    companion object {
        private const val TAG = "SchneiderUsbDriver"
        const val ACTION_USB_PERMISSION = "com.plc.directmaintenance.USB_PERMISSION"

        // Schneider Electric Vendor ID: 0x2122 (8482 dec)
        const val SCHNEIDER_VENDOR_ID = 8482
        // FTDI Vendor ID for TSX CUSB485 cable: 0x0403 (1027 dec)
        const val FTDI_VENDOR_ID = 1027
    }

    fun setCallbacks(
        onData: (ByteArray) -> Unit,
        onStatus: (Boolean, String) -> Unit
    ) {
        this.onDataReceivedListener = onData
        this.onConnectionStatusChanged = onStatus
    }

    /**
     * Enumerates connected USB devices and connects to the Schneider PLC via USB-B.
     */
    fun findAndConnectSchneiderPlc() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            onConnectionStatusChanged?.invoke(false, "No USB devices detected. Connect USB-B to Type-C cable.")
            return
        }

        // Find Schneider device or FTDI programming adapter
        var targetDriver: UsbSerialDriver? = null
        for (driver in availableDrivers) {
            val device = driver.device
            if (device.vendorId == SCHNEIDER_VENDOR_ID || device.vendorId == FTDI_VENDOR_ID) {
                targetDriver = driver
                break
            }
        }

        // Fallback to first available serial port if custom converter used
        if (targetDriver == null && availableDrivers.isNotEmpty()) {
            targetDriver = availableDrivers[0]
        }

        if (targetDriver == null) {
            onConnectionStatusChanged?.invoke(false, "Schneider Modicon USB device not recognized.")
            return
        }

        val device = targetDriver.device
        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (ACTION_USB_PERMISSION == intent.action) {
                        synchronized(this) {
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            if (granted) {
                                openPort(targetDriver)
                            } else {
                                onConnectionStatusChanged?.invoke(false, "USB Host permission denied by user.")
                            }
                        }
                    }
                }
            }, filter)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            openPort(targetDriver)
        }
    }

    private fun openPort(driver: UsbSerialDriver) {
        try {
            val connection = usbManager.openDevice(driver.device)
                ?: throw IOException("Failed to open Android UsbDeviceConnection.")

            val port = driver.ports[0] // Most PLCs use port 0
            port.open(connection)

            // Standard Schneider Modicon communication parameters:
            // 19200 Baud, 8 Data Bits, 1 Stop Bit, Parity Even (Modbus RTU standard)
            port.setParameters(
                19200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_EVEN
            )

            usbSerialPort = port

            // Start IO listener thread
            val executor = Executors.newSingleThreadExecutor()
            ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    onDataReceivedListener?.invoke(data)
                }

                override fun onRunError(e: Exception) {
                    Log.e(TAG, "USB Serial run error: ${e.message}")
                    onConnectionStatusChanged?.invoke(false, "USB Connection lost: ${e.message}")
                }
            })
            executor.submit(ioManager)

            val modelName = when (driver.device.vendorId) {
                SCHNEIDER_VENDOR_ID -> "Schneider Modicon (USB-B Native)"
                FTDI_VENDOR_ID -> "Schneider TSX CUSB485 Cable"
                else -> "USB Serial (${driver.javaClass.simpleName})"
            }

            onConnectionStatusChanged?.invoke(true, "Connected via USB-B to $modelName (19200 8-E-1)")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Schneider USB port", e)
            onConnectionStatusChanged?.invoke(false, "Error: ${e.message}")
        }
    }

    /**
     * Reads Schneider Modicon Holding Register (%MW) over Modbus RTU USB.
     * Function Code 0x03: Read Holding Registers
     */
    suspend fun readHoldingRegister(slaveAddress: Int, registerAddress: Int, count: Int): ByteArray? = withContext(Dispatchers.IO) {
        val port = usbSerialPort ?: return@withContext null

        val frame = ByteArray(8)
        frame[0] = slaveAddress.toByte()
        frame[1] = 0x03 // Read Holding Registers
        frame[2] = ((registerAddress shr 8) and 0xFF).toByte()
        frame[3] = (registerAddress and 0xFF).toByte()
        frame[4] = ((count shr 8) and 0xFF).toByte()
        frame[5] = (count and 0xFF).toByte()

        val crc = calculateCrc16(frame, 6)
        frame[6] = (crc and 0xFF).toByte()
        frame[7] = ((crc shr 8) and 0xFF).toByte()

        try {
            port.write(frame, 1000)
            val buffer = ByteArray(256)
            val len = port.read(buffer, 1000)
            if (len > 0) buffer.copyOf(len) else null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading %MW register over USB", e)
            null
        }
    }

    fun disconnect() {
        ioManager?.stop()
        try {
            usbSerialPort?.close()
        } catch (ignored: Exception) {}
        usbSerialPort = null
        onConnectionStatusChanged?.invoke(false, "Disconnected")
    }

    private fun calculateCrc16(buffer: ByteArray, length: Int): Int {
        var crc = 0xFFFF
        for (pos in 0 until length) {
            crc = crc xor (buffer[pos].toInt() and 0xFF)
            for (i in 8 downTo 1) {
                if ((crc and 0x0001) != 0) {
                    crc = (crc shr 1) xor 0xA001
                } else {
                    crc = crc shr 1
                }
            }
        }
        return crc
    }
}
