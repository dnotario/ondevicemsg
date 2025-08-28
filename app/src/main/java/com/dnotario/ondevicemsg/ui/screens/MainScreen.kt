package com.dnotario.ondevicemsg.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    object Demo : Screen("demo", "Demo", Icons.Default.Science)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    smsRepository: SmsRepository,
    onTtsClick: (String) -> Unit,
    onRecordClick: () -> Unit,
    onClearClick: () -> Unit,
    onOnlineModeChanged: (Boolean) -> Unit,
    onOpenLanguageSettings: () -> Unit,
    onPlayMessage: (Conversation) -> Unit,
    onReplyToMessage: (Conversation) -> Unit,
    isRecording: Boolean,
    recognizedText: String,
    ttsEnabled: Boolean,
    asrEnabled: Boolean,
    useOnlineRecognition: Boolean,
    recognizerState: String,
    hasSmsPermissions: Boolean,
    showReplyDialog: Boolean,
    replyTranscription: String,
    currentReplyConversation: Conversation?,
    onSendReply: (String) -> Unit,
    onRetryReply: () -> Unit,
    onDismissReply: () -> Unit,
    refreshTrigger: Int = 0
) {
    val navController = rememberNavController()
    val items = listOf(Screen.Messages, Screen.Demo)
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
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
                        onReplyToMessage = onReplyToMessage,
                        refreshTrigger = refreshTrigger
                    )
                } else {
                    PermissionRequestScreen()
                }
            }
            
            composable(Screen.Demo.route) {
                DemoScreen(
                    onTtsClick = onTtsClick,
                    onRecordClick = onRecordClick,
                    onClearClick = onClearClick,
                    onOnlineModeChanged = onOnlineModeChanged,
                    onOpenLanguageSettings = onOpenLanguageSettings,
                    isRecording = isRecording,
                    recognizedText = recognizedText,
                    ttsEnabled = ttsEnabled,
                    asrEnabled = asrEnabled,
                    useOnlineRecognition = useOnlineRecognition,
                    recognizerState = recognizerState
                )
            }
        }
    }
    
    // Show voice reply dialog when needed
    if (showReplyDialog && currentReplyConversation != null) {
        VoiceReplyDialog(
            recipientName = currentReplyConversation.contactName ?: currentReplyConversation.getDisplayName(),
            recipientNumber = currentReplyConversation.address,
            isRecording = isRecording,
            transcribedText = replyTranscription,
            recognizerState = recognizerState,
            onSend = onSendReply,
            onRetry = onRetryReply,
            onDismiss = onDismissReply
        )
    }
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