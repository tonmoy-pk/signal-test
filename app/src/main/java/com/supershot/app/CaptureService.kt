package com.supershot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.supershot.app.engine.Analysis
import com.supershot.app.engine.ChartReader
import com.supershot.app.engine.Dir
import com.supershot.app.engine.LogModel
import com.supershot.app.engine.EntrySnap
import com.supershot.app.engine.Resolver
import com.supershot.app.engine.Sig
import com.supershot.app.engine.SignalEngine
import kotlin.math.abs

/**
 * Super Shot-এর মূল সার্ভিস:
 * - স্ক্রিন ক্যাপচার + ভাসমান বাবল
 * - প্রতি ক্যান্ডেলের শেষের আগে (ডিফল্ট ১০ সেকেন্ড) ছবি নিয়ে বিশ্লেষণ
 * - পরের মিনিটের বিশ্লেষণ-ছবিতেই আগের সিগন্যালের ফলাফল ঠিক হয়:
 *   এন্ট্রির সময়ের দাম বনাম ঠিক এক টাইমফ্রেম পরের দাম (আসল ১ মিনিটের ট্রেডের মতো)
 */
class CaptureService : Service() {

    companion object {
        @Volatile
        var running = false

        private const val CHANNEL_ID = "super_shot_channel"
        private const val NOTIF_ID = 1
        private const val KIND_ANALYZE = 1

        private const val C_IDLE = 0xE6263238.toInt()
        private const val C_UP = 0xFF00E676.toInt()
        private const val C_DOWN = 0xFFFF1744.toInt()
        private const val C_WAIT = 0xFF546E7A.toInt()
    }

    private class Req(val kind: Int, val cycle: Long, val manual: Boolean)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null

    private lateinit var log: SignalLog

    private val queue = ArrayDeque<Req>()
    @Volatile
    private var current: Req? = null
    @Volatile
    private var wantFrame = false
    private val recent = ArrayList<String>()

    // আগের মিনিটের ছবির তথ্য (শুধু ব্যাকগ্রাউন্ড থ্রেড ছোঁয়)
    private var prevSnap: EntrySnap? = null
    private var prevSigId = 0L
    private var prevSigDir = Dir.UP

    private var lastCycle = -1L
    private var holdUntil = 0L
    private var holdCloseAt = 0L
    private var holdText = ""
    private var holdColor = C_IDLE
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    // ভাষা: প্রতিবার Prefs থেকে দেখে নেওয়া হয়, তাই সার্ভিস চলার সময় ভাষা বদলালেও কাজ করে
    private var trCode = ""
    private var trCtx: Context? = null

    @Synchronized
    private fun tr(id: Int, vararg a: Any): String {
        val code = Prefs.lang(this)
        var c = trCtx
        if (c == null || code != trCode) {
            c = Lang.forCode(applicationContext, code)
            trCtx = c
            trCode = code
        }
        return Lang.format(c, id, *a)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            log = SignalLog(this)
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)

            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopSelf() }
                }
            }
            projectionCallback = cb
            projection?.registerCallback(cb, mainHandler)

            setupCapture()
            showBubble()
            running = true
            mainHandler.postDelayed(tickRunnable, 500)
            showToast(tr(R.string.svc_started))
        } catch (e: Exception) {
            showToast(tr(R.string.svc_error, e.message ?: ""))
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Super Shot", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(tr(R.string.notif_title))
            .setContentText(tr(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // ---------------- ক্যাপচার ----------------

    private fun setupCapture() {
        val dm = DisplayMetrics()
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)

        bgThread = HandlerThread("supershot-worker").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)

        val reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image: Image? = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image != null) {
                try {
                    val req = current
                    if (wantFrame && req != null) {
                        wantFrame = false
                        processFrame(image, req)
                    }
                } catch (e: Exception) {
                    val req = current
                    mainHandler.post { if (req != null) onFrameFailed(req) }
                } finally {
                    image.close()
                }
            }
        }, bgHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "super-shot-capture",
            dm.widthPixels,
            dm.heightPixels,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val wide = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        wide.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return wide
        val cropped = Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
        wide.recycle()
        return cropped
    }

    /** ব্যাকগ্রাউন্ড থ্রেডে চলে */
    private fun processFrame(image: Image, req: Req) {
        val bmp = imageToBitmap(image)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val reading = ChartReader.read(px, w, h, com.supershot.app.engine.Profile.forPlatform(Prefs.platform(this)))
        val an = SignalEngine.analyze(reading, Prefs.minSetups(this))
        val ts = System.currentTimeMillis()
        val tf = Prefs.tfMin(this)
        var icon: String? = null
        var voidNote = ""

        if (!req.manual) {
            // ১) আগের মিনিটের ছবির সাথে মিলিয়ে দামের চলন ও আগের সিগন্যালের ফলাফল
            val snap = prevSnap
            if (snap != null) {
                val period = tf * 60_000L
                val onTime = snap.cycle == req.cycle - 1 && Math.abs((ts - snap.tsMs) - period) <= 4000
                var mv = LogModel.VOID
                var note = "timing mismatch"
                if (onTime) {
                    val r = Resolver.move(snap, reading)
                    mv = r.first
                    note = r.second
                }
                if (mv != LogModel.VOID) log.addMove(ts, mv, tf)
                if (prevSigId != 0L) {
                    val res = Resolver.outcome(prevSigDir, mv)
                    log.setResult(prevSigId, res)
                    icon = when (res) {
                        LogModel.WIN -> "✅"
                        LogModel.LOSS -> "❌"
                        LogModel.TIE -> "➖"
                        else -> "❔"
                    }
                    if (res == LogModel.VOID) voidNote = "void: $note"
                }
            }
            prevSnap = null
            prevSigId = 0L

            // ২) এই ছবির তথ্য রাখি, পরের মিনিটে কাজে লাগবে
            val snapNow = Resolver.snapshot(reading, req.cycle, ts)
            prevSnap = snapNow
            val dirNow = an.signal
            if (dirNow != null) {
                val ids = an.setups.filter { it.dir == dirNow }.map { it.id }
                log.addSignal(Sig(ts, ts, dirNow.name, ids, an.candleCount, tf, Prefs.payout(this), LogModel.PENDING))
                if (snapNow != null) {
                    prevSigId = ts
                    prevSigDir = dirNow
                } else {
                    log.setResult(ts, LogModel.VOID)
                }
            } else {
                log.addSkip(ts, an.reason)
            }
        }

        var hist = ""
        val dir = an.signal
        if (dir != null) {
            val primary = an.setups.first { it.dir == dir }.id
            val t = LogModel.tally(log.load().signals.filter { primary in it.setups })
            hist = if (t.n >= 20) tr(R.string.hist_rate, Math.round(t.rate * 100), t.n) else tr(R.string.hist_few, t.n)
        }
        var tag = if (req.manual) "manual" else "auto"
        if (voidNote.isNotEmpty()) tag += " | $voidNote"
        if (req.manual || Prefs.debug(this) || voidNote.isNotEmpty()) {
            val category = if (req.manual) "manual" else if (voidNote.isNotEmpty()) "void" else "auto"
            DebugPainter.save(this, bmp, reading, an, tag, category)
        }
        bmp.recycle()
        mainHandler.post { onAnalyzed(req, an, hist, icon) }
    }

    // ---------------- সময়সূচি ----------------

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            try {
                onTick()
            } catch (e: Exception) {
                // এক টিকের ভুলে পুরো সার্ভিস বন্ধ হবে না
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private fun clockNow(): Long = System.currentTimeMillis() + Prefs.offsetMs(this)

    private fun onTick() {
        val period = Prefs.tfMin(this) * 60_000L
        val now = clockNow()
        val remaining = period - (now % period)
        val cycle = now / period
        val leadMs = Prefs.leadSec(this) * 1000L

        if (remaining <= leadMs + 600 && remaining > 1500 && cycle != lastCycle) {
            lastCycle = cycle
            enqueue(Req(KIND_ANALYZE, cycle, false))
        }

        refreshBubble(remaining)
    }

    private fun enqueue(r: Req) {
        queue.addLast(r)
        pump()
    }

    private fun pump() {
        if (current != null || queue.isEmpty() || destroyed) return
        val r = queue.removeFirst()
        current = r
        bubble?.visibility = View.INVISIBLE // বাবল যেন ছবিতে না আসে
        mainHandler.postDelayed({
            if (destroyed || current !== r) return@postDelayed
            wantFrame = true
            mainHandler.postDelayed({
                if (!destroyed && current === r && wantFrame) {
                    wantFrame = false
                    onFrameFailed(r)
                }
            }, 2500)
        }, 350)
    }

    private fun finishCurrent() {
        current = null
        bubble?.visibility = View.VISIBLE
        pump()
    }

    private fun onFrameFailed(req: Req) {
        if (!req.manual) {
            log.addSkip(System.currentTimeMillis(), "NOFRAME")
        }
        finishCurrent()
    }

    // ---------------- ফলাফল হাতে আসার পর (মেইন থ্রেড) ----------------

    private fun reasonText(reason: String): String = when (reason) {
        "NO_SETUP" -> tr(R.string.reason_no_setup)
        "CONFLICT" -> tr(R.string.reason_conflict)
        "NEED_MORE" -> tr(R.string.reason_need_more)
        "FEW_CANDLES" -> tr(R.string.reason_few_candles)
        "HIDDEN_CANDLE" -> tr(R.string.reason_hidden_candle)
        "NO_CHART" -> tr(R.string.reason_no_chart)
        "FLAT_MARKET" -> tr(R.string.reason_flat_market)
        else -> reason
    }

    private fun onAnalyzed(req: Req, an: Analysis, hist: String, icon: String?) {
        val period = Prefs.tfMin(this) * 60_000L
        val cycleEnd = if (req.manual) (clockNow() / period + 1) * period else (req.cycle + 1) * period
        val dir = an.signal

        if (icon != null) {
            recent.add(icon)
            while (recent.size > 5) recent.removeAt(0)
        }

        if (dir != null) {
            val ids = an.setups.filter { it.dir == dir }.map { it.id }
            val arrow = if (dir == Dir.UP) "▲ UP" else "▼ DOWN"
            holdText = arrow + (if (ids.size > 1) "  ×${ids.size}" else "") + "\n" +
                ids.joinToString("+") + "\n" + hist + (if (req.manual) tr(R.string.manual_signal_note) else "")
            holdColor = if (dir == Dir.UP) C_UP else C_DOWN
        } else {
            holdText = "⏸ WAIT\n" + reasonText(an.reason) + (if (req.manual) tr(R.string.manual_wait_note) else "")
            holdColor = C_WAIT
        }
        holdCloseAt = cycleEnd
        holdUntil = cycleEnd + 3000
        finishCurrent()
        refreshBubble(cycleEnd - clockNow())
    }

    // ---------------- বাবল ----------------

    private fun setBubble(text: String, color: Int) {
        val b = bubble ?: return
        if (b.text.toString() != text) b.text = text
        (b.background as? GradientDrawable)?.setColor(color)
    }

    private fun refreshBubble(remainingMs: Long) {
        if (bubble == null || current != null) return
        val now = clockNow()
        if (now < holdUntil) {
            val toClose = holdCloseAt - now
            val tail = if (toClose > 0) "⏱ ${(toClose + 999) / 1000}s" else tr(R.string.bubble_running)
            setBubble(holdText + "\n" + tail, holdColor)
        } else {
            val sec = (remainingMs + 999) / 1000
            val last = if (recent.isEmpty()) "" else "\n" + recent.joinToString("")
            setBubble("Super Shot\n⏱ ${sec}s$last", C_IDLE)
        }
    }

    private fun showBubble() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val tv = TextView(this).apply {
            text = "Super Shot"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            val d = (8 * resources.displayMetrics.density).toInt()
            setPadding(d * 2, d, d * 2, d)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(C_IDLE)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300 // চার্টের ওপরের সারিতে; দরকারে টেনে সরাও
        }

        tv.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false
            private var downTime = 0L

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        downTime = e.eventTime
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        if (moved) {
                            params.x = startX + dx
                            params.y = startY + dy
                            wm.updateViewLayout(v, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            if (e.eventTime - downTime > 800) {
                                stopSelf()
                            } else if (current == null && queue.isEmpty()) {
                                val period = Prefs.tfMin(this@CaptureService) * 60_000L
                                enqueue(Req(KIND_ANALYZE, clockNow() / period, true))
                            }
                        }
                    }
                }
                return true
            }
        })

        wm.addView(tv, params)
        bubble = tv
    }

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------------- বন্ধ করা ----------------

    override fun onDestroy() {
        destroyed = true
        running = false
        wantFrame = false
        mainHandler.removeCallbacksAndMessages(null)

        try {
            bubble?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
        }
        bubble = null
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        try {
            imageReader?.close()
        } catch (e: Exception) {
        }
        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
            projection?.stop()
        } catch (e: Exception) {
        }
        bgThread?.quitSafely()
        super.onDestroy()
    }
}
