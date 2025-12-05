package com.example.narratorapp.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import com.example.narratorapp.utils.ImageUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * FIXED VERSION - Correct preprocessing and color channels
 */
class ObjectDetector(context: Context) {

    // ===== TUNED THRESHOLDS =====
    private val confidenceThreshold = 0.25f  // ✅ Raised from 0.1 to reduce false positives
    private val iouThreshold = 0.5f
    private val inputSize = 320
    private val numThreads = 4
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    private val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
        "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )
    
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffer: Array<Array<FloatArray>>

    init {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "model.tflite")
            
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    try {
                        val delegateOptions = compatList.bestOptionsForThisDevice
                        gpuDelegate = GpuDelegate(delegateOptions)
                        addDelegate(gpuDelegate)
                        Log.i("ObjectDetector", "✓ GPU delegate enabled")
                    } catch (e: Exception) {
                        Log.w("ObjectDetector", "GPU delegate failed, using NNAPI", e)
                        setUseNNAPI(true)
                    }
                } else {
                    Log.i("ObjectDetector", "GPU not available, using NNAPI")
                    setUseNNAPI(true)
                }
                
                // setAllowFp16PrecisionForFp32(true)
            }
            
            interpreter = Interpreter(modelBuffer, options)
            
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            
            // val inputBufferSize = inputSize * inputSize * 3 * 4
            inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            
            val outputShape = outputTensor.shape()
            outputBuffer = Array(outputShape[0]) {
                Array(outputShape[1]) { FloatArray(outputShape[2]) }
            }
            
            Log.i("ObjectDetector", "=== MODEL INFO ===")
            Log.i("ObjectDetector", "Input: ${inputTensor.shape().contentToString()}")
            Log.i("ObjectDetector", "Output: ${outputShape.contentToString()}")
            Log.i("ObjectDetector", "Confidence threshold: $confidenceThreshold")
            
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error loading model", e)
        }
    }

    fun detect(bitmap: Bitmap): List<DetectedObject> {
        if (interpreter == null) return emptyList()

        var resizedBitmap: Bitmap? = null
        
        try {
            resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            inputBuffer.rewind()
            ImageUtils.bitmapToByteBuffer(bitmap, inputSize, inputBuffer)

            val startTime = SystemClock.uptimeMillis()
            interpreter!!.run(inputBuffer, outputBuffer)
            val inferenceTime = SystemClock.uptimeMillis() - startTime
            
            if (Math.random() < 0.1) {
                Log.i("ObjectDetector", "Inference: ${inferenceTime}ms")
            }

            val detections = decodeYOLO(outputBuffer[0], bitmap.width, bitmap.height)
            val finalDetections = nonMaxSuppression(detections)
            
            // ===== NEW: Log detections for debugging =====
            if (finalDetections.isNotEmpty()) {
                Log.d("ObjectDetector", "Detected ${finalDetections.size} objects:")
                finalDetections.take(5).forEach { obj ->
                    Log.d("ObjectDetector", "  - ${obj.label}: ${obj.confidencePercent()}")
                }
            }
            
            return finalDetections
        } finally {
            // Clean up scaled bitmap
            if (resizedBitmap != null && resizedBitmap != bitmap) {
                resizedBitmap.recycle()
            }
        }
    }

    // ===== CRITICAL FIX: Correct preprocessing =====
    private fun bitmapToByteBuffer(bitmap: Bitmap, buffer: ByteBuffer) {
        buffer.rewind()

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // ===== TRY OPTION 1 FIRST: Simple [0,1] normalization =====
        // Most YOLO models use this (YOLOv5, YOLOv8, etc.)
        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            
            // IMPORTANT: YOLO expects RGB order
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        
        /* ===== IF OPTION 1 DOESN'T WORK, TRY OPTION 2: ImageNet normalization =====
        // But with CORRECT channel assignments!
        val MEAN_R = 123.675f
        val MEAN_G = 116.28f
        val MEAN_B = 103.53f
        val STD_R = 58.395f
        val STD_G = 57.12f
        val STD_B = 57.375f

        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8) and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()

            // ✅ FIXED: Correct channel-to-std mapping
            buffer.putFloat((r - MEAN_R) / STD_R)  // R with R std
            buffer.putFloat((g - MEAN_G) / STD_G)  // G with G std
            buffer.putFloat((b - MEAN_B) / STD_B)  // B with B std
        }
        */
        
        /* ===== IF OPTION 2 DOESN'T WORK, TRY OPTION 3: [-1,1] normalization =====
        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            
            // Normalize to [-1, 1]
            buffer.putFloat((r - 0.5f) * 2.0f)
            buffer.putFloat((g - 0.5f) * 2.0f)
            buffer.putFloat((b - 0.5f) * 2.0f)
        }
        */
    }

    private fun decodeYOLO(output: Array<FloatArray>, originalWidth: Int, originalHeight: Int): MutableList<DetectedObject> {
        val results = mutableListOf<DetectedObject>()
        var totalPredictions = 0
        var passedObjectness = 0
        var passedConfidence = 0
        
        for (prediction in output) {
            totalPredictions++
            val objectness = prediction[4]
            
            if (objectness < confidenceThreshold) continue
            passedObjectness++
            
            // Find best class
            var maxClassScore = 0f
            var classId = -1
            for (i in 5 until prediction.size) {
                if (prediction[i] > maxClassScore) {
                    maxClassScore = prediction[i]
                    classId = i - 5
                }
            }
            
            val confidence = objectness * maxClassScore
            if (confidence < confidenceThreshold) continue
            passedConfidence++

            // YOLO outputs are normalized [0,1]
            val xCenter = prediction[0]
            val yCenter = prediction[1]
            val width = prediction[2]
            val height = prediction[3]
            
            // Convert to corners
            val xMin = xCenter - (width / 2f)
            val yMin = yCenter - (height / 2f)
            val xMax = xMin + width
            val yMax = yMin + height
            
            // Scale to image dimensions
            val left = (xMin * originalWidth).coerceIn(0f, originalWidth.toFloat())
            val top = (yMin * originalHeight).coerceIn(0f, originalHeight.toFloat())
            val right = (xMax * originalWidth).coerceIn(0f, originalWidth.toFloat())
            val bottom = (yMax * originalHeight).coerceIn(0f, originalHeight.toFloat())
            
            // Validate box
            if (right <= left || bottom <= top) continue

            results.add(
                DetectedObject(
                    label = labels.getOrElse(classId) { "Unknown" },
                    confidence = confidence,
                    boundingBox = RectF(left, top, right, bottom)
                )
            )
        }
        
        // ===== NEW: Debug logging =====
        if (totalPredictions > 0 && Math.random() < 0.1) {
            Log.d("ObjectDetector", "Decode stats: $totalPredictions predictions, " +
                  "$passedObjectness passed objectness, $passedConfidence passed confidence, " +
                  "${results.size} after NMS")
        }
        
        return results
    }

    private fun nonMaxSuppression(detections: List<DetectedObject>): List<DetectedObject> {
        val finalDetections = mutableListOf<DetectedObject>()
        val byClass = detections.groupBy { it.label }

        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.confidence }
            val selected = mutableListOf<DetectedObject>()

            for (detection in sorted) {
                var keep = true
                for (sel in selected) {
                    val iou = calculateIoU(detection.boundingBox, sel.boundingBox)
                    if (iou > iouThreshold) {
                        keep = false
                        break
                    }
                }
                if (keep) selected.add(detection)
            }
            finalDetections.addAll(selected)
        }
        return finalDetections
    }

    private fun calculateIoU(r1: RectF, r2: RectF): Float {
        val xA = max(r1.left, r2.left)
        val yA = max(r1.top, r2.top)
        val xB = min(r1.right, r2.right)
        val yB = min(r1.bottom, r2.bottom)

        val intersection = max(0f, xB - xA) * max(0f, yB - yA)
        val boxA = (r1.right - r1.left) * (r1.bottom - r1.top)
        val boxB = (r2.right - r2.left) * (r2.bottom - r2.top)
        val union = boxA + boxB - intersection

        return if (union > 0) intersection / union else 0f
    }
    
    fun cleanup() {
        gpuDelegate?.close()
        interpreter?.close()
        Log.d("ObjectDetector", "Cleaned up")
    }
}