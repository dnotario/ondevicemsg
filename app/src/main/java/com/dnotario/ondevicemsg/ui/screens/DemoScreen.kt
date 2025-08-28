package com.dnotario.ondevicemsg.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun DemoScreen(
    onTtsClick: (String) -> Unit,
    onRecordClick: () -> Unit,
    onClearClick: () -> Unit,
    onOnlineModeChanged: (Boolean) -> Unit,
    onOpenLanguageSettings: () -> Unit,
    isRecording: Boolean,
    recognizedText: String,
    ttsEnabled: Boolean,
    asrEnabled: Boolean,
    useOnlineRecognition: Boolean,
    recognizerState: String
) {
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "On-Device TTS & ASR Test",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Text to Speech",
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("Enter text to speak") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                Button(
                    onClick = { onTtsClick(inputText) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enabled = ttsEnabled && inputText.isNotEmpty()
                ) {
                    Text("Speak Text")
                }

                if (!ttsEnabled) {
                    Text(
                        text = "TTS initializing...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Speech Recognition",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                recognizerState.startsWith("Error") -> MaterialTheme.colorScheme.errorContainer
                                recognizerState.startsWith("Recognized") -> MaterialTheme.colorScheme.primaryContainer
                                recognizerState.startsWith("Hearing") || recognizerState == "Listening..." -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Text(
                            text = recognizerState,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = when {
                                recognizerState.startsWith("Error") -> MaterialTheme.colorScheme.onErrorContainer
                                recognizerState.startsWith("Recognized") -> MaterialTheme.colorScheme.onPrimaryContainer
                                recognizerState.startsWith("Hearing") || recognizerState == "Listening..." -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = recognizedText,
                    onValueChange = { },
                    label = { Text("Recognized text will appear here") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    readOnly = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRecordClick,
                        enabled = asrEnabled,
                        colors = if (isRecording) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Text(if (isRecording) "Stop Recording" else "Start Recording")
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = useOnlineRecognition,
                            onCheckedChange = onOnlineModeChanged,
                            enabled = !isRecording
                        )
                        Text(
                            text = "Allow Online",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (!asrEnabled) {
                    Text(
                        text = "ASR not available or permission denied",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (isRecording) {
                    Text(
                        text = "🎤 Recording... (will stop on silence)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Text(
                    text = if (useOnlineRecognition) "Mode: Online/Offline" else "Mode: On-Device Only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Show button to download language if unavailable
                if (recognizerState.contains("Language unavailable") && !useOnlineRecognition) {
                    OutlinedButton(
                        onClick = onOpenLanguageSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download Offline Language Pack")
                    }
                    Text(
                        text = "Go to: Languages & input → On-device speech recognition → Download English",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Button(
            onClick = onClearClick,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = recognizedText.isNotEmpty()
        ) {
            Text("Clear Recognized Text")
        }
    }
}