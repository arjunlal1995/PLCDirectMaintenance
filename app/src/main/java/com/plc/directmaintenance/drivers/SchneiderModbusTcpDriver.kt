package com.plc.directmaintenance.drivers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Driver for RJ-45 to Type-C Ethernet connection directly to Schneider Electric Modicon PLCs.
 * Communicates via native Modbus TCP (Port 502) or X-Way Ethernet.
 *
 * Supported PLCs:
 * - Schneider Modicon M580 (BMEP58xxxx)
 * - Schneider Modicon M340 (BMXP34xxxx)
 * - Schneider Modicon M241 / M251
 *
 * Includes automatic default Schneider IP resolution from MAC Address (84.x.y.z standard).
 */
class SchneiderModbusTcpDriver {

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val transactionId = AtomicInteger(1)

    companion object {
        private const val TAG = "SchneiderModbusTcp"
        const val MODBUS_PORT = 502

        /**
         * Calculates Schneider Electric factory default fallback IP address
         * based on the PLC MAC address: 84.x.y.z (where x, y, z are the last 3 octets).
         */
        fun calculateDefaultSchneiderIp(macAddress: String): String {
            val parts = macAddress.replace(":", "-").split("-")
            if (parts.size == 6) {
                val x = parts[3].toIntOrNull(16) ?: 0
                val y = parts[4].toIntOrNull(16) ?: 0
                val z = parts[5].toIntOrNull(16) ?: 0
                return "84.$x.$y.$z"
            }
            return "192.168.1.10"
        }
    }

    suspend fun connect(ipAddress: String, timeoutMs: Int = 3000): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val s = Socket()
            s.connect(InetSocketAddress(ipAddress, MODBUS_PORT), timeoutMs)
            s.soTimeout = 2000
            s.tcpNoDelay = true

            socket = s
            inputStream = s.getInputStream()
            outputStream = s.getOutputStream()
            Log.i(TAG, "Connected to Schneider Modicon at $ipAddress:502 via RJ-45 to Type-C")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Schneider PLC: ${e.message}")
            false
        }
    }

    /**
     * Reads Schneider %MW (Holding Registers) via Modbus TCP Function 0x03.
     * @param startRegister Zero-based register address (e.g. 100 for %MW100)
     * @param count Number of 16-bit registers to read
     * @return List of 16-bit register values (IntArray)
     */
    suspend fun readHoldingRegisters(
        startRegister: Int,
        count: Int,
        unitId: Int = 255
    ): IntArray? = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext null
        val inStream = inputStream ?: return@withContext null

        val tId = transactionId.incrementAndGet() and 0xFFFF
        val request = ByteArray(12)

        // Modbus TCP MBAP Header
        request[0] = ((tId shr 8) and 0xFF).toByte()
        request[1] = (tId and 0xFF).toByte()
        request[2] = 0x00 // Protocol ID High (0 for Modbus)
        request[3] = 0x00 // Protocol ID Low
        request[4] = 0x00 // Length High
        request[5] = 0x06 // Length Low (6 bytes follow)
        request[6] = (unitId and 0xFF).toByte() // Unit ID / Slave Address

        // Function Code: 0x03 (Read Holding Registers)
        request[7] = 0x03
        // Starting Address
        request[8] = ((startRegister shr 8) and 0xFF).toByte()
        request[9] = (startRegister and 0xFF).toByte()
        // Quantity of Registers
        request[10] = ((count shr 8) and 0xFF).toByte()
        request[11] = (count and 0xFF).toByte()

        try {
            out.write(request)
            out.flush()

            val header = ByteArray(9)
            var bytesRead = 0
            while (bytesRead < 9) {
                val r = inStream.read(header, bytesRead, 9 - bytesRead)
                if (r == -1) return@withContext null
                bytesRead += r
            }

            val byteCount = header[8].toInt() and 0xFF
            val data = ByteArray(byteCount)
            bytesRead = 0
            while (bytesRead < byteCount) {
                val r = inStream.read(data, bytesRead, byteCount - bytesRead)
                if (r == -1) return@withContext null
                bytesRead += r
            }

            val result = IntArray(count)
            for (i in 0 until count) {
                val high = data[i * 2].toInt() and 0xFF
                val low = data[i * 2 + 1].toInt() and 0xFF
                result[i] = (high shl 8) or low
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error in Modbus TCP readHoldingRegisters", e)
            null
        }
    }

    /**
     * Reads Schneider System Words %SW (e.g., %SW50 error code, %SW0 task time).
     */
    suspend fun readSystemWords(startWord: Int, count: Int): IntArray? {
        // Schneider maps %SW to Holding Registers offset or custom system area
        return readHoldingRegisters(startWord, count, unitId = 255)
    }

    /**
     * Reads Schneider Coils / Internal Bits %M (Function 0x01).
     */
    suspend fun readInternalBits(startBit: Int, count: Int): BooleanArray? = withContext(Dispatchers.IO) {
        // Queries Modbus Coils
        val out = outputStream ?: return@withContext null
        val inStream = inputStream ?: return@withContext null

        val tId = transactionId.incrementAndGet() and 0xFFFF
        val request = ByteArray(12)
        request[0] = ((tId shr 8) and 0xFF).toByte()
        request[1] = (tId and 0xFF).toByte()
        request[2] = 0x00
        request[3] = 0x00
        request[4] = 0x00
        request[5] = 0x06
        request[6] = 0xFF.toByte() // Unit ID
        request[7] = 0x01 // Read Coils (%M)
        request[8] = ((startBit shr 8) and 0xFF).toByte()
        request[9] = (startBit and 0xFF).toByte()
        request[10] = ((count shr 8) and 0xFF).toByte()
        request[11] = (count and 0xFF).toByte()

        try {
            out.write(request)
            out.flush()

            val header = ByteArray(9)
            inStream.read(header, 0, 9)
            val byteCount = header[8].toInt() and 0xFF
            val data = ByteArray(byteCount)
            inStream.read(data, 0, byteCount)

            val bits = BooleanArray(count)
            for (i in 0 until count) {
                val byteIndex = i / 8
                val bitIndex = i % 8
                bits[i] = ((data[byteIndex].toInt() shr bitIndex) and 1) == 1
            }
            bits
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (ignored: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false
}
