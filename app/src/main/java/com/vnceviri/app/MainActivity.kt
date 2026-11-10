package com.vnceviri.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : Activity() {

    private val reqCapture = 1001
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vn", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }

        val d = resources.displayMetrics.density
        fun px(v: Int): Int = (v * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(px(20), px(20), px(20), px(20))

        fun label(t: String, size: Float = 16f): TextView {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = size
            tv.setPadding(0, px(14), 0, px(4))
            root.addView(tv)
            return tv
        }

        fun button(t: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = t
            b.isAllCaps = false
            b.setOnClickListener { onClick() }
            root.addView(b)
            return b
        }

        label("VN Çeviri", 28f)
        status = label("", 15f)

        button("1) Ekran üstünde gösterme iznini ver") { requestOverlay() }

        label("2) Oyundaki yazının dili:")
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Langs.all.map { it.label }
        )
        sp.setSelection(prefs.getInt("src", 0).coerceIn(0, Langs.all.size - 1))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt("src", pos).apply()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(sp)

        label("3) Çeviri modu:")
        val rg = RadioGroup(this)
        val rbOff = RadioButton(this)
        rbOff.text = "Çevrimdışı - en hızlı, internet gerekmez"
        rbOff.id = View.generateViewId()
        val rbOn = RadioButton(this)
        rbOn.text = "Çevrimiçi (Google) - daha kaliteli, internet gerekir"
        rbOn.id = View.generateViewId()
        rg.addView(rbOff)
        rg.addView(rbOn)
        if (prefs.getString("mode", "offline") == "online") rbOn.isChecked = true else rbOff.isChecked = true
        rg.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putString("mode", if (checked == rbOn.id) "online" else "offline").apply()
        }
        root.addView(rg)

        button("Çevrimdışı çeviri modelini indir (bir kez, internet gerekir)") { downloadModel() }

        label("Çeviri yazı boyutu:")
        val sb = SeekBar(this)
        sb.max = 28
        sb.progress = prefs.getInt("font", 20).coerceIn(12, 40) - 12
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt("font", 12 + p).apply()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(sb)

        button("4) BAŞLAT") { startCapture() }
        button("Durdur") {
            stopService(Intent(this, CaptureService::class.java))
            refreshStatus()
        }

        label(
            "Nasıl kullanılır:\n" +
                "- BAŞLAT'a bas, çıkan pencerede \"Tüm ekran\" seçeneğini seç.\n" +
                "- Oyunu aç. Ekranda küçük bir çubuk görünür.\n" +
                "- Çubukta \"Alan\"a bas ve oyundaki diyalog kutusunu parmağınla çiz.\n" +
                "- \"Oto\"ya bas: yazı değiştikçe çeviri otomatik, anında çıkar.\n" +
                "- \"Tek\": sadece bir kez çevirir. \"A-\" / \"A+\": çeviri yazısını küçültür / büyütür. \"Taşı\": çubuğu sürükler.\n" +
                "- Çeviri kutusu diyalog kutusunun hemen üstünde çıkar ve dokunuşları oyuna geçirir.",
            14f
        )

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val ov = if (Settings.canDrawOverlays(this)) "VAR" else "YOK"
        val run = if (CaptureService.running) "ÇALIŞIYOR" else "kapalı"
        status.text = "Üstte gösterme izni: $ov\nÇeviri servisi: $run"
    }

    private fun requestOverlay() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun downloadModel() {
        val l = Langs.all[prefs.getInt("src", 0).coerceIn(0, Langs.all.size - 1)]
        status.text = "İndiriliyor: ${l.label} -> Türkçe ..."
        val tr = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(l.mlkit)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
        )
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                status.text = "Model hazır: ${l.label} -> Türkçe. Artık internetsiz çalışır."
                tr.close()
            }
            .addOnFailureListener {
                status.text = "İndirilemedi. İnternet bağlantını kontrol edip tekrar dene."
                tr.close()
            }
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Önce 1. adımdaki izni ver", Toast.LENGTH_LONG).show()
            requestOverlay()
            return
        }
        if (CaptureService.running) {
            Toast.makeText(this, "Zaten çalışıyor. Yeniden başlatmak için önce Durdur'a bas.", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                i.putExtra(CaptureService.EXTRA_CODE, resultCode)
                i.putExtra(CaptureService.EXTRA_DATA, data)
                startForegroundService(i)
                Toast.makeText(this, "Başlatıldı. Şimdi oyunu aç; çubuk ekranda görünecek.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Ekran yakalama izni verilmedi", Toast.LENGTH_LONG).show()
            }
        }
    }
}
