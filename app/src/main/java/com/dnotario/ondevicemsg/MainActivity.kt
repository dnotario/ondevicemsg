package com.dnotario.ondevicemsg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dnotario.ondevicemsg.ui.theme.OndevicemsgTheme
import com.dnotario.ondevicemsg.data.repository.SmsRepository
import com.dnotario.ondevicemsg.data.models.Conversation
import com.dnotario.ondevicemsg.ui.screens.MainScreen
import com.dnotario.ondevicemsg.odm.TextToSpeech
import com.dnotario.ondevicemsg.odm.SpeechRecognition
import com.dnotario.ondevicemsg.odm.ImageAnalysis
import com.dnotario.ondevicemsg.odm.RecognitionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import java.util.*

class MainActivity : ComponentActivity() {
    // ViewModel for preserving state and services across configuration changes
    private val viewModel: MainViewModel by viewModels()
    
    // Services are now in ViewModel
    private val tts: TextToSpeech
        get() = viewModel.tts
    private val speechRecognition: SpeechRecognition
        get() = viewModel.speechRecognition
    private val imageAnalysis: ImageAnalysis
        get() = viewModel.imageAnalysis
    

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        viewModel.hasRecordPermission = isGranted
    }
    
    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.hasSmsPermissions = permissions[Manifest.permission.READ_SMS] == true &&
                permissions[Manifest.permission.SEND_SMS] == true
        viewModel.hasContactsPermission = permissions[Manifest.permission.READ_CONTACTS] == true
        
        Log.d("Permissions", "SMS permissions: ${viewModel.hasSmsPermissions}, Contacts: ${viewModel.hasContactsPermission}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check for record permission
        viewModel.hasRecordPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!viewModel.hasRecordPermission) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        
        // Check for SMS and Contacts permissions
        checkAndRequestSmsPermissions()

        // Services are initialized in ViewModel, no need to initialize here
        
        // Observe speech recognition states
        lifecycleScope.launch {
            speechRecognition.state.collect { state ->
                viewModel.recognizerState = when (state) {
                    is RecognitionState.Idle -> "Idle"
                    is RecognitionState.Listening -> "Listening"
                    is RecognitionState.Processing -> "Processing..."
                    is RecognitionState.Error -> state.message
                }
            }
        }
        
        lifecycleScope.launch {
            speechRecognition.isListening.collect { listening ->
                viewModel.isListening = listening
            }
        }

        val smsRepository = SmsRepository(this)
        
        setContent {
            OndevicemsgTheme {
                MainScreen(
                    smsRepository = smsRepository,
                    onPlayMessage = ::playConversation,
                    onStopPlaying = ::stopPlaying,
                    onReplyToMessage = ::replyToConversation,
                    currentlyPlayingThreadId = viewModel.currentlyPlayingThreadId,
                    isRecording = viewModel.isListening,
                    recognizerState = viewModel.recognizerState,
                    hasSmsPermissions = viewModel.hasSmsPermissions,
                    showReplyDialog = viewModel.showReplyDialog,
                    replyTranscription = viewModel.replyTranscription,
                    currentReplyConversation = viewModel.currentReplyConversation,
                    onSendReply = ::sendReply,
                    onRetryReply = ::retryReply,
                    onDismissReply = ::dismissReplyDialog,
                    refreshTrigger = viewModel.conversationRefreshTrigger
                )
            }
        }
    }

    // Speech recognizer initialization removed - handled by ODM SpeechRecognition class
    

    private fun speakText(text: String) {
        tts.speak(text, preprocessText = true)
    }


    private fun startListening() {
        if (!viewModel.hasRecordPermission) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        speechRecognition.startListening(
            onResult = { finalText ->
                viewModel.replyTranscription = finalText
                Log.d("MainActivity", "Final transcription: $finalText")
            },
            onPartialResult = { partialText ->
                viewModel.replyTranscription = partialText
                Log.d("MainActivity", "Partial transcription: $partialText")
            },
            onError = { error ->
                Log.e("MainActivity", "Recognition error: $error")
            }
        )
    }

    private fun stopListening() {
        speechRecognition.stopListening()
        Log.d("MainActivity", "Stopped listening")
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
        
        viewModel.hasSmsPermissions = smsPermissionsGranted
        viewModel.hasContactsPermission = contactsPermissionGranted
        
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
        // Stop any current playback first
        stopPlaying()
        viewModel.currentlyPlayingThreadId = conversation.threadId

        val smsRepository = SmsRepository(this)
        
        // Launch and store the job
        viewModel.currentPlaybackJob = CoroutineScope(Dispatchers.IO).launch {
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
                    // Check if coroutine is still active
                    ensureActive()
                    
                    // Build the text to speak
                    var textToSpeak = ""
                    
                    // Handle image description if present
                    if (message.hasImage && message.imageUri != null) {
                        Log.d("MainActivity", "Message has image: ${message.imageUri}")
                        
                        // Announce the image is being processed
                        val imageAnnouncement = if (message.isOutgoing) {
                            "You sent an image. Analyzing..."
                        } else {
                            "$contactName sent an image. Analyzing..."
                        }
                        speakText(imageAnnouncement)
                        
                        // Check if coroutine is still active before processing
                        ensureActive()
                        
                        try {
                            val imageUri = android.net.Uri.parse(message.imageUri)
                            
                            // Get the image description (this will wait for completion)
                            val description = withContext(Dispatchers.IO) {
                                imageAnalysis.describeImage(imageUri)
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
                    while (tts.isSpeaking.value && isActive) {
                        delay(100) // Use coroutine delay instead of Thread.sleep
                    }
                    
                    // Small delay between messages (only if still active)
                    if (isActive) {
                        delay(200)
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
                    viewModel.currentlyPlayingThreadId = null
                }
            }
        }
    }
    
    private fun stopPlaying() {
        viewModel.stopPlayback()
    }
    
    private fun replyToConversation(conversation: Conversation) {
        viewModel.currentReplyConversation = conversation
        viewModel.replyTranscription = ""
        viewModel.recognizerState = "Initializing..."
        viewModel.showReplyDialog = true
        
        // Start recording automatically when dialog opens
        CoroutineScope(Dispatchers.Main).launch {
            startReplyRecording()
        }
    }
    
    private fun startReplyRecording() {
        if (!viewModel.hasRecordPermission) {
            viewModel.recognizerState = "No microphone permission"
            return
        }
        
        // Stop any TTS that might be playing
        tts.stop()
        
        viewModel.replyTranscription = ""
        startListening()
    }
    
    private fun sendReply(messageText: String) {
        val conversation = viewModel.currentReplyConversation ?: return
        val message = messageText.trim()
        
        if (message.isNotEmpty()) {
            // Stop recording if still active
            if (viewModel.isListening) {
                stopListening()
            }
            
            // Send the SMS
            val smsRepository = SmsRepository(this)
            smsRepository.sendSms(conversation.address, message)
            
            // Speak confirmation
            speakText("Message sent to ${conversation.contactName ?: conversation.address}")
            
            // Close dialog
            viewModel.showReplyDialog = false
            viewModel.replyTranscription = ""
            viewModel.currentReplyConversation = null
            viewModel.recognizerState = "Idle"
            
            // Trigger conversation list refresh
            viewModel.conversationRefreshTrigger++
            
            Log.d("MainActivity", "Sent SMS to ${conversation.address}: $message")
        }
    }
    
    private fun retryReply() {
        // Stop current recording if active
        if (viewModel.isListening) {
            stopListening()
            // Small delay before restarting
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(200)
                viewModel.replyTranscription = ""
                viewModel.recognizerState = "Initializing..."
                startReplyRecording()
            }
        } else {
            // Clear transcription and start fresh
            viewModel.replyTranscription = ""
            viewModel.recognizerState = "Initializing..."
            startReplyRecording()
        }
    }
    
    private fun dismissReplyDialog() {
        // Stop recording if active
        if (viewModel.isListening) {
            stopListening()
        }
        
        viewModel.resetReplyDialog()
    }
    

    override fun onDestroy() {
        super.onDestroy()
        // Services are cleaned up in ViewModel.onCleared()
    }
}