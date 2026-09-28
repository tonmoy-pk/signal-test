package com.supershot.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** ভাষা নির্বাচন (বাংলা / English)। সংখ্যা সবসময় ইংরেজি অঙ্কে দেখানো হয়। */
object Lang {
    const val BN = "bn"
    const val EN = "en"

    /** ভাষা বদলানো কনফিগারেশনসহ কনটেক্সট */
    fun forCode(base: Context, code: String): Context {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale(code))
        return base.createConfigurationContext(cfg)
    }

    /** Activity-র attachBaseContext-এ ব্যবহারের জন্য */
    fun wrap(base: Context): Context = forCode(base, Prefs.lang(base))

    /** স্ট্রিং রিসোর্স + আর্গুমেন্ট। আর্গুমেন্ট আগে String করা হয়, তাই বাংলা লোকেলেও অঙ্ক ইংরেজিই থাকে। */
    fun format(ctx: Context, id: Int, vararg args: Any): String =
        String.format(Locale.US, ctx.getString(id), *args.map { it.toString() }.toTypedArray())
}
