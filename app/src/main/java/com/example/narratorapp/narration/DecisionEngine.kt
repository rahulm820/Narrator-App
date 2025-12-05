package com.example.narratorapp.narration

import android.util.Log
import com.example.narratorapp.camera.CombinedAnalyzer
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FIXED VERSION - Proper coordination with voice service
 * - Only speaks when voice is not listening for hotword
 * - Batches announcements to reduce interruptions
 * - Respects reading mode completely
 */
class DecisionEngine(private val ttsManager: TTSManager) {

    // ===== NEW: Voice coordination =====
    private val voiceIsListeningForHotword = AtomicBoolean(false)
    private var lastVoiceStateChange = 0L
    private val voiceStateDebounce = 1000L  // 1s grace period after voice state change
    
    // Throttling for announcements
    private var lastNarrationTime = 0L
    private val narrationCooldown = 3000L  // Increased from 2s to 3s
    
    private var lastObjectNarrationTime = 0L
    private val objectNarrationCooldown = 1000L  // Increased from 500ms
    
    private val seenObjects = mutableMapOf<String, Long>()
    private val objectMemoryDuration = 10000L  // Increased from 5s to 10s
    
    private val objectDetectionCount = mutableMapOf<String, Int>()
    private val requiredConsecutiveDetections = 3  // Increased from 2 to 3
    
    private var processCallCount = 0
    
    // Manual announcement mode
    private var manualAnnounceRequested = false
    
    // ===== CRITICAL DANGER OBJECTS (announce even during hotword listening) =====
    private val criticalDangerObjects = setOf(
        "car", "truck", "bus", "motorcycle", "bicycle"  // Only vehicles
    )
    
    // ===== SAFETY-CRITICAL OBJECTS (announce when voice not listening) =====
    private val dangerousObjects = setOf(
        // Vehicles (already in critical)
        "car", "truck", "bus", "motorcycle", "bicycle", "train",
        
        // Traffic infrastructure
        "traffic light", "stop sign",
        
        // Moving hazards
        "dog", "cat",
        
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

    // ===== NEW: Voice coordination API =====
    fun setVoiceListeningState(isListeningForHotword: Boolean) {
        val changed = voiceIsListeningForHotword.getAndSet(isListeningForHotword) != isListeningForHotword
        if (changed) {
            lastVoiceStateChange = System.currentTimeMillis()
            Log.i("DecisionEngine", "Voice state changed: hotword=$isListeningForHotword")
        }
    }
    
    private fun canAnnounce(): Boolean {
        val now = System.currentTimeMillis()
        
        // Always allow critical dangers
        // For everything else, check voice state with debounce
        val gracePeriodActive = (now - lastVoiceStateChange) < voiceStateDebounce
        
        return !voiceIsListeningForHotword.get() || gracePeriodActive
    }

    // ===== NEW: Request manual announcement (called by voice command) =====
    fun requestSceneDescription() {
        manualAnnounceRequested = true
        Log.i("DecisionEngine", "✓ Manual scene description requested")
    }

    fun processWithDepth(objectsWithDepth: List<CombinedAnalyzer.ObjectWithDepth>) {
        processCallCount++
        val now = System.currentTimeMillis()
        
        if (processCallCount % 30 == 0) {  // Reduced logging frequency
            Log.i("DecisionEngine", "=== FRAME #$processCallCount ===")
            Log.i("DecisionEngine", "Objects: ${objectsWithDepth.size}, Voice listening: ${voiceIsListeningForHotword.get()}")
        }
        
        if (objectsWithDepth.isEmpty()) {
            objectDetectionCount.clear()
            return
        }
        
        // ===== SPLIT: Critical vs Dangerous vs Informational =====
        val critical = objectsWithDepth.filter { isCriticalDanger(it.obj.label) && it.depth != null && it.depth < 1.5f }
        val dangerous = objectsWithDepth.filter { isDangerous(it.obj.label) && !isCriticalDanger(it.obj.label) }
        val informational = objectsWithDepth.filter { !isDangerous(it.obj.label) }
        
        // ===== ALWAYS announce critical dangers (even during hotword listening) =====
        if (critical.isNotEmpty()) {
            announceObjectsWithDepth(critical, now, forceAnnounce = true, priority = TTSManager.Priority.CRITICAL)
            return  // Don't process anything else
        }
        
        // ===== Check if we can announce (voice not listening for hotword) =====
        if (!canAnnounce()) {
            if (processCallCount % 30 == 0) {
                Log.d("DecisionEngine", "⏸️ Skipping announcements - voice is listening for hotword")
            }
            return
        }
        
        // ===== Announce dangerous objects =====
        if (dangerous.isNotEmpty()) {
            announceObjectsWithDepth(dangerous, now, forceAnnounce = false, priority = TTSManager.Priority.HIGH)
        }
        
        // ===== ONLY announce informational if user requested =====
        if (manualAnnounceRequested && informational.isNotEmpty()) {
            announceObjectsWithDepth(informational, now, forceAnnounce = true, priority = TTSManager.Priority.HIGH)
            manualAnnounceRequested = false  // Reset flag
        }
    }
    
    private fun isCriticalDanger(label: String): Boolean {
        return criticalDangerObjects.contains(label.lowercase())
    }
    
    private fun isDangerous(label: String): Boolean {
        return dangerousObjects.contains(label.lowercase())
    }
    
    fun process(objects: List<DetectedObject>, texts: List<OCRLine>) {
        processCallCount++
        val now = System.currentTimeMillis()
        
        // Priority 1: Announce text if present (reading mode)
        if (texts.isNotEmpty()) {
            announceText(texts.first())
            return
        }
        
        // ===== For objects, follow same logic as processWithDepth =====
        val critical = objects.filter { 
            isCriticalDanger(it.label) && it.confidence > 0.6f 
        }
        
        if (critical.isNotEmpty()) {
            announceObjects(critical, now, forceAnnounce = true, priority = TTSManager.Priority.CRITICAL)
            return
        }
        
        if (!canAnnounce()) {
            return
        }
        
        val dangerous = objects.filter { isDangerous(it.label) && !isCriticalDanger(it.label) }
        val informational = objects.filter { !isDangerous(it.label) }
        
        if (dangerous.isNotEmpty()) {
            announceObjects(dangerous, now, forceAnnounce = false, priority = TTSManager.Priority.HIGH)
        }
        
        if (manualAnnounceRequested && informational.isNotEmpty()) {
            announceObjects(informational, now, forceAnnounce = true, priority = TTSManager.Priority.HIGH)
            manualAnnounceRequested = false
        }
    }

    private var lastSpokenText = ""

    private fun announceText(text: OCRLine) {
        // ===== NEW: Check voice state for text reading too =====
        if (voiceIsListeningForHotword.get()) {
            Log.d("DecisionEngine", "⏸️ Skipping text - voice listening")
            return
        }
        
        val cleanText = text.text.trim()
        if (cleanText.length < 2) {
            Log.d("DecisionEngine", "Skipping single character: '$cleanText'")
            return
        }

        if (cleanText == lastSpokenText && (System.currentTimeMillis() - lastNarrationTime) < 2000) {
            return
        }

        val announcement = cleanText.take(50)
        ttsManager.speak(announcement, TTSManager.Priority.NORMAL)
    
        lastSpokenText = cleanText
        lastNarrationTime = System.currentTimeMillis()
        
        Log.i("DecisionEngine", "🔊 READING: $announcement")
    }
    
    private fun announceObjectsWithDepth(
        objectsWithDepth: List<CombinedAnalyzer.ObjectWithDepth>, 
        now: Long,
        forceAnnounce: Boolean = false,
        priority: TTSManager.Priority = TTSManager.Priority.NORMAL
    ) {
        // Clean up old memories
        seenObjects.entries.removeIf { now - it.value > objectMemoryDuration }
        
        // Update detection counts
        val currentLabels = objectsWithDepth.map { it.obj.label }.toSet()
        objectDetectionCount.keys.retainAll(currentLabels)
        
        for (data in objectsWithDepth.filter { it.obj.confidence > 0.2f }) {  // Raised threshold
            val oldCount = objectDetectionCount[data.obj.label] ?: 0
            objectDetectionCount[data.obj.label] = oldCount + 1
        }
        
        // Find objects ready to announce
        val confirmedObjects = objectsWithDepth
            .filter { it.obj.confidence > 0.2f }
            .filter { 
                val count = objectDetectionCount[it.obj.label] ?: 0
                count >= requiredConsecutiveDetections || forceAnnounce
            }
            .sortedByDescending { it.obj.confidence }
            .filter { data ->
                val lastSeen = seenObjects[data.obj.label]
                lastSeen == null || (now - lastSeen) > objectMemoryDuration || forceAnnounce
            }
        
        if (confirmedObjects.isEmpty()) return
        
        // ===== FOR MANUAL REQUEST: Announce top 3 objects =====
        if (forceAnnounce && manualAnnounceRequested) {
            val top3 = confirmedObjects.take(3)
            val announcement = buildSceneDescription(top3)
            ttsManager.speak(announcement, TTSManager.Priority.HIGH)
            
            top3.forEach { seenObjects[it.obj.label] = now }
            manualAnnounceRequested = false
            return
        }
        
        // ===== FOR DANGEROUS OBJECTS: Announce immediately =====
        val timeSinceLastAnnouncement = now - lastObjectNarrationTime
        
        if (timeSinceLastAnnouncement > objectNarrationCooldown || forceAnnounce || priority == TTSManager.Priority.CRITICAL) {
            val dataToAnnounce = confirmedObjects.first()
            Log.i("DecisionEngine", "✅ ANNOUNCING (${priority.name}): ${dataToAnnounce.obj.label}")
            announceObjectWithDepthAndPosition(dataToAnnounce, priority)
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
                
                append(" ${data.position}")
                
                if (data.depth != null && data.depth < 3.0f) {
                    append(" at ${String.format("%.1f", data.depth)} meters")
                }
            }
        }
    }
    
    private fun announceObjectWithDepthAndPosition(
        data: CombinedAnalyzer.ObjectWithDepth,
        priority: TTSManager.Priority
    ) {
        val obj = data.obj
        val depth = data.depth
        val position = data.position
        
        val urgency = if (priority == TTSManager.Priority.CRITICAL) {
            "Caution! "
        } else if (priority == TTSManager.Priority.HIGH && depth != null && depth < 2.0f) {
            "Warning: "
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
                obj.confidence > 0.50f -> append("${obj.label} ")
                obj.confidence > 0.30f -> append("${obj.label} detected ")
                else -> append("Possibly ${obj.label} ")
            }
            
            append(position)
            
            if (depthStr != null) {
                append(", $depthStr")
            }
        }
        
        Log.i("DecisionEngine", "🔊 ANNOUNCING: '$announcement' (priority: ${priority.name})")
        ttsManager.speak(announcement, priority)
    }
    
    private fun announceObjects(
        objects: List<DetectedObject>, 
        now: Long,
        forceAnnounce: Boolean = false,
        priority: TTSManager.Priority = TTSManager.Priority.NORMAL
    ) {
        seenObjects.entries.removeIf { now - it.value > objectMemoryDuration }
        
        val currentLabels = objects.map { it.label }.toSet()
        objectDetectionCount.keys.retainAll(currentLabels)
        
        for (obj in objects.filter { it.confidence > 0.2f }) {
            val oldCount = objectDetectionCount[obj.label] ?: 0
            objectDetectionCount[obj.label] = oldCount + 1
        }
        
        val confirmedObjects = objects
            .filter { it.confidence > 0.2f }
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
        
        if (timeSinceLastAnnouncement > objectNarrationCooldown || forceAnnounce || priority == TTSManager.Priority.CRITICAL) {
            val objToAnnounce = confirmedObjects.first()
            announceObjectSimple(objToAnnounce, priority)
            seenObjects[objToAnnounce.label] = now
            lastObjectNarrationTime = now
            lastNarrationTime = now
            
            objectDetectionCount[objToAnnounce.label] = 0
        }
    }
    
    private fun announceObjectSimple(obj: DetectedObject, priority: TTSManager.Priority) {
        val urgency = if (priority == TTSManager.Priority.CRITICAL) "Caution! " else ""
        
        val announcement = urgency + when {
            obj.confidence > 0.50f -> "${obj.label} detected"
            obj.confidence > 0.30f -> "${obj.label} ahead"
            else -> "Possibly a ${obj.label}"
        }
        
        Log.i("DecisionEngine", "🔊 ANNOUNCING: '$announcement' (priority: ${priority.name})")
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
        voiceIsListeningForHotword.set(false)
        Log.i("DecisionEngine", "Engine reset")
    }
}