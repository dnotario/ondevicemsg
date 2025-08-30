package com.dnotario.ondevicemsg

import android.Manifest
import android.content.pm.PackageManager
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
                // Get unread messages directly from the repository
                val unreadMessages = smsRepository.getUnreadMessagesForConversation(conversation.threadId)
                Log.d("MainActivity", "Found ${unreadMessages.size} unread messages for thread ${conversation.threadId}")
                
                val messagesToPlay = if (unreadMessages.isNotEmpty()) {
                    // Play all unread messages (oldest first)
                    Log.d("MainActivity", "Playing unread messages (oldest first)")
                    unreadMessages.reversed()
                } else {
                    // Get just the last message
                    Log.d("MainActivity", "No unread messages, getting last message")
                    val lastMessage = smsRepository.getLastMessageForConversation(conversation.threadId)
                    if (lastMessage != null) {
                        Log.d("MainActivity", "Playing last message: isRead=${lastMessage.isRead}")
                        listOf(lastMessage)
                    } else {
                        Log.d("MainActivity", "No messages found for thread")
                        emptyList()
                    }
                }
                
                val contactName = conversation.contactName ?: conversation.address
                for ((index, message) in messagesToPlay.withIndex()) {
                    Log.d("MainActivity", "Playing message ${index + 1}/${messagesToPlay.size}: hasImage=${message.hasImage}, hasText=${message.body.isNotEmpty()}")
                    
                    // Check if coroutine is still active
                    ensureActive()
                    
                    // Build the text to speak
                    var textToSpeak = ""
                    
                    // Handle image and text together for proper sequencing
                    if (message.hasImage && message.imageUri != null) {
                        Log.d("MainActivity", "Processing image message: ${message.imageUri}")
                        
                        // Announce the image is being processed
                        val imageAnnouncement = if (message.isOutgoing) {
                            "You sent an image. Analyzing..."
                        } else {
                            "Image. Analyzing..."
                        }
                        speakText(imageAnnouncement)
                        
                        // Wait for announcement to finish
                        Log.d("MainActivity", "Waiting for 'Analyzing...' to finish speaking")
                        while (tts.isSpeaking.value && isActive) {
                            delay(100)
                        }
                        Log.d("MainActivity", "'Analyzing...' finished, proceeding with image analysis")
                        
                        // Check if coroutine is still active before processing
                        ensureActive()
                        
                        try {
                            val imageUri = android.net.Uri.parse(message.imageUri)
                            Log.d("MainActivity", "Starting image analysis for URI: $imageUri")
                            
                            // Get the image description (this will wait for completion)
                            val description = withContext(Dispatchers.IO) {
                                imageAnalysis.describeImage(imageUri)
                            }
                            
                            Log.d("MainActivity", "Image analysis completed. Description: '$description' (null=${description == null}, empty=${description?.isEmpty()})")
                            
                            // Speak the image description first
                            if (!description.isNullOrEmpty()) {
                                speakText("The image shows: $description")
                                
                                // Wait for image description to finish
                                while (tts.isSpeaking.value && isActive) {
                                    delay(100)
                                }
                            } else {
                                // If no description available, still announce there was an image
                                speakText("Image received but could not be described")
                                
                                // Wait for announcement to finish
                                while (tts.isSpeaking.value && isActive) {
                                    delay(100)
                                }
                            }
                            
                            // Then speak the message text if present
                            if (message.body.isNotEmpty()) {
                                val messageText = if (message.isOutgoing) {
                                    "You said: ${message.body}"
                                } else {
                                    message.body
                                }
                                speakText(messageText)
                                
                                // Wait for message text to finish
                                while (tts.isSpeaking.value && isActive) {
                                    delay(100)
                                }
                            }
                            
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error describing image", e)
                            speakText("Could not analyze the image.")
                            
                            // Wait for error message to finish
                            while (tts.isSpeaking.value && isActive) {
                                delay(100)
                            }
                            
                            // Still speak the text message if image analysis failed
                            if (message.body.isNotEmpty()) {
                                val messageText = if (message.isOutgoing) {
                                    "You said: ${message.body}"
                                } else {
                                    message.body
                                }
                                speakText(messageText)
                                
                                // Wait for text to finish
                                while (tts.isSpeaking.value && isActive) {
                                    delay(100)
                                }
                            }
                        }
                    } else {
                        Log.d("MainActivity", "Message does not have image. hasImage=${message.hasImage}, imageUri=${message.imageUri}")
                        
                        // Handle text-only message
                        val messageText = if (message.isOutgoing) {
                            if (message.body.isNotEmpty()) "You said: ${message.body}" else ""
                        } else {
                            message.body
                        }
                        
                        // Speak message text if present
                        if (messageText.isNotEmpty()) {
                            speakText(messageText)
                            
                            // Wait for TTS to finish speaking this message
                            while (tts.isSpeaking.value && isActive) {
                                delay(100)
                            }
                        }
                    }
                    
                    // Small delay between messages (only if still active)
                    if (isActive && index < messagesToPlay.size - 1) {
                        Log.d("MainActivity", "Finished message ${index + 1}, pausing 200ms before next message")
                        delay(200)
                    }
                }
                Log.d("MainActivity", "Finished playing all ${messagesToPlay.size} messages")
                
                // Mark messages as read after playing
                Log.d("MainActivity", "Played ${messagesToPlay.size} messages, unread count: ${messagesToPlay.count { !it.isRead }}")
                if (messagesToPlay.any { !it.isRead }) {
                    Log.d("MainActivity", "Marking ${messagesToPlay.count { !it.isRead }} messages as read for thread ${conversation.threadId}")
                    smsRepository.markMessagesAsRead(conversation.threadId)
                    // Small delay to ensure database update completes
                    delay(100)
                } else {
                    Log.d("MainActivity", "All messages were already marked as read")
                }
                
                // Wait for TTS to actually finish before clearing the playing state
                while (tts.isSpeaking.value) {
                    delay(100)
                }
                
                // Clear the playing state after TTS completes
                withContext(Dispatchers.Main) {
                    viewModel.currentlyPlayingThreadId = null
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error playing conversation", e)
            } finally {
                // Ensure we clear the playing state even if there's an error
                withContext(Dispatchers.Main) {
                    if (viewModel.currentlyPlayingThreadId == conversation.threadId) {
                        viewModel.currentlyPlayingThreadId = null
                    }
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