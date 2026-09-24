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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var etContent: EditText
    private lateinit var btnPrint: Button
    private lateinit var tvStatus: TextView
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etContent = findViewById(R.id.etContent)
        btnPrint = findViewById(R.id.btnPrint)
        tvStatus = findViewById(R.id.tvStatus)

        handleIncomingIntent(intent)

        btnPrint.setOnClickListener {
            checkPermissionsAndPrint()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        var incomingText: String? = null

        if (intent.action == Intent.ACTION_SEND) {
            // Tenta obter como texto normal
            incomingText = intent.getStringExtra(Intent.EXTRA_TEXT)
            
            // Se for nulo, tenta obter como sequência de caracteres
            if (incomingText.isNullOrEmpty()) {
                incomingText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }
        } else if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            incomingText = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        }

        if (!incomingText.isNullOrEmpty()) {
            etContent.setText(incomingText)
            etContent.setSelection(etContent.text.length)
            tvStatus.text = "Texto carregado com sucesso!"
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

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            tvStatus.text = "Bluetooth desativado ou indisponível."
            return
        }

        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
        val printerDevice = pairedDevices.firstOrNull { 
            it.name != null && (it.name.contains("MPT", ignoreCase = true) || it.name.contains("POS", ignoreCase = true))
        }

        if (printerDevice == null) {
            tvStatus.text = "MPT-II não encontrada. Confirme se está emparelhada no Bluetooth."
            return
        }

        tvStatus.text = "A ligar a ..."

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
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Erro: "
                }
            } finally {
                try {
                    outStream?.close()
                    socket?.close()
                } catch (_: Exception) {}
            }
        }.start()
    }
}