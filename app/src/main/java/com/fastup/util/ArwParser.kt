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
     * Extracts preview JPEG from Sony ARW (TIFF-based) file and saves to a thumbnail cache file.
     * Sony ARW files have an embedded JPEG preview inside.
     * We try:
     * 1. ExifInterface.thumbnailBitmap or thumbnailBytes
     * 2. Direct scan for embedded JPEG SOI (0xFFD8FFE0 or 0xFFD8FFE1) if ExifInterface fails
     */
    fun extractPreview(arwFile: File, outputCacheDir: File): File? {
        val previewFile = File(outputCacheDir, "${arwFile.nameWithoutExtension}_thumb.jpg")
        if (previewFile.exists() && previewFile.length() > 0) {
            return previewFile
        }

        try {
            val exif = ExifInterface(arwFile.absolutePath)
            if (exif.hasThumbnail()) {
                val thumbBytes = exif.thumbnailBytes
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    previewFile.outputStream().use { it.write(thumbBytes) }
                    Log.d(TAG, "Extracted preview via ExifInterface (${thumbBytes.size} bytes)")
                    return previewFile
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ExifInterface thumbnail extraction failed: ${e.message}")
        }

        // Fallback: Scan ARW header for embedded JPEG marker (SOI: FF D8 FF)
        try {
            val extracted = scanEmbeddedJpeg(arwFile, previewFile)
            if (extracted) {
                return previewFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct embedded JPEG scan failed: ${e.message}")
        }

        return null
    }

    private fun scanEmbeddedJpeg(arwFile: File, outputFile: File): Boolean {
        // Read the first 2MB where Sony embeds the preview thumbnail
        val maxScan = minOf(arwFile.length(), 4L * 1024 * 1024).toInt()
        val buffer = ByteArray(maxScan)

        RandomAccessFile(arwFile, "r").use { raf ->
            raf.readFully(buffer)
        }

        var jpegStart = -1
        for (i in 0 until maxScan - 4) {
            // Check for JPEG SOI (FF D8) followed by marker FF
            if ((buffer[i].toInt() and 0xFF) == 0xFF &&
                (buffer[i + 1].toInt() and 0xFF) == 0xD8 &&
                (buffer[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                // Potential JPEG start
                jpegStart = i
                break
            }
        }

        if (jpegStart == -1) return false

        // Locate JPEG EOI (FF D9) after start
        var jpegEnd = -1
        for (i in (jpegStart + 2) until maxScan - 1) {
            if ((buffer[i].toInt() and 0xFF) == 0xFF && (buffer[i + 1].toInt() and 0xFF) == 0xD9) {
                // Must be reasonably sized (> 10KB)
                if (i - jpegStart > 10 * 1024) {
                    jpegEnd = i + 2
                    break
                }
            }
        }

        if (jpegEnd > jpegStart) {
            val length = jpegEnd - jpegStart
            FileOutputStream(outputFile).use { fos ->
                fos.write(buffer, jpegStart, length)
            }
            Log.d(TAG, "Extracted embedded JPEG directly: $length bytes")
            return true
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
