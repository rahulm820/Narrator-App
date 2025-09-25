package com.example.narrator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class ObjectDetectorHelper(
    context: Context,
    private val listener: DetectorListener?
) {

    // Post-processing parameters
    private val confidenceThreshold = 0.4f // Filter out detections with low confidence
    private val iouThreshold = 0.5f       // NMS threshold to remove overlapping boxes

    private var interpreter: Interpreter? = null
    private val inputSize = 640  // The input size of the YOLOv5 model
    private val numThreads = 4

    // A list of class labels. Make sure this matches your model's training data.
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

    init {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "model.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i("ObjectDetectorHelperRaw", "TFLite Interpreter loaded successfully.")
        } catch (e: Exception) {
            Log.e("ObjectDetectorHelperRaw", "Error loading TFLite model.", e)
            listener?.onError("Failed to load model: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap) {
        if (interpreter == null) {
            Log.e("ObjectDetectorHelperRaw", "Interpreter not initialized.")
            return
        }

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = bitmapToByteBuffer(resizedBitmap)

        // The output shape for YOLOv5s is [1, 25200, 85]
        // 25200 = total number of predictions
        // 85 = 4 (box coords) + 1 (confidence) + 80 (class scores)
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        val outputBuffer = Array(outputShape[0]) {
            Array(outputShape[1]) {
                FloatArray(outputShape[2])
            }
        }

        val startTime = SystemClock.uptimeMillis()
        interpreter!!.run(inputBuffer, outputBuffer)
        val inferenceTime = SystemClock.uptimeMillis() - startTime

        val detections = decodeYOLO(outputBuffer[0], bitmap.width, bitmap.height)
        val finalDetections = nonMaxSuppression(detections)

        listener?.onResults(finalDetections, inferenceTime, bitmap.height, bitmap.width)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // Allocate a buffer for the image data
        // 1 (batch size) * inputSize * inputSize * 3 (RGB channels) * 4 (bytes per float)
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixelValue in intValues) {
            // Normalize pixel values to [0, 1]
            buffer.putFloat(((pixelValue shr 16 and 0xFF) / 255.0f)) // Red
            buffer.putFloat(((pixelValue shr 8 and 0xFF) / 255.0f))  // Green
            buffer.putFloat(((pixelValue and 0xFF) / 255.0f))        // Blue
        }
        buffer.rewind()
        return buffer
    }

    private fun decodeYOLO(output: Array<FloatArray>, originalWidth: Int, originalHeight: Int): MutableList<DetectionResult> {
        val results = mutableListOf<DetectionResult>()

        for (prediction in output) {
            val confidence = prediction[4]
            if (confidence < confidenceThreshold) continue

            // Find the class with the highest score
            var maxClassScore = 0f
            var classId = -1
            for (i in 5 until prediction.size) {
                if (prediction[i] > maxClassScore) {
                    maxClassScore = prediction[i]
                    classId = i - 5 // Class index is offset by 5
                }
            }

            // Ensure the detection meets the confidence threshold for a specific class
            if (maxClassScore > confidenceThreshold) {
                // Decode bounding box coordinates and scale them to original image size
                val xCenter = prediction[0] * originalWidth
                val yCenter = prediction[1] * originalHeight
                val width = prediction[2] * originalWidth
                val height = prediction[3] * originalHeight

                val x = xCenter - (width / 2)
                val y = yCenter - (height / 2)

                results.add(
                    DetectionResult(
                        rect = RectF(x, y, x + width, y + height),
                        confidence = confidence, // Use the objectness score
                        classId = classId,
                        label = labels.getOrElse(classId) { "Unknown" }
                    )
                )
            }
        }
        return results
    }

    private fun nonMaxSuppression(detections: List<DetectionResult>): MutableList<DetectionResult> {
        val finalDetections = mutableListOf<DetectionResult>()

        // Group detections by class ID
        val detectionsByClass = detections.groupBy { it.classId }

        for ((_, group) in detectionsByClass) {
            // Sort detections in the group by confidence in descending order
            val sortedDetections = group.sortedByDescending { it.confidence }
            val selected = mutableListOf<DetectionResult>()

            for (detection in sortedDetections) {
                var shouldAdd = true
                for (selectedDetection in selected) {
                    val iou = calculateIoU(detection.rect, selectedDetection.rect)
                    if (iou > iouThreshold) {
                        shouldAdd = false
                        break
                    }
                }
                if (shouldAdd) {
                    selected.add(detection)
                }
            }
            finalDetections.addAll(selected)
        }
        return finalDetections
    }

    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val xA = max(rect1.left, rect2.left)
        val yA = max(rect1.top, rect2.top)
        val xB = min(rect1.right, rect2.right)
        val yB = min(rect1.bottom, rect2.bottom)

        val intersectionArea = max(0f, xB - xA) * max(0f, yB - yA)
        val boxAArea = (rect1.right - rect1.left) * (rect1.bottom - rect1.top)
        val boxBArea = (rect2.right - rect2.left) * (rect2.bottom - rect2.top)
        val unionArea = boxAArea + boxBArea - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    // Listener for detection results
    interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            results: MutableList<DetectionResult>,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }

    // Data class to hold detection results
    data class DetectionResult(
        val rect: RectF,
        val confidence: Float,
        val classId: Int,
        val label: String
    )
}