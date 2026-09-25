package com.exemplo.mptprinter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val cbDirectPrint = findViewById<CheckBox>(R.id.cbDirectPrint)
        val spFeedLines = findViewById<Spinner>(R.id.spFeedLines)
        val spCodePage = findViewById<Spinner>(R.id.spCodePage)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val btnTestPrint = findViewById<Button>(R.id.btnTestPrint)

        val prefs = getSharedPreferences("air_printer_prefs", Context.MODE_PRIVATE)

        val feedOptions = arrayOf("2 linhas", "3 linhas", "4 linhas", "5 linhas", "6 linhas")
        val feedAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, feedOptions)
        spFeedLines.adapter = feedAdapter

        val cpOptions = arrayOf(
            "WPC1252 (1B 74 10)",
            "CP850 (1B 74 02)",
            "CP860 (1B 74 03)",
            "WPC1252 Alt (1B 74 20)",
            "ISO-8859-1 (1B 74 11)",
            "Sem acentos (Sanitizado)"
        )
        val cpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cpOptions)
        spCodePage.adapter = cpAdapter

        cbDirectPrint.isChecked = prefs.getBoolean("direct_print", false)
        val savedFeed = prefs.getInt("feed_lines", 4)
        val feedIndex = feedOptions.indexOfFirst { it.startsWith(savedFeed.toString()) }
        if (feedIndex >= 0) spFeedLines.setSelection(feedIndex)

        val savedCpIndex = prefs.getInt("codepage_index", 0)
        if (savedCpIndex in cpOptions.indices) {
            spCodePage.setSelection(savedCpIndex)
        }

        btnSave.setOnClickListener {
            val selectedFeedStr = spFeedLines.selectedItem.toString().substringBefore(" ")
            val feedCount = selectedFeedStr.toIntOrNull() ?: 4

            prefs.edit()
                .putBoolean("direct_print", cbDirectPrint.isChecked)
                .putInt("feed_lines", feedCount)
                .putInt("codepage_index", spCodePage.selectedItemPosition)
                .apply()

            Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnTestPrint.setOnClickListener {
            runCharacterDiagnostics()
        }
    }

    private fun runCharacterDiagnostics() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth desligado.", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permissao Bluetooth necessaria.", Toast.LENGTH_SHORT).show()
            return
        }

        val printer = bluetoothAdapter.bondedDevices.firstOrNull {
            it.name != null && (it.name.contains("MPT", ignoreCase = true) || it.name.contains("POS", ignoreCase = true))
        }

        if (printer == null) {
            Toast.makeText(this, "Impressora nao encontrada nos pareados.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Imprimindo teste de acentos...", Toast.LENGTH_SHORT).show()

        Thread {
            var socket: BluetoothSocket? = null
            var out: OutputStream? = null
            try {
                socket = try {
                    val s = printer.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                    s
                } catch (e1: Exception) {
                    val m = printer.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    val s = m.invoke(printer, 1) as BluetoothSocket
                    s.connect()
                    s
                }

                out = socket?.outputStream ?: throw IllegalStateException("Fluxo de saída indisponível")
                val realSample = "diâmetro ação Não avô\n"

                fun printSample(title: String, cmd: ByteArray, charsetName: String) {
                    out.write(byteArrayOf(0x1B, 0x40)) // ESC @ inicialização
                    out.write(cmd)                     // ESC t [n] página de código
                    out.write((title + "\n").toByteArray(Charset.forName("US-ASCII")))
                    out.write(realSample.toByteArray(Charset.forName(charsetName)))
                    out.write(byteArrayOf(0x0A))
                }

                // 1. WPC1252 (16 decimal)
                printSample("1. WPC1252 (1B 74 10):", byteArrayOf(0x1B, 0x74, 0x10), "windows-1252")

                // 2. CP850 (Multilingual Latin)
                printSample("2. CP850 (1B 74 02):", byteArrayOf(0x1B, 0x74, 0x02), "CP850")

                // 3. CP860 (Português)
                printSample("3. CP860 (1B 74 03):", byteArrayOf(0x1B, 0x74, 0x03), "CP860")

                // 4. WPC1252 Alt (32 decimal / 0x20)
                printSample("4. WPC1252 Alt (1B 74 20):", byteArrayOf(0x1B, 0x74, 0x20), "windows-1252")

                // 5. ISO-8859-1 (17 decimal)
                printSample("5. ISO-8859-1 (1B 74 11):", byteArrayOf(0x1B, 0x74, 0x11), "ISO-8859-1")

                out.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A))
                out.flush()
                Thread.sleep(200)

                runOnUiThread {
                    Toast.makeText(this, "Teste impresso!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Erro no teste: " + e.message, Toast.LENGTH_SHORT).show()
                }
            } finally {
                try { out?.close() } catch (ignored: Throwable) {}
                try { socket?.close() } catch (ignored: Throwable) {}
            }
        }.start()
    }
}