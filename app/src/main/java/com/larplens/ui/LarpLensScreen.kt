package com.larplens.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.larplens.LarpLensViewModel
import com.larplens.TagUiState
import com.larplens.camera.NormRect
import com.meta.wearable.dat.core.types.RegistrationState

@Composable
fun LarpLensScreen(
    viewModel: LarpLensViewModel,
    onRegister: () -> Unit,
    onStartCamera: () -> Unit,
    onMockToggle: (Boolean) -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val frame by viewModel.latestFrame.collectAsStateWithLifecycle()
  val tag by viewModel.tag.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  var phrase by rememberSaveable { mutableStateOf("That's Zaid. Building LarpLens, a whisper in your ear.") }

  LaunchedEffect(state.message) {
    state.message?.let {
      snackbar.showSnackbar(it.text)
      viewModel.clearMessage(it.id)
    }
  }

  MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
      Column(
          modifier =
              Modifier.fillMaxSize()
                  .padding(padding)
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 16.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("LarpLens", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Section("Glasses") {
          StatusRow("SDK", if (state.sdkReady) "ready" else "waiting for Bluetooth permission")
          StatusRow("Registration", state.registrationState?.toString() ?: "-")
          StatusRow("Devices", state.deviceCount.toString())
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mock glasses (phone camera)", modifier = Modifier.weight(1f))
            Switch(
                checked = state.mockEnabled,
                onCheckedChange = onMockToggle,
                enabled = state.sdkReady && !state.cameraRunning,
            )
          }
          if (state.registrationState != RegistrationState.REGISTERED && !state.mockEnabled) {
            OutlinedButton(onClick = onRegister, enabled = state.sdkReady) {
              Text("Register with Meta AI")
            }
          }
        }

        Section("Camera") {
          StatusRow("Session", state.sessionState?.toString() ?: "-")
          StatusRow("Stream", state.streamState?.toString() ?: "-")
          StatusRow("Frames", state.frameSize?.let { "$it @ ${state.fps} fps" } ?: "-")
          Box(
              modifier =
                  Modifier.fillMaxWidth()
                      .aspectRatio(frame?.let { it.width.toFloat() / it.height } ?: (504f / 896f))
                      .clip(RoundedCornerShape(12.dp))
                      .background(Color.Black),
              contentAlignment = Alignment.Center,
          ) {
            val bitmap = frame
            if (bitmap != null) {
              Image(
                  bitmap = bitmap.asImageBitmap(),
                  contentDescription = "Glasses camera preview",
                  contentScale = ContentScale.FillBounds,
                  modifier = Modifier.fillMaxSize(),
              )
              tag.detection?.let { TagOverlay(it.box, tag.locked) }
            } else {
              Text("No video", color = Color.White.copy(alpha = 0.6f))
            }
          }
          Row {
            Button(onClick = onStartCamera, enabled = state.sdkReady && !state.cameraRunning) {
              Text("Start camera")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = viewModel::stopCamera, enabled = state.cameraRunning) {
              Text("Stop")
            }
          }
        }

        Section("Name tag") {
          val status =
              when {
                tag.locked -> "LOCKED"
                tag.detection != null -> "candidate..."
                state.streamState != null -> "searching"
                else -> "-"
              }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                          tag.locked -> LockedGreen
                          tag.detection != null -> CandidateAmber
                          else -> MaterialTheme.colorScheme.outlineVariant
                        }))
            Spacer(Modifier.width(8.dp))
            Text(status, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "${tag.analysisMs} ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          tag.detection?.let {
            StatusRow("Fill / ink", "%.2f / %.2f".format(it.fill, it.ink))
          }
          StatusRow("Locks this run", tag.lockCount.toString())
          StatusRow("Guest list", if (tag.guestCount > 0) "${tag.guestCount} names" else "not loaded")
          ReadResult(tag, onReadAloud = viewModel::readGuestAloud, audioBusy = state.audioBusy)
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Read facts aloud automatically")
              Text(
                  if (state.ttsConfigured) "ElevenLabs, confident matches only"
                  else "Set elevenlabs_api_key to enable",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
                checked = tag.autoSpeak,
                onCheckedChange = viewModel::setAutoSpeak,
                enabled = state.ttsConfigured,
            )
          }
          tag.crop?.let { crop ->
            Image(
                bitmap = crop.asImageBitmap(),
                contentDescription = "Cropped name tag",
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(8.dp)),
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Read with Claude Haiku")
              Text(
                  if (tag.claudeAvailable) "Reader: ${tag.readerLabel}"
                  else "Set anthropic_api_key in local.properties to enable",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
                checked = tag.useClaude,
                onCheckedChange = viewModel::setUseClaude,
                enabled = tag.claudeAvailable,
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chime in glasses on lock", modifier = Modifier.weight(1f))
            Switch(checked = tag.chimeEnabled, onCheckedChange = viewModel::setChimeEnabled)
          }
        }

        Section("Audio to glasses") {
          StatusRow("Bluetooth A2DP", if (state.a2dpConnected) "connected" else "not connected")
          StatusRow("Last route", state.audioRoute ?: "-")
          Button(onClick = viewModel::playTestTone, enabled = !state.audioBusy) {
            Text("Play test tone")
          }
          OutlinedTextField(
              value = phrase,
              onValueChange = { phrase = it },
              label = { Text("Say with ElevenLabs") },
              modifier = Modifier.fillMaxWidth(),
          )
          Button(
              onClick = { viewModel.speak(phrase) },
              enabled = !state.audioBusy && state.ttsConfigured && phrase.isNotBlank(),
          ) {
            Text("Speak")
          }
          if (!state.ttsConfigured) {
            Text(
                "Set elevenlabs_api_key in local.properties and rebuild to enable TTS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

/** What ML Kit read off the tag: confirmed name, live read, raw lines, history. */
@Composable
private fun ReadResult(tag: TagUiState, onReadAloud: () -> Unit, audioBusy: Boolean) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
        tag.confirmedName
            ?: tag.leader
            ?: if (tag.ocrMs > 0 && tag.ocrName == null) CANT_READ else "—",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color =
            if (tag.confirmedName != null) LockedGreen
            else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        when {
          tag.confirmedName != null && tag.leaderVotes <= 1 -> "confirmed"
          tag.confirmedName != null -> "confirmed (${tag.leaderVotes} reads agree)"
          tag.leader != null -> "unconfirmed: ${tag.leaderVotes} read so far"
          tag.locked -> "reading…"
          else -> "point at a name tag"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (tag.ocrLines.isNotEmpty() || tag.ocrMs > 0) {
      StatusRow("Last read (${tag.ocrMs} ms)", tag.ocrName ?: CANT_READ)
      Text(
          if (tag.ocrLines.isEmpty()) "no text read"
          else tag.ocrLines.joinToString(" | ") { "\"$it\"" },
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
      )
    }
    GuestMatchCard(tag, onReadAloud, audioBusy)
    if (tag.history.isNotEmpty()) {
      StatusRow("Seen", tag.history.joinToString(", "))
    }
  }
}

/** The guest-list lookup for the confirmed name: the top match, clearly highlighted. */
@Composable
private fun GuestMatchCard(tag: TagUiState, onReadAloud: () -> Unit, audioBusy: Boolean) {
  val lookup = tag.guestLookup ?: return
  val match = tag.guestMatch
  val accent =
      when {
        match == null -> MaterialTheme.colorScheme.error
        match.ambiguous -> CandidateAmber
        else -> LockedGreen
      }
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .padding(top = 8.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(accent.copy(alpha = 0.14f))
              .border(2.dp, accent, RoundedCornerShape(10.dp))
              .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
        if (match == null) "NOT ON GUEST LIST" else "GUEST LIST MATCH",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = accent,
    )
    if (match == null) {
      Text(
          "No guest close to \"$lookup\" (${tag.guestCount} guests checked)",
          style = MaterialTheme.typography.bodyMedium,
      )
      return@Column
    }
    Text(match.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(
        "%d%% match for \"%s\"".format((match.score * 100).toInt(), lookup),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (tag.guestFacts.isEmpty()) {
      Text(
          "No facts on file",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      tag.guestFacts.forEach { fact ->
        Text("• $fact", style = MaterialTheme.typography.bodyLarge)
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
      OutlinedButton(onClick = onReadAloud, enabled = !audioBusy) { Text("Read aloud") }
      Spacer(Modifier.width(12.dp))
      Text(
          tag.speechNote ?: "",
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (match.ambiguous) {
      Text(
          "Ambiguous: other guests match just as well",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = accent,
      )
    }
    if (match.runnersUp.isNotEmpty()) {
      Text(
          "Next: " + match.runnersUp.joinToString(", ") { (name, score) -> "$name ${(score * 100).toInt()}%" },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private const val CANT_READ = "Can't Read Name"

private val LockedGreen = Color(0xFF2ECC71)
private val CandidateAmber = Color(0xFFF5B700)

/** Draws the detected tag box over the preview: amber while unconfirmed, green once locked. */
@Composable
private fun TagOverlay(box: NormRect, locked: Boolean) {
  Canvas(Modifier.fillMaxSize()) {
    val color = if (locked) LockedGreen else CandidateAmber
    val topLeft = Offset(box.left * size.width, box.top * size.height)
    val boxSize = Size(box.width * size.width, box.height * size.height)
    if (locked) drawRect(color.copy(alpha = 0.18f), topLeft, boxSize)
    drawRect(color, topLeft, boxSize, style = Stroke(width = if (locked) 4.dp.toPx() else 2.dp.toPx()))
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      content()
    }
  }
}

@Composable
private fun StatusRow(label: String, value: String) {
  Row {
    Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value)
  }
}
