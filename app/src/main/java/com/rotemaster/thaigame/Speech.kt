package com.rotemaster.thaigame

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thai voice. Uses the device's Thai text-to-speech if installed, otherwise a built-in online Thai voice
 * (no extra app needed). mode: auto | device | online.
 */
class Speech(private val ctx: Context, private val attrs: AudioAttributes, private val onDone: () -> Unit,
             private val onError: (String) -> Unit) {

    private var tts: TextToSpeech? = null
    @Volatile var deviceThai = false
        private set
    private val net = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null
    private val generation = AtomicInteger(0)
    private val cacheDir = File(ctx.cacheDir, "voice").also { it.mkdirs() }

    init {
        try {
            tts = TextToSpeech(ctx) { status ->
                deviceThai = status == TextToSpeech.SUCCESS &&
                    (tts?.setLanguage(Locale("th", "TH")) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0
                tts?.setAudioAttributes(attrs)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { onDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { onDone() }
                })
            }
        } catch (_: Exception) { deviceThai = false }
    }

    fun usesDevice(mode: String) = mode == "device" || (mode == "auto" && deviceThai)

    fun speak(text: String, rate: Float, mode: String) {
        stop()
        if (text.isBlank()) return
        if (usesDevice(mode) && deviceThai) {
            tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "line")
            return
        }
        val gen = generation.incrementAndGet()
        net.execute {
            try {
                val files = chunks(text).map { download(it) }
                if (gen == generation.get()) playSequence(files, 0, rate, gen)
            } catch (e: Exception) {
                onError("เสียงออนไลน์ไม่สำเร็จ: ${e.message}")
                onDone()
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        try { tts?.stop() } catch (_: Exception) {}
        synchronized(this) {
            try { player?.stop() } catch (_: Exception) {}
            player?.release(); player = null
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        net.shutdownNow()
    }

    // ------------------------------------------------------------------ online Thai voice
    /** Split into pieces of at most 180 chars, preferring sentence / word boundaries. */
    private fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        var rest = text.replace('\n', ' ').trim()
        while (rest.length > 180) {
            var cut = rest.lastIndexOfAny(charArrayOf(' ', '.', '!', '?', ',', '…'), 180)
            if (cut < 60) cut = 180
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out.filter { it.isNotBlank() }
    }

    private fun download(piece: String): File {
        val md = MessageDigest.getInstance("MD5").digest(piece.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(cacheDir, "$md.mp3")
        if (f.exists() && f.length() > 500) return f
        val q = URLEncoder.encode(piece, "UTF-8")
        val c = URL("https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=th&total=1&idx=0&textlen=${piece.length}&q=$q")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
        c.setRequestProperty("Referer", "https://translate.google.com/")
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        val tmp = File(cacheDir, "$md.part")
        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        if (tmp.length() < 500) throw IllegalStateException("ไฟล์เสียงว่าง")
        tmp.renameTo(f)
        if (cacheDir.listFiles()?.size ?: 0 > 600) cacheDir.listFiles()?.sortedBy { it.lastModified() }?.take(200)?.forEach { it.delete() }
        return f
    }

    private fun playSequence(files: List<File>, i: Int, rate: Float, gen: Int) {
        if (gen != generation.get()) return
        if (i >= files.size) { onDone(); return }
        val mp = MediaPlayer()
        synchronized(this) { player?.release(); player = mp }
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(files[i].absolutePath)
            mp.prepare()
            try { mp.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(0.5f, 2f)) } catch (_: Exception) {}
            mp.setOnCompletionListener { playSequence(files, i + 1, rate, gen) }
            mp.setOnErrorListener { _, _, _ -> onDone(); true }
            mp.start()
        } catch (e: Exception) {
            onError("เล่นเสียงไม่ได้: ${e.message}")
            onDone()
        }
    }
}
