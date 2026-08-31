package dev.cvkulkarnidev.melodyvisualizer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.cvkulkarnidev.melodyvisualizer.AnalysisStage
import dev.cvkulkarnidev.melodyvisualizer.MainViewModel
import dev.cvkulkarnidev.melodyvisualizer.MelodyUiState
import dev.cvkulkarnidev.melodyvisualizer.music.DetectedNoteEvent
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
import kotlin.math.max
import kotlin.math.roundToInt

private enum class Destination { Home, Record, Result }

@Composable
fun MelodyVisualizerApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var destinationName by rememberSaveable { mutableStateOf(Destination.Home.name) }
    var microphoneGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val destination = Destination.valueOf(destinationName)

    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> microphoneGranted = granted }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.analyzeUploadedAudio(uri)
            destinationName = Destination.Result.name
        }
    }

    fun goHome() {
        viewModel.reset()
        destinationName = Destination.Home.name
    }

    BackHandler(enabled = destination != Destination.Home) {
        if (state.isRecording) viewModel.cancelRecording()
        goHome()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF28214B), Ink, Ink),
                    radius = 1_150f,
                    center = Offset(200f, 0f),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (destination) {
            Destination.Home -> HomeScreen(
                onRecord = {
                    destinationName = Destination.Record.name
                    if (!microphoneGranted) {
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onUpload = { uploadLauncher.launch(arrayOf("audio/*")) },
            )

            Destination.Record -> RecordingScreen(
                state = state,
                microphoneGranted = microphoneGranted,
                onBack = ::goHome,
                onRequestPermission = {
                    microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStart = viewModel::startRecording,
                onCancel = viewModel::cancelRecording,
                onDone = {
                    viewModel.finishRecordingAndAnalyze()
                    destinationName = Destination.Result.name
                },
                onClearError = viewModel::clearError,
            )

            Destination.Result -> ResultScreen(
                state = state,
                onBack = ::goHome,
                onPlay = viewModel::playMelody,
                onStop = viewModel::stopPlayback,
                onSelectNote = viewModel::selectNote,
                onRecordAgain = {
                    viewModel.reset()
                    destinationName = Destination.Record.name
                },
                onUploadAnother = {
                    viewModel.reset()
                    uploadLauncher.launch(arrayOf("audio/*"))
                },
                onClearError = viewModel::clearError,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    onRecord: () -> Unit,
    onUpload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Brand()
        Spacer(Modifier.height(42.dp))
        Text(
            text = "Hum it.\nSee the piano notes.",
            color = TextPrimary,
            fontSize = 38.sp,
            lineHeight = 43.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "First finish your recording. Then the app analyzes the complete melody on your phone.",
            color = TextSecondary,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )

        Spacer(Modifier.height(34.dp))
        ActionCard(
            icon = Icons.Rounded.Mic,
            eyebrow = "RECORD IN THE APP",
            title = "Record humming",
            description = "Tap the mic, hum your melody, then press Done to create piano notes.",
            accent = Violet,
            onClick = onRecord,
        )
        Spacer(Modifier.height(14.dp))
        ActionCard(
            icon = Icons.Rounded.UploadFile,
            eyebrow = "CHOOSE AN AUDIO FILE",
            title = "Upload audio",
            description = "Select an existing humming, singing, or whistling recording.",
            accent = Aqua,
            onClick = onUpload,
        )

        Spacer(Modifier.height(26.dp))
        PrivacyPill()
        Spacer(Modifier.height(18.dp))
        Text(
            text = "For the clearest notes, record one melody without background music. Mixed-song vocal isolation will be added separately.",
            color = TextSecondary.copy(alpha = 0.82f),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Violet),
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
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    description: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDeep.copy(alpha = 0.93f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(19.dp)).background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(17.dp))
            Column(Modifier.weight(1f)) {
                Text(eyebrow, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Spacer(Modifier.height(5.dp))
                Text(title, color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(description, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Text("›", color = accent, fontSize = 32.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun PrivacyPill() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Your audio is processed only on this phone", color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun RecordingScreen(
    state: MelodyUiState,
    microphoneGranted: Boolean,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onClearError: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        TopBar("Record humming", onBack)
        Spacer(Modifier.height(28.dp))

        if (!microphoneGranted) {
            PermissionCard(onRequestPermission)
            return@Column
        }

        state.errorMessage?.let {
            ErrorCard(it, onClearError)
            Spacer(Modifier.height(16.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (state.isRecording) "RECORDING" else "READY TO RECORD",
                color = if (state.isRecording) Color(0xFFFF8C9A) else TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = formatDuration(state.recordingDurationMillis),
                color = TextPrimary,
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.height(28.dp))
            RecordingWaveform(level = state.recordingLevel, active = state.isRecording)
            Spacer(Modifier.height(34.dp))

            Box(
                modifier = Modifier
                    .size(116.dp)
                    .clip(CircleShape)
                    .background(if (state.isRecording) Color(0xFFFF7185).copy(alpha = 0.16f) else Violet.copy(alpha = 0.14f))
                    .border(2.dp, if (state.isRecording) Color(0xFFFF7185) else Violet, CircleShape)
                    .clickable(enabled = !state.isRecording, onClick = onStart),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (state.isRecording) 44.dp else 72.dp)
                        .clip(if (state.isRecording) RoundedCornerShape(12.dp) else CircleShape)
                        .background(if (state.isRecording) Color(0xFFFF7185) else Violet),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isRecording) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
                        contentDescription = null,
                        tint = Ink,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = if (state.isRecording) "Hum your melody, then tap Done" else "Tap to start recording",
                color = TextSecondary,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(40.dp))
        if (state.isRecording) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Cancel")
                }
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Ink),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Done · Analyze", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(
            "Hum one note at a time in a quiet room. The recording is analyzed only after you press Done.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        )
    }
}

@Composable
private fun RecordingWaveform(level: Float, active: Boolean) {
    val animatedLevel by animateFloatAsState(if (active) level else 0.08f, label = "recording level")
    Row(
        modifier = Modifier.fillMaxWidth().height(92.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(25) { index ->
            val distance = kotlin.math.abs(index - 12) / 12f
            val shape = 0.35f + (1f - distance) * 0.65f
            val variation = 0.62f + ((index * 37) % 10) / 20f
            val height = 8f + animatedLevel * 78f * shape * variation
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .width(4.dp)
                    .height(height.dp)
                    .clip(CircleShape)
                    .background(if (active) Brush.verticalGradient(listOf(Violet, Aqua)) else Brush.verticalGradient(listOf(TextSecondary, TextSecondary))),
            )
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDeep),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(68.dp).clip(CircleShape).background(Violet.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, contentDescription = null, tint = Violet, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("Microphone access", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Needed only when you choose Record humming. Uploaded audio does not need microphone access.",
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
private fun ResultScreen(
    state: MelodyUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSelectNote: (Int) -> Unit,
    onRecordAgain: () -> Unit,
    onUploadAnother: () -> Unit,
    onClearError: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
    ) {
        TopBar("Piano notes", onBack)
        Spacer(Modifier.height(16.dp))

        when (state.stage) {
            AnalysisStage.Decoding,
            AnalysisStage.Transcribing,
            -> ProcessingCard(state)

            AnalysisStage.Error -> {
                ErrorResultCard(state.errorMessage ?: "The audio could not be analyzed.", onClearError)
                Spacer(Modifier.height(16.dp))
                RetryButtons(onRecordAgain, onUploadAnother)
            }

            AnalysisStage.Complete -> CompletedResult(
                state = state,
                onPlay = onPlay,
                onStop = onStop,
                onSelectNote = onSelectNote,
                onRecordAgain = onRecordAgain,
                onUploadAnother = onUploadAnother,
            )

            AnalysisStage.Idle -> ProcessingCard(state.copy(stage = AnalysisStage.Decoding, progress = 0.02f))
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ProcessingCard(state: MelodyUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDeep),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Violet.copy(alpha = 0.16f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(70.dp).clip(CircleShape).background(Violet.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Violet, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (state.stage == AnalysisStage.Transcribing) "Finding piano notes…" else "Preparing your audio…",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(state.fileName.orEmpty(), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.height(24.dp))
            ProgressBar(state.progress)
            Spacer(Modifier.height(10.dp))
            Text("${(state.progress * 100).roundToInt()}%", color = VioletBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Text("Everything is running locally on your phone.", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "analysis progress")
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            size = size,
            cornerRadius = CornerRadius(size.height / 2f),
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Violet, Aqua)),
            size = Size(size.width * animated, size.height),
            cornerRadius = CornerRadius(size.height / 2f),
        )
    }
}

@Composable
private fun CompletedResult(
    state: MelodyUiState,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSelectNote: (Int) -> Unit,
    onRecordAgain: () -> Unit,
    onUploadAnother: () -> Unit,
) {
    if (state.notes.isEmpty()) {
        EmptyResultCard()
        Spacer(Modifier.height(16.dp))
        RetryButtons(onRecordAgain, onUploadAnother)
        return
    }

    val selectedIndex = state.selectedNoteIndex?.coerceIn(state.notes.indices) ?: 0
    val selected = state.notes[selectedIndex]
    val averageConfidence = state.notes.map { it.confidence }.average().toFloat()

    SummaryCard(
        noteCount = state.notes.size,
        durationMillis = state.audioDurationMillis,
        confidence = averageConfidence,
        currentNote = selected.note.name,
    )
    Spacer(Modifier.height(12.dp))

    SequencePianoRoll(
        notes = state.notes,
        durationMillis = state.audioDurationMillis,
        selectedIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth().height(205.dp),
    )
    PianoKeyboard(
        highlightedMidi = selected.note.midi,
        startMidi = keyboardStartMidi(selected.note.midi),
        modifier = Modifier.fillMaxWidth().height(126.dp),
    )

    Spacer(Modifier.height(14.dp))
    NoteSequence(state.notes, selectedIndex, onSelectNote)
    Spacer(Modifier.height(16.dp))

    Button(
        onClick = if (state.isPlaying) onStop else onPlay,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(17.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (state.isPlaying) SurfaceRaised else Violet, contentColor = if (state.isPlaying) TextPrimary else Ink),
    ) {
        Icon(if (state.isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (state.isPlaying) "Stop piano playback" else "Play detected melody", fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(12.dp))
    RetryButtons(onRecordAgain, onUploadAnother)
}

@Composable
private fun SummaryCard(
    noteCount: Int,
    durationMillis: Long,
    confidence: Float,
    currentNote: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDeep),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Violet.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(62.dp).clip(RoundedCornerShape(19.dp)).background(Violet.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text(currentNote, color = VioletBright, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Melody ready", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("$noteCount notes · ${formatDuration(durationMillis)}", color = TextSecondary, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${(confidence * 100).roundToInt()}%", color = Mint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("confidence", color = TextSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SequencePianoRoll(
    notes: List<DetectedNoteEvent>,
    durationMillis: Long,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val minimumMidi = (notes.minOf { it.note.midi } - 2).coerceAtLeast(0)
    val maximumMidi = (notes.maxOf { it.note.midi } + 2).coerceAtMost(127)
    val laneCount = (maximumMidi - minimumMidi + 1).coerceAtLeast(7)
    val totalDuration = max(durationMillis, notes.maxOf { it.endMillis }).coerceAtLeast(1L)

    Canvas(
        modifier = modifier.clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Color(0xFF10121D)),
    ) {
        val laneHeight = size.height / laneCount
        repeat(laneCount + 1) { lane ->
            drawLine(
                color = Color.White.copy(alpha = 0.045f),
                start = Offset(0f, lane * laneHeight),
                end = Offset(size.width, lane * laneHeight),
                strokeWidth = 0.7.dp.toPx(),
            )
        }
        repeat(5) { marker ->
            val x = marker * size.width / 4f
            drawLine(
                color = Color.White.copy(alpha = 0.065f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 0.8.dp.toPx(),
            )
        }

        notes.forEachIndexed { index, event ->
            val x = event.startMillis.toFloat() / totalDuration * size.width
            val width = (event.durationMillis.toFloat() / totalDuration * size.width).coerceAtLeast(6.dp.toPx())
            val lane = maximumMidi - event.note.midi
            val y = lane * laneHeight + laneHeight * 0.14f
            val selected = index == selectedIndex
            drawRoundRect(
                brush = Brush.horizontalGradient(if (selected) listOf(VioletBright, Aqua) else listOf(Violet.copy(alpha = 0.72f), Aqua.copy(alpha = 0.68f))),
                topLeft = Offset(x, y),
                size = Size(width.coerceAtMost(size.width - x), laneHeight * 0.72f),
                cornerRadius = CornerRadius(5.dp.toPx()),
            )
            if (selected) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.75f),
                    topLeft = Offset(x, y),
                    size = Size(width.coerceAtMost(size.width - x), laneHeight * 0.72f),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                    style = Stroke(1.4.dp.toPx()),
                )
            }
            if (width > 25.dp.toPx()) {
                drawContext.canvas.nativeCanvas.drawText(
                    event.note.name,
                    x + 5.dp.toPx(),
                    y + laneHeight * 0.51f,
                    Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 9.sp.toPx()
                        isFakeBoldText = true
                        isAntiAlias = true
                    },
                )
            }
        }
    }
}

@Composable
private fun PianoKeyboard(
    highlightedMidi: Int,
    startMidi: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)).background(Color(0xFFE8E8F0)),
    ) {
        val whiteNotes = (startMidi until startMidi + 24).filter { !MusicNote.fromMidi(it).isBlackKey }
        val whiteKeyWidth = size.width / whiteNotes.size
        whiteNotes.forEachIndexed { index, midi ->
            drawRect(
                color = if (highlightedMidi == midi) VioletBright else Color(0xFFF4F3F8),
                topLeft = Offset(index * whiteKeyWidth, 0f),
                size = Size(whiteKeyWidth, size.height),
            )
            drawRect(
                color = Color(0xFF8E90A1).copy(alpha = 0.48f),
                topLeft = Offset(index * whiteKeyWidth, 0f),
                size = Size(whiteKeyWidth, size.height),
                style = Stroke(0.75.dp.toPx()),
            )
        }

        val blackWidth = whiteKeyWidth * 0.62f
        val blackHeight = size.height * 0.61f
        (startMidi until startMidi + 24).filter { MusicNote.fromMidi(it).isBlackKey }.forEach { midi ->
            val whiteBefore = whiteNotes.count { it < midi }
            drawRoundRect(
                color = if (highlightedMidi == midi) Violet else Color(0xFF151622),
                topLeft = Offset(whiteBefore * whiteKeyWidth - blackWidth / 2f, 0f),
                size = Size(blackWidth, blackHeight),
                cornerRadius = CornerRadius(0f, 5.dp.toPx()),
            )
        }

        val note = MusicNote.fromMidi(highlightedMidi)
        if (highlightedMidi in startMidi until startMidi + 24) {
            val centerX = if (note.isBlackKey) {
                whiteNotes.count { it < highlightedMidi } * whiteKeyWidth
            } else {
                (whiteNotes.indexOf(highlightedMidi) + 0.5f) * whiteKeyWidth
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
private fun NoteSequence(
    notes: List<DetectedNoteEvent>,
    selectedIndex: Int,
    onSelectNote: (Int) -> Unit,
) {
    Column {
        Text("TAP A NOTE TO HEAR IT", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            itemsIndexed(notes, key = { index, event -> "$index-${event.note.midi}" }) { index, event ->
                val selected = index == selectedIndex
                val background by animateColorAsState(if (selected) Violet else Violet.copy(alpha = 0.12f), label = "selected note")
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(background)
                        .border(1.dp, Violet.copy(alpha = if (selected) 0.85f else 0.22f), RoundedCornerShape(14.dp))
                        .clickable { onSelectNote(index) }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(event.note.name, color = if (selected) Ink else VioletBright, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("${event.durationMillis} ms", color = if (selected) Ink.copy(alpha = 0.7f) else TextSecondary, fontSize = 8.sp)
                }
            }
        }
    }
}

@Composable
private fun RetryButtons(onRecordAgain: () -> Unit, onUploadAnother: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onRecordAgain,
            modifier = Modifier.weight(1f).height(49.dp),
            shape = RoundedCornerShape(15.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Record again", fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = onUploadAnother,
            modifier = Modifier.weight(1f).height(49.dp),
            shape = RoundedCornerShape(15.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        ) {
            Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Upload another", fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyResultCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDeep),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No clear melody found", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(9.dp))
            Text(
                "Try a quieter recording with one sustained voice or hum and no background music.",
                color = TextSecondary,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorResultCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1720)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFFF8A9A).copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text("Couldn’t analyze this audio", color = Color(0xFFFF9AAA), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(message, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFF7185).copy(alpha = 0.11f))
            .border(1.dp, Color(0xFFFF7185).copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(onClick = onDismiss)
            .padding(14.dp),
    ) {
        Text(message, color = Color(0xFFFF9AAA), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("×", color = Color(0xFFFF9AAA), fontSize = 18.sp)
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }
        Text(title, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

private fun keyboardStartMidi(midi: Int): Int = (midi / 12 * 12 - 12).coerceIn(24, 96)

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (milliseconds.coerceAtLeast(0L) % 1_000L) / 100L
    return if (minutes > 0) "%d:%02d".format(minutes, seconds) else "%02d.%d".format(seconds, tenths)
}
