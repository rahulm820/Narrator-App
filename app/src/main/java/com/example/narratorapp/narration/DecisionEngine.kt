package com.example.narratorapp.narration

import android.util.Log
import com.example.narratorapp.camera.CombinedAnalyzer
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine

/**
 * SMART VERSION - Only announces:
 * 1. Dangerous/safety-critical objects automatically
 * 2. Everything else ONLY when user asks via voice command
 */
class DecisionEngine(private val ttsManager: TTSManager) {

    // Throttling for announcements
    private var lastNarrationTime = 0L
    private val narrationCooldown = 2000L
    
    private var lastObjectNarrationTime = 0L
    private val objectNarrationCooldown = 500L
    
    private val seenObjects = mutableMapOf<String, Long>()
    private val objectMemoryDuration = 5000L  // Remember for 5 seconds
    
    private val objectDetectionCount = mutableMapOf<String, Int>()
    private val requiredConsecutiveDetections = 2  // Need 2 frames for safety
    
    private var processCallCount = 0
    
    // ===== NEW: Manual announcement mode =====
    private var manualAnnounceRequested = false
    
    // ===== SAFETY-CRITICAL OBJECTS (announce automatically) =====
    private val dangerousObjects = setOf(
        // Vehicles
        "car", "truck", "bus", "motorcycle", "bicycle", "train",
        
        // Traffic infrastructure
        "traffic light", "stop sign",
        
        // Moving hazards
        "dog","cat" , // People walking in path
        
        // Obstacles
        "fire hydrant", "parking meter"
    )
    
    // ===== INFORMATIONAL OBJECTS (announce only on request) =====
    private val informationalObjects = setOf(
        "chair", "couch", "bench", "table", "dining table",
        "book", "laptop", "tv", "clock", "vase",
        "bottle", "cup", "bowl", "fork", "knife", "spoon",
        "apple", "banana", "orange", "sandwich", "pizza",
        "backpack", "handbag", "suitcase", "umbrella",
        "potted plant", "person", "bird"
    )

    // ===== NEW: Request manual announcement (called by voice command) =====
    fun requestSceneDescription() {
        manualAnnounceRequested = true
        Log.i("DecisionEngine", "✓ Manual scene description requested")
    }

    fun processWithDepth(objectsWithDepth: List<CombinedAnalyzer.ObjectWithDepth>) {
        processCallCount++
        val now = System.currentTimeMillis()
        
        if (processCallCount % 10 == 0) {
            Log.i("DecisionEngine", "=== PROCESS CALL #$processCallCount ===")
            Log.i("DecisionEngine", "Objects with depth: ${objectsWithDepth.size}")
        }
        
        if (objectsWithDepth.isEmpty()) {
            objectDetectionCount.clear()
            return
        }
        
        // ===== SPLIT: Dangerous vs Informational =====
        val dangerous = objectsWithDepth.filter { isDangerous(it.obj.label) }
        val informational = objectsWithDepth.filter { !isDangerous(it.obj.label) }
        
        Log.i("DecisionEngine", "Dangerous: ${dangerous.size}, Info: ${informational.size}")
        
        // ===== ALWAYS announce dangerous objects =====
        if (dangerous.isNotEmpty()) {
            announceObjectsWithDepth(dangerous, now, forceAnnounce = true)
        }
        
        // ===== ONLY announce informational if user requested =====
        if (manualAnnounceRequested && informational.isNotEmpty()) {
            announceObjectsWithDepth(informational, now, forceAnnounce = true)
            manualAnnounceRequested = false  // Reset flag
        }
    }
    
    private fun isDangerous(label: String): Boolean {
        return dangerousObjects.contains(label.lowercase())
    }
    
    fun process(objects: List<DetectedObject>, texts: List<OCRLine>) {
        processCallCount++
        val now = System.currentTimeMillis()
        
        if (processCallCount % 10 == 0) {
            Log.i("DecisionEngine", "=== PROCESS CALL #$processCallCount ===")
            Log.i("DecisionEngine", "Objects: ${objects.size}, Texts: ${texts.size}")
        }
        
        // Priority 1: Announce text if present (reading mode)
        if (texts.isNotEmpty()) {
            announceText(texts.first())
            return
        }
        
        // Priority 2: Check for dangerous objects
        val dangerous = objects.filter { isDangerous(it.label) }
        val informational = objects.filter { !isDangerous(it.label) }
        
        // Always announce dangerous
        if (dangerous.isNotEmpty()) {
            announceObjects(dangerous, now, forceAnnounce = true)
        }
        
        // Only announce informational if requested
        if (manualAnnounceRequested && informational.isNotEmpty()) {
            announceObjects(informational, now, forceAnnounce = true)
            manualAnnounceRequested = false
        }
    }

    private var lastSpokenText = ""

    private fun announceText(text: OCRLine) {
        val cleanText = text.text.trim()
        if (cleanText.length < 2) {
            Log.d("DecisionEngine", "Skipping single character: '$cleanText'")
            return
        }

        if (cleanText == lastSpokenText && (System.currentTimeMillis() - lastNarrationTime) < 2000) {
            return
        }

        val announcement = cleanText.take(50)
        ttsManager.speak(announcement)
    
        lastSpokenText = cleanText
        lastNarrationTime = System.currentTimeMillis()
        
        Log.i("DecisionEngine", "🔊 READING: $announcement")
    }
    
    private fun announceObjectsWithDepth(
        objectsWithDepth: List<CombinedAnalyzer.ObjectWithDepth>, 
        now: Long,
        forceAnnounce: Boolean = false
    ) {
        // Clean up old memories
        seenObjects.entries.removeIf { now - it.value > objectMemoryDuration }
        
        // Update detection counts
        val currentLabels = objectsWithDepth.map { it.obj.label }.toSet()
        objectDetectionCount.keys.retainAll(currentLabels)
        
        for (data in objectsWithDepth.filter { it.obj.confidence > 0.15f }) {
            val oldCount = objectDetectionCount[data.obj.label] ?: 0
            objectDetectionCount[data.obj.label] = oldCount + 1
        }
        
        // Find objects ready to announce
        val confirmedObjects = objectsWithDepth
            .filter { it.obj.confidence > 0.15f }
            .filter { 
                val count = objectDetectionCount[it.obj.label] ?: 0
                count >= requiredConsecutiveDetections || forceAnnounce
            }
            .sortedByDescending { it.obj.confidence }
            .filter { data ->
                val lastSeen = seenObjects[data.obj.label]
                lastSeen == null || (now - lastSeen) > objectMemoryDuration || forceAnnounce
            }
        
        Log.i("DecisionEngine", "Confirmed objects: ${confirmedObjects.size}")
        
        if (confirmedObjects.isEmpty()) return
        
        // ===== FOR MANUAL REQUEST: Announce top 3 objects =====
        if (forceAnnounce && manualAnnounceRequested) {
            val top3 = confirmedObjects.take(3)
            val announcement = buildSceneDescription(top3)
            ttsManager.speak(announcement, TTSManager.Priority.HIGH)
            
            // Mark all as seen
            top3.forEach { seenObjects[it.obj.label] = now }
            manualAnnounceRequested = false
            return
        }
        
        // ===== FOR DANGEROUS OBJECTS: Announce immediately =====
        val timeSinceLastAnnouncement = now - lastObjectNarrationTime
        
        if (timeSinceLastAnnouncement > objectNarrationCooldown || forceAnnounce) {
            val dataToAnnounce = confirmedObjects.first()
            Log.i("DecisionEngine", "✅ ANNOUNCING: ${dataToAnnounce.obj.label}")
            announceObjectWithDepthAndPosition(dataToAnnounce, isDangerous(dataToAnnounce.obj.label))
            seenObjects[dataToAnnounce.obj.label] = now
            lastObjectNarrationTime = now
            lastNarrationTime = now
            
            objectDetectionCount[dataToAnnounce.obj.label] = 0
        }
    }
    
    private fun buildSceneDescription(objects: List<CombinedAnalyzer.ObjectWithDepth>): String {
        return buildString {
            append("I see ")
            objects.forEachIndexed { index, data ->
                if (index > 0 && index == objects.size - 1) append(" and ")
                else if (index > 0) append(", ")
                
                append("a ${data.obj.label}")
                
                // Add position
                append(" ${data.position}")
                
                // Add depth if available and close
                if (data.depth != null && data.depth < 3.0f) {
                    append(" at ${String.format("%.1f", data.depth)} meters")
                }
            }
        }
    }
    
    private fun announceObjectWithDepthAndPosition(
        data: CombinedAnalyzer.ObjectWithDepth,
        isDangerous: Boolean
    ) {
        val obj = data.obj
        val depth = data.depth
        val position = data.position
        
        // ===== URGENT for dangerous objects =====
        val urgency = if (isDangerous && depth != null && depth < 2.0f) {
            when {
                depth < 0.5f -> "Caution! "
                depth < 1.5f -> "Warning: "
                else -> ""
            }
        } else ""
        
        val depthStr = if (depth != null) {
            when {
                depth < 0.5f -> "very close"
                depth < 3.0f -> String.format("%.1f meters away", depth)
                else -> null
            }
        } else null
        
        val announcement = buildString {
            append(urgency)
            
            when {
                obj.confidence > 0.40f -> append("${obj.label} ")
                obj.confidence > 0.25f -> append("${obj.label} detected ")
                else -> append("Possibly ${obj.label} ")
            }
            
            append(position)
            
            if (depthStr != null) {
                append(", $depthStr")
            }
        }
        
        // ===== Use HIGH priority for dangerous objects =====
        val priority = if (isDangerous) TTSManager.Priority.HIGH else TTSManager.Priority.NORMAL
        
        Log.i("DecisionEngine", "🔊 ANNOUNCING: '$announcement' (priority: ${priority.name})")
        ttsManager.speak(announcement, priority)
    }
    
    private fun announceObjects(
        objects: List<DetectedObject>, 
        now: Long,
        forceAnnounce: Boolean = false
    ) {
        seenObjects.entries.removeIf { now - it.value > objectMemoryDuration }
        
        val currentLabels = objects.map { it.label }.toSet()
        objectDetectionCount.keys.retainAll(currentLabels)
        
        for (obj in objects.filter { it.confidence > 0.15f }) {
            val oldCount = objectDetectionCount[obj.label] ?: 0
            objectDetectionCount[obj.label] = oldCount + 1
        }
        
        val confirmedObjects = objects
            .filter { it.confidence > 0.15f }
            .filter { 
                val count = objectDetectionCount[it.label] ?: 0
                count >= requiredConsecutiveDetections || forceAnnounce
            }
            .sortedByDescending { it.confidence }
            .filter { obj ->
                val lastSeen = seenObjects[obj.label]
                lastSeen == null || (now - lastSeen) > objectMemoryDuration || forceAnnounce
            }
        
        if (confirmedObjects.isEmpty()) return
        
        // Manual request - announce multiple
        if (forceAnnounce && manualAnnounceRequested) {
            val top3 = confirmedObjects.take(3)
            val announcement = buildString {
                append("I see ")
                top3.forEachIndexed { index, obj ->
                    if (index > 0 && index == top3.size - 1) append(" and ")
                    else if (index > 0) append(", ")
                    append("a ${obj.label}")
                }
            }
            ttsManager.speak(announcement, TTSManager.Priority.HIGH)
            top3.forEach { seenObjects[it.label] = now }
            manualAnnounceRequested = false
            return
        }
        
        val timeSinceLastAnnouncement = now - lastObjectNarrationTime
        
        if (timeSinceLastAnnouncement > objectNarrationCooldown || forceAnnounce) {
            val objToAnnounce = confirmedObjects.first()
            announceObjectSimple(objToAnnounce, isDangerous(objToAnnounce.label))
            seenObjects[objToAnnounce.label] = now
            lastObjectNarrationTime = now
            lastNarrationTime = now
            
            objectDetectionCount[objToAnnounce.label] = 0
        }
    }
    
    private fun announceObjectSimple(obj: DetectedObject, isDangerous: Boolean) {
        val announcement = when {
            obj.confidence > 0.40f -> "${obj.label} detected"
            obj.confidence > 0.25f -> "${obj.label} ahead"
            else -> "Possibly a ${obj.label}"
        }
        
        val priority = if (isDangerous) TTSManager.Priority.HIGH else TTSManager.Priority.NORMAL
        
        Log.i("DecisionEngine", "🔊 ANNOUNCING: '$announcement' (no depth, priority: ${priority.name})")
        ttsManager.speak(announcement, priority)
    }
    
    fun describeScene(objects: List<DetectedObject>) {
        if (objects.isEmpty()) {
            ttsManager.speak("No objects detected")
            return
        }
        
        val topObjects = objects
            .sortedByDescending { it.confidence }
            .take(5)
            .map { it.label }
        
        val announcement = buildString {
            append("I see ")
            topObjects.forEachIndexed { index, label ->
                if (index > 0 && index == topObjects.size - 1) append(" and ")
                else if (index > 0) append(", ")
                append("a $label")
            }
        }
        
        ttsManager.speak(announcement, TTSManager.Priority.HIGH)
        Log.d("DecisionEngine", announcement)
    }
    
    fun reset() {
        seenObjects.clear()
        objectDetectionCount.clear()
        lastNarrationTime = 0L
        lastObjectNarrationTime = 0L
        manualAnnounceRequested = false
        Log.i("DecisionEngine", "Engine reset")
    }
}