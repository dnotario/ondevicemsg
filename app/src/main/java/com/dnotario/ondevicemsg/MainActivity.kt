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
import com.dnotario.ondevicemsg.data.repository.ContactsRepository
import com.dnotario.ondevicemsg.data.models.Conversation
import com.dnotario.ondevicemsg.ui.screens.MainScreen
import com.dnotario.ondevicemsg.ui.components.ComposeDialog
import com.dnotario.ondevicemsg.utils.FuzzyMatcher
import com.dnotario.ondevicemsg.odm.TextToSpeech
import com.dnotario.ondevicemsg.odm.SpeechRecognition
import com.dnotario.ondevicemsg.odm.ImageAnalysis
import com.dnotario.ondevicemsg.odm.SmartReplyGenerator
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
    private val smartReplyGenerator: SmartReplyGenerator
        get() = viewModel.smartReplyGenerator
    

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
        val contactsRepository = ContactsRepository(this)
        
        setContent {
            OndevicemsgTheme {
                // Show compose dialog if needed
                if (viewModel.showComposeDialog) {
                    ComposeDialog(
                        isListening = viewModel.composeIsListening,
                        recognizerState = viewModel.composeRecognizerState,
                        transcription = viewModel.composeTranscription,
                        matchedContacts = viewModel.composeMatchedContacts,
                        onDismiss = { dismissComposeDialog() },
                        onTextChange = { text ->
                            onComposeTextChange(text, contactsRepository)
                        },
                        onContactSelect = { contact ->
                            onComposeContactSelect(contact)
                        },
                        onRetry = {
                            retryComposeListening(contactsRepository)
                        },
                        onStopListening = {
                            stopComposeListening()
                        }
                    )
                }
                
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
                    smartReplies = viewModel.smartReplies,
                    isLoadingSmartReplies = viewModel.isLoadingSmartReplies,
                    onSendReply = ::sendReply,
                    onRetryReply = ::retryReply,
                    onDismissReply = ::dismissReplyDialog,
                    refreshTrigger = viewModel.conversationRefreshTrigger,
                    onCompose = { showComposeDialog() }
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
                                speakText(description)
                                
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
        viewModel.smartReplies = emptyList()
        viewModel.isLoadingSmartReplies = true
        
        // Generate smart replies based on conversation history
        CoroutineScope(Dispatchers.IO).launch {
            generateSmartReplies(conversation)
        }
        
        // Start recording automatically when dialog opens
        CoroutineScope(Dispatchers.Main).launch {
            startReplyRecording()
        }
    }
    
    private suspend fun generateSmartReplies(conversation: Conversation) {
        try {
            Log.d("MainActivity", "Generating smart replies for conversation with ${conversation.contactName ?: conversation.address}")
            
            // Get recent messages from the conversation
            val smsRepository = SmsRepository(this)
            val messages = smsRepository.getMessagesForConversation(conversation.threadId)
                .sortedBy { it.date } // Ensure chronological order
                .takeLast(10) // Get last 10 messages
            
            Log.d("MainActivity", "Found ${messages.size} messages for conversation")
            
            if (messages.isEmpty()) {
                Log.d("MainActivity", "No messages found for smart reply generation")
                CoroutineScope(Dispatchers.Main).launch {
                    viewModel.smartReplies = emptyList()
                    viewModel.isLoadingSmartReplies = false
                }
                return
            }
            
            // Convert to SmartReplyGenerator format
            val messageData = messages.map { msg ->
                SmartReplyGenerator.MessageData(
                    text = msg.body,
                    timestamp = msg.date,
                    isOutgoing = msg.isOutgoing
                )
            }
            
            // Check if last message is from remote user (required for smart reply)
            val lastMessage = messageData.lastOrNull()
            if (lastMessage?.isOutgoing == true) {
                Log.d("MainActivity", "Last message is outgoing - smart reply won't generate suggestions")
                // Smart reply only works when last message is from the other person
                CoroutineScope(Dispatchers.Main).launch {
                    viewModel.smartReplies = emptyList()
                    viewModel.isLoadingSmartReplies = false
                }
                return
            }
            
            Log.d("MainActivity", "Calling smart reply generator with ${messageData.size} messages")
            
            // Generate smart replies
            val replies = smartReplyGenerator.generateReplies(
                messages = messageData,
                remoteUserId = conversation.address
            )
            
            Log.d("MainActivity", "Smart reply generator returned ${replies.size} suggestions")
            
            // Update UI on main thread
            CoroutineScope(Dispatchers.Main).launch {
                viewModel.smartReplies = replies
                viewModel.isLoadingSmartReplies = false
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error generating smart replies", e)
            CoroutineScope(Dispatchers.Main).launch {
                viewModel.smartReplies = emptyList()
                viewModel.isLoadingSmartReplies = false
            }
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
            speakText("Sent")
            
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
        // Cancel speech recognition immediately
        speechRecognition.cancelListening()
        viewModel.isListening = false
        
        // Clear transcription and state
        viewModel.replyTranscription = ""
        viewModel.recognizerState = "Idle"
        
        viewModel.resetReplyDialog()
    }
    

    // Compose dialog methods
    private fun showComposeDialog() {
        viewModel.showComposeDialog = true
        viewModel.composeTranscription = ""
        viewModel.composeMatchedContacts = emptyList()
        viewModel.composeRecognizerState = "Initializing..."
        
        // Get contacts repository for searching
        val contactsRepository = ContactsRepository(this)
        
        // Start recording automatically when dialog opens
        CoroutineScope(Dispatchers.Main).launch {
            delay(200) // Small delay to let dialog render
            startComposeListening(contactsRepository)
        }
    }
    
    private fun dismissComposeDialog() {
        // Cancel any ongoing speech recognition immediately
        speechRecognition.cancelListening()
        viewModel.composeIsListening = false
        viewModel.composeRecognizerState = "Idle"
        viewModel.resetComposeDialog()
    }
    
    private fun onComposeTextChange(text: String, contactsRepository: ContactsRepository) {
        viewModel.composeTranscription = text
        // Clear any error state when typing
        if (viewModel.composeRecognizerState.startsWith("Error:")) {
            viewModel.composeRecognizerState = "Type to search contacts"
        }
        searchContacts(contactsRepository)
    }
    
    private fun searchContacts(contactsRepository: ContactsRepository) {
        CoroutineScope(Dispatchers.IO).launch {
            val matches = contactsRepository.searchContacts(
                query = viewModel.composeTranscription,
                threshold = 0.2f,  // Lower threshold to catch more variations
                maxResults = 5     // Show more results
            )
            withContext(Dispatchers.Main) {
                viewModel.composeMatchedContacts = matches
            }
        }
    }
    
    private fun onComposeContactSelect(contact: FuzzyMatcher.MatchResult) {
        // Cancel compose listening immediately if active
        if (viewModel.composeIsListening) {
            speechRecognition.cancelListening()
            viewModel.composeIsListening = false
            viewModel.composeRecognizerState = "Idle"
        }
        
        // Create a conversation object for the selected contact
        val conversation = Conversation(
            threadId = -1L, // We'll need to find or create the actual thread
            address = contact.phoneNumber,
            contactName = contact.name,
            messageCount = 0,
            lastMessageText = null,
            lastMessageTime = System.currentTimeMillis(),
            unreadCount = 0,
            lastMessageIsOutgoing = false
        )
        
        // Close compose dialog and clear state
        viewModel.resetComposeDialog()
        
        // Clear any previous reply state completely
        viewModel.replyTranscription = ""
        viewModel.recognizerState = "Idle"
        viewModel.isListening = false
        viewModel.smartReplies = emptyList()
        viewModel.isLoadingSmartReplies = false
        
        // Open reply dialog with this contact
        viewModel.currentReplyConversation = conversation
        viewModel.showReplyDialog = true
        
        // Start recording automatically after a longer delay
        CoroutineScope(Dispatchers.Main).launch {
            delay(500) // Give more time for cleanup and dialog transition
            viewModel.recognizerState = "Initializing..."
            startReplyRecording()
        }
    }
    
    private fun startComposeListening(contactsRepository: ContactsRepository) {
        if (!viewModel.hasRecordPermission) {
            viewModel.composeRecognizerState = "No microphone permission"
            return
        }
        
        // Stop any TTS that might be playing
        tts.stop()
        
        viewModel.composeIsListening = true
        viewModel.composeRecognizerState = "Listening..."
        
        speechRecognition.startListening(
            onResult = { finalText ->
                viewModel.composeTranscription = finalText
                searchContacts(contactsRepository)
                viewModel.composeIsListening = false
                // Only update state if we didn't get results
                if (viewModel.composeMatchedContacts.isEmpty()) {
                    viewModel.composeRecognizerState = "No matches found"
                } else {
                    viewModel.composeRecognizerState = "Found ${viewModel.composeMatchedContacts.size} matches"
                }
                Log.d("MainActivity", "Compose final transcription: $finalText")
            },
            onPartialResult = { partialText ->
                viewModel.composeTranscription = partialText
                searchContacts(contactsRepository)
                // Update state while listening
                if (viewModel.composeMatchedContacts.isNotEmpty()) {
                    viewModel.composeRecognizerState = "Listening... (${viewModel.composeMatchedContacts.size} matches)"
                }
                Log.d("MainActivity", "Compose partial transcription: $partialText")
            },
            onError = { error ->
                viewModel.composeIsListening = false
                // Only show error if it's not a "no match" error when we have results
                if (!error.contains("no match", ignoreCase = true) || viewModel.composeMatchedContacts.isEmpty()) {
                    viewModel.composeRecognizerState = "Error: $error"
                } else {
                    viewModel.composeRecognizerState = "Found ${viewModel.composeMatchedContacts.size} matches"
                }
                Log.e("MainActivity", "Compose recognition error: $error")
            }
        )
    }
    
    private fun stopComposeListening() {
        speechRecognition.stopListening()
        viewModel.composeIsListening = false
        viewModel.composeRecognizerState = "Ready to search contacts"
        Log.d("MainActivity", "Stopped compose listening")
    }
    
    private fun retryComposeListening(contactsRepository: ContactsRepository) {
        // Stop current recording if active
        if (viewModel.composeIsListening) {
            stopComposeListening()
            // Small delay before restarting
            CoroutineScope(Dispatchers.Main).launch {
                delay(200)
                viewModel.composeTranscription = ""
                viewModel.composeMatchedContacts = emptyList()
                viewModel.composeRecognizerState = "Initializing..."
                startComposeListening(contactsRepository)
            }
        } else {
            // Clear transcription and start fresh
            viewModel.composeTranscription = ""
            viewModel.composeMatchedContacts = emptyList()
            viewModel.composeRecognizerState = "Initializing..."
            startComposeListening(contactsRepository)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Services are cleaned up in ViewModel.onCleared()
    }
}