package com.dnotario.ondevicemsg.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dnotario.ondevicemsg.data.models.Conversation
import com.dnotario.ondevicemsg.data.repository.SmsRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessagesScreen(
    smsRepository: SmsRepository,
    onPlayMessage: (Conversation) -> Unit,
    onStopPlaying: () -> Unit,
    onReplyToMessage: (Conversation) -> Unit,
    currentlyPlayingThreadId: Long? = null,
    refreshTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    var conversations by remember { mutableStateOf<Map<Long, Conversation>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    
    // Initial load
    LaunchedEffect(Unit) {
        isLoading = true
        try {
            val convList = smsRepository.getConversations()
            conversations = convList.associateBy { it.threadId }
        } catch (e: Exception) {
            // Handle error
        } finally {
            isLoading = false
        }
    }
    
    // Update only changed conversations
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(10000) // Check every 10 seconds
            try {
                val newConversations = smsRepository.getConversations()
                
                // Build a new map, preserving unchanged conversations
                val updatedMap = conversations.toMutableMap()
                
                newConversations.forEach { newConv ->
                    val existing = updatedMap[newConv.threadId]
                    // Only update if content actually changed
                    if (newConv.hasContentChanges(existing)) {
                        updatedMap[newConv.threadId] = newConv
                    }
                }
                
                // Remove deleted conversations
                val currentThreadIds = newConversations.map { it.threadId }.toSet()
                updatedMap.keys.retainAll(currentThreadIds)
                
                if (updatedMap != conversations) {
                    conversations = updatedMap
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
    
    // Force refresh when explicitly triggered (e.g., after sending)
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            try {
                val convList = smsRepository.getConversations()
                conversations = convList.associateBy { it.threadId }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Removed title for cleaner Android Auto style
        
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No messages yet",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            val sortedConversations = remember(conversations) {
                conversations.values.sortedByDescending { it.lastMessageTime }
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(
                    items = sortedConversations,
                    key = { it.threadId } // Use stable keys for better recomposition
                ) { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        isPlaying = currentlyPlayingThreadId == conversation.threadId,
                        onPlay = { onPlayMessage(conversation) },
                        onStop = onStopPlaying,
                        onReply = { onReplyToMessage(conversation) }
                    )
                }
            }
        }
    }
}

@Composable
fun ConversationCard(
    conversation: Conversation,
    isPlaying: Boolean = false,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onReply: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = conversation.getDisplayName(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (conversation.unreadCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = conversation.unreadCount.toString(),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = if (conversation.lastMessageIsOutgoing) {
                            "You: ${conversation.lastMessageText ?: "No messages"}"
                        } else {
                            conversation.lastMessageText ?: "No messages"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = formatTime(conversation.lastMessageTime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = if (isPlaying) onStop else onPlay,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    colors = if (isPlaying) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ) else ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Stop" else "Play",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isPlaying) "Stop" else "Play")
                }
                
                Button(
                    onClick = onReply,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Reply",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reply")
                }
            }
        }
    }
}

fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }
}