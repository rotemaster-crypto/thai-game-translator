package com.rotemaster.thaigame

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Prefs {
    const val LANG = "lang"            // ja | en | zh | ko
    const val ENGINE = "engine"        // claude_vision | claude | google | mlkit
    const val CLAUDE_KEY = "claude_key"
    const val CLAUDE_MODEL = "claude_model"
    const val GAME = "game"            // game title, gives the AI context
    const val AREA = "area"            // 0 full, 1 bottom half, 2 bottom third
    const val VOICE = "voice"
    const val VOICE_SRC = "voice_src"  // auto | device | online
    const val RATE = "rate"            // speech rate x10
    const val AUTO = "auto"
    const val INTERVAL = "interval"    // ms
    const val SUB_SIZE = "sub_size"    // sp
    const val DUCK = "duck"            // duck | mute | off  (game sound while Thai voice speaks)
    const val NAMES = "show_names"     // show speaker names in subtitles

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
}

/** One text box found on screen. */
data class Block(val box: Rect, val speaker: String, val text: String)

/** One translated line of dialogue. */
data class Line(val speaker: String, val original: String, var speakerTh: String = "", var thai: String = "")

fun isCjk(s: String) = s.any { it in '぀'..'ヿ' || it in '㐀'..'鿿' || it in '가'..'힯' }

private val SENTENCE_PUNCT = Regex("[.,!?…:;。、！？「」\"]")

/** A short first line without sentence punctuation is almost always the speaker's name. */
fun looksLikeName(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty() || SENTENCE_PUNCT.containsMatchIn(t)) return false
    if (isCjk(t)) return t.length <= 8
    val words = t.split(Regex("\\s+"))
    return t.length <= 22 && words.size <= 3 && t.first().isUpperCase() &&
        t.all { it.isLetter() || it == ' ' || it == '\'' || it == '-' || it == '.' }
}

/** On-device text recognition (Google ML Kit, works offline). Used to find text and to detect new dialogue. */
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
            val lines = b.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            var speaker = ""
            var body = lines
            if (lines.size >= 2 && looksLikeName(lines[0])) { speaker = lines[0]; body = lines.drop(1) }
            val sb = StringBuilder()
            for (t in body) {
                if (sb.isNotEmpty() && !(isCjk(sb.takeLast(1).toString()) && isCjk(t.take(1)))) sb.append(' ')
                sb.append(t)
            }
            val text = sb.toString().trim()
            val letters = text.count { it.isLetter() }
            if (letters < 2) continue                     // HUD numbers, icons, noise
            if (box.height() < 12 && letters < 4) continue
            out.add(Block(box, speaker, text))
        }
        // a lone name-only block directly above a text block belongs to it
        return out.sortedWith(compareBy({ it.box.top / 24 }, { it.box.left }))
    }

    fun close() = recognizer.close()
}

/**
 * Translation. claude_vision = AI reads the screenshot itself (best), claude = AI translates OCR text,
 * google = free online, mlkit = offline on-device. Keeps conversation history + a name glossary for context.
 */
class ThaiTranslator(private val ctx: Context) {
    private val cacheFile = File(ctx.filesDir, "tr_cache.json")
    private val namesFile = File(ctx.filesDir, "names.json")
    private val cache: JSONObject = try { JSONObject(cacheFile.readText()) } catch (e: Exception) { JSONObject() }
    private val names: JSONObject = try { JSONObject(namesFile.readText()) } catch (e: Exception) { JSONObject() }
    private val history = ArrayDeque<Line>()
    private var mlkit: com.google.mlkit.nl.translate.Translator? = null
    private var mlkitSrc = ""

    // ------------------------------------------------------------------ history / de-duplication
    /** Lines not already shown recently (old speech bubbles still on screen are not repeated). */
    fun takeNew(lines: List<Line>): List<Line> {
        val fresh = lines.filter { l -> history.none { similarity(norm(it.original), norm(l.original)) > 0.8 } }
        for (l in fresh) {
            history.addLast(l)
            while (history.size > 12) history.removeFirst()
        }
        return fresh
    }

    fun clearHistory() = history.clear()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun rememberName(src: String, th: String) {
        if (src.isBlank() || th.isBlank() || names.has(src)) return
        if (names.length() > 150) return
        names.put(src, th)
        try { namesFile.writeText(names.toString()) } catch (_: Exception) {}
    }

    private fun saveCache() { try { cacheFile.writeText(cache.toString()) } catch (_: Exception) {} }

    // ------------------------------------------------------------------ text translation (from OCR)
    fun translateLines(lines: List<Line>, src: String, engine: String, log: (String) -> Unit) {
        if (engine == "claude" || engine == "claude_vision") {
            try { claudeText(lines); return } catch (e: Exception) { log("Claude ไม่สำเร็จ (${e.message}) → ใช้ Google แทน") }
        }
        for (l in lines) {
            l.thai = simple(l.original, src, engine, log)
            if (l.speaker.isNotBlank()) {
                l.speakerTh = names.optString(l.speaker, "").ifBlank {
                    simple(l.speaker, src, engine, log).also { rememberName(l.speaker, it) }
                }
            }
        }
        saveCache()
    }

    private fun simple(text: String, src: String, engine: String, log: (String) -> Unit): String {
        val e = if (engine == "mlkit") "mlkit" else "google"
        val k = "$e|$src|$text"
        if (cache.has(k)) return cache.getString(k)
        val out = try {
            if (e == "mlkit") offline(text, src) else google(text, src)
        } catch (ex: Exception) {
            log("แปลออนไลน์ไม่สำเร็จ → ใช้ตัวแปลออฟไลน์")
            try { offline(text, src) } catch (ex2: Exception) { text }
        }
        cache.put(k, out)
        return out
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
        for (i in 0 until parts.length()) sb.append(parts.optJSONArray(i)?.optString(0, "") ?: "")
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

    // ------------------------------------------------------------------ Claude (AI game localizer)
    private val style = """
You are a professional video game localizer translating game dialogue into Thai for Thai players reading live subtitles.
Rules:
- Translate meaning, tone and intent, never word-for-word. It must read like natural Thai dialogue written by a skilled Thai localizer, not machine translation.
- Match the setting and each character's voice. Medieval / fantasy / historical settings: use ข้า, เจ้า, ท่าน, ข้าพเจ้า, กระหม่อม, เพคะ etc. as fits rank and relationship; formal speech for nobles, priests and knights; rough speech for mercenaries and villains. Modern settings: everyday spoken Thai with natural particles (นะ, สิ, ล่ะ, หรอก, เถอะ).
- Proper nouns (people, places, factions, items): transliterate into Thai the way Thai fans would say them. Always reuse the glossary spelling when one is given.
- Keep lines concise enough to read quickly as a subtitle.
- The source text may come from OCR of a pixel font and may contain errors (for example '0' instead of ',', 'v' instead of 'y', split or merged words). Silently infer what was meant.
- Use the recent lines for context (who speaks to whom, honorifics, what is going on).
""".trim()

    private fun context(): String {
        val p = Prefs.get(ctx)
        val sb = StringBuilder()
        val game = p.getString(Prefs.GAME, "")!!.trim()
        if (game.isNotEmpty()) sb.append("Game: ").append(game).append('\n')
        if (names.length() > 0) {
            sb.append("Glossary (source = Thai): ")
            val it = names.keys()
            var first = true
            while (it.hasNext()) { val k = it.next(); if (!first) sb.append(", "); sb.append(k).append(" = ").append(names.getString(k)); first = false }
            sb.append('\n')
        }
        if (history.isNotEmpty()) {
            sb.append("Recent lines (oldest first):\n")
            for (h in history) sb.append("- ").append(if (h.speaker.isNotBlank()) h.speaker + ": " else "").append(h.original).append(" => ").append(h.thai).append('\n')
        }
        return sb.toString()
    }

    private fun claudeText(lines: List<Line>) {
        val arr = JSONArray()
        for (l in lines) arr.put(JSONObject().put("speaker", l.speaker).put("text", l.original))
        val prompt = context() + "\nTranslate these OCR'd lines. Return ONLY a JSON array with one object per input line, same order: " +
            "[{\"speaker_th\":\"Thai speaker name or empty\",\"thai\":\"Thai translation\"}]\nInput:\n" + arr.toString()
        val out = parseArray(claude(JSONArray().put(JSONObject().put("type", "text").put("text", prompt))))
        if (out.length() != lines.size) throw IllegalStateException("จำนวนบรรทัดไม่ตรง")
        for (i in lines.indices) {
            val o = out.getJSONObject(i)
            lines[i].thai = o.optString("thai", lines[i].original)
            lines[i].speakerTh = o.optString("speaker_th", "")
            rememberName(lines[i].speaker, lines[i].speakerTh)
        }
    }

    /** AI reads the screenshot and translates in one step. */
    fun claudeVision(bmp: Bitmap): List<Line> {
        val scale = minOf(1f, 1280f / bmp.width)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val prompt = context() + "\nThis is a screenshot of the game. Read every piece of dialogue or story text currently visible " +
            "in message windows or speech bubbles (ignore menus, HUD numbers, stats and button hints unless they are the only text). " +
            "Join the lines of one box into one sentence. Order: the box the player most likely read first comes first.\n" +
            "Return ONLY a JSON array: [{\"speaker\":\"name shown in the box or empty\",\"original\":\"exact text\"," +
            "\"speaker_th\":\"Thai name or empty\",\"thai\":\"Thai translation\"}]. Return [] if there is no dialogue."
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("type", "text").put("text", prompt))
        val out = parseArray(claude(content))
        val res = ArrayList<Line>()
        for (i in 0 until out.length()) {
            val o = out.optJSONObject(i) ?: continue
            val l = Line(o.optString("speaker", ""), o.optString("original", ""), o.optString("speaker_th", ""), o.optString("thai", ""))
            if (l.thai.isBlank()) continue
            rememberName(l.speaker, l.speakerTh)
            res.add(l)
        }
        return res
    }

    private fun parseArray(txt: String): JSONArray {
        val a = txt.indexOf('['); val b = txt.lastIndexOf(']')
        if (a < 0 || b < a) throw IllegalStateException("AI ตอบรูปแบบไม่ถูกต้อง")
        return JSONArray(txt.substring(a, b + 1))
    }

    fun claude(content: JSONArray, maxTokens: Int = 1200): String {
        val p = Prefs.get(ctx)
        val key = p.getString(Prefs.CLAUDE_KEY, "")!!.trim()
        if (key.isEmpty()) throw IllegalStateException("ยังไม่ได้ใส่ Claude API key")
        val req = JSONObject()
            .put("model", p.getString(Prefs.CLAUDE_MODEL, "claude-haiku-5-5"))
            .put("max_tokens", maxTokens)
            .put("system", style)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 10000; c.readTimeout = 60000
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
        if (c.responseCode != 200) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            val msg = try { JSONObject(err).getJSONObject("error").getString("message") } catch (e: Exception) { err.take(120) }
            throw IllegalStateException("Claude ${c.responseCode}: $msg")
        }
        val resp = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val arr = resp.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.getJSONObject(i).optString("text", ""))
        return sb.toString()
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
