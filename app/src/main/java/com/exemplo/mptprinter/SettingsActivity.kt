package com.exemplo.mptprinter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val REQ_BT_PERMISSION = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val cbDirectPrint = findViewById<CheckBox>(R.id.cbDirectPrint)
        val spFeedLines = findViewById<Spinner>(R.id.spFeedLines)
        val spCodePage = findViewById<Spinner>(R.id.spCodePage)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val btnTestPrint = findViewById<Button>(R.id.btnTestPrint)
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)

        val prefs = getSharedPreferences("air_printer_prefs", Context.MODE_PRIVATE)

        val feedOptions = arrayOf("2 linhas", "3 linhas", "4 linhas", "5 linhas", "6 linhas")
        val feedAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, feedOptions)
        spFeedLines.adapter = feedAdapter

        val cpOptions = arrayOf(
            "UTF-8 Nativo (Padrao MPT-II GZP)",
            "Sanitizado (Sem acentos - Seguro)",
            "PC860 Portugues Nativo",
            "WPC1252 / Windows-1252"
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
            checkPermissionAndRunDiagnostics()
        }

        btnCheckUpdate.setOnClickListener {
            downloadAndInstallUpdate()
        }
    }

    private fun downloadAndInstallUpdate() {
        Toast.makeText(this, "Baixando atualização do Air Printer...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                // Link direto do asset da release mais recente do GitHub
                val updateUrl = "https://github.com/Kintessence/mpt-printer/releases/latest/download/AirPrinter.apk"
                val url = URL(updateUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val apkFile = File(externalCacheDir ?: cacheDir, "AirPrinter-update.apk")
                val inStream = conn.inputStream
                val outStream = FileOutputStream(apkFile)

                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    outStream.write(buffer, 0, bytesRead)
                }
                outStream.flush()
                outStream.close()
                inStream.close()

                runOnUiThread {
                    installApk(apkFile)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Erro no download: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installApk(file: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                this,
                "com.exemplo.mptprinter.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, "Falha ao abrir instalador: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissionAndRunDiagnostics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQ_BT_PERMISSION
                )
                return
            }
        }
        runCharacterDiagnostics()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runCharacterDiagnostics()
            } else {
                Toast.makeText(this, "Permissão necessária para conexão Bluetooth.", Toast.LENGTH_LONG).show()
            }
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
            return
        }

        val printer = bluetoothAdapter.bondedDevices.firstOrNull {
            it.name != null && (it.name.contains("MPT", ignoreCase = true) || it.name.contains("POS", ignoreCase = true))
        }

        if (printer == null) {
            Toast.makeText(this, "MPT-II não encontrada nos pareados.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Imprimindo teste de diagnóstico...", Toast.LENGTH_SHORT).show()

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

                out = socket?.outputStream ?: throw IllegalStateException("Fluxo indisponível")

                out.write(byteArrayOf(0x1B, 0x40))

                // Teste UTF-8
                out.write("1. UTF-8 Nativo:\n".toByteArray(Charsets.UTF_8))
                out.write("diâmetro ação Não avô\nInformações do pedido: OK\n\n".toByteArray(Charsets.UTF_8))

                // Teste Sanitizado
                out.write("2. Sanitizado:\n".toByteArray(Charsets.US_ASCII))
                out.write("diametro acao Nao avo\n\n".toByteArray(Charsets.US_ASCII))

                out.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A))
                out.flush()
                Thread.sleep(200)

                runOnUiThread {
                    Toast.makeText(this, "Teste impresso com sucesso!", Toast.LENGTH_SHORT).show()
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