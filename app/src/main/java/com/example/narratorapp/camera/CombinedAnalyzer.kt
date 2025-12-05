package com.example.narratorapp.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.detection.ObjectDetector
import com.example.narratorapp.memory.FaceDetector
import com.example.narratorapp.memory.MemoryManager
import com.example.narratorapp.narration.DecisionEngine
import com.example.narratorapp.narration.TTSManager
import com.example.narratorapp.navigation.NavigationEngine
import com.example.narratorapp.ocr.OCRLine
import com.example.narratorapp.ocr.OCRProcessor
import com.example.narratorapp.utils.ImageUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.LinkedBlockingQueue

class CombinedAnalyzer(
    private val context: Context,
    private val ttsManager: TTSManager,
    private val overlayView: OverlayView? = null,
    private val navigationEngine: NavigationEngine? = null,
    private val memoryManager: MemoryManager? = null
) : ImageAnalysis.Analyzer {

    private val objectDetector = ObjectDetector(context)
    private val ocrProcessor = OCRProcessor()
    private val faceDetector = FaceDetector()
    private val decisionEngine = DecisionEngine(ttsManager)
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private val detectionDispatcher = Dispatchers.Default.limitedParallelism(1)
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ocrDispatcher = Dispatchers.Default.limitedParallelism(1)
    
    private val scope = CoroutineScope(SupervisorJob())
    private val bitmapQueue = LinkedBlockingQueue<BitmapTask>(2)
    
    private val isDetectingObjects = AtomicBoolean(false)
    private val isOCRing = AtomicBoolean(false)
    private val isRecognizing = AtomicBoolean(false)
    
    private var lastAnalysisTime = 0L
    private val analysisInterval = 200L
    
    private var lastTextDetectionTime = 0L
    private val textCooldown = 2000L
    
    private var lastFaceRecognitionTime = 0L
    private val faceRecognitionCooldown = 5000L
    
    private var lastPlaceRecognitionTime = 0L
    private val placeRecognitionCooldown = 10000L
    
    private var frameCount = 0
    private var processedFrameCount = 0
    
    private var dimensionsInitialized = false
    
    private var lastMode: Mode = Mode.OBJECT_AND_TEXT

    enum class Mode {
        OBJECT_AND_TEXT,
        READING_ONLY,
        RECOGNITION_MODE
    }

    var mode = Mode.OBJECT_AND_TEXT
        set(value) {
            if (field != value) {
                Log.i("CombinedAnalyzer", "=== MODE CHANGED: ${field.name} → ${value.name} ===")
                
                // CRITICAL: Clear all pending tasks when mode changes
                bitmapQueue.clear()
                decisionEngine.reset()
                
                when (value) {
                    Mode.READING_ONLY -> {
                        // Clear objects, keep text
                        overlayView?.post {
                            overlayView.objects = emptyList()
                            overlayView.postInvalidate()
                        }
                    }
                    Mode.OBJECT_AND_TEXT, Mode.RECOGNITION_MODE -> {
                        // Clear text
                        overlayView?.post {
                            overlayView.texts = emptyList()
                            overlayView.postInvalidate()
                        }
                    }
                }
                
                lastMode = field
                field = value
            }
        }

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            frameCount++
            
            if (!dimensionsInitialized && overlayView != null) {
                val width = image.width
                val height = image.height
                val rotation = image.imageInfo.rotationDegrees
                
                overlayView.post {
                    overlayView.updateSourceSize(width, height, rotation)
                    Log.i("CombinedAnalyzer", "✓ Overlay updated: ${width}x${height}, rotation=$rotation")
                }
                dimensionsInitialized = true
            }
            
            if (now - lastAnalysisTime < analysisInterval) {
                return
            }
            
            lastAnalysisTime = now
            processedFrameCount++
            
            val rotationDegrees = image.imageInfo.rotationDegrees
            val bitmap = ImageUtils.imageProxyToBitmap(image)
            
            val task = BitmapTask(bitmap, bitmap.width, bitmap.height, rotationDegrees)
            
            if (!bitmapQueue.offer(task)) {
                return
            }
            
            when (mode) {
                Mode.OBJECT_AND_TEXT -> processObjectsAndText()
                Mode.READING_ONLY -> processTextOnly()
                Mode.RECOGNITION_MODE -> processRecognition()
            }
        } finally {
            image.close()
        }
    }
    
    private fun processObjectsAndText() {
        val task = bitmapQueue.poll() ?: return
        val bitmap = task.bitmap
        
        if (isDetectingObjects.compareAndSet(false, true)) {
            scope.launch(detectionDispatcher) {
                try {
                    if (mode != Mode.OBJECT_AND_TEXT) {
                        return@launch
                    }
                    
                    val startTime = System.currentTimeMillis()
                    val detections = objectDetector.detect(bitmap)
                    val detectionTime = System.currentTimeMillis() - startTime
                    
                    if (mode != Mode.OBJECT_AND_TEXT) {
                        return@launch
                    }
                    
                    val depthData = mutableMapOf<String, ObjectWithDepth>()
                    if (detections.isNotEmpty() && navigationEngine != null) {
                        for (obj in detections) {
                            val depth = navigationEngine.arCoreManager.getDepthForBoundingBox(obj.boundingBox)
                            val position = getObjectPosition(obj, task.width, task.height)
                            depthData[obj.label] = ObjectWithDepth(obj, depth, position)
                        }
                    }
                    
                    navigationEngine?.processObstacles(detections)
                    
                    withContext(Dispatchers.Main) {
                        if (mode == Mode.OBJECT_AND_TEXT) {
                            overlayView?.apply {
                                objects = detections
                                texts = emptyList()
                                postInvalidate()
                            }
                        }
                    }
                    
                    if (mode == Mode.OBJECT_AND_TEXT) {
                        decisionEngine.processWithDepth(depthData.values.toList())
                    }
                    
                } catch (e: Exception) {
                    Log.e("CombinedAnalyzer", "Detection error", e)
                } finally {
                    isDetectingObjects.set(false)
                }
            }
        }
         
        if (memoryManager != null && frameCount % 10 == 0 && mode == Mode.OBJECT_AND_TEXT) {
            scope.launch(detectionDispatcher) {
                val detections = overlayView?.objects ?: emptyList()
                val personDetections = detections.filter { 
                    it.label == "person" && it.confidence > 0.6f 
                }
                if (personDetections.isNotEmpty()) {
                    tryRecognizeFaces(bitmap, personDetections)
                }
            }
        }
    }
    
    private fun getObjectPosition(obj: DetectedObject, imageWidth: Int, imageHeight: Int): String {
        val centerX = obj.boundingBox.centerX()
        val leftThird = imageWidth / 3f
        val rightThird = imageWidth * 2f / 3f
        
        return when {
            centerX < leftThird -> "on your left"
            centerX > rightThird -> "on your right"
            else -> "ahead"
        }
    }

    private fun processTextOnly() {
        val task = bitmapQueue.poll() ?: return
        val bitmap = task.bitmap
        
        if (isOCRing.compareAndSet(false, true)) {
            scope.launch(ocrDispatcher) {
                try {
                    if (mode != Mode.READING_ONLY) {
                        return@launch
                    }
                    
                    // Note: Rotation passed to detectSync to handle orientation
                    val texts = ocrProcessor.detectSync(bitmap, rotationDegrees = task.rotationDegrees)
                    
                    if (mode != Mode.READING_ONLY) {
                        return@launch
                    }
                    
                    // ===== FIXED: Update overlay even if list is empty =====
                    // This ensures objects are cleared and "empty" state is shown
                    withContext(Dispatchers.Main) {
                        if (mode == Mode.READING_ONLY) {
                            overlayView?.apply {
                                this.objects = emptyList()
                                this.texts = texts
                                postInvalidate()
                            }
                        }
                    }
                    
                    // Only process decision engine if we have text, otherwise it's quiet
                    if (texts.isNotEmpty()) {
                        decisionEngine.process(emptyList(), texts)
                    }
                    
                } catch (e: Exception) {
                    Log.e("CombinedAnalyzer", "OCR error", e)
                } finally {
                    isOCRing.set(false)
                }
            }
        }
    }
    
    private fun processRecognition() {
        val task = bitmapQueue.poll() ?: return
        val bitmap = task.bitmap
        
        if (memoryManager == null) return
        
        val now = System.currentTimeMillis()
        
        if (isRecognizing.compareAndSet(false, true)) {
            scope.launch(detectionDispatcher) {
                try {
                    if (mode != Mode.RECOGNITION_MODE) {
                        return@launch
                    }
                    
                    if (now - lastFaceRecognitionTime > faceRecognitionCooldown) {
                        val faces = faceDetector.detectFaces(bitmap)
                        if (faces.isNotEmpty() && mode == Mode.RECOGNITION_MODE) {
                            val bestFace = faces.first()
                            val result = memoryManager.recognizeFace(bestFace.bitmap)
                            
                            if (result != null) {
                                withContext(Dispatchers.Main) {
                                    ttsManager.speak("Hello ${result.label}, confidence ${result.confidencePercent()} percent")
                                }
                                lastFaceRecognitionTime = now
                            }
                        }
                    }
                    
                    if (now - lastPlaceRecognitionTime > placeRecognitionCooldown && mode == Mode.RECOGNITION_MODE) {
                        val result = memoryManager.recognizePlace(bitmap)
                        if (result != null) {
                            withContext(Dispatchers.Main) {
                                ttsManager.speak("You are at ${result.label}")
                            }
                            lastPlaceRecognitionTime = now
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CombinedAnalyzer", "Recognition error", e)
                } finally {
                    isRecognizing.set(false)
                }
            }
        }
    }
    
    private suspend fun tryRecognizeFaces(bitmap: Bitmap, @Suppress("UNUSED_PARAMETER") personDetections: List<DetectedObject>) {
        val now = System.currentTimeMillis()
        if (now - lastFaceRecognitionTime < faceRecognitionCooldown) return
        
        try {
            val faces = faceDetector.detectFaces(bitmap)
            if (faces.isNotEmpty()) {
                val bestFace = faces.first()
                val result = memoryManager?.recognizeFace(bestFace.bitmap)
                
                if (result != null) {
                    withContext(Dispatchers.Main) {
                        ttsManager.speak("I see ${result.label}")
                    }
                    lastFaceRecognitionTime = now
                }
            }
        } catch (e: Exception) {
            Log.e("CombinedAnalyzer", "Background face recognition error", e)
        }
    }
    
    fun getDecisionEngine(): DecisionEngine = decisionEngine
    
    fun cleanup() {
        scope.cancel()
        bitmapQueue.clear()
        faceDetector.shutdown()
        Log.d("CombinedAnalyzer", "Cleanup complete")
    }
    
    private data class BitmapTask(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int
    )
    
    data class ObjectWithDepth(
        val obj: DetectedObject,
        val depth: Float?,
        val position: String
    )
}