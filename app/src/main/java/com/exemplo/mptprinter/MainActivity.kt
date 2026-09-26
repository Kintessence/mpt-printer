package com.exemplo.mptprinter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var etContent: EditText
    private lateinit var btnPrint: Button
    private lateinit var btnSettings: Button
    private lateinit var tvStatus: TextView
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val REQ_BT_PRINT = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            etContent = findViewById(R.id.etContent)
            btnPrint = findViewById(R.id.btnPrint)
            btnSettings = findViewById(R.id.btnSettings)
            tvStatus = findViewById(R.id.tvStatus)

            btnSettings.setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            btnPrint.setOnClickListener {
                checkPermissionsAndPrint()
            }

            handleIncomingIntent(intent)
        } catch (e: Throwable) {
            Log.e("AirPrinter", "Erro no onCreate", e)
            Toast.makeText(this, "Erro ao iniciar: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        try {
            var incomingText: String? = null

            if (intent.action == Intent.ACTION_SEND) {
                incomingText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (incomingText.isNullOrEmpty()) {
                    incomingText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                }
            } else if (intent.action == Intent.ACTION_PROCESS_TEXT) {
                incomingText = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            }

            if (!incomingText.isNullOrEmpty()) {
                val trimmed = incomingText.trim()
                val urlRegex = Regex("https?://\\S+")
                val match = urlRegex.find(trimmed)
                if (match != null) {
                    fetchWebReceipt(match.value)
                } else {
                    updateEditor(incomingText)
                    checkAutoPrint(incomingText)
                }
            }
        } catch (e: Throwable) {
            tvStatus.text = "Falha ao processar: " + e.message
        }
    }

    private fun checkAutoPrint(text: String) {
        val prefs = getSharedPreferences("air_printer_prefs", Context.MODE_PRIVATE)
        val isDirectPrint = prefs.getBoolean("direct_print", false)
        if (isDirectPrint && text.isNotBlank()) {
            tvStatus.text = "Disparando impressao direta (v2.9)..."
            checkPermissionsAndPrint()
        }
    }

    private fun decodeHtmlEntities(input: String): String {
        return input.replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&nbsp;", " ")
    }

    private fun fetchWebReceipt(urlStr: String) {
        tvStatus.text = "Buscando recibo na web..."
        Thread {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")

                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                reader.close()
                conn.disconnect()

                val rawHtml = sb.toString()
                val preMatch = Regex("(?is)<pre[^>]*>(.*?)</pre>").find(rawHtml)
                val finalResult: String

                if (preMatch != null) {
                    finalResult = decodeHtmlEntities(preMatch.groupValues[1]).trim()
                } else {
                    var clean = rawHtml.replace(Regex("(?is)<script.*?</script>"), "")
                                       .replace(Regex("(?is)<style.*?</style>"), "")
                                       .replace(Regex("(?i)<br\\s*/?>"), "\n")
                                       .replace(Regex("(?i)</p>"), "\n\n")
                                       .replace(Regex("(?i)</div>"), "\n")
                                       .replace(Regex("(?i)</tr>"), "\n")
                                       .replace(Regex("(?i)</li>"), "\n")
                                       .replace(Regex("(?i)</td>"), "  ")

                    val textOnly = clean.replace(Regex("<[^>]+>"), "")
                    finalResult = decodeHtmlEntities(textOnly).trim()
                }

                runOnUiThread {
                    updateEditor(finalResult)
                    tvStatus.text = "Recibo pronto para imprimir!"
                    checkAutoPrint(finalResult)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    tvStatus.text = "Erro ao baixar pagina: " + e.message
                    updateEditor(urlStr)
                }
            }
        }.start()
    }

    private fun updateEditor(text: String) {
        etContent.setText(text)
        if (text.isNotEmpty()) {
            etContent.setSelection(text.length)
        }
    }

    private fun checkPermissionsAndPrint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_PRINT)
                return
            }
        }
        executePrint(etContent.text.toString())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PRINT) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                executePrint(etContent.text.toString())
            } else {
                Toast.makeText(this, "Permissao necessaria para impressao.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createConnectedSocket(device: BluetoothDevice): BluetoothSocket {
        return try {
            val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            s
        } catch (e1: Exception) {
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val s = method.invoke(device, 1) as BluetoothSocket
                s.connect()
                s
            } catch (e2: Exception) {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                s
            }
        }
    }

    private fun executePrint(rawText: String) {
        if (rawText.isBlank()) {
            Toast.makeText(this, "Nenhum texto para imprimir.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                tvStatus.text = "Bluetooth desligado."
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                tvStatus.text = "Permissao Bluetooth nao concedida."
                return
            }

            val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
            val printerDevice = pairedDevices.firstOrNull { 
                it.name != null && (it.name.contains("MPT", ignoreCase = true) || it.name.contains("POS", ignoreCase = true))
            }

            if (printerDevice == null) {
                tvStatus.text = "MPT-II nao encontrada nos pareados."
                return
            }

            tvStatus.text = "Imprimindo via UTF-8 Nativo (v2.9)..."

            val prefs = getSharedPreferences("air_printer_prefs", Context.MODE_PRIVATE)
            val feedLinesCount = prefs.getInt("feed_lines", 2) // Padrão: 2 linhas

            Thread {
                var socket: BluetoothSocket? = null
                var outStream: OutputStream? = null
                try {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        bluetoothAdapter.cancelDiscovery()
                    }

                    socket = createConnectedSocket(printerDevice)
                    outStream = socket.outputStream

                    // ESC @ (Reset inicial)
                    outStream.write(byteArrayOf(0x1B, 0x40))

                    // ENVIA EXATAMENTE EM UTF-8 PURO (IDENTICO AO TESTE DE DIAGNOSTICO QUE FUNCIONOU)
                    val textBytes = rawText.toByteArray(Charsets.UTF_8)
                    outStream.write(textBytes)

                    // Linhas de corte (2 linhas)
                    val feedBytes = ByteArray(feedLinesCount) { 0x0A }
                    outStream.write(feedBytes)
                    outStream.flush()

                    Thread.sleep(200)

                    runOnUiThread {
                        tvStatus.text = "Impressao v2.9 concluida!"
                        Toast.makeText(this, "Impresso com sucesso!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        tvStatus.text = "Erro ao imprimir: " + (e.message ?: "Erro desconhecido")
                    }
                } finally {
                    try { outStream?.close() } catch (ignored: Throwable) {}
                    try { socket?.close() } catch (ignored: Throwable) {}
                }
            }.start()
        } catch (e: Throwable) {
            tvStatus.text = "Falha geral no Bluetooth: " + e.message
        }
    }
}