package org.amauris.grainmoisture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Reading(
    val time: Long,
    val grain: String,
    val sample: String,
    val refill: Int,
    /** "single" (Measure tab), "sweep" or "test". */
    val mode: String,
    val m: Measurement,
    val volumeIndex: Int,
    val toneLevel: Double,
    val input: String,
    val source: String,
    val appVersion: String,
)

data class Calibration(
    val grain: String,
    val sample: String,
    val moisturePct: Double,
    val method: String,
    val time: Long,
)

/** Plain CSV files in the app's private storage. No database needed for v1. */
class Store(context: Context) {
    private val readingsFile = File(context.filesDir, "readings.csv")
    private val calFile = File(context.filesDir, "calibration.csv")

    @Synchronized
    fun addReading(r: Reading) {
        if (!readingsFile.exists()) readingsFile.writeText(csvLine(READING_HEADER) + "\n")
        val iso = isoFormat().format(Date(r.time))
        val fields = listOf(
            iso, r.time.toString(), r.grain, r.sample, r.refill.toString(), r.mode,
            num(r.m.freqHz, 1), num(r.m.levelDb, 3), num(r.m.amplitude, 8), num(r.m.stdDb, 3),
            num(r.m.peak, 5), num(r.m.rmsDb, 3), r.m.blocks.toString(),
            r.volumeIndex.toString(), num(r.toneLevel, 3), r.input, r.source, r.appVersion,
        )
        readingsFile.appendText(csvLine(fields) + "\n")
    }

    @Synchronized
    fun readings(): List<Map<String, String>> = readTable(readingsFile)

    @Synchronized
    fun calibrations(): List<Calibration> = readTable(calFile).mapNotNull { row ->
        val pct = row["moisture_pct"]?.toDoubleOrNull() ?: return@mapNotNull null
        Calibration(
            row["grain"].orEmpty(), row["sample"].orEmpty(), pct,
            row["method"].orEmpty(), row["epoch_ms"]?.toLongOrNull() ?: 0L,
        )
    }

    /** Adds or replaces the reference value for one grain + sample label. */
    @Synchronized
    fun saveCalibration(c: Calibration) {
        val others = calibrations().filterNot { it.grain == c.grain && it.sample == c.sample }
        val all = others + c
        val sb = StringBuilder(csvLine(CAL_HEADER)).append('\n')
        for (x in all) {
            sb.append(
                csvLine(listOf(x.grain, x.sample, num(x.moisturePct, 2), x.method, x.time.toString()))
            ).append('\n')
        }
        calFile.writeText(sb.toString())
    }

    @Synchronized
    fun deleteCalibration(grain: String, sample: String) {
        val keep = calibrations().filterNot { it.grain == grain && it.sample == sample }
        calFile.delete()
        keep.forEach { saveCalibration(it) }
    }

    @Synchronized
    fun removeLastReading(): Boolean {
        if (!readingsFile.exists()) return false
        val lines = readingsFile.readLines().filter { it.isNotBlank() }
        if (lines.size <= 1) return false
        readingsFile.writeText(lines.dropLast(1).joinToString("\n") + "\n")
        return true
    }

    @Synchronized
    fun clearAll() {
        readingsFile.delete()
        calFile.delete()
    }

    /** All readings with the reference moisture (if entered) joined on. */
    @Synchronized
    fun exportCsv(): String {
        val cals = calibrations().associateBy { it.grain + "\u0000" + it.sample }
        val sb = StringBuilder(csvLine(READING_HEADER + listOf("ref_moisture_pct", "ref_method"))).append('\n')
        for (row in readings()) {
            val cal = cals[row["grain"].orEmpty() + "\u0000" + row["sample"].orEmpty()]
            val fields = READING_HEADER.map { row[it].orEmpty() } +
                listOf(cal?.let { num(it.moisturePct, 2) }.orEmpty(), cal?.method.orEmpty())
            sb.append(csvLine(fields)).append('\n')
        }
        return sb.toString()
    }

    private fun readTable(f: File): List<Map<String, String>> {
        if (!f.exists()) return emptyList()
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val header = parseCsvLine(lines[0])
        return lines.drop(1).map { line ->
            val cells = parseCsvLine(line)
            header.indices.associate { i -> header[i] to cells.getOrElse(i) { "" } }
        }
    }

    companion object {
        val READING_HEADER = listOf(
            "timestamp", "epoch_ms", "grain", "sample", "refill", "mode",
            "freq_hz", "level_dbfs", "amplitude", "std_db", "peak", "rms_dbfs", "blocks",
            "volume_index", "tone_level", "input_device", "audio_source", "app_version",
        )
        val CAL_HEADER = listOf("grain", "sample", "moisture_pct", "method", "epoch_ms")

        fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

        fun num(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

        fun csvLine(fields: List<String>): String = fields.joinToString(",") { field ->
            val clean = field.replace('\n', ' ').replace('\r', ' ')
            if (clean.contains(',') || clean.contains('"')) {
                "\"" + clean.replace("\"", "\"\"") + "\""
            } else {
                clean
            }
        }

        fun parseCsvLine(line: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (quoted) {
                    if (ch == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            sb.append('"')
                            i++
                        } else {
                            quoted = false
                        }
                    } else {
                        sb.append(ch)
                    }
                } else {
                    when (ch) {
                        '"' -> quoted = true
                        ',' -> {
                            out.add(sb.toString())
                            sb.setLength(0)
                        }
                        else -> sb.append(ch)
                    }
                }
                i++
            }
            out.add(sb.toString())
            return out
        }
    }
}
