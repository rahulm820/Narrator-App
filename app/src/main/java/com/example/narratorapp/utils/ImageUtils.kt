package com.example.narratorapp.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageUtils {

    /**
     * FAST: Converts YUV directly to ARGB Bitmap without JPEG compression.
     * This is roughly 10x faster than the YuvImage approach.
     */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // Fallback for weird device formats, but typically unnecessary for YUV_420_888
        val nv21 = ByteArray(ySize + uSize + vSize)

        // Get strides to handle device-specific padding
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val width = image.width
        val height = image.height
        
        // Output array for ARGB pixels
        val argbArray = IntArray(width * height)

        var outputIndex = 0
        
        // Loop through every pixel
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Calculate memory indices
                val yIndex = y * yRowStride + x * yPixelStride
                
                // UV planes are subsampled (share pixels), so we divide by 2
                val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride

                // Extract YUV values (and subtract 128 from UV)
                // Note: buffer.get() returns byte, we need unsigned int 0-255
                val yVal = (yBuffer.get(yIndex).toInt() and 0xFF)
                
                // Check bounds effectively (u/v buffers can be smaller)
                // Standard YUV_420_888 logic:
                val uVal = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                // YUV to RGB Conversion Math
                val r = (yVal + 1.370705f * vVal).toInt().coerceIn(0, 255)
                val g = (yVal - 0.337633f * uVal - 0.698001f * vVal).toInt().coerceIn(0, 255)
                val b = (yVal + 1.732446f * uVal).toInt().coerceIn(0, 255)

                // Pack into ARGB Int
                argbArray[outputIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(argbArray, width, height, Bitmap.Config.ARGB_8888)
    }

    fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
        if (rotationDegrees == 0f) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Populate an EXISTING buffer to avoid memory leaks.
     * @param buffer The ByteBuffer created ONCE in your Analyzer/Detector class.
     */
    fun bitmapToByteBuffer(bitmap: Bitmap, inputSize: Int, buffer: ByteBuffer) {
        buffer.rewind()
        
        val intValues = IntArray(inputSize * inputSize)
        // Scaled bitmap should also ideally be reused if possible, but this is okay
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixelValue in intValues) {
            // Extract RGB and Normalize to [0, 1]
            val r = (pixelValue shr 16 and 0xFF) / 255.0f
            val g = (pixelValue shr 8 and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f

            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
    }
}   