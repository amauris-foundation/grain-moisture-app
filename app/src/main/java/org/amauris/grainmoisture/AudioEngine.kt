package org.amauris.grainmoisture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One measurement of the returned tone at a single frequency. */
data class Measurement(
    val freqHz: Double,
    /** Tone amplitude at the mic, as a fraction of full scale (0..1). */
    val amplitude: Double,
    /** Same amplitude in dB relative to full scale. This is the main reading. */
    val levelDb: Double,
    /** Spread of the per-block levels in dB: small = steady reading. */
    val stdDb: Double,
    /** Largest absolute sample, fraction of full scale. Near 1.0 = clipping. */
    val peak: Double,
    /** Broadband RMS level in dBFS (everything the mic hears, not just the tone). */
    val rmsDb: Double,
    val blocks: Int,
)

/**
 * Continuous sine generator. The measuring tone goes out on the LEFT channel, which is
 * the plug TIP; in the cell wiring the tip feeds the series resistor to the bolt.
 * The RIGHT channel (ring 1) is silent unless [rightLevel] is set, which only the
 * wire finder does. Frequencies and levels can be changed while it runs without clicks.
 */
class ToneGenerator(private val sampleRate: Int) {
    @Volatile var frequency = 10000.0
    @Volatile var level = 0.5 // 0..1 of digital full scale
    @Volatile var rightFrequency = 2300.0
    @Volatile var rightLevel = 0.0
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    val isRunning: Boolean get() = running

    fun start(outputDevice: AudioDeviceInfo?) {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (outputDevice != null) t.setPreferredDevice(outputDevice)
        track = t
        running = true
        t.play()
        val th = Thread {
            val frames = 1024
            val buf = ShortArray(frames * 2)
            var phase = 0.0
            var phaseR = 0.0
            while (running) {
                val a = level.coerceIn(0.0, 1.0) * 32767.0 * 0.98
                val inc = 2.0 * PI * frequency / sampleRate
                val aR = rightLevel.coerceIn(0.0, 1.0) * 32767.0 * 0.98
                val incR = 2.0 * PI * rightFrequency / sampleRate
                for (i in 0 until frames) {
                    buf[2 * i] = (sin(phase) * a).toInt().toShort()
                    buf[2 * i + 1] = (sin(phaseR) * aR).toInt().toShort()
                    phase += inc
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                    phaseR += incR
                    if (phaseR > 2.0 * PI) phaseR -= 2.0 * PI
                }
                t.write(buf, 0, buf.size)
            }
        }
        th.priority = Thread.MAX_PRIORITY
        th.start()
        thread = th
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        thread = null
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
    }
}

/**
 * Opens the headset mic (unprocessed when the phone supports it), plays the tone,
 * and measures the tone's amplitude coming back on the mic line.
 */
class AudioEngine(context: Context) {
    val sampleRate = 48000
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val tone = ToneGenerator(sampleRate)
    @Volatile private var record: AudioRecord? = null

    var sourceName = ""
        private set

    /** What the app managed to do about the phone's own audio processing. */
    var effectsStatus = ""
        private set

    /** Mic mode chosen on the Setup tab; one of [MIC_MODES]. */
    @Volatile var micMode = MIC_MODES[0]

    private val effects = mutableListOf<AudioEffect>()

    val isOpen: Boolean get() = record != null

    fun unprocessedSupported(): Boolean =
        am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    fun headsetInput(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in HEADSET_TYPES }

    fun headsetOutput(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HEADSET_TYPES }

    /**
     * Recording sources to try, in order. Every choice avoids the phone's voice processing
     * as far as Android allows; "Auto" prefers unprocessed, then voice performance (meant
     * for live music, normally no noise suppression), then voice recognition.
     */
    private fun sourceOrder(): List<Pair<Int, String>> {
        val unprocessed = MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED"
        val performance = 10 to "VOICE_PERFORMANCE" // MediaRecorder.AudioSource.VOICE_PERFORMANCE, Android 10+
        val recognition = MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION"
        val mic = MediaRecorder.AudioSource.MIC to "MIC"
        val camcorder = MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER"
        val canPerform = Build.VERSION.SDK_INT >= 29
        return when (micMode) {
            "Unprocessed" -> listOf(unprocessed, recognition)
            "Voice performance" -> if (canPerform) listOf(performance, recognition) else listOf(recognition)
            "Voice recognition" -> listOf(recognition)
            "Mic" -> listOf(mic, recognition)
            "Camcorder" -> listOf(camcorder, recognition)
            else -> buildList {
                if (unprocessedSupported()) add(unprocessed)
                if (canPerform) add(performance)
                add(recognition)
                add(mic)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun open() {
        close()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        var opened: AudioRecord? = null
        for ((src, name) in sourceOrder()) {
            val r = try {
                AudioRecord(
                    src, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf, sampleRate * 2)
                )
            } catch (_: Exception) {
                null
            } ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                opened = r
                sourceName = name
                break
            }
            r.release()
        }
        val r = opened ?: throw IllegalStateException("The microphone could not be opened")
        effectsStatus = disableEffects(r.audioSessionId)
        headsetInput()?.let { r.setPreferredDevice(it) }
        r.startRecording()
        record = r
        tone.start(headsetOutput())
    }

    /** Turns off noise suppression, auto gain and echo cancelling where Android exposes them. */
    private fun disableEffects(sessionId: Int): String {
        val parts = mutableListOf<String>()
        fun off(label: String, available: Boolean, make: () -> AudioEffect?) {
            if (!available) {
                parts.add("$label: none")
                return
            }
            val fx = try {
                make()
            } catch (_: Exception) {
                null
            }
            if (fx == null) {
                parts.add("$label: couldn't control")
                return
            }
            try {
                fx.setEnabled(false)
            } catch (_: Exception) {
            }
            effects.add(fx)
            parts.add(if (fx.enabled) "$label: STILL ON" else "$label: off")
        }
        off("Noise suppression", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
        off("Auto gain", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
        off("Echo cancelling", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
        return parts.joinToString(" · ")
    }

    fun close() {
        tone.stop()
        effects.forEach {
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        effects.clear()
        record?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        record = null
    }

    /** Which input the phone actually routed the recording to. */
    fun routedInput(): AudioDeviceInfo? = record?.routedDevice

    fun routedInputName(): String =
        routedInput()?.let { "${typeName(it.type)} (${it.productName})" } ?: "none"

    /**
     * Sets the tone frequency, waits [settleMs] for things to settle, then measures
     * for about [durationMs] in 100 ms blocks.
     */
    fun measure(freq: Double, settleMs: Int, durationMs: Int): Measurement {
        val r = record ?: throw IllegalStateException("Audio is not running")
        tone.frequency = freq

        val settleSamples = sampleRate * settleMs / 1000
        val scratch = ShortArray(2048)
        var discarded = 0
        while (discarded < settleSamples) {
            val n = r.read(scratch, 0, scratch.size)
            if (n <= 0) throw IllegalStateException("Microphone read failed ($n)")
            discarded += n
        }

        val block = sampleRate / 10
        val nBlocks = max(1, (sampleRate.toLong() * durationMs / 1000 / block).toInt())
        val data = ShortArray(block)
        val amps = DoubleArray(nBlocks)
        var peak = 0.0
        var sumSq = 0.0
        var count = 0L
        for (b in 0 until nBlocks) {
            var filled = 0
            while (filled < block) {
                val n = r.read(data, filled, block - filled)
                if (n <= 0) throw IllegalStateException("Microphone read failed ($n)")
                filled += n
            }
            for (s in data) {
                val v = s / 32768.0
                val av = abs(v)
                if (av > peak) peak = av
                sumSq += v * v
            }
            count += block
            amps[b] = toneAmplitude(data, freq, sampleRate)
        }
        val blockDbs = amps.map { toDb(it) }
        val avgDb = blockDbs.average()
        val std = sqrt(blockDbs.map { (it - avgDb).pow(2) }.average())
        val meanAmp = amps.average()
        val rms = sqrt(sumSq / count)
        return Measurement(freq, meanAmp, toDb(meanAmp), std, peak, toDb(rms), nBlocks)
    }

    /** Result of [listen]: level of each requested tone, plus broadband level, in dBFS. */
    data class Snapshot(val levelsDb: List<Double>, val rmsDb: Double, val peak: Double)

    /**
     * Reads the next [durationMs] of mic audio (no settling) and returns the level of each
     * frequency in [freqs]. Used by the wire finder, which listens for two tones at once.
     */
    fun listen(freqs: List<Double>, durationMs: Int): Snapshot {
        val r = record ?: throw IllegalStateException("Audio is not running")
        val n = max(1024, sampleRate * durationMs / 1000)
        val data = ShortArray(n)
        var filled = 0
        while (filled < n) {
            val got = r.read(data, filled, n - filled)
            if (got <= 0) throw IllegalStateException("Microphone read failed ($got)")
            filled += got
        }
        var peak = 0.0
        var sumSq = 0.0
        for (s in data) {
            val v = s / 32768.0
            if (abs(v) > peak) peak = abs(v)
            sumSq += v * v
        }
        val levels = freqs.map { toDb(toneAmplitude(data, it, sampleRate)) }
        return Snapshot(levels, toDb(sqrt(sumSq / n)), peak)
    }

    companion object {
        val MIC_MODES = listOf("Auto", "Voice performance", "Unprocessed", "Voice recognition", "Mic", "Camcorder")

        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )

        fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone's built-in mic"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones (no mic)"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            else -> "Device type $type"
        }

        fun toDb(amplitude: Double): Double = 20.0 * log10(max(amplitude, 1e-9))

        private val windows = HashMap<Int, DoubleArray>()

        @Synchronized
        private fun hann(n: Int): DoubleArray = windows.getOrPut(n) {
            DoubleArray(n) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)) }
        }

        /**
         * Amplitude (fraction of full scale) of a sine at [freq] in [x], using a
         * Hann-windowed single-frequency DFT. The window keeps mains hum and its
         * harmonics from leaking into the reading.
         */
        fun toneAmplitude(x: ShortArray, freq: Double, fs: Int): Double {
            val n = x.size
            val win = hann(n)
            val w = 2.0 * PI * freq / fs
            val c = cos(w)
            val s = sin(w)
            var cr = 1.0
            var ci = 0.0
            var re = 0.0
            var im = 0.0
            var winSum = 0.0
            for (i in 0 until n) {
                val v = x[i] / 32768.0 * win[i]
                winSum += win[i]
                re += v * cr
                im -= v * ci
                val ncr = cr * c - ci * s
                ci = cr * s + ci * c
                cr = ncr
            }
            return 2.0 * sqrt(re * re + im * im) / winSum
        }
    }
}
