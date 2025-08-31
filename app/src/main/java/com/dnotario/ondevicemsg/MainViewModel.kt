package com.dnotario.ondevicemsg

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.dnotario.ondevicemsg.data.models.Conversation
import com.dnotario.ondevicemsg.odm.TextToSpeech
import com.dnotario.ondevicemsg.odm.SpeechRecognition
import com.dnotario.ondevicemsg.odm.ImageAnalysis
import com.dnotario.ondevicemsg.utils.FuzzyMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewModel to preserve UI state and services across configuration changes
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    // Services that need to survive configuration changes
    val tts = TextToSpeech(application)
    val speechRecognition = SpeechRecognition(application)
    val imageAnalysis = ImageAnalysis(application)
    
    private var servicesInitialized = false
    
    init {
        // Initialize services once
        if (!servicesInitialized) {
            CoroutineScope(Dispatchers.IO).launch {
                tts.initialize()
                imageAnalysis.initialize()
            }
            speechRecognition.initialize()
            servicesInitialized = true
        }
    }
    
    // Playback state
    var currentlyPlayingThreadId by mutableStateOf<Long?>(null)
    var currentPlaybackJob: Job? = null
    
    // Reply dialog state
    var showReplyDialog by mutableStateOf(false)
    var replyTranscription by mutableStateOf("")
    var currentReplyConversation by mutableStateOf<Conversation?>(null)
    
    // Recognition state
    var isListening by mutableStateOf(false)
    var recognizerState by mutableStateOf("Idle")
    
    // Permissions state
    var hasSmsPermissions by mutableStateOf(false)
    var hasContactsPermission by mutableStateOf(false)
    var hasRecordPermission by mutableStateOf(false)
    
    // Conversation refresh trigger
    var conversationRefreshTrigger by mutableStateOf(0)
    
    // Compose dialog state
    var showComposeDialog by mutableStateOf(false)
    var composeTranscription by mutableStateOf("")
    var composeMatchedContacts by mutableStateOf<List<FuzzyMatcher.MatchResult>>(emptyList())
    var composeIsListening by mutableStateOf(false)
    var composeRecognizerState by mutableStateOf("Idle")
    
    fun resetReplyDialog() {
        showReplyDialog = false
        replyTranscription = ""
        currentReplyConversation = null
        recognizerState = "Idle"
    }
    
    fun resetComposeDialog() {
        showComposeDialog = false
        composeTranscription = ""
        composeMatchedContacts = emptyList()
        composeRecognizerState = "Idle"
        composeIsListening = false
    }
    
    fun stopPlayback() {
        // Cancel the coroutine job first
        currentPlaybackJob?.cancel()
        currentPlaybackJob = null
        // Then stop TTS
        tts.stop()
        currentlyPlayingThreadId = null
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clean up services when ViewModel is destroyed
        tts.release()
        speechRecognition.release()
        imageAnalysis.release()
    }
}