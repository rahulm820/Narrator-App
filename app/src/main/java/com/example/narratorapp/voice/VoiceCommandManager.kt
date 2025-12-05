package com.example.narratorapp.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.narratorapp.narration.TTSManager
import java.util.*
import kotlin.math.min

/**
 * UPDATED VERSION - Includes Fuzzy Matching for robust command detection
 */
class VoiceCommandManager(
    private val context: Context,
    private val ttsManager: TTSManager
) {
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isHotwordMode = true
    private val handler = Handler(Looper.getMainLooper())
    
    private var onCommandRecognized: ((VoiceCommand) -> Unit)? = null
    private var onListeningStateChanged: ((Boolean) -> Unit)? = null
    private var onHotwordModeChanged: ((Boolean) -> Unit)? = null
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private val hotwords = listOf(
        "hey narrator", "ok narrator", "hello narrator", "narrator",
        "hay narrator", "hey navigator", "hie narrator"
    )
    
    private var isTTSSpeaking = false
    
    // Track consecutive failures to prevent infinite restart loops
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 5
    private var lastSuccessTime = System.currentTimeMillis()

    init {
        initializeSpeechRecognizer()
        
        ttsManager.setOnSpeakingStateListener { speaking ->
            isTTSSpeaking = speaking
            if (speaking && isListening) {
                Log.w("VoiceCommandManager", "⚠️ TTS started - pausing recognition")
                pauseRecognition()
            } else if (!speaking && !isListening) {
                Log.i("VoiceCommandManager", "✓ TTS finished - resuming recognition")
                handler.postDelayed({ resumeRecognition() }, 200)
            }
        }
    }
    
    private fun normalizeSpokenText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        
        // Lowercase, trim, collapse multiple spaces
        val lower = raw.lowercase(Locale.getDefault()).trim()
        val collapsed = lower.replace(Regex("\\s+"), " ")
        // Keep alphanumeric, spaces, apostrophes and dashes
        val cleaned = collapsed.replace(Regex("[^a-z0-9\\s''-]"), "")
        
        return cleaned
    }
    
    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("VoiceCommandManager", "Speech recognition not available")
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(recognitionListener)
        Log.d("VoiceCommandManager", "Speech recognizer initialized")
    }
    
    private fun isMicrophoneAvailable(): Boolean {
        val mode = audioManager.mode
        val isMusicActive = audioManager.isMusicActive
        
        if (mode != AudioManager.MODE_NORMAL) {
            Log.w("VoiceCommandManager", "Mic unavailable - audio mode: $mode")
            return false
        }
        
        if (isMusicActive) {
            Log.w("VoiceCommandManager", "Mic unavailable - music playing")
            return false
        }
        
        return true
    }
    
    fun startListening(startWithHotword: Boolean = true) {
        if (isListening) {
            Log.w("VoiceCommandManager", "Already listening")
            return
        }
        
        if (isTTSSpeaking) {
            Log.w("VoiceCommandManager", "Cannot start - TTS is speaking")
            handler.postDelayed({ startListening(startWithHotword) }, 500)
            return
        }
        
        if (!isMicrophoneAvailable()) {
            Log.w("VoiceCommandManager", "Cannot start - microphone busy")
            handler.postDelayed({ startListening(startWithHotword) }, 1000)
            return
        }
        
        isHotwordMode = startWithHotword
        Log.i("VoiceCommandManager", "Starting: ${if (startWithHotword) "HOTWORD" else "COMMAND"}")
        onHotwordModeChanged?.invoke(isHotwordMode)
        startRecognition()
    }
    
    fun stopListening() {
        isListening = false
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e("VoiceCommandManager", "Error canceling recognizer", e)
        }
        onListeningStateChanged?.invoke(false)
        onHotwordModeChanged?.invoke(false)
        Log.i("VoiceCommandManager", "Stopped listening")
    }
    
    private fun pauseRecognition() {
        if (isListening) {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e("VoiceCommandManager", "Error pausing", e)
            }
            isListening = false
            Log.d("VoiceCommandManager", "Recognition paused")
        }
    }
    
    private fun resumeRecognition() {
        if (isListening && !isTTSSpeaking) {
            startListening(isHotwordMode)
            Log.d("VoiceCommandManager", "Recognition resumed")
        }
    }
       
    private fun startRecognition() {
        if (isTTSSpeaking) {
            Log.w("VoiceCommandManager", "Skipping start - TTS active")
            return
        }
        
        // Safety check for loops
        if (consecutiveErrors >= maxConsecutiveErrors) {
            val timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessTime
            if (timeSinceLastSuccess < 30000) { // 10 seconds
                Log.e("VoiceCommandManager", "Too many errors, waiting before retry...")
                handler.postDelayed({
                    consecutiveErrors = 0
                    if (isListening) startRecognition()
                }, 5000)
                return
            } else {
                consecutiveErrors = 0 // Reset after timeout
            }
        }
        val silenceTimeout = if (isHotwordMode) {
            5000L  // 5 seconds for hotword (you need time to notice and speak)
        } else {
            3000L  // 3 seconds for commands (user is already engaged)
        }
        
        val possibleSilenceTimeout = if (isHotwordMode) {
            4000L  // 4 seconds before considering speech might be done
        } else {
            2000L  // 2 seconds for commands
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeout)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibleSilenceTimeout)
            if (isHotwordMode) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            }
        }
        
        
        isListening = true
        
        try {
            speechRecognizer?.startListening(intent)
            onListeningStateChanged?.invoke(true)
            onHotwordModeChanged?.invoke(isHotwordMode)
            
            val mode = if (isHotwordMode) "HOTWORD" else "COMMAND"
            Log.i("VoiceCommandManager", "🎤 Started ($mode)")
        } catch (e: Exception) {
            Log.e("VoiceCommandManager", "Failed to start recognition", e)
            isListening = false
            consecutiveErrors++
            handler.postDelayed({ startRecognition() }, 1000)
        }
    }
    
    private val recognitionListener = object : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            val mode = if (isHotwordMode) "hotword" else "command"
            Log.d("VoiceCommandManager", "✓ Ready ($mode)")
            consecutiveErrors = 0 
        }
        
        override fun onBeginningOfSpeech() {
            Log.d("VoiceCommandManager", "🗣️ Speech detected")
            lastSuccessTime = System.currentTimeMillis()
        }
        
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            Log.d("VoiceCommandManager", "Speech ended")
        }
        
        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "TIMEOUT"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO_ERROR"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT_ERROR"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK_ERROR"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                else -> "Error $error"
            }
            
            Log.w("VoiceDebug", "❌ $errorMessage")
            val isIgnorable = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

            if (!isIgnorable) {
                consecutiveErrors++
                Log.w("VoiceDebug", "Error: $error")
            }
            
            isListening = false // Ensure state is reset
            
            // FIX: Fast restart (100ms) for timeout/no match to keep listening
            // val delay = if (isIgnorable) 100L else 1000L
            
            if (!isTTSSpeaking) {
                handler.postDelayed({ startRecognition() }, 100L)
            }
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            processResults(matches)
        }
        private fun processResults(matches: ArrayList<String>?) {
        if (matches.isNullOrEmpty()) {
            if (!isTTSSpeaking) handler.postDelayed({ startRecognition() }, 100L)
            return
        }
        
        isListening = false // Will restart if needed
        lastSuccessTime = System.currentTimeMillis()
        consecutiveErrors = 0
        
        val normalized = normalizeSpokenText(matches.firstOrNull())
        Log.i("VoiceDebug", "Heard: '$normalized'")
        
        if (isHotwordMode) {
            val foundHotword = matches.any { hyp ->
            val h = normalizeSpokenText(hyp)
            // Check strict contains OR similarity score > 0.6
            hotwords.any { hw -> 
                h.contains(hw) || 
                h.contains(hw.replace(" ", "")) ||
                calculateSimilarity(h, hw) > 0.6 // <--- ALLOWS "Narratoe" / "Hay Narrator"
            }
            }
            
            if (foundHotword) {
                Log.i("VoiceCommandManager", "✓ HOTWORD")
                ttsManager.speak("Listening. Say 'Help' for commands.", TTSManager.Priority.HIGH)
                isHotwordMode = false
                onHotwordModeChanged?.invoke(false)
                
                // Wait for "Yes?" to finish before listening for command
                handler.postDelayed({ startRecognition() }, 2500 ) 
            } else {
                handler.postDelayed({ startRecognition() }, 100L)
            }
        } else {
            // Command Mode
            val command = parseCommand(normalized)
            if (command != null) {
                onCommandRecognized?.invoke(command)
                isHotwordMode = true
                onHotwordModeChanged?.invoke(true)
                handler.postDelayed({ startRecognition() }, 1000)
            } else {
                Log.w("VoiceCommandManager", "❌ Unknown command: '$normalized'")
                ttsManager.speak("I didn't understand. Say 'Help' to hear the options.", TTSManager.Priority.HIGH)
                isHotwordMode = true // Go back to hotword mode on failure? Or stay?
                // Let's go back to hotword to avoid frustration loop
                onHotwordModeChanged?.invoke(true)
                handler.postDelayed({ startRecognition() }, 3000)
            }
        }
    }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    /**
     * Uses Fuzzy Matching to find the best command
     */
    private fun parseCommand(spokenText: String): VoiceCommand? {
        // 1. Define all possible targets
        val targets = mapOf(
            VoiceCommand.StartNavigation to listOf("start navigation", "start nav", "begin navigation"),
            VoiceCommand.StopNavigation to listOf("stop navigation", "stop nav", "cancel navigation", "end navigation"),
            VoiceCommand.RecordWaypoint to listOf("record waypoint", "save waypoint", "mark location"),
            VoiceCommand.GetLocation to listOf("where am i", "my location", "current location", "what is my location"),
            VoiceCommand.EnableReadingMode to listOf("read text", "reading mode", "start reading", "enable reading mode"),
            VoiceCommand.DisableReadingMode to listOf("stop reading", "normal mode", "exit reading", "disable reading mode"),
            VoiceCommand.RecognizeFace to listOf("who is this", "identify face", "recognize face"),
            VoiceCommand.RecognizePlace to listOf("where is this", "identify place", "recognize place"),
            VoiceCommand.DescribeScene to listOf("what do you see", "describe scene", "what is in front", "whats in front"),
            VoiceCommand.FindObject to listOf("find object", "search for object"),
            VoiceCommand.IncreaseVolume to listOf("increase volume", "volume up", "louder"),
            VoiceCommand.DecreaseVolume to listOf("decrease volume", "volume down", "quieter"),
            VoiceCommand.Pause to listOf("pause", "pause listening"),
            VoiceCommand.Resume to listOf("resume", "resume listening"),
            VoiceCommand.Help to listOf("help", "help me", "commands", "what can you do")
        )

        var bestCommand: VoiceCommand? = null
        var bestScore = 0.0

        // 2. Iterate and score
        for ((command, phrases) in targets) {
            for (phrase in phrases) {
                val score = calculateSimilarity(spokenText, phrase)
                if (score > bestScore) {
                    bestScore = score
                    bestCommand = command
                }
            }
        }

        // 3. Special handling for dynamic commands (Learn Face/Place)
        if (spokenText.startsWith("learn face") || spokenText.startsWith("remember face")) {
            val name = spokenText.removePrefix("learn face").removePrefix("remember face").trim()
            return if (name.isNotEmpty()) VoiceCommand.LearnFace(name) else VoiceCommand.LearnFacePrompt
        }
        
        if (spokenText.startsWith("learn place") || spokenText.startsWith("remember place")) {
            val name = spokenText.removePrefix("learn place").removePrefix("remember place").trim()
            return if (name.isNotEmpty()) VoiceCommand.LearnPlace(name) else VoiceCommand.LearnPlacePrompt
        }

        Log.d("VoiceDebug", "Best match: ${bestCommand?.getDescription()} with score $bestScore")
        
        // 4. Threshold check (0.65 allows for "art nation" -> "start navigation")
        return if (bestScore > 0.65) bestCommand else null
    }

    /**
     * Calculates similarity between two strings (0.0 to 1.0)
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        
        val longerLength = longer.length
        if (longerLength == 0) return 1.0
        
        val editDistance = levenshteinDistance(longer, shorter)
        return (longerLength - editDistance) / longerLength.toDouble()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val costs = IntArray(s2.length + 1)
        for (i in 0..s1.length) {
            var lastValue = i
            for (j in 0..s2.length) {
                if (i == 0) {
                    costs[j] = j
                } else {
                    if (j > 0) {
                        var newValue = costs[j - 1]
                        if (s1[i - 1] != s2[j - 1]) {
                            newValue = min(min(newValue, lastValue), costs[j]) + 1
                        }
                        costs[j - 1] = lastValue
                        lastValue = newValue
                    }
                }
            }
            if (i > 0) costs[s2.length] = lastValue
        }
        return costs[s2.length]
    }
    
    fun setOnCommandRecognizedListener(listener: (VoiceCommand) -> Unit) {
        onCommandRecognized = listener
    }
    
    fun setOnListeningStateChangedListener(listener: (Boolean) -> Unit) {
        onListeningStateChanged = listener
    }
    
    fun setOnHotwordModeChangedListener(listener: (Boolean) -> Unit) {
        onHotwordModeChanged = listener
    }
    
    fun isCurrentlyListening() = isListening
    fun isInHotwordMode() = isHotwordMode
    
    fun cleanup() {
        stopListening()
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d("VoiceCommandManager", "Cleaned up")
    }
}