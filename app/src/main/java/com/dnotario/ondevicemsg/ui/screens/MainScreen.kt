package com.dnotario.ondevicemsg.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dnotario.ondevicemsg.data.models.Conversation
import com.dnotario.ondevicemsg.data.repository.SmsRepository

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Messages : Screen("messages", "Conversations", Icons.Default.Message)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    smsRepository: SmsRepository,
    onPlayMessage: (Conversation) -> Unit,
    onStopPlaying: () -> Unit,
    onReplyToMessage: (Conversation) -> Unit,
    currentlyPlayingThreadId: Long?,
    isRecording: Boolean,
    recognizerState: String,
    hasSmsPermissions: Boolean,
    showReplyDialog: Boolean,
    replyTranscription: String,
    currentReplyConversation: Conversation?,
    smartReplies: List<String> = emptyList(),
    isLoadingSmartReplies: Boolean = false,
    onSendReply: (String) -> Unit,
    onRetryReply: () -> Unit,
    onDismissReply: () -> Unit,
    refreshTrigger: Int = 0,
    onCompose: () -> Unit
) {
    val navController = rememberNavController()
    val items = listOf(Screen.Messages)
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Main content with potential blur
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showReplyDialog) {
                        Modifier.blur(radius = 5.dp) // Apply static blur when dialog is shown
                    } else {
                        Modifier
                    }
                )
        ) {
            Scaffold(
                floatingActionButton = {
                    if (hasSmsPermissions) {
                        FloatingActionButton(
                            onClick = onCompose,
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Compose"
                            )
                        }
                    }
                }
            ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Messages.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Messages.route) {
                if (hasSmsPermissions) {
                    MessagesScreen(
                        smsRepository = smsRepository,
                        onPlayMessage = onPlayMessage,
                        onStopPlaying = onStopPlaying,
                        onReplyToMessage = onReplyToMessage,
                        currentlyPlayingThreadId = currentlyPlayingThreadId,
                        refreshTrigger = refreshTrigger
                    )
                } else {
                    PermissionRequestScreen()
                }
            }
        }
    }
        } // Close Box with blur
        
        // Show voice reply dialog when needed
        if (showReplyDialog && currentReplyConversation != null) {
            VoiceReplyDialog(
                recipientName = currentReplyConversation.contactName ?: currentReplyConversation.getDisplayName(),
                recipientNumber = currentReplyConversation.address,
                isRecording = isRecording,
                transcribedText = replyTranscription,
                recognizerState = recognizerState,
                smartReplies = smartReplies,
                isLoadingSmartReplies = isLoadingSmartReplies,
                onSend = onSendReply,
                onRetry = onRetryReply,
                onDismiss = onDismissReply,
                onStopRecording = onRetryReply // Will stop if recording
            )
        }
    } // Close outer Box
}

@Composable
fun PermissionRequestScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "SMS Permission Required",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "This app needs SMS permissions to read and send messages.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = "Please grant the permission when prompted and restart the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}