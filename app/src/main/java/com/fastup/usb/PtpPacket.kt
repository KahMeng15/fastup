package com.fastup.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PtpHeader(
    val length: Long,
    val type: Int,
    val code: Int,
    val transactionId: Int
)

class PtpPacket private constructor() {
    companion object {
        const val HEADER_SIZE = 12

        fun createCommand(code: Int, transactionId: Int, vararg params: Int): ByteArray {
            val totalSize = HEADER_SIZE + (params.size * 4)
            val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(totalSize)
            buffer.putShort(SonyPtpConstants.PTP_CONTAINER_COMMAND.toShort())
            buffer.putShort(code.toShort())
            buffer.putInt(transactionId)
            for (p in params) {
                buffer.putInt(p)
            }
            return buffer.array()
        }

        fun parseHeader(bytes: ByteArray, offset: Int = 0): PtpHeader {
            val buffer = ByteBuffer.wrap(bytes, offset, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val length = buffer.int.toLong() and 0xFFFFFFFFL
            val type = buffer.short.toInt() and 0xFFFF
            val code = buffer.short.toInt() and 0xFFFF
            val transactionId = buffer.int
            return PtpHeader(length, type, code, transactionId)
        }
    }
}
