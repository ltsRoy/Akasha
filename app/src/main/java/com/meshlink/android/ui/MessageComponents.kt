package com.MeshLink.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
 

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.Intent
import android.net.Uri
import com.MeshLink.android.model.MeshLinkMessage
import com.MeshLink.android.model.MeshMessageType
import com.MeshLink.android.model.DeliveryStatus
import com.MeshLink.android.mesh.BluetoothMeshService
import java.text.SimpleDateFormat
import java.util.*
import com.MeshLink.android.ui.media.VoiceNotePlayer
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import com.MeshLink.android.ui.media.FileMessageItem
import com.MeshLink.android.model.MeshLinkMessageType
import com.MeshLink.android.R
import androidx.compose.ui.res.stringResource


// VoiceNotePlayer moved to com.MeshLink.android.ui.media.VoiceNotePlayer

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<MeshLinkMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    modifier: Modifier = Modifier,
    forceScrollToBottom: Boolean = false,
    onScrolledUpChanged: ((Boolean) -> Unit)? = null,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((MeshLinkMessage) -> Unit)? = null,
    onCancelTransfer: ((MeshLinkMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    
    // Track if this is the first time messages are being loaded
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
    var followIncomingMessages by remember { mutableStateOf(true) }
    
    // Smart scroll: auto-scroll to bottom for initial load, then follow unless user scrolls away
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val isFirstLoad = !hasScrolledToInitialPosition
            if (isFirstLoad || followIncomingMessages) {
                listState.scrollToItem(0)
                if (isFirstLoad) {
                    hasScrolledToInitialPosition = true
                }
            }
        }
    }
    
    // Track whether user has scrolled away from the latest messages
    val isAtLatest by remember {
        derivedStateOf {
            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            firstVisibleIndex <= 2
        }
    }
    LaunchedEffect(isAtLatest) {
        followIncomingMessages = isAtLatest
        onScrolledUpChanged?.invoke(!isAtLatest)
    }
    
    // Force scroll to bottom when requested (e.g., when user sends a message)
    LaunchedEffect(forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            // With reverseLayout=true and reversed data, latest is at index 0
            followIncomingMessages = true
            listState.scrollToItem(0)
        }
    }
    
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
        reverseLayout = true
    ) {
        items(
            items = messages.asReversed(),
            key = { it.id }
        ) { message ->
                MessageItem(
                    message = message,
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: MeshLinkMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    messages: List<MeshLinkMessage> = emptyList(),
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((MeshLinkMessage) -> Unit)? = null,
    onCancelTransfer: ((MeshLinkMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    // Check if the message is an SOS emergency broadcast
    val isSos = message.meshMessageType == MeshMessageType.SOS || message.content.startsWith("🆘")
    if (isSos) {
        SosMessageCard(
            message = message,
            onMessageLongPress = onMessageLongPress
        )
        return
    }

    // Check if the message is a Location sharing broadcast
    val isLocation = message.meshMessageType == MeshMessageType.LOCATION || message.content.startsWith("📍")
    if (isLocation) {
        LocationMessageCard(
            message = message,
            onMessageLongPress = onMessageLongPress
        )
        return
    }


    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    
    val isSelf = message.senderPeerID == meshService.myPeerID || 
                 message.sender == currentUserNickname ||
                 message.sender.startsWith("$currentUserNickname#")

    // Signature inspector for this message, opened from the tick / warning marker.
    var showSecurityDetails by remember { mutableStateOf(false) }
    if (showSecurityDetails) {
        com.MeshLink.android.ui.components.MessageSecurityDialog(
            message = message,
            myPeerID = meshService.myPeerID,
            onDismiss = { showSecurityDetails = false },
        )
    }


    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val bubbleColor = if (isSelf) {
        if (isDark) Color(0xFF005C4B) else Color(0xFFD9FDD3) // WhatsApp green
    } else {
        if (isDark) Color(0xFF202C33) else Color(0xFFFFFFFF) // WhatsApp gray/white
    }
    
    // For group chats, we might want to show the sender name
    val showSenderName = !isSelf && !message.isPrivate && message.sender != "system"
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start
    ) {
        if (isSelf) {
            Spacer(modifier = Modifier.width(48.dp))
        }
        
        Box(
            modifier = Modifier
                .background(
                    color = if (message.sender == "system") Color.Transparent else bubbleColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isSelf) 12.dp else 4.dp,
                        bottomEnd = if (isSelf) 4.dp else 12.dp
                    )
                )
                .padding(if (message.sender == "system") 0.dp else 8.dp)
        ) {
            Column {
                if (showSenderName) {
                    val peerColor = com.MeshLink.android.ui.getPeerColor(message, isDark)
                    Text(
                        text = message.sender,
                        color = peerColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onNicknameClick?.invoke(message.sender) }
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                
                Row(verticalAlignment = Alignment.Bottom) {
                    MessageTextWithClickableNicknames(
                        message = message,
                        messages = messages,
                        currentUserNickname = currentUserNickname,
                        meshService = meshService,
                        colorScheme = colorScheme,
                        timeFormatter = timeFormatter,
                        onNicknameClick = onNicknameClick,
                        onMessageLongPress = onMessageLongPress,
                        onCancelTransfer = onCancelTransfer,
                        onImageClick = onImageClick,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeFormatter.format(message.timestamp),
                            color = Color.Gray.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )

                        // Authenticity marker for incoming traffic. Tap to see which key signed it.
                        if (!isSelf) {
                            Spacer(modifier = Modifier.width(4.dp))
                            com.MeshLink.android.ui.components.TrustBadge(
                                trustState = message.trustState,
                                size = 11,
                                modifier = Modifier.clickable { showSecurityDetails = true }
                            )
                        }

                        if (isSelf && message.isPrivate) {
                            Spacer(modifier = Modifier.width(4.dp))
                            message.deliveryStatus?.let { status ->
                                DeliveryStatusIcon(status = status)
                            }
                        }
                    }
                }
            }
        }
        
        if (!isSelf) {
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
    private fun MessageTextWithClickableNicknames(
        message: MeshLinkMessage,
        messages: List<MeshLinkMessage>,
        currentUserNickname: String,
        meshService: BluetoothMeshService,
        colorScheme: ColorScheme,
        timeFormatter: SimpleDateFormat,
        onNicknameClick: ((String) -> Unit)?,
        onMessageLongPress: ((MeshLinkMessage) -> Unit)?,
        onCancelTransfer: ((MeshLinkMessage) -> Unit)?,
        onImageClick: ((String, List<String>, Int) -> Unit)?,
        modifier: Modifier = Modifier
    ) {
    // Image special rendering
    if (message.type == MeshLinkMessageType.Image) {
        com.MeshLink.android.ui.media.ImageMessageItem(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            onImageClick = onImageClick,
            modifier = modifier
        )
        return
    }

    // Voice note special rendering
    if (message.type == MeshLinkMessageType.Audio) {
        com.MeshLink.android.ui.media.AudioMessageItem(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            modifier = modifier
        )
        return
    }

    // File special rendering
    if (message.type == MeshLinkMessageType.File) {
        val path = message.content.trim()
        // Derive sending progress if applicable
        val (overrideProgress, _) = when (val st = message.deliveryStatus) {
            is com.MeshLink.android.model.DeliveryStatus.PartiallyDelivered -> {
                if (st.total > 0 && st.reached < st.total) {
                    (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5) // blue while sending
                } else null to null
            }
            else -> null to null
        }
        Column(modifier = modifier.fillMaxWidth()) {
            // Header: nickname + timestamp line above the file, identical styling to text messages
            val headerText = formatMessageHeaderAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter
            )
            val haptic = LocalHapticFeedback.current
            var headerLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = headerText,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface,
                modifier = Modifier.pointerInput(message.id) {
                    detectTapGestures(onTap = { pos ->
                        val layout = headerLayout ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(pos)
                        val ann = headerText.getStringAnnotations("nickname_click", offset, offset)
                        if (ann.isNotEmpty() && onNicknameClick != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick.invoke(ann.first().item)
                        }
                    }, onLongPress = { onMessageLongPress?.invoke(message) })
                },
                onTextLayout = { headerLayout = it }
            )

            // Try to load the file packet from the path
            val packet = try {
                val file = java.io.File(path)
                if (file.exists()) {
                    // Create a temporary MeshLinkFilePacket for display
                    // In a real implementation, this would be stored with the packet metadata
                    com.MeshLink.android.model.MeshLinkFilePacket(
                        fileName = file.name,
                        fileSize = file.length(),
                        mimeType = com.MeshLink.android.features.file.FileUtils.getMimeTypeFromExtension(file.name),
                        content = file.readBytes()
                    )
                } else null
            } catch (e: Exception) {
                null
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box {
                    if (packet != null) {
                        if (overrideProgress != null) {
                            // Show sending animation while in-flight
                            com.MeshLink.android.ui.media.FileSendingAnimation(
                                fileName = packet.fileName,
                                progress = overrideProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // Static file display with open/save dialog
                            FileMessageItem(
                                packet = packet,
                                onFileClick = {
                                    // handled inside FileMessageItem via dialog
                                }
                            )
                        }

                        // Cancel button overlay during sending
                        val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
                        if (showCancel) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                                    .clickable { onCancelTransfer?.invoke(message) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    } else {
                        Text(text = stringResource(R.string.file_unavailable), fontFamily = FontFamily.Monospace, color = Color.Gray)
                    }
                }
            }
        }
        return
    }

    // Check if this message should be animated during PoW mining
    val shouldAnimate = shouldAnimateMessage(message.id)
    
    // If animation is needed, use the matrix animation component for content only
    if (shouldAnimate) {
        // Display message with matrix animation for content
        MessageWithMatrixAnimation(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onImageClick = onImageClick,
            modifier = modifier
        )
    } else {
        // Normal message display
        val annotatedText = formatMessageAsAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
        
        // Check if this message was sent by self to avoid click interactions on own nickname
        val isSelf = message.senderPeerID == meshService.myPeerID || 
                     message.sender == currentUserNickname ||
                     message.sender.startsWith("$currentUserNickname#")
        
        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = annotatedText,
            modifier = modifier.pointerInput(message) {
                detectTapGestures(
                    onTap = { position ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        // Nickname click only when not self
                        if (!isSelf && onNicknameClick != null) {
                            val nicknameAnnotations = annotatedText.getStringAnnotations(
                                tag = "nickname_click",
                                start = offset,
                                end = offset
                            )
                            if (nicknameAnnotations.isNotEmpty()) {
                                val nickname = nicknameAnnotations.first().item
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNicknameClick.invoke(nickname)
                                return@detectTapGestures
                            }
                        }
                        // Geohash teleport (all messages)
                        val geohashAnnotations = annotatedText.getStringAnnotations(
                            tag = "geohash_click",
                            start = offset,
                            end = offset
                        )
                        if (geohashAnnotations.isNotEmpty()) {
                            val geohash = geohashAnnotations.first().item
                            try {
                                val locationManager = com.MeshLink.android.geohash.LocationChannelManager.getInstance(
                                    context
                                )
                                val level = when (geohash.length) {
                                    in 0..2 -> com.MeshLink.android.geohash.GeohashChannelLevel.REGION
                                    in 3..4 -> com.MeshLink.android.geohash.GeohashChannelLevel.PROVINCE
                                    5 -> com.MeshLink.android.geohash.GeohashChannelLevel.CITY
                                    6 -> com.MeshLink.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
                                    else -> com.MeshLink.android.geohash.GeohashChannelLevel.BLOCK
                                }
                                val channel = com.MeshLink.android.geohash.GeohashChannel(level, geohash.lowercase())
                                locationManager.setTeleported(true)
                                locationManager.select(com.MeshLink.android.geohash.ChannelID.Location(channel))
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                        // URL open (all messages)
                        val urlAnnotations = annotatedText.getStringAnnotations(
                            tag = "url_click",
                            start = offset,
                            end = offset
                        )
                        if (urlAnnotations.isNotEmpty()) {
                            val raw = urlAnnotations.first().item
                            val resolved = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
            fontFamily = FontFamily.Monospace,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = androidx.compose.ui.text.TextStyle(
                color = colorScheme.onSurface
            ),
            onTextLayout = { result -> textLayoutResult = result }
        )
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = stringResource(R.string.status_sending),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Sent -> {
            // Use a subtle hollow marker for Sent; single check is reserved for Delivered (iOS parity)
            Text(
                text = stringResource(R.string.status_pending),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Delivered -> {
            // Single check for Delivered (matches iOS expectations)
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = stringResource(R.string.status_delivered),
                fontSize = 10.sp,
                color = Color(0xFF007AFF), // Blue
                fontWeight = FontWeight.Bold
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = stringResource(R.string.status_failed),
                fontSize = 10.sp,
                color = Color.Red.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            // Show a single subdued check without numeric label
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Renders an SOS emergency broadcast message as a prominent red card.
 * High-visibility styling ensures emergency alerts stand out clearly in the chat stream.
 *
 * @param message The SOS MeshLinkMessage to display.
 * @param onMessageLongPress Callback triggered when the message card is long-pressed.
 */
@Composable
fun SosMessageCard(
    message: MeshLinkMessage,
    onMessageLongPress: ((MeshLinkMessage) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFCDD2) // #FFCDD2 red background
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .pointerInput(message) {
                detectTapGestures(
                    onLongPress = { onMessageLongPress?.invoke(message) }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "🆘 EMERGENCY ALERT",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFFB71C1C), // dark red for high contrast
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = message.content,
                fontSize = 14.sp,
                color = Color.Black,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            val senderId = message.sosSenderId ?: message.sender
            Text(
                text = "Sender ID: $senderId",
                fontSize = 11.sp,
                color = Color.DarkGray,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Renders a Location Sharing message as a styled card with a pin icon and map link.
 *
 * @param message The Location MeshLinkMessage to display.
 * @param onMessageLongPress Callback triggered when the message card is long-pressed.
 */
@Composable
fun LocationMessageCard(
    message: MeshLinkMessage,
    onMessageLongPress: ((MeshLinkMessage) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .pointerInput(message) {
                detectTapGestures(
                    onLongPress = { onMessageLongPress?.invoke(message) }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = "📍 LOCATION SHARED",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Text(
                text = "Shared by: ${message.sender}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Extract lat/lng from content if not set on the object directly (fallback for backward compatibility)
            var lat = message.latitude
            var lng = message.longitude
            if (lat == null || lng == null) {
                try {
                    val coords = message.content.substringAfter("📍 Location Shared:").split(",")
                    lat = coords[0].trim().toDoubleOrNull()
                    lng = coords[1].trim().toDoubleOrNull()
                } catch (_: Exception) {}
            }

            if (lat != null && lng != null) {
                Text(
                    text = "Coordinates: %.5f, %.5f".format(lat, lng),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Button(
                    onClick = {
                        try {
                            val uri = "geo:$lat,$lng?q=$lat,$lng(Shared+Location)"
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                                setPackage("com.google.android.apps.maps")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback if Google Maps app is not installed
                            val uri = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(uri))
                            context.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "View on Map",
                        color = colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Text(
                    text = message.content,
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
