package com.example.narratorapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.narratorapp.camera.CameraXManager
import com.example.narratorapp.camera.CombinedAnalyzer
import com.example.narratorapp.camera.OverlayView
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.memory.MemoryManager
import com.example.narratorapp.narration.TTSManager
import com.example.narratorapp.navigation.ARCoreManager
import com.example.narratorapp.navigation.NavigationEngine
import com.example.narratorapp.voice.VoiceCommand
import com.example.narratorapp.voice.VoiceCommandService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale


class MainActivity : ComponentActivity() {

    private lateinit var cameraXManager: CameraXManager
    private lateinit var ttsManager: TTSManager
    private lateinit var memoryManager: MemoryManager
    private lateinit var arCoreManager: ARCoreManager
    private lateinit var navigationEngine: NavigationEngine
    
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var voiceIndicator: ImageView
    private lateinit var statusText: TextView
    
    private var voiceInputRecognizer: SpeechRecognizer? = null
    private var voiceInputCallback: ((String) -> Unit)? = null
    private val voiceInputHandler = Handler(Looper.getMainLooper())

    private var voiceCommandService: VoiceCommandService? = null
    private var serviceBound = false

    // Dynamic property to safely access analyzer
    private val combinedAnalyzer: CombinedAnalyzer?
        get() = if (::cameraXManager.isInitialized) cameraXManager.getAnalyzer() else null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceCommandService.LocalBinder
            voiceCommandService = binder.getService()
            serviceBound = true
            
            voiceCommandService?.setCommandCallback { command ->
                handleVoiceCommand(command)
            }
            
            voiceCommandService?.setHotwordModeCallback { isHotwordMode ->
                combinedAnalyzer?.getDecisionEngine()?.setVoiceListeningState(isHotwordMode)
                
                runOnUiThread {
                    if (isHotwordMode) {
                        voiceIndicator.setImageResource(android.R.drawable.ic_menu_edit)
                        voiceIndicator.alpha = 0.5f 
                    } else {
                        voiceIndicator.setImageResource(android.R.drawable.ic_btn_speak_now)
                        voiceIndicator.alpha = 1.0f
                    }
                }
            }
            
            Log.d("MainActivity", "Voice command service connected")

            // Auto-start "How may I help you"
            Handler(Looper.getMainLooper()).postDelayed({
                if (serviceBound) {
                    voiceCommandService?.startListening(startWithHotword = false)
                    ttsManager.speak("How may I help you?", TTSManager.Priority.HIGH)
                    statusText.text = "How may I help you?"
                }
            }, 1000)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceCommandService = null
            serviceBound = false
            Log.d("MainActivity", "Voice command service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        initializeComponents()
        setupButtonListeners()
        requestPermissions()
    }

    private fun initializeViews() {
        previewView = findViewById(R.id.view_finder)
        overlayView = findViewById(R.id.overlayView)
        voiceIndicator = findViewById(R.id.voiceIndicator)
        statusText = findViewById(R.id.statusText)
    }

    private fun initializeComponents() {
        ttsManager = TTSManager(this)
        memoryManager = MemoryManager(this)
        
        arCoreManager = ARCoreManager(this)
        lifecycle.addObserver(arCoreManager)
        
        if (arCoreManager.initialize()) {
            navigationEngine = NavigationEngine(this, ttsManager, arCoreManager)
            statusText.text = "ARCore initialized"
        } else {
            statusText.text = "ARCore not available"
            Toast.makeText(this, "Navigation features disabled", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupButtonListeners() {
        findViewById<Button>(R.id.btnNormalMode)?.setOnClickListener {
            combinedAnalyzer?.mode = CombinedAnalyzer.Mode.OBJECT_AND_TEXT
            statusText.text = "Normal Mode"
            ttsManager.speak("Normal mode activated")
            Log.i("MainActivity", "Switched to NORMAL mode")
        }

        findViewById<Button>(R.id.btnReadingMode)?.setOnClickListener {
            combinedAnalyzer?.mode = CombinedAnalyzer.Mode.READING_ONLY
            statusText.text = "Reading Mode"
            ttsManager.speak("Reading mode activated")
            Log.i("MainActivity", "Switched to READING mode")
        }

        findViewById<Button>(R.id.btnRecognitionMode)?.setOnClickListener {
            combinedAnalyzer?.mode = CombinedAnalyzer.Mode.RECOGNITION_MODE
            statusText.text = "Recognition Mode"
            ttsManager.speak("Recognition mode activated")
            Log.i("MainActivity", "Switched to RECOGNITION mode")
        }

        findViewById<Button>(R.id.btnVoiceCommand)?.setOnClickListener {
            if (serviceBound && voiceCommandService?.isListening() == true) {
                voiceCommandService?.stopListening()
                statusText.text = "Voice paused"
            } else {
                voiceCommandService?.startListening(startWithHotword = false)
                statusText.text = "Listening...."
            }
        }

        findViewById<Button>(R.id.btnNavigation)?.setOnClickListener {
            showNavigationDialog()
        }

        findViewById<Button>(R.id.btnMemory)?.setOnClickListener {
            showMemoryDialog()
        }

        findViewById<Button>(R.id.btnTestTTS)?.setOnClickListener {
            Log.i("MainActivity", "Test TTS button clicked")
            statusText.text = "Testing TTS..."
            ttsManager.speak("Test. This is a test announcement. If you hear this, TTS is working.")
        }

        findViewById<Button>(R.id.btnAnnounceDetections)?.setOnClickListener {
            val currentObjects = overlayView.objects
            Log.i("MainActivity", "Announce detections clicked. Found ${currentObjects.size} objects")
            
            if (currentObjects.isEmpty()) {
                ttsManager.speak("No objects currently detected")
                statusText.text = "No objects detected"
            } else {
                combinedAnalyzer?.getDecisionEngine()?.requestSceneDescription()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            if (cameraGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            }

            if (audioGranted) {
                startVoiceCommandService()
            } else {
                Toast.makeText(this, "Microphone permission required for voice commands", Toast.LENGTH_LONG).show()
            }
        }

        permissionLauncher.launch(permissions)
    }

    private fun startCamera() {
        cameraXManager = CameraXManager(
            context = this,
            ttsManager = ttsManager,
            previewView = previewView,
            overlayView = overlayView,
            navigationEngine = if (::navigationEngine.isInitialized) navigationEngine else null,
            memoryManager = memoryManager
        )
        
        cameraXManager.start()
    }

    private fun startVoiceCommandService() {
        VoiceCommandService.start(this)
        val intent = Intent(this, VoiceCommandService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        voiceIndicator.visibility = ImageView.VISIBLE
    }

    private fun handleVoiceCommand(command: VoiceCommand) {
        Log.d("MainActivity", "Handling command: ${command.getDescription()}")
        runOnUiThread {
            statusText.text = command.getDescription()
        }

        when (command) {
            is VoiceCommand.StartNavigation -> {
                ttsManager.speak("Please select a destination first")
            }

            is VoiceCommand.StopNavigation -> {
                if (::navigationEngine.isInitialized) {
                    navigationEngine.stopNavigation()
                }
            }

            is VoiceCommand.RecordWaypoint -> {
                if (::navigationEngine.isInitialized) {
                    val waypoint = navigationEngine.recordWaypoint("Voice Waypoint")
                    if (waypoint != null) {
                        ttsManager.speak("Waypoint recorded")
                    } else {
                        ttsManager.speak("Failed to record waypoint. GPS or AR not ready.")
                    }
                } else {
                    ttsManager.speak("Navigation not ready")
                }
            }

            is VoiceCommand.GetLocation -> {
                val pose = arCoreManager.getCameraPose()
                if (pose != null) {
                    ttsManager.speak("Your coordinates are X ${pose.tx().toInt()}, Z ${pose.tz().toInt()}")
                } else {
                    ttsManager.speak("Location not available")
                }
            }

            is VoiceCommand.EnableReadingMode -> {
                combinedAnalyzer?.mode = CombinedAnalyzer.Mode.READING_ONLY
                ttsManager.speak("Reading mode enabled")
            }

            is VoiceCommand.DisableReadingMode -> {
                combinedAnalyzer?.mode = CombinedAnalyzer.Mode.OBJECT_AND_TEXT
                ttsManager.speak("Normal mode enabled")
            }

            is VoiceCommand.LearnFace -> {
                captureFaceForLearning(command.name)
            }

            is VoiceCommand.LearnFacePrompt -> {
                showLearnFaceDialog()
            }

            is VoiceCommand.LearnPlace -> {
                capturePlaceForLearning(command.name)
            }

            is VoiceCommand.LearnPlacePrompt -> {
                showLearnPlaceDialog()
            }

            is VoiceCommand.RecognizeFace -> {
                combinedAnalyzer?.mode = CombinedAnalyzer.Mode.RECOGNITION_MODE
                ttsManager.speak("Scanning for faces")
            }

            is VoiceCommand.RecognizePlace -> {
                ttsManager.speak("Analyzing location")
            }

            is VoiceCommand.DescribeScene -> {
                describeCurrentScene()
            }

            is VoiceCommand.FindObject -> {
                ttsManager.speak("What object would you like me to find?")
            }

            is VoiceCommand.IncreaseVolume -> {
                adjustVolume(increase = true)
            }

            is VoiceCommand.DecreaseVolume -> {
                adjustVolume(increase = false)
            }

            is VoiceCommand.Pause -> {
                voiceCommandService?.stopListening()
                ttsManager.speak("Paused")
            }

            is VoiceCommand.Resume -> {
                startVoiceCommandService()
                ttsManager.speak("Resumed")
            }

            is VoiceCommand.Help -> {
                announceAvailableCommands()
            }
        }
    }

    private fun showNavigationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Navigation")
            .setItems(arrayOf("Record Waypoint", "Start Navigation", "Stop Navigation")) { _, which ->
                when (which) {
                    0 -> {
                        // ===== FIXED: Added feedback/error handling =====
                        if (::navigationEngine.isInitialized) {
                            val waypoint = navigationEngine.recordWaypoint("Manual Waypoint")
                            if (waypoint != null) {
                                Toast.makeText(this, "Waypoint recorded", Toast.LENGTH_SHORT).show()
                                ttsManager.speak("Waypoint recorded")
                            } else {
                                Toast.makeText(this, "Failed: GPS or AR not tracking", Toast.LENGTH_LONG).show()
                                ttsManager.speak("Could not record. Check GPS or move camera.")
                            }
                        } else {
                            Toast.makeText(this, "Navigation Engine not initialized", Toast.LENGTH_LONG).show()
                            ttsManager.speak("Navigation not ready")
                        }
                    }
                    1 -> ttsManager.speak("Please create a route first")
                    2 -> {
                        if (::navigationEngine.isInitialized) {
                            navigationEngine.stopNavigation()
                        }
                    }
                }
            }
            .show()
    }

    private fun showMemoryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Memory")
            .setItems(arrayOf("Learn Face", "Learn Place", "View Memories")) { _, which ->
                when (which) {
                    0 -> showLearnFaceDialog()
                    1 -> showLearnPlaceDialog()
                    2 -> showMemoriesDialog()
                }
            }
            .show()
    }

    private fun showLearnFaceDialog() {
        ttsManager.speak("Please say the person's name now", TTSManager.Priority.HIGH)
        
        voiceInputHandler.postDelayed({
            // Face name: 2 seconds timeout (default)
            startVoiceCapture(silenceTimeout = 2000L) { spokenName ->
                val cleanName = spokenName.trim()
                
                if (cleanName.isEmpty()) {
                    ttsManager.speak("No name detected. Cancelled.")
                    return@startVoiceCapture
                }
                
                ttsManager.speak("I heard: $cleanName. Say confirm to save, retry to try again, or cancel")
                
                voiceInputHandler.postDelayed({
                    startVoiceCapture(silenceTimeout = 2000L) { response ->
                        when (response.lowercase().replace(" ", "")) {
                            "confirm" -> {
                                captureFaceForLearning(cleanName)
                            }
                            "retry" -> {
                                showLearnFaceDialog()
                            }
                            "cancel" -> {
                                ttsManager.speak("Cancelled")
                            }
                            else -> {
                                ttsManager.speak("I didn't understand. Cancelled.")
                            }
                        }
                    }
                }, 2500)
            }
        }, 2000)
    }

    private fun showLearnPlaceDialog() {
        ttsManager.speak("Please say the place name now", TTSManager.Priority.HIGH)
        
        voiceInputHandler.postDelayed({
            // ===== FIXED: Increased timeout to 5 seconds for places =====
            startVoiceCapture(silenceTimeout = 5000L) { spokenName ->
                val cleanName = spokenName.trim()
                
                if (cleanName.isEmpty()) {
                    ttsManager.speak("No name detected. Cancelled.")
                    return@startVoiceCapture
                }
                
                ttsManager.speak("I heard: $cleanName. Say confirm to save, retry to try again, or cancel")
                
                voiceInputHandler.postDelayed({
                    startVoiceCapture(silenceTimeout = 2000L) { response ->
                        when (response.lowercase().replace(" ", "")) {
                            "confirm" -> {
                                capturePlaceForLearning(cleanName)
                            }
                            "retry" -> {
                                showLearnPlaceDialog()
                            }
                            "cancel" -> {
                                ttsManager.speak("Cancelled")
                            }
                            else -> {
                                ttsManager.speak("I didn't understand. Cancelled.")
                            }
                        }
                    }
                }, 2500)
            }
        }, 2000)
    }

    // ===== FIXED: Added silenceTimeout parameter =====
    private fun startVoiceCapture(silenceTimeout: Long = 2000L, onResult: (String) -> Unit) {
        voiceCommandService?.stopListening()
        
        voiceInputCallback = onResult
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Use the timeout parameter here
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeout)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeout)
        }
        
        voiceInputRecognizer?.destroy()
        voiceInputRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        voiceInputRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull() ?: ""
                
                Log.i("VoiceInput", "Captured: '$spokenText'")
                
                voiceInputRecognizer?.destroy()
                voiceInputRecognizer = null
                
                voiceInputHandler.postDelayed({
                    voiceCommandService?.startListening(startWithHotword = true)
                }, 1000)
                
                voiceInputCallback?.invoke(spokenText)
                voiceInputCallback = null
            }
            
            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    else -> "Recognition error $error"
                }
                
                Log.e("VoiceInput", "Error: $errorMsg")
                
                voiceInputRecognizer?.destroy()
                voiceInputRecognizer = null
                
                voiceInputHandler.postDelayed({
                    voiceCommandService?.startListening(startWithHotword = true)
                }, 1000)
                
                voiceInputCallback?.invoke("")
                voiceInputCallback = null
            }
            
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("VoiceInput", "Ready for speech")
                runOnUiThread {
                    statusText.text = "Listening..."
                }
            }
            
            override fun onBeginningOfSpeech() {
                Log.d("VoiceInput", "Speech detected")
            }
            
            override fun onEndOfSpeech() {
                Log.d("VoiceInput", "Speech ended")
                runOnUiThread {
                    statusText.text = "Processing..."
                }
            }
            
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        
        try {
            voiceInputRecognizer?.startListening(intent)
            runOnUiThread {
                statusText.text = "Say the name..."
                voiceIndicator.visibility = ImageView.VISIBLE
            }
        } catch (e: Exception) {
            Log.e("VoiceInput", "Failed to start recognition", e)
            voiceInputCallback?.invoke("")
            voiceInputCallback = null
        }
    }

    private fun showMemoriesDialog() {
        lifecycleScope.launch {
            val faces = memoryManager.getAllFaces()
            val places = memoryManager.getAllPlaces()
            
            val items = mutableListOf<String>()
            items.add("--- Faces ---")
            items.addAll(faces)
            items.add("--- Places ---")
            items.addAll(places)
            
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Stored Memories")
                    .setItems(items.toTypedArray(), null)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun captureFaceForLearning(name: String) {
        lifecycleScope.launch {
            try {
                val bitmap = captureCurrentFrame()
                if (bitmap != null) {
                    val success = memoryManager.learnFace(bitmap, name)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            ttsManager.speak("I will remember $name")
                            Toast.makeText(this@MainActivity, "Learned face: $name", Toast.LENGTH_SHORT).show()
                        } else {
                            ttsManager.speak("Failed to learn face")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error learning face", e)
            }
        }
    }

    private fun capturePlaceForLearning(name: String) {
        lifecycleScope.launch {
            try {
                val bitmap = captureCurrentFrame()
                if (bitmap != null) {
                    val success = memoryManager.learnPlace(bitmap, name)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            ttsManager.speak("Location saved as $name")
                            Toast.makeText(this@MainActivity, "Learned place: $name", Toast.LENGTH_SHORT).show()
                        } else {
                            ttsManager.speak("Failed to learn location")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error learning place", e)
            }
        }
    }

    private fun describeCurrentScene() {
        combinedAnalyzer?.getDecisionEngine()?.requestSceneDescription()
    }

    private fun adjustVolume(increase: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun announceAvailableCommands() {
    val commands = """
        Here are the commands you can use:
        To navigate, say 'Start Navigation' or 'Where am I'.
        To read, say 'Read Text'.
        To recognize people, say 'Who is this'.
        To describe the room, say 'What do you see'.
        You can also say 'Stop' or 'Pause' at any time.
    """.trimIndent()
    ttsManager.speak(commands, TTSManager.Priority.HIGH)
    }

    private suspend fun captureCurrentFrame(): Bitmap? {
        return withContext(Dispatchers.Main) {
            try {
                previewView.bitmap
            } catch (e: Exception) {
                Log.e("MainActivity", "Error capturing frame", e)
                null
            }
        }
    }

    private fun getCurrentDetections(): List<DetectedObject> {
        return overlayView.objects
    }

    override fun onDestroy() {
        voiceInputRecognizer?.destroy()
        voiceInputRecognizer = null
        voiceInputHandler.removeCallbacksAndMessages(null)
        
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        
        VoiceCommandService.stop(this)
        
        if (::cameraXManager.isInitialized) {
            cameraXManager.shutdown()
        }
        
        ttsManager.shutdown()
        memoryManager.cleanup()
        
        if (::navigationEngine.isInitialized) {
            navigationEngine.cleanup()
        }
        
        arCoreManager.cleanup()
        super.onDestroy()
    }
}