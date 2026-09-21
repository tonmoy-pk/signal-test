package com.example.signaltest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.signaltest.engine.LogModel
import com.example.signaltest.engine.SignalEngine
import com.example.signaltest.engine.Stats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTIF = 1002
    }

    private lateinit var log: SignalLog
    private lateinit var statusView: TextView
    private lateinit var statsBox: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, bold: Boolean = false, top: Int = 0): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(top), 0, dp(4))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = SignalLog(this)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        root.addView(label("Super Shot", 26f, true))
        root.addView(label("v0.2.1 · ডেমো টেস্ট মোড", 13f))
        statusView = label("", 16f, true, 8)
        root.addView(statusView)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply {
            text = "▶ শুরু করো"
            setOnClickListener { startFlow() }
        }
        val stop = Button(this).apply {
            text = "■ বন্ধ করো"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                statusView.postDelayed({ refresh() }, 400)
            }
        }
        row.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        // ---------- সেটিংস ----------
        root.addView(label("সেটিংস", 18f, true, 20))
        stepper(root, "ক্যান্ডেল টাইমফ্রেম (মিনিট)", { Prefs.tfMin(this) }, { Prefs.setTfMin(this, it) }, 1, 15, 1) { "$it মি" }
        stepper(root, "সিগন্যাল শেষের কত সেকেন্ড আগে", { Prefs.leadSec(this) }, { Prefs.setLeadSec(this, it) }, 5, 20, 1) { "$it সে" }
        stepper(root, "ঘড়ির সমন্বয় (ব্রোকারের সাথে)", { Prefs.offsetMs(this) }, { Prefs.setOffsetMs(this, it) }, -10000, 10000, 500) {
            String.format(Locale.US, "%+.1f সে", it / 1000.0)
        }
        stepper(root, "পেআউট %", { Prefs.payout(this) }, { Prefs.setPayout(this, it) }, 50, 100, 1) { "$it%" }
        stepper(root, "ন্যূনতম একমত সেটআপ", { Prefs.minSetups(this) }, { Prefs.setMinSetups(this, it) }, 1, 3, 1) { "$it টি" }
        stepper(root, "টেস্টে ন্যূনতম সিগন্যাল", { Prefs.minSamples(this) }, { Prefs.setMinSamples(this, it) }, 100, 2000, 50) { "$it" }

        val dbg = Switch(this).apply {
            text = "ডিবাগ ছবি সেভ (Pictures/SuperShot)"
            isChecked = Prefs.debug(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setDebug(this@MainActivity, checked) }
        }
        root.addView(dbg)

        // ---------- ফলাফল ----------
        root.addView(label("ডেমো টেস্টের ফলাফল", 18f, true, 20))
        statsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statsBox)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val export = Button(this).apply {
            text = "CSV এক্সপোর্ট"
            setOnClickListener {
                val name = log.exportCsv()
                Toast.makeText(
                    this@MainActivity,
                    if (name != null) "Downloads/$name সেভ হয়েছে" else "এক্সপোর্ট ব্যর্থ",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        val reset = Button(this).apply {
            text = "লগ মুছো"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("লগ মুছবে?")
                    .setMessage("সব সিগন্যাল ও ফলাফল মুছে গণনা ০ থেকে শুরু হবে।")
                    .setPositiveButton("মুছো") { _, _ -> log.clear(); refresh() }
                    .setNegativeButton("না", null)
                    .show()
            }
        }
        row2.addView(export, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(reset, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)

        root.addView(label("ব্যবহারের নিয়ম", 18f, true, 20))
        root.addView(
            label(
                "1. Expert Option ডেমো অ্যাকাউন্ট, ক্যান্ডেলস্টিক চার্ট, প্ল্যাটফর্মের নিজের ইন্ডিকেটর বন্ধ।\n" +
                    "2. চার্ট জুম আউট করে অন্তত ৫৫টি ক্যান্ডেল দেখাও (কম হলে EMA50/MACD বন্ধ থাকে; ২৫টির কম হলে সিগন্যালই নেই)।\n" +
                    "3. শুরু করলে বাবল আসবে। ক্যান্ডেল শেষের নির্ধারিত সেকেন্ড আগে বাবলে UP / DOWN / WAIT দেখাবে।\n" +
                    "4. চাইলে সিগন্যাল দেখে ডেমোতে ট্রেড নাও। অ্যাপ ২ ক্যান্ডেল পরে নিজে ছবি নিয়ে পরের ক্যান্ডেলের রং দেখে জিত/হার লগ করে।\n" +
                    "5. এই জিত/হার ব্রোকারের আসল ফলাফল নয়, পরের ক্যান্ডেল সবুজ না লাল তার ভিত্তিতে। ভুল হলে নিচের তালিকায় ট্যাপ করে বদলাও।\n" +
                    "6. বাবলে ট্যাপ = এখনই বিশ্লেষণ (লগ হয় না, ডিবাগ ছবি সেভ হয়)। ১ সেকেন্ড চেপে ধরলে অ্যাপ বন্ধ।\n" +
                    "7. প্রথম ক্যান্ডেলের সিগন্যালের সময় ঘড়ি ঠিক আছে কি না দেখে নিও: বাবলের ⏱ ও Expert Option-এর টাইমার মেলে কি না। না মিললে ঘড়ির সমন্বয় বদলাও।",
                14f
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- সেটিংস: − মান + ----------
    private fun stepper(
        parent: LinearLayout,
        title: String,
        get: () -> Int,
        set: (Int) -> Unit,
        min: Int,
        max: Int,
        step: Int,
        fmt: (Int) -> String
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(this).apply {
            text = title
            textSize = 14f
        }
        val minus = Button(this).apply { text = "−" }
        val value = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            minWidth = dp(72)
        }
        val plus = Button(this).apply { text = "+" }

        fun show() {
            value.text = fmt(get())
        }
        minus.setOnClickListener { set((get() - step).coerceAtLeast(min)); show() }
        plus.setOnClickListener { set((get() + step).coerceAtMost(max)); show() }
        show()

        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(minus)
        row.addView(value)
        row.addView(plus)
        parent.addView(row)
    }

    // ---------- ফলাফল দেখানো ----------
    private fun pct(v: Double): String = String.format(Locale.US, "%.1f", v * 100)

    private fun shortVerdict(t: com.example.signaltest.engine.Tally, payout: Int, minN: Int): String {
        val v = LogModel.verdict(t, payout, minN, 2.91)
        return when {
            v.startsWith("✅") -> "✅"
            v.startsWith("❌") -> "❌"
            v.startsWith("⚠") -> "⚠"
            else -> "…"
        }
    }

    private fun refresh() {
        statusView.text = if (CaptureService.running) "● চলছে" else "○ বন্ধ"
        statsBox.removeAllViews()

        val data = log.load()
        val payout = Prefs.payout(this)
        val minN = Prefs.minSamples(this)
        val all = LogModel.tally(data.signals)
        val voids = data.signals.count { it.result == LogModel.VOID }
        val waiting = data.signals.count { it.result == LogModel.PENDING }
        val be = Stats.breakEven(payout)

        val sb = StringBuilder()
        sb.append("মোট সিগন্যাল: ${data.signals.size}  |  যাচাই হয়েছে: ${all.n}\n")
        sb.append("জিত ${all.wins}  হার ${all.losses}  টাই ${all.ties}  বাতিল $voids  অপেক্ষায় $waiting\n")
        if (all.n > 0) {
            val ci = Stats.wilson(all.wins, all.n, 1.96)
            sb.append("Win rate: ${pct(all.rate)}%  (৯৫% সীমা ${pct(ci[0])}–${pct(ci[1])}%)\n")
            sb.append("ব্রেক-ইভেন: ${pct(be)}% (পেআউট $payout%)\n")
            sb.append("সমান স্টেকে লাভ/ক্ষতি: ${String.format(Locale.US, "%+.1f", all.profit)} স্টেক\n")
        }
        sb.append("সিদ্ধান্ত: ").append(LogModel.verdict(all, payout, minN, 1.96))
        statsBox.addView(label(sb.toString(), 14f))

        var any = false
        val ps = StringBuilder("সেটআপ অনুযায়ী (১৪টা সেটআপ তুলনার জন্য কড়া সীমা z=2.9; একই সিগন্যাল একাধিক সেটআপে গোনা হয়):\n")
        for (id in SignalEngine.SETUP_IDS) {
            val list = data.signals.filter { id in it.setups }
            if (list.isEmpty()) continue
            any = true
            val t = LogModel.tally(list)
            val ci = Stats.wilson(t.wins, t.n, 2.91)
            ps.append("${shortVerdict(t, payout, minN)} $id: n=${t.n}, ${pct(t.rate)}% (${pct(ci[0])}–${pct(ci[1])})\n")
        }
        if (any) statsBox.addView(label(ps.toString().trimEnd(), 13f, false, 8))

        if (data.skips.isNotEmpty()) {
            val ks = data.skips.entries.joinToString("  ") { "${it.key}:${it.value}" }
            statsBox.addView(label("WAIT/স্কিপ: $ks", 12f, false, 8))
        }

        if (data.signals.isNotEmpty()) {
            statsBox.addView(label("সাম্প্রতিক সিগন্যাল (ট্যাপ করে ফলাফল বদলাও)", 14f, true, 12))
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            for (s in data.signals.asReversed().take(15)) {
                val icon = when (s.result) {
                    LogModel.WIN -> "✅ জিত"
                    LogModel.LOSS -> "❌ হার"
                    LogModel.TIE -> "➖ টাই"
                    LogModel.VOID -> "❔ বাতিল"
                    else -> "⏳ অপেক্ষা"
                }
                val arrow = if (s.dir == "UP") "▲" else "▼"
                val tv = label("${fmt.format(Date(s.ts))}  $arrow ${s.dir}  ${s.setups.joinToString("+")}  →  $icon", 13f)
                tv.setPadding(0, dp(6), 0, dp(6))
                tv.setOnClickListener {
                    val next = when (s.result) {
                        LogModel.WIN -> LogModel.LOSS
                        LogModel.LOSS -> LogModel.VOID
                        else -> LogModel.WIN
                    }
                    log.setResult(s.id, next)
                    refresh()
                }
                statsBox.addView(tv)
            }
        }
    }

    // ---------- শুরু করার ধাপ ----------
    private fun startFlow() {
        if (CaptureService.running) {
            Toast.makeText(this, "Super Shot আগে থেকেই চলছে", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay অনুমতি চালু করে ব্যাক করো", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) requestCapture()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, CaptureService::class.java).apply {
                    putExtra("code", resultCode)
                    putExtra("data", data)
                }
                startForegroundService(svc)
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "স্ক্রিন ক্যাপচারের অনুমতি দেওয়া হয়নি", Toast.LENGTH_LONG).show()
            }
        }
    }
}
