package com.dnotario.ondevicemsg.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

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
    onStopRecording: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var editableText by remember(transcribedText) { mutableStateOf(transcribedText) }
    var visible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val textFieldFocusRequester = remember { FocusRequester() }
    
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
        modifier = Modifier.fillMaxSize()
    ) {
        // Dark scrim overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha)) // Animated scrim
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        
        // Container that handles keyboard and status bar padding
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding() // Respect status bar
                .imePadding(), // Push up with keyboard
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp) // Slightly smaller to fit better
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .scale(dialogScale)
                    .alpha(dialogAlpha),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
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
                    onValueChange = { 
                        editableText = it
                        // Stop recording if user starts typing
                        if (isRecording) {
                            onStopRecording()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f) // Take available space
                        .focusRequester(textFieldFocusRequester),
                    placeholder = {
                        Text(
                            text = "Your message",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    enabled = true, // Always enabled to allow editing
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
                        onClick = {
                            // Dismiss keyboard when starting recording
                            if (!isRecording) {
                                focusManager.clearFocus()
                            }
                            onRetry()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        colors = if (isRecording) {
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(
                            text = if (isRecording) "Stop" else "Record",
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
        } // Close centering Box
    } // Close outer Box
}