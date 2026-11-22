package com.example.towerbot

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import kotlin.math.abs

class CaptureService : Service() {
    private var proj: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var wm: WindowManager? = null
    private var btn: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var resetFlag = false
    private var sw = 0; private var sh = 0; private var cw = 0; private var ch = 0
    private val hist = ArrayList<DoubleArray>()
    private var cooldownUntil = 0.0
    private var warned = false

    // результат scanRow
    private var rc = 0; private var rmin = 0; private var rmax = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bot", "TowerBot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bot")
            .setContentTitle("TowerBot работает")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra("data", Intent::class.java)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        proj = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        proj!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)

        wm = getSystemService(WindowManager::class.java)
        val b = wm!!.maximumWindowMetrics.bounds
        sw = b.width(); sh = b.height(); cw = sw / 2; ch = sh / 2

        thread = HandlerThread("cap").also { it.start() }
        val h = Handler(thread!!.looper)
        reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        vd = proj!!.createVirtualDisplay(
            "tb", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, h
        )
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { if (running) process(img) else hist.clear() } catch (e: Exception) { Log.e("TB", "err", e) } finally { img.close() }
        }, h)

        addButton()
        return START_NOT_STICKY
    }

    private fun addButton() {
        val t = TextView(this).apply {
            text = "СТАРТ"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(44, 22, 44, 22)
            background = GradientDrawable().apply { cornerRadius = 80f; setColor(0xFF2E7D32.toInt()) }
            setOnClickListener { toggle() }
            setOnLongClickListener { stopSelf(); true }
        }
        btn = t
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (sw * 0.16).toInt(); y = (sh * 0.045).toInt() }
        wm!!.addView(t, lp)
    }

    private fun toggle() {
        running = !running
        resetFlag = true
        btn?.text = if (running) "СТОП" else "СТАРТ"
        (btn?.background as? GradientDrawable)?.setColor(if (running) 0xFFC62828.toInt() else 0xFF2E7D32.toInt())
        if (running && TapService.inst == null)
            Toast.makeText(this, "Включите службу специальных возможностей TowerBot!", Toast.LENGTH_LONG).show()
    }

    // Считает «тёмно-синие» пиксели (контур блоков) в строке y.
    private fun scanRow(buf: ByteBuffer, rs: Int, y: Int) {
        var c = 0; var mn = Int.MAX_VALUE; var mx = -1
        val base = y * rs
        var x = 0
        while (x < cw) {
            val o = base + x * 4
            val r = buf.get(o).toInt() and 255
            if (r < 85) {
                val g = buf.get(o + 1).toInt() and 255
                val b = buf.get(o + 2).toInt() and 255
                if (g < 95 && b in 50..160 && b > r + 20) { c++; if (x < mn) mn = x; if (x > mx) mx = x }
            }
            x += 2
        }
        rc = c; rmin = mn; rmax = mx
    }
    private fun rowOk() = rc >= 5 && rmax - rmin >= 60

    private fun process(img: Image) {
        if (resetFlag) { hist.clear(); resetFlag = false }
        val now = System.nanoTime() / 1e6
        if (now < cooldownUntil) return
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride

        val yMin = (ch * 0.33).toInt()
        val yMax = (ch * 0.93).toInt().coerceAtMost(ch - 1)

        // 1) верх башни: идём снизу вверх, пока есть непрерывные «строки блоков»
        var top = -1; var gap = 0
        var y = yMax
        while (y >= yMin) {
            scanRow(buf, rs, y)
            if (rowOk()) { top = y; gap = 0 } else if (top >= 0) { gap++; if (gap > 40) break }
            y--
        }
        if (top < 0) { hist.clear(); return }

        // центр верхнего блока башни (медиана)
        val cs = ArrayList<Int>()
        for (yy in top..(top + 55).coerceAtMost(ch - 1)) {
            scanRow(buf, rs, yy)
            if (rowOk()) cs.add((rmin + rmax) / 2)
        }
        if (cs.size < 10) return
        cs.sort()
        val tx = cs[cs.size / 2]

        // 2) блок на кране: выше башни
        val hs = ArrayList<Int>()
        y = yMin
        while (y < top - 30) {
            scanRow(buf, rs, y)
            if (rowOk()) hs.add((rmin + rmax) / 2)
            y++
        }
        if (hs.size < 25) { hist.clear(); return }
        hs.sort()
        val bx = hs[hs.size / 2]

        // 3) история положений
        if (hist.isNotEmpty() && (now - hist.last()[0] > 150 || abs(bx - hist.last()[1]) > 60)) hist.clear()
        hist.add(doubleArrayOf(now, bx.toDouble()))
        while (hist.size > 8) hist.removeAt(0)
        if (hist.size < 5) return

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val lead = prefs.getInt("lead", 120)
        val target = tx + prefs.getInt("off", 0) / 2.0

        // 4) квадратичная аппроксимация x(τ)=a+bτ+cτ², τ = t - now
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in hist) {
            val tau = p[0] - now; val xv = p[1]
            val tt = tau * tau
            s0 += 1; s1 += tau; s2 += tt; s3 += tt * tau; s4 += tt * tt
            t0 += xv; t1 += xv * tau; t2 += xv * tt
        }
        fun det(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double, g: Double, h: Double, i: Double) =
            a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        val d = det(s0, s1, s2, s1, s2, s3, s2, s3, s4)
        if (abs(d) < 1e-9) return
        val a = det(t0, s1, s2, t1, s2, s3, t2, s3, s4) / d
        val b = det(s0, t0, s2, s1, t1, s3, s2, t2, s4) / d
        val c = det(s0, s1, t0, s1, s2, t1, s2, s3, t2) / d

        val k = a - target
        var tau = Double.NaN
        if (abs(c) < 1e-7) {
            if (abs(b) > 1e-6) tau = -k / b
        } else {
            val disc = b * b - 4 * c * k
            if (disc >= 0) {
                val sq = Math.sqrt(disc)
                val r1 = (-b - sq) / (2 * c); val r2 = (-b + sq) / (2 * c)
                tau = listOf(r1, r2).filter { it >= -15.0 }.minOrNull() ?: Double.NaN
            }
        }
        if (!tau.isNaN() && tau >= -15.0 && tau <= lead) {
            val svc = TapService.inst
            if (svc == null) {
                if (!warned) { warned = true; main.post { Toast.makeText(this, "Служба нажатий не включена", Toast.LENGTH_LONG).show() } }
            } else {
                svc.tap(sw * 0.5f, sh * 0.75f)
            }
            Log.d("TB", "TAP block=$bx tower=$tx tau=$tau")
            cooldownUntil = now + 1300
            hist.clear()
        }
    }

    override fun onDestroy() {
        running = false
        try { btn?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        try { proj?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
