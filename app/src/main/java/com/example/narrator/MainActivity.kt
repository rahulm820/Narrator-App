package com.example.narratorapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.narrator.ObjectDetectorHelper
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var resultText: TextView
    private lateinit var viewFinder: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var tts: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultText = findViewById(R.id.resultText)
        viewFinder = findViewById(R.id.view_finder)

        // Initialize TextToSpeech
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.7f)
            }
        }
        
        
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Initialize the object detector
        objectDetectorHelper = ObjectDetectorHelper(context = this, listener = this)
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        val preview =
            Preview.Builder()
                .setTargetRotation(viewFinder.display.rotation)
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

        val imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetRotation(viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                        val bitmap = image.toBitmap()
                        objectDetectorHelper.detect(bitmap)
                        image.close()
                    }
                }

        cameraProvider.unbindAll()

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        } catch (exc: Exception) {
            Log.e("MainActivity", "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
        }
    }

    // Helper: compute direction + depth
    private fun getPositionDescription(
        box: android.graphics.RectF,
        imageWidth: Int,
        imageHeight: Int
    ): String {
        val centerX = box.centerX()
        val centerY = box.centerY()

        val directionX = when {
            centerX < imageWidth / 3 -> "left"
            centerX > imageWidth * 2 / 3 -> "right"
            else -> "center"
        }

        val directionY = when {
            centerY < imageHeight / 3 -> "top"
            centerY > imageHeight * 2 / 3 -> "bottom"
            else -> "middle"
        }

        val boxHeight = box.height()
        val depth = when {
            boxHeight > imageHeight / 2 -> "very close"
            boxHeight > imageHeight / 3 -> "near"
            else -> "far"
        }

        return "$directionX-$directionY, $depth"
    }

 private val stableObjects = mutableMapOf<String, Pair<Long, android.graphics.RectF>>() // label -> (firstSeenTime, rect)
private val stabilityThreshold = 2000L // 2 seconds in milliseconds

override fun onResults(
    results: MutableList<ObjectDetectorHelper.DetectionResult>,
    inferenceTime: Long,
    imageHeight: Int,
    imageWidth: Int
) {
    val currentTime = System.currentTimeMillis()

    // Keep track of which labels are detected in this frame
    val detectedThisFrame = mutableSetOf<String>()

    runOnUiThread {
        val sb = StringBuilder()

        for (result in results) {
            val label = result.label
            val rect = result.rect
            val score = result.confidence
            val formattedScore = NumberFormat.getPercentInstance().format(score)

            // Check if object was already in stableObjects
            val previous = stableObjects[label]
            if (previous != null) {
                val lastRect = previous.second
                val distance = distanceRects(lastRect, rect)
                if (distance < 50f) { // Object roughly in same place
                    val firstSeen = previous.first
                    if (currentTime - firstSeen >= stabilityThreshold) {
                        // Narrate only if stable for >= 2 seconds
                        val position = getPositionDescription(rect, imageWidth, imageHeight)
                        val depthMeters = estimateDepth(result.rect.height(), result.label)
                        val narration = "I see a ${result.label} at $position, approximately %.1f meters away.".format(depthMeters)
                        sb.append("$label: $formattedScore → $position\n")
                        tts.speak(narration, TextToSpeech.QUEUE_ADD, null, null)
                        Log.i("Narration", narration)
                        stableObjects.remove(label) // Prevent repeated narration until it disappears
                    }
                } else {
                    // Object moved significantly, reset timer
                    stableObjects[label] = currentTime to rect
                }
            } else {
                // New object, add to map
                stableObjects[label] = currentTime to rect
            }

            detectedThisFrame.add(label)
        }

        // Remove objects not seen in this frame
        stableObjects.keys.retainAll(detectedThisFrame)

        resultText.text = sb.toString().ifEmpty { "No objects detected" }
    }
}
private fun estimateDepth(boxHeightPx: Float, objectLabel: String): Float {
    val realHeights = mapOf(
        "person" to 1.7f,
        "car" to 1.5f,
        "bicycle" to 1.2f
        // Add more objects as needed
    )
    val H = realHeights[objectLabel] ?: 1.0f // default 1m if unknown
    val f = 1000f // approximate focal length in pixels (adjust for your camera)
    return H * f / boxHeightPx
}
// Helper function to measure distance between two RectFs
private fun distanceRects(rect1: android.graphics.RectF, rect2: android.graphics.RectF): Float {
    val dx = rect1.centerX() - rect2.centerX()
    val dy = rect1.centerY() - rect2.centerY()
    return Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
}

    override fun onError(error: String) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
