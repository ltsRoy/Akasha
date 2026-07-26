package com.MeshLink.android.ui.media

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.MeshLink.android.mesh.BluetoothMeshService
import com.MeshLink.android.model.MeshLinkMessage
import com.MeshLink.android.model.parseLocation
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LocationMessageItem(
    message: MeshLinkMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((MeshLinkMessage) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val coords = remember(message.content) { parseLocation(message.content) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    Column(modifier = modifier.fillMaxWidth()) {
        // Header: nickname + timestamp line, identical styling to text messages
        val headerText = com.MeshLink.android.ui.formatMessageHeaderAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
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

        Spacer(modifier = Modifier.height(4.dp))

        if (coords != null) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .pointerInput(message.id) {
                        detectTapGestures(
                            onLongPress = { onMessageLongPress?.invoke(message) }
                        )
                    }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "📍",
                            fontSize = 20.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Column {
                            Text(
                                text = "Shared Location",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariant
                            )
                            val timeStr = remember(message.timestamp) {
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(message.timestamp)
                            }
                            Text(
                                text = timeStr,
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Lat: %.4f".format(Locale.US, coords.latitude),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Lng: %.4f".format(Locale.US, coords.longitude),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val uri = Uri.parse("geo:${coords.latitude},${coords.longitude}?q=${coords.latitude},${coords.longitude}")
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            
                            // Check if Google Maps is installed, try opening directly
                            intent.setPackage("com.google.android.apps.maps")
                            
                            try {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback to chooser (open with any maps app)
                                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                                val chooser = Intent.createChooser(fallbackIntent, "Open location with")
                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(chooser)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Open in Maps",
                            color = colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            Text(
                text = "📍 Invalid Location: ${message.content}",
                fontFamily = FontFamily.Monospace,
                color = Color.Red
            )
        }
    }
}
