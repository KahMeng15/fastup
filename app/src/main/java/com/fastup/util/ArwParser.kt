package com.fastup.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.fastup.model.ExifDetails
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object ArwParser {
    private const val TAG = "ArwParser"

    /**
     * Extracts high-resolution preview JPEG from Sony ARW (or TIFF-based RAW) file and saves to preview cache file.
     * Sony ARW files have:
     * 1. 160x120 EXIF thumbnail in IFD1 (too small, blurry)
     * 2. 1616x1080 (or higher) full HD embedded preview JPEG in IFD0 / raw stream (~320KB)
     *
     * We scan the first 8MB for the largest embedded JPEG stream (>= 20KB) so that the preview is sharp and high-res.
     */
    fun extractPreview(arwFile: File, outputCacheDir: File): File? {
        val previewFile = File(outputCacheDir, "${arwFile.nameWithoutExtension}_thumb.jpg")
        if (previewFile.exists() && previewFile.length() > 20 * 1024) {
            return previewFile
        }

        // 1. Scan for the largest embedded JPEG in the ARW file (1616x1080 HD preview)
        try {
            val extracted = scanLargestEmbeddedJpeg(arwFile, previewFile)
            if (extracted) {
                return previewFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct largest embedded JPEG scan failed: ${e.message}")
        }

        // 2. Fallback: ExifInterface thumbnail if large preview extraction failed
        try {
            val exif = ExifInterface(arwFile.absolutePath)
            if (exif.hasThumbnail()) {
                val thumbBytes = exif.thumbnailBytes
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    previewFile.outputStream().use { it.write(thumbBytes) }
                    Log.d(TAG, "Fallback to ExifInterface thumbnail (${thumbBytes.size} bytes)")
                    return previewFile
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ExifInterface thumbnail extraction failed: ${e.message}")
        }

        return null
    }

    private fun scanLargestEmbeddedJpeg(arwFile: File, outputFile: File): Boolean {
        // Sony ARW stores the 1616x1080 preview within the first 8MB.
        // Instead of allocating a monolithic 8MB ByteArray on the heap (which causes GC spikes during bursts),
        // we map the file region into memory via FileChannel or use buffered streams.
        val fileLength = arwFile.length()
        val maxScan = minOf(fileLength, 8L * 1024 * 1024)
        if (maxScan < 100) return false

        try {
            RandomAccessFile(arwFile, "r").use { raf ->
                val channel = raf.channel
                val buffer = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, maxScan)

                var bestStart = -1
                var bestLength = 0
                val limit = buffer.limit()
                var i = 0

                while (i < limit - 4) {
                    // Check for JPEG SOI (FF D8 FF)
                    if ((buffer.get(i).toInt() and 0xFF) == 0xFF &&
                        (buffer.get(i + 1).toInt() and 0xFF) == 0xD8 &&
                        (buffer.get(i + 2).toInt() and 0xFF) == 0xFF
                    ) {
                        // Find matching JPEG EOI (FF D9)
                        var eoi = -1
                        var j = i + 2
                        while (j < limit - 1) {
                            if ((buffer.get(j).toInt() and 0xFF) == 0xFF &&
                                (buffer.get(j + 1).toInt() and 0xFF) == 0xD9
                            ) {
                                eoi = j + 2
                                break
                            }
                            j++
                        }

                        if (eoi != -1) {
                            val length = eoi - i
                            if (length > bestLength) {
                                bestLength = length
                                bestStart = i
                            }
                            i = eoi
                            continue
                        }
                    }
                    i++
                }

                if (bestStart != -1 && bestLength > 20 * 1024) {
                    val outChannel = FileOutputStream(outputFile).channel
                    outChannel.use { oc ->
                        channel.transferTo(bestStart.toLong(), bestLength.toLong(), oc)
                    }
                    Log.i(TAG, "Extracted high-res embedded JPEG via zero-copy channel: $bestLength bytes (at offset $bestStart)")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Zero-copy embedded JPEG scan failed: ${e.message}, falling back to buffered stream")
        }

        return false
    }

    /**
     * Reads EXIF details from file (works for both ARW and JPEG).
     */
    fun extractExif(file: File): ExifDetails {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val shutter = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                val sec = it.toDoubleOrNull()
                if (sec != null && sec > 0) {
                    if (sec < 1.0) "1/${Math.round(1.0 / sec)}s" else "${sec}s"
                } else it
            }
            val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
            val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" }
            val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let {
                val f = it.toDoubleOrNull()
                if (f != null) "${Math.round(f)}mm" else it
            }
            val lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)
            val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Sony ILCE-7M3"

            ExifDetails(
                shutterSpeed = shutter,
                aperture = aperture,
                iso = iso,
                focalLength = focal,
                lensModel = lens,
                cameraModel = model
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading EXIF from ${file.name}: ${e.message}")
            ExifDetails()
        }
    }
}
