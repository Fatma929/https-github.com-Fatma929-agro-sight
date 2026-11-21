package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.core.net.toUri
import androidx.credentials.exceptions.NoCredentialException
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.example.R
import com.example.data.GeminiAgronomyChatMessage
import com.example.model.EGYPTIAN_ZONE_PRESETS
import com.example.model.EgyptianZonePreset
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.RoseDanger
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.SkySoft
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate500
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.viewmodel.AgroSightUiState
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
private fun HubBentoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun HubSectionHeader(
    title: String,
    subtitle: String,
    badgeText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Slate900,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Slate600
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = EmeraldSoft,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.4f))
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldDark,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun AiDigitalTwinHubScreen(
    uiState: AgroSightUiState,
    onGoogleSignInTokenReceived: (String) -> Unit,
    onGoogleSignInError: (String) -> Unit,
    onSignOutFirebase: () -> Unit,
    onSyncWorkspaceToFirestore: () -> Unit,
    onRecordRoomSqlSnapshot: () -> Unit,
    onDeleteRoomSqlAudit: (String) -> Unit,
    onOpenFullSqlSchemaModal: () -> Unit,
    onSendGeminiChatMessage: (String, Boolean, ((String) -> Unit)?) -> Unit,
    onToggleSearchGrounding: () -> Unit,
    onToggleMapsGrounding: () -> Unit,
    onToggleAutoSpeakTts: () -> Unit,
    onSetVoiceListeningState: (Boolean) -> Unit,
    onSetVoiceSpeakingTtsState: (Boolean) -> Unit,
    onSelectValveSector: (Int) -> Unit,
    onSelectZonePreset: (EgyptianZonePreset) -> Unit,
    onToggleAcousticPlayback: () -> Unit,
    onSelectAcousticPreset: (String) -> Unit,
    onOpenDirectWaMeLink: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeSubTab by remember { mutableIntStateOf(0) } // 0: Gemini Voice/Chat, 1: 3D Three.js & Maps, 2: Acoustic Music, 3: Firebase & SQL

    // TextToSpeech engine for real-time Arabic voice conversation replies
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.forLanguageTag("ar-EG")
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onSetVoiceSpeakingTtsState(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        onSetVoiceSpeakingTtsState(false)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onSetVoiceSpeakingTtsState(false)
                    }
                })
            }
        }
        ttsEngine = engine
        onDispose {
            engine.stop()
            engine.shutdown()
        }
    }

    val speakArabicText: (String) -> Unit = { textToSpeak ->
        val cleanSpeech = textToSpeak
            .replace("*", "")
            .replace("`", "")
            .replace("#", "")
            .take(550)
        ttsEngine?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, null, "agrosight_tts_${System.currentTimeMillis()}")
    }

    // SpeechRecognizer for live Arabic voice input
    var voiceStatusHintAr by remember { mutableStateOf("اضغط على الميكروفون للتحدث بالعربية مع المستشار الزراعي Gemini") }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    DisposableEffect(context) {
        val recognizer = if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onSetVoiceListeningState(true)
                        voiceStatusHintAr = "🎙️ استمع إليك الآن... تحدث عن محصولك أو ملوحة التربة أو جدولة الري"
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        onSetVoiceListeningState(false)
                    }

                    override fun onError(error: Int) {
                        onSetVoiceListeningState(false)
                        voiceStatusHintAr = "تم إيقاف الاستماع الصوتي (أو استخدم أزرار الأوامر الصوتية الفورية أدناه)"
                    }

                    override fun onResults(results: Bundle?) {
                        onSetVoiceListeningState(false)
                        val spokenList = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val transcript = spokenList?.firstOrNull()?.trim().orEmpty()
                        if (transcript.isNotBlank()) {
                            voiceStatusHintAr = "🗣️ سمعتك تقول: \"$transcript\""
                            onSendGeminiChatMessage(transcript, true, speakArabicText)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else null
        speechRecognizer = recognizer
        onDispose {
            recognizer?.destroy()
        }
    }

    val startVoiceListening: () -> Unit = {
        val recognizer = speechRecognizer
        if (recognizer != null) {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                recognizer.startListening(intent)
            } catch (_: Exception) {
                voiceStatusHintAr = "تعذر تشغيل الميكروفون على هذا الجهاز؛ يمكنك استخدام الأوامر الصوتية الفورية"
            }
        } else {
            voiceStatusHintAr = "خدمة التعرف الصوتي غير متوفرة في المحاكي؛ تم تفعيل الأوامر الصوتية الذكية والرد الصوتي المسموع (TTS)"
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceListening()
        } else {
            voiceStatusHintAr = "تم رفض إذن الميكروفون؛ يمكنك استخدام أزرار المحادثة الصوتية السريعة مع نطق Gemini الصوتي"
        }
    }

    // Real-time procedural Agro-Acoustic Bio-Frequency Music Synthesizer (AudioTrack)
    LaunchedEffect(uiState.isAcousticGeneratorPlaying, uiState.selectedAcousticPresetId, uiState.epidemiologyReport.ndviMean) {
        if (!uiState.isAcousticGeneratorPlaying) return@LaunchedEffect
        val sampleRate = 22050
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (_: Exception) {
            null
        }

        try {
            audioTrack?.play()
            val baseFreq = when (uiState.selectedAcousticPresetId) {
                "SOLAR_CHLOROPHYLL_432HZ" -> 432.0
                "ROOT_OSMOSIS_528HZ" -> 528.0
                "VALVE_HARMONIC_396HZ" -> 396.0
                else -> 432.0
            }
            val ndviMod = (0.85 + uiState.epidemiologyReport.ndviMean * 0.3)
            var phase1 = 0.0
            var phase2 = 0.0
            var lfoPhase = 0.0
            val chunk = ShortArray(1024)

            while (isActive && uiState.isAcousticGeneratorPlaying) {
                for (i in chunk.indices) {
                    val lfo = 0.55 + 0.45 * sin(lfoPhase)
                    val s1 = sin(phase1) * 0.45
                    val s2 = sin(phase2) * 0.25
                    val sample = ((s1 + s2) * lfo * 10000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    chunk[i] = sample
                    phase1 += 2.0 * PI * (baseFreq * ndviMod) / sampleRate
                    phase2 += 2.0 * PI * (baseFreq * 1.5) / sampleRate
                    lfoPhase += 2.0 * PI * 0.35 / sampleRate
                }
                audioTrack?.write(chunk, 0, chunk.size)
                delay(15)
            }
        } catch (_: Exception) {
        } finally {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {
            }
        }
    }

    val triggerGoogleSignIn: () -> Unit = {
        scope.launch {
            try {
                val serverClientId = context.getString(R.string.default_web_client_id)
                val googleIdOption = GetSignInWithGoogleOption.Builder(serverClientId).build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val credentialManager = CredentialManager.create(context)
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    onGoogleSignInTokenReceived(googleIdTokenCredential.idToken)
                } else {
                    onGoogleSignInError("نوع بيانات اعتماد Google غير مدعوم")
                }
            } catch (e: NoCredentialException) {
                onGoogleSignInError("لم يتم العثور على حساب Google مسجل على الجهاز (${e.localizedMessage ?: "NoCredentialException"})")
            } catch (e: Exception) {
                onGoogleSignInError("تنبيه Google Sign-In: ${e.localizedMessage ?: "تأكد من تسجيل حساب Google على الجهاز"}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Banner: Multi-Capability Hub Switcher
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Slate900, EmeraldDark, Slate800)
                    )
                )
                .border(1.dp, EmeraldLight.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "مركز الذكاء الزراعي الصوتي والتوأم الرقمي 3D والسحابة",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Gemini Live Voice & Search/Maps Grounding • Three.js 3D Digital Twin • Firebase Firestore & Room SQL",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyanLight
                        )
                    }
                    Surface(
                        color = if (uiState.firebaseUserUid != null) EmeraldPrimary else Slate800,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, EmeraldLight),
                        modifier = Modifier
                            .clickable {
                                if (uiState.firebaseUserUid == null) triggerGoogleSignIn()
                                else onSyncWorkspaceToFirestore()
                            }
                            .testTag("hero_firebase_sync_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (uiState.firebaseUserUid != null) Icons.Default.CloudDone else Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (uiState.firebaseUserUid != null) "Firestore متصل" else "ربط Google Cloud",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 4 Sub-Tab Selector Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabs = listOf(
                        Triple(0, "🎙️ شات ومحادثة Gemini الصوتية + Google Search", Icons.Default.RecordVoiceOver),
                        Triple(1, "🌐 التوأم الرقمي Three.js 3D + خرائط Google Maps", Icons.Default.ViewInAr),
                        Triple(2, "🎵 مولد الترددات الصوتية الحيوية للمزرعة", Icons.Default.MusicNote),
                        Triple(3, "☁️ سحابة Firebase Firestore + قاعدة بيانات SQL", Icons.Default.Storage)
                    )
                    tabs.forEach { (idx, title, icon) ->
                        val selected = activeSubTab == idx
                        FilterChip(
                            selected = selected,
                            onClick = { activeSubTab = idx },
                            label = {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldPrimary,
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White,
                                containerColor = Slate800,
                                labelColor = Color.White,
                                iconColor = CyanLight
                            ),
                            modifier = Modifier.testTag("ai_hub_subtab_$idx")
                        )
                    }
                }
            }
        }

        when (activeSubTab) {
            0 -> GeminiVoiceAndGroundedChatSection(
                uiState = uiState,
                voiceStatusHintAr = voiceStatusHintAr,
                onRequestMicListening = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onSpeakText = speakArabicText,
                onStopSpeaking = {
                    ttsEngine?.stop()
                    onSetVoiceSpeakingTtsState(false)
                },
                onSendChatMessage = { msg, isVoice ->
                    onSendGeminiChatMessage(msg, isVoice, speakArabicText)
                },
                onToggleSearchGrounding = onToggleSearchGrounding,
                onToggleMapsGrounding = onToggleMapsGrounding,
                onToggleAutoSpeakTts = onToggleAutoSpeakTts,
                onOpenDirectWaMeLink = onOpenDirectWaMeLink,
                onSelectValveSector = onSelectValveSector,
                onSelectZonePreset = onSelectZonePreset,
                onOpenFullMapsTab = { activeSubTab = 1 }
            )

            1 -> ThreeJsDigitalTwinAndGoogleMapsSection(
                uiState = uiState,
                onSelectValveSector = onSelectValveSector,
                onSelectZonePreset = onSelectZonePreset,
                onAskGeminiAboutMapLocation = { prompt ->
                    activeSubTab = 0
                    onSendGeminiChatMessage(prompt, false, speakArabicText)
                }
            )

            2 -> AgroAcousticMusicGeneratorSection(
                uiState = uiState,
                onTogglePlayback = onToggleAcousticPlayback,
                onSelectPreset = onSelectAcousticPreset
            )

            3 -> FirebaseCloudAndSqlDatabaseSection(
                uiState = uiState,
                onTriggerGoogleSignIn = triggerGoogleSignIn,
                onSignOutFirebase = onSignOutFirebase,
                onSyncWorkspaceToFirestore = onSyncWorkspaceToFirestore,
                onRecordRoomSqlSnapshot = onRecordRoomSqlSnapshot,
                onDeleteRoomSqlAudit = onDeleteRoomSqlAudit,
                onOpenFullSqlSchemaModal = onOpenFullSqlSchemaModal
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GeminiVoiceAndGroundedChatSection(
    uiState: AgroSightUiState,
    voiceStatusHintAr: String,
    onRequestMicListening: () -> Unit,
    onSpeakText: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onSendChatMessage: (String, Boolean) -> Unit,
    onToggleSearchGrounding: () -> Unit,
    onToggleMapsGrounding: () -> Unit,
    onToggleAutoSpeakTts: () -> Unit,
    onOpenDirectWaMeLink: (String, String) -> Unit,
    onSelectValveSector: (Int) -> Unit = {},
    onSelectZonePreset: (EgyptianZonePreset) -> Unit = {},
    onOpenFullMapsTab: () -> Unit = {}
) {
    var inputQuestion by remember { mutableStateOf("") }
    val context = LocalContext.current
    val activeSector = uiState.valveSectors.find { it.valveNumber == uiState.selectedValveNumber }
        ?: uiState.valveSectors.firstOrNull()

    // 1. Live Voice Conversation Studio Card
    HubBentoCard(modifier = Modifier.fillMaxWidth()) {
        HubSectionHeader(
            title = "المحادثة الصوتية الحية مع المستشار الزراعي (Gemini Voice Conversation)",
            subtitle = "تحدث بالصوت مباشرة واستمع للرد المسموع بالعربية مع ربط حي ببيانات القمر الصناعي Sentinel-2 وخرائط Google Maps",
            badgeText = if (uiState.isVoiceListening) "🎙️ يستمع الآن..." else if (uiState.isVoiceSpeakingTts) "🔊 يتحدث الآن..." else "Gemini 3.8 Flash Live"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Slate900)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(
                onClick = onRequestMicListening,
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (uiState.isVoiceListening) RoseDanger else EmeraldPrimary)
                    .testTag("gemini_voice_mic_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "بدء التحدث الصوتي",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = voiceStatusHintAr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "محصول ${uiState.selectedCrop.nameAr} • ${uiState.fieldName} • pH ${formatDecimal1(uiState.soilPh)} • ET0 ${formatDecimal2(uiState.forecastDays.firstOrNull()?.et0MmDay ?: 5.6f)} مم/يوم",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldLight
                )
            }

            if (uiState.isVoiceSpeakingTts) {
                IconButton(
                    onClick = onStopSpeaking,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(RoseDanger)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "إيقاف النطق الصوتي",
                        tint = Color.White
                    )
                }
            }
        }

        // Microphone permission rationale & privacy notice
        Surface(
            color = Slate100,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mic_permission_rationale_banner")
        ) {
            Text(
                text = "🔒 خصوصية الصوت وإذن الميكروفون (RECORD_AUDIO): يُطلب الإذن فقط عند الضغط على زر الميكروفون لتحويل سؤالك الصوتي إلى نص زراعي، ولا يتم تسجيل أو تخزين أي ملفات صوتية خام. في حال رفض الإذن، يمكنك دائماً استخدام أزرار الأسئلة الفورية أو الكتابة النصية مع الاستماع للرد المسموع (TTS).",
                style = MaterialTheme.typography.labelSmall,
                color = Slate600,
                modifier = Modifier.padding(10.dp)
            )
        }

        // Quick Voice Prompts
        Text(
            text = "أوامر صوتية فورية (اضغط لإرسال السؤال وتشغيل الرد الصوتي المسموع):",
            style = MaterialTheme.typography.labelMedium,
            color = Slate700,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val voicePresets = listOf(
                "🎙️ كم ساعة أشغل المحبس اليوم وما جرعة الحامض لمعادلة pH؟",
                "🎙️ هل الاصفرار في المحبس رقم 3 مرض فطري أم عطل ري؟",
                "🎙️ حلل موقع الحقل على Google Maps (${uiState.latitude}, ${uiState.longitude}) لمحصول ${uiState.selectedCrop.nameAr}"
            )
            voicePresets.forEachIndexed { idx, preset ->
                Surface(
                    color = EmeraldSoft,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, EmeraldPrimary),
                    modifier = Modifier
                        .clickable { onSendChatMessage(preset.removePrefix("🎙️ "), true) }
                        .testTag("voice_preset_chip_$idx")
                ) {
                    Text(
                        text = preset,
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldDark,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // 1B. Embedded Interactive Google Maps Satellite + Chatbot Spatial Connector Card
    HubBentoCard(modifier = Modifier.fillMaxWidth()) {
        HubSectionHeader(
            title = "ربط خرائط Google Maps Satellite المباشر مع شات Gemini",
            subtitle = "اختر المنطقة أو قطاع المحبس على خريطة Google Maps واسأل الشات الذكي فوراً عن حالته الطيفية",
            badgeText = "Google Maps + Gemini"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EGYPTIAN_ZONE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = uiState.selectedZoneId == preset.id,
                    onClick = { onSelectZonePreset(preset) },
                    label = { Text(preset.nameAr) },
                    leadingIcon = {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                )
            }
        }

        val interactiveGoogleMapsHtml = remember(
            uiState.latitude,
            uiState.longitude,
            uiState.fieldName,
            uiState.selectedValveNumber,
            uiState.epidemiologyReport.ndviMean
        ) {
            buildInteractiveGoogleMapsLeafletHtml(
                latitude = uiState.latitude,
                longitude = uiState.longitude,
                fieldName = uiState.fieldName,
                cropNameAr = uiState.selectedCrop.nameAr,
                selectedValve = uiState.selectedValveNumber,
                ndviMean = uiState.epidemiologyReport.ndviMean,
                sectors = uiState.valveSectors
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, SkyAccent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(
                        "https://maps.google.com",
                        interactiveGoogleMapsHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("chatbot_embedded_google_maps_webview")
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (1..6).forEach { vNum ->
                val sec = uiState.valveSectors.find { it.valveNumber == vNum }
                FilterChip(
                    selected = uiState.selectedValveNumber == vNum,
                    onClick = { onSelectValveSector(vNum) },
                    label = {
                        Text(
                            if (sec != null) {
                                "محبس $vNum (NDVI ${formatDecimal2(sec.ndvi)})"
                            } else {
                                "محبس $vNum"
                            }
                        )
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val sectorInfo = if (activeSector != null) {
                        "محبس رقم ${activeSector.valveNumber} (${activeSector.nameAr}) حيث NDVI=${formatDecimal2(activeSector.ndvi)} وNDWI=${formatDecimal2(activeSector.ndwi)} وزمن الري=${formatDecimal1(activeSector.operatingHours)} ساعة"
                    } else {
                        "محبس رقم ${uiState.selectedValveNumber}"
                    }
                    onSendChatMessage(
                        "حلل لي على خريطة Google Maps عند الإحداثيات (${uiState.latitude}, ${uiState.longitude}) حالة $sectorInfo في ${uiState.fieldName} وأعطني توصية الري والتسميد الدقيقة.",
                        false
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                modifier = Modifier
                    .weight(1.25f)
                    .testTag("ask_gemini_from_google_map_btn")
            ) {
                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("اسأل شات Gemini عن هذا القطاع", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = {
                    try {
                        val uri = "https://www.google.com/maps/search/?api=1&query=${uiState.latitude},${uiState.longitude}".toUri()
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier
                    .weight(0.9f)
                    .testTag("open_google_maps_from_chat_btn")
            ) {
                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("فتح Google Maps", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = onOpenFullMapsTab,
                modifier = Modifier.testTag("expand_3d_google_maps_tab_btn")
            ) {
                Text("3D", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    // 2. Multi-Turn Gemini Chatbot with Google Search & Google Maps Grounding
    HubBentoCard(modifier = Modifier.fillMaxWidth()) {
        HubSectionHeader(
            title = "شات المستشار الزراعي متعدد الجولات (Gemini Chatbot + Google Search & Maps)",
            subtitle = "مدعوم بنموذج Gemini 3.8 Flash وبيانات بحث Google الحية وخرائط Google Maps وبصمة Sentinel-2",
            badgeText = "${uiState.geminiChatMessages.size} رسالة"
        )

        // Grounding Toggles Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.useGoogleSearchGrounding,
                onClick = onToggleSearchGrounding,
                label = { Text("تفعيل بيانات بحث Google (Search Grounding)") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("toggle_google_search_grounding")
            )
            FilterChip(
                selected = uiState.useGoogleMapsGrounding,
                onClick = onToggleMapsGrounding,
                label = { Text("تفعيل بيانات خرائط Google Maps") },
                leadingIcon = {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("toggle_google_maps_grounding")
            )
            FilterChip(
                selected = uiState.autoSpeakGeminiReplies,
                onClick = onToggleAutoSpeakTts,
                label = { Text("نطق الردود صوتياً (TTS)") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }

        // Chat Message Thread
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Slate100)
                .border(1.dp, Slate200, RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            uiState.geminiChatMessages.forEach { msg ->
                GeminiGroundedChatBubble(
                    message = msg,
                    latitude = uiState.latitude,
                    longitude = uiState.longitude,
                    onSpeakMessage = { onSpeakText(msg.text) },
                    onShareViaWaMe = {
                        onOpenDirectWaMeLink(uiState.activeFarmer.whatsappPhone, msg.text)
                    },
                    onOpenCitationUrl = { url ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                            context.startActivity(intent)
                        } catch (_: Exception) {
                        }
                    }
                )
            }

            if (uiState.isGeminiChatLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = EmeraldPrimary)
                    Text(
                        text = "جاري تحليل البصمة الطيفية والبحث في Google Search وGoogle Maps عبر Gemini AI...",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Chat Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputQuestion,
                onValueChange = { inputQuestion = it },
                label = { Text("اسأل Gemini عن الري، التسميد، خرائط المزرعة، أو أمراض المحصول...") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("gemini_grounded_chat_input"),
                singleLine = true
            )
            Button(
                onClick = {
                    if (inputQuestion.isNotBlank()) {
                        onSendChatMessage(inputQuestion, false)
                        inputQuestion = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                modifier = Modifier
                    .height(52.dp)
                    .testTag("gemini_grounded_chat_send_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال")
            }
        }
    }
}

@Composable
private fun GeminiGroundedChatBubble(
    message: GeminiAgronomyChatMessage,
    latitude: Double,
    longitude: Double,
    onSpeakMessage: () -> Unit,
    onShareViaWaMe: () -> Unit,
    onOpenCitationUrl: (String) -> Unit
) {
    val isUser = message.role == "USER"
    Surface(
        color = if (isUser) EmeraldSoft else Color.White,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (isUser) EmeraldPrimary.copy(alpha = 0.5f) else Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isUser) "👨‍🌾 المزارع (${message.channel})" else "🛰️ المستشار الذكي Gemini (${message.channel})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) EmeraldDark else SkyAccent,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isUser) {
                        Surface(
                            color = if (message.isLiveAiGenerated) EmeraldSoft else Slate100,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, if (message.isLiveAiGenerated) EmeraldPrimary else Slate400)
                        ) {
                            Text(
                                text = if (message.isLiveAiGenerated) "✨ Gemini 3.8 Live" else "⚡ تحليل محلي + Maps",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.isLiveAiGenerated) EmeraldDark else Slate700,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500
                    )
                    IconButton(
                        onClick = onSpeakMessage,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "استماع صوتي",
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = Slate900
            )

            if (message.citations.isNotEmpty()) {
                HorizontalDivider(color = Slate200)
                Text(
                    text = "🌐 المصادر الموثقة (Google Search & Maps Grounding Citations):",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate700,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    message.citations.forEach { citation ->
                        Surface(
                            color = SkySoft,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, SkyAccent.copy(alpha = 0.4f)),
                            modifier = Modifier.clickable { onOpenCitationUrl(citation.url) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = SkyAccent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = citation.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SkyAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (!isUser) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
                ) {
                    Surface(
                        color = SkySoft,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, SkyAccent.copy(alpha = 0.4f)),
                        modifier = Modifier.clickable {
                            onOpenCitationUrl("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
                        }
                    ) {
                        Text(
                            text = "📍 الموقع على Google Maps",
                            style = MaterialTheme.typography.labelSmall,
                            color = SkyAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }

                    Surface(
                        color = EmeraldSoft,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.4f)),
                        modifier = Modifier.clickable { onShareViaWaMe() }
                    ) {
                        Text(
                            text = "📲 إرسال عبر واتساب (wa.me)",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldDark,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ThreeJsDigitalTwinAndGoogleMapsSection(
    uiState: AgroSightUiState,
    onSelectValveSector: (Int) -> Unit,
    onSelectZonePreset: (EgyptianZonePreset) -> Unit,
    onAskGeminiAboutMapLocation: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var cameraElevationDeg by remember { mutableFloatStateOf(42f) }
    var cameraRotationDeg by remember { mutableFloatStateOf(28f) }

    // 1. Three.js 3D Interactive Farm Digital Twin (WebGL Scene + Interactive 3D Controls)
    HubBentoCard(modifier = Modifier.fillMaxWidth()) {
        HubSectionHeader(
            title = "مشهد التوأم الرقمي ثلاثي الأبعاد للمزرعة (Three.js 3D Farm & Soil Twin)",
            subtitle = "تمثيل ثلاثي الأبعاد لقطاعات المحابس الـ 6، كثافة الكلوروفيل (NDVI)، بؤر الإصابة، وطبقات رطوبة الجذور",
            badgeText = "Three.js WebGL 3D"
        )

        val threeJsHtml = remember(
            uiState.fieldName,
            uiState.epidemiologyReport.ndviMean,
            uiState.epidemiologyReport.affectedValveNumber,
            uiState.soilPh,
            cameraElevationDeg,
            cameraRotationDeg
        ) {
            buildThreeJsFarmSceneHtml(
                fieldName = uiState.fieldName,
                cropNameAr = uiState.selectedCrop.nameAr,
                ndviMean = uiState.epidemiologyReport.ndviMean,
                ndreMean = uiState.epidemiologyReport.ndreMean,
                psriMean = uiState.epidemiologyReport.psriMean,
                affectedValve = uiState.epidemiologyReport.affectedValveNumber,
                soilPh = uiState.soilPh,
                elevationDeg = cameraElevationDeg,
                rotationDeg = cameraRotationDeg
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, EmeraldPrimary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        webViewClient = WebViewClient()
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(
                        "https://cdnjs.cloudflare.com",
                        threeJsHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("threejs_3d_webview")
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { cameraRotationDeg = (cameraRotationDeg + 30f) % 360f }) {
                Text("تدوير الكاميرا 3D (+30°)")
            }
            OutlinedButton(onClick = { cameraElevationDeg = if (cameraElevationDeg > 60f) 25f else cameraElevationDeg + 15f }) {
                Text("زاوية الارتفاع (${cameraElevationDeg.toInt()}°)")
            }
            (1..6).forEach { vNum ->
                FilterChip(
                    selected = uiState.selectedValveNumber == vNum,
                    onClick = { onSelectValveSector(vNum) },
                    label = { Text("محبس $vNum") }
                )
            }
        }
    }

    // 2. Interactive Google Maps & Geospatial Field Grounding Section
    HubBentoCard(modifier = Modifier.fillMaxWidth()) {
        HubSectionHeader(
            title = "خريطة Google Maps التفاعلية وإحداثيات الحقل الفضائية",
            subtitle = "ربط مباشر مع Google Maps Satellite Hybrid ومضلعات المحابس الـ 6 وشات Gemini الذكي (${uiState.latitude}, ${uiState.longitude})",
            badgeText = uiState.activeFarmer.region
        )

        // Zone Presets Quick Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EGYPTIAN_ZONE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = uiState.selectedZoneId == preset.id,
                    onClick = { onSelectZonePreset(preset) },
                    label = { Text("${preset.nameAr} (${formatDecimal2(preset.latitude.toFloat())}N)") },
                    leadingIcon = {
                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                )
            }
        }

        val googleMapsHtml = remember(
            uiState.latitude,
            uiState.longitude,
            uiState.fieldName,
            uiState.selectedValveNumber,
            uiState.epidemiologyReport.ndviMean
        ) {
            buildInteractiveGoogleMapsLeafletHtml(
                latitude = uiState.latitude,
                longitude = uiState.longitude,
                fieldName = uiState.fieldName,
                cropNameAr = uiState.selectedCrop.nameAr,
                selectedValve = uiState.selectedValveNumber,
                ndviMean = uiState.epidemiologyReport.ndviMean,
                sectors = uiState.valveSectors
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(275.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, SkyAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(
                        "https://maps.google.com",
                        googleMapsHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("google_maps_satellite_webview")
            )
        }

        Text(
            text = "📍 الإحداثيات الفضائية: Lat ${uiState.latitude} • Lon ${uiState.longitude} (${uiState.areaFeddans.toInt()} فدان • محبس ${uiState.selectedValveNumber})",
            style = MaterialTheme.typography.labelMedium,
            color = Slate700,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onAskGeminiAboutMapLocation(
                        "حلل لي الموقع الفضائي على Google Maps عند (${uiState.latitude}, ${uiState.longitude}) لمحصول ${uiState.selectedCrop.nameAr} في ${uiState.fieldName} (محبس رقم ${uiState.selectedValveNumber}) وما أفضل خطة ري وتسميد؟"
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ask_gemini_from_3d_maps_tab_button")
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("اسأل شات Gemini عن هذا الموقع")
            }

            Button(
                onClick = {
                    try {
                        val uri = "https://www.google.com/maps/search/?api=1&query=${uiState.latitude},${uiState.longitude}".toUri()
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                modifier = Modifier
                    .weight(1f)
                    .testTag("open_external_google_maps_button")
            ) {
                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("فتح في Google Maps")
            }
        }
    }
}

@Composable
private fun AgroAcousticMusicGeneratorSection(
    uiState: AgroSightUiState,
    onTogglePlayback: () -> Unit,
    onSelectPreset: (String) -> Unit
) {
    var wavePhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(uiState.isAcousticGeneratorPlaying) {
        while (isActive) {
            if (uiState.isAcousticGeneratorPlaying) {
                wavePhase = (wavePhase + 0.18f) % (2f * PI.toFloat())
            }
            delay(40)
        }
    }

    HubBentoCard(modifier = Modifier.fillMaxWidth()) {
        HubSectionHeader(
            title = "مولد الترددات الصوتية والموسيقى الحيوية للمزرعة (Agro-Acoustic Music Generator)",
            subtitle = "توليد صوتي وتوافقي حيوي (PCM Audio Synthesizer) مشتق من مؤشر الكلوروفيل NDVI (${formatDecimal2(uiState.epidemiologyReport.ndviMean)}) ورطوبة المجموع الخضري",
            badgeText = if (uiState.isAcousticGeneratorPlaying) "🎵 يعمل الآن" else "متوقف"
        )

        val presets = listOf(
            Triple("SOLAR_CHLOROPHYLL_432HZ", "تناغم الكلوروفيل والتمثيل الضوئي (432 Hz)", "مشتق من NDVI=${formatDecimal2(uiState.epidemiologyReport.ndviMean)} والإشعاع الشمسي"),
            Triple("ROOT_OSMOSIS_528HZ", "نبض الامتصاص الأسموزي للجذور (528 Hz)", "متناغم مع ملوحة التربة ECe=${formatDecimal1(uiState.soilEce)} dS/m ومعادلة pH"),
            Triple("VALVE_HARMONIC_396HZ", "إيقاع التدفق الهيدروليكي للمحابس (396 Hz)", "يعكس استقرار ضغط الشبكة (${formatDecimal1(uiState.valveFlowRateM3PerFeddanHr)} م³/ساعة)")
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (id, title, desc) ->
                val selected = uiState.selectedAcousticPresetId == id
                Surface(
                    color = if (selected) EmeraldSoft else Slate100,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (selected) EmeraldPrimary else Slate200),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPreset(id) }
                        .testTag("acoustic_preset_$id")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) EmeraldDark else Slate900,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate600
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = if (selected) EmeraldPrimary else Slate500
                        )
                    }
                }
            }
        }

        // Harmonic Waveform Visualizer Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Slate900)
                .padding(12.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midY = h / 2f
                val path1 = Path()
                val path2 = Path()
                val steps = 80
                for (i in 0..steps) {
                    val x = (i.toFloat() / steps) * w
                    val norm = i.toFloat() / steps * 4f * PI.toFloat()
                    val amp1 = (h * 0.34f) * uiState.epidemiologyReport.ndviMean.coerceIn(0.3f, 0.95f)
                    val amp2 = (h * 0.22f) * uiState.epidemiologyReport.ndmiMean.coerceIn(0.2f, 0.9f)
                    val y1 = midY + sin(norm + wavePhase) * amp1
                    val y2 = midY + cos(norm * 1.5f - wavePhase) * amp2
                    if (i == 0) {
                        path1.moveTo(x, y1)
                        path2.moveTo(x, y2)
                    } else {
                        path1.lineTo(x, y1)
                        path2.lineTo(x, y2)
                    }
                }
                drawPath(path1, color = EmeraldLight, style = Stroke(width = 4f))
                drawPath(path2, color = CyanLight, style = Stroke(width = 2.5f))
                drawLine(
                    color = Slate700,
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1f
                )
            }
        }

        Button(
            onClick = onTogglePlayback,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isAcousticGeneratorPlaying) RoseDanger else EmeraldPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("toggle_acoustic_music_button")
        ) {
            Icon(
                imageVector = if (uiState.isAcousticGeneratorPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (uiState.isAcousticGeneratorPlaying) "إيقاف التوليد الموسيقي الحيوي" else "تشغيل الموسيقى والترددات الصوتية الحيوية للمزرعة",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun FirebaseCloudAndSqlDatabaseSection(
    uiState: AgroSightUiState,
    onTriggerGoogleSignIn: () -> Unit,
    onSignOutFirebase: () -> Unit,
    onSyncWorkspaceToFirestore: () -> Unit,
    onRecordRoomSqlSnapshot: () -> Unit,
    onDeleteRoomSqlAudit: (String) -> Unit,
    onOpenFullSqlSchemaModal: () -> Unit
) {
    // 1. Firebase Auth (Google Sign-In via Credential Manager) + Firestore Cloud Sync Card
    HubBentoCard(modifier = Modifier.fillMaxWidth()) {
        HubSectionHeader(
            title = "المصادقة السحابية وقاعدة بيانات Firebase Firestore",
            subtitle = "تسجيل الدخول بحساب Google الرسمي (Jetpack Credential Manager) ومزامنة بيانات المزرعة والمحادثات في Firestore",
            badgeText = if (uiState.firebaseUserUid != null) "متصل بالسحابة" else "جاهز للربط"
        )

        Surface(
            color = if (uiState.firebaseUserUid != null) EmeraldSoft else SkySoft,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (uiState.firebaseUserUid != null) EmeraldPrimary else SkyAccent),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = uiState.firebaseCloudStatusAr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate900,
                    fontWeight = FontWeight.Bold
                )
                if (uiState.firebaseUserUid != null) {
                    Text(
                        text = "UID: ${uiState.firebaseUserUid} • البريد: ${uiState.firebaseUserEmail ?: uiState.activeFarmer.gmailAddress}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldDark,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.firebaseUserUid == null) {
                Button(
                    onClick = onTriggerGoogleSignIn,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("firebase_google_signin_button")
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تسجيل الدخول بحساب Google (Credential Manager)", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onSyncWorkspaceToFirestore,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("firestore_manual_sync_button")
                ) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("مزامنة الحقل مع Firestore الآن", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onSignOutFirebase,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("firebase_signout_button")
                ) {
                    Text("خروج")
                }
            }
        }
    }

    // 2. Room SQL Relational Database Audit Table + PostgreSQL / Supabase DDL Export
    HubBentoCard(modifier = Modifier.fillMaxWidth()) {
        HubSectionHeader(
            title = "قاعدة البيانات العلائقية SQL (Room SQLite Engine + PostgreSQL DDL)",
            subtitle = "تخزين محلي مهيكل بلغة SQL لسجلات الفحص الطيفي والري مع إمكانية تصدير مخطط SQL الكامل",
            badgeText = "${uiState.roomSqlAudits.size} سجل SQL"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRecordRoomSqlSnapshot,
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("record_room_sql_snapshot_button")
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("حفظ لقطة الحقل في جدول SQL الآن", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onOpenFullSqlSchemaModal,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("open_sql_ddl_modal_button")
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("مخطط SQL DDL")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            uiState.roomSqlAudits.take(10).forEach { audit ->
                Surface(
                    color = Slate100,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${audit.fieldName} (${audit.cropType} • ${audit.areaFeddans.toInt()} فدان)",
                                style = MaterialTheme.typography.labelLarge,
                                color = Slate900,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "المزارع: ${audit.farmerName} • 📱 ${audit.whatsappPhone} • 🪪 ${audit.nationalId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                            Text(
                                text = "NDVI=${formatDecimal2(audit.ndviMean)} | NDRE=${formatDecimal2(audit.ndreMean)} | pH=${formatDecimal1(audit.soilPh)} | المقنن=${formatDecimal1(audit.grossM3PerFeddan)} م³/فدان",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldDark,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "التشخيص: ${audit.detectedRiskName} • ${audit.recordedAt}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberWarning
                            )
                        }
                        IconButton(onClick = { onDeleteRoomSqlAudit(audit.id) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "حذف سجل SQL",
                                tint = RoseDanger
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildThreeJsFarmSceneHtml(
    fieldName: String,
    cropNameAr: String,
    ndviMean: Float,
    ndreMean: Float,
    psriMean: Float,
    affectedValve: Int,
    soilPh: Float,
    elevationDeg: Float,
    rotationDeg: Float
): String {
    return """
<!DOCTYPE html>
<html dir="rtl">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<style>
  body { margin: 0; overflow: hidden; background: #09131c; font-family: 'Cairo', sans-serif; color: #fff; }
  #hud {
    position: absolute; top: 8px; right: 10px; left: 10px;
    display: flex; justify-content: space-between; align-items: center;
    background: rgba(15, 23, 42, 0.84); border: 1px solid rgba(16, 185, 129, 0.45);
    border-radius: 10px; padding: 6px 10px; font-size: 11px; pointer-events: none; z-index: 10;
  }
  .badge { color: #34d399; font-weight: bold; }
  .warn { color: #fb7185; font-weight: bold; }
</style>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
</head>
<body>
<div id="hud">
  <div>🛰️ <b>$fieldName</b> ($cropNameAr)</div>
  <div class="badge">NDVI: ${formatDecimal2(ndviMean)} | NDRE: ${formatDecimal2(ndreMean)} | pH: ${formatDecimal1(soilPh)}</div>
  <div class="warn">بؤرة محبس #$affectedValve</div>
</div>
<canvas id="c" style="width:100vw;height:100vh;display:block;"></canvas>
<script>
(function() {
  const canvas = document.getElementById('c');
  const affectedValve = $affectedValve;
  const ndvi = $ndviMean;
  const baseRot = ($rotationDeg * Math.PI) / 180.0;
  const baseElev = ($elevationDeg * Math.PI) / 180.0;

  if (typeof THREE !== 'undefined') {
    const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.setPixelRatio(window.devicePixelRatio || 1);

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x09131c);

    const camera = new THREE.PerspectiveCamera(45, window.innerWidth / window.innerHeight, 0.1, 100);
    const amb = new THREE.AmbientLight(0xffffff, 0.75);
    scene.add(amb);
    const dir = new THREE.DirectionalLight(0x38bdf8, 0.95);
    dir.position.set(10, 18, 12);
    scene.add(dir);

    const group = new THREE.Group();
    scene.add(group);

    // Soil base slab
    const soilGeo = new THREE.BoxGeometry(11.5, 0.6, 7.6);
    const soilMat = new THREE.MeshLambertMaterial({ color: 0x3b2f2f });
    const soilMesh = new THREE.Mesh(soilGeo, soilMat);
    soilMesh.position.y = -0.35;
    group.add(soilMesh);

    // 6 Valve Sectors (3x2 grid)
    let vIdx = 1;
    for (let r = 0; r < 2; r++) {
      for (let c = 0; c < 3; c++) {
        const isAffected = (vIdx === affectedValve);
        const h = isAffected ? 0.55 : (0.85 + (ndvi * 0.6));
        const geo = new THREE.BoxGeometry(3.3, h, 3.1);
        const col = isAffected ? 0xe11d48 : (vIdx % 2 === 0 ? 0x10b981 : 0x059669);
        const mat = new THREE.MeshPhongMaterial({ color: col, shininess: 40 });
        const mesh = new THREE.Mesh(geo, mat);
        mesh.position.set((c - 1) * 3.7, h / 2, (r - 0.5) * 3.6);
        group.add(mesh);
        vIdx++;
      }
    }

    let angle = baseRot;
    function animate() {
      requestAnimationFrame(animate);
      angle += 0.0045;
      const dist = 14.5;
      camera.position.x = Math.cos(angle) * dist * Math.cos(baseElev * 0.5);
      camera.position.z = Math.sin(angle) * dist * Math.cos(baseElev * 0.5);
      camera.position.y = 6.5 + Math.sin(baseElev) * 5.0;
      camera.lookAt(0, 0.4, 0);
      renderer.render(scene, camera);
    }
    animate();
  } else {
    // Fallback isometric 3D canvas renderer if offline
    const ctx = canvas.getContext('2d');
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
    let t = baseRot;
    function drawFallback() {
      ctx.fillStyle = '#09131c';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      const cx = canvas.width / 2;
      const cy = canvas.height / 2 + 25;
      for (let i = 0; i < 6; i++) {
        const row = Math.floor(i / 3);
        const col = i % 3;
        const isAff = (i + 1) === affectedValve;
        const x = cx + (col - 1) * 95 + (row - 0.5) * 35 * Math.cos(t);
        const y = cy + (row - 0.5) * 65;
        ctx.fillStyle = isAff ? '#e11d48' : '#10b981';
        ctx.fillRect(x - 36, y - (isAff ? 18 : 34), 72, isAff ? 36 : 52);
        ctx.strokeStyle = '#ffffff';
        ctx.strokeRect(x - 36, y - (isAff ? 18 : 34), 72, isAff ? 36 : 52);
      }
      t += 0.02;
      requestAnimationFrame(drawFallback);
    }
    drawFallback();
  }
})();
</script>
</body>
</html>
    """.trimIndent()
}

fun buildInteractiveGoogleMapsLeafletHtml(
    latitude: Double,
    longitude: Double,
    fieldName: String,
    cropNameAr: String,
    selectedValve: Int,
    ndviMean: Float,
    sectors: List<com.example.model.ValveSectorTelemetry>
): String {
    val sectorJsArray = if (sectors.isNotEmpty()) {
        sectors.joinToString(",") { sec ->
            "{v:${sec.valveNumber},name:'${sec.nameAr}',ndvi:${formatDecimal2(sec.ndvi)},ndwi:${formatDecimal2(sec.ndwi)},hrs:${formatDecimal1(sec.operatingHours)}}"
        }
    } else {
        (1..6).joinToString(",") { v ->
            "{v:$v,name:'محبس $v',ndvi:${formatDecimal2(ndviMean)},ndwi:0.24,hrs:2.3}"
        }
    }
    return """
<!DOCTYPE html>
<html dir="rtl">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <style>
    body, html { margin: 0; padding: 0; width: 100%; height: 100%; background: #07111e; font-family: sans-serif; }
    #map { width: 100%; height: 100%; }
    .hud-overlay {
      position: absolute; top: 8px; right: 8px; left: 8px; z-index: 999;
      background: rgba(15, 23, 42, 0.86); color: #fff; border: 1px solid #38bdf8;
      border-radius: 10px; padding: 6px 10px; font-size: 11px;
      display: flex; justify-content: space-between; align-items: center;
      pointer-events: none;
    }
    .gmaps-badge { color: #38bdf8; font-weight: bold; }
    .ndvi-badge { color: #34d399; font-weight: bold; }
  </style>
</head>
<body>
  <div class="hud-overlay">
    <div class="gmaps-badge">🗺️ Google Maps Satellite • $fieldName ($cropNameAr)</div>
    <div class="ndvi-badge">محبس #$selectedValve • NDVI ${formatDecimal2(ndviMean)}</div>
  </div>
  <div id="map"></div>
  <script>
    (function() {
      const lat = $latitude;
      const lon = $longitude;
      const selValve = $selectedValve;
      const sectors = [$sectorJsArray];

      if (typeof L !== 'undefined') {
        const map = L.map('map', { zoomControl: true, attributionControl: false }).setView([lat, lon], 16);
        L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
          maxZoom: 20
        }).addTo(L);

        const dLat = 0.0014;
        const dLon = 0.0016;
        sectors.forEach(function(sec, idx) {
          const row = Math.floor(idx / 3);
          const col = idx % 3;
          const sLat = lat + (0.5 - row) * dLat;
          const sLon = lon + (col - 1) * dLon;
          const isSelected = (sec.v === selValve);
          const color = sec.ndvi >= 0.62 ? '#10b981' : (sec.ndvi >= 0.50 ? '#f59e0b' : '#ef4444');
          const bounds = [
            [sLat - dLat * 0.44, sLon - dLon * 0.44],
            [sLat + dLat * 0.44, sLon + dLon * 0.44]
          ];
          const rect = L.rectangle(bounds, {
            color: isSelected ? '#ffffff' : color,
            weight: isSelected ? 3 : 1.5,
            fillColor: color,
            fillOpacity: isSelected ? 0.52 : 0.32
          }).addTo(map);
          rect.bindPopup('<b>' + sec.name + '</b><br/>NDVI: ' + sec.ndvi + ' | NDWI: ' + sec.ndwi + '<br/>زمن الري: ' + sec.hrs + ' ساعة');
          if (isSelected) {
            rect.openPopup();
          }
        });
      } else {
        document.getElementById('map').innerHTML =
          '<iframe width="100%" height="100%" frameborder="0" style="border:0;" src="https://maps.google.com/maps?q=' + lat + ',' + lon + '&t=k&z=16&output=embed"></iframe>';
      }
    })();
  </script>
</body>
</html>
    """.trimIndent()
}

