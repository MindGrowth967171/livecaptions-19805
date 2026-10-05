package com.example.livecaptions

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private val langs = listOf("en", "es", "fr", "de", "ru", "hi", "zh")
    private lateinit var src: Spinner
    private lateinit var tgt: Spinner

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val names = langs.map { Locale(it).displayLanguage }
        fun spinner() = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
        }
        src = spinner(); tgt = spinner().apply { setSelection(1) }
        val start = Button(this).apply { text = "Start captions"; setOnClickListener { begin() } }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, CaptionService::class.java)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48); gravity = Gravity.TOP
            addView(TextView(context).apply { text = "Language spoken in the video"; textSize = 16f })
            addView(src)
            addView(TextView(context).apply { text = "Show captions in"; textSize = 16f })
            addView(tgt); addView(start); addView(stop)
        })
    }

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("Allow 'Display over other apps', then tap Start again"); return
        }
        val need = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), 2)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); toast("Tap Start again")
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 2 && res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptionService::class.java)
                .putExtra("rc", res).putExtra("data", data)
                .putExtra("src", langs[src.selectedItemPosition])
                .putExtra("tgt", langs[tgt.selectedItemPosition]))
            moveTaskToBack(true) // go back to your video
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
