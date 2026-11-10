package com.vnceviri.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CHANNEL_ID = "vn_ceviri"

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var autoFuture: ScheduledFuture<*>? = null

    // Ekran yakalama
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var lastImage: Image? = null
    private var capW = 0
    private var capH = 0
    private var dpi = 320

    // Değişiklik takibi
    @Volatile private var lastSig = 0L
    @Volatile private var pending = false
    @Volatile private var forceNext = false
    @Volatile private var lastText = ""
    private var autoOn = false

    // OCR / çeviri
    private var recognizer: TextRecognizer? = null
    private var recIdx = -1
    private var translator: Translator? = null
    private var trIdx = -1
    private var modelReady = false
    private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 300
        }
    }

    // Ekran üstü görünümler
    private var bar: LinearLayout? = null
    private var autoBtn: TextView? = null
    private var transView: TextView? = null
    private var transLp: WindowManager.LayoutParams? = null
    private var selector: SelectorView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("vn", MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (running) return START_NOT_STICKY
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val data = readData(intent)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    ui.post { stopSelf() }
                }
            }, ui)
            setupCapture(p)
            createTranslationView()
            createControlBar()
            running = true
            warmUp()
            toast("VN Çeviri hazır. \"Alan\" ile yazı alanını seç, sonra \"Oto\"ya bas.")
        } catch (e: Exception) {
            toast("Başlatılamadı: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readData(i: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            i.getParcelableExtra<Intent>(EXTRA_DATA)
        }
    }

    private fun startAsForeground() {
        val ch = NotificationChannel(CHANNEL_ID, "VN Çeviri", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VN Çeviri çalışıyor")
            .setContentText("Kapatmak için çubuktaki X'e bas veya uygulamadan Durdur'a bas")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- yakalama

    private fun screenSize(): IntArray {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            intArrayOf(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            intArrayOf(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun setupCapture(p: MediaProjection) {
        val s = screenSize()
        capW = s[0]
        capH = s[1]
        dpi = resources.displayMetrics.densityDpi
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        reader = r
        vd = p.createVirtualDisplay(
            "vnceviri", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    /** Ekran döndürülürse yakalamayı yeni boyuta uyarlar. */
    private fun rebuild(w: Int, h: Int) {
        lastImage?.close()
        lastImage = null
        val old = reader
        val nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        vd?.resize(w, h, dpi)
        vd?.surface = nr.surface
        reader = nr
        old?.close()
        capW = w
        capH = h
        lastSig = 0L
        pending = false
    }

    private fun refreshFrame(): Image? {
        val s = screenSize()
        if (s[0] != capW || s[1] != capH) rebuild(s[0], s[1])
        val rd = reader ?: return null
        val img = rd.acquireLatestImage()
        if (img != null) {
            lastImage?.close()
            lastImage = img
        }
        return lastImage
    }

    private fun region(w: Int, h: Int): Rect {
        val l = prefs.getFloat("rl", 0.04f)
        val t = prefs.getFloat("rt", 0.66f)
        val r = prefs.getFloat("rr", 0.96f)
        val b = prefs.getFloat("rb", 0.98f)
        val rect = Rect((l * w).toInt(), (t * h).toInt(), (r * w).toInt(), (b * h).toInt())
        rect.left = rect.left.coerceIn(0, w - 32)
        rect.top = rect.top.coerceIn(0, h - 32)
        rect.right = rect.right.coerceIn(rect.left + 16, w)
        rect.bottom = rect.bottom.coerceIn(rect.top + 16, h)
        return rect
    }

    /** Seçili alandan seyrek örneklerle küçük bir "parmak izi" çıkarır. Çok hızlıdır. */
    private fun signature(img: Image, r: Rect): Long {
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val lim = buf.limit()
        val sx = maxOf(1, r.width() / 220)
        val sy = maxOf(1, r.height() / 70)
        var h = 1469598103934665603L
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val o = y * rs + x * ps
                if (o + 2 < lim) {
                    val v = ((buf.get(o).toInt() and 0xF0) shl 4) or
                        (buf.get(o + 1).toInt() and 0xF0) or
                        ((buf.get(o + 2).toInt() and 0xF0) shr 4)
                    h = (h xor v.toLong()) * 1099511628211L
                }
                x += sx
            }
            y += sy
        }
        return h
    }

    /** Sadece seçili alanın satırlarını kopyalayıp kırpar. */
    private fun crop(img: Image, r: Rect): Bitmap? {
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val fullW = rs / ps
        val h = minOf(r.height(), img.height - r.top - 1)
        if (h < 8) return null
        buf.position(r.top * rs)
        val tmp = Bitmap.createBitmap(fullW, h, Bitmap.Config.ARGB_8888)
        tmp.copyPixelsFromBuffer(buf)
        buf.rewind()
        val w = minOf(r.width(), fullW - r.left)
        if (w < 8) {
            tmp.recycle()
            return null
        }
        val out = Bitmap.createBitmap(tmp, r.left, 0, w, h)
        if (out !== tmp) tmp.recycle()
        return out
    }

    // ---------------------------------------------------------------- döngü

    private fun setAuto(on: Boolean) {
        autoOn = on
        autoFuture?.cancel(false)
        autoFuture = null
        val btn = autoBtn
        if (on) {
            lastText = ""
            lastSig = 0L
            pending = false
            forceNext = true
            autoFuture = worker.scheduleWithFixedDelay({ tick() }, 0L, 100L, TimeUnit.MILLISECONDS)
            btn?.text = "Oto: AÇIK"
            btn?.setBackgroundColor(Color.argb(255, 30, 140, 70))
        } else {
            btn?.text = "Oto: KAPALI"
            btn?.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun tick() {
        try {
            val img = refreshFrame() ?: return
            val r = region(capW, capH)
            val sig = signature(img, r)
            if (forceNext) {
                forceNext = false
                lastSig = sig
                pending = false
                process(img, r)
                return
            }
            if (sig != lastSig) {
                // yazı değişiyor (daktilo efekti vs.) - durulmasını bekle
                lastSig = sig
                pending = true
                return
            }
            if (pending) {
                pending = false
                process(img, r)
            }
        } catch (e: Exception) {
            // bir sonraki turda tekrar denenir
        }
    }

    private fun runOnce() {
        try {
            val img = refreshFrame()
            if (img == null) {
                toast("Ekran görüntüsü henüz hazır değil, tekrar dene")
                return
            }
            lastText = ""
            process(img, region(capW, capH))
        } catch (e: Exception) {
            toast("Hata: ${e.message}")
        }
    }

    private fun process(img: Image, r: Rect) {
        val idx = prefs.getInt("src", 0).coerceIn(0, Langs.all.size - 1)
        val mode = prefs.getString("mode", "offline") ?: "offline"
        val bmp = crop(img, r) ?: return
        val text = try {
            ocr(bmp, idx)
        } finally {
            bmp.recycle()
        }
        if (text.length < 2) {
            lastText = ""
            hideTranslation()
            return
        }
        if (text == lastText) return
        lastText = text
        val key = "$idx|$mode|$text"
        var result = cache[key]
        if (result == null) {
            result = translate(text, idx, mode)
            if (result != null) cache[key] = result
        }
        if (result == null) {
            showTranslation("Çeviri yapılamadı (modeli indirdin mi / internet var mı?)")
        } else {
            showTranslation(result)
        }
    }

    private fun warmUp() {
        worker.execute {
            val idx = prefs.getInt("src", 0).coerceIn(0, Langs.all.size - 1)
            val mode = prefs.getString("mode", "offline") ?: "offline"
            try {
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                ocr(bmp, idx)
                bmp.recycle()
            } catch (e: Exception) {
            }
            try {
                if (mode == "online") onlineTranslate("Hello", idx) else offlineTranslate("Hello", idx)
            } catch (e: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- OCR

    private fun getRecognizer(idx: Int): TextRecognizer {
        val existing = recognizer
        if (existing != null && recIdx == idx) return existing
        existing?.close()
        val rec: TextRecognizer = when (Langs.all[idx].script) {
            Langs.JA -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Langs.ZH -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Langs.KO -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        recognizer = rec
        recIdx = idx
        return rec
    }

    private fun ocr(bmp: Bitmap, idx: Int): String {
        val res = Tasks.await(getRecognizer(idx).process(InputImage.fromBitmap(bmp, 0)))
        val script = Langs.all[idx].script
        val sep = if (script == Langs.LATIN || script == Langs.KO) " " else ""
        val sb = StringBuilder()
        for (b in res.textBlocks) {
            val line = b.lines.joinToString(sep) { it.text }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------- çeviri

    private fun translate(text: String, idx: Int, mode: String): String? {
        val order = if (mode == "online") listOf(true, false) else listOf(false, true)
        for (online in order) {
            try {
                val r = if (online) onlineTranslate(text, idx) else offlineTranslate(text, idx)
                if (r != null && r.isNotBlank()) return r
            } catch (e: Exception) {
                // diğer yöntemi dene
            }
        }
        return null
    }

    private fun getTranslator(idx: Int): Translator {
        val existing = translator
        if (existing != null && trIdx == idx) return existing
        existing?.close()
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(Langs.all[idx].mlkit)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
        )
        translator = t
        trIdx = idx
        modelReady = false
        return t
    }

    private fun offlineTranslate(text: String, idx: Int): String {
        val t = getTranslator(idx)
        if (!modelReady) {
            Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            modelReady = true
        }
        return Tasks.await(t.translate(text))
    }

    private fun onlineTranslate(text: String, idx: Int): String? {
        val sl = Langs.all[idx].google
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + sl +
                "&tl=tr&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 2500
        c.readTimeout = 3500
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (c.responseCode != 200) {
            c.errorStream?.close()
            return null
        }
        val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(body).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val seg = arr.optJSONArray(i) ?: continue
            if (!seg.isNull(0)) sb.append(seg.getString(0))
        }
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------- ekran üstü arayüz

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun createTranslationView() {
        val d = resources.displayMetrics.density
        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.textSize = prefs.getInt("font", 20).toFloat()
        val p = (12 * d).toInt()
        tv.setPadding(p, p / 2, p, p / 2)
        val g = GradientDrawable()
        g.setColor(Color.argb(220, 10, 10, 20))
        g.cornerRadius = 14 * d
        tv.background = g
        tv.visibility = View.GONE
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            false
        )
        transView = tv
        transLp = lp
        wm.addView(tv, lp)
    }

    private fun showTranslation(text: String) {
        ui.post {
            val tv = transView
            val lp = transLp
            if (tv != null && lp != null) {
                val w = capW
                val h = capH
                val r = region(w, h)
                tv.textSize = prefs.getInt("font", 20).toFloat()
                tv.text = text
                lp.width = r.width()
                lp.x = r.left
                if (r.top < h * 0.35f) {
                    // alan ekranın üstündeyse çeviriyi altına koy
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.y = r.bottom
                } else {
                    // normalde: diyalog kutusunun hemen üstü
                    lp.gravity = Gravity.BOTTOM or Gravity.START
                    lp.y = h - r.top
                }
                tv.visibility = View.VISIBLE
                try {
                    wm.updateViewLayout(tv, lp)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun hideTranslation() {
        ui.post { transView?.visibility = View.GONE }
    }

    private fun bumpFont(delta: Int) {
        val f = (prefs.getInt("font", 20) + delta).coerceIn(12, 40)
        prefs.edit().putInt("font", f).apply()
        transView?.textSize = f.toFloat()
    }

    private fun createControlBar() {
        val d = resources.displayMetrics.density
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        val bg = GradientDrawable()
        bg.setColor(Color.argb(205, 25, 25, 35))
        bg.cornerRadius = 20 * d
        b.background = bg

        fun mk(label: String): TextView {
            val t = TextView(this)
            t.text = label
            t.setTextColor(Color.WHITE)
            t.textSize = 15f
            t.setPadding((11 * d).toInt(), (9 * d).toInt(), (11 * d).toInt(), (9 * d).toInt())
            return t
        }

        val handle = mk("Taşı")
        val auto = mk("Oto: KAPALI")
        val once = mk("Tek")
        val area = mk("Alan")
        val minus = mk("A-")
        val plus = mk("A+")
        val close = mk("X")
        for (v in listOf(handle, auto, once, area, minus, plus, close)) b.addView(v)

        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            true
        )
        lp.x = (16 * d).toInt()
        lp.y = (16 * d).toInt()

        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x
                        sy = lp.y
                        tx = e.rawX
                        ty = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - tx).toInt()
                        lp.y = sy + (e.rawY - ty).toInt()
                        try {
                            wm.updateViewLayout(b, lp)
                        } catch (ex: Exception) {
                        }
                    }
                }
                return true
            }
        })

        auto.setOnClickListener { setAuto(!autoOn) }
        once.setOnClickListener { worker.execute { runOnce() } }
        area.setOnClickListener { showSelector() }
        minus.setOnClickListener { bumpFont(-2) }
        plus.setOnClickListener { bumpFont(2) }
        close.setOnClickListener { stopSelf() }

        autoBtn = auto
        bar = b
        wm.addView(b, lp)
    }

    private fun showSelector() {
        if (selector != null) return
        val w = capW
        val h = capH
        val v = SelectorView(this) { rf ->
            ui.post { removeSelector() }
            if (rf != null && rf.width() > 60 && rf.height() > 30) {
                prefs.edit()
                    .putFloat("rl", (rf.left / w).coerceIn(0f, 1f))
                    .putFloat("rt", (rf.top / h).coerceIn(0f, 1f))
                    .putFloat("rr", (rf.right / w).coerceIn(0f, 1f))
                    .putFloat("rb", (rf.bottom / h).coerceIn(0f, 1f))
                    .apply()
                lastText = ""
                lastSig = 0L
                pending = false
                if (autoOn) forceNext = true
                toast("Alan kaydedildi")
            }
        }
        selector = v
        wm.addView(v, overlayParams(w, h, true))
    }

    private fun removeSelector() {
        val v = selector
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
        selector = null
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------------------------------------------------------------- kapanış

    private fun removeViewSafe(v: View?) {
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
    }

    override fun onDestroy() {
        running = false
        autoFuture?.cancel(false)
        worker.shutdownNow()
        try {
            worker.awaitTermination(400, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
        }
        ui.removeCallbacksAndMessages(null)
        removeViewSafe(bar)
        removeViewSafe(transView)
        removeViewSafe(selector)
        bar = null
        transView = null
        selector = null
        try { lastImage?.close() } catch (e: Exception) { }
        try { vd?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        try { recognizer?.close() } catch (e: Exception) { }
        try { translator?.close() } catch (e: Exception) { }
        super.onDestroy()
    }
}
