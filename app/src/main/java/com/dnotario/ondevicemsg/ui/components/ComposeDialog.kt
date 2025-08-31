package com.dnotario.ondevicemsg.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dnotario.ondevicemsg.utils.FuzzyMatcher
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
fun ComposeDialog(
    isListening: Boolean,
    recognizerState: String,
    transcription: String,
    matchedContacts: List<FuzzyMatcher.MatchResult>,
    onDismiss: () -> Unit,
    onTextChange: (String) -> Unit,
    onContactSelect: (FuzzyMatcher.MatchResult) -> Unit,
    onRetry: () -> Unit,
    onStopListening: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val textFieldFocusRequester = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f) // Take 90% of screen height
                .imePadding() // Adjust for keyboard
                .navigationBarsPadding(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Compose Message",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Text input field with simple keyboard
                OutlinedTextField(
                    value = transcription,
                    onValueChange = { text ->
                        onTextChange(text)
                        // Stop listening if user starts typing
                        if (isListening) {
                            onStopListening()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(textFieldFocusRequester),
                    label = { Text("Search contacts") },
                    placeholder = { Text("Type or speak contact name...") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    trailingIcon = {
                        if (transcription.isNotEmpty()) {
                            IconButton(onClick = { onTextChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Scrollable contacts area that fills remaining space
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f) // This takes all available space between text field and buttons
                ) {
                    if (matchedContacts.isNotEmpty()) {
                        Text(
                            text = "Contacts (${matchedContacts.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(matchedContacts) { contact ->
                                ContactItem(
                                    contact = contact,
                                    onClick = { onContactSelect(contact) }
                                )
                            }
                        }
                    } else if (transcription.isNotEmpty()) {
                        // Show empty state when searching but no matches
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No contacts found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        // Empty space when not searching
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Status line (matching reply dialog style)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val statusText = when {
                        isListening -> "Listening..."
                        recognizerState.startsWith("Error") -> recognizerState
                        recognizerState.contains("Found") -> recognizerState
                        recognizerState == "Initializing..." -> "Starting..."
                        transcription.isNotEmpty() -> "Type or speak to search"
                        else -> "Ready to search"
                    }
                    
                    val statusColor = when {
                        isListening -> MaterialTheme.colorScheme.error
                        recognizerState.startsWith("Error") -> MaterialTheme.colorScheme.error
                        matchedContacts.isNotEmpty() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // Pulsing indicator when listening (matching reply dialog)
                        if (isListening) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = statusColor,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Voice control button (centered)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isListening) {
                                onStopListening()
                            } else {
                                // Dismiss keyboard when starting voice search
                                focusManager.clearFocus()
                                onRetry()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = if (isListening) {
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = if (isListening) "Stop" else "Start",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isListening) "Stop Searching" else "Search with Voice",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            } // Close main Column
        } // Close Card
    } // Close Dialog
}

@Composable
fun ContactItem(
    contact: FuzzyMatcher.MatchResult,
    onClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasMultipleNumbers = contact.phoneNumbers.size > 1
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column {
            // Main contact row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        if (hasMultipleNumbers) {
                            expanded = !expanded
                        } else {
                            onClick()
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(8.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = contact.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = if (hasMultipleNumbers) {
                            "${contact.phoneNumbers.size} numbers"
                        } else {
                            formatPhoneNumber(contact.phoneNumbers.firstOrNull()?.number ?: "")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
                
                // Expand icon for multiple numbers
                if (hasMultipleNumbers) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            // Expandable phone numbers list
            if (expanded && hasMultipleNumbers) {
                Divider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.1f))
                
                contact.phoneNumbers.forEach { phoneNumber ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClick() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .padding(4.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = formatPhoneNumber(phoneNumber.number),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            if (phoneNumber.type.isNotEmpty()) {
                                Text(
                                    text = phoneNumber.type,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatPhoneNumber(number: String): String {
    val cleaned = number.filter { it.isDigit() }
    return when {
        cleaned.length == 10 -> "(${cleaned.substring(0, 3)}) ${cleaned.substring(3, 6)}-${cleaned.substring(6)}"
        cleaned.length == 11 && cleaned.startsWith("1") -> 
            "(${cleaned.substring(1, 4)}) ${cleaned.substring(4, 7)}-${cleaned.substring(7)}"
        else -> number
    }
}