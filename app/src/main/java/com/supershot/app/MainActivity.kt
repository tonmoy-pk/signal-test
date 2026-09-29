package com.supershot.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
import com.supershot.app.engine.LogModel
import com.supershot.app.engine.SignalEngine
import com.supershot.app.engine.Stats
import com.supershot.app.engine.Verdict
import com.supershot.app.engine.VerdictKind
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    private fun tr(id: Int, vararg a: Any): String = Lang.format(this, id, *a)

    @Suppress("DEPRECATION")
    private fun versionText(): String = try {
        "v" + packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        ""
    }

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
        root.addView(label(versionText(), 13f))

        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        langRow.addView(
            TextView(this).apply {
                text = tr(R.string.lang_label)
                textSize = 14f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        for ((code, name) in listOf(Lang.BN to "বাংলা", Lang.EN to "English")) {
            langRow.addView(Button(this).apply {
                text = if (Prefs.lang(this@MainActivity) == code) "● $name" else name
                setOnClickListener {
                    if (Prefs.lang(this@MainActivity) != code) {
                        Prefs.setLang(this@MainActivity, code)
                        recreate()
                    }
                }
            })
        }
        root.addView(langRow)

        val platRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        platRow.addView(
            TextView(this).apply {
                text = tr(R.string.platform_label)
                textSize = 14f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        for ((code, nameId) in listOf("eo" to R.string.platform_eo, "quotex" to R.string.platform_quotex)) {
            platRow.addView(Button(this).apply {
                val name = tr(nameId)
                text = if (Prefs.platform(this@MainActivity) == code) "● $name" else name
                setOnClickListener {
                    if (Prefs.platform(this@MainActivity) != code) {
                        Prefs.setPlatform(this@MainActivity, code)
                        recreate()
                    }
                }
            })
        }
        root.addView(platRow)
        if (Prefs.platform(this) == "quotex") {
            root.addView(label(tr(R.string.platform_note), 12f, false, 4))
        }
        statusView = label("", 16f, true, 8)
        root.addView(statusView)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply {
            text = tr(R.string.btn_start)
            setOnClickListener { startFlow() }
        }
        val stop = Button(this).apply {
            text = tr(R.string.btn_stop)
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                statusView.postDelayed({ refresh() }, 400)
            }
        }
        row.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        // ---------- সেটিংস ----------
        root.addView(label(tr(R.string.settings), 18f, true, 20))
        stepper(root, tr(R.string.set_tf), { Prefs.tfMin(this) }, { Prefs.setTfMin(this, it) }, 1, 15, 1) { tr(R.string.unit_min, it) }
        stepper(root, tr(R.string.set_lead), { Prefs.leadSec(this) }, { Prefs.setLeadSec(this, it) }, 5, 20, 1) { tr(R.string.unit_sec, it) }
        stepper(root, tr(R.string.set_offset), { Prefs.offsetMs(this) }, { Prefs.setOffsetMs(this, it) }, -10000, 10000, 500) {
            tr(R.string.unit_sec, String.format(Locale.US, "%+.1f", it / 1000.0))
        }
        stepper(root, tr(R.string.set_payout), { Prefs.payout(this) }, { Prefs.setPayout(this, it) }, 50, 100, 1) { tr(R.string.unit_pct, it) }
        stepper(root, tr(R.string.set_minsetups), { Prefs.minSetups(this) }, { Prefs.setMinSetups(this, it) }, 1, 3, 1) { tr(R.string.unit_count, it) }
        stepper(root, tr(R.string.set_minsamples), { Prefs.minSamples(this) }, { Prefs.setMinSamples(this, it) }, 100, 2000, 50) { "$it" }

        val dbg = Switch(this).apply {
            text = tr(R.string.debug_switch)
            isChecked = Prefs.debug(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setDebug(this@MainActivity, checked) }
        }
        root.addView(dbg)

        // ---------- ফলাফল ----------
        root.addView(label(tr(R.string.results_title), 18f, true, 20))
        statsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statsBox)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val export = Button(this).apply {
            text = tr(R.string.btn_export)
            setOnClickListener {
                val name = log.exportCsv()
                Toast.makeText(
                    this@MainActivity,
                    if (name != null) tr(R.string.export_ok, name) else tr(R.string.export_fail),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        val reset = Button(this).apply {
            text = tr(R.string.btn_clear)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(tr(R.string.clear_title))
                    .setMessage(tr(R.string.clear_msg))
                    .setPositiveButton(tr(R.string.clear_yes)) { _, _ -> log.clear(); refresh() }
                    .setNegativeButton(tr(R.string.clear_no), null)
                    .show()
            }
        }
        row2.addView(export, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(reset, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)

        root.addView(label(tr(R.string.howto_title), 18f, true, 20))
        root.addView(label(tr(R.string.howto_body), 14f))

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

    private fun shortVerdict(t: com.supershot.app.engine.Tally, payout: Int, minN: Int): String =
        when (LogModel.verdict(t, payout, minN, 2.91).kind) {
            VerdictKind.ABOVE_BREAK_EVEN -> "✅"
            VerdictKind.BELOW_BREAK_EVEN -> "❌"
            VerdictKind.UNCERTAIN -> "⚠"
            VerdictKind.FEW_SAMPLES -> "…"
        }

    private fun verdictText(v: Verdict): String = when (v.kind) {
        VerdictKind.FEW_SAMPLES -> tr(R.string.verdict_few, v.n, v.minN)
        VerdictKind.ABOVE_BREAK_EVEN -> tr(R.string.verdict_above)
        VerdictKind.BELOW_BREAK_EVEN -> tr(R.string.verdict_below)
        VerdictKind.UNCERTAIN -> tr(R.string.verdict_unsure)
    }

    private fun refresh() {
        statusView.text = if (CaptureService.running) tr(R.string.status_running) else tr(R.string.status_stopped)
        statsBox.removeAllViews()

        val data = log.load()
        val payout = Prefs.payout(this)
        val minN = Prefs.minSamples(this)
        val all = LogModel.tally(data.signals)
        val voids = data.signals.count { it.result == LogModel.VOID }
        val waiting = data.signals.count { it.result == LogModel.PENDING }
        val be = Stats.breakEven(payout)

        val sb = StringBuilder()
        sb.append(tr(R.string.stats_total, data.signals.size, all.n))
        sb.append(tr(R.string.stats_counts, all.wins, all.losses, all.ties, voids, waiting))
        if (all.n > 0) {
            val ci = Stats.wilson(all.wins, all.n, 1.96)
            sb.append(tr(R.string.stats_winrate, pct(all.rate), pct(ci[0]), pct(ci[1])))
            sb.append(tr(R.string.stats_be, pct(be), payout))
            sb.append(tr(R.string.stats_profit, String.format(Locale.US, "%+.1f", all.profit)))
        }
        sb.append(tr(R.string.stats_verdict)).append(verdictText(LogModel.verdict(all, payout, minN, 1.96)))
        statsBox.addView(label(sb.toString(), 14f))

        val bl = LogModel.baseline(data.moves)
        if (bl.n > 0) {
            val bb = StringBuilder(tr(R.string.baseline_head, bl.n + bl.tie))
            bb.append(tr(R.string.baseline_updown, pct(bl.up.toDouble() / bl.n), pct(bl.down.toDouble() / bl.n)))
            if (bl.pairs > 0) {
                val ci = Stats.wilson(bl.same, bl.pairs, 1.96)
                bb.append(tr(R.string.baseline_same, pct(bl.same.toDouble() / bl.pairs), pct(ci[0]), pct(ci[1]), bl.pairs))
            }
            statsBox.addView(label(bb.toString(), 13f, false, 8))
        }

        var any = false
        val ps = StringBuilder(tr(R.string.setup_head))
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
            statsBox.addView(label(tr(R.string.skips, ks), 12f, false, 8))
        }

        if (data.signals.isNotEmpty()) {
            statsBox.addView(label(tr(R.string.recent_title), 14f, true, 12))
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            for (s in data.signals.asReversed().take(15)) {
                val icon = when (s.result) {
                    LogModel.WIN -> tr(R.string.res_win)
                    LogModel.LOSS -> tr(R.string.res_loss)
                    LogModel.TIE -> tr(R.string.res_tie)
                    LogModel.VOID -> tr(R.string.res_void)
                    else -> tr(R.string.res_pending)
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
            Toast.makeText(this, tr(R.string.toast_already), Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, tr(R.string.toast_overlay), Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, tr(R.string.toast_capture_denied), Toast.LENGTH_LONG).show()
            }
        }
    }
}
