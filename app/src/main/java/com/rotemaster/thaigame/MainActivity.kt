package com.rotemaster.thaigame

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private val REQ_CAPTURE = 10
    private lateinit var status: TextView
    private lateinit var root: LinearLayout
    private var tts: TextToSpeech? = null
    private var thaiVoice: Boolean? = null

    private val bg = Color.parseColor("#14161F")
    private val card = Color.parseColor("#1F2230")
    private val green = Color.parseColor("#00C37A")
    private val blue = Color.parseColor("#007ACC")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs.get(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        text("แปลเกมเป็นภาษาไทย + พากย์เสียง", 22f, green, bold = true)
        text("ใช้ได้กับทุก emulator (RetroArch, mGBA, DuckStation, Pizza Boy ฯลฯ) — อ่านข้อความบนจอ แปลไทย แสดงซับ และพูดให้ฟัง", 14f, Color.LTGRAY)
        status = text("", 14f, Color.WHITE)

        // ---- big buttons
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("▶  เริ่มใช้งาน", green) { start() }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(8) })
        row.addView(button("■  หยุด", Color.parseColor("#AA3333")) { stop() }, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        // ---- settings
        section("ตั้งค่า")
        spinner("ภาษาในเกม", Prefs.LANG, listOf("ja" to "ญี่ปุ่น", "en" to "อังกฤษ", "zh" to "จีน", "ko" to "เกาหลี"), "ja")
        spinner("แปลด้วย", Prefs.ENGINE, listOf(
            "google" to "Google ออนไลน์ (ฟรี, แนะนำ)",
            "mlkit" to "ออฟไลน์ในเครื่อง (ฟรี, ไม่ต้องใช้เน็ต)",
            "claude" to "Claude AI (แปลดีที่สุด, ต้องมี API key)"), "google")
        spinner("พื้นที่ที่อ่าน", Prefs.AREA + "_s", listOf("0" to "ทั้งจอ", "1" to "ครึ่งล่าง (กล่องข้อความ)", "2" to "1/3 ล่าง"), "0") {
            p.edit().putInt(Prefs.AREA, it.toInt()).apply()
        }
        spinner("ตรวจจอทุก", Prefs.INTERVAL + "_s", listOf("500" to "0.5 วินาที", "800" to "0.8 วินาที", "1200" to "1.2 วินาที", "2000" to "2 วินาที"), "800") {
            p.edit().putInt(Prefs.INTERVAL, it.toInt()).apply()
        }
        spinner("ขนาดซับ", Prefs.SUB_SIZE + "_s", listOf("16" to "เล็ก", "20" to "กลาง", "24" to "ใหญ่", "28" to "ใหญ่มาก"), "20") {
            p.edit().putInt(Prefs.SUB_SIZE, it.toInt()).apply()
        }
        switch("พากย์เสียงภาษาไทย", Prefs.VOICE, true)
        switch("เริ่มด้วยโหมดแปลอัตโนมัติ", Prefs.AUTO, true)
        label("ความเร็วเสียงพูด")
        root.addView(SeekBar(this).apply {
            max = 15; progress = p.getInt(Prefs.RATE, 11) - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { p.edit().putInt(Prefs.RATE, v + 5).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        section("Claude AI (ไม่บังคับ)")
        label("API key จาก console.anthropic.com — ใช้เมื่อเลือก \"แปลด้วย Claude\" (เสียเงินตามการใช้งาน)")
        root.addView(EditText(this).apply {
            setText(p.getString(Prefs.CLAUDE_KEY, ""))
            hint = "sk-ant-..."
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setOnFocusChangeListener { v, has -> if (!has) p.edit().putString(Prefs.CLAUDE_KEY, (v as EditText).text.toString().trim()).apply() }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { p.edit().putString(Prefs.CLAUDE_KEY, s.toString().trim()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        spinner("รุ่น Claude", Prefs.CLAUDE_MODEL, listOf("claude-haiku-5-5" to "Haiku (เร็ว ถูก)", "claude-sonnet-5-5" to "Sonnet (แม่นกว่า)"), "claude-haiku-5-5")

        section("เตรียมเครื่อง (ทำครั้งแรกครั้งเดียว)")
        root.addView(button("1. อนุญาตให้แสดงทับแอปอื่น", blue) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, full())
        root.addView(button("2. ตั้งค่าเสียงพูดภาษาไทย", blue) { openTtsSettings() }, full())
        root.addView(button("3. ดาวน์โหลดตัวแปลออฟไลน์ (~30MB)", blue) { downloadOffline() }, full())
        root.addView(button("ทดลองฟังเสียง", Color.parseColor("#444A5A")) {
            if (thaiVoice == true) tts?.speak("สวัสดีครับ ท่านผู้กล้า ยินดีต้อนรับสู่หมู่บ้านของเรา", TextToSpeech.QUEUE_FLUSH, null, "t")
            else Toast.makeText(this, "ยังไม่มีเสียงภาษาไทย กดข้อ 2 ก่อน", Toast.LENGTH_LONG).show()
        }, full())

        section("วิธีใช้")
        text("• กด \"เริ่มใช้งาน\" → กด \"เริ่มเลย/Start now\" ในหน้าต่างขออนุญาตบันทึกจอ\n" +
            "• เปิดเกมใน emulator — จะมีปุ่มลอยกลม ๆ บนจอ\n" +
            "• โหมดอัตโนมัติ (ปุ่มสีเขียว): ข้อความในเกมเปลี่ยนเมื่อไหร่ แปลและพูดให้เอง\n" +
            "• แตะปุ่มลอย = แปลทันที  |  กดค้าง = เปิด/ปิดโหมดอัตโนมัติ  |  ลากเพื่อย้ายปุ่ม\n" +
            "• แตะซับเพื่อซ่อน, ลากขึ้นลงเพื่อย้ายตำแหน่ง\n" +
            "• ถ้าไปอ่านหลอดพลัง/ตัวเลขบนจอมั่ว ให้เลือกพื้นที่ \"ครึ่งล่าง\"\n" +
            "• ปิดการทำงาน: กดหยุด หรือกด \"ปิด\" ในแถบแจ้งเตือน", 14f, Color.LTGRAY)

        tts = TextToSpeech(this) { st ->
            thaiVoice = st == TextToSpeech.SUCCESS && (tts?.setLanguage(Locale("th", "TH")) ?: -2) >= 0
            runOnUiThread { refresh() }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        status.text = (if (ov) "✅ อนุญาตแสดงทับแอปอื่นแล้ว" else "❌ ยังไม่อนุญาตแสดงทับแอปอื่น (กดข้อ 1 ด้านล่าง)") + "\n" +
            when (thaiVoice) { true -> "✅ มีเสียงพูดภาษาไทย"; false -> "❌ ยังไม่มีเสียงพูดภาษาไทย (กดข้อ 2 ด้านล่าง)"; null -> "… กำลังตรวจเสียงพูด" } + "\n" +
            (if (OverlayService.running) "🟢 กำลังทำงาน — สลับไปเล่นเกมได้เลย" else "⚪ ยังไม่ได้เริ่ม")
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "อนุญาตให้แสดงทับแอปอื่นก่อน แล้วกลับมากดเริ่มอีกครั้ง", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (OverlayService.running) { Toast.makeText(this, "กำลังทำงานอยู่แล้ว", Toast.LENGTH_SHORT).show(); return }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "ต้องอนุญาตการบันทึกหน้าจอ แอปจึงจะอ่านข้อความในเกมได้", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data)
        startForegroundService(i)
        Toast.makeText(this, "เริ่มแล้ว! สลับไปเปิดเกมได้เลย", Toast.LENGTH_LONG).show()
        status.postDelayed({ refresh() }, 800)
        moveTaskToBack(true)
    }

    private fun stop() {
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        status.postDelayed({ refresh() }, 500)
    }

    private fun openTtsSettings() {
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) {
            try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) {}
        }
        Toast.makeText(this, "เลือก Speech Services by Google → ติดตั้งเสียงภาษาไทย", Toast.LENGTH_LONG).show()
    }

    private fun downloadOffline() {
        val lang = Prefs.get(this).getString(Prefs.LANG, "ja")!!
        Toast.makeText(this, "กำลังดาวน์โหลดตัวแปลออฟไลน์ ($lang → ไทย)...", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try { ThaiTranslator(this).offlineReady(lang, wifiOnly = false); "ดาวน์โหลดตัวแปลออฟไลน์เสร็จแล้ว ✅" }
            catch (e: Exception) { "ดาวน์โหลดไม่สำเร็จ: ${e.message}" }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    // ------------------------------------------------------------------ tiny UI helpers
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(t); return t
    }

    private fun label(s: String) = text(s, 14f, Color.LTGRAY)

    private fun section(s: String) {
        val t = text(s, 17f, green, bold = true)
        (t.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18)
    }

    private fun button(s: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = s; setTextColor(Color.WHITE); textSize = 16f; isAllCaps = false
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun switch(s: String, key: String, def: Boolean) {
        val p = Prefs.get(this)
        root.addView(Switch(this).apply {
            text = s; textSize = 15f; setTextColor(Color.WHITE)
            isChecked = p.getBoolean(key, def)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply() }
        })
    }

    private fun spinner(title: String, key: String, items: List<Pair<String, String>>, def: String, onPick: ((String) -> Unit)? = null) {
        val p = Prefs.get(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(8).toFloat() }
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        box.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 15f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        val sp = Spinner(this)
        val ad = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items.map { it.second }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE); textSize = 15f }
        }
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        val cur = p.getString(key, def)
        sp.setSelection(items.indexOfFirst { it.first == cur }.coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val v = items[pos].first
                p.edit().putString(key, v).apply()
                onPick?.invoke(v)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        box.addView(sp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
}
