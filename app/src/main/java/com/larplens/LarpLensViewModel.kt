// LarpLensViewModel - one DeviceSession with a Camera stream, plus audio out to the glasses.
//
// DAT lifecycle rules followed here:
// - Wearables.initialize() only after BLUETOOTH_CONNECT is granted
// - DAT camera permission is checked/requested before the stream starts
// - The camera is added only once the session reports STARTED
// - Collectors subscribe before start(); a STOPPED session is terminal and is recreated next time

package com.larplens

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.larplens.audio.ElevenLabsTts
import com.larplens.audio.GlassesAudioPlayer
import com.larplens.camera.I420
import com.larplens.camera.OpenCvTagDetector
import com.larplens.camera.TagDetector
import com.larplens.camera.NormRect
import com.larplens.camera.TagDetection
import com.larplens.camera.TagTracker
import com.larplens.glasses.MockGlassesController
import com.larplens.guests.GuestMatch
import com.larplens.guests.GuestList
import com.larplens.ocr.ClaudeNameTagReader
import com.larplens.ocr.MlKitNameTagReader
import com.larplens.ocr.NameExtractor
import com.larplens.ocr.NameTagReader
import com.larplens.ocr.ReadingVoter
import com.meta.wearable.dat.camera.Camera
import org.opencv.android.OpenCVLoader
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LarpLensUiState(
    val sdkReady: Boolean = false,
    val registrationState: RegistrationState? = null,
    val deviceCount: Int = 0,
    val mockEnabled: Boolean = false,
    val sessionState: DeviceSessionState? = null,
    val streamState: StreamState? = null,
    val frameSize: String? = null,
    val fps: Int = 0,
    val a2dpConnected: Boolean = false,
    val audioRoute: String? = null,
    val audioBusy: Boolean = false,
    val ttsConfigured: Boolean = false,
    val message: Message? = null,
) {
  val cameraRunning: Boolean
    get() = sessionState != null && sessionState != DeviceSessionState.STOPPED
}

data class Message(val id: Long, val text: String)

data class TagUiState(
    /** Latest per-frame detection (may flicker). */
    val detection: TagDetection? = null,
    /** Debounced: a tag has been steadily in view. */
    val locked: Boolean = false,
    /** Cropped tag image from the most recent locked frame. */
    val crop: Bitmap? = null,
    val lockCount: Int = 0,
    val analysisMs: Long = 0,
    val chimeEnabled: Boolean = true,
    /** Lines ML Kit saw on the most recent read. */
    val ocrLines: List<String> = emptyList(),
    /** Name extracted from the most recent read (may flicker). */
    val ocrName: String? = null,
    val ocrMs: Long = 0,
    /** Front-runner for this lock and how many reads agreed. */
    val leader: String? = null,
    val leaderVotes: Int = 0,
    /** Confirmed for the current lock (2+ agreeing reads). */
    val confirmedName: String? = null,
    /** Recently confirmed names, newest first. */
    val history: List<String> = emptyList(),
    val readerLabel: String = "",
    val useClaude: Boolean = false,
    /** False when no anthropic_api_key is configured. */
    val claudeAvailable: Boolean = false,
    /** Number of names loaded from the guest list. */
    val guestCount: Int = 0,
    /** The name we looked up on the guest list (the confirmed read). */
    val guestLookup: String? = null,
    /** Best guest-list match for [guestLookup]; null with a lookup means "not on the list". */
    val guestMatch: GuestMatch? = null,
    /** Facts (point1..3) for [guestMatch]. */
    val guestFacts: List<String> = emptyList(),
    /** Read a confident match's facts aloud via ElevenLabs as soon as it's found. */
    val autoSpeak: Boolean = true,
    /** Guest whose facts were last sent to ElevenLabs, for the UI. */
    val spokenGuest: String? = null,
    /** What happened to the brief for the current match ("Speaking…", "Queued", why skipped). */
    val speechNote: String? = null,
)

class LarpLensViewModel(application: Application) : AndroidViewModel(application) {
  private val _uiState =
      MutableStateFlow(LarpLensUiState(ttsConfigured = BuildConfig.ELEVENLABS_API_KEY.isNotBlank()))
  val uiState: StateFlow<LarpLensUiState> = _uiState.asStateFlow()

  // Latest decoded frame. Kept separate from uiState so 24 fps doesn't recompose everything, and
  // so the name tag OCR step can read it later.
  private val _latestFrame = MutableStateFlow<Bitmap?>(null)
  val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()

  private val _tag =
      MutableStateFlow(
          TagUiState(
              claudeAvailable = BuildConfig.ANTHROPIC_API_KEY.isNotBlank(),
              readerLabel = "ML Kit (on-device)",
          ))
  val tag: StateFlow<TagUiState> = _tag.asStateFlow()
  private val tagDetector: TagDetector? =
      if (OpenCVLoader.initLocal()) OpenCvTagDetector()
      else {
        Log.e(TAG, "OpenCV failed to load; name tag detection is off")
        null
      }
  private val tagTracker = TagTracker()
  private var tagReader: NameTagReader = MlKitNameTagReader()
  private var readingVoter = ReadingVoter(tagReader.readsNeeded)
  private var ocrJob: Job? = null
  private var lastOcr = 0L
  private var readsThisLock = 0
  // Bumped on every new lock (and reader switch) so a slow read can't land on the wrong tag
  private var lockGeneration = 0
  @Volatile private var guestList: GuestList? = null
  // Guest name -> when their facts were last spoken, so one person isn't repeated every lock
  private val lastSpokenAt = mutableMapOf<String, Long>()
  // Newest brief that arrived while audio was busy; played as soon as it finishes
  @Volatile private var pendingBrief: Pair<String, List<String>>? = null

  init {
    // Start on the Claude reader when a key is configured; the switch can still turn it off.
    // (With no key, setUseClaude would show a message, so only call it when there is one.)
    if (BuildConfig.ANTHROPIC_API_KEY.isNotBlank()) setUseClaude(true)

    // Name + point1..3 only, generated at build time from the guest CSV (see app/build.gradle.kts)
    viewModelScope.launch(Dispatchers.IO) {
      val list =
          runCatching {
                GuestList.parse(
                    application.assets.open("guests.tsv").bufferedReader().use { it.readText() })
              }
              .onFailure { Log.w(TAG, "No guest list bundled", it) }
              .getOrDefault(GuestList(emptyList()))
      guestList = list
      Log.i(TAG, "Guest list: ${list.size} guests, ${list.guests.count { it.facts.isNotEmpty() }} with facts")
      _tag.update { it.copy(guestCount = list.size) }
    }
  }

  private val mockGlasses = MockGlassesController(application)
  private val audioPlayer = GlassesAudioPlayer(application)
  private val tts =
      ElevenLabsTts(
          apiKey = BuildConfig.ELEVENLABS_API_KEY,
          voiceId = BuildConfig.ELEVENLABS_VOICE_ID,
          modelId = BuildConfig.ELEVENLABS_MODEL_ID,
      )

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private val sessionJobs = mutableListOf<Job>()
  private val cameraJobs = mutableListOf<Job>()
  private var audioJob: Job? = null
  private var messageId = 0L

  // One frame at a time, off the main thread; conflate() drops frames we can't keep up with
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

  fun initializeSdk() {
    if (_uiState.value.sdkReady) return
    Wearables.initialize(getApplication())
        .onSuccess {
          _uiState.update { it.copy(sdkReady = true) }
          viewModelScope.launch {
            Wearables.registrationState.collect { state ->
              _uiState.update { it.copy(registrationState = state) }
            }
          }
          viewModelScope.launch {
            Wearables.devices.collect { devices ->
              _uiState.update {
                it.copy(deviceCount = devices.size, a2dpConnected = audioPlayer.isA2dpConnected())
              }
            }
          }
        }
        .onFailure { error, _ -> showMessage("Failed to initialize DAT: ${error.description}") }
    refreshAudioRoute()
  }

  // ---- Mock glasses ----

  fun setMockEnabled(enabled: Boolean) {
    if (session != null) {
      showMessage("Stop the camera before switching between mock and real glasses")
      return
    }
    if (enabled) {
      mockGlasses.enable()?.let { error ->
        showMessage("Could not pair mock glasses: $error")
        mockGlasses.disable()
        return
      }
    } else {
      mockGlasses.disable()
    }
    _uiState.update { it.copy(mockEnabled = enabled) }
  }

  // ---- Camera ----

  /** Call from a user action: the permission request can open the Meta AI app. */
  fun startCamera(requestPermission: suspend (Permission) -> PermissionStatus) {
    if (session != null) return
    viewModelScope.launch {
      if (_uiState.value.registrationState != RegistrationState.REGISTERED) {
        showMessage("Register with Meta AI first (or turn on mock glasses)")
        return@launch
      }
      if (!ensureCameraPermission(requestPermission)) return@launch
      startSession()
    }
  }

  fun stopCamera() {
    camera?.stop()
    // Stopping the session cascades to the camera; the state collector cleans up on STOPPED
    session?.stop()
  }

  private suspend fun ensureCameraPermission(
      requestPermission: suspend (Permission) -> PermissionStatus
  ): Boolean {
    var status: PermissionStatus? = null
    Wearables.checkPermissionStatus(Permission.CAMERA)
        .onSuccess { status = it }
        .onFailure { error, _ -> showMessage("Camera permission: ${error.description}") }
    if (status == null) return false
    if (status != PermissionStatus.Granted) status = requestPermission(Permission.CAMERA)
    if (status != PermissionStatus.Granted) {
      showMessage("LarpLens needs the glasses camera permission")
      return false
    }
    return true
  }

  private fun startSession() {
    Wearables.createSession(AutoDeviceSelector())
        .fold(
            onSuccess = { newSession ->
              session = newSession
              sessionJobs +=
                  viewModelScope.launch {
                    newSession.errors.collect { error -> showMessage(error.description) }
                  }
              sessionJobs +=
                  viewModelScope.launch {
                    newSession.state.collect { state ->
                      _uiState.update { it.copy(sessionState = state) }
                      when (state) {
                        DeviceSessionState.STARTED -> attachCamera(newSession)
                        DeviceSessionState.STOPPED -> clearSession(newSession)
                        else -> Unit
                      }
                    }
                  }
              // Fire-and-forget: failures arrive on session.errors
              newSession.start()
            },
            onFailure = { error, _ -> showMessage(error.description) },
        )
  }

  private fun attachCamera(activeSession: DeviceSession) {
    if (camera != null) return
    activeSession
        .addCamera(
            // MEDIUM (504x896) at 15 fps: enough to read a name tag, and lower settings get
            // less per-frame compression over Bluetooth
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15),
        )
        .fold(
            onSuccess = { addedCamera ->
              camera = addedCamera
              val stream = addedCamera.stream
              cameraJobs +=
                  viewModelScope.launch {
                    stream.state.collect { state ->
                      _uiState.update { it.copy(streamState = state) }
                    }
                  }
              cameraJobs +=
                  viewModelScope.launch {
                    stream.errorStream.collect { error -> showMessage("Stream: ${error.description}") }
                  }
              cameraJobs += viewModelScope.launch(frameDispatcher) { collectFrames(addedCamera) }
              stream.start().onFailure { error, _ ->
                showMessage("Could not start stream: ${error.description}")
              }
            },
            onFailure = { error, _ -> showMessage("Could not add camera: ${error.description}") },
        )
  }

  private suspend fun collectFrames(activeCamera: Camera) {
    var windowStart = System.currentTimeMillis()
    var framesInWindow = 0
    var lastAnalysis = 0L
    activeCamera.stream.videoStream.conflate().collect { frame ->
      if (frame.isCompressed) return@collect // we request raw YUV; ignore anything else
      val yuv = I420.copy(frame.buffer, frame.width, frame.height) ?: return@collect
      val bitmap = I420.toBitmap(yuv, frame.width, frame.height)
      _latestFrame.value = bitmap
      framesInWindow++
      val now = System.currentTimeMillis()
      if (now - lastAnalysis >= ANALYSIS_INTERVAL_MS) {
        lastAnalysis = now
        analyzeTag(yuv, bitmap)
      }
      if (now - windowStart >= 1000) {
        val fps = framesInWindow
        framesInWindow = 0
        windowStart = now
        _uiState.update { it.copy(fps = fps, frameSize = "${frame.width}×${frame.height}") }
      }
    }
  }

  private fun analyzeTag(yuv: ByteArray, frame: Bitmap) {
    val started = System.nanoTime()
    val detection = tagDetector?.detect(yuv, frame.width, frame.height)
    val event = tagTracker.update(detection)
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    val crop = if (tagTracker.locked && detection != null) crop(frame, detection.box) else null
    _tag.update {
      it.copy(
          detection = detection,
          locked = tagTracker.locked,
          crop = crop ?: if (tagTracker.locked) it.crop else null,
          lockCount = if (event == TagTracker.Event.ACQUIRED) it.lockCount + 1 else it.lockCount,
          analysisMs = elapsedMs,
      )
    }
    if (event == TagTracker.Event.ACQUIRED) startNewRead()
    if (crop != null) maybeReadTag(crop)
    if (event == TagTracker.Event.ACQUIRED) {
      Log.i(TAG, "Name tag locked: $detection")
      // The wearer can't see the phone, so the glasses chime is the real indicator
      if (_tag.value.chimeEnabled && audioJob?.isActive != true) {
        viewModelScope.launch {
          runCatching { audioPlayer.playDetectedEarcon() }
              .onFailure { if (it is CancellationException) throw it }
        }
      }
    }
  }

  private fun startNewRead() {
    lockGeneration++
    readsThisLock = 0
    lastOcr = 0L
    ocrJob?.cancel()
    readingVoter.reset()
    _tag.update {
      it.copy(
          confirmedName = null,
          leader = null,
          leaderVotes = 0,
          guestLookup = null,
          guestMatch = null,
          guestFacts = emptyList(),
          speechNote = null,
      )
    }
  }

  /**
   * Reads the locked tag's crop, one read in flight. Reads quickly (the reader's pace) until a
   * name is confirmed or the reader's quick-read budget is spent, then keeps re-checking slowly
   * so a new person in front of you is noticed even if the tag never leaves the frame (the
   * tracker only unlocks after the tag disappears).
   */
  private fun maybeReadTag(crop: Bitmap) {
    val reader = tagReader
    val now = System.currentTimeMillis()
    val settled = _tag.value.confirmedName != null || readsThisLock >= reader.maxReadsPerLock
    val interval = if (settled) RECHECK_INTERVAL_MS else reader.minIntervalMs
    if (ocrJob?.isActive == true || now - lastOcr < interval) return
    lastOcr = now
    readsThisLock++
    val generation = lockGeneration
    ocrJob =
        viewModelScope.launch(Dispatchers.Default) {
          val reading =
              try {
                reader.read(crop)
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                Log.w(TAG, "${reader.label} read failed", e)
                if (generation == lockGeneration) {
                  _tag.update { it.copy(ocrLines = listOf("error: ${e.message}"), ocrName = null) }
                }
                return@launch
              }
          if (generation != lockGeneration) return@launch
          // A different name on the same lock = you've turned to someone else: start over
          val current = _tag.value.confirmedName
          if (current != null &&
              reading.name != null &&
              NameExtractor.normalize(reading.name) != NameExtractor.normalize(current)) {
            Log.i(TAG, "New name on the same lock ($current -> ${reading.name}): treating as a new person")
            readingVoter.reset()
            _tag.update {
              it.copy(
                  confirmedName = null,
                  guestLookup = null,
                  guestMatch = null,
                  guestFacts = emptyList(),
                  speechNote = null,
              )
            }
          }
          val confirmed = readingVoter.add(reading.name)
          val leader = readingVoter.leader()
          Log.d(TAG, "${reader.label} ${reading.elapsedMs} ms: ${reading.rawLines} -> ${reading.name}")
          val match = confirmed?.let { guestList?.matcher?.match(it) }
          val facts = match?.let { guestList?.factsFor(it.name) }.orEmpty()
          if (confirmed != null) {
            Log.i(
                TAG,
                "Name confirmed: $confirmed -> " +
                    (match?.let { "guest ${it.name} (%.2f)%s".format(it.score, if (it.ambiguous) " ambiguous" else "") }
                        ?: "not on guest list"))
          }
          _tag.update {
            it.copy(
                ocrLines = reading.rawLines,
                ocrName = reading.name,
                ocrMs = reading.elapsedMs,
                leader = leader?.first,
                leaderVotes = leader?.second ?: 0,
                confirmedName = confirmed ?: it.confirmedName,
                guestLookup = confirmed ?: it.guestLookup,
                guestMatch = if (confirmed != null) match else it.guestMatch,
                guestFacts = if (confirmed != null) facts else it.guestFacts,
                history =
                    if (confirmed != null) (listOf(match?.name ?: confirmed) + it.history).distinct().take(8)
                    else it.history,
            )
          }
          // Whisper only when we're confident who it is; ambiguous or no match stays silent
          if (confirmed != null) {
            when {
              match == null -> note("Not spoken: not on the guest list")
              match.ambiguous -> note("Not spoken: ambiguous, tap Read aloud to hear the top match")
              !_tag.value.autoSpeak -> note("Not spoken: auto-read is off")
              else -> {
                val ago = System.currentTimeMillis() - (lastSpokenAt[match.name] ?: 0L)
                if (ago > REPEAT_COOLDOWN_MS) {
                  speakGuest(match.name, facts, warnRoute = false)
                } else {
                  Log.i(TAG, "Not repeating ${match.name}: spoken ${ago / 1000}s ago")
                  note("Not spoken: already read ${ago / 1000}s ago")
                }
              }
            }
          }
        }
  }

  /** Crops the tag with a little padding, for the debug view now and OCR next. */
  private fun crop(frame: Bitmap, box: NormRect): Bitmap? {
    val padX = box.width * 0.08f
    val padY = box.height * 0.08f
    val left = ((box.left - padX) * frame.width).toInt().coerceIn(0, frame.width - 1)
    val top = ((box.top - padY) * frame.height).toInt().coerceIn(0, frame.height - 1)
    val right = ((box.right + padX) * frame.width).toInt().coerceIn(left + 1, frame.width)
    val bottom = ((box.bottom + padY) * frame.height).toInt().coerceIn(top + 1, frame.height)
    return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
  }

  /** Switches between on-device ML Kit and Claude Haiku for reading tags. */
  fun setUseClaude(enabled: Boolean) {
    if (enabled == _tag.value.useClaude) return
    if (enabled && BuildConfig.ANTHROPIC_API_KEY.isBlank()) {
      showMessage("Add anthropic_api_key to local.properties and rebuild")
      return
    }
    val old = tagReader
    val next: NameTagReader =
        if (enabled) ClaudeNameTagReader(BuildConfig.ANTHROPIC_API_KEY) else MlKitNameTagReader()
    tagReader = next
    readingVoter = ReadingVoter(next.readsNeeded)
    startNewRead()
    old.close()
    _tag.update { it.copy(useClaude = enabled, readerLabel = next.label, ocrLines = emptyList(), ocrName = null, ocrMs = 0) }
    viewModelScope.launch { next.warmUp() }
  }

  fun setChimeEnabled(enabled: Boolean) {
    _tag.update { it.copy(chimeEnabled = enabled) }
  }

  private fun clearSession(stoppedSession: DeviceSession) {
    if (session !== stoppedSession) return
    (cameraJobs + sessionJobs).forEach { it.cancel() }
    cameraJobs.clear()
    sessionJobs.clear()
    camera = null
    session = null
    _latestFrame.value = null
    tagTracker.reset()
    startNewRead()
    _tag.update {
      TagUiState(
          chimeEnabled = it.chimeEnabled,
          lockCount = it.lockCount,
          history = it.history,
          readerLabel = it.readerLabel,
          useClaude = it.useClaude,
          claudeAvailable = it.claudeAvailable,
          guestCount = it.guestCount,
          autoSpeak = it.autoSpeak,
          spokenGuest = it.spokenGuest,
      )
    }
    _uiState.update { it.copy(streamState = null, fps = 0, frameSize = null) }
  }

  // ---- Audio ----

  fun refreshAudioRoute() {
    _uiState.update { it.copy(a2dpConnected = audioPlayer.isA2dpConnected()) }
  }

  fun playTestTone() {
    runAudio { audioPlayer.playTestTone(::onRouted) }
  }

  fun speak(text: String) {
    if (text.isBlank()) return
    if (!tts.isConfigured) {
      showMessage("Add elevenlabs_api_key to local.properties and rebuild")
      return
    }
    runAudio { tts.speak(text.trim(), audioPlayer, ::onRouted) }
  }

  /** "Read aloud" on the match card: speaks the current top match, ambiguous or not. */
  fun readGuestAloud() {
    val name = _tag.value.guestMatch?.name ?: return
    speakGuest(name, _tag.value.guestFacts, warnRoute = true)
  }

  fun setAutoSpeak(enabled: Boolean) {
    _tag.update { it.copy(autoSpeak = enabled) }
  }

  private fun speakGuest(name: String, facts: List<String>, warnRoute: Boolean) {
    if (!tts.isConfigured) {
      if (warnRoute) showMessage("Add elevenlabs_api_key to local.properties and rebuild")
      note("Not spoken: no ElevenLabs key")
      return
    }
    val pause = if (tts.supportsBreakTags) GuestList.BREAK_PAUSE else GuestList.PLAIN_PAUSE
    val text = GuestList.spokenBrief(name, facts, pause)
    val started =
        runAudio(warnRoute) {
          try {
            tts.speak(text, audioPlayer, ::onRouted)
            note("Spoken")
          } catch (e: Exception) {
            if (e !is CancellationException) note("Not spoken: ElevenLabs failed (${e.message?.take(60)})")
            throw e
          }
        }
    if (started) {
      Log.i(TAG, "Speaking brief for $name (${facts.size} facts)")
      lastSpokenAt[name] = System.currentTimeMillis()
      _tag.update { it.copy(spokenGuest = name) }
      note("Speaking…")
    } else {
      // Something is still playing (e.g. the previous person's brief): keep only the newest
      Log.i(TAG, "Audio busy; queued brief for $name")
      pendingBrief = name to facts
      note("Queued: plays after the current audio")
    }
  }

  private fun note(text: String) {
    _tag.update { it.copy(speechNote = text) }
  }

  private fun onRouted(route: String) {
    _uiState.update { it.copy(audioRoute = route) }
  }

  /** Runs one audio job at a time; returns false if something is already playing. */
  private fun runAudio(warnRoute: Boolean = true, block: suspend () -> Unit): Boolean {
    if (audioJob?.isActive == true) return false
    refreshAudioRoute()
    if (warnRoute && !_uiState.value.a2dpConnected) {
      showMessage("No Bluetooth A2DP output connected - this will play on the phone")
    }
    audioJob =
        viewModelScope
            .launch {
          _uiState.update { it.copy(audioBusy = true) }
          try {
            block()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Audio failed", e)
            showMessage("Audio failed: ${e.message}")
          } finally {
            _uiState.update { it.copy(audioBusy = false) }
          }
        }
            .also { job ->
              // Once this audio is done, play the newest brief that arrived meanwhile
              job.invokeOnCompletion {
                val next = pendingBrief ?: return@invokeOnCompletion
                pendingBrief = null
                viewModelScope.launch { speakGuest(next.first, next.second, warnRoute = false) }
              }
            }
    return true
  }

  // ---- Messages ----

  fun showMessage(text: String) {
    messageId += 1
    _uiState.update { it.copy(message = Message(messageId, text)) }
  }

  fun clearMessage(id: Long) {
    _uiState.update { if (it.message?.id == id) it.copy(message = null) else it }
  }

  override fun onCleared() {
    audioJob?.cancel()
    stopCamera()
    if (_uiState.value.mockEnabled) mockGlasses.disable()
    tagReader.close()
    super.onCleared()
  }

  private companion object {
    const val TAG = "LarpLens"
    // ~6 analyses/s: plenty for a debounced lock, and leaves headroom for OCR later
    const val ANALYSIS_INTERVAL_MS = 150L
    // Don't re-read the same guest's facts within this window
    const val REPEAT_COOLDOWN_MS = 60_000L
    // While a tag stays locked, re-read it this often to catch a new person
    const val RECHECK_INTERVAL_MS = 2_500L
  }
}
