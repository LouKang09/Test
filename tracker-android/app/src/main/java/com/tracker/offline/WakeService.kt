package com.tracker.offline

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.*

/**
 * Offline two-stage voice service: constrained Vosk wake recognizer, then
 * free-speech Vosk transcription. Microphone level is measured from actual PCM.
 */
class WakeService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var speaking = false
    @Volatile private var suppressUntil = 0L
    @Volatile private var audio: AudioRecord? = null
    private var worker: Thread? = null
    private var tts: TextToSpeech? = null
    private val hz = 16000
    private val wakePattern = Regex(
        """\b(?:hey|hi|hay|he)\s+(?:tracker|track her|track|trackers|tractor)\b|\btracker\b""",
        RegexOption.IGNORE_CASE
    )

    private fun publish(action: () -> Unit) { ui.post(action) }
    private fun fail(message: String) {
        Log.e("TrackerVoice", message)
        publish {
            TrackerEvents.error.value = message
            TrackerEvents.voiceStatus.value = "Microphone or recognition error"
            TrackerEvents.micActive.value = false
            TrackerEvents.micLevel.intValue = 0
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tracker-voice-3", "Tracker always-ready voice", NotificationManager.IMPORTANCE_LOW)
        )
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { speaking = false; suppressUntil = SystemClock.elapsedRealtime() + 600 }
            override fun onError(id: String?) { speaking = false }
        })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            getSharedPreferences("tracker", MODE_PRIVATE).edit().putBoolean("voice_enabled", false).apply()
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, WakeService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, "tracker-voice-3")
            .setContentTitle("Tracker offline voice")
            .setContentText("Say Hey Tracker · tap to check live microphone")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(31, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(31, notification)
        if (worker?.isAlive == true) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission denied")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        publish {
            TrackerEvents.voiceStatus.value = "Loading offline voice model"
            TrackerEvents.error.value = ""
            TrackerEvents.micActive.value = false
            TrackerEvents.micLevel.intValue = 0
            TrackerEvents.wakePreview.value = "Waiting for audio input"
        }
        worker = Thread({ runEngine() }, "TrackerAudioLoop").also { it.start() }
        return START_NOT_STICKY
    }

    private fun copyAssets(path: String, dest: File) {
        val names = assets.list(path) ?: emptyArray()
        if (names.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { src ->
                dest.outputStream().use { dst -> src.copyTo(dst, 65536) }
            }
        } else {
            dest.mkdirs()
            for (name in names) copyAssets(path + "/" + name, File(dest, name))
        }
    }
    private fun openModel(): Model {
        val dir = File(filesDir, "vosk-model-small-en-us-0.15")
        val marker = File(dir, ".complete-v3")
        if (!marker.exists()) {
            if (dir.exists()) dir.deleteRecursively()
            copyAssets("vosk-model-small-en-us-0.15", dir)
            if (!File(dir, "am/final.mdl").isFile) error("Offline model incomplete")
            marker.writeText("ok")
        }
        return Model(dir.absolutePath)
    }
    @SuppressLint("MissingPermission")
    private fun createAudio(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            hz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) error("16kHz microphone unsupported: " + min)
        val bytes = max(min * 2, 8192)
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(src, hz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (ex: Exception) { Log.w("TrackerVoice", "Source failed: " + src, ex) }
        }
        error("Microphone unavailable: another app may have exclusive access")
    }
    private fun extract(json: String, key: String): String =
        try { JSONObject(json).optString(key, "").trim().lowercase(Locale.ROOT) }
        catch (_: Exception) { "" }
    private fun microphoneLevel(frame: ShortArray, count: Int): Int {
        var sum = 0.0
        for (i in 0 until count) { val s = frame[i].toDouble(); sum += s * s }
        val rms = sqrt(sum / count.coerceAtLeast(1))
        if (rms < 1.0) return 0
        val db = 20.0 * log10(rms / 32768.0)
        return (((db + 65.0) / 50.0) * 100).toInt().coerceIn(0, 100)
    }

    private fun runEngine() {
        var model: Model? = null
        try {
            model = openModel()
            if (!running) return
            var attempt = 0
            while (running && attempt < 3) {
                try {
                    capture(model)
                    if (running) error("Capture ended unexpectedly")
                } catch (ex: Exception) {
                    if (!running) break
                    attempt++
                    val info = "Capture failure " + attempt + "/3: " + (ex.message ?: "unknown")
                    Log.e("TrackerVoice", info, ex)
                    publish {
                        TrackerEvents.error.value = info
                        TrackerEvents.voiceStatus.value = "Retrying offline microphone"
                        TrackerEvents.micActive.value = false
                    }
                    if (attempt < 3) Thread.sleep(1000)
                }
            }
            if (running) {
                fail("Microphone could not capture audio. Check permission and close competing voice apps.")
                stopSelf()
            }
        } catch (ex: Exception) {
            if (running) {
                fail("Offline model error: " + (ex.message ?: "unknown"))
                stopSelf()
            }
        } finally { try { model?.close() } catch (_: Exception) {} }
    }

    private fun capture(model: Model) {
        val wake = Recognizer(model, hz.toFloat(),
            """["hey tracker","hi tracker","hey track her","hey track","tracker","[unk]"]""")
        var command: Recognizer? = null
        var record: AudioRecord? = null
        val memory = ArrayDeque<ShortArray>()
        val buffer = ShortArray(3200)
        var expiresAt = 0L
        var lastMeterAt = 0L
        var lastSubmittedAt = 0L
        var lastAttempted = ""

        fun ready() {
            try { command?.close() } catch (_: Exception) {}
            command = null
            expiresAt = 0L
            lastAttempted = ""
            wake.reset()
            publish { TrackerEvents.voiceStatus.value = "Listening for Hey Tracker" }
        }
        fun submit(transcript: String): Boolean {
            val clean = wakePattern.replaceFirst(transcript, "").trim()
            if (clean.isBlank() || lastAttempted == clean) return false
            lastAttempted = clean
            publish { TrackerEvents.heard.value = clean.take(150) }
            val expense = CommandParser.parse(clean) ?: return false
            val now = SystemClock.elapsedRealtime()
            if (now - lastSubmittedAt < 3500L) return false
            lastSubmittedAt = now
            try {
                ExpenseStore(applicationContext).use { db ->
                    db.add(expense.amount, expense.category, expense.item)
                    val spent = BudgetLogic.spentToday(db.all())
                    val remains = getSharedPreferences("tracker", MODE_PRIVATE).getInt("budget", 1000) - spent
                    publish {
                        TrackerEvents.saved()
                        TrackerEvents.voiceStatus.value = "Saved " + expense.amount + " pesos · " + expense.item
                    }
                    suppressUntil = SystemClock.elapsedRealtime() + 1300L
                    tts?.speak(
                        "Saved " + expense.amount + " pesos for " + expense.item + ". " + remains + " pesos left.",
                        TextToSpeech.QUEUE_FLUSH, null, "tracker-saved"
                    )
                }
                return true
            } catch (ex: Exception) {
                fail("Expense not saved: " + (ex.message ?: "unknown"))
                return false
            }
        }

        try {
            record = createAudio()
            audio = record
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                error("Android microphone did not enter RECORDING state")
            publish {
                TrackerEvents.micActive.value = true
                TrackerEvents.error.value = ""
                TrackerEvents.voiceStatus.value = "Mic is recording · say Hey Tracker"
            }
            while (running && !Thread.currentThread().isInterrupted) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error("Microphone read error " + count)
                if (count == 0) continue
                val now = SystemClock.elapsedRealtime()
                val level = microphoneLevel(buffer, count)
                if (now - lastMeterAt >= 250) {
                    lastMeterAt = now
                    publish {
                        TrackerEvents.micLevel.intValue = level
                        TrackerEvents.lastAudioMs.longValue = SystemClock.elapsedRealtime()
                        TrackerEvents.micActive.value = true
                    }
                }
                if (speaking || now < suppressUntil) continue
                memory.addLast(buffer.copyOf(count))
                while (memory.size > 10) memory.removeFirst()

                if (command == null) {
                    val ended = wake.acceptWaveForm(buffer, count)
                    val candidate = extract(
                        if (ended) wake.getResult() else wake.getPartialResult(),
                        if (ended) "text" else "partial"
                    )
                    if (candidate.isNotBlank()) {
                        publish { TrackerEvents.wakePreview.value = candidate.take(70) }
                    }
                    if (wakePattern.containsMatchIn(candidate)) {
                        command = Recognizer(model, hz.toFloat())
                        expiresAt = now + 13000
                        for (chunk in memory) command?.acceptWaveForm(chunk, chunk.size)
                        memory.clear()
                        publish {
                            TrackerEvents.wakeEvents.intValue++
                            TrackerEvents.voiceStatus.value = "Hey Tracker heard · listening for expense"
                            TrackerEvents.wakePreview.value = candidate
                        }
                    }
                } else {
                    val transcriber = command ?: continue
                    val ended = transcriber.acceptWaveForm(buffer, count)
                    val words = extract(
                        if (ended) transcriber.getResult() else transcriber.getPartialResult(),
                        if (ended) "text" else "partial"
                    )
                    if (words.isNotBlank()) {
                        publish { TrackerEvents.heard.value = words.take(150) }
                        if (ended && submit(words)) {
                            ready()
                            continue
                        }
                    }
                    if (now >= expiresAt) {
                        val last = extract(transcriber.getFinalResult(), "text")
                        if (!submit(last)) {
                            publish {
                                TrackerEvents.voiceStatus.value = "Expense not understood · try Hey Tracker again"
                                if (last.isNotEmpty()) TrackerEvents.heard.value = last.take(150)
                            }
                        }
                        ready()
                    }
                }
            }
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
            audio = null
            try { command?.close() } catch (_: Exception) {}
            try { wake.close() } catch (_: Exception) {}
            publish { TrackerEvents.micActive.value = false; TrackerEvents.micLevel.intValue = 0 }
        }
    }

    override fun onDestroy() {
        running = false
        try { audio?.stop() } catch (_: Exception) {}
        worker?.interrupt()
        tts?.stop()
        tts?.shutdown()
        publish {
            TrackerEvents.micActive.value = false
            TrackerEvents.micLevel.intValue = 0
            if (TrackerEvents.error.value.isEmpty()) TrackerEvents.voiceStatus.value = "Voice listener stopped"
        }
        super.onDestroy()
    }
}
