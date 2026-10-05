package com.example.livecaptions

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

class CaptionService : Service() {
    private val models = mapOf(
        "en" to "vosk-model-small-en-us-0.15", "es" to "vosk-model-small-es-0.42",
        "fr" to "vosk-model-small-fr-0.22", "de" to "vosk-model-small-de-0.15",
        "ru" to "vosk-model-small-ru-0.22", "hi" to "vosk-model-small-hi-0.22",
        "zh" to "vosk-model-small-cn-0.22"
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ui = Handler(Looper.getMainLooper())
    private val clear = Runnable { tv.text = "" }
    private lateinit var tv: TextView
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "Captions", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, CaptionService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        if (intent.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val n = NotificationCompat.Builder(this, "cap")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Live captions running")
            .addAction(0, "Stop", stopPi).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("data")!!
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(intent.getIntExtra("rc", 0), data).apply {
            registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)
        }
        addOverlay()
        scope.launch { startCaptions(intent.getStringExtra("src")!!, intent.getStringExtra("tgt")!!) }
        return START_NOT_STICKY
    }

    private fun addOverlay() {
        tv = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xAA000000.toInt())
            textSize = 18f; gravity = Gravity.CENTER; setPadding(24, 12, 24, 12)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = 120 }
        getSystemService(WindowManager::class.java).addView(tv, lp)
    }

    private fun show(s: String, hold: Long = 6000) = ui.post {
        ui.removeCallbacks(clear); tv.text = s; ui.postDelayed(clear, hold)
    }

    private suspend fun startCaptions(src: String, tgt: String) {
        try {
            show("Preparing speech model…", 60000)
            val rec = Recognizer(loadModel(src), 16000f)
            val translator = if (src != tgt) Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
            ).also { show("Preparing translation…", 60000); it.downloadModelIfNeeded().await() } else null

            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val r = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(32000)
                .setAudioPlaybackCaptureConfig(cfg).build()
            record = r; r.startRecording(); show("Listening…", 3000)

            val buf = ByteArray(4096)
            while (currentCoroutineContext().isActive) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0 || !rec.acceptWaveForm(buf, n)) continue
                val text = JSONObject(rec.result).optString("text")
                if (text.isBlank()) continue
                show(translator?.translate(text)?.await() ?: text)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { show("Error: ${e.message}", 15000) }
    }

    private fun loadModel(lang: String): Model {
        val name = models[lang]!!
        val root = File(filesDir, "models")
        val dir = File(root, name)
        if (!dir.exists()) {
            val zip = File(cacheDir, "$name.zip")
            URL("https://alphacephei.com/vosk/models/$name.zip").openStream().use { i ->
                zip.outputStream().use { i.copyTo(it) } }
            ZipInputStream(zip.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val f = File(root, e.name)
                    if (!f.canonicalPath.startsWith(root.canonicalPath)) throw SecurityException("bad zip")
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    e = z.nextEntry
                }
            }
            zip.delete()
        }
        return Model(dir.absolutePath)
    }

    override fun onDestroy() {
        scope.cancel()
        record?.run { stop(); release() }
        projection?.stop()
        if (::tv.isInitialized) getSystemService(WindowManager::class.java).removeView(tv)
        super.onDestroy()
    }
}
