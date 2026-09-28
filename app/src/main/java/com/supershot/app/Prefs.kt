package com.supershot.app

import android.content.Context

/** অ্যাপের সেটিংস (SharedPreferences) */
object Prefs {

    private fun sp(c: Context) = c.getSharedPreferences("supershot", Context.MODE_PRIVATE)

    fun tfMin(c: Context): Int = sp(c).getInt("tf", 1)
    fun setTfMin(c: Context, v: Int) = sp(c).edit().putInt("tf", v).apply()

    /** ক্যান্ডেল শেষের কত সেকেন্ড আগে সিগন্যাল */
    fun leadSec(c: Context): Int = sp(c).getInt("lead", 10)
    fun setLeadSec(c: Context, v: Int) = sp(c).edit().putInt("lead", v).apply()

    /** ব্রোকারের ঘড়ির সাথে ফোনের ঘড়ির ব্যবধান (মিলিসেকেন্ড) */
    fun offsetMs(c: Context): Int = sp(c).getInt("offset_ms", 0)
    fun setOffsetMs(c: Context, v: Int) = sp(c).edit().putInt("offset_ms", v).apply()

    fun payout(c: Context): Int = sp(c).getInt("payout", 80)
    fun setPayout(c: Context, v: Int) = sp(c).edit().putInt("payout", v).apply()

    fun minSetups(c: Context): Int = sp(c).getInt("min_setups", 1)
    fun setMinSetups(c: Context, v: Int) = sp(c).edit().putInt("min_setups", v).apply()

    fun minSamples(c: Context): Int = sp(c).getInt("min_samples", 400)
    fun setMinSamples(c: Context, v: Int) = sp(c).edit().putInt("min_samples", v).apply()

    fun debug(c: Context): Boolean = sp(c).getBoolean("debug", true)
    fun setDebug(c: Context, v: Boolean) = sp(c).edit().putBoolean("debug", v).apply()

    /** অ্যাপের ভাষা: "bn" বা "en" (ডিফল্ট বাংলা) */
    fun lang(c: Context): String = sp(c).getString("lang", Lang.BN) ?: Lang.BN
    fun setLang(c: Context, v: String) = sp(c).edit().putString("lang", v).apply()
}
