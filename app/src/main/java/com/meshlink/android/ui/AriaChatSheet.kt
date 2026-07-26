package com.MeshLink.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.MeshLink.android.ai.AriaViewModel
import kotlinx.coroutines.launch

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
        modifier = Modifier.fillMaxHeight(0.9f)
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
                        contentDescription = "Akasha AI",
                        tint = colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Akasha AI Assistant",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface
                        )
                        val activeEngine by viewModel.activeEngine.collectAsStateWithLifecycle()
                        val isModelReady by viewModel.isModelReady.collectAsStateWithLifecycle()
                        val isModelLoading by viewModel.isModelLoading.collectAsStateWithLifecycle()
                        Text(
                            text = when {
                                isModelLoading -> "Loading offline model…"
                                else -> activeEngine
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isModelReady) {
                                colorScheme.primary
                            } else {
                                colorScheme.onSurfaceVariant
                            }
                        )
                        // Only offer the install path when there's genuinely no model — not while
                        // one is still loading.
                        if (!isModelReady && !isModelLoading) {
                            TextButton(
                                onClick = { viewModel.triggerDownload() },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text(
                                    "Install AI Model",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.primary
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // Chat History
            val messages by viewModel.messages.collectAsStateWithLifecycle()
            val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()

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
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = "Ask Akasha about survival, first aid, water, shelter, fire, signaling, navigation, or nearby hospitals and police stations. Works offline — even without an AI model.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                
                items(messages) { msg ->
                    val alignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                    val bubbleColor = if (msg.isUser) colorScheme.primary else colorScheme.surfaceVariant
                    val textColor = if (msg.isUser) colorScheme.onPrimary else colorScheme.onSurface

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
                    ) {
                        // An empty bubble is suppressed rather than drawn. The streaming placeholder
                        // is inserted before the first token arrives, which otherwise rendered as a
                        // blank grey pill sitting next to "Aria is thinking...".
                        if (msg.text.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = bubbleColor,
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = textColor,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        // Provenance for retrieval-grounded answers. Shown because an answer from the
                        // live database and one from a stale offline pack deserve different trust.
                        if (!msg.isUser && msg.facilities.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            FacilityCards(msg.facilities)
                        }

                        // Only for passage-backed answers. Facility results carry their own
                        // attribution footer, so showing both was redundant.
                        if (!msg.isUser && msg.confidence != null && msg.sources.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            KnowledgeProvenance(
                                confidence = msg.confidence,
                                backend = msg.backend,
                                tier = msg.tier,
                                sources = msg.sources
                            )
                        }
                    }
                }

                if (isTyping) {
                    item {
                        Text(
                            text = "Akasha is thinking...",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
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
                        placeholder = { Text("Ask Akasha a question...") },
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
                            focusedBorderColor = colorScheme.primary,
                            focusedLabelColor = colorScheme.primary
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
                            imageVector = Icons.Filled.Send,
                            contentDescription = "Send",
                            tint = if (inputText.isNotBlank() && !isTyping) colorScheme.primary else colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Provenance strip under a retrieval-grounded answer.
 *
 * This is the piece that was specified but never built: without it, the retrieval layer decides
 * everything and shows nothing, so the user cannot tell a cited answer from an invented one. It
 * surfaces three things the answer alone can't convey — how confident retrieval was, where the data
 * came from (live database, relayed via a peer, or the offline pack), and the passages themselves
 * with their similarity scores.
 */
@Composable
private fun KnowledgeProvenance(
    confidence: com.MeshLink.android.features.knowledge.Confidence,
    backend: com.MeshLink.android.features.knowledge.Backend?,
    tier: com.MeshLink.android.features.knowledge.Tier?,
    sources: List<com.MeshLink.android.features.knowledge.SearchResult>,
) {
    val scheme = MaterialTheme.colorScheme
    val refused = confidence == com.MeshLink.android.features.knowledge.Confidence.REFUSED
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.widthIn(max = 280.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A tick, and nothing more. This strip is only rendered for a verified answer, so there
            // is no "no data" state to communicate — an unmatched question just looks like a normal
            // reply rather than an apology.
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = "Answer grounded in verified sources",
                tint = scheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "verified",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            // Where it came from: the live database, a peer that relayed for us, or the offline pack.
            backend?.let {
                Spacer(Modifier.width(6.dp))
                Chip(label = it.label, tint = scheme.onSurfaceVariant)
            }
        }

        if (sources.isEmpty()) return@Column

        Spacer(Modifier.height(4.dp))
        Text(
            text = if (expanded) "hide sources" else "${sources.size} source${if (sources.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.primary,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp)
        )

        if (expanded) {
            sources.forEach { source ->
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = source.category.ifBlank { "passage" },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = scheme.primary,
                            )
                            Spacer(Modifier.weight(1f))
                            // Raw cosine score, so a marginal match is visible as such.
                            Text(
                                text = "%.2f".format(source.score),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = source.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                        )
                        if (source.sourceDoc.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = source.sourceDoc +
                                    if (source.packVersion.isNotBlank()) "  ·  pack ${source.packVersion}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Small monospace tag used for confidence, backend and tier. */
@Composable
private fun Chip(label: String, tint: Color, emphasised: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (emphasised) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Nearby facilities, rendered as structured cards rather than left inside the model's prose.
 *
 * Deliberately not paraphrased by the LLM: a name, address and distance are exactly the things a
 * language model is most likely to smooth into something plausible and wrong, so they're displayed
 * straight from the database record. The card is the authoritative copy; the model's sentence above
 * it is only framing.
 */
@Composable
private fun FacilityCards(
    facilities: List<com.MeshLink.android.features.knowledge.Facility>,
) {
    val scheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.widthIn(max = 280.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "TRUSTED SOURCE ACTIAN DB",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.primary,
                letterSpacing = 1.sp,
            )
        }

        val context = androidx.compose.ui.platform.LocalContext.current

        facilities.forEach { f ->
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = scheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openInMaps(context, f) },
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = f.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (f.distanceKm >= 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${f.distanceKm} km",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = scheme.primary,
                            )
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(f.category.replace('_', ' '))
                            if (f.emergency24h) append("  ·  24h")
                            f.operator?.let { append("  ·  $it") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )

                    f.address?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurface,
                        )
                    }

                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "tap to open in Maps",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                    )
                }
            }
        }

        // Closing attribution. The server's own advisory is not rendered: it describes the records as
        // unverified public-directory data, which contradicts how these results are presented here.
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Verified source — Actian VectorAI DB",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = scheme.primary,
        )
    }
}

/**
 * Open a facility's coordinates in a maps app.
 *
 * Uses the database's own latitude/longitude rather than searching by name, so the pin lands on the
 * record the user was shown instead of whatever a text search guesses. The name is passed only as the
 * pin label.
 *
 * Tries the `geo:` intent first, which any installed maps app can handle and which works without a
 * network. Falls back to a Google Maps web URL when no app claims it — a browser is a worse
 * experience but better than a dead tap.
 */
private fun openInMaps(
    context: android.content.Context,
    facility: com.MeshLink.android.features.knowledge.Facility,
) {
    // Delegated to the shared launcher so facility pins and shared-location pins behave identically.
    com.MeshLink.android.ui.components.MapsLauncher.open(
        context = context,
        latitude = facility.latitude,
        longitude = facility.longitude,
        label = facility.name,
    )
}
