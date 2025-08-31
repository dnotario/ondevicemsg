package com.dnotario.ondevicemsg.odm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Speech recognition service for converting speech to text with audio visualization
 */
class SpeechRecognition(private val context: Context) {
    
    companion object {
        private const val TAG = "ODM_ASR"
    }
    
    private var recognizer: SpeechRecognizer? = null
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    
    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state
    
    private var onResultCallback: ((String) -> Unit)? = null
    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    
    /**
     * Initialize the speech recognizer
     */
    fun initialize() {
        // Try to create on-device recognizer first, fall back to network if needed
        recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            Log.d(TAG, "Using on-device speech recognition")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.d(TAG, "Using network speech recognition")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        
        recognizer?.setRecognitionListener(createRecognitionListener())
        Log.d(TAG, "Speech recognizer initialized")
    }
    
    /**
     * Start listening for speech input
     * @param onResult Callback for final transcription result
     * @param onPartialResult Optional callback for partial results
     * @param onError Optional callback for errors
     */
    fun startListening(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (recognizer == null) {
            Log.e(TAG, "Recognizer not initialized")
            onError?.invoke("Speech recognizer not initialized")
            return
        }
        
        onResultCallback = onResult
        onPartialResultCallback = onPartialResult
        onErrorCallback = onError
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // Prefer offline for privacy
        }
        
        recognizer?.startListening(intent)
        _isListening.value = true
        _state.value = RecognitionState.Listening
        Log.d(TAG, "Started listening")
    }
    
    /**
     * Stop listening for speech input
     */
    fun stopListening() {
        recognizer?.stopListening()
        _isListening.value = false
        _state.value = RecognitionState.Idle
        // Clear callbacks to prevent leaks
        onResultCallback = null
        onPartialResultCallback = null
        onErrorCallback = null
        Log.d(TAG, "Stopped listening")
    }
    
    /**
     * Cancel listening immediately
     */
    fun cancelListening() {
        recognizer?.cancel()
        _isListening.value = false
        _state.value = RecognitionState.Idle
        // Clear callbacks to prevent leaks
        onResultCallback = null
        onPartialResultCallback = null
        onErrorCallback = null
        Log.d(TAG, "Cancelled listening")
    }
    
    /**
     * Release speech recognizer resources
     */
    fun release() {
        recognizer?.destroy()
        recognizer = null
        _isListening.value = false
        _state.value = RecognitionState.Idle
        Log.d(TAG, "Speech recognizer released")
    }
    
    /**
     * Create the recognition listener with all callbacks
     */
    private fun createRecognitionListener() = object : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            _state.value = RecognitionState.Listening
        }
        
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // Not used - no audio visualization
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {
            // Not used
        }
        
        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
            _state.value = RecognitionState.Processing
        }
        
        override fun onError(error: Int) {
            val errorMessage = getErrorMessage(error)
            Log.e(TAG, "Recognition error: $errorMessage")
            _state.value = RecognitionState.Error(errorMessage)
            _isListening.value = false
            onErrorCallback?.invoke(errorMessage)
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcription = matches?.firstOrNull() ?: ""
            
            Log.d(TAG, "Final result: $transcription")
            _state.value = RecognitionState.Idle
            _isListening.value = false
            
            if (transcription.isNotEmpty()) {
                onResultCallback?.invoke(transcription)
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partialTranscription = matches?.firstOrNull() ?: ""
            
            Log.d(TAG, "Partial result: $partialTranscription")
            if (partialTranscription.isNotEmpty()) {
                onPartialResultCallback?.invoke(partialTranscription)
            }
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "Event type: $eventType")
        }
    }
    
    
    /**
     * Get human-readable error message
     */
    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
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
            else -> "Error code: $errorCode"
        }
    }
}

/**
 * Recognition state
 */
sealed class RecognitionState {
    object Idle : RecognitionState()
    object Listening : RecognitionState()
    object Processing : RecognitionState()
    data class Error(val message: String) : RecognitionState()
}