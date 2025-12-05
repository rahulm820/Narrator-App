package com.example.narratorapp.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OCRProcessor {

    // Use Builder pattern for better accuracy
    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.Builder()
            .build()
    )

    // Old callback-based method (keep for compatibility)
    fun detect(bitmap: Bitmap, onResult: (List<OCRLine>) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val results = mutableListOf<OCRLine>()
                visionText.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        results.add(
                            OCRLine(
                                text = line.text,
                                boundingBox = line.boundingBox ?: Rect()
                            )
                        )
                    }
                }
                onResult(results)
            }
            .addOnFailureListener { e ->
                Log.e("OCRProcessor", "Text recognition failed", e)
                onResult(emptyList())
            }
    }
    
    // NEW: Synchronous coroutine-friendly method
    suspend fun detectSync(bitmap: Bitmap, rotationDegrees: Int): List<OCRLine> {
        return try {
            // CRITICAL: Don't rotate the bitmap - use rotation parameter
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            val visionText = recognizer.process(image).await()
            
            val results = mutableListOf<OCRLine>()
            
            // Process ALL text blocks, not just lines
            visionText.textBlocks.forEach { block ->
                // Add the entire block as one result (better for paragraphs)
                Log.i("OCRProcessor", "Detected block: ${block.text}")
                if (block.text.length > 1) {  // Skip single characters
                    results.add(
                        OCRLine(
                            text = block.text,
                            boundingBox = block.boundingBox ?: Rect()
                        )
                    )
                }
            }
            
            Log.d("OCRProcessor", "Detected ${results.size} text blocks: ${results.map { it.text }}")
            results
        } catch (e: Exception) {
            Log.e("OCRProcessor", "Text recognition failed", e)
            emptyList()
        }
    }
}