package com.example.signaltest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
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
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "signal_test_channel"
        private const val NOTIF_ID = 1
        private const val BLACK_LIMIT_PERCENT = 85f
        private const val SAMPLE_STEP = 6
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    @Volatile
    private var wantFrame = false
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

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
        } catch (e: Exception) {
            showToast("শুরু করতে সমস্যা: ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Signal Test", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Signal Test চলছে")
            .setContentText("বাবলে ট্যাপ করলে স্ক্রিনশট + বিশ্লেষণ হবে")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupCapture() {
        val dm = DisplayMetrics()
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)

        val width = dm.widthPixels
        val height = dm.heightPixels
        val dpi = dm.densityDpi

        bgThread = HandlerThread("capture-thread").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image: Image? = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image != null) {
                try {
                    if (wantFrame) {
                        wantFrame = false
                        processImage(image)
                    }
                } catch (e: Exception) {
                    postResult("⚠ ত্রুটি: ${e.message}")
                } finally {
                    image.close()
                }
            }
        }, bgHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "signal-test-capture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    // ---------- বাবল ----------

    private fun showBubble() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val tv = TextView(this).apply {
            text = "📸"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            val d = (12 * resources.displayMetrics.density).toInt()
            setPadding(d * 2, d, d * 2, d)
            background = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(0xE61E88E5.toInt())
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
            y = 320
        }
        bubbleParams = params

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
                            } else {
                                requestCapture()
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

    private fun requestCapture() {
        val b = bubble ?: return
        b.text = "…"
        b.visibility = View.INVISIBLE // বাবল যেন ছবিতে না আসে

        mainHandler.postDelayed({
            wantFrame = true
            // ২.৫ সেকেন্ডে ফ্রেম না এলে হাল ছাড়ি
            mainHandler.postDelayed({
                if (wantFrame) {
                    wantFrame = false
                    b.visibility = View.VISIBLE
                    b.text = "ফ্রেম আসেনি, আবার ট্যাপ করো"
                    resetLabelLater()
                }
            }, 2500)
        }, 400)
    }

    private fun postResult(msg: String) {
        mainHandler.post {
            val b = bubble ?: return@post
            b.visibility = View.VISIBLE
            b.text = msg
            resetLabelLater()
        }
    }

    private fun resetLabelLater() {
        mainHandler.postDelayed({
            if (!destroyed) bubble?.text = "📸"
        }, 6000)
    }

    // ---------- ছবি প্রসেসিং ----------

    private fun processImage(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val wide = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        wide.copyPixelsFromBuffer(buffer)
        val bmp: Bitmap
        if (rowPadding == 0) {
            bmp = wide
        } else {
            bmp = Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
            wide.recycle()
        }

        // বিশ্লেষণ: কালো / সবুজ / লাল পিক্সেলের হার
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var total = 0
        var black = 0
        var green = 0
        var red = 0

        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                total++
                if (r < 10 && g < 10 && b < 10) {
                    black++
                } else if (g > r + 40 && g > b + 20) {
                    green++
                } else if (r > g + 60 && r > b + 40) {
                    red++
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        val blackPct = black * 100f / total
        val greenPct = green * 100f / total
        val redPct = red * 100f / total

        val saved = saveToGallery(bmp)
        bmp.recycle()

        val verdict = if (blackPct > BLACK_LIMIT_PERCENT) {
            "⛔ ব্লক সম্ভাবনা"
        } else {
            "✅ ছবি এসেছে"
        }
        val msg = String.format(
            Locale.US,
            "%s\nকালো %.0f%% | সবুজ %.1f%% | লাল %.1f%%\n%s",
            verdict, blackPct, greenPct, redPct,
            if (saved) "সেভ: Pictures/SignalTest" else "সেভ হয়নি"
        )
        postResult(msg)
    }

    private fun saveToGallery(bmp: Bitmap): Boolean {
        return try {
            val name = "chart_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SignalTest")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                false
            } else {
                contentResolver.openOutputStream(uri)?.use { os ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ---------- বন্ধ করা ----------

    override fun onDestroy() {
        destroyed = true
        wantFrame = false
        mainHandler.removeCallbacksAndMessages(null)

        try {
            bubble?.let { windowManager?.removeView(it) }
        } catch (ignored: Exception) {
        }
        bubble = null

        try {
            virtualDisplay?.release()
        } catch (ignored: Exception) {
        }
        try {
            imageReader?.close()
        } catch (ignored: Exception) {
        }
        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
            projection?.stop()
        } catch (ignored: Exception) {
        }
        bgThread?.quitSafely()

        super.onDestroy()
    }
}
