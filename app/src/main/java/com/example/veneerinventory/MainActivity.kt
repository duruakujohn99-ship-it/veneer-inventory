package com.example.veneerinventory

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.round

data class Row(
    val id: Long,
    val pallet: Int,
    val date: String,
    val supplier: String,
    val code: String,
    val barcode: String,
    val len: Double,
    val wide: Double,
    val sheets: Int,
    val area: Double
)

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var supplier: EditText
    private lateinit var code: EditText
    private lateinit var barcode: EditText
    private lateinit var len: EditText
    private lateinit var wide: EditText
    private lateinit var sheets: EditText
    private lateinit var areaText: TextView
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var palletTabs: LinearLayout
    private lateinit var recordsView: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var scanButton: Button
   @Volatile private var scanArmed = false
  private var toneGenerator: ToneGenerator? = null


   private val textRecognizer by lazy {
       TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
}
    
    private val rows = mutableListOf<Row>()
    private val pallets = sortedSetOf(1)
    private var currentPallet = 1

    private val executor = Executors.newSingleThreadExecutor()

    private val prefs by lazy {
        getSharedPreferences("inventory", MODE_PRIVATE)
    }

    private var last = ""
    private var lastAt = 0L
    private var cameraStarted = false

    private val permission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) camera()
            else status.text = "Camera permission required."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
ui()
refresh()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            camera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun ui() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "VENEER INVENTORY"
            textSize = 24f
        })

        preview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(180))
        }
        root.addView(preview)

        status = TextView(this).apply {
            text = "Point camera at barcode."
            textSize = 15f
        }
        root.addView(status)

scanButton = Button(this).apply {
text = "SCAN BARCODE"
textSize = 18f
setOnClickListener {
scanArmed = true
last = ""
lastAt = 0L
barcode.setText("")
status.text = "SCANNING... POINT CAMERA AT BARCODE."
scanButton.text = "SCANNING... TAP TO TRY AGAIN"
isEnabled = true
}
}
root.addView(scanButton)

supplier = field("Supplier (optional)")
code = field("Stock / product code (optional)")
barcode = field("Scanned barcode")
len = field("Length in cm")
wide = field("Width in cm")
sheets = field("Number of sheets")

listOf(supplier, code, barcode, len, wide, sheets)
.forEach { root.addView(it) }

areaText = TextView(this).apply {
textSize = 18f
}

        root.addView(areaText)

        root.addView(Button(this).apply {
            text = "CALCULATE AREA"
            setOnClickListener { calculate() }
        })

        palletTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        root.addView(TextView(this).apply {
            text = "PALLETS"
            textSize = 18f
        })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(palletTabs)
        })

        root.addView(Button(this).apply {
            text = "＋ ADD NEW PALLET"
            setOnClickListener { addPallet() }
        })

        saveButton = Button(this).apply {
            setOnClickListener { save() }
        }
        root.addView(saveButton)

        root.addView(Button(this).apply {
            text = "DELETE CURRENT PALLET"
            setOnClickListener { deletePallet() }
        })

        root.addView(Button(this).apply {
            text = "EXPORT ALL PALLETS TO CSV"
            setOnClickListener { export() }
        })

        summary = TextView(this).apply {
            textSize = 16f
        }
        root.addView(summary)

        root.addView(TextView(this).apply {
            text = "TALLY SHEET — TAP A RECORD TO VIEW OR EDIT"
            textSize = 18f
        })

        recordsView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(recordsView)

        setContentView(ScrollView(this).apply {
            addView(root)
        })
    }

    private fun dp(n: Int): Int =
        (n * resources.displayMetrics.density).toInt()

    private fun field(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            setSingleLine(true)
        }

    private fun rounded(value: Double): Double =
        round(value * 100.0) / 100.0

    private fun areaValue(
        length: Double,
        width: Double,
        count: Int
    ): Double = rounded(length * width * count / 10000.0)

    private fun camera() {
        if (cameraStarted) return
        cameraStarted = true

        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            try {
                val provider = future.get()

                val cameraPreview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(preview.surfaceProvider)
                    }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                val scanner = BarcodeScanning.getClient()
                


                analysis.setAnalyzer(executor) { proxy ->
    val mediaImage = proxy.image

    if (mediaImage == null) {
        proxy.close()
    } else {
        val input = InputImage.fromMediaImage(
            mediaImage,
            proxy.imageInfo.rotationDegrees
        )

        val barcodeTask = scanner.process(input)
        val textTask = textRecognizer.process(input)

        com.google.android.gms.tasks.Tasks
            .whenAllComplete(barcodeTask, textTask)
            .addOnCompleteListener {
                val raw = if (barcodeTask.isSuccessful) {
                    barcodeTask.result.firstOrNull {
                        !it.rawValue.isNullOrBlank()
                    }?.rawValue
                } else {
                    null
                }

                val recognizedText = if (textTask.isSuccessful) {
                    textTask.result.text
                } else {
                    ""
                }

                runOnUiThread {
                    try {
                        if (scanArmed) {
                            var captured = false

                            if (raw != null) {
                                scanArmed = false
                                last = raw
                                lastAt = System.currentTimeMillis()

                                scanButton.text = "SCAN BARCODE"
                                handleScannedBarcode(raw)

                                captured = true
                            }

                            if (parseLabelText(recognizedText)) {
                                scanArmed = false
                                scanButton.text = "SCAN BARCODE"
                                captured = true
                            }

                            if (!captured) {
                                status.text =
                                    "Scanning barcode and veneer label..."
                            }
                        }
                    } finally {
                        proxy.close()
                    }
                }
            }
    }
}

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    cameraPreview,
                    analysis
                )

                status.text = "Camera ready. Aim at a barcode."

            } catch (e: Exception) {
                cameraStarted = false
                status.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScannedBarcode(raw: String) {
    val normalized = normalizeBarcode(raw)

    val existing = rows.firstOrNull {
        normalizeBarcode(it.barcode) == normalized
    }

    if (normalized.isNotEmpty() && existing != null) {
        barcode.setText("")
        beep()

        status.text = "DUPLICATE BARCODE — ALREADY SCANNED ON PALLET " +
            existing.pallet.toString().padStart(2, '0') + "!"

        Toast.makeText(
            this,
            "DUPLICATE BARCODE — NOT SAVED!",
            Toast.LENGTH_LONG
        ).show()

        return
    }

    barcode.setText(raw)
    parseSample(raw)

    status.text = "Barcode captured. Check dimensions, then save the bundle."
}

private fun normalizeBarcode(value: String): String {
    return value.filter { !it.isWhitespace() }
}

private fun beep() {
    try {
        if (toneGenerator == null) {
            toneGenerator = ToneGenerator(
                AudioManager.STREAM_MUSIC,
                100
            )
        }

        toneGenerator?.startTone(
            ToneGenerator.TONE_PROP_NACK,
            700
        )
    } catch (_: Exception) {
        Toast.makeText(
            this,
            "DUPLICATE BARCODE — NOT SAVED!",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun parseSample(raw: String) {
    when (normalizeBarcode(raw)) {
        "00000430900460240055" -> {
            len.setText("90")
            wide.setText("46")
            sheets.setText("24")
        }

        "4201153000424040025" -> {
            len.setText("40")
            wide.setText("25")
            sheets.setText("24")
        }
    }

    calculate()
}
    private fun parseLabelText(text: String): Boolean {
    fun findValue(labelPattern: String): String? {
        val pattern = Regex(
            """(?:\b(?:$labelPattern)\b\s*[:=]?\s*(\d+(?:[.,]\d+)?)|(\d+(?:[.,]\d+)?)\s*\b(?:$labelPattern)\b)""",
            RegexOption.IGNORE_CASE
        )

        val match = pattern.find(text) ?: return null

        return (match.groups[1]?.value ?: match.groups[2]?.value)
            ?.replace(",", ".")
    }

    val lengthValue = findValue("LEN")
    val widthValue = findValue("WIDE")
    val sheetsValue = findValue("SHT|SHEETS")

    if (
        lengthValue == null ||
        widthValue == null ||
        sheetsValue == null
    ) {
        return false
    }

    val lengthNumber = lengthValue.toDoubleOrNull()
    val widthNumber = widthValue.toDoubleOrNull()
    val sheetsNumber = sheetsValue.toDoubleOrNull()?.toInt()

    if (
        lengthNumber == null ||
        widthNumber == null ||
        sheetsNumber == null ||
        lengthNumber <= 0 ||
        widthNumber <= 0 ||
        sheetsNumber <= 0
    ) {
        return false
    }

    len.setText(lengthValue)
    wide.setText(widthValue)
    sheets.setText(sheetsNumber.toString())

    calculate()

    status.text = "Label read successfully. Check dimensions, then save."

    return true
}
   ```kotlin
private fun calculate(): Double? {
    val l = len.text.toString()
        .replace(",", ".")
        .toDoubleOrNull()

    val w = wide.text.toString()
        .replace(",", ".")
        .toDoubleOrNull()

    val s = sheets.text.toString()
        .toIntOrNull()

    if (
        l == null || w == null || s == null ||
        l <= 0 || w <= 0 || s <= 0
    ) {
        areaText.text =
            "Area: enter valid length, width and sheets."
        return null
    }

    val area = areaValue(l, w, s)

    areaText.text =
        "Total area: ${String.format(Locale.US, "%.2f", area)} m²"

    return area
}


val area = areaValue(l, w, s)

areaText.text =
    "Total area: ${String.format(Locale.US, "%.2f", area)} m²"

return area

}

    private fun save() {
        val scanned = normalizeBarcode(barcode.text.toString())

        if (scanned.isEmpty()) {
            Toast.makeText(
                this,
                "Scan or enter a barcode first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val duplicate = rows.firstOrNull {
            normalizeBarcode(it.barcode) == scanned
        }

        if (duplicate != null) {
            beep()
            status.text =
                "DUPLICATE BARCODE — ALREADY ON PALLET " +
                duplicate.pallet.toString().padStart(2, '0') + "!"

            Toast.makeText(
                this,
                "Duplicate barcode rejected.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val l = len.text.toString()
            .replace(",", ".").toDoubleOrNull()

        val w = wide.text.toString()
            .replace(",", ".").toDoubleOrNull()

        val s = sheets.text.toString().toIntOrNull()
        val area = calculate()

        if (
            l == null || w == null || s == null ||
            l <= 0 || w <= 0 || s <= 0 || area == null
        ) {
            Toast.makeText(
                this,
                "Enter valid dimensions first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        rows.add(
            Row(
                id = System.nanoTime(),
                pallet = currentPallet,
                date = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.US
                ).format(Date()),
                supplier = supplier.text.toString(),
                code = code.text.toString(),
                barcode = barcode.text.toString(),
                len = l,
                wide = w,
                sheets = s,
                area = area
            )
        )

        persist()
        refresh()

        Toast.makeText(
            this,
            "Bundle saved to Pallet " +
                currentPallet.toString().padStart(2, '0'),
            Toast.LENGTH_SHORT
        ).show()

        barcode.setText("")
        last = ""
        status.text = "Ready for the next barcode."
    }

    private fun addPallet() {
        val next = (pallets.maxOrNull() ?: 0) + 1
        pallets.add(next)
        currentPallet = next
        persist()
        refresh()

        Toast.makeText(
            this,
            "Pallet ${next.toString().padStart(2, '0')} created.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun deletePallet() {
        if (pallets.size <= 1) {
            Toast.makeText(
                this,
                "You must keep at least one pallet.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val number = currentPallet.toString().padStart(2, '0')

        AlertDialog.Builder(this)
            .setTitle("Delete Pallet $number?")
            .setMessage(
                "This permanently deletes all records on this pallet. " +
                    "Export your tally first if you need a copy."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE") { _, _ ->
                rows.removeAll { it.pallet == currentPallet }
                pallets.remove(currentPallet)
                currentPallet = pallets.first()
                persist()
                refresh()
            }
            .show()
    }

    private fun refresh() {
        if (!::palletTabs.isInitialized) return

        palletTabs.removeAllViews()

        pallets.forEach { number ->
            palletTabs.addView(Button(this).apply {
                text = number.toString().padStart(2, '0')
                isAllCaps = false
                textSize = 16f

                if (number == currentPallet) {
                    alpha = 1.0f
                    text = "[$text]"
                } else {
                    alpha = 0.65f
                }

                setOnClickListener {
                    currentPallet = number
                    persist()
                    refresh()
                }
            })
        }

        saveButton.text =
            "SAVE BUNDLE TO PALLET " +
            currentPallet.toString().padStart(2, '0')

        val currentRows = rows.filter { it.pallet == currentPallet }

        summary.text =
            "PALLET ${currentPallet.toString().padStart(2, '0')}\n" +
            "Bundles: ${currentRows.size} | " +
            "Sheets: ${currentRows.sumOf { it.sheets }} | " +
            "Total: " + String.format(
                Locale.US,
                "%.2f",
                currentRows.sumOf { it.area }
            ) + " m²\n\n" +
            "ALL PALLETS: ${rows.size} bundles | " +
            "Total: " + String.format(
                Locale.US,
                "%.2f",
                rows.sumOf { it.area }
            ) + " m²"

        recordsView.removeAllViews()

        if (currentRows.isEmpty()) {
            recordsView.addView(TextView(this).apply {
                text = "No bundles saved on this pallet yet."
                textSize = 15f
            })
        }

        currentRows.asReversed().forEach { row ->
            val recordButton = Button(this).apply {
                isAllCaps = false
                textSize = 14f
                text =
                    "${row.barcode}\n" +
                    "${row.len} × ${row.wide} cm | " +
                    "${row.sheets} sheets | " +
                    "${String.format(Locale.US, "%.2f", row.area)} m²\n" +
                    "Tap to view, edit or delete"
                setOnClickListener { showRecord(row) }
            }

            recordsView.addView(recordButton)
        }
    }

    private fun showRecord(row: Row) {
        val lengthInput = field("Length in cm").apply {
            setText(row.len.toString())
        }

        val widthInput = field("Width in cm").apply {
            setText(row.wide.toString())
        }

        val sheetsInput = field("Number of sheets").apply {
            setText(row.sheets.toString())
        }

        val supplierInput = field("Supplier").apply {
            setText(row.supplier)
        }

        val codeInput = field("Stock / product code").apply {
            setText(row.code)
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))

            addView(TextView(this@MainActivity).apply {
                text =
                    "Barcode: ${row.barcode}\n" +
                    "Pallet: ${row.pallet.toString().padStart(2, '0')}\n" +
                    "Date: ${row.date}"
                textSize = 15f
            })

            addView(supplierInput)
            addView(codeInput)
            addView(lengthInput)
            addView(widthInput)
            addView(sheetsInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Bundle Details")
            .setView(details)
            .setNeutralButton("DELETE", null)
            .setNegativeButton("CLOSE", null)
            .setPositiveButton("SAVE CHANGES", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Delete this bundle?")
                        .setMessage(
                            "This will remove the bundle and its " +
                                "m² from the tally."
                        )
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("DELETE") { _, _ ->
                            rows.removeAll { it.id == row.id }
                            persist()
                            refresh()
                            dialog.dismiss()
                        }
                        .show()
                }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val l = lengthInput.text.toString()
                        .replace(",", ".").toDoubleOrNull()

                    val w = widthInput.text.toString()
                        .replace(",", ".").toDoubleOrNull()

                    val s = sheetsInput.text.toString().toIntOrNull()

                    if (
                        l == null || w == null || s == null ||
                        l <= 0 || w <= 0 || s <= 0
                    ) {
                        Toast.makeText(
                            this,
                            "Enter valid dimensions.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }

                    val index = rows.indexOfFirst { it.id == row.id }

                    if (index >= 0) {
                        rows[index] = row.copy(
                            supplier = supplierInput.text.toString(),
                            code = codeInput.text.toString(),
                            len = l,
                            wide = w,
                            sheets = s,
                            area = areaValue(l, w, s)
                        )
                        persist()
                        refresh()
                        dialog.dismiss()
                    }
                }
        }

        dialog.show()
    }

    private fun persist() {
        val data = rows.joinToString("\n") { row ->
            listOf(
                row.id.toString(),
                row.pallet.toString(),
                row.date,
                row.supplier,
                row.code,
                row.barcode,
                row.len.toString(),
                row.wide.toString(),
                row.sheets.toString(),
                row.area.toString()
            ).joinToString("\t")
        }

        prefs.edit()
            .putString("data", data)
            .putString("pallets", pallets.joinToString(","))
            .putInt("currentPallet", currentPallet)
            .apply()
    }

    private fun load() {
        val savedPallets = prefs.getString("pallets", "") ?: ""

        savedPallets.split(",")
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
            .forEach { pallets.add(it) }

        val data = prefs.getString("data", "") ?: ""

        data.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val v = line.split("\t")

                runCatching {
                    when (v.size) {
                        // New format
                        10 -> {
                            rows.add(
                                Row(
                                    id = v[0].toLong(),
                                    pallet = v[1].toInt(),
                                    date = v[2],
                                    supplier = v[3],
                                    code = v[4],
                                    barcode = v[5],
                                    len = v[6].toDouble(),
                                    wide = v[7].toDouble(),
                                    sheets = v[8].toInt(),
                                    area = rounded(v[9].toDouble())
                                )
                            )
                        }

                        // Older app format: move existing records to Pallet 01
                        8 -> {
                            rows.add(
                                Row(
                                    id = System.nanoTime(),
                                    pallet = 1,
                                    date = v[0],
                                    supplier = v[1],
                                    code = v[2],
                                    barcode = v[3],
                                    len = v[4].toDouble(),
                                    wide = v[5].toDouble(),
                                    sheets = v[6].toInt(),
                                    area = rounded(v[7].toDouble())
                                )
                            )
                            pallets.add(1)
                        }
                    }
                }
            }

        if (pallets.isEmpty()) pallets.add(1)

        pallets.addAll(rows.map { it.pallet })

        currentPallet = prefs.getInt("currentPallet", 1)

        if (currentPallet !in pallets) {
            currentPallet = pallets.first()
        }

        // Save migrated records using the new format.
        persist()
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

    private fun export() {
        val csv = buildString {
            appendLine(
                "Pallet,Date,Supplier,StockCode,Barcode," +
                    "Length_cm,Width_cm,Sheets,Total_m2"
            )

            rows.sortedWith(
                compareBy<Row> { it.pallet }.thenBy { it.date }
            ).forEach { row ->
                val values = listOf(
                    row.pallet.toString().padStart(2, '0'),
                    row.date,
                    row.supplier,
                    row.code,
                    row.barcode,
                    row.len.toString(),
                    row.wide.toString(),
                    row.sheets.toString(),
                    String.format(Locale.US, "%.2f", row.area)
                )

                appendLine(values.joinToString(",") { quote(it) })
            }

            appendLine()
            appendLine("Pallet,BundleCount,SheetCount,Total_m2")

            pallets.forEach { palletNumber ->
                val palletRows = rows.filter {
                    it.pallet == palletNumber
                }

                appendLine(
                    listOf(
                        palletNumber.toString().padStart(2, '0'),
                        palletRows.size.toString(),
                        palletRows.sumOf { it.sheets }.toString(),
                        String.format(
                            Locale.US,
                            "%.2f",
                            palletRows.sumOf { it.area }
                        )
                    ).joinToString(",")
                )
            }

            appendLine(
                listOf(
                    "ALL PALLETS",
                    rows.size.toString(),
                    rows.sumOf { it.sheets }.toString(),
                    String.format(
                        Locale.US,
                        "%.2f",
                        rows.sumOf { it.area }
                    )
                ).joinToString(",")
            )
        }

        try {
            val values = ContentValues().apply {
                put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    "veneer_inventory_${System.currentTimeMillis()}.csv"
                )
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                )
            }

            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Could not create CSV file")

            contentResolver.openOutputStream(uri)
                ?.bufferedWriter()
                ?.use { it.write(csv) }

            Toast.makeText(
                this,
                "CSV exported to Downloads.",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Export failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

     override fun onDestroy() {
        toneGenerator?.release()
        toneGenerator = null
        textRecognizer.close()
        executor.shutdown()
        super.onDestroy()
    }
}
