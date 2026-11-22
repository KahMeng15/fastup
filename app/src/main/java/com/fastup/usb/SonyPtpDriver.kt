package com.fastup.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SonyPtpDriver(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val interruptIn: UsbEndpoint?
) {
    private var transactionId = 1
    private var sessionOpen = false

    private fun nextTransactionId(): Int = transactionId++

    /**
     * Clears endpoint halt / stall conditions using USB standard control request CLEAR_FEATURE.
     */
    fun clearHalt() {
        try {
            connection.controlTransfer(0x02, 0x01, 0x00, bulkIn.address, null, 0, 1000)
            connection.controlTransfer(0x02, 0x01, 0x00, bulkOut.address, null, 0, 1000)
            interruptIn?.let {
                connection.controlTransfer(0x02, 0x01, 0x00, it.address, null, 0, 1000)
            }
            Log.d(TAG, "Cleared endpoint halt conditions on IN, OUT, and INT")
        } catch (e: Exception) {
            Log.w(TAG, "clearHalt failed: ${e.message}")
        }
    }

    /**
     * Opens PTP session and executes Sony SDIO handshake to enter PC Remote mode.
     */
    fun openSession(): Boolean {
        try {
            clearHalt()
            transactionId = 1

            // Per PTP standard ISO 15740: transactionId for OpenSession MUST be 0x00000000
            val cmd = PtpPacket.createCommand(SonyPtpConstants.PTP_OP_OPEN_SESSION, 0, 1)
            sendBulk(cmd)

            val resp = readTransactionResponse()
            val isOpen = resp != null && (resp.code == SonyPtpConstants.PTP_RESP_OK || resp.code == SonyPtpConstants.PTP_RESP_SESSION_ALREADY_OPEN)
            
            if (!isOpen) {
                Log.e(TAG, "Failed opening PTP session: code=0x${resp?.code?.toString(16)}")
                return false
            }

            sessionOpen = true
            Log.i(TAG, "PTP Session opened successfully (code=0x${resp?.code?.toString(16)})")

            // Execute Sony SDIO handshake phases 1, 2, 3 to exit "Connecting..." state
            val sdio1 = sendSdioConnect(1)
            Log.i(TAG, "Sony SDIO Connect phase 1: $sdio1")

            val sdio2 = sendSdioConnect(2)
            Log.i(TAG, "Sony SDIO Connect phase 2: $sdio2")

            val sdio3 = sendSdioConnect(3)
            Log.i(TAG, "Sony SDIO Connect phase 3: $sdio3")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception opening PTP session", e)
        }
        return false
    }

    /**
     * Sends Sony proprietary SDIO_Connect (0x9201) command.
     */
    fun sendSdioConnect(phase: Int): Boolean {
        return try {
            val tid = nextTransactionId()
            val cmd = PtpPacket.createCommand(SonyPtpConstants.SONY_OP_SDIO_CONNECT, tid, phase, 0, 0)
            if (!sendBulk(cmd)) return false
            val resp = readTransactionResponse()
            val ok = resp != null && resp.code == SonyPtpConstants.PTP_RESP_OK
            Log.d(TAG, "SDIO connect phase $phase resp: code=0x${resp?.code?.toString(16)}, type=${resp?.type}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "SDIO connect phase $phase error: ${e.message}")
            false
        }
    }

    fun closeSession() {
        if (!sessionOpen) return
        try {
            val tid = nextTransactionId()
            val cmd = PtpPacket.createCommand(SonyPtpConstants.PTP_OP_CLOSE_SESSION, tid)
            sendBulk(cmd)
            readTransactionResponse()
            sessionOpen = false
            Log.i(TAG, "PTP Session closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PTP session: ${e.message}")
        }
    }

    /**
     * Polls the Interrupt IN endpoint for incoming PTP event (e.g. ObjectAdded or Sony ObjectReady).
     */
    fun pollEvent(timeoutMs: Int = 200): Pair<Int, Int?>? {
        val ep = interruptIn ?: return null
        val buffer = ByteArray(ep.maxPacketSize)
        val read = connection.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
        if (read >= PtpPacket.HEADER_SIZE) {
            val header = PtpPacket.parseHeader(buffer)
            if (header.type == SonyPtpConstants.PTP_CONTAINER_EVENT) {
                var param1: Int? = null
                if (header.length >= 16) {
                    val bb = ByteBuffer.wrap(buffer, 12, 4).order(ByteOrder.LITTLE_ENDIAN)
                    param1 = bb.int
                }
                return Pair(header.code, param1)
            }
        }
        return null
    }

    /**
     * Checks Sony property 0xD215 (ObjectInMemory).
     * Returns true if an object is resident in camera RAM waiting for transfer (bit 15 set, value > 0x8000).
     */
    fun hasObjectInMemory(): Boolean {
        return try {
            val tid = nextTransactionId()
            val cmd = PtpPacket.createCommand(
                SonyPtpConstants.PTP_OP_GET_DEVICE_PROP_VALUE,
                tid,
                SonyPtpConstants.SONY_DPC_OBJECT_IN_MEMORY
            )
            if (!sendBulk(cmd)) return false

            val maxPacket = bulkIn.maxPacketSize.coerceAtLeast(16384)
            val buffer = ByteArray(maxPacket)
            val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, 1000)
            if (read < PtpPacket.HEADER_SIZE) return false

            val header = PtpPacket.parseHeader(buffer)
            if (header.type != SonyPtpConstants.PTP_CONTAINER_DATA) {
                Log.d(TAG, "hasObjectInMemory returned non-data container: type=${header.type}, code=0x${header.code.toString(16)}")
                return false
            }

            val payloadLen = read - PtpPacket.HEADER_SIZE
            val value = if (payloadLen >= 2) {
                val bb = ByteBuffer.wrap(buffer, PtpPacket.HEADER_SIZE, 2).order(ByteOrder.LITTLE_ENDIAN)
                bb.short.toInt() and 0xFFFF
            } else 0

            // Consume trailing response container
            readTransactionResponse()

            Log.d(TAG, "Sony ObjectInMemory (0xD215) value: 0x${value.toString(16)} ($value)")
            (value > 0x8000) || (value and 0x8000 != 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking hasObjectInMemory: ${e.message}")
            false
        }
    }

    /**
     * Retrieves filename and approximate size from ObjectInfo dataset.
     * Returns null if no object is ready or camera returned a response container directly (e.g. queue empty).
     */
    fun getObjectInfo(handle: Int, retries: Int = 3): Pair<String, Long>? {
        for (attempt in 1..retries) {
            val tid = nextTransactionId()
            val cmd = PtpPacket.createCommand(SonyPtpConstants.PTP_OP_GET_OBJECT_INFO, tid, handle)
            if (!sendBulk(cmd)) {
                if (attempt < retries) {
                    try { Thread.sleep(150) } catch (ignored: InterruptedException) {}
                    continue
                }
                return null
            }

            val maxPacket = bulkIn.maxPacketSize.coerceAtLeast(16384)
            val buffer = ByteArray(maxPacket)
            val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, 2000)
            if (read < PtpPacket.HEADER_SIZE) {
                if (attempt < retries) {
                    try { Thread.sleep(150) } catch (ignored: InterruptedException) {}
                    continue
                }
                return null
            }

            val header = PtpPacket.parseHeader(buffer)
            if (header.type == SonyPtpConstants.PTP_CONTAINER_RESPONSE) {
                Log.d(TAG, "getObjectInfo(0x${handle.toString(16)}) returned response container directly: code=0x${header.code.toString(16)}")
                return null
            }

            if (header.type != SonyPtpConstants.PTP_CONTAINER_DATA) {
                Log.w(TAG, "getObjectInfo expected DATA container, got type ${header.type}")
                if (attempt < retries) {
                    try { Thread.sleep(150) } catch (ignored: InterruptedException) {}
                    continue
                }
                return null
            }

            val totalDataLen = (header.length - PtpPacket.HEADER_SIZE).toInt()
            val bos = ByteArrayOutputStream(if (totalDataLen > 0) totalDataLen else 1024)

            val firstChunk = read - PtpPacket.HEADER_SIZE
            if (firstChunk > 0) {
                bos.write(buffer, PtpPacket.HEADER_SIZE, firstChunk)
            }

            while (bos.size() < totalDataLen) {
                val chunk = connection.bulkTransfer(bulkIn, buffer, buffer.size, 2000)
                if (chunk <= 0) break
                bos.write(buffer, 0, chunk)
            }

            // Consume trailing response container
            val resp = readTransactionResponse()
            val data = bos.toByteArray()

            if (data.size < 52) {
                Log.w(TAG, "getObjectInfo returned insufficient data: size=${data.size}")
                if (attempt < retries) {
                    try { Thread.sleep(150) } catch (ignored: InterruptedException) {}
                    continue
                }
                return null
            }

            try {
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(8) // Skip StorageID(4), ObjectFormat(2), ProtectionStatus(2)
                val compressedSize = bb.int.toLong() and 0xFFFFFFFFL

                // Skip thumb & image dimensions to reach filename string
                // thumbFormat(2) + thumbCompressed(4) + thumbWidth(4) + thumbHeight(4)
                // + imgWidth(4) + imgHeight(4) + imgBitDepth(4) + parentObj(4)
                // + assocType(2) + assocDesc(4) + seqNum(4) = 40 bytes
                bb.position(12 + 40)

                val filename = readPtpString(bb) ?: "DSC_${handle}.ARW"
                Log.i(TAG, "Parsed ObjectInfo for 0x${handle.toString(16)}: $filename ($compressedSize bytes)")
                return Pair(filename, compressedSize)
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing ObjectInfo: ${e.message}")
                if (attempt < retries) {
                    try { Thread.sleep(150) } catch (ignored: InterruptedException) {}
                    continue
                }
                return null
            }
        }
        return null
    }

    /**
     * Streams object payload directly into destination file.
     */
    fun downloadObject(
        handle: Int,
        destinationFile: File,
        expectedSize: Long = 0L,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean {
        val tid = nextTransactionId()
        val cmd = PtpPacket.createCommand(SonyPtpConstants.PTP_OP_GET_OBJECT, tid, handle)
        if (!sendBulk(cmd)) {
            Log.e(TAG, "Failed sending PTP_OP_GET_OBJECT command")
            return false
        }

        val maxPacket = bulkIn.maxPacketSize.coerceAtLeast(16384)
        val buffer = ByteArray(maxPacket)

        // Read initial data container header
        val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, 10000)
        Log.i(TAG, "downloadObject initial read: $read bytes")
        if (read < PtpPacket.HEADER_SIZE) {
            Log.e(TAG, "Failed reading initial PTP Data packet for handle 0x${handle.toString(16)} (read=$read)")
            return false
        }

        val header = PtpPacket.parseHeader(buffer)
        Log.i(TAG, "downloadObject header: length=${header.length}, type=${header.type}, code=0x${header.code.toString(16)}, tid=${header.transactionId}")

        if (header.type == SonyPtpConstants.PTP_CONTAINER_RESPONSE) {
            Log.e(TAG, "Camera returned PTP Response container directly: code=0x${header.code.toString(16)}")
            return false
        }

        if (header.type != SonyPtpConstants.PTP_CONTAINER_DATA) {
            Log.e(TAG, "Expected PTP Data container (2), got type ${header.type}")
            return false
        }

        // Calculate expected data size
        val knownTotal = if (header.length > PtpPacket.HEADER_SIZE && header.length != 0xFFFFFFFFL) {
            header.length - PtpPacket.HEADER_SIZE
        } else {
            expectedSize
        }

        var bytesReceived = 0L

        FileOutputStream(destinationFile).use { fos ->
            val firstChunkPayload = read - PtpPacket.HEADER_SIZE
            if (firstChunkPayload > 0) {
                fos.write(buffer, PtpPacket.HEADER_SIZE, firstChunkPayload)
                bytesReceived += firstChunkPayload
                onProgress?.invoke(bytesReceived, knownTotal)
            }

            // If we know the exact target length > 0
            if (knownTotal > 0) {
                while (bytesReceived < knownTotal) {
                    val toRead = minOf(buffer.size.toLong(), knownTotal - bytesReceived).toInt()
                    val chunkRead = connection.bulkTransfer(bulkIn, buffer, toRead, 10000)
                    if (chunkRead <= 0) {
                        Log.e(TAG, "USB transfer interrupted while downloading handle 0x${handle.toString(16)} ($bytesReceived / $knownTotal)")
                        break
                    }
                    fos.write(buffer, 0, chunkRead)
                    bytesReceived += chunkRead
                    onProgress?.invoke(bytesReceived, knownTotal)
                }
            } else {
                // Unknown total length: read until stream completes (packet smaller than buffer / max packet)
                while (true) {
                    val chunkRead = connection.bulkTransfer(bulkIn, buffer, buffer.size, 5000)
                    if (chunkRead <= 0) break
                    fos.write(buffer, 0, chunkRead)
                    bytesReceived += chunkRead
                    onProgress?.invoke(bytesReceived, bytesReceived)
                    if (chunkRead < buffer.size) {
                        // Short packet marks end of data stream
                        break
                    }
                }
            }
            fos.flush()
        }

        // Consume trailing PTP Response packet
        val resp = readTransactionResponse()
        Log.i(TAG, "Completed object 0x${handle.toString(16)} download: $bytesReceived bytes, resp=0x${resp?.code?.toString(16)}")
        return bytesReceived > 0
    }

    private fun readPtpString(bb: ByteBuffer): String? {
        if (!bb.hasRemaining()) return null
        val numChars = bb.get().toInt() and 0xFF
        if (numChars == 0) return ""
        val chars = CharArray(numChars - 1)
        for (i in 0 until numChars - 1) {
            if (bb.remaining() >= 2) {
                chars[i] = bb.char
            }
        }
        if (bb.hasRemaining()) bb.char // Read null terminator
        return String(chars)
    }

    private fun sendBulk(data: ByteArray, retries: Int = 3): Boolean {
        for (attempt in 1..retries) {
            val sent = connection.bulkTransfer(bulkOut, data, data.size, 5000)
            if (sent >= 0) return true
            Log.w(TAG, "bulkTransfer write failed (sent=$sent, attempt $attempt/$retries)")
            if (attempt < retries) {
                try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {}
            }
        }
        return false
    }

    private fun receiveData(): ByteArray? {
        val maxPacket = bulkIn.maxPacketSize.coerceAtLeast(16384)
        val buffer = ByteArray(maxPacket)
        val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, 5000)
        if (read < PtpPacket.HEADER_SIZE) return null

        val header = PtpPacket.parseHeader(buffer)
        if (header.type != SonyPtpConstants.PTP_CONTAINER_DATA) return null

        val totalDataLen = (header.length - PtpPacket.HEADER_SIZE).toInt()
        val bos = ByteArrayOutputStream(if (totalDataLen > 0) totalDataLen else 1024)

        val firstChunk = read - PtpPacket.HEADER_SIZE
        if (firstChunk > 0) {
            bos.write(buffer, PtpPacket.HEADER_SIZE, firstChunk)
        }

        while (bos.size() < totalDataLen) {
            val chunk = connection.bulkTransfer(bulkIn, buffer, buffer.size, 5000)
            if (chunk <= 0) break
            bos.write(buffer, 0, chunk)
        }
        return bos.toByteArray()
    }

    /**
     * Reads the transaction response. If a DATA container (type 2) arrives, it consumes the data
     * payload and then reads the trailing RESPONSE container (type 3).
     */
    private fun readTransactionResponse(): PtpHeader? {
        val buffer = ByteArray(4096)
        val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, 5000)
        if (read < PtpPacket.HEADER_SIZE) return null
        var header = PtpPacket.parseHeader(buffer)

        if (header.type == SonyPtpConstants.PTP_CONTAINER_DATA) {
            val totalDataLen = (header.length - PtpPacket.HEADER_SIZE).toInt()
            var bytesRead = read - PtpPacket.HEADER_SIZE
            while (bytesRead < totalDataLen) {
                val toRead = minOf(buffer.size, totalDataLen - bytesRead)
                val chunk = connection.bulkTransfer(bulkIn, buffer, toRead, 5000)
                if (chunk <= 0) break
                bytesRead += chunk
            }
            // Read trailing response container
            val respRead = connection.bulkTransfer(bulkIn, buffer, buffer.size, 5000)
            if (respRead >= PtpPacket.HEADER_SIZE) {
                header = PtpPacket.parseHeader(buffer)
            }
        }
        return header
    }

    companion object {
        private const val TAG = "SonyPtpDriver"
    }
}
