package com.dnotario.ondevicemsg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.media.AudioManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import kotlin.math.abs
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dnotario.ondevicemsg.ui.theme.OndevicemsgTheme
import com.dnotario.ondevicemsg.data.repository.SmsRepository
import com.dnotario.ondevicemsg.data.models.Conversation
import com.dnotario.ondevicemsg.services.ImageDescriptionService
import com.dnotario.ondevicemsg.ui.screens.MainScreen
import com.dnotario.ondevicemsg.utils.TextNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsInitialized by mutableStateOf(false)
    private lateinit var imageDescriptionService: ImageDescriptionService
    private var isListening by mutableStateOf(false)
    private var hasRecordPermission by mutableStateOf(false)
    private var hasSmsPermissions by mutableStateOf(false)
    private var hasContactsPermission by mutableStateOf(false)
    private var recognizerState by mutableStateOf("Idle")
    
    // Reply dialog state
    private var showReplyDialog by mutableStateOf(false)
    private var replyTranscription by mutableStateOf("")
    private var currentReplyConversation by mutableStateOf<Conversation?>(null)
    private var conversationRefreshTrigger by mutableStateOf(0)
    private var currentlyPlayingThreadId by mutableStateOf<Long?>(null)
    private var audioLevel by mutableStateOf(0f) // For sound visualization
    

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
        
        // Initialize Image Description Service
        imageDescriptionService = ImageDescriptionService(this)
        CoroutineScope(Dispatchers.IO).launch {
            imageDescriptionService.initialize()
        }

        // Initialize Speech Recognizer (default to on-device if available)
        initializeSpeechRecognizer()

        val smsRepository = SmsRepository(this)
        
        setContent {
            OndevicemsgTheme {
                MainScreen(
                    smsRepository = smsRepository,
                    onPlayMessage = ::playConversation,
                    onStopPlaying = ::stopPlaying,
                    onReplyToMessage = ::replyToConversation,
                    currentlyPlayingThreadId = currentlyPlayingThreadId,
                    isRecording = isListening,
                    recognizerState = recognizerState,
                    hasSmsPermissions = hasSmsPermissions,
                    showReplyDialog = showReplyDialog,
                    replyTranscription = replyTranscription,
                    currentReplyConversation = currentReplyConversation,
                    audioLevel = audioLevel,
                    onSendReply = ::sendReply,
                    onRetryReply = ::retryReply,
                    onDismissReply = ::dismissReplyDialog,
                    refreshTrigger = conversationRefreshTrigger
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
        
        // Try to use on-device recognizer for privacy and speed (API 31+)
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
        
        setupRecognitionListener()
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

            override fun onRmsChanged(rmsdB: Float) {
                // Convert RMS to a normalized value (0 to 1)
                // RMS typically ranges from -2 to 10 for speech
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                // Amplify for better visualization
                audioLevel = (normalized * 2f).coerceIn(0f, 1f)
                
                // Log RMS updates with timestamp
                val currentTime = System.currentTimeMillis()
                Log.d("ASR", "RMS update: rmsdB=$rmsdB, normalized=$normalized, audioLevel=$audioLevel, time=$currentTime")
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Try to get real waveform data from the buffer
                if (buffer != null && buffer.isNotEmpty()) {
                    try {
                        // Convert byte array to 16-bit PCM samples
                        val samples = ShortArray(buffer.size / 2)
                        for (i in samples.indices) {
                            // Combine two bytes into one 16-bit sample (little-endian)
                            val low = buffer[i * 2].toInt() and 0xFF
                            val high = buffer[i * 2 + 1].toInt() shl 8
                            samples[i] = (high or low).toShort()
                        }
                        
                        // Calculate peak amplitude for responsive visualization
                        var maxAmplitude = 0
                        for (sample in samples) {
                            val amplitude = kotlin.math.abs(sample.toInt())
                            if (amplitude > maxAmplitude) {
                                maxAmplitude = amplitude
                            }
                        }
                        
                        // Normalize to 0-1 range (16-bit audio max is 32768)
                        val normalized = (maxAmplitude / 32768f)
                        
                        // Amplify for better visualization
                        audioLevel = (normalized * 3f).coerceIn(0f, 1f)
                        
                        Log.d("ASR", "Buffer received: ${buffer.size} bytes, peak: $maxAmplitude, level: $audioLevel")
                    } catch (e: Exception) {
                        Log.e("ASR", "Error processing audio buffer", e)
                    }
                }
            }

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
                
                // For language unavailable error, provide guidance
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
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
                    // Update reply transcription with final results
                    replyTranscription = matches[0]
                    recognizerState = "Ready to send"
                    Log.d("ASR", "Final results: ${matches[0]}")
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
                    // Update reply transcription with partial results
                    replyTranscription = matches[0]
                    recognizerState = "Hearing..."
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun speakText(text: String) {
        if (ttsInitialized && text.isNotEmpty()) {
            // Apply text normalization for better TTS output
            val normalizedText = TextNormalizer.normalizeForTTS(text)
            tts.speak(normalizedText, TextToSpeech.QUEUE_FLUSH, null, "utterance_id")
            Log.d("TTS", "Speaking: $normalizedText")
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
            // Always use offline for privacy and speed
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
        // Note: We don't stop audio capture here because we want 
        // the visualization to continue while the dialog is open
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
        // If already playing this conversation, stop it
        if (currentlyPlayingThreadId == conversation.threadId) {
            stopPlaying()
            return
        }
        
        // Stop any current playback first
        tts.stop()
        currentlyPlayingThreadId = conversation.threadId
        
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
                for (message in messagesToPlay) {
                    // Check if we should stop
                    if (currentlyPlayingThreadId != conversation.threadId) {
                        break
                    }
                    
                    // Build the text to speak
                    var textToSpeak = ""
                    
                    // Handle image description if present
                    if (message.hasImage && message.imageUri != null) {
                        Log.d("MainActivity", "Message has image: ${message.imageUri}")
                        
                        // Announce the image and play processing sounds
                        val imageAnnouncement = if (message.isOutgoing) {
                            "You sent an image."
                        } else {
                            "$contactName sent an image."
                        }
                        speakText(imageAnnouncement)
                        
                        // Wait for announcement to finish
                        while (tts.isSpeaking && currentlyPlayingThreadId == conversation.threadId) {
                            Thread.sleep(100)
                        }
                        
                        try {
                            val imageUri = android.net.Uri.parse(message.imageUri)
                            
                            // Get the image description (this will wait for completion)
                            val description = withContext(Dispatchers.IO) {
                                imageDescriptionService.describeImage(imageUri)
                            }
                            
                            Log.d("MainActivity", "Image description result: $description")
                            
                            // Now speak the description
                            textToSpeak = if (!description.isNullOrEmpty()) {
                                "The image shows: $description"
                            } else {
                                "" // Don't say anything more if no description
                            }
                            
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error describing image", e)
                            textToSpeak = "Could not analyze the image."
                        }
                    } else {
                        Log.d("MainActivity", "Message does not have image. hasImage=${message.hasImage}, imageUri=${message.imageUri}")
                    }
                    
                    // Add message text
                    textToSpeak += if (message.isOutgoing) {
                        if (message.body.isNotEmpty()) "You said: ${message.body}" else ""
                    } else {
                        if (message.body.isNotEmpty()) "$contactName says: ${message.body}" else ""
                    }
                    
                    // Speak and wait for completion
                    if (textToSpeak.isNotEmpty()) {
                        speakText(textToSpeak)
                    }
                    
                    // Wait for TTS to finish speaking this message
                    while (tts.isSpeaking && currentlyPlayingThreadId == conversation.threadId) {
                        Thread.sleep(100)
                    }
                    
                    // Small delay between messages
                    if (currentlyPlayingThreadId == conversation.threadId) {
                        Thread.sleep(200)
                    }
                }
                
                // Mark messages as read after playing
                if (unreadMessages.isNotEmpty()) {
                    smsRepository.markMessagesAsRead(conversation.threadId)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error playing conversation", e)
            } finally {
                withContext(Dispatchers.Main) {
                    currentlyPlayingThreadId = null
                }
            }
        }
    }
    
    private fun stopPlaying() {
        tts.stop()
        currentlyPlayingThreadId = null
    }
    
    private fun replyToConversation(conversation: Conversation) {
        // Stop TTS if playing
        if (currentlyPlayingThreadId != null) {
            stopPlaying()
        }
        
        currentReplyConversation = conversation
        replyTranscription = ""
        recognizerState = "Initializing..."
        showReplyDialog = true
        
        // Don't use AudioRecord as it conflicts with SpeechRecognizer
        // We'll use RMS from speech recognizer instead
        
        // Start recording automatically when dialog opens
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(500) // Small delay for dialog to open
            startReplyRecording()
        }
    }
    
    private fun startReplyRecording() {
        if (!hasRecordPermission) {
            recognizerState = "No microphone permission"
            return
        }
        
        if (speechRecognizer == null) {
            recognizerState = "Recognizer not available"
            return
        }
        
        // Stop any TTS that might be playing
        tts.stop()
        
        replyTranscription = ""
        startListening()
    }
    
    private fun sendReply(messageText: String) {
        val conversation = currentReplyConversation ?: return
        val message = messageText.trim()
        
        if (message.isNotEmpty()) {
            // Stop recording if still active
            if (isListening) {
                stopListening()
            }
            
            // Send the SMS
            val smsRepository = SmsRepository(this)
            smsRepository.sendSms(conversation.address, message)
            
            // Speak confirmation
            speakText("Message sent to ${conversation.contactName ?: conversation.address}")
            
            // Close dialog
            showReplyDialog = false
            replyTranscription = ""
            currentReplyConversation = null
            recognizerState = "Idle"
            
            // Trigger conversation list refresh
            conversationRefreshTrigger++
            
            Log.d("MainActivity", "Sent SMS to ${conversation.address}: $message")
        }
    }
    
    private fun retryReply() {
        // Stop current recording if active
        if (isListening) {
            stopListening()
            // Small delay before restarting
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(200)
                replyTranscription = ""
                recognizerState = "Initializing..."
                startReplyRecording()
            }
        } else {
            // Clear transcription and start fresh
            replyTranscription = ""
            recognizerState = "Initializing..."
            startReplyRecording()
        }
    }
    
    private fun dismissReplyDialog() {
        // Stop recording if active
        if (isListening) {
            stopListening()
        }
        
        showReplyDialog = false
        replyTranscription = ""
        currentReplyConversation = null
        recognizerState = "Idle"
        audioLevel = 0f
    }
    

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        speechRecognizer?.destroy()
        if (::imageDescriptionService.isInitialized) {
            imageDescriptionService.close()
        }
    }
}