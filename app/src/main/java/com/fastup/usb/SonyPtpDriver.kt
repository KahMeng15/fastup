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
            // Drain any residual bytes on bulkIn left over from previous process/session
            val drainBuf = ByteArray(16384)
            while (connection.bulkTransfer(bulkIn, drainBuf, drainBuf.size, 50) > 0) {}

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

            if (resp != null && resp.code == SonyPtpConstants.PTP_RESP_SESSION_ALREADY_OPEN) {
                Log.w(TAG, "PTP Session was already open on camera (0x201e). Resetting session...")
                closeSession()
                try { Thread.sleep(200) } catch (ignored: InterruptedException) {}
                transactionId = 1
                val retryCmd = PtpPacket.createCommand(SonyPtpConstants.PTP_OP_OPEN_SESSION, 0, 1)
                sendBulk(retryCmd)
                val retryResp = readTransactionResponse()
                Log.i(TAG, "Reopened PTP session: code=0x${retryResp?.code?.toString(16)}")
            }

            sessionOpen = true
            Log.i(TAG, "PTP Session opened successfully (code=0x${resp?.code?.toString(16)})")

            // Execute Sony SDIO handshake: phase 1 → phase 2 → GetExtDeviceInfo → phase 3
            // GetExtDeviceInfo (0x9202) MUST be called between phases 2 and 3, otherwise
            // phase 3 returns 0xa101 (device not in correct state) and the camera never
            // properly enters PC Remote mode — causing the buffer to never drain after captures.
            val sdio1 = sendSdioConnect(1)
            Log.i(TAG, "Sony SDIO Connect phase 1: $sdio1")

            val sdio2 = sendSdioConnect(2)
            Log.i(TAG, "Sony SDIO Connect phase 2: $sdio2")

            val extInfo = sendSdioGetExtDeviceInfo()
            Log.i(TAG, "Sony SDIO_GetExtDeviceInfo: $extInfo")

            val sdio3 = sendSdioConnect(3)
            Log.i(TAG, "Sony SDIO Connect phase 3: $sdio3")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception opening PTP session", e)
        }
        return false
    }

    /**
     * Sends Sony SDIO_Connect (0x9201) command.
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

    /**
     * Sends Sony SDIO_GetExtDeviceInfo (0x9202) to fetch device capabilities.
     *
     * This MUST be called between SDIO_Connect phase 2 and phase 3. Without it,
     * phase 3 returns 0xa101 and the camera never fully enters PC Remote mode,
     * causing the buffer/write indicator to stay stuck after each capture.
     *
     * The camera returns a data payload (device capability blob) followed by a
     * PTP response. We consume both and treat any successful resp as OK.
     */
    fun sendSdioGetExtDeviceInfo(deviceType: Int = 0xC8): Boolean {
        return try {
            val tid = nextTransactionId()
            val cmd = PtpPacket.createCommand(SonyPtpConstants.SONY_OP_SDIO_GET_EXT_DEVICE_INFO, tid, deviceType)
            if (!sendBulk(cmd)) return false
            // readTransactionResponse already handles DATA container + trailing RESPONSE
            val resp = readTransactionResponse(timeoutMs = 3000)
            val ok = resp != null && resp.code == SonyPtpConstants.PTP_RESP_OK
            Log.i(TAG, "SDIO_GetExtDeviceInfo (0x9202) resp: 0x${resp?.code?.toString(16)}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "SDIO_GetExtDeviceInfo error: ${e.message}")
            false
        }
    }

    fun closeSession() {
        if (!sessionOpen) return
        try {
            val tid = nextTransactionId()
            val cmd = PtpPacket.createCommand(SonyPtpConstants.PTP_OP_CLOSE_SESSION, tid)
            sendBulk(cmd)
            readTransactionResponse(timeoutMs = 500)
            sessionOpen = false
            Log.i(TAG, "PTP Session closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PTP session: ${e.message}")
        }
    }

    /**
     * Completely releases the PTP session, releases claimed USB interfaces,
     * and closes the native UsbDeviceConnection file descriptor.
     */
    fun release() {
        closeSession()
        try {
            connection.releaseInterface(usbInterface)
            Log.i(TAG, "Released USB interface: ${usbInterface.id}")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing USB interface: ${e.message}")
        }
        try {
            connection.close()
            Log.i(TAG, "Closed native USB connection")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing USB connection: ${e.message}")
        }
    }

    private val interruptBuffer by lazy { interruptIn?.let { ByteArray(it.maxPacketSize) } }
    private val liveViewBuffer by lazy { ByteArray(bulkIn.maxPacketSize.coerceAtLeast(16384)) }

    /**
     * Polls the Interrupt IN endpoint for incoming PTP event (e.g. ObjectAdded, Sony ObjectReady, or PropChanged).
     * Returns Triple(eventCode, param1, param2)
     */
    fun pollEvent(timeoutMs: Int = 200): Triple<Int, Int?, Int?>? {
        val ep = interruptIn ?: return null
        val buffer = interruptBuffer ?: return null
        val read = connection.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
        if (read >= PtpPacket.HEADER_SIZE) {
            val header = PtpPacket.parseHeader(buffer)
            if (header.type == SonyPtpConstants.PTP_CONTAINER_EVENT) {
                var param1: Int? = null
                var param2: Int? = null
                if (header.length >= 16 && read >= 16) {
                    val bb = ByteBuffer.wrap(buffer, 12, 4).order(ByteOrder.LITTLE_ENDIAN)
                    param1 = bb.int
                }
                if (header.length >= 20 && read >= 20) {
                    val bb = ByteBuffer.wrap(buffer, 16, 4).order(ByteOrder.LITTLE_ENDIAN)
                    param2 = bb.int
                }
                return Triple(header.code, param1, param2)
            }
        }
        return null
    }

    /**
     * Retrieves filename and approximate size from ObjectInfo dataset.
     * Returns null if no object is ready or camera returned a permanent error.
     */
    fun getObjectInfo(handle: Int, retries: Int = 2): Pair<String, Long>? {
        for (attempt in 1..retries) {
            val tid = nextTransactionId()
            val cmd = PtpPacket.createCommand(SonyPtpConstants.PTP_OP_GET_OBJECT_INFO, tid, handle)
            if (!sendBulk(cmd)) {
                if (attempt < retries) {
                    try { Thread.sleep(100) } catch (ignored: InterruptedException) {}
                    continue
                }
                return null
            }

            val maxPacket = bulkIn.maxPacketSize.coerceAtLeast(16384)
            val buffer = ByteArray(maxPacket)
            val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, 1500)
            if (read < PtpPacket.HEADER_SIZE) {
                if (attempt < retries) {
                    try { Thread.sleep(100) } catch (ignored: InterruptedException) {}
                    continue
                }
                return null
            }

            val header = PtpPacket.parseHeader(buffer)
            if (header.type == SonyPtpConstants.PTP_CONTAINER_RESPONSE) {
                if (header.code == SonyPtpConstants.PTP_RESP_OK && attempt < retries) {
                    // Camera said OK but sent no data: RAW companion or next object not ready yet.
                    val backoff = 150L
                    Log.d(TAG, "getObjectInfo(0x${handle.toString(16)}) attempt $attempt: not ready (0x2001), retrying in ${backoff}ms...")
                    try { Thread.sleep(backoff) } catch (ignored: InterruptedException) {}
                    continue
                }
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

        val buffer = ByteArray(32768)

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

        var responseAlreadyConsumed = false
        FileOutputStream(destinationFile).use { fos ->
            val firstChunkPayload = read - PtpPacket.HEADER_SIZE
            if (firstChunkPayload > 0) {
                val toWrite = if (knownTotal > 0) minOf(firstChunkPayload.toLong(), knownTotal).toInt() else firstChunkPayload
                fos.write(buffer, PtpPacket.HEADER_SIZE, toWrite)
                bytesReceived += toWrite
                onProgress?.invoke(bytesReceived, knownTotal)

                val surplus = firstChunkPayload - toWrite
                if (surplus >= PtpPacket.HEADER_SIZE) {
                    responseAlreadyConsumed = true
                }
            }

            // If we know the exact target length > 0
            if (knownTotal > 0) {
                while (bytesReceived < knownTotal) {
                    // ALWAYS read using full buffer.size (multiple of maxPacketSize) on bulkIn to avoid USB babble/stalls
                    val chunkRead = connection.bulkTransfer(bulkIn, buffer, buffer.size, 10000)
                    if (chunkRead <= 0) {
                        Log.e(TAG, "USB transfer interrupted while downloading handle 0x${handle.toString(16)} ($bytesReceived / $knownTotal)")
                        break
                    }
                    val toWrite = minOf(chunkRead.toLong(), knownTotal - bytesReceived).toInt()
                    fos.write(buffer, 0, toWrite)
                    bytesReceived += toWrite
                    onProgress?.invoke(bytesReceived, knownTotal)

                    val surplus = chunkRead - toWrite
                    if (surplus >= PtpPacket.HEADER_SIZE) {
                        responseAlreadyConsumed = true
                    }
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

        // Consume trailing PTP Response packet if not already bundled with data
        if (!responseAlreadyConsumed) {
            val resp = readTransactionResponse()
            Log.i(TAG, "Completed object 0x${handle.toString(16)} download: $bytesReceived bytes, resp=0x${resp?.code?.toString(16)}")
        } else {
            Log.i(TAG, "Completed object 0x${handle.toString(16)} download: $bytesReceived bytes (response in payload)")
        }
        return bytesReceived > 0
    }

    @Volatile
    var lastLiveViewResponseCode: Int = 0
        private set

    /**
     * Fetches a single Live View JPEG frame from Sony virtual handle (0xFFFFC002).
     * Decodes the proprietary Sony packet header to find the JPEG payload offset.
     */
    fun getLiveViewFrame(handle: Int = SonyPtpConstants.LIVEVIEW_OBJECT_HANDLE): ByteArray? {
        val tid = nextTransactionId()
        val cmd = PtpPacket.createCommand(SonyPtpConstants.PTP_OP_GET_OBJECT, tid, handle)
        if (!sendBulk(cmd)) {
            return null
        }

        val buffer = liveViewBuffer

        // Read initial data container header with safe 2500ms timeout
        val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, 2500)
        if (read < PtpPacket.HEADER_SIZE) {
            // Bulk read timed out or failed: drain any arriving bytes so endpoint stays aligned
            while (connection.bulkTransfer(bulkIn, buffer, buffer.size, 100) > 0) {}
            return null
        }

        val header = PtpPacket.parseHeader(buffer)
        if (header.type == SonyPtpConstants.PTP_CONTAINER_RESPONSE) {
            lastLiveViewResponseCode = header.code
            // Direct response (e.g. 0x200F AccessDenied or 0x2019 DeviceBusy when shutter is pressed)
            return null
        }

        lastLiveViewResponseCode = 0

        if (header.type != SonyPtpConstants.PTP_CONTAINER_DATA) {
            readTransactionResponse(timeoutMs = 200)
            return null
        }

        val totalDataLen = if (header.length > PtpPacket.HEADER_SIZE && header.length != 0xFFFFFFFFL) {
            (header.length - PtpPacket.HEADER_SIZE).toInt()
        } else -1

        val bos = ByteArrayOutputStream(if (totalDataLen in 1..2000000) totalDataLen else 65536)
        val firstChunk = read - PtpPacket.HEADER_SIZE
        var responseAlreadyConsumed = false
        if (firstChunk > 0) {
            val toWrite = if (totalDataLen > 0) minOf(firstChunk, totalDataLen) else firstChunk
            bos.write(buffer, PtpPacket.HEADER_SIZE, toWrite)
            val surplus = firstChunk - toWrite
            if (surplus >= PtpPacket.HEADER_SIZE) {
                responseAlreadyConsumed = true
            }
        }

        if (totalDataLen > 0) {
            while (bos.size() < totalDataLen) {
                // ALWAYS read using full buffer.size (multiple of maxPacketSize) to avoid babble/stalls
                val chunk = connection.bulkTransfer(bulkIn, buffer, buffer.size, 1000)
                if (chunk <= 0) break
                val toWrite = minOf(chunk, totalDataLen - bos.size())
                bos.write(buffer, 0, toWrite)

                val surplus = chunk - toWrite
                if (surplus >= PtpPacket.HEADER_SIZE) {
                    responseAlreadyConsumed = true
                }
            }
        } else {
            while (true) {
                val chunk = connection.bulkTransfer(bulkIn, buffer, buffer.size, 500)
                if (chunk <= 0) break
                bos.write(buffer, 0, chunk)
                if (chunk < buffer.size) break
            }
        }

        // Consume trailing response container if not already received
        if (!responseAlreadyConsumed) {
            readTransactionResponse(timeoutMs = 300)
        }

        val raw = bos.toByteArray()
        if (raw.size < 8) return null

        // Locate JPEG SOI marker (0xFF, 0xD8).
        // On Sony Alpha, the first 4 bytes of the data payload specify the offset to the JPEG SOI (libgphoto2 #389).
        var soiIndex = -1
        val offset = (raw[0].toInt() and 0xFF) or
                ((raw[1].toInt() and 0xFF) shl 8) or
                ((raw[2].toInt() and 0xFF) shl 16) or
                ((raw[3].toInt() and 0xFF) shl 24)

        if (offset in 0 until raw.size - 1 &&
            raw[offset] == 0xFF.toByte() && raw[offset + 1] == 0xD8.toByte()
        ) {
            soiIndex = offset
        } else {
            // Fallback: search after header
            for (i in 64 until raw.size - 1) {
                if (raw[i] == 0xFF.toByte() && raw[i + 1] == 0xD8.toByte()) {
                    soiIndex = i
                    break
                }
            }
        }

        if (soiIndex == -1) {
            // Fallback from index 0
            for (i in 0 until raw.size - 1) {
                if (raw[i] == 0xFF.toByte() && raw[i + 1] == 0xD8.toByte()) {
                    soiIndex = i
                    break
                }
            }
        }

        if (soiIndex == -1) return null

        // Locate JPEG EOI marker (0xFF, 0xD9) scanning from end backwards
        var eoiIndex = -1
        for (i in raw.size - 1 downTo soiIndex + 2) {
            if (raw[i - 1] == 0xFF.toByte() && raw[i] == 0xD9.toByte()) {
                eoiIndex = i
                break
            }
        }

        val ret = if (eoiIndex > soiIndex) {
            raw.copyOfRange(soiIndex, eoiIndex + 1)
        } else {
            raw.copyOfRange(soiIndex, raw.size)
        }
        Log.d(TAG, "getLiveViewFrame: raw=${raw.size}, offset=$offset, soiIndex=$soiIndex, eoiIndex=$eoiIndex -> frame=${ret.size} bytes")
        return ret
    }

    /**
     * NOTE: Sony A7III in PC Remote mode rejects PTP DeleteObject (0x100B) by immediately
     * stalling the bulk-in endpoint. Do NOT call this — the camera releases its RAM buffer
     * automatically after GetObject completes once the SDIO handshake (phases 1,2,
     * GetExtDeviceInfo, 3) is fully established.
     *
     * Kept here for reference only. Always returns false without sending anything.
     */
    @Suppress("unused")
    fun deleteObject(handle: Int): Boolean {
        Log.d(TAG, "deleteObject(0x${handle.toString(16)}) skipped — not supported in PC Remote mode")
        return false
    }

    /**
     * Triggers the camera shutter via Sony SDIO_Capture (0x9207).
     * Param 2 = AF + full shutter press. Useful for automated testing.
     */
    fun triggerCapture(): Boolean {
        return try {
            val tid = nextTransactionId()
            val cmd = PtpPacket.createCommand(SonyPtpConstants.SONY_OP_CAPTURE, tid, 2)
            if (!sendBulk(cmd)) {
                Log.w(TAG, "triggerCapture: failed to send command")
                return false
            }
            val resp = readTransactionResponse(timeoutMs = 5000)
            val ok = resp != null && resp.code == SonyPtpConstants.PTP_RESP_OK
            Log.i(TAG, "triggerCapture resp: 0x${resp?.code?.toString(16)}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "triggerCapture error: ${e.message}")
            false
        }
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
            val sent = connection.bulkTransfer(bulkOut, data, data.size, 2000)
            if (sent >= 0) return true
            Log.w(TAG, "bulkTransfer write failed (sent=$sent, attempt $attempt/$retries)")
            // Clear endpoint halt to restore USB pipe toggle alignment
            clearHalt()
            // If bulkOut is rejected, camera may have pending bytes on bulkIn blocking the pipe
            val drainBuf = ByteArray(16384)
            while (connection.bulkTransfer(bulkIn, drainBuf, drainBuf.size, 100) > 0) {}
            if (attempt < retries) {
                try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {}
            }
        }
        return false
    }

    private fun receiveData(): ByteArray? {
        val buffer = ByteArray(32768)
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
            val toWrite = minOf(chunk, totalDataLen - bos.size())
            bos.write(buffer, 0, toWrite)
        }
        return bos.toByteArray()
    }

    /**
     * Reads the transaction response. If a DATA container (type 2) arrives, it consumes the data
     * payload and then reads the trailing RESPONSE container (type 3).
     */
    private fun readTransactionResponse(timeoutMs: Int = 2000): PtpHeader? {
        val buffer = ByteArray(4096)
        val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
        if (read < PtpPacket.HEADER_SIZE) return null
        var header = PtpPacket.parseHeader(buffer)

        if (header.type == SonyPtpConstants.PTP_CONTAINER_DATA) {
            val totalDataLen = (header.length - PtpPacket.HEADER_SIZE).toInt()
            var bytesRead = read - PtpPacket.HEADER_SIZE
            var responseAlreadyConsumed = false
            val surplusFirst = bytesRead - totalDataLen
            if (surplusFirst >= PtpPacket.HEADER_SIZE) {
                responseAlreadyConsumed = true
            }

            while (bytesRead < totalDataLen) {
                // ALWAYS read full buffer.size to avoid USB babble/stalls
                val chunk = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
                if (chunk <= 0) break
                val toConsume = minOf(chunk, totalDataLen - bytesRead)
                bytesRead += toConsume
                val surplus = chunk - toConsume
                if (surplus >= PtpPacket.HEADER_SIZE) {
                    responseAlreadyConsumed = true
                }
            }
            // Read trailing response container if not already bundled
            if (!responseAlreadyConsumed) {
                val respRead = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
                if (respRead >= PtpPacket.HEADER_SIZE) {
                    header = PtpPacket.parseHeader(buffer)
                }
            }
        }
        return header
    }

    companion object {
        private const val TAG = "SonyPtpDriver"
    }
}
