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
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
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
    private val REQ_INSTALL_MAIN = 104
    private var pendingDownloadUrl: String? = null

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

            // Checagem proativa ao abrir
            if (BuildConfig.ENABLE_INAPP_UPDATE) { checkForAppUpdateProactively() }

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
                    val formatted = formatTextForThermal58mm(incomingText)
                    updateEditor(formatted)
                    checkAutoPrint(formatted)
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
            tvStatus.text = "Disparando impressao direta..."
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

    /**
     * Formata o texto para caber exatamente em 32 colunas tÃ©rmicas,
     * permitindo que o usuÃ¡rio veja e edite antes de mandar imprimir.
     */
    private fun formatTextForThermal58mm(input: String, maxColumns: Int = 32): String {
        val sb = StringBuilder()
        val originalLines = input.lines()

        for (line in originalLines) {
            val trimmed = line.trimEnd()

            if (trimmed.matches(Regex("^-{3,}$"))) {
                sb.append("-".repeat(maxColumns)).append("\n")
                continue
            }

            if (trimmed.length <= maxColumns) {
                sb.append(trimmed).append("\n")
                continue
            }

            val words = trimmed.split(" ")
            var currentLine = StringBuilder()

            for (word in words) {
                if (word.isEmpty()) continue

                if (currentLine.isEmpty()) {
                    currentLine.append(word)
                } else if (currentLine.length + 1 + word.length <= maxColumns) {
                    currentLine.append(" ").append(word)
                } else {
                    sb.append(currentLine.toString()).append("\n")
                    currentLine = StringBuilder(word)
                }
            }

            if (currentLine.isNotEmpty()) {
                sb.append(currentLine.toString()).append("\n")
            }
        }

        return sb.toString().trimEnd()
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
                val extractedText: String

                if (preMatch != null) {
                    extractedText = decodeHtmlEntities(preMatch.groupValues[1]).trim()
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
                    extractedText = decodeHtmlEntities(textOnly).trim()
                }

                // O texto que entra no editor jÃ¡ Ã© formatado para 32 colunas
                val finalFormatted = formatTextForThermal58mm(extractedText)

                runOnUiThread {
                    updateEditor(finalFormatted)
                    tvStatus.text = "Recibo pronto no visualizador!"
                    checkAutoPrint(finalFormatted)
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

    private fun if (BuildConfig.ENABLE_INAPP_UPDATE) { checkForAppUpdateProactively() } {
        Thread {
            try {
                val apiUrl = URL("https://api.github.com/repos/Kintessence/mpt-printer/releases/latest")
                val conn = apiUrl.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "AirPrinterApp")
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                if (conn.responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                    val jsonStr = reader.readText()
                    reader.close()

                    val json = JSONObject(jsonStr)
                    val tagName = json.optString("tag_name", "")
                    val assets = json.optJSONArray("assets")
                    var assetDownloadUrl = "https://github.com/Kintessence/mpt-printer/releases/latest/download/AirPrinter.apk"
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                                assetDownloadUrl = asset.optString("browser_download_url", assetDownloadUrl)
                                break
                            }
                        }
                    }

                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"

                    if (isNewerVersion(tagName, currentVersion)) {
                        pendingDownloadUrl = assetDownloadUrl
                        runOnUiThread {
                            showUpdatePromptDialog(tagName)
                        }
                    }
                }
            } catch (ignored: Throwable) {}
        }.start()
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val cleanRemote = remote.trim().removePrefix("v").removePrefix("V")
        val cleanLocal = local.trim().removePrefix("v").removePrefix("V")
        if (cleanRemote.equals(cleanLocal, ignoreCase = true)) return false

        val rParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)

        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun showUpdatePromptDialog(newVersion: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("AtualizaÃ§Ã£o DisponÃ­vel")
            .setMessage("Nova versÃ£o $newVersion do Air Printer encontrada.\n\nDeseja atualizar agora?")
            .setPositiveButton("Atualizar Agora") { _, _ ->
                if (checkInstallPermission()) {
                    startDownloadUpdate()
                }
            }
            .setNegativeButton("Mais Tarde", null)
            .show()
    }

    private fun checkInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "Ative a permissÃ£o para permitir atualizar o app", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQ_INSTALL_MAIN)
                return false
            }
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INSTALL_MAIN) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                startDownloadUpdate()
            }
        }
    }

    private fun startDownloadUpdate() {
        val targetUrl = pendingDownloadUrl ?: "https://github.com/Kintessence/mpt-printer/releases/latest/download/AirPrinter.apk"
        Toast.makeText(this, "Baixando atualizaÃ§Ã£o...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val url = URL(targetUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val apkFile = File(externalCacheDir ?: cacheDir, "AirPrinter-update.apk")
                if (apkFile.exists()) apkFile.delete()

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
                    val apkUri: Uri = FileProvider.getUriForFile(this, "com.exemplo.mptprinter.provider", apkFile)
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    startActivity(installIntent)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Erro ao baixar atualizaÃ§Ã£o: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun checkPermissionsAndPrint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_PRINT)
                return
            }
        }
        // Imprime EXATAMENTE o texto atualmente visÃ­vel e editado no editor
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

            tvStatus.text = "Imprimindo via UTF-8 Nativo..."

            val prefs = getSharedPreferences("air_printer_prefs", Context.MODE_PRIVATE)
            val feedLinesCount = prefs.getInt("feed_lines", 2)

            Thread {
                var socket: BluetoothSocket? = null
                var outStream: OutputStream? = null
                try {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        bluetoothAdapter.cancelDiscovery()
                    }

                    socket = createConnectedSocket(printerDevice)
                    outStream = socket.outputStream

                    outStream.write(byteArrayOf(0x1B, 0x40))

                    // Re-formata para garantir que qualquer ediÃ§Ã£o manual permaneÃ§a em 32 colunas
                    val textToPrint = formatTextForThermal58mm(rawText)
                    val textBytes = textToPrint.toByteArray(Charsets.UTF_8)
                    outStream.write(textBytes)

                    val feedBytes = ByteArray(feedLinesCount) { 0x0A }
                    outStream.write(feedBytes)
                    outStream.flush()

                    Thread.sleep(200)

                    runOnUiThread {
                        tvStatus.text = "Impressao v2.9.4 concluida!"
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