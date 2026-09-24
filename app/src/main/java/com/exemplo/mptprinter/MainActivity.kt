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
import android.text.Html
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
import java.nio.charset.Charset
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var etContent: EditText
    private lateinit var btnPrint: Button
    private lateinit var tvStatus: TextView
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            etContent = findViewById(R.id.etContent)
            btnPrint = findViewById(R.id.btnPrint)
            tvStatus = findViewById(R.id.tvStatus)

            btnPrint.setOnClickListener {
                checkPermissionsAndPrint()
            }

            handleIncomingIntent(intent)
        } catch (e: Throwable) {
            Log.e("AirPrinter", "Erro no onCreate", e)
            Toast.makeText(this, "Erro ao iniciar: \", Toast.LENGTH_LONG).show()
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
                // Se for URL (ou contiver http/https no texto)
                val urlMatch = Regex("https?://[^\s]+").find(trimmed)?.value
                if (urlMatch != null) {
                    fetchWebReceipt(urlMatch)
                } else {
                    updateEditor(incomingText)
                }
            }
        } catch (e: Throwable) {
            tvStatus.text = "Falha ao processar compartilhamento: \"
        }
    }

    private fun fetchWebReceipt(urlStr: String) {
        tvStatus.text = "Buscando dados da página web..."
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
                val reader = BufferedReader(InputStreamReader(stream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                reader.close()
                conn.disconnect()

                val rawHtml = sb.toString()
                val cleanedHtml = rawHtml.replace("(?s)<script.*?</script>".toRegex(), "")
                                         .replace("(?s)<style.*?</style>".toRegex(), "")
                                         .replace("<br\s*/?>".toRegex(), "\n")
                                         .replace("</p>".toRegex(), "\n\n")
                                         .replace("</div>".toRegex(), "\n")
                                         .replace("</tr>".toRegex(), "\n")
                                         .replace("</td>".toRegex(), " ")

                val parsedText = Html.fromHtml(cleanedHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim()

                runOnUiThread {
                    updateEditor(parsedText)
                    tvStatus.text = "Recibo pronto para imprimir!"
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    tvStatus.text = "Erro ao baixar página: \"
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
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
                return
            }
        }
        executePrint(etContent.text.toString())
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
                tvStatus.text = "Bluetooth desligado ou indisponível."
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                tvStatus.text = "Permissão Bluetooth não concedida."
                return
            }

            val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
            val printerDevice = pairedDevices.firstOrNull { 
                it.name != null && (it.name.contains("MPT", ignoreCase = true) || it.name.contains("POS", ignoreCase = true))
            }

            if (printerDevice == null) {
                tvStatus.text = "MPT-II não encontrada. Confirme se está pareada."
                return
            }

            tvStatus.text = "Conectando à \..."

            Thread {
                var socket: BluetoothSocket? = null
                var outStream: OutputStream? = null
                try {
                    socket = printerDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket.connect()
                    outStream = socket.outputStream

                    val ESC_INIT = byteArrayOf(0x1B, 0x40)
                    val CODE_PAGE_860 = byteArrayOf(0x1B, 0x74, 0x03)
                    val FEED_AND_CUT = byteArrayOf(0x0A, 0x0A, 0x0A)

                    outStream.write(ESC_INIT)
                    outStream.write(CODE_PAGE_860)

                    val textBytes = rawText.toByteArray(Charset.forName("CP860"))
                    outStream.write(textBytes)
                    outStream.write(FEED_AND_CUT)
                    outStream.flush()

                    runOnUiThread {
                        tvStatus.text = "Impressão concluída!"
                        Toast.makeText(this, "Impresso com sucesso!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        tvStatus.text = "Erro de impressão: \"
                    }
                } finally {
                    try {
                        outStream?.close()
                        socket?.close()
                    } catch (_: Throwable) {}
                }
            }.start()
        } catch (e: Throwable) {
            tvStatus.text = "Falha geral no Bluetooth: \"
        }
    }
}