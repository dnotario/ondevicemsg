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
import com.dnotario.ondevicemsg.data.repository.SmsRepository
import com.dnotario.ondevicemsg.data.models.Conversation
import com.dnotario.ondevicemsg.ui.screens.MainScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsInitialized by mutableStateOf(false)
    private var isListening by mutableStateOf(false)
    private var recognizedText by mutableStateOf("")
    private var hasRecordPermission by mutableStateOf(false)
    private var hasSmsPermissions by mutableStateOf(false)
    private var hasContactsPermission by mutableStateOf(false)
    private var useOnlineRecognition by mutableStateOf(false) // Default to offline
    private var recognizerState by mutableStateOf("Idle")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasRecordPermission = isGranted
    }
    
    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasSmsPermissions = permissions[Manifest.permission.READ_SMS] == true &&
                permissions[Manifest.permission.SEND_SMS] == true
        hasContactsPermission = permissions[Manifest.permission.READ_CONTACTS] == true
        
        Log.d("Permissions", "SMS permissions: $hasSmsPermissions, Contacts: $hasContactsPermission")
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
        
        // Check for SMS and Contacts permissions
        checkAndRequestSmsPermissions()

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

        val smsRepository = SmsRepository(this)
        
        setContent {
            OndevicemsgTheme {
                MainScreen(
                    smsRepository = smsRepository,
                    onTtsClick = ::speakText,
                    onRecordClick = ::toggleRecording,
                    onClearClick = { 
                        recognizedText = ""
                        recognizerState = "Cleared"
                    },
                    onOnlineModeChanged = ::handleOnlineModeChange,
                    onOpenLanguageSettings = ::openLanguageSettings,
                    onPlayMessage = ::playConversation,
                    onReplyToMessage = ::replyToConversation,
                    isRecording = isListening,
                    recognizedText = recognizedText,
                    ttsEnabled = ttsInitialized,
                    asrEnabled = hasRecordPermission && speechRecognizer != null,
                    useOnlineRecognition = useOnlineRecognition,
                    recognizerState = recognizerState,
                    hasSmsPermissions = hasSmsPermissions
                )
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
    
    private fun checkAndRequestSmsPermissions() {
        val smsPermissionsGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        
        val contactsPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        
        hasSmsPermissions = smsPermissionsGranted
        hasContactsPermission = contactsPermissionGranted
        
        val permissionsToRequest = mutableListOf<String>()
        if (!smsPermissionsGranted) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }
        if (!contactsPermissionGranted) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun playConversation(conversation: Conversation) {
        val smsRepository = SmsRepository(this)
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val messages = smsRepository.getMessagesForConversation(conversation.threadId)
                val unreadMessages = messages.filter { !it.isRead && !it.isOutgoing }
                
                val messagesToPlay = if (unreadMessages.isNotEmpty()) {
                    // Play all unread messages
                    unreadMessages.reversed() // Oldest first
                } else {
                    // Play just the last message
                    messages.take(1)
                }
                
                val contactName = conversation.contactName ?: conversation.address
                messagesToPlay.forEach { message ->
                    val textToSpeak = "$contactName says: ${message.body}"
                    speakText(textToSpeak)
                    Thread.sleep(100) // Small delay between messages
                }
                
                // Mark messages as read after playing
                if (unreadMessages.isNotEmpty()) {
                    smsRepository.markMessagesAsRead(conversation.threadId)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error playing conversation", e)
            }
        }
    }
    
    private fun replyToConversation(conversation: Conversation) {
        // TODO: Will implement voice reply dialog in next phase
        Log.d("MainActivity", "Reply to ${conversation.address}")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        speechRecognizer?.destroy()
    }
}