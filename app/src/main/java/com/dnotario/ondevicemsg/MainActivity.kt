package com.dnotario.ondevicemsg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dnotario.ondevicemsg.ui.theme.OndevicemsgTheme
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsInitialized by mutableStateOf(false)
    private var isListening by mutableStateOf(false)
    private var recognizedText by mutableStateOf("")
    private var hasRecordPermission by mutableStateOf(false)
    private var useOnlineRecognition by mutableStateOf(false) // Default to offline
    private var recognizerState by mutableStateOf("Idle")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasRecordPermission = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check for record permission
        hasRecordPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasRecordPermission) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US)
                ttsInitialized = true
                Log.d("TTS", "TextToSpeech initialized successfully")
            } else {
                Log.e("TTS", "TextToSpeech initialization failed")
            }
        }

        // Initialize Speech Recognizer (default to on-device if available)
        initializeSpeechRecognizer()

        setContent {
            OndevicemsgTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MessagingTestUI(
                        modifier = Modifier.padding(innerPadding),
                        onTtsClick = ::speakText,
                        onRecordClick = ::toggleRecording,
                        onClearClick = { 
                        recognizedText = ""
                        recognizerState = "Cleared"
                    },
                        onOnlineModeChanged = ::handleOnlineModeChange,
                        onOpenLanguageSettings = ::openLanguageSettings,
                        isRecording = isListening,
                        recognizedText = recognizedText,
                        ttsEnabled = ttsInitialized,
                        asrEnabled = hasRecordPermission && speechRecognizer != null,
                        useOnlineRecognition = useOnlineRecognition,
                        recognizerState = recognizerState
                    )
                }
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        // Destroy existing recognizer if any
        speechRecognizer?.destroy()
        speechRecognizer = null
        
        // Check if recognition is available at all
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("ASR", "Speech recognition not available on this device")
            recognizerState = "Recognition not available"
            return
        }
        
        if (useOnlineRecognition) {
            // Create standard recognizer (can go online)
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            Log.d("ASR", "Using online/offline speech recognition")
            recognizerState = "Online mode ready"
        } else {
            // Create on-device only recognizer (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                Log.d("ASR", "Using on-device only speech recognition")
                recognizerState = "On-device mode ready"
            } else {
                // Fallback to standard recognizer if on-device not available
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Log.w("ASR", "On-device requires API 31+, using standard recognition")
                    recognizerState = "Standard mode (API<31)"
                } else {
                    Log.w("ASR", "On-device not available, using standard recognition")
                    recognizerState = "Standard mode"
                }
            }
        }
        
        setupRecognitionListener()
    }
    
    private fun handleOnlineModeChange(enabled: Boolean) {
        // Stop any ongoing recognition
        if (isListening) {
            stopListening()
        }
        
        useOnlineRecognition = enabled
        initializeSpeechRecognizer()
    }

    private fun setupRecognitionListener() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("ASR", "Ready for speech")
                recognizerState = "Ready for speech"
            }

            override fun onBeginningOfSpeech() {
                Log.d("ASR", "Speech beginning")
                recognizerState = "Listening..."
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d("ASR", "Speech ended")
                recognizerState = "Processing..."
                // Don't set isListening to false here, let onResults or onError handle it
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable - Download offline language"
                    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Cannot check language support"
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
                    else -> "Error code: $error"
                }
                Log.e("ASR", "Recognition error code $error: $errorMessage")
                recognizerState = errorMessage
                
                // For language unavailable error with on-device mode, provide guidance
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && !useOnlineRecognition) {
                    Log.e("ASR", "Language unavailable for on-device recognition. " +
                            "User needs to download offline speech recognition data. " +
                            "Go to Settings > System > Languages & input > On-device speech recognition")
                }
                
                // Stop listening on any error
                isListening = false
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    recognizedText = if (recognizedText.isEmpty()) {
                        matches[0]
                    } else {
                        "$recognizedText ${matches[0]}"
                    }
                    Log.d("ASR", "Final results: ${matches[0]}")
                    recognizerState = "Recognized: \"${matches[0]}\""
                } else {
                    recognizerState = "No results"
                }
                
                // Speech recognition session completed naturally
                isListening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d("ASR", "Partial results: ${matches[0]}")
                    recognizerState = "Hearing: \"${matches[0]}\""
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun speakText(text: String) {
        if (ttsInitialized && text.isNotEmpty()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_id")
            Log.d("TTS", "Speaking: $text")
        }
    }

    private fun toggleRecording() {
        if (isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (!hasRecordPermission) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (speechRecognizer == null) {
            Log.e("ASR", "Speech recognizer not available")
            recognizerState = "Recognizer not available"
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Only use offline if explicitly set
            if (!useOnlineRecognition) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        speechRecognizer?.startListening(intent)
        isListening = true
        recognizerState = "Starting..."
        Log.d("ASR", "Started listening")
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        recognizerState = "Stopped"
        Log.d("ASR", "Stopped listening")
    }
    
    private fun openLanguageSettings() {
        try {
            // Try to open speech recognition settings directly
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
            Log.d("Settings", "Opening language settings")
        } catch (e: Exception) {
            // Fallback to general settings
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
            Log.e("Settings", "Could not open language settings directly", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        speechRecognizer?.destroy()
    }
}

@Composable
fun MessagingTestUI(
    modifier: Modifier = Modifier,
    onTtsClick: (String) -> Unit,
    onRecordClick: () -> Unit,
    onClearClick: () -> Unit,
    onOnlineModeChanged: (Boolean) -> Unit,
    onOpenLanguageSettings: () -> Unit,
    isRecording: Boolean,
    recognizedText: String,
    ttsEnabled: Boolean,
    asrEnabled: Boolean,
    useOnlineRecognition: Boolean,
    recognizerState: String
) {
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "On-Device TTS & ASR Test",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Text to Speech",
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("Enter text to speak") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                Button(
                    onClick = { onTtsClick(inputText) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enabled = ttsEnabled && inputText.isNotEmpty()
                ) {
                    Text("Speak Text")
                }

                if (!ttsEnabled) {
                    Text(
                        text = "TTS initializing...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Speech Recognition",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                recognizerState.startsWith("Error") -> MaterialTheme.colorScheme.errorContainer
                                recognizerState.startsWith("Recognized") -> MaterialTheme.colorScheme.primaryContainer
                                recognizerState.startsWith("Hearing") || recognizerState == "Listening..." -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Text(
                            text = recognizerState,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = when {
                                recognizerState.startsWith("Error") -> MaterialTheme.colorScheme.onErrorContainer
                                recognizerState.startsWith("Recognized") -> MaterialTheme.colorScheme.onPrimaryContainer
                                recognizerState.startsWith("Hearing") || recognizerState == "Listening..." -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = recognizedText,
                    onValueChange = { },
                    label = { Text("Recognized text will appear here") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    readOnly = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRecordClick,
                        enabled = asrEnabled,
                        colors = if (isRecording) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Text(if (isRecording) "Stop Recording" else "Start Recording")
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = useOnlineRecognition,
                            onCheckedChange = onOnlineModeChanged,
                            enabled = !isRecording
                        )
                        Text(
                            text = "Allow Online",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (!asrEnabled) {
                    Text(
                        text = "ASR not available or permission denied",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (isRecording) {
                    Text(
                        text = "🎤 Recording... (will stop on silence)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Text(
                    text = if (useOnlineRecognition) "Mode: Online/Offline" else "Mode: On-Device Only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Show button to download language if unavailable
                if (recognizerState.contains("Language unavailable") && !useOnlineRecognition) {
                    OutlinedButton(
                        onClick = onOpenLanguageSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download Offline Language Pack")
                    }
                    Text(
                        text = "Go to: Languages & input → On-device speech recognition → Download English",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Button(
            onClick = onClearClick,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = recognizedText.isNotEmpty()
        ) {
            Text("Clear Recognized Text")
        }
    }
}