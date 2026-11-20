package com.cosmerearchives.wobtracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * One voice session per WoB. Only the transcript is read during the first phase.
 * The prompts are spoken separately. No review is saved until a spoken "save"
 * confirms the recognized tags, classification, and junk decision.
 */
internal class HandsFreeReviewSession(
    private val context: Context,
    private val entry: SavedWob,
    private val library: WobLibrary,
    initialTags: String,
    private val initialNotes: String,
    initialCosmere: Boolean,
    initialExtended: Boolean,
    initialWritingExcuses: Boolean,
    private val updateStatus: (String) -> Unit,
    private val onAdvance: (String, Boolean) -> Unit,
    private val onStop: () -> Unit
) : RecognitionListener {
    private enum class Phase { TAGS, CLASSIFICATION, JUNK, CONFIRM }
    private val main = Handler(Looper.getMainLooper())
    private var stopped = false
    private var phase = Phase.TAGS
    private var lastQuestion = ""
    private var lastUtterance = ""
    private var serial = 0
    private var speakingReady = false
    private var listening = false
    private val startingTags = initialTags
    private var tags = initialTags
    private var cosmere = initialCosmere
    private var extended = initialExtended
    private var writingExcuses = initialWritingExcuses
    private var junk = entry.junk
    private val tts: TextToSpeech
    private val recognizer: SpeechRecognizer?

    init {
        recognizer = try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(this) }
            } else null
        } catch (_: Exception) { null }
        tts = TextToSpeech(context) { result ->
            main.post {
                if (!stopped) {
                    if (result == TextToSpeech.SUCCESS) {
                        if (recognizer == null) {
                            fail("Android speech recognition is unavailable. Enable a speech recognition service to use Hands-Free Mode.")
                        } else {
                            speakingReady = true
                            readTranscript()
                        }
                    } else fail("Text-to-speech isn't available on this device.")
                }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post {
                    if (!stopped && utteranceId == lastUtterance) listen()
                }
            }
            @Deprecated("Deprecated in Android API")
            override fun onError(utteranceId: String?) {
                main.post { if (!stopped && utteranceId == lastUtterance) fail("Voice playback failed. Stop and retry.") }
            }
        })
    }

    private fun fail(message: String) {
        updateStatus(message)
        stop()
        onStop()
    }

    private fun speechChunks(value: String): List<String> {
        val text = value.trim()
        if (text.isEmpty()) return listOf("This WoB has no transcript.")
        val chunks = mutableListOf<String>()
        var at = 0
        while (at < text.length) {
            if (text.length - at <= 2800) {
                chunks.add(text.substring(at))
                break
            }
            val limit = at + 2800
            val whitespace = text.lastIndexOf(' ', limit)
            val end = if (whitespace > at + 1300) whitespace else limit
            chunks.add(text.substring(at, end))
            at = end
            while (at < text.length && text[at] == ' ') at++
        }
        return chunks
    }

    private fun readTranscript() {
        if (stopped || !speakingReady) return
        phase = Phase.TAGS
        val question = "What tags would you like? Say tags separated by the word comma, or say none. You can also say skip or stop."
        lastQuestion = question
        say(speechChunks(entry.transcript) + question, Phase.TAGS, "Reading transcript")
    }

    private fun say(texts: List<String>, nextPhase: Phase, label: String) {
        if (stopped) return
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        phase = nextPhase
        updateStatus(label)
        val utterance = "handsfree-${++serial}"
        lastUtterance = utterance
        texts.forEachIndexed { index, text ->
            val id = if (index == texts.lastIndex) utterance else "$utterance-part-$index"
            val result = tts.speak(
                text,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                id
            )
            if (result == TextToSpeech.ERROR) {
                fail("Could not speak this WoB. Check your text-to-speech settings.")
                return
            }
        }
    }

    private fun ask(question: String, nextPhase: Phase) {
        lastQuestion = question
        say(listOf(question), nextPhase, question)
    }

    private fun listen() {
        if (stopped) return
        if (recognizer == null) {
            fail("No speech recognition service is available. Install or enable Android speech recognition.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            listening = true
            updateStatus("Listening: ${when (phase) {
                Phase.TAGS -> "tags (or none)"
                Phase.CLASSIFICATION -> "work classification"
                Phase.JUNK -> "junk: yes or no"
                Phase.CONFIRM -> "save, change, skip, or stop"
            }}")
            recognizer.startListening(intent)
        } catch (e: Exception) {
            listening = false
            fail("Couldn't start microphone recognition: ${e.javaClass.simpleName}")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() { updateStatus("Listening to your response…") }
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { updateStatus("Processing your response…") }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onError(error: Int) {
        if (stopped || !listening) return
        listening = false
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            fail("Microphone permission is required for Hands-Free Mode.")
            return
        }
        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            updateStatus("Speech recognition became unavailable. Tap Stop, then restart Hands-Free Mode.")
            return
        }
        ask("I didn't catch that. ${lastQuestion}", phase)
    }

    override fun onResults(results: Bundle?) {
        if (stopped || !listening) return
        listening = false
        val response = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (response.isBlank()) {
            ask("I didn't catch that. ${lastQuestion}", phase)
            return
        }
        process(response)
    }

    private fun process(response: String) {
        val value = response.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
        when (value) {
            "stop", "stop reviewing", "exit", "exit hands free", "cancel hands free" -> {
                stop()
                onStop()
                return
            }
            "skip", "skip this", "skip this one", "next", "next wob" -> {
                stop()
                onAdvance("Skipped without saving.", false)
                return
            }
            "repeat wob", "read again", "reread", "repeat transcript" -> {
                readTranscript()
                return
            }
            "repeat", "repeat question" -> {
                ask(lastQuestion, phase)
                return
            }
        }
        when (phase) {
            Phase.TAGS -> {
                if (value !in listOf("none", "no tags", "nothing", "no", "skip tags")) {
                    val spokenTags = response.replace(Regex("\\s+comma\\s+", RegexOption.IGNORE_CASE), ",")
                        .split(',', ';')
                        .map { it.trim() }.filter { it.isNotBlank() }
                    spokenTags.forEach { tags = appendPersonalTag(tags, it) }
                }
                ask("Which body of work? Say Cosmere, Extended Works, Writing Excuses, or multiple of those.", Phase.CLASSIFICATION)
            }
            Phase.CLASSIFICATION -> {
                val cos = value.contains("cosmere")
                val ext = value.contains("extended work")
                val writing = value.contains("writing excuse")
                if (!cos && !ext && !writing) {
                    ask("I need at least one classification. Say Cosmere, Extended Works, Writing Excuses, or a combination.", Phase.CLASSIFICATION)
                    return
                }
                // The answer replaces the classifications, rather than silently preserving
                // unrelated ones from a previous attempt.
                cosmere = cos
                extended = ext
                writingExcuses = writing
                ask("Is this junk? Say yes or no.", Phase.JUNK)
            }
            Phase.JUNK -> {
                when (value) {
                    "yes", "yeah", "yep", "it is", "junk", "mark junk" -> junk = true
                    "no", "nope", "not junk", "keep", "no it isn't" -> junk = false
                    else -> {
                        ask("Is this junk? Please say yes or no.", Phase.JUNK)
                        return
                    }
                }
                val classification = listOfNotNull(
                    if (cosmere) "Cosmere" else null,
                    if (extended) "Extended Works" else null,
                    if (writingExcuses) "Writing Excuses" else null
                ).joinToString(" and ")
                val spokenTags = tags.trim().trimEnd(',').ifBlank { "none" }
                ask(
                    "I heard tags: $spokenTags. Classification: $classification. " +
                        if (junk) "This is junk. Say save, change, skip, or stop."
                        else "This needs export. Say save, change, skip, or stop.",
                    Phase.CONFIRM
                )
            }
            Phase.CONFIRM -> {
                when (value) {
                    "save", "confirm", "yes", "save and next", "mark reviewed" -> {
                        try {
                            if (library.completeReview(
                                    entry.id, tags, initialNotes, !junk,
                                    cosmere, extended, junk, writingExcuses
                                )) {
                                stop()
                                onAdvance(if (junk) "Saved to Junk." else "Reviewed and queued for export.", true)
                            } else {
                                fail("Review could not be saved; this WoB remains pending.")
                            }
                        } catch (e: Exception) {
                            fail("Review save failed; this WoB remains pending.")
                        }
                    }
                    "change", "redo", "start over", "edit" -> {
                        tags = startingTags
                        readTranscript()
                    }
                    else -> ask("Say save to confirm, change to start again, skip, or stop.", Phase.CONFIRM)
                }
            }
        }
    }

    fun repeat() {
        if (stopped) return
        if (phase == Phase.TAGS) readTranscript() else ask(lastQuestion, phase)
    }

    fun skip() {
        if (stopped) return
        stop()
        onAdvance("Skipped without saving.", false)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        listening = false
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
    }
}

/**
 * The parent keeps active=true across WoBs, so a new session starts automatically
 * after save or skip. Disposing a session always stops the mic and TTS.
 */
@Composable
fun HandsFreeReview(
    library: WobLibrary,
    entry: SavedWob,
    initialTags: String,
    initialNotes: String,
    initialCosmere: Boolean,
    initialExtended: Boolean,
    initialWritingExcuses: Boolean,
    onAdvance: (String, Boolean) -> Unit,
    onStop: () -> Unit
) {
    var status by remember(entry.id) { mutableStateOf("Preparing hands-free review…") }
    val updatedAdvance by rememberUpdatedState(onAdvance)
    val updatedStop by rememberUpdatedState(onStop)
    val context = LocalContext.current
    val session = remember(entry.id) {
        HandsFreeReviewSession(
            context = context,
            entry = entry, library = library,
            initialTags = initialTags, initialNotes = initialNotes,
            initialCosmere = initialCosmere, initialExtended = initialExtended,
            initialWritingExcuses = initialWritingExcuses,
            updateStatus = { status = it },
            onAdvance = { message, saved -> updatedAdvance(message, saved) },
            onStop = { updatedStop() }
        )
    }
    DisposableEffect(session) {
        onDispose { session.stop() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Hands-Free Mode", style = MaterialTheme.typography.titleMedium)
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { session.repeat() }) { Text("Repeat") }
            OutlinedButton(onClick = { session.skip() }) { Text("Skip") }
            TextButton(onClick = { session.stop(); onStop() }) { Text("Stop") }
        }
    }
}
