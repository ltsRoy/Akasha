package com.MeshLink.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.MeshLink.android.ai.AriaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AriaChatSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: AriaViewModel
) {
    if (!isPresented) return

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val colorScheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.fillMaxHeight(0.95f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Aria AI Assistant",
                        tint = Color(0xFF00C851),
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Aria AI Assistant",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface
                        )
                        val isModelReady by viewModel.isModelReady.collectAsStateWithLifecycle()

                        val engineLabel = if (isModelReady) "🟢 Gemma 3 1B (100% Offline AI)" else "⚡ Local Keyword Engine"
                        val engineColor = if (isModelReady) Color(0xFF00C851) else Color(0xFFFFAB00)

                        Text(
                            text = engineLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = engineColor
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = "Clear chat",
                            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            val isModelReady by viewModel.isModelReady.collectAsStateWithLifecycle()

            // Banner when Gemma model is initializing
            if (!isModelReady) {
                ModelDownloadCard(
                    onRecheckClick = { viewModel.initializeLocalModel() }
                )
            }

            // Chat History
            val messages by viewModel.messages.collectAsStateWithLifecycle()
            val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
            val listState = rememberLazyListState()

            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages) { msg ->
                    val alignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                    val bubbleColor = if (msg.isUser) Color(0xFF00C851) else colorScheme.surfaceVariant
                    val textColor = if (msg.isUser) Color.White else colorScheme.onSurfaceVariant

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = alignment
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = bubbleColor,
                            modifier = Modifier.widthIn(max = 310.dp)
                        ) {
                            Text(
                                text = msg.text,
                                color = textColor,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                if (isTyping) {
                    item {
                        AriaTypingIndicator()
                    }
                }
            }

            // Input Field
            var inputText by remember { mutableStateOf("") }
            
            Surface(
                color = colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { 
                            Text("Type a message...") 
                        },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00C851),
                            focusedLabelColor = Color(0xFF00C851)
                        ),
                        maxLines = 4
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && !isTyping
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (inputText.isNotBlank() && !isTyping) Color(0xFF00C851) else colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelDownloadCard(
    onRecheckClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF00C851).copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00C851).copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.OfflineBolt,
                    contentDescription = null,
                    tint = Color(0xFF00C851),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Initializing Gemma 3 1B Offline Model...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "The Gemma 3 1B offline AI model is pre-packaged inside this app! Tap initialize to load it into memory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onRecheckClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C851)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Initialize Offline Model", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AriaTypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
    ) {
        for (dotIndex in 0..2) {
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(dotIndex * 200)
                ),
                label = "dot_$dotIndex"
            )
            
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00C851).copy(alpha = alpha))
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = "Aria is thinking...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
