package com.MeshLink.android.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import com.MeshLink.android.features.voice.LiveAudioPlayer
import com.MeshLink.android.features.voice.LiveAudioStreamer
import com.MeshLink.android.model.RoutedPacket
import com.MeshLink.android.protocol.MeshLinkPacket
import com.MeshLink.android.protocol.MessageType
import com.MeshLink.android.protocol.SpecialRecipients
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.draw.blur
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.MeshLink.android.R
import com.MeshLink.android.india.NavicMonitor
import com.MeshLink.android.model.MeshLinkMessage
import com.MeshLink.android.model.MeshLinkMessageType
import com.MeshLink.android.ui.ChatViewModel
import com.MeshLink.android.ui.VoiceRecordButton
import com.MeshLink.android.ui.theme.BeamMode
import com.MeshLink.android.ui.theme.GrabberHandle
import com.MeshLink.android.ui.theme.OrbState
import com.MeshLink.android.ui.theme.SosGlyph
import com.MeshLink.android.ui.theme.ThoughtOrb
import com.MeshLink.android.ui.theme.SectionHeader
import com.MeshLink.android.ui.theme.Space
import com.MeshLink.android.ui.theme.borderBeam
import com.MeshLink.android.ui.theme.bounceClick
import com.MeshLink.android.ui.theme.hairline
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val FOCUS_ZOOM = 16.5f

/**
 * Resting height of the mesh sheet. Tall enough that the header, composer and the first messages
 * are visible without dragging, while a strip of map stays uncovered above it.
 */
private val SHEET_PEEK = 420.dp

/**
 * Hop limit for live audio.
 *
 * Kept short deliberately: voice runs at ~25 packets a second, and relaying that across the whole
 * mesh would saturate BLE and starve text and SOS traffic. Three hops covers a useful area while
 * leaving the network usable.
 */
private const val LIVE_CALL_TTL: UByte = 3u

/** Convert our hex peer ID into the fixed 8-byte sender field the packet header expects. */
private fun hexToPeerId(hex: String): ByteArray {
    val out = ByteArray(8)
    var i = 0
    var rest = hex
    while (rest.length >= 2 && i < 8) {
        out[i] = rest.substring(0, 2).toIntOrNull(16)?.toByte() ?: 0
        rest = rest.substring(2)
        i++
    }
    return out
}

/**
 * Akasha home: a dark map fills the backdrop, the dotted thought-orb is docked in a panel over it
 * (not floating loose on the map), and the sheet below carries the live mesh detail — peers with
 * signal strength, channels, the timeline, and a composer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AkashaHomeScreen(
    viewModel: ChatViewModel,
    onOpenChat: () -> Unit,
    onOpenPeers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val peers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val peerRSSI by viewModel.peerRSSI.collectAsStateWithLifecycle()
    val peerDirect by viewModel.peerDirect.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val unreadChannels by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val unreadPrivate by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()

    // Mirror ChatScreen's source selection so home shows the SAME timeline the app is actually on.
    // Without this, messages that arrive on the active geohash/classic channel land in a different
    // bucket than `messages` and never appear here — which is why the plain feed looked unreliable.
    val timeline: List<MeshLinkMessage> = when {
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        selectedLocationChannel is com.MeshLink.android.geohash.ChannelID.Location -> {
            val geo = (selectedLocationChannel as com.MeshLink.android.geohash.ChannelID.Location).channel.geohash
            channelMessages["geo:$geo"] ?: emptyList()
        }
        else -> messages
    }

    var confirmSos by remember { mutableStateOf(false) }
    var sosSent by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showAria by remember { mutableStateOf(false) }
    // Message whose signature details are being inspected, if any.
    var inspectMessage by remember { mutableStateOf<MeshLinkMessage?>(null) }

    // --- Live voice call ---
    val incomingCall by viewModel.incomingCall.collectAsStateWithLifecycle()
    val isLiveStreaming by LiveAudioStreamer.isStreaming.collectAsStateWithLifecycle()

    /**
     * Announce a broadcast, then stream microphone audio as it's captured.
     *
     * CALL_REQUEST goes out first so nearby devices can show an invite; the audio chunks that follow
     * are fire-and-forget with a short TTL, since a voice frame that arrives late is useless. The
     * sender also opens its own player so it can hear anyone else who joins.
     */
    val startLiveCall = {
        val service = viewModel.meshService
        val announce = MeshLinkPacket(
            version = 1u,
            type = MessageType.CALL_REQUEST.value,
            senderID = hexToPeerId(service.myPeerID),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = ByteArray(0),
            ttl = LIVE_CALL_TTL,
        )
        service.broadcastPacket(RoutedPacket(announce))

        LiveAudioStreamer.onPacketReady = { payload ->
            val chunk = MeshLinkPacket(
                version = 1u,
                type = MessageType.VOICE_STREAM.value,
                senderID = hexToPeerId(service.myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = payload,
                ttl = LIVE_CALL_TTL,
            )
            service.broadcastPacket(RoutedPacket(chunk))
        }
        LiveAudioStreamer.startStreaming()
        LiveAudioPlayer.isCallActive = true
        LiveAudioPlayer.startPlaying()
    }

    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startLiveCall() }

    val toggleLiveCall: () -> Unit = {
        if (isLiveStreaming) {
            LiveAudioStreamer.stopStreaming()
            LiveAudioPlayer.endCall()
        } else {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (granted) startLiveCall() else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // Release the mic and speaker if the screen goes away mid-call — otherwise the stream would
    // keep running with no way to stop it from the UI.
    DisposableEffect(Unit) {
        onDispose {
            LiveAudioStreamer.stopStreaming()
            LiveAudioPlayer.endCall()
        }
    }

    val scope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded),
    )

    // --- Location for the map backdrop ---
    var myLocation by remember { mutableStateOf<LatLng?>(null) }
    val camera = rememberCameraPositionState()
    var centered by remember { mutableStateOf(false) }

    val hasLocationPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@LaunchedEffect
        while (true) {
            val here = runCatching {
                LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            }.getOrNull()
            if (here != null) myLocation = LatLng(here.latitude, here.longitude)
            kotlinx.coroutines.delay(15_000)
        }
    }

    // How far out the furthest peer sits, from its signal strength. Drives the zoom so a tight
    // cluster gets framed closely instead of collapsing onto the centre dot.
    // Framed against the radius actually drawn, not the raw estimate, so the camera matches the dots.
    val spreadMeters = remember(peers, peerRSSI) {
        peers.map { displayMeters(peerRSSI[it]) }.maxOrNull()
    }
    val targetZoom = spreadMeters?.let { zoomForRadius(it) } ?: FOCUS_ZOOM

    LaunchedEffect(myLocation) {
        val target = myLocation
        if (!centered && target != null) {
            camera.position = CameraPosition.fromLatLngZoom(target, targetZoom)
            centered = true
        }
    }

    // Re-frame smoothly as peers join/leave or their signal (and so the spread) changes.
    LaunchedEffect(targetZoom, myLocation) {
        val target = myLocation
        if (centered && target != null) {
            runCatching {
                camera.animate(
                    CameraUpdateFactory.newLatLngZoom(target, targetZoom),
                    durationMs = 900,
                )
            }
        }
    }

    val mapStyle = remember {
        runCatching { MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark) }.getOrNull()
    }

    // NavIC (ISRO's constellation) satellite tracking — shows when Indian satellites are fixing us.
    val navicMonitor = remember { NavicMonitor(context) }
    val navic by navicMonitor.status.collectAsStateWithLifecycle()
    DisposableEffect(hasLocationPermission) {
        if (hasLocationPermission) navicMonitor.start()
        onDispose { navicMonitor.stop() }
    }

    var mapLoaded by remember { mutableStateOf(false) }
    var coverTimeout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2500); coverTimeout = true }
    val coverAlpha by animateFloatAsState(
        if (mapLoaded || coverTimeout) 0f else 1f, tween(400), label = "mapCover",
    )

    val orbState = when {
        sosSent -> OrbState.Listening
        peers.isNotEmpty() -> OrbState.Working
        else -> OrbState.Searching
    }

    // Share current location as a message. Best-effort: silently no-ops without permission/fix.
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    val sendLocation: () -> Unit = {
        if (hasLocationPermission) {
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    viewModel.sendMessage("\uD83D\uDCCD ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}")
                }
            }
        }
    }

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = SHEET_PEEK,
        sheetContainerColor = scheme.surface,
        sheetContentColor = scheme.onSurface,
        containerColor = scheme.background,
        sheetDragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = Space.m), contentAlignment = Alignment.Center) {
                GrabberHandle()
            }
        },
        sheetContent = {
            MeshSheet(
                messages = timeline,
                peers = peers,
                peerNicknames = peerNicknames,
                peerRSSI = peerRSSI,
                peerDirect = peerDirect,
                joinedChannels = joinedChannels,
                unreadChannels = unreadChannels,
                unreadPrivateCount = unreadPrivate.size,
                privateChatCount = privateChats.size,
                myNickname = nickname,
                onSend = { viewModel.sendMessage(it) },
                onSendImage = { viewModel.sendImageNote(null, null, it) },
                onSendVoice = { viewModel.sendVoiceNote(null, null, it) },
                onSendLocation = sendLocation,
                myPeerId = viewModel.meshService.myPeerID,
                onOpenChat = onOpenChat,
                onOpenPeers = onOpenPeers,
                onInspectMessage = { inspectMessage = it },
                isLiveStreaming = isLiveStreaming,
                onLiveCallToggle = toggleLiveCall,
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.matchParentSize(),
                cameraPositionState = camera,
                onMapLoaded = { mapLoaded = true },
                properties = MapProperties(mapStyleOptions = mapStyle, minZoomPreference = 3f),
                uiSettings = MapUiSettings(
                    compassEnabled = false,
                    mapToolbarEnabled = false,
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    rotationGesturesEnabled = false,
                    tiltGesturesEnabled = false,
                    indoorLevelPickerEnabled = false,
                ),
                // Keep the "you" marker centred in the visible band between the top console and
                // the sheet, so it isn't hidden behind either.
                contentPadding = PaddingValues(top = 172.dp, bottom = SHEET_PEEK),
            ) {
                myLocation?.let { here ->
                    // Mesh nodes around you. BLE gives signal strength but no bearing, so the
                    // distance is real (derived from RSSI) while the angle is a stable per-peer
                    // hash — the ring reads as "who is close", not as a survey-grade position.
                    peers.forEach { id ->
                        val node = projectPeer(here, id, peerRSSI[id])
                        Polyline(
                            points = listOf(here, node),
                            color = scheme.primary.copy(alpha = 0.30f),
                            width = 3f,
                        )
                        Circle(
                            center = node,
                            radius = 4.0,
                            fillColor = scheme.primary.copy(alpha = 0.75f),
                            strokeColor = scheme.onPrimary.copy(alpha = 0.55f),
                            strokeWidth = 2f,
                        )
                    }

                    // You: a small, solid marker rather than a large translucent blob.
                    Circle(
                        center = here,
                        radius = 7.0,
                        fillColor = scheme.primary,
                        strokeColor = scheme.onPrimary.copy(alpha = 0.85f),
                        strokeWidth = 3f,
                    )
                }
            }

            if (coverAlpha > 0.01f) {
                Box(Modifier.matchParentSize().background(scheme.background.copy(alpha = coverAlpha)))
            }

            // Top: brand + entry to the full chat, then the emergency console. Anchored to the
            // top so the map stays visible below and the console never sits over map content.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(Space.m),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Wordmark()
                    Spacer(Modifier.width(Space.s))
                    NavicChip(navic)
                    Spacer(Modifier.weight(1f))
                    MapButton(Icons.Filled.Settings, onClick = { showSettings = true })
                    Spacer(Modifier.width(Space.s))
                    // Offline AI reachable from home: asking "how do I splint an arm" shouldn't
                    // require detouring through the chat screen first.
                    MapButton(Icons.Filled.AutoAwesome, onClick = { showAria = true })
                }
                Spacer(Modifier.height(Space.s))
                SosConsole(
                    orbState = orbState,
                    sosSent = sosSent,
                    peerCount = peers.size,
                    onTrigger = { confirmSos = true },
                )
            }
        }
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            containerColor = scheme.surface,
            titleContentColor = scheme.onSurface,
            textContentColor = scheme.onSurfaceVariant,
            title = {
                Text("Broadcast SOS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Sends an emergency alert to every device on the mesh, relayed hop by hop. " +
                        "Peers in range: ${peers.size}.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSos = false
                    sosSent = true
                    viewModel.sendMessage("🆘 SOS — immediate assistance needed")
                }) {
                    Text(
                        "SEND",
                        color = scheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSos = false }) {
                    Text("CANCEL", color = scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
            },
        )
    }

    AkashaSettingsSheet(
        isPresented = showSettings,
        onDismiss = { showSettings = false },
        viewModel = viewModel,
        onShowDebug = { showDebug = true },
    )
    if (showDebug) {
        com.MeshLink.android.ui.debug.DebugSettingsSheet(
            isPresented = showDebug,
            onDismiss = { showDebug = false },
            meshService = viewModel.meshService,
        )
    }
    incomingCall?.let { callerPeerId ->
        val callerName = peerNicknames[callerPeerId] ?: callerPeerId.take(8)
        AlertDialog(
            onDismissRequest = { viewModel.rejectCall() },
            title = {
                Text(
                    "LIVE VOICE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            },
            text = {
                Text(
                    "@$callerName is broadcasting live to everyone in range. Join to listen?",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Joining runs the same path as starting a call, so the joiner both listens
                        // and transmits. Previously accepting only opened the receive side, which
                        // made every call one-way: the joiner could hear, but was never heard.
                        viewModel.acceptCall()
                        toggleLiveCall()
                    }
                ) {
                    Text("JOIN", color = scheme.primary, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.rejectCall() }) {
                    Text("IGNORE", color = scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = scheme.surface,
            titleContentColor = scheme.onSurface,
            textContentColor = scheme.onSurfaceVariant,
        )
    }

    inspectMessage?.let { msg ->
        com.MeshLink.android.ui.components.MessageSecurityDialog(
            message = msg,
            myPeerID = viewModel.meshService.myPeerID,
            onDismiss = { inspectMessage = null },
        )
    }
    if (showAria) {
        val ariaViewModel: com.MeshLink.android.ai.AriaViewModel =
            androidx.lifecycle.viewmodel.compose.viewModel()
        com.MeshLink.android.ui.AriaChatSheet(
            isPresented = showAria,
            onDismiss = { showAria = false },
            viewModel = ariaViewModel,
        )
    }
}

/** The docked SOS panel: dotted orb, state caption, and the trigger. */
@Composable
private fun SosConsole(
    orbState: OrbState,
    sosSent: Boolean,
    peerCount: Int,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.93f))
            .borderBeam(
                cornerRadius = 20.dp,
                color = scheme.primary,
                mode = if (sosSent) BeamMode.Travel else BeamMode.Pulse,
                durationMillis = if (sosSent) 1400 else 3200,
                strength = if (sosSent) 1f else 0.5f,
            )
            .bounceClick(onTrigger)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThoughtOrb(
            state = orbState,
            ink = if (sosSent) scheme.primary else scheme.onSurface,
            modifier = Modifier.size(62.dp),
        )
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
            Text(
                if (sosSent) "SOS BROADCASTING" else "EMERGENCY BROADCAST",
                color = if (sosSent) scheme.primary else scheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    sosSent -> "relaying across the network"
                    peerCount > 0 -> "tap to alert $peerCount nearby"
                    else -> "tap to alert — no peers yet"
                },
                color = scheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            // Morse "SOS" instead of an emoji — keys out while broadcasting.
            SosGlyph(
                color = scheme.onPrimary,
                animated = sosSent,
                modifier = Modifier.size(width = 26.dp, height = 14.dp),
            )
        }
    }
}

/** Sheet body: identity, live counters, peers with signal, channels, timeline, composer. */
@Composable
private fun MeshSheet(
    messages: List<MeshLinkMessage>,
    peers: List<String>,
    peerNicknames: Map<String, String>,
    peerRSSI: Map<String, Int>,
    peerDirect: Map<String, Boolean>,
    joinedChannels: Set<String>,
    unreadChannels: Map<String, Int>,
    unreadPrivateCount: Int,
    privateChatCount: Int,
    myNickname: String,
    onSend: (String) -> Unit,
    onSendImage: (String) -> Unit,
    onSendVoice: (String) -> Unit,
    onSendLocation: () -> Unit,
    myPeerId: String,
    onOpenChat: () -> Unit,
    onOpenPeers: () -> Unit,
    onInspectMessage: (MeshLinkMessage) -> Unit,
    isLiveStreaming: Boolean,
    onLiveCallToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    // The activity is adjustResize, so the window already shrinks when the keyboard opens and the
    // sheet re-measures its peek against the smaller height — that alone lifts the composer.
    // Applying imePadding() here as well would double-count the keyboard inset and push content
    // off-screen, so only the navigation-bar inset is handled locally.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.l)
            .navigationBarsPadding(),
    ) {
        // --- Identity header ---
        Text(
            if (peers.isEmpty()) "No peers in range" else "${peers.size} ${if (peers.size == 1) "peer" else "peers"} nearby",
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "you are @${myNickname.ifBlank { "anon" }}",
            color = scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )

        // --- Composer: right below the header, always reachable without scrolling ---
        Spacer(Modifier.height(Space.m))
        Composer(
            onSend = onSend,
            onSendImage = onSendImage,
            onSendVoice = onSendVoice,
            onSendLocation = onSendLocation,
            isLiveStreaming = isLiveStreaming,
            onLiveCallToggle = onLiveCallToggle,
        )

        // --- Messages: fixed band, newest at the bottom near the composer; scroll up for older ---
        Spacer(Modifier.height(Space.l))
        SectionHeader("messages")
        Spacer(Modifier.height(Space.s))

        if (messages.isEmpty()) {
            Text(
                "Nothing yet. Messages from nearby devices land here, no internet required.",
                color = scheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = Space.s),
            )
            // (network, not internet — mesh works offline)
        } else {
            // Newest first, at the top right under the composer. When a message arrives we snap
            // back to the top so the new one is always the first thing you see.
            val listState = rememberLazyListState()
            val newest = messages.lastOrNull()?.id
            LaunchedEffect(newest) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(0)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                items(messages.asReversed(), key = { it.id }) { msg ->
                    val isSelf = msg.senderPeerID == myPeerId ||
                        msg.sender == myNickname ||
                        msg.sender.startsWith("$myNickname#")
                    MessageRow(msg, isSelf, onInspect = onInspectMessage, modifier = Modifier.animateItem())
                }
            }
        }

        // --- Everything below is secondary: stats, nearby peers, channels. Scroll down for it. ---
        Spacer(Modifier.height(Space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("PEERS", peers.size.toString(), Modifier.weight(1f))
            StatTile("CHANNELS", joinedChannels.size.toString(), Modifier.weight(1f))
            StatTile("DMS", privateChatCount.toString(), Modifier.weight(1f))
            StatTile("UNREAD", (unreadPrivateCount + unreadChannels.values.sum()).toString(), Modifier.weight(1f))
        }

        if (peers.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("nearby", Modifier.weight(1f))
                Text(
                    "see all",
                    color = scheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.bounceClick(onOpenPeers),
                )
            }
            Spacer(Modifier.height(Space.s))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                peers.forEach { id ->
                    PeerChip(
                        name = peerNicknames[id] ?: id.take(8),
                        rssi = peerRSSI[id],
                        direct = peerDirect[id] == true,
                    )
                }
            }
        }

        if (joinedChannels.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            SectionHeader("channels")
            Spacer(Modifier.height(Space.s))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                joinedChannels.forEach { ch ->
                    ChannelChip(name = ch, unread = unreadChannels[ch] ?: 0, onClick = onOpenChat)
                }
            }
        }

        Spacer(Modifier.height(Space.xl))
    }
}

/**
 * Send to the mesh without leaving home. One dynamic control lives inside the field: with no text
 * it's an attach button (photo / camera / file); once you type it becomes the send action.
 */
@Composable
private fun Composer(
    onSend: (String) -> Unit,
    onSendImage: (String) -> Unit,
    onSendVoice: (String) -> Unit,
    onSendLocation: () -> Unit,
    isLiveStreaming: Boolean,
    onLiveCallToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(14.dp)
    val canSend = text.isNotBlank()

    fun send() {
        if (text.isNotBlank()) {
            onSend(text.trim())
            text = ""
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
            .borderBeam(
                cornerRadius = 14.dp,
                color = scheme.primary,
                mode = BeamMode.Travel,
                active = canSend,
                durationMillis = 2200,
                strength = 0.85f,
            )
            .then(if (canSend) Modifier else Modifier.hairline(shape))
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ">",
            color = scheme.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.width(Space.s))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(
                    "broadcast to network",
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(
                    color = scheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(scheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(Space.s))
        // Location pin is always available — one tap shares your coordinates to the network.
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = "Share location",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).bounceClick { onSendLocation() },
        )
        Spacer(Modifier.width(Space.m))
        // With text: send. Empty: a voice button and a media button (photo / video).
        if (canSend) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = scheme.primary,
                modifier = Modifier.size(20.dp).bounceClick { send() },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Live voice broadcast: hold-free, hands-free talking to everyone in range. Turns
                // into a hang-up affordance while transmitting.
                Icon(
                    imageVector = if (isLiveStreaming) Icons.Filled.CallEnd else Icons.Filled.Call,
                    contentDescription = if (isLiveStreaming) "End live call" else "Start live call",
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp).bounceClick { onLiveCallToggle() },
                )
                Spacer(Modifier.width(Space.s))
                VoiceRecordButton(
                    backgroundColor = scheme.primary,
                    onStart = {},
                    onAmplitude = { _, _ -> },
                    onFinish = { path -> onSendVoice(path) },
                )
                Spacer(Modifier.width(Space.s))
                MediaControl(onSendImage = onSendImage)
            }
        }
    }
}

/**
 * Photo attachment button.
 *
 * Photos only. Video sending was removed: a clip is megabytes, the mesh splits a frame into
 * 469-byte fragments with no retransmission, so a single dropped fragment loses the whole
 * transfer. Offering a control that cannot realistically deliver is worse than not offering it.
 */
@Composable
private fun MediaControl(
    onSendImage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    val imagePicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val out = com.MeshLink.android.features.media.ImageUtils.downscaleAndSaveToAppFiles(context, uri)
            if (!out.isNullOrBlank()) onSendImage(out)
        }
    }

    Icon(
        Icons.Filled.PermMedia,
        contentDescription = "Send photo",
        tint = scheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp).bounceClick { imagePicker.launch("image/*") },
    )
}

/** A compact labelled counter. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.45f))
            .hairline(shape)
            .padding(vertical = Space.s, horizontal = Space.s),
    ) {
        Text(
            value,
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Text(
            label,
            color = scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
        )
    }
}

/** A peer with a 4-bar signal read-out derived from RSSI. */
@Composable
private fun PeerChip(name: String, rssi: Int?, direct: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    // Typical BLE range: -30 dBm (touching) to -100 dBm (edge of range).
    val bars = when {
        rssi == null -> 0
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    Row(
        Modifier
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .hairline(shape)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            for (i in 1..4) {
                Box(
                    Modifier
                        .padding(end = 1.5.dp)
                        .size(width = 2.5.dp, height = (4 + i * 2).dp)
                        .background(
                            if (i <= bars) scheme.primary
                            else scheme.onSurfaceVariant.copy(alpha = 0.28f)
                        ),
                )
            }
        }
        Spacer(Modifier.width(Space.s))
        Text(
            name,
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (direct) {
            Spacer(Modifier.width(Space.xs))
            Text("·1hop", color = scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ChannelChip(name: String, unread: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .hairline(shape)
            .bounceClick(onClick)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
        )
        if (unread > 0) {
            Spacer(Modifier.width(Space.s))
            Box(
                Modifier.clip(CircleShape).background(scheme.primary).padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    unread.toString(),
                    color = scheme.onPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

/**
 * One timeline entry, WhatsApp-style: your own messages hug the right in a red-tinted bubble,
 * everyone else sits on the left with a per-sender colour so the crowd stays legible. SOS traffic
 * overrides the colour with the alert red and a travelling beam, whichever side it's on.
 */
@Composable
private fun MessageRow(
    msg: MeshLinkMessage,
    isSelf: Boolean,
    onInspect: (MeshLinkMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val isSos = msg.content.trimStart().startsWith("🆘")
    val time = remember(msg.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(msg.timestamp)
    }
    val accent = when {
        isSos -> scheme.primary
        isSelf -> scheme.primary
        else -> senderColor(msg.sender)
    }
    // Asymmetric corners point the bubble toward its side, like a chat tail.
    val shape = if (isSelf) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 14.dp)
    }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .clickable { onInspect(msg) }
                .background(accent.copy(alpha = if (isSos) 0.16f else 0.13f))
                .then(
                    if (isSos) Modifier.borderBeam(
                        cornerRadius = 14.dp,
                        color = scheme.primary,
                        mode = BeamMode.Travel,
                        durationMillis = 1800,
                    ) else Modifier.hairline(shape)
                )
                .padding(horizontal = Space.m, vertical = Space.s),
        ) {
            // Header: SOS badge + sender (others only) + time.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSos) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(scheme.primary)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        SosGlyph(
                            color = scheme.onPrimary,
                            animated = true,
                            modifier = Modifier.size(width = 22.dp, height = 10.dp),
                        )
                    }
                    Spacer(Modifier.width(Space.s))
                }
                if (!isSelf) {
                    Text(
                        msg.sender,
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(Space.s))
                }
                Text(
                    time,
                    color = scheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                // Authenticity marker. Tapping the bubble explains what it means.
                if (!isSelf) {
                    Spacer(Modifier.width(4.dp))
                    com.MeshLink.android.ui.components.TrustBadge(msg.trustState, size = 11)
                }
            }
            Spacer(Modifier.height(3.dp))
            // For media messages `content` holds a file path, not text — render the payload
            // rather than printing the path.
            when (msg.type) {
                MeshLinkMessageType.Image -> MediaImage(msg.content.trim(), msg.isSensitiveMedia)
                MeshLinkMessageType.Audio -> MediaChip(Icons.Filled.GraphicEq, "voice note")
                MeshLinkMessageType.File -> {
                    val name = msg.content.trim().substringAfterLast('/')
                    MediaChip(Icons.Filled.Attachment, name.ifBlank { "file" })
                }
                else -> Text(
                    if (isSos) msg.content.trimStart().removePrefix("\uD83C\uDD98").trim() else msg.content,
                    color = scheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }

            // A shared pin is only useful if you can act on it, so give it a direct route to a map.
            // Its own clickable takes priority over the bubble's inspect gesture.
            val coords = remember(msg.id, msg.content) {
                com.MeshLink.android.ui.components.MapsLauncher.coordinatesOf(msg)
            }
            if (coords != null) {
                Spacer(Modifier.height(Space.s))
                Text(
                    "open in Maps",
                    color = scheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        com.MeshLink.android.ui.components.MapsLauncher.open(
                            context = context,
                            latitude = coords.first,
                            longitude = coords.second,
                            label = if (isSelf) "My shared location" else "${msg.sender}'s location",
                        )
                    },
                )
            }
        }
    }
}

/**
 * Decode and show an image payload from its on-disk path.
 *
 * When [sensitive] is set the image is blurred behind a tap-to-reveal cover rather than hidden. The
 * user decides — the classifier only warns, because a wrong call here would otherwise suppress the
 * injury photos this app exists to carry.
 */
@Composable
private fun MediaImage(path: String, sensitive: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val bmp = remember(path) {
        runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
    }

    // Reveal state resets per image, and re-blurs if the row is recycled.
    var revealed by remember(path) { mutableStateOf(false) }

    if (bmp == null) {
        // Still arriving, or the file isn't readable yet.
        MediaChip(Icons.Filled.Photo, "image…")
        return
    }

    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .widthIn(max = 260.dp)
            .heightIn(max = 260.dp)
            .clip(shape),
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = if (sensitive) "Possibly explicit image" else "Image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // 18dp is heavy enough that content isn't discernible, while still showing
                    // enough shape/colour for the recipient to judge whether to reveal it.
                    if (sensitive && !revealed) Modifier.blur(18.dp) else Modifier
                ),
        )

        if (sensitive && !revealed) {
            Column(
                Modifier
                    .matchParentSize()
                    .background(scheme.background.copy(alpha = 0.55f))
                    .clickable { revealed = true }
                    .padding(Space.m),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(Space.s))
                Text(
                    "possibly explicit",
                    color = scheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "tap to view",
                    color = scheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** Compact row used for non-image payloads (voice, files). */
@Composable
private fun MediaChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.s))
        Text(
            label,
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A stable, muted colour per sender so different people are visually separable without turning the
 * timeline into confetti. Red is intentionally excluded — it's reserved for SOS.
 */
private val senderPalette = listOf(
    Color(0xFF7FA6B0), // slate blue
    Color(0xFF9C8AB0), // muted violet
    Color(0xFF8FB08A), // sage
    Color(0xFFB0A47F), // sand
    Color(0xFF7FB0A3), // teal
    Color(0xFFB08F9C), // dusty rose
    Color(0xFF9AA0B0), // steel
    Color(0xFFB0A05C), // ochre
)

private fun senderColor(sender: String): Color =
    senderPalette[(sender.hashCode() and Int.MAX_VALUE) % senderPalette.size]

@Composable
private fun MapButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.92f))
            .hairline(shape)
            .bounceClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

/**
 * The Akasha wordmark. Sits over the map, so it carries its own scrim panel to stay legible on
 * any tile. The trailing slash echoes the terminal feel of the rest of the type.
 */
@Composable
private fun Wordmark() {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.92f))
            .hairline(shape)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name).uppercase(),
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            letterSpacing = 3.sp,
        )
        Text(
            text = "/",
            color = scheme.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
    }
}

/**
 * Place a peer on the map relative to [origin].
 *
 * BLE gives signal strength but no direction, so only the distance is real — estimated from RSSI
 * via the usual log-distance path-loss form. The bearing is a stable hash of the peer ID, so a peer
 * keeps the same spot between frames instead of jittering. The ring answers "who is close", not
 * "where exactly are they".
 */
private fun projectPeer(origin: LatLng, peerId: String, rssi: Int?): LatLng {
    val bearing = ((peerId.hashCode().toDouble() % 360.0) + 360.0) % 360.0
    return offsetMeters(origin, displayMeters(rssi), bearing)
}

/**
 * Minimum distance a peer is drawn at, in metres.
 *
 * Signal strength can put a peer 4 m away, which at a legible zoom renders on top of your own dot.
 * Since the bearing is already a stable hash rather than a real direction, this ring is a legibility
 * device, not a survey — so nudging very close peers outward costs no accuracy that was there to
 * begin with, and avoids zooming so far in that the map loses all context.
 */
private const val MIN_DISPLAY_METERS = 18.0

/** Radius a peer is plotted at: its estimated distance, floored for legibility. */
private fun displayMeters(rssi: Int?): Double = rssiMeters(rssi).coerceAtLeast(MIN_DISPLAY_METERS)

/** Estimated distance to a peer from its RSSI, via the log-distance path-loss form. */
private fun rssiMeters(rssi: Int?): Double {
    if (rssi == null) return 30.0
    // -55 dBm ≈ touching, -100 dBm ≈ edge of range. Clamp to a sane 4..45 m for display.
    return (10.0.pow((-55 - rssi) / 22.0) * 4.0).coerceIn(4.0, 45.0)
}

/**
 * Pick a zoom that frames the cluster while keeping the surroundings readable.
 *
 * Previously this went to zoom 20 for peers in the same room, which fills the screen with roughly
 * 30 m of ground: the node dots separate nicely but every street name and building outline vanishes,
 * so the map stops telling you where you are. Capped at 18 instead, with [MIN_DISPLAY_METERS] keeping
 * nearby nodes visually apart at that distance rather than solving it by zooming further in.
 */
private fun zoomForRadius(meters: Double): Float = when {
    meters <= 20.0 -> 18.0f
    meters <= 30.0 -> 17.6f
    meters <= 45.0 -> 17.2f
    else -> 16.8f
}

/** A LatLng [meters] away from [center] along [bearingDeg], on a spherical earth. */
private fun offsetMeters(center: LatLng, meters: Double, bearingDeg: Double): LatLng {
    val angular = meters / 6_378_137.0
    val bearing = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(center.latitude)
    val lon1 = Math.toRadians(center.longitude)
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angular) * cos(lat1),
        cos(angular) - sin(lat1) * sin(lat2),
    )
    return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

/**
 * NavIC indicator. Only appears once Indian satellites are actually being tracked, so it never
 * makes a claim the hardware isn't backing up — indoors it stays hidden rather than showing zero.
 */
@Composable
private fun NavicChip(navic: NavicMonitor.GnssSnapshot) {
    if (navic.navicVisible == 0) return
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val live = navic.navicContributing

    Row(
        Modifier
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.92f))
            .hairline(shape)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (live) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(Space.s))
        Text(
            "NAVIC ${if (live) navic.navicInFix else navic.navicVisible}",
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}

/** Live mesh status: a dot plus state label. */
@Composable
private fun StatusPill(peerCount: Int, sosActive: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.92f))
            .then(
                if (sosActive) Modifier.borderBeam(
                    cornerRadius = 999.dp,
                    color = scheme.primary,
                    mode = BeamMode.Pulse,
                    durationMillis = 900,
                ) else Modifier.hairline(shape)
            )
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    when {
                        sosActive -> scheme.primary
                        peerCount > 0 -> scheme.primary
                        else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                ),
        )
        Spacer(Modifier.width(Space.s))
        Text(
            when {
                sosActive -> "SOS ACTIVE"
                peerCount > 0 -> "MESH LIVE"
                else -> "SEARCHING"
            },
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}
