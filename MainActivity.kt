package com.example.towerbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val req = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        fun text(s: String) = TextView(this).apply { this.text = s; textSize = 15f; setPadding(0, 16, 0, 16) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s; setOnClickListener { onClick() }
        }
        fun seek(label: String, key: String, min: Int, max: Int, def: Int) {
            val tv = TextView(this)
            val sb = SeekBar(this)
            val cur = prefs.getInt(key, def)
            tv.text = "$label: $cur"
            sb.max = max - min
            sb.progress = cur - min
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    prefs.edit().putInt(key, p + min).apply()
                    tv.text = "$label: ${p + min}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            root.addView(tv); root.addView(sb)
        }

        root.addView(text("TowerBot: автоматически ставит блоки в игре «Башня»."))
        root.addView(button("1. Разрешить показ поверх других приложений") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("2. Включить службу «TowerBot (нажатия)»") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(text("Если пункт серый: Настройки → Приложения → TowerBot → ⋮ → «Разрешить ограниченные настройки», потом повторите шаг 2."))
        root.addView(button("3. Запустить (появится кнопка СТАРТ)") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала шаг 1", Toast.LENGTH_LONG).show()
            } else {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(mpm.createScreenCaptureIntent(), req)
            }
        })
        root.addView(text("Настройка точности. Если блок ставится правее/левее центра, меняйте сдвиг. Если нажимает слишком рано/поздно, меняйте опережение."))
        seek("Опережение, мс", "lead", 0, 400, 120)
        seek("Сдвиг по X, px", "off", -80, 80, 0)
        root.addView(text("Откройте игру, нажмите плавающую кнопку СТАРТ. Долгое нажатие на кнопку закрывает программу."))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == req && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            Toast.makeText(this, "Откройте игру и нажмите СТАРТ", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }
}
