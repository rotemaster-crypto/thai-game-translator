package com.rotemaster.thaigame

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Prefs {
    const val LANG = "lang"            // ja | en | zh | ko
    const val ENGINE = "engine"        // google | mlkit | claude
    const val CLAUDE_KEY = "claude_key"
    const val CLAUDE_MODEL = "claude_model"
    const val AREA = "area"            // 0 full, 1 bottom half, 2 bottom third
    const val VOICE = "voice"
    const val RATE = "rate"            // speech rate x10
    const val AUTO = "auto"
    const val INTERVAL = "interval"    // ms
    const val SUB_SIZE = "sub_size"    // sp

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
}

data class Block(val box: Rect, val text: String)

fun isCjk(s: String) = s.any { it in '぀'..'ヿ' || it in '㐀'..'鿿' || it in '가'..'힯' }

/** On-device text recognition (Google ML Kit, works offline). */
class Ocr(lang: String) {
    private val recognizer: TextRecognizer = when (lang) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Must be called off the main thread. */
    fun read(bmp: Bitmap): List<Block> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 15, TimeUnit.SECONDS)
        val out = ArrayList<Block>()
        for (b in result.textBlocks) {
            val box = b.boundingBox ?: continue
            val sb = StringBuilder()
            for (line in b.lines) {
                val t = line.text.trim()
                if (t.isEmpty()) continue
                if (sb.isNotEmpty() && !(isCjk(sb.takeLast(1).toString()) && isCjk(t.take(1)))) sb.append(' ')
                sb.append(t)
            }
            val text = sb.toString().trim()
            val letters = text.count { it.isLetter() }
            if (letters < 2) continue                     // HUD numbers, icons, noise
            if (box.height() < 12 && letters < 4) continue
            out.add(Block(box, text))
        }
        return out.sortedWith(compareBy({ it.box.top / 24 }, { it.box.left }))
    }

    fun close() = recognizer.close()
}

/** Translation with cache. google = free online, mlkit = offline on-device, claude = AI (API key). */
class ThaiTranslator(private val ctx: Context) {
    private val cacheFile = File(ctx.filesDir, "tr_cache.json")
    private val cache: JSONObject = try { JSONObject(cacheFile.readText()) } catch (e: Exception) { JSONObject() }
    private var mlkit: com.google.mlkit.nl.translate.Translator? = null
    private var mlkitSrc = ""

    fun translateAll(texts: List<String>, src: String, engine: String, log: (String) -> Unit): List<String> {
        val result = MutableList(texts.size) { "" }
        val todo = ArrayList<Int>()
        texts.forEachIndexed { i, t ->
            val k = "$engine|$src|$t"
            if (cache.has(k)) result[i] = cache.getString(k) else todo.add(i)
        }
        if (todo.isEmpty()) return result
        val fresh: List<String> = try {
            when (engine) {
                "claude" -> claude(todo.map { texts[it] })
                "mlkit" -> todo.map { offline(texts[it], src) }
                else -> todo.map { google(texts[it], src) }
            }
        } catch (e: Exception) {
            log("แปลด้วย $engine ไม่สำเร็จ (${e.message}) → ใช้แปลออฟไลน์แทน")
            todo.map { try { offline(texts[it], src) } catch (e2: Exception) { texts[it] } }
        }
        todo.forEachIndexed { j, i ->
            result[i] = fresh[j]
            cache.put("$engine|$src|${texts[i]}", fresh[j])
        }
        try { cacheFile.writeText(cache.toString()) } catch (_: Exception) {}
        return result
    }

    private fun google(text: String, src: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$src&tl=th&dt=t&q=$q")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val parts = JSONArray(body).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONArray(i) ?: continue
            sb.append(p.optString(0, ""))
        }
        return sb.toString().trim()
    }

    fun offlineReady(src: String, wifiOnly: Boolean): Boolean {
        val tr = mlkitClient(src)
        val cond = if (wifiOnly) DownloadConditions.Builder().requireWifi().build() else DownloadConditions.Builder().build()
        Tasks.await(tr.downloadModelIfNeeded(cond), 10, TimeUnit.MINUTES)
        return true
    }

    private fun mlkitClient(src: String): com.google.mlkit.nl.translate.Translator {
        if (mlkit == null || mlkitSrc != src) {
            mlkit?.close()
            val from = TranslateLanguage.fromLanguageTag(src) ?: TranslateLanguage.JAPANESE
            mlkit = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(TranslateLanguage.THAI).build()
            )
            mlkitSrc = src
        }
        return mlkit!!
    }

    private fun offline(text: String, src: String): String {
        val tr = mlkitClient(src)
        Tasks.await(tr.downloadModelIfNeeded(), 10, TimeUnit.MINUTES)
        return Tasks.await(tr.translate(text), 30, TimeUnit.SECONDS)
    }

    private fun claude(texts: List<String>): List<String> {
        val p = Prefs.get(ctx)
        val key = p.getString(Prefs.CLAUDE_KEY, "")!!.trim()
        if (key.isEmpty()) throw IllegalStateException("ยังไม่ได้ใส่ Claude API key")
        val prompt = "You translate retro video game text into natural, casual Thai for players. " +
            "Keep names consistent. Return ONLY a JSON array of Thai strings, same order and length as the input.\n" +
            JSONArray(texts).toString()
        val req = JSONObject()
            .put("model", p.getString(Prefs.CLAUDE_MODEL, "claude-haiku-5-5"))
            .put("max_tokens", 1500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 10000; c.readTimeout = 60000
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
        if (c.responseCode != 200) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IllegalStateException("Claude ${c.responseCode}: ${err.take(120)}")
        }
        val resp = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val content = resp.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) sb.append(content.getJSONObject(i).optString("text", ""))
        val txt = sb.toString()
        val arr = JSONArray(txt.substring(txt.indexOf('['), txt.lastIndexOf(']') + 1))
        if (arr.length() != texts.size) throw IllegalStateException("Claude ตอบจำนวนไม่ตรง")
        return List(arr.length()) { arr.getString(it) }
    }
}

/** Similarity 0..1 (normalized Levenshtein) — ignores small OCR jitter between frames. */
fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val x = a.take(300); val y = b.take(300)
    var prev = IntArray(y.length + 1) { it }
    for (i in 1..x.length) {
        val cur = IntArray(y.length + 1)
        cur[0] = i
        for (j in 1..y.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return 1.0 - prev[y.length].toDouble() / maxOf(x.length, y.length)
}
