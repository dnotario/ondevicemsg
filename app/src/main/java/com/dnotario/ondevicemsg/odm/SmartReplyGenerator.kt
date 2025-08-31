package com.dnotario.ondevicemsg.odm

import android.util.Log
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplyGenerator
import com.google.mlkit.nl.smartreply.TextMessage
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SmartReplyGenerator {
    
    companion object {
        private const val TAG = "SmartReplyGenerator"
        private const val MAX_SUGGESTIONS = 2  // We want 2 suggestions
    }
    
    private val smartReply: SmartReplyGenerator = SmartReply.getClient()
    
    /**
     * Generate smart reply suggestions based on conversation history
     */
    suspend fun generateReplies(
        messages: List<MessageData>,
        remoteUserId: String = "remote_user"
    ): List<String> {
        if (messages.isEmpty()) {
            Log.d(TAG, "No messages to generate replies from")
            return emptyList()
        }
        
        try {
            // Build conversation history
            val conversation = mutableListOf<TextMessage>()
            
            messages.forEach { message ->
                // Skip empty messages
                if (message.text.isBlank()) {
                    Log.d(TAG, "Skipping empty message")
                    return@forEach
                }
                
                Log.d(TAG, "Message: '${message.text.take(50)}' isOutgoing=${message.isOutgoing}")
                val textMessage = if (message.isOutgoing) {
                    // Local user (sent by me)
                    TextMessage.createForLocalUser(
                        message.text,
                        message.timestamp
                    )
                } else {
                    // Remote user (received)
                    TextMessage.createForRemoteUser(
                        message.text,
                        message.timestamp,
                        remoteUserId
                    )
                }
                conversation.add(textMessage)
            }
            
            if (conversation.isEmpty()) {
                Log.d(TAG, "No valid messages in conversation")
                return emptyList()
            }
            
            Log.d(TAG, "Processing ${conversation.size} messages for smart replies")
            Log.d(TAG, "Last message: '${messages.lastOrNull()?.text}' from ${if (messages.lastOrNull()?.isOutgoing == true) "me" else "them"}")
            
            // Generate suggestions
            val result = smartReply.suggestReplies(conversation).await()
            
            Log.d(TAG, "Smart reply result status: ${result.status}")
            when (result.status) {
                com.google.mlkit.nl.smartreply.SmartReplySuggestionResult.STATUS_SUCCESS -> {
                    val suggestions = result.suggestions.take(MAX_SUGGESTIONS).map { it.text }
                    Log.d(TAG, "Generated ${suggestions.size} smart reply suggestions: $suggestions")
                    return suggestions
                }
                com.google.mlkit.nl.smartreply.SmartReplySuggestionResult.STATUS_NOT_SUPPORTED_LANGUAGE -> {
                    Log.d(TAG, "Language not supported for smart reply")
                }
                com.google.mlkit.nl.smartreply.SmartReplySuggestionResult.STATUS_NO_REPLY -> {
                    Log.d(TAG, "No smart reply available for this conversation")
                }
                else -> {
                    Log.d(TAG, "Smart reply status: ${result.status}")
                }
            }
            return emptyList()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating smart replies", e)
            return emptyList()
        }
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        smartReply.close()
    }
    
    /**
     * Data class for message information
     */
    data class MessageData(
        val text: String,
        val timestamp: Long,
        val isOutgoing: Boolean
    )
}