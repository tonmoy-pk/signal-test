package com.example.signaltest

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.signaltest.engine.LogData
import com.example.signaltest.engine.LogModel
import com.example.signaltest.engine.Sig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ডেমো টেস্টের লগ ফাইল (অ্যাপের ভেতরে থাকে, CSV হিসেবে এক্সপোর্ট করা যায়) */
class SignalLog(private val ctx: Context) {

    private val file = File(ctx.filesDir, "signals.log")

    @Synchronized
    fun addSignal(s: Sig) {
        file.appendText(LogModel.sigLine(s) + "\n")
    }

    @Synchronized
    fun setResult(id: Long, result: String) {
        file.appendText(LogModel.resLine(id, System.currentTimeMillis(), result) + "\n")
    }

    @Synchronized
    fun addSkip(ts: Long, reason: String) {
        file.appendText(LogModel.skipLine(ts, reason) + "\n")
    }

    @Synchronized
    fun load(): LogData {
        if (!file.exists()) return LogData(emptyList(), emptyMap())
        return LogModel.parse(file.readLines())
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    /** Downloads ফোল্ডারে CSV সেভ করে; ফাইলের নাম ফেরত দেয় (ব্যর্থ হলে null) */
    fun exportCsv(): String? {
        return try {
            val data = load()
            val name = "supershot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                val sb = StringBuilder()
                sb.append("time,direction,setups,candles,timeframe_min,payout_pct,result\n")
                for (s in data.signals) {
                    sb.append(fmt.format(Date(s.ts))).append(',')
                        .append(s.dir).append(',')
                        .append(s.setups.joinToString("+")).append(',')
                        .append(s.n).append(',')
                        .append(s.tf).append(',')
                        .append(s.payout).append(',')
                        .append(s.result).append('\n')
                }
                sb.append("\n# skips\n")
                for ((k, v) in data.skips) sb.append("# ").append(k).append(',').append(v).append('\n')
                os.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            name
        } catch (e: Exception) {
            null
        }
    }
}
