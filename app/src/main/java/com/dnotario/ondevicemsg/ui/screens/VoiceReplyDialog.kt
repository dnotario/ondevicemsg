package com.dnotario.ondevicemsg.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun VoiceReplyDialog(
    recipientName: String,
    recipientNumber: String,
    isRecording: Boolean,
    transcribedText: String,
    recognizerState: String,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editableText by remember(transcribedText) { mutableStateOf(transcribedText) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reply to",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = recipientName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = recipientNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Divider()
                
                // Recording indicator
                if (isRecording) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Recording",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Listening...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    
                    // Animated recording indicator
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                
                // Update editableText when transcribedText changes
                LaunchedEffect(transcribedText) {
                    if (transcribedText.isNotEmpty()) {
                        editableText = transcribedText
                    }
                }
                
                // Editable text field
                OutlinedTextField(
                    value = editableText,
                    onValueChange = { editableText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp), // Fixed height to prevent resizing
                    placeholder = {
                        Text(
                            text = if (isRecording) "Listening..." else "Your message will appear here",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    enabled = !isRecording,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 10
                )
                
                // Recognizer state indicator
                if (recognizerState.isNotEmpty() && recognizerState != "Idle") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                recognizerState.startsWith("Error") -> MaterialTheme.colorScheme.errorContainer
                                recognizerState.startsWith("Recognized") -> MaterialTheme.colorScheme.primaryContainer
                                recognizerState.startsWith("Hearing") -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Text(
                            text = recognizerState,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = when {
                                recognizerState.startsWith("Error") -> MaterialTheme.colorScheme.onErrorContainer
                                recognizerState.startsWith("Recognized") -> MaterialTheme.colorScheme.onPrimaryContainer
                                recognizerState.startsWith("Hearing") -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Retry/Change/Restart button
                    val retryButtonText = when {
                        isRecording -> "Restart"
                        editableText.isNotEmpty() -> "Re-record"
                        recognizerState.startsWith("Error") -> "Retry"
                        else -> "Retry"
                    }
                    
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        enabled = true, // Always enabled now
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Refresh else if (editableText.isNotEmpty()) Icons.Default.Mic else Icons.Default.Refresh,
                            contentDescription = retryButtonText,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = retryButtonText,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    
                    // Send button
                    Button(
                        onClick = { onSend(editableText) },
                        modifier = Modifier.weight(1f),
                        enabled = editableText.isNotEmpty(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecording) "Send" else "Send",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                
                // Helper text
                Text(
                    text = when {
                        isRecording -> "Speaking now... Will auto-stop on silence"
                        editableText.isNotEmpty() -> "Edit message or tap Re-record to start over"
                        recognizerState.startsWith("Error") -> "Tap Retry to try again"
                        else -> "Initializing voice recognition..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}