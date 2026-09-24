package com.exemplo.mptprinter

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val cbDirectPrint = findViewById<CheckBox>(R.id.cbDirectPrint)
        val spFeedLines = findViewById<Spinner>(R.id.spFeedLines)
        val spCodePage = findViewById<Spinner>(R.id.spCodePage)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        val prefs = getSharedPreferences("air_printer_prefs", Context.MODE_PRIVATE)

        // Configuração do Spinner de linhas de corte
        val feedOptions = arrayOf("2 linhas", "3 linhas", "4 linhas", "5 linhas", "6 linhas")
        val feedAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, feedOptions)
        spFeedLines.adapter = feedAdapter

        // Configuração do Spinner de codificação
        val cpOptions = arrayOf("CP850 (Latino/Acentos - Padrão)", "CP860 (Português)", "Sem acentos (Sanitizado)")
        val cpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cpOptions)
        spCodePage.adapter = cpAdapter

        // Carregar valores salvos
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
    }
}