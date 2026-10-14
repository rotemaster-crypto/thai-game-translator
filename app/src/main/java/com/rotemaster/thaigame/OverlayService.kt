package com.rotemaster.thaigame

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        const val CHANNEL = "live"
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var capThread: HandlerThread

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Any()
    private var latest: Image? = null
    private var sw = 0
    private var sh = 0

    private var bubble: TextView? = null
    private var subtitle: TextView? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var subLp: WindowManager.LayoutParams

    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private lateinit var audio: AudioManager
    private var focusReq: AudioFocusRequest? = null
    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var ocr: Ocr? = null
    private var ocrLang = ""
    private lateinit var translator: ThaiTranslator

    private val busy = AtomicBoolean(false)
    private var auto = false
    private var lastSig: IntArray? = null
    private var stable = 0
    private var lastKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        startAsForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        translator = ThaiTranslator(this)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        startCapture()
        addOverlays()
        tts = TextToSpeech(this) { status ->
            ttsOk = status == TextToSpeech.SUCCESS &&
                (tts?.setLanguage(Locale("th", "TH")) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0
            if (!ttsOk) toast("ยังไม่มีเสียงพูดภาษาไทย — ตั้งค่าในแอปหน้าแรก")
            tts?.setAudioAttributes(speechAttrs)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { releaseFocus() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { releaseFocus() }
                override fun onStop(id: String?, interrupted: Boolean) { if (!interrupted) releaseFocus() }
            })
        }
        auto = Prefs.get(this).getBoolean(Prefs.AUTO, true)
        updateBubble()
        if (auto) scheduleTick()
        toast(if (auto) "เริ่มแปลอัตโนมัติ — แตะปุ่มลอยเพื่อแปลทันที, กดค้างเพื่อปิดโหมดอัตโนมัติ"
              else "แตะปุ่มลอยเพื่อแปลหน้าจอ, กดค้างเพื่อเปิดโหมดอัตโนมัติ")
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ foreground notification
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "แปลเกมขณะเล่น", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("แปลเกมไทย กำลังทำงาน")
            .setContentText("แตะปุ่มลอยเพื่อแปล • กดค้างเพื่อสลับโหมดอัตโนมัติ")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "ปิด", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    // ------------------------------------------------------------------ screen capture
    private fun startCapture() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels
        sh = dm.heightPixels
        capThread = HandlerThread("cap").also { it.start() }
        val r = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            synchronized(frameLock) { latest?.close(); latest = img }
        }, Handler(capThread.looper))
        reader = r
        display = projection?.createVirtualDisplay("thai-game-cap", sw, sh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    /** Current screen as bitmap, with our own overlays painted out so they are never read back. */
    private fun grab(): Bitmap? {
        val bmp: Bitmap = synchronized(frameLock) {
            val img = latest ?: return null
            val plane = img.planes[0]
            val rowW = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowW, img.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            full.copyPixelsFromBuffer(plane.buffer)
            if (rowW == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        }
        val c = Canvas(bmp)
        val black = Paint().apply { color = Color.BLACK }
        for (v in listOf<View?>(bubble, subtitle)) {
            if (v == null || v.visibility != View.VISIBLE || !v.isAttachedToWindow) continue
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            c.drawRect(Rect(loc[0] - 4, loc[1] - 4, loc[0] + v.width + 4, loc[1] + v.height + 4), black)
        }
        val area = Prefs.get(this).getInt(Prefs.AREA, 0)
        if (area == 0) return bmp
        val top = if (area == 1) bmp.height / 2 else bmp.height * 2 / 3
        return Bitmap.createBitmap(bmp, 0, top, bmp.width, bmp.height - top)
    }

    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 64, 36, true)
        val px = IntArray(64 * 36)
        s.getPixels(px, 0, 64, 0, 0, 64, 36)
        return IntArray(px.size) { (Color.red(px[it]) * 3 + Color.green(px[it]) * 6 + Color.blue(px[it])) / 10 }
    }

    private fun changed(a: IntArray, b: IntArray) = a.indices.count { abs(a[it] - b[it]) > 18 } > 6

    // ------------------------------------------------------------------ translate pipeline
    private fun scheduleTick() {
        val ms = Prefs.get(this).getInt(Prefs.INTERVAL, 800).toLong()
        main.postDelayed({ if (running && auto) { tick(); scheduleTick() } }, ms)
    }

    /** Auto mode: translate once the screen has changed and then stayed still (text finished typing). */
    private fun tick() {
        if (busy.get()) return
        worker.execute {
            val bmp = grab() ?: return@execute
            val sig = signature(bmp)
            val prev = lastSig
            lastSig = sig
            if (prev == null || changed(sig, prev)) { stable = 0; return@execute }
            stable++
            if (stable == 1) translate(bmp, manual = false)
        }
    }

    private fun translateNow() {
        worker.execute {
            val bmp = grab()
            if (bmp == null) { toast("ยังไม่ได้ภาพหน้าจอ ลองอีกครั้ง"); return@execute }
            translate(bmp, manual = true)
        }
    }

    /** Runs on worker thread. */
    private fun translate(bmp: Bitmap, manual: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        main.post { bubble?.alpha = 0.5f }
        try {
            val p = Prefs.get(this)
            val lang = p.getString(Prefs.LANG, "ja")!!
            if (ocr == null || ocrLang != lang) { ocr?.close(); ocr = Ocr(lang); ocrLang = lang }
            val blocks = ocr!!.read(bmp)
            if (blocks.isEmpty()) {
                if (manual) toast("ไม่พบข้อความบนจอ")
                return
            }
            val key = blocks.joinToString("|") { it.text }.filterNot { it.isWhitespace() }
            if (!manual && similarity(key, lastKey) > 0.85) return   // same dialogue, OCR jitter
            lastKey = key
            val engine = p.getString(Prefs.ENGINE, "google")!!
            val thai = translator.translateAll(blocks.map { it.text }, lang, engine) { msg -> toast(msg) }
            val text = thai.filter { it.isNotBlank() }.joinToString("\n")
            main.post { showSubtitle(text) }
            if (p.getBoolean(Prefs.VOICE, true) && ttsOk) {
                tts?.setSpeechRate(p.getInt(Prefs.RATE, 11) / 10f)
                requestFocus(p.getString(Prefs.DUCK, "duck")!!)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "line")
            }
        } catch (e: Exception) {
            toast("แปลไม่สำเร็จ: ${e.message}")
        } finally {
            busy.set(false)
            main.post { bubble?.alpha = 1f }
        }
    }

    // ------------------------------------------------------------------ overlay views
    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlays() {
        val sub = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = Prefs.get(this@OverlayService).getInt(Prefs.SUB_SIZE, 20).toFloat()
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(0xE60A0C1C.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0xFF00C37A.toInt()) }
            visibility = View.GONE
            setLineSpacing(0f, 1.15f)
        }
        subLp = WindowManager.LayoutParams(
            (sw * 0.86).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (sh * 0.70).toInt() }
        makeDraggable(sub, subLp, allowX = false, onTap = { sub.visibility = View.GONE }, onLong = null)
        wm.addView(sub, subLp)
        subtitle = sub

        val b = TextView(this).apply {
            text = "แปล"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        bubbleLp = WindowManager.LayoutParams(dp(56), dp(56), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = sw - dp(72); y = dp(90) }
        makeDraggable(b, bubbleLp, allowX = true, onTap = { translateNow() }, onLong = {
            auto = !auto
            Prefs.get(this).edit().putBoolean(Prefs.AUTO, auto).apply()
            updateBubble()
            toast(if (auto) "เปิดแปลอัตโนมัติ" else "ปิดแปลอัตโนมัติ (แตะเพื่อแปลเอง)")
            if (auto) { lastSig = null; scheduleTick() }
        })
        wm.addView(b, bubbleLp)
        bubble = b
    }

    private fun updateBubble() {
        bubble?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (auto) 0xEE00A86B.toInt() else 0xEE007ACC.toInt())
            setStroke(dp(2), Color.WHITE)
        }
        bubble?.text = if (auto) "อัตโน\nมัติ" else "แปล"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(v: View, lp: WindowManager.LayoutParams, allowX: Boolean, onTap: () -> Unit, onLong: (() -> Unit)?) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var downAt = 0L
        val longRunnable = Runnable { if (!moved) { moved = true; onLong?.invoke() } }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; downAt = System.currentTimeMillis()
                    if (onLong != null) main.postDelayed(longRunnable, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        if (!moved) main.removeCallbacks(longRunnable)
                        moved = true
                        if (allowX) lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longRunnable)
                    if (!moved && System.currentTimeMillis() - downAt < 600) onTap()
                }
            }
            true
        }
    }

    private fun showSubtitle(text: String) {
        val s = subtitle ?: return
        s.text = text
        s.visibility = View.VISIBLE
    }

    /** Lower (duck) or pause the game's sound while the Thai voice speaks; restored when speech ends. */
    private fun requestFocus(mode: String) {
        if (mode == "off") return
        val gain = if (mode == "mute") AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                   else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(speechAttrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        focusReq?.let { audio.abandonAudioFocusRequest(it) }
        focusReq = req
        audio.requestAudioFocus(req)
    }

    private fun releaseFocus() {
        focusReq?.let { audio.abandonAudioFocusRequest(it) }
        focusReq = null
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        running = false
        auto = false
        main.removeCallbacksAndMessages(null)
        if (::wm.isInitialized) for (v in listOf<View?>(bubble, subtitle)) try { if (v != null) wm.removeView(v) } catch (_: Exception) {}
        bubble = null; subtitle = null
        tts?.stop(); tts?.shutdown()
        if (::audio.isInitialized) releaseFocus()
        display?.release()
        projection?.stop()
        synchronized(frameLock) { latest?.close(); latest = null }
        reader?.close()
        if (::capThread.isInitialized) capThread.quitSafely()
        worker.shutdownNow()
        try { ocr?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
