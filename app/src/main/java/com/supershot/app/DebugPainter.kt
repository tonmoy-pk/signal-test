package com.supershot.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import com.supershot.app.engine.Analysis
import com.supershot.app.engine.ChartReading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** শনাক্ত ক্যান্ডেলের বাক্স আঁকা ছবি সেভ করে, যাতে নিজের চোখে যাচাই করা যায়।
 *  পুরো স্ক্রিনের বদলে শুধু চার্টের অংশ (+মার্জিন) কেটে সেভ হয় — সেশন লম্বা হলে
 *  ফুল-রেজোলিউশন বারবার সেভ করায় ধীরে ধীরে ধীর হয়ে যাচ্ছিল, এখন হালকা ও দ্রুত। */
object DebugPainter {

    fun save(ctx: Context, src: Bitmap, reading: ChartReading, an: Analysis?, tag: String, category: String = "auto"): Boolean {
        return try {
            val top = max(0, reading.roiTop - 20)
            val bottom = min(src.height, reading.roiBottom + 90)
            val cropH = bottom - top
            if (cropH <= 0) return false
            val bmp = Bitmap.createBitmap(src, 0, top, src.width, cropH).copy(Bitmap.Config.ARGB_8888, true)
            val cv = Canvas(bmp)
            fun ay(y: Int) = (y - top).toFloat()

            val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
            val line = Paint().apply { color = Color.WHITE; strokeWidth = 2f }
            val txt = Paint().apply { color = Color.YELLOW; textSize = 34f; isAntiAlias = true }

            box.color = Color.YELLOW
            cv.drawRect(0f, ay(reading.roiTop), bmp.width - 1f, ay(reading.roiBottom), box)

            for (c in reading.allCandles) {
                val inWin = reading.candles.contains(c)
                box.color = if (c.cut) Color.rgb(255, 165, 0) else if (inWin) Color.CYAN else Color.MAGENTA
                cv.drawRect(c.x0 - 1f, ay(c.yHigh), c.x1.toFloat(), ay(c.yLow), box)
                cv.drawLine(c.x0 - 3f, ay(c.yBodyTop), c.x1 + 3f, ay(c.yBodyTop), line)
                cv.drawLine(c.x0 - 3f, ay(c.yBodyBottom), c.x1 + 3f, ay(c.yBodyBottom), line)
            }
            for (c in reading.candles) {
                if (c.synthetic) {
                    box.color = Color.RED
                    cv.drawRect(c.x0.toFloat(), ay(c.yBodyTop) - 6f, c.x1.toFloat(), ay(c.yBodyTop) + 6f, box)
                }
            }
            if (reading.priceLineY >= 0) {
                box.color = Color.YELLOW
                cv.drawLine(0f, ay(reading.priceLineY), bmp.width.toFloat(), ay(reading.priceLineY), box)
            }

            var y = ay(reading.roiBottom) + 44f
            cv.drawText("$tag | candles=${reading.candles.size}/${reading.allCandles.size} bw=${reading.bodyWidth} pitch=${reading.pitch} filled=${reading.gaps}", 16f, y, txt)
            if (an != null) {
                y += 40f
                val sig = an.signal?.name ?: "WAIT"
                cv.drawText("signal=$sig reason=${an.reason} setups=${an.setups.joinToString(",") { it.id }}", 16f, y, txt)
            }

            val name = "dbg_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + category + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SuperShot")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            var ok = false
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    ok = bmp.compress(Bitmap.CompressFormat.JPEG, 72, os)
                }
            }
            bmp.recycle()
            ok
        } catch (e: Exception) {
            false
        }
    }
}
