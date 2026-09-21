package com.example.signaltest

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import com.example.signaltest.engine.Analysis
import com.example.signaltest.engine.ChartReading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** শনাক্ত ক্যান্ডেলের বাক্স আঁকা ছবি সেভ করে, যাতে নিজের চোখে যাচাই করা যায় */
object DebugPainter {

    fun save(ctx: Context, src: Bitmap, reading: ChartReading, an: Analysis?, tag: String): Boolean {
        return try {
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            val cv = Canvas(bmp)
            val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
            val line = Paint().apply { color = Color.WHITE; strokeWidth = 2f }
            val txt = Paint().apply { color = Color.YELLOW; textSize = 34f; isAntiAlias = true }

            box.color = Color.YELLOW
            cv.drawRect(0f, reading.roiTop.toFloat(), bmp.width - 1f, reading.roiBottom.toFloat(), box)

            for (c in reading.allCandles) {
                val inWin = reading.candles.contains(c)
                box.color = if (c.cut) Color.rgb(255, 165, 0) else if (inWin) Color.CYAN else Color.MAGENTA
                cv.drawRect(c.x0 - 1f, c.yHigh.toFloat(), c.x1.toFloat(), c.yLow.toFloat(), box)
                cv.drawLine(c.x0 - 3f, c.yBodyTop.toFloat(), c.x1 + 3f, c.yBodyTop.toFloat(), line)
                cv.drawLine(c.x0 - 3f, c.yBodyBottom.toFloat(), c.x1 + 3f, c.yBodyBottom.toFloat(), line)
            }
            for (c in reading.candles) {
                if (c.synthetic) {
                    box.color = Color.RED
                    cv.drawRect(c.x0.toFloat(), c.yBodyTop - 6f, c.x1.toFloat(), c.yBodyTop + 6f, box)
                }
            }
            if (reading.priceLineY >= 0) {
                box.color = Color.YELLOW
                cv.drawLine(0f, reading.priceLineY.toFloat(), bmp.width.toFloat(), reading.priceLineY.toFloat(), box)
            }

            var y = reading.roiBottom + 44f
            cv.drawText("$tag | candles=${reading.candles.size}/${reading.allCandles.size} bw=${reading.bodyWidth} pitch=${reading.pitch} filled=${reading.gaps}", 16f, y, txt)
            if (an != null) {
                y += 40f
                val sig = an.signal?.name ?: "WAIT"
                cv.drawText("signal=$sig reason=${an.reason} setups=${an.setups.joinToString(",") { it.id }}", 16f, y, txt)
            }

            val name = "dbg_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SuperShot")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            var ok = false
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    ok = bmp.compress(Bitmap.CompressFormat.JPEG, 70, os)
                }
            }
            bmp.recycle()
            ok
        } catch (e: Exception) {
            false
        }
    }
}
