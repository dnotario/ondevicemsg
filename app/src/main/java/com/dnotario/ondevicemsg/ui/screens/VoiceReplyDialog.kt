package com.dnotario.ondevicemsg.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
    var visible by remember { mutableStateOf(false) }
    
    // Trigger animation on first composition
    LaunchedEffect(Unit) {
        visible = true
    }
    
    // Animate background opacity
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (visible) 0.5f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "backgroundAlpha"
    )
    
    // Animate dialog scale and alpha
    val dialogScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.8f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "dialogScale"
    )
    
    val dialogAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "dialogAlpha"
    )
    
    // Full screen overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha)) // Animated scrim
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        
        // Dialog card with animations
        Card(
            modifier = modifier
                .fillMaxWidth()
                .height(380.dp) // Fixed height
                .padding(16.dp)
                .align(Alignment.Center)
                .scale(dialogScale)
                .alpha(dialogAlpha),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Simple header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = recipientName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        .weight(1f), // Take available space
                    placeholder = {
                        Text(
                            text = "Your message",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    enabled = !isRecording,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        disabledContainerColor = Color.Transparent
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 8
                )
                
                // Single status line (fixed height)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val statusText = when {
                        isRecording -> "Listening..."
                        recognizerState.startsWith("Error") -> recognizerState
                        recognizerState.startsWith("Recognized") -> "Complete"
                        recognizerState == "Initializing..." -> "Starting..."
                        editableText.isNotEmpty() -> "Ready to send"
                        else -> "Ready"
                    }
                    
                    val statusColor = when {
                        isRecording -> MaterialTheme.colorScheme.error
                        recognizerState.startsWith("Error") -> MaterialTheme.colorScheme.error
                        editableText.isNotEmpty() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
                
                // Simple action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Text(
                            text = when {
                                isRecording -> "Restart"
                                editableText.isNotEmpty() -> "Re-record"
                                else -> "Record"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    
                    Button(
                        onClick = { onSend(editableText) },
                        modifier = Modifier.weight(1f),
                        enabled = editableText.isNotEmpty(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "Send",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}