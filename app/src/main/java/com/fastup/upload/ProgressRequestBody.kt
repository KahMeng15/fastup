package com.fastup.upload

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import java.io.IOException

class ProgressRequestBody(
    private val delegate: RequestBody,
    private val throttleKbps: Long = 0L,
    private val dropRatePercent: Int = 0,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val countingSink = CountingSink(sink)
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }

    private inner class CountingSink(delegate: Sink) : ForwardingSink(delegate) {
        private var bytesWritten = 0L
        private val totalBytes = contentLength()
        private val startTime = System.currentTimeMillis()

        // Randomly decide if this upload stream will be dropped midway
        private val willDropMidway = dropRatePercent > 0 && kotlin.random.Random.nextInt(100) < dropRatePercent
        // Drop midway between 20% and 75% of transfer
        private val dropThresholdBytes = if (willDropMidway && totalBytes > 0) {
            (totalBytes * kotlin.random.Random.nextDouble(0.20, 0.75)).toLong()
        } else -1L

        @Throws(IOException::class)
        override fun write(source: okio.Buffer, byteCount: Long) {
            if (willDropMidway && dropThresholdBytes > 0 && bytesWritten >= dropThresholdBytes) {
                throw IOException("Simulated network drop: TCP connection reset midway through upload ($bytesWritten / $totalBytes bytes)")
            }

            super.write(source, byteCount)
            bytesWritten += byteCount
            onProgress(bytesWritten, totalBytes)

            // Dynamic throttling to simulate slow bandwidth
            if (throttleKbps > 0) {
                val expectedMs = (bytesWritten * 1000L) / (throttleKbps * 1024L)
                val elapsedMs = System.currentTimeMillis() - startTime
                val sleepMs = expectedMs - elapsedMs
                if (sleepMs > 10) {
                    try {
                        Thread.sleep(sleepMs.coerceAtMost(300L))
                    } catch (_: InterruptedException) {}
                }
            }
        }
    }
}
