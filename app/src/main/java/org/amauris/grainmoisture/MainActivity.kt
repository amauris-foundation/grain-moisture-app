package org.amauris.grainmoisture

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : Activity() {

    private data class Meta(val grain: String, val sample: String, val refill: Int)

    private lateinit var engine: AudioEngine
    private lateinit var store: Store
    private lateinit var am: AudioManager
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var busy = false
    @Volatile private var liveRunning = false
    @Volatile private var pendingSave: Meta? = null
    @Volatile private var curFreq = 10000.0
    @Volatile private var curTone = 0.5
    @Volatile private var curVol = 0
    @Volatile private var lastVolActual = 0
    @Volatile private var volWarned = false
    @Volatile private var wireRunning = false
    @Volatile private var lastButtonAt = 0L

    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null

    // Sample bar (shared by all tabs)
    private lateinit var grainSpinner: Spinner
    private lateinit var sampleInput: EditText
    private lateinit var refillText: TextView
    private var refill = 1

    // Measure tab
    private lateinit var freqInput: EditText
    private lateinit var toneLabel: TextView
    private lateinit var volLabel: TextView
    private lateinit var liveBig: TextView
    private lateinit var liveDetail: TextView
    private lateinit var liveBtn: Button

    // Sweep tab
    private lateinit var sweepFreqs: EditText
    private lateinit var sweepOut: TextView
    private lateinit var sweepBtn: Button

    // Setup tab
    private lateinit var setupStatus: TextView
    private lateinit var setupResult: TextView

    // Wire finder (Setup tab)
    private lateinit var wireBtn: Button
    private lateinit var wireStatus: TextView
    private lateinit var wireLevels: TextView
    private lateinit var wireButtonLog: TextView

    // Calibrate tab
    private lateinit var calSampleSpinner: Spinner
    private lateinit var calMoisture: EditText
    private lateinit var calMethod: Spinner
    private lateinit var calList: TextView
    private lateinit var plotFreqSpinner: Spinner
    private lateinit var plot: PlotView
    private lateinit var plotInfo: TextView
    private var plotFreqs: List<Double> = emptyList()

    // Data tab
    private lateinit var dataSummary: TextView
    private lateinit var dataRecent: TextView

    private val tabViews = mutableListOf<View>()
    private val tabButtons = mutableListOf<Button>()
    private var currentTab = 0

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshSetup()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshSetup()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        engine = AudioEngine(this)
        store = Store(this)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        curVol = (maxVol * 0.6).roundToInt().coerceIn(1, maxVol)

        setContentView(buildUi(maxVol))
        showTab(0)

        if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        am.registerAudioDeviceCallback(deviceCallback, ui)
        refreshSetup()
        checkForUpdate(silent = true)
    }

    override fun onPause() {
        super.onPause()
        liveRunning = false
        wireRunning = false
    }

    override fun onDestroy() {
        liveRunning = false
        wireRunning = false
        am.unregisterAudioDeviceCallback(deviceCallback)
        worker.execute { engine.close() }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup()
    }

    /** While audio is running, swallow volume and headset-button presses. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (engine.isOpen && event.keyCode in BLOCKED_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in HEADSET_KEYS) onHeadsetButton()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- UI building

    private fun buildUi(maxVol: Int): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Sample bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.rgb(232, 245, 233))
        }
        grainSpinner = spinner(GRAINS)
        bar.addView(row(label("Grain"), grainSpinner))
        sampleInput = EditText(this).apply {
            setSingleLine()
            hint = "Sample label"
            setText("A")
        }
        refillText = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        updateRefill()
        val minus = smallButton("−") { if (refill > 1) refill--; updateRefill() }
        val plus = smallButton("+") { refill++; updateRefill() }
        val sampleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sampleRow.addView(label("Sample"))
        sampleRow.addView(sampleInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sampleRow.addView(minus)
        sampleRow.addView(refillText)
        sampleRow.addView(plus)
        bar.addView(sampleRow)
        root.addView(bar)

        // Tab buttons
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        TAB_NAMES.forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name
                textSize = 12f
                isAllCaps = false
                setOnClickListener { showTab(i) }
            }
            tabRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tabButtons.add(b)
        }
        root.addView(tabRow)

        val frame = FrameLayout(this)
        val pages = listOf(
            buildSetupTab(), buildMeasureTab(maxVol), buildSweepTab(), buildCalibrateTab(), buildDataTab()
        )
        for (page in pages) {
            val sv = ScrollView(this)
            sv.addView(page)
            frame.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            tabViews.add(sv)
        }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildSetupTab(): View {
        val p = page()
        p.addView(heading("Wire finder"))
        p.addView(
            body(
                "Use this first, to find which coloured wire is which after you cut the headset cable. " +
                    "No multimeter needed, and brief touches can't harm the phone."
            )
        )
        p.addView(
            hint(
                "1. Cut the cable between the plug and the remote, strip the wires and burn off any " +
                    "coloured coating. Keep the bare ends apart.\n" +
                    "2. Plug in the adapter and tap Start. Keep the wires apart for 2 seconds while it listens.\n" +
                    "3. The app can only hear the MIC wire, so every test must include it. On Apple EarPods " +
                    "it is usually the white wire with copper wrapped around it.\n" +
                    "4. Touch each other wire to the mic wire, one at a time:\n" +
                    "   • LEFT tone (strong) → that wire is TIP, the tone wire\n" +
                    "   • RIGHT tone (strong) → that wire is RING 1, not used\n" +
                    "   • Button press → that wire is GROUND (all the copper wires are usually ground)\n" +
                    "Pairs that don't include the mic wire show nothing. That's normal.\n" +
                    "A long touch on ground can open the voice assistant. Keep touches short."
            )
        )
        wireBtn = button("Start wire finder") { toggleWireFinder() }
        p.addView(wireBtn)
        wireStatus = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        p.addView(wireStatus)
        wireButtonLog = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(198, 40, 40))
        }
        p.addView(wireButtonLog)
        wireLevels = mono("")
        p.addView(wireLevels)

        p.addView(heading("Setup check"))
        setupStatus = body("")
        p.addView(setupStatus)
        p.addView(button("Refresh") { refreshSetup() })
        p.addView(button("Test signal (tone on and off)") { runSignalTest() })
        setupResult = mono("")
        p.addView(setupResult)
        p.addView(heading("Wiring (no resistor needed)"))
        p.addView(
            body(
                "Tip (left audio) = tone → the CAN\n" +
                    "Sleeve (next to cable) = mic → the centre BOLT\n" +
                    "Ring 2 (ground) and Ring 1 (right audio) → not connected, tape them off"
            )
        )
        p.addView(
            hint(
                "Apple EarPods colours: green = tone → can, white = mic → bolt. Twist all the coppers " +
                    "together and tape them off with the red. Make sure copper never touches the can: that " +
                    "would short the tone. The phone's own mic resistor and the paddy form the measuring " +
                    "circuit, so wetter paddy gives a STRONGER signal."
            )
        )
        p.addView(heading("App"))
        p.addView(body("Version ${BuildConfig.VERSION_NAME}"))
        p.addView(button("Check for updates") { checkForUpdate(silent = false) })
        return p
    }

    private fun buildMeasureTab(maxVol: Int): View {
        val p = page()
        p.addView(heading("Live reading"))

        freqInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("10000")
            addTextChangedListener(simpleWatcher { s ->
                s.toDoubleOrNull()?.let { if (it in 20.0..23000.0) curFreq = it }
            })
        }
        p.addView(row(label("Tone frequency (Hz)"), freqInput))

        toneLabel = body("")
        p.addView(toneLabel)
        p.addView(seek(100, (curTone * 100).roundToInt()) { v ->
            curTone = v / 100.0
            engine.tone.level = curTone
            toneLabel.text = "Tone level: $v%"
        })
        toneLabel.text = "Tone level: ${(curTone * 100).roundToInt()}%"

        volLabel = body("")
        p.addView(volLabel)
        p.addView(seek(maxVol, curVol) { v ->
            curVol = v
            volLabel.text = "Phone volume: $v / $maxVol (held fixed during readings)"
        })
        volLabel.text = "Phone volume: $curVol / $maxVol (held fixed during readings)"

        liveBig = TextView(this).apply {
            textSize = 44f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            text = "— dB"
        }
        p.addView(liveBig)
        liveDetail = body("Press Start live to see the signal level coming back from the cell.")
        p.addView(liveDetail)

        liveBtn = button("Start live") { toggleLive() }
        p.addView(liveBtn)
        p.addView(button("Record a reading") { recordSingle() })
        p.addView(
            hint(
                "Wetter paddy reads higher. Set the level with your WETTEST sample in the cell: raise the " +
                    "tone level or phone volume until it reads between about −20 and −6 dB with no clipping, then " +
                    "leave both fixed for all samples. Dry paddy will read lower, which is expected. Higher " +
                    "frequencies (10–18 kHz) usually give a stronger signal."
            )
        )
        return p
    }

    private fun buildSweepTab(): View {
        val p = page()
        p.addView(heading("Frequency sweep"))
        p.addView(
            body(
                "Fill the cell through the hopper and strike it off. Run the sweep. Then empty, refill and run " +
                    "again. The refill number goes up by itself. Three refills per sample is a good start."
            )
        )
        sweepFreqs = EditText(this).apply {
            setText(DEFAULT_SWEEP)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        p.addView(label("Frequencies (Hz, comma separated)"))
        p.addView(sweepFreqs)
        p.addView(hint("Uses the tone level and phone volume set on the Measure tab."))
        sweepBtn = button("Run sweep and save") { runSweep() }
        p.addView(sweepBtn)
        sweepOut = mono("")
        p.addView(sweepOut)
        return p
    }

    private fun buildCalibrateTab(): View {
        val p = page()
        p.addView(heading("Reference moisture"))
        p.addView(body("Enter the true moisture for each sample, from the oven test or a commercial meter."))
        calSampleSpinner = spinner(listOf("(no readings yet)"))
        p.addView(label("Sample"))
        p.addView(calSampleSpinner)
        calMoisture = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "e.g. 14.2"
        }
        p.addView(row(label("Moisture (%)"), calMoisture))
        calMethod = spinner(METHODS)
        p.addView(row(label("Method"), calMethod))
        p.addView(button("Save reference value") { saveCalibration() })
        calList = body("")
        p.addView(calList)

        p.addView(heading("Readings vs moisture"))
        plotFreqSpinner = spinner(listOf("(none)"))
        plotFreqSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updatePlot()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        p.addView(row(label("Frequency"), plotFreqSpinner))
        plot = PlotView(this)
        p.addView(plot)
        plotInfo = body("")
        p.addView(plotInfo)
        return p
    }

    private fun buildDataTab(): View {
        val p = page()
        p.addView(heading("Data"))
        dataSummary = body("")
        p.addView(dataSummary)
        p.addView(button("Save CSV to Downloads") { saveToDownloads(share = false) })
        p.addView(button("Share CSV (email, WhatsApp, Drive…)") { saveToDownloads(share = true) })
        p.addView(button("Delete last reading") {
            if (store.removeLastReading()) toast("Last reading deleted") else toast("Nothing to delete")
            refreshData()
        })
        p.addView(button("Clear all data") { confirmClear() })
        p.addView(heading("Recent readings"))
        dataRecent = mono("")
        p.addView(dataRecent)
        return p
    }

    private fun showTab(i: Int) {
        currentTab = i
        tabViews.forEachIndexed { j, v -> v.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { j, b -> b.alpha = if (j == i) 1f else 0.55f }
        when (i) {
            0 -> refreshSetup()
            3 -> refreshCalibrate()
            4 -> refreshData()
        }
    }

    // ---------------------------------------------------------------- setup

    private fun refreshSetup() {
        if (!::setupStatus.isInitialized) return
        val inDev = engine.headsetInput()
        val outDev = engine.headsetOutput()
        val sb = StringBuilder()
        sb.append(if (hasMic()) "✓ Microphone permission granted\n" else "✗ Microphone permission needed (tap Test signal)\n")
        sb.append(
            if (inDev != null) "✓ Headset mic found: ${AudioEngine.typeName(inDev.type)} (${inDev.productName})\n"
            else "✗ No headset mic found. Plug in the USB-C adapter with the cell cable.\n"
        )
        sb.append(
            if (outDev != null) "✓ Headset output found: ${AudioEngine.typeName(outDev.type)}\n"
            else "✗ No headset output found\n"
        )
        sb.append(
            if (engine.unprocessedSupported()) "✓ Unprocessed mic mode supported (no auto gain)\n"
            else "! Unprocessed mic mode not supported; using voice-recognition mode (minimal processing)\n"
        )
        setupStatus.text = sb.toString()
    }

    private fun runSignalTest() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (busy) { toast("Busy — stop the current reading first"); return }
        busy = true
        setupResult.text = "Testing…"
        worker.execute {
            try {
                if (!ensureEngine()) return@execute
                val routed = engine.routedInput()
                engine.tone.level = curTone
                val m1 = engine.measure(1000.0, 500, 600)
                val m10 = engine.measure(10000.0, 250, 600)
                engine.tone.level = 0.0
                val n10 = engine.measure(10000.0, 300, 600)
                engine.tone.level = curTone
                val snr = m10.levelDb - n10.levelDb
                val sb = StringBuilder()
                sb.append("Mic in use: ${engine.routedInputName()}\n")
                sb.append("Mic mode:   ${engine.sourceName}\n")
                sb.append("Volume:     $lastVolActual (asked for $curVol)\n\n")
                sb.append(fmt("Tone  1 kHz: %7.1f dB\n", m1.levelDb))
                sb.append(fmt("Tone 10 kHz: %7.1f dB\n", m10.levelDb))
                sb.append(fmt("Tone off:    %7.1f dB (noise)\n", n10.levelDb))
                sb.append(fmt("Signal/noise: %6.1f dB\n", snr))
                sb.append(fmt("Peak:        %5.0f%% of full scale\n\n", maxOf(m1.peak, m10.peak) * 100))
                if (routed == null || routed.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    sb.append("⚠ The phone is using its own mic, not the cell. Check the adapter supports a headset mic and the white (mic) wire.\n")
                }
                if (maxOf(m1.peak, m10.peak) > 0.95) sb.append("⚠ Clipping. Lower the tone level or phone volume.\n")
                if (snr < 20) {
                    sb.append("Weak signal. That's expected with an empty or dry cell. To check the wiring, fill the cell " +
                        "with damp grain (or briefly touch the bolt to the can): the 10 kHz level should jump.\n")
                }
                if (snr >= 20 && maxOf(m1.peak, m10.peak) <= 0.95 && routed != null && routed.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    sb.append("✓ Signal is getting through. Fill the cell and try the Measure tab.\n")
                }
                ui.post { setupResult.text = sb.toString() }
            } catch (e: Exception) {
                ui.post { setupResult.text = "Test failed: ${e.message}" }
            } finally {
                stopEngine()
                busy = false
            }
        }
    }

    // ---------------------------------------------------------------- measuring

    private fun toggleLive() {
        if (liveRunning) {
            liveRunning = false
            return
        }
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (busy) { toast("Busy — wait for the current task"); return }
        busy = true
        liveRunning = true
        liveBtn.text = "Stop live"
        worker.execute {
            try {
                if (!ensureEngine()) return@execute
                var first = true
                while (liveRunning) {
                    engine.tone.level = curTone
                    if (lastVolActual != curVol) applyVolume()
                    val save = pendingSave
                    val m = engine.measure(curFreq, if (first) 400 else 40, if (save != null) 1000 else 400)
                    first = false
                    if (save != null) {
                        pendingSave = null
                        store.addReading(makeReading(save, "single", m))
                        ui.post { toast("Reading saved (${fmt("%.1f", m.levelDb)} dB)") }
                    }
                    ui.post { showLive(m) }
                }
            } catch (e: Exception) {
                ui.post { toast("Live reading stopped: ${e.message}") }
            } finally {
                stopEngine()
                liveRunning = false
                busy = false
                ui.post { liveBtn.text = "Start live" }
            }
        }
    }

    private fun recordSingle() {
        val meta = meta()
        if (liveRunning) {
            pendingSave = meta
            return
        }
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (busy) { toast("Busy — wait for the current task"); return }
        busy = true
        liveDetail.text = "Measuring…"
        worker.execute {
            try {
                if (!ensureEngine()) return@execute
                engine.tone.level = curTone
                val m = engine.measure(curFreq, 500, 1000)
                store.addReading(makeReading(meta, "single", m))
                ui.post {
                    showLive(m)
                    toast("Reading saved (${fmt("%.1f", m.levelDb)} dB)")
                }
            } catch (e: Exception) {
                ui.post { toast("Reading failed: ${e.message}") }
            } finally {
                stopEngine()
                busy = false
            }
        }
    }

    private fun showLive(m: Measurement) {
        liveBig.text = fmt("%.1f dB", m.levelDb)
        val sb = StringBuilder()
        sb.append(fmt("at %.0f Hz · steadiness ±%.2f dB · peak %.0f%%", m.freqHz, m.stdDb, m.peak * 100))
        if (m.peak > 0.95) sb.append("\n⚠ Clipping: lower the tone level or volume")
        if (m.levelDb < -70) sb.append("\nVery weak: normal for an empty or very dry cell. If wet grain also reads this low, check the green → can and white → bolt connections")
        if (lastVolActual < curVol) sb.append("\n! Phone limited the volume to $lastVolActual")
        liveDetail.text = sb.toString()
    }

    private fun runSweep() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (busy) { toast("Busy — stop live reading first"); return }
        val freqs = sweepFreqs.text.toString().split(',', ' ', ';')
            .mapNotNull { it.trim().toDoubleOrNull() }
            .filter { it in 20.0..23000.0 }
        if (freqs.isEmpty()) { toast("Enter at least one frequency"); return }
        val meta = meta()
        busy = true
        sweepBtn.isEnabled = false
        val header = "${meta.grain} · ${meta.sample} · refill ${meta.refill}\n"
        sweepOut.text = header + "Running…"
        worker.execute {
            val sb = StringBuilder()
            var ok = false
            try {
                if (!ensureEngine()) return@execute
                engine.tone.level = curTone
                var first = true
                for (f in freqs) {
                    if (lastVolActual != curVol) applyVolume()
                    val m = engine.measure(f, if (first) 500 else 250, 600)
                    first = false
                    store.addReading(makeReading(meta, "sweep", m))
                    sb.append(fmt("%6.0f Hz  %7.1f dB  ±%.2f", f, m.levelDb, m.stdDb))
                    if (m.peak > 0.95) sb.append("  CLIP")
                    sb.append('\n')
                    val text = header + sb.toString()
                    ui.post { sweepOut.text = text }
                }
                ok = true
            } catch (e: Exception) {
                val text = header + sb.toString() + "\nSweep failed: ${e.message}"
                ui.post { sweepOut.text = text }
            } finally {
                stopEngine()
                busy = false
                ui.post {
                    sweepBtn.isEnabled = true
                    if (ok) {
                        refill = meta.refill + 1
                        updateRefill()
                        sweepOut.append("\nSaved. Empty and refill the cell, then run again (now refill $refill).")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- wire finder

    /**
     * Plays a low tone on the left wire (tip) and a high tone on the right wire (ring 1),
     * and listens on the mic wire. Touching a bare wire to the mic wire lets that wire's
     * tone straight into the mic. Touching the mic wire to ground shorts it, which the
     * phone reports as a headset button press.
     */
    private fun toggleWireFinder() {
        if (wireRunning) {
            wireRunning = false
            return
        }
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (busy) { toast("Busy — stop the current reading first"); return }
        busy = true
        wireRunning = true
        wireBtn.text = "Stop wire finder"
        wireStatus.text = "Listening… keep the wires apart"
        wireButtonLog.text = ""
        wireLevels.text = ""
        val freqs = listOf(WIRE_LEFT_HZ, WIRE_RIGHT_HZ)
        worker.execute {
            try {
                if (!ensureEngine()) return@execute
                engine.tone.frequency = WIRE_LEFT_HZ
                engine.tone.level = WIRE_LEVEL
                engine.tone.rightFrequency = WIRE_RIGHT_HZ
                engine.tone.rightLevel = WIRE_LEVEL
                val routed = engine.routedInput()
                val builtIn = routed == null || routed.type == AudioDeviceInfo.TYPE_BUILTIN_MIC

                // Learn the background with the wires apart.
                engine.listen(freqs, 300)
                var baseL = -200.0
                var baseR = -200.0
                repeat(10) {
                    val b = engine.listen(freqs, 150)
                    baseL = maxOf(baseL, b.levelsDb[0])
                    baseR = maxOf(baseR, b.levelsDb[1])
                }

                var heldRank = 0
                var heldText = WIRE_NONE
                var heldUntil = 0L
                var lastDirectAt = 0L
                var maxL = -200.0
                var maxR = -200.0

                while (wireRunning) {
                    val snap = engine.listen(freqs, 150)
                    val now = System.currentTimeMillis()
                    val l = snap.levelsDb[0]
                    val r = snap.levelsDb[1]
                    maxL = maxOf(maxL, l)
                    maxR = maxOf(maxR, r)
                    val dl = l - baseL
                    val dr = r - baseR
                    val clipped = snap.peak > 0.95

                    // A wire touching the mic directly gives a strong tone; a tone that only
                    // leaks through the shared ground is much weaker.
                    val strongL = dl >= WIRE_DIRECT_DB || (clipped && dl > dr && dl >= WIRE_FAINT_DB)
                    val strongR = dr >= WIRE_DIRECT_DB || (clipped && dr > dl && dr >= WIRE_FAINT_DB)
                    val rank: Int
                    val text: String
                    when {
                        strongL && strongR && kotlin.math.abs(dl - dr) < 10 -> { rank = 2; text = WIRE_BOTH }
                        strongL && dl > dr -> { rank = 3; text = WIRE_LEFT }
                        strongR && dr > dl -> { rank = 3; text = WIRE_RIGHT }
                        dl >= WIRE_FAINT_DB && l > -80 && dl >= dr -> { rank = 1; text = WIRE_LEFT_FAINT }
                        dr >= WIRE_FAINT_DB && r > -80 -> { rank = 1; text = WIRE_RIGHT_FAINT }
                        else -> { rank = 0; text = WIRE_NONE }
                    }
                    if (rank == 3) lastDirectAt = now
                    // Hold a result for a moment so a flickery contact still reads clearly.
                    if (rank >= heldRank || now > heldUntil) {
                        heldRank = rank
                        heldText = text
                        heldUntil = now + when (rank) { 3, 2 -> 2000L; 1 -> 700L; else -> 0L }
                    }

                    // Shorting the mic to ground shows up as a headset button press. A speaker
                    // wire touching the mic directly can also trip it, so a strong tone wins.
                    val sinceButton = now - lastButtonAt
                    val buttonVerdict = sinceButton in 400L..4000L && lastDirectAt < lastButtonAt - 1500
                    val status: String
                    val note: String
                    if (heldRank == 3) {
                        status = heldText
                        note = if (sinceButton < 4000) {
                            "The phone also saw a button signal. That's normal when a speaker wire touches the mic directly. Trust the tone."
                        } else {
                            ""
                        }
                    } else if (buttonVerdict) {
                        status = WIRE_GROUND
                        note = ""
                    } else {
                        status = heldText
                        note = ""
                    }

                    val levels = StringBuilder()
                    if (builtIn) {
                        levels.append("⚠ The phone is listening on its own mic, not the cable.\n")
                        levels.append("Unplug the adapter, plug it back in and start again.\n\n")
                    }
                    levels.append(fmt("Left  %4.0f Hz: %6.1f dB  (+%4.1f over background)\n", WIRE_LEFT_HZ, l, dl))
                    levels.append(fmt("Right %4.0f Hz: %6.1f dB  (+%4.1f over background)\n", WIRE_RIGHT_HZ, r, dr))
                    levels.append(fmt("Strongest so far: left %.1f, right %.1f dB\n", maxL, maxR))
                    levels.append(fmt("Strong = +%.0f dB or more; faint = +%.0f dB\n", WIRE_DIRECT_DB, WIRE_FAINT_DB))
                    levels.append("Mic in use: ${engine.routedInputName()}")
                    val levelText = levels.toString()
                    ui.post {
                        wireStatus.text = status
                        wireButtonLog.text = note
                        wireLevels.text = levelText
                    }
                }
            } catch (e: Exception) {
                // Shorting wires can make the phone briefly drop the adapter; say so plainly.
                ui.post { wireStatus.text = "Stopped: ${e.message}. If you were touching wires, unplug and replug the adapter, then start again." }
            } finally {
                engine.tone.rightLevel = 0.0
                engine.tone.frequency = curFreq
                engine.tone.level = curTone
                stopEngine()
                wireRunning = false
                busy = false
                ui.post { wireBtn.text = "Start wire finder" }
            }
        }
    }

    /** Called on the main thread when the phone reports a headset button press. */
    private fun onHeadsetButton() {
        val now = System.currentTimeMillis()
        if (now - lastButtonAt < 400) return
        lastButtonAt = now
    }

    /** Opens audio if needed. Runs on the worker thread. */
    private fun ensureEngine(): Boolean {
        if (!engine.isOpen) {
            try {
                engine.tone.level = curTone
                engine.open()
            } catch (e: Exception) {
                ui.post { toast("Could not start audio: ${e.message}") }
                return false
            }
            ui.post { onAudioStarted() }
        }
        applyVolume()
        return true
    }

    private fun stopEngine() {
        if (engine.isOpen) {
            engine.close()
            ui.post { onAudioStopped() }
        }
    }

    private fun applyVolume() {
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, curVol, 0)
        } catch (_: SecurityException) {
        }
        lastVolActual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (lastVolActual < curVol && !volWarned) {
            volWarned = true
            ui.post { toast("The phone capped the volume at $lastVolActual (safe-volume limit)") }
        }
    }

    private fun onAudioStarted() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build()
        am.requestAudioFocus(fr)
        focusRequest = fr
        // An active media session receives the headset button presses, so they don't
        // pause anything or reach other apps. The callback simply ignores them.
        val ms = MediaSession(this, "GrainMoisture")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                onHeadsetButton()
                return true
            }
        })
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .build()
        )
        ms.isActive = true
        mediaSession = ms
    }

    private fun onAudioStopped() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
    }

    private fun makeReading(meta: Meta, mode: String, m: Measurement) = Reading(
        time = System.currentTimeMillis(),
        grain = meta.grain,
        sample = meta.sample,
        refill = meta.refill,
        mode = mode,
        m = m,
        volumeIndex = lastVolActual,
        toneLevel = curTone,
        input = engine.routedInputName(),
        source = engine.sourceName,
        appVersion = BuildConfig.VERSION_NAME,
    )

    private fun meta() = Meta(
        grainSpinner.selectedItem?.toString() ?: GRAINS[0],
        sampleInput.text.toString().trim().ifEmpty { "unlabelled" },
        refill,
    )

    private fun updateRefill() {
        refillText.text = "Refill $refill"
    }

    // ---------------------------------------------------------------- calibration

    private fun refreshCalibrate() {
        val rows = store.readings()
        val samples = rows.map { it["grain"].orEmpty() + " | " + it["sample"].orEmpty() }.distinct()
        calSampleSpinner.adapter = listAdapter(samples.ifEmpty { listOf("(no readings yet)") })
        val current = meta().grain + " | " + meta().sample
        val idx = samples.indexOf(current)
        if (idx >= 0) calSampleSpinner.setSelection(idx)

        val cals = store.calibrations()
        calList.text = if (cals.isEmpty()) {
            "No reference values yet."
        } else {
            cals.sortedWith(compareBy<Calibration>({ it.grain }, { it.moisturePct }))
                .joinToString("\n") { "${it.grain} | ${it.sample}: ${fmt("%.1f", it.moisturePct)}% (${it.method})" }
        }

        val previous = plotFreqs.getOrNull(plotFreqSpinner.selectedItemPosition)
        plotFreqs = rows.mapNotNull { it["freq_hz"]?.toDoubleOrNull() }.distinct().sorted()
        plotFreqSpinner.adapter = listAdapter(plotFreqs.map { "${it.roundToInt()} Hz" }.ifEmpty { listOf("(none)") })
        val pick = when {
            previous != null && previous in plotFreqs -> plotFreqs.indexOf(previous)
            10000.0 in plotFreqs -> plotFreqs.indexOf(10000.0)
            else -> 0
        }
        if (plotFreqs.isNotEmpty()) plotFreqSpinner.setSelection(pick)
        updatePlot()
    }

    private fun saveCalibration() {
        val item = calSampleSpinner.selectedItem?.toString() ?: return
        if (!item.contains(" | ")) { toast("Record some readings first"); return }
        val pct = calMoisture.text.toString().trim().toDoubleOrNull()
        if (pct == null || pct < 0 || pct > 60) { toast("Enter moisture as a number, e.g. 14.2"); return }
        val grain = item.substringBefore(" | ")
        val sample = item.substringAfter(" | ")
        store.saveCalibration(Calibration(grain, sample, pct, calMethod.selectedItem.toString(), System.currentTimeMillis()))
        calMoisture.setText("")
        toast("Saved $sample = ${fmt("%.1f", pct)}%")
        refreshCalibrate()
    }

    private fun updatePlot() {
        if (!::plot.isInitialized) return
        val f = plotFreqs.getOrNull(plotFreqSpinner.selectedItemPosition)
        if (f == null) {
            plot.setData(emptyList(), null, "Reference moisture (%)", "Level (dB)")
            plotInfo.text = ""
            return
        }
        val cals = store.calibrations().associateBy { it.grain + "\u0000" + it.sample }
        val pts = store.readings().mapNotNull { row ->
            if (row["freq_hz"]?.toDoubleOrNull() != f) return@mapNotNull null
            val cal = cals[row["grain"].orEmpty() + "\u0000" + row["sample"].orEmpty()] ?: return@mapNotNull null
            val db = row["level_dbfs"]?.toDoubleOrNull() ?: return@mapNotNull null
            cal.moisturePct to db
        }
        val fit = linearFit(pts)
        plot.setData(pts, fit?.let { it.first to it.second }, "Reference moisture (%)", "Level at ${f.roundToInt()} Hz (dB)")
        plotInfo.text = if (pts.isEmpty()) {
            "Enter reference moisture values above to see the curve."
        } else {
            fit?.let { fmt("%d points · %.2f dB per 1%% moisture · R² = %.3f", pts.size, it.first, it.third) }
                ?: "${pts.size} points. Need at least two different moisture levels for a fit."
        }
    }

    /** Least-squares line through the points: slope, intercept, R². */
    private fun linearFit(pts: List<Pair<Double, Double>>): Triple<Double, Double, Double>? {
        if (pts.size < 2) return null
        val n = pts.size.toDouble()
        val mx = pts.sumOf { it.first } / n
        val my = pts.sumOf { it.second } / n
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        val syy = pts.sumOf { (it.second - my) * (it.second - my) }
        val sxy = pts.sumOf { (it.first - mx) * (it.second - my) }
        if (sxx < 1e-9) return null
        val slope = sxy / sxx
        val intercept = my - slope * mx
        val r = if (syy < 1e-12) 0.0 else sxy / sqrt(sxx * syy)
        return Triple(slope, intercept, r * r)
    }

    // ---------------------------------------------------------------- data

    private fun refreshData() {
        if (!::dataSummary.isInitialized) return
        val rows = store.readings()
        val samples = rows.map { it["grain"] + "|" + it["sample"] }.distinct().size
        val cals = store.calibrations().size
        dataSummary.text = "${rows.size} readings · $samples samples · $cals reference values"
        val timeFmt = SimpleDateFormat("MMM d HH:mm", Locale.US)
        dataRecent.text = if (rows.isEmpty()) {
            "No readings yet."
        } else {
            rows.takeLast(20).reversed().joinToString("\n") { r ->
                val t = r["epoch_ms"]?.toLongOrNull()?.let { timeFmt.format(Date(it)) } ?: ""
                val f = r["freq_hz"]?.toDoubleOrNull()?.roundToInt() ?: 0
                val db = r["level_dbfs"]?.toDoubleOrNull() ?: 0.0
                fmt("%s  %s r%s  %5d Hz %7.1f dB", t, r["sample"].orEmpty().take(10), r["refill"].orEmpty(), f, db)
            }
        }
    }

    private fun saveToDownloads(share: Boolean) {
        val csv = store.exportCsv()
        if (store.readings().isEmpty()) { toast("No readings yet"); return }
        val name = "grain-moisture-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
        var uri: Uri? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { u -> contentResolver.openOutputStream(u)?.use { it.write(csv.toByteArray()) } }
            } catch (e: Exception) {
                uri = null
                toast("Could not save to Downloads: ${e.message}")
            }
        }
        if (!share) {
            if (uri != null) toast("Saved to Downloads/$name") else if (Build.VERSION.SDK_INT < 29) toast("Use Share on this Android version")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, name)
            if (uri != null) {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, csv)
            }
        }
        startActivity(Intent.createChooser(send, "Share grain moisture data"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear all data?")
            .setMessage("This deletes every reading and reference value on this phone. Export first if you need them.")
            .setPositiveButton("Delete everything") { _, _ ->
                store.clearAll()
                refreshData()
                toast("All data cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- updates

    private fun checkForUpdate(silent: Boolean) {
        Thread {
            val rel = try {
                Updater.fetchLatest()
            } catch (_: Exception) {
                null
            }
            ui.post {
                if (rel == null) {
                    if (!silent) toast("Couldn't check for updates (offline, or no release yet)")
                } else if (rel.versionCode > BuildConfig.VERSION_CODE) {
                    promptUpdate(rel)
                } else if (!silent) {
                    toast("You have the latest version (${BuildConfig.VERSION_NAME})")
                }
            }
        }.start()
    }

    private fun promptUpdate(rel: Updater.Release) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("Version ${rel.tag.removePrefix("v")} is ready (you have ${BuildConfig.VERSION_NAME}).\n\n${rel.notes.take(400)}")
            .setPositiveButton("Install") { _, _ -> startUpdate(rel) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate(rel: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Allow this app to install updates, then come back and tap Check for updates")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        toast("Downloading update…")
        Thread {
            try {
                Updater.downloadAndInstall(applicationContext, rel.apkUrl)
            } catch (e: Exception) {
                ui.post { toast("Update failed: ${e.message}") }
            }
        }.start()
    }

    // ---------------------------------------------------------------- helpers

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.US, pattern, *args)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(32))
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.GRAY)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun mono(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 0, dp(10), 0)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        minWidth = 0
        minimumWidth = dp(44)
        setOnClickListener { onClick() }
    }

    private fun row(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(left)
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun listAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun spinner(items: List<String>) = Spinner(this).apply { adapter = listAdapter(items) }

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun simpleWatcher(onText: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onText(s?.toString().orEmpty())
    }

    companion object {
        const val REQ_MIC = 1
        const val DEFAULT_SWEEP = "500, 1000, 2000, 5000, 10000, 15000, 18000"
        val TAB_NAMES = listOf("Setup", "Measure", "Sweep", "Calibrate", "Data")
        val GRAINS = listOf("Rice paddy", "Milled rice", "Maize", "Sorghum", "Millet", "Soybean", "Groundnut", "Other")
        val METHODS = listOf("Oven dry", "Commercial meter", "Salt test", "Other")
        const val WIRE_LEFT_HZ = 700.0
        const val WIRE_RIGHT_HZ = 2300.0
        const val WIRE_LEVEL = 0.1
        const val WIRE_FAINT_DB = 15.0
        const val WIRE_DIRECT_DB = 35.0
        const val WIRE_LEFT = "LEFT tone (strong): the wire touching the mic is TIP, the tone wire"
        const val WIRE_RIGHT = "RIGHT tone (strong): the wire touching the mic is RING 1, not used"
        const val WIRE_LEFT_FAINT = "Faint left tone only: probably leaking through the shared ground. Make one wire the MIC wire."
        const val WIRE_RIGHT_FAINT = "Faint right tone only: probably leaking through the shared ground. Make one wire the MIC wire."
        const val WIRE_BOTH = "Both tones strong: more than two wires are touching. Separate them."
        const val WIRE_GROUND = "BUTTON PRESS: the wire touching the mic is GROUND"
        const val WIRE_NONE = "Nothing heard: touch a wire to the MIC wire"
        val HEADSET_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST,
        )
        val BLOCKED_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST,
        )
    }
}
