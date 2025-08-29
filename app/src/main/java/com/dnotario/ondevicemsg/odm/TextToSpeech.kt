package com.dnotario.ondevicemsg.odm

import android.content.Context
import android.speech.tts.TextToSpeech as AndroidTTS
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Text-to-Speech service for converting text to spoken audio
 */
class TextToSpeech(private val context: Context) {
    
    companion object {
        private const val TAG = "ODM_TTS"
    }
    
    private var tts: AndroidTTS? = null
    private var isInitialized = false
    
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking
    
    /**
     * Initialize the TTS engine
     */
    suspend fun initialize(): Boolean = suspendCoroutine { continuation ->
        tts = AndroidTTS(context) { status ->
            if (status == AndroidTTS.SUCCESS) {
                tts?.setLanguage(Locale.US)
                
                // Set up utterance progress listener once
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        Log.d(TAG, "TTS started: $utteranceId")
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        Log.d(TAG, "TTS completed: $utteranceId")
                    }
                    
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        Log.e(TAG, "TTS error: $utteranceId")
                    }
                })
                
                isInitialized = true
                Log.d(TAG, "TextToSpeech initialized successfully")
                continuation.resume(true)
            } else {
                Log.e(TAG, "TextToSpeech initialization failed")
                continuation.resume(false)
            }
        }
    }
    
    /**
     * Speak the given text
     * @param text The text to speak
     * @param preprocessText Whether to apply text normalization (URLs, etc.)
     */
    fun speak(text: String, preprocessText: Boolean = true) {
        if (!isInitialized || text.isEmpty()) {
            Log.w(TAG, "Cannot speak: TTS not initialized or empty text")
            return
        }
        
        val processedText = if (preprocessText) {
            TextNormalizer.normalizeForTTS(text)
        } else {
            text
        }
        
        val utteranceId = "utterance_${System.currentTimeMillis()}"
        tts?.speak(processedText, AndroidTTS.QUEUE_FLUSH, null, utteranceId)
        Log.d(TAG, "Speaking: $processedText (id: $utteranceId)")
    }
    
    /**
     * Stop any ongoing speech
     */
    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        Log.d(TAG, "TTS stopped")
    }
    
    /**
     * Check if TTS is currently speaking
     */
    fun isCurrentlySpeaking(): Boolean {
        return tts?.isSpeaking == true
    }
    
    /**
     * Release TTS resources
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _isSpeaking.value = false
        Log.d(TAG, "TTS released")
    }
}