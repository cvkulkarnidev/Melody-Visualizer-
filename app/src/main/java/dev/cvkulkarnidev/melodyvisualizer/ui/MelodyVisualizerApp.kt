package dev.cvkulkarnidev.melodyvisualizer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cvkulkarnidev.melodyvisualizer.MainViewModel
import dev.cvkulkarnidev.melodyvisualizer.MelodyUiState
import dev.cvkulkarnidev.melodyvisualizer.PitchTrailPoint
import dev.cvkulkarnidev.melodyvisualizer.music.MusicNote
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.Aqua
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.Ink
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.Mint
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.SurfaceDeep
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.SurfaceRaised
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.TextPrimary
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.TextSecondary
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.Violet
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.VioletBright

private enum class Destination { Home, Live, Song }

@Composable
fun MelodyVisualizerApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var destinationName by rememberSaveable { mutableStateOf(Destination.Home.name) }
    var microphoneGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val destination = Destination.valueOf(destinationName)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        microphoneGranted = granted
        if (granted) viewModel.startListening()
    }

    fun goHome() {
        viewModel.stopListening()
        destinationName = Destination.Home.name
    }

    BackHandler(enabled = destination != Destination.Home, onBack = ::goHome)

    LaunchedEffect(destination, microphoneGranted) {
        if (destination == Destination.Live && microphoneGranted) {
            viewModel.startListening()
        }
    }
    DisposableEffect(destination) {
        onDispose {
            if (destination == Destination.Live) viewModel.stopListening()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF252044), Ink, Ink),
                    radius = 1_100f,
                    center = Offset(220f, 0f),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (destination) {
            Destination.Home -> HomeScreen(
                onSingClick = {
                    destinationName = Destination.Live.name
                    if (!microphoneGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onSongClick = { destinationName = Destination.Song.name },
            )

            Destination.Live -> LiveScreen(
                state = uiState,
                microphoneGranted = microphoneGranted,
                onBack = ::goHome,
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStart = viewModel::startListening,
                onStop = viewModel::stopListening,
                onPianoEnabledChange = viewModel::setPianoEnabled,
                onPlayCurrentNote = viewModel::playCurrentNote,
                onClearError = viewModel::clearError,
            )

            Destination.Song -> SongModePreview(onBack = ::goHome)
        }
    }
}

@Composable
private fun HomeScreen(
    onSingClick: () -> Unit,
    onSongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Violet),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Ink)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "MELODY VISUALIZER",
                color = VioletBright,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.5.sp,
            )
        }

        Spacer(Modifier.height(42.dp))
        Text(
            text = "Turn any melody\ninto piano notes.",
            color = TextPrimary,
            fontSize = 38.sp,
            lineHeight = 43.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Sing, hum, or let your phone listen. We’ll show you exactly which keys to play.",
            color = TextSecondary,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )

        Spacer(Modifier.height(34.dp))
        ModeCard(
            icon = Icons.Rounded.Mic,
            eyebrow = "LIVE · READY",
            title = "Sing or hum",
            description = "See your melody appear on the piano as you sing.",
            accent = Violet,
            onClick = onSingClick,
        )
        Spacer(Modifier.height(14.dp))
        ModeCard(
            icon = Icons.Rounded.GraphicEq,
            eyebrow = "ON-DEVICE MODEL · NEXT",
            title = "Listen to a song",
            description = "Isolate vocals and turn a recorded song into playable notes.",
            accent = Aqua,
            onClick = onSongClick,
        )

        Spacer(Modifier.height(26.dp))
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.055f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                .padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
            Text(
                text = "Private by design · audio stays on this phone",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    description: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDeep.copy(alpha = 0.92f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(17.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = eyebrow,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.1.sp,
                )
                Spacer(Modifier.height(5.dp))
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Spacer(Modifier.height(5.dp))
                Text(description, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Text(text = "›", color = accent, fontSize = 32.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun LiveScreen(
    state: MelodyUiState,
    microphoneGranted: Boolean,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPianoEnabledChange: (Boolean) -> Unit,
    onPlayCurrentNote: () -> Unit,
    onClearError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        TopBar(title = "Sing or hum", onBack = onBack, live = state.isListening)
        Spacer(Modifier.height(14.dp))

        if (!microphoneGranted) {
            PermissionCard(onRequestPermission)
            return@Column
        }

        state.errorMessage?.let { message ->
            ErrorCard(message = message, onDismiss = onClearError)
            Spacer(Modifier.height(12.dp))
        }

        CurrentPitchCard(state)
        Spacer(Modifier.height(12.dp))

        val keyboardStartMidi = keyboardStartMidi(state.note?.midi)
        PianoRoll(
            trail = state.trail,
            startMidi = keyboardStartMidi,
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
        )
        PianoKeyboard(
            highlightedMidi = state.note?.midi,
            startMidi = keyboardStartMidi,
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp),
        )

        Spacer(Modifier.height(14.dp))
        RecentNotes(notes = state.recentNotes)
        Spacer(Modifier.height(14.dp))
        ListeningControls(
            state = state,
            onStart = onStart,
            onStop = onStop,
            onPianoEnabledChange = onPianoEnabledChange,
            onPlayCurrentNote = onPlayCurrentNote,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Tip: hum one note at a time, about 15–30 cm from the microphone. Use headphones before enabling automatic piano sound.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, live: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }
        Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Spacer(Modifier.weight(1f))
        if (live) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Mint.copy(alpha = 0.12f))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Mint))
                Text("LISTENING", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDeep),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(Violet.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null, tint = Violet, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("Microphone access", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "The microphone is used only for live pitch detection. Nothing is uploaded or saved.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Ink),
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Allow microphone", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(onClick = onDismiss)
            .padding(14.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("×", color = MaterialTheme.colorScheme.error, fontSize = 18.sp)
    }
}

@Composable
private fun CurrentPitchCard(state: MelodyUiState) {
    val noteVisible = state.note != null && state.confidence > 0f
    val accent by animateColorAsState(if (noteVisible) Violet else TextSecondary, label = "note accent")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDeep.copy(alpha = 0.94f)),
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InputLevel(level = state.inputLevel, active = state.isListening)
                Text(
                    text = if (noteVisible) "NOTE DETECTED" else "HUM A STEADY NOTE",
                    color = if (noteVisible) Mint else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (noteVisible) state.note!!.name else "—",
                color = accent,
                fontSize = 66.sp,
                lineHeight = 72.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-2).sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    text = state.frequencyHz?.let { "%.1f Hz".format(it) } ?: "Waiting for voice",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                if (noteVisible) {
                    Text(
                        text = when {
                            state.cents > 3 -> "+${state.cents} cents sharp"
                            state.cents < -3 -> "${state.cents} cents flat"
                            else -> "In tune"
                        },
                        color = if (kotlin.math.abs(state.cents) <= 8) Mint else Aqua,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            PitchMeter(cents = state.cents, visible = noteVisible)
        }
    }
}

@Composable
private fun InputLevel(level: Float, active: Boolean) {
    val animated by animateFloatAsState(if (active) level else 0f, label = "input level")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(4) { index ->
            val threshold = (index + 1) / 4f
            Box(
                Modifier
                    .width(3.dp)
                    .height((5 + index * 3).dp)
                    .clip(CircleShape)
                    .background(if (animated >= threshold * 0.55f) Mint else TextSecondary.copy(alpha = 0.25f)),
            )
        }
    }
}

@Composable
private fun PitchMeter(cents: Int, visible: Boolean) {
    val animatedCents by animateFloatAsState(if (visible) cents.toFloat() else 0f, label = "pitch cents")
    Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
        val centerY = size.height / 2f
        drawLine(
            color = Color.White.copy(alpha = 0.11f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Mint.copy(alpha = 0.6f),
            start = Offset(size.width * 0.44f, centerY),
            end = Offset(size.width * 0.56f, centerY),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val markerX = size.width * (0.5f + animatedCents.coerceIn(-50f, 50f) / 110f)
        drawCircle(
            color = if (visible) VioletBright else TextSecondary,
            radius = 7.dp.toPx(),
            center = Offset(markerX, centerY),
        )
        drawCircle(
            color = Ink,
            radius = 2.5.dp.toPx(),
            center = Offset(markerX, centerY),
        )
    }
}

@Composable
private fun PianoRoll(
    trail: List<PitchTrailPoint>,
    startMidi: Int,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Color(0xFF10121D)),
    ) {
        val laneWidth = size.width / 24f
        for (lane in 0..24) {
            val isOctave = lane % 12 == 0
            drawLine(
                color = Color.White.copy(alpha = if (isOctave) 0.12f else 0.045f),
                start = Offset(lane * laneWidth, 0f),
                end = Offset(lane * laneWidth, size.height),
                strokeWidth = if (isOctave) 1.2.dp.toPx() else 0.7.dp.toPx(),
            )
        }
        repeat(3) { index ->
            val y = size.height * (index + 1) / 4f
            drawLine(
                color = Color.White.copy(alpha = 0.06f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 9f)),
            )
        }

        trail.forEach { point ->
            val age = now - point.timestampMillis
            if (age in 0..TRAIL_WINDOW_MILLIS && point.midi in startMidi until startMidi + 24) {
                val x = (point.midi - startMidi) * laneWidth + laneWidth * 0.12f
                val y = size.height - age.toFloat() / TRAIL_WINDOW_MILLIS * size.height
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(Violet, Aqua)),
                    topLeft = Offset(x, (y - 5.dp.toPx()).coerceAtLeast(0f)),
                    size = Size(laneWidth * 0.76f, 11.dp.toPx()),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                    alpha = (1f - age.toFloat() / TRAIL_WINDOW_MILLIS).coerceIn(0.18f, 1f),
                )
            }
        }

        drawLine(
            color = VioletBright,
            start = Offset(0f, size.height - 2.dp.toPx()),
            end = Offset(size.width, size.height - 2.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

@Composable
private fun PianoKeyboard(
    highlightedMidi: Int?,
    startMidi: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
            .background(Color(0xFFE8E8F0)),
    ) {
        val whiteNotes = (startMidi until startMidi + 24).filter { !MusicNote.fromMidi(it).isBlackKey }
        val whiteKeyWidth = size.width / whiteNotes.size
        val whiteKeyHeight = size.height

        whiteNotes.forEachIndexed { index, midi ->
            val highlighted = highlightedMidi == midi
            drawRect(
                color = if (highlighted) VioletBright else Color(0xFFF4F3F8),
                topLeft = Offset(index * whiteKeyWidth, 0f),
                size = Size(whiteKeyWidth, whiteKeyHeight),
            )
            drawRect(
                color = Color(0xFF8E90A1).copy(alpha = 0.48f),
                topLeft = Offset(index * whiteKeyWidth, 0f),
                size = Size(whiteKeyWidth, whiteKeyHeight),
                style = Stroke(width = 0.75.dp.toPx()),
            )
        }

        val blackWidth = whiteKeyWidth * 0.62f
        val blackHeight = size.height * 0.61f
        (startMidi until startMidi + 24).filter { MusicNote.fromMidi(it).isBlackKey }.forEach { midi ->
            val whiteBefore = whiteNotes.count { it < midi }
            val left = whiteBefore * whiteKeyWidth - blackWidth / 2f
            val highlighted = highlightedMidi == midi
            drawRoundRect(
                color = if (highlighted) Violet else Color(0xFF151622),
                topLeft = Offset(left, 0f),
                size = Size(blackWidth, blackHeight),
                cornerRadius = CornerRadius(0f, 5.dp.toPx()),
            )
        }

        highlightedMidi?.takeIf { it in startMidi until startMidi + 24 }?.let { midi ->
            val note = MusicNote.fromMidi(midi)
            val centerX = if (note.isBlackKey) {
                val whiteBefore = whiteNotes.count { it < midi }
                whiteBefore * whiteKeyWidth
            } else {
                (whiteNotes.indexOf(midi) + 0.5f) * whiteKeyWidth
            }
            val baseline = if (note.isBlackKey) blackHeight - 10.dp.toPx() else size.height - 12.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(
                note.name,
                centerX,
                baseline,
                Paint().apply {
                    color = if (note.isBlackKey) android.graphics.Color.WHITE else android.graphics.Color.rgb(52, 43, 82)
                    textAlign = Paint.Align.CENTER
                    textSize = 10.sp.toPx()
                    isFakeBoldText = true
                    isAntiAlias = true
                },
            )
        }
    }
}

@Composable
private fun RecentNotes(notes: List<MusicNote>) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RECENT NOTES", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("newest →", color = TextSecondary.copy(alpha = 0.65f), fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.White.copy(alpha = 0.035f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Your melody will appear here", color = TextSecondary, fontSize = 12.sp)
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                itemsIndexed(notes, key = { index, note -> "$index-${note.midi}" }) { _, note ->
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Violet.copy(alpha = 0.13f))
                            .border(1.dp, Violet.copy(alpha = 0.22f), RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(note.name, color = VioletBright, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningControls(
    state: MelodyUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPianoEnabledChange: (Boolean) -> Unit,
    onPlayCurrentNote: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceRaised.copy(alpha = 0.88f)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Violet.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.pianoEnabled) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                        contentDescription = null,
                        tint = VioletBright,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Automatic piano sound", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Headphones recommended", color = TextSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = state.pianoEnabled,
                    onCheckedChange = onPianoEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ink,
                        checkedTrackColor = Violet,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.1f),
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = if (state.isListening) onStop else onStart,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isListening) Color.White.copy(alpha = 0.09f) else Violet,
                        contentColor = if (state.isListening) TextPrimary else Ink,
                    ),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Icon(if (state.isListening) Icons.Rounded.Stop else Icons.Rounded.Mic, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.isListening) "Stop" else "Start listening", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onPlayCurrentNote,
                    enabled = state.note != null,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Aqua.copy(alpha = 0.14f),
                        contentColor = Aqua,
                        disabledContainerColor = Color.White.copy(alpha = 0.04f),
                        disabledContentColor = TextSecondary.copy(alpha = 0.45f),
                    ),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Play note", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SongModePreview(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
    ) {
        TopBar(title = "Listen to a song", onBack = onBack)
        Spacer(Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDeep),
            shape = RoundedCornerShape(28.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Aqua.copy(alpha = 0.16f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(74.dp).clip(CircleShape).background(Aqua.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Aqua, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text("On-device song mode", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "This mode needs a compact vocal-separation model before it can reliably identify the lead melody. It will be added without uploading your recordings.",
                    color = TextSecondary,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.clip(CircleShape).background(Aqua.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
                    Text("Planned as fully offline", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "The live Sing or hum mode is ready to test now. Its pitch and note-cleaning results will guide the transcription stage used here.",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

private fun keyboardStartMidi(highlightedMidi: Int?): Int {
    val midi = highlightedMidi ?: 69
    val currentOctaveC = midi / 12 * 12
    return (currentOctaveC - 12).coerceIn(24, 96)
}

private const val TRAIL_WINDOW_MILLIS = 6_000L
