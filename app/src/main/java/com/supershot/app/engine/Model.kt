package com.supershot.app.engine

enum class Dir { UP, DOWN }

/** ছবিতে শনাক্ত হওয়া একটি ক্যান্ডেল। y-মান পিক্সেলে (ওপরে ছোট), শেষ প্রান্ত exclusive। */
data class Candle(
    val x0: Int,
    val x1: Int,
    val yHigh: Int,
    val yLow: Int,
    val yBodyTop: Int,
    val yBodyBottom: Int,
    val green: Boolean,
    val cut: Boolean,
    /** আইকনের নিচে ঢাকা পড়ায় ছবিতে নেই; দুই পাশের ক্যান্ডেল থেকে বানানো */
    val synthetic: Boolean = false
) {
    val cx: Int get() = (x0 + x1) / 2
}

data class ChartReading(
    val ok: Boolean,
    val message: String,
    /** নির্ভরযোগ্য উইন্ডো: ধারাবাহিক ক্যান্ডেল, পুরোনো → নতুন */
    val candles: List<Candle>,
    /** শনাক্ত সব ক্যান্ডেল (ডিবাগ আঁকার জন্য) */
    val allCandles: List<Candle>,
    val bodyWidth: Int,
    val pitch: Int,
    val priceLineY: Int,
    val roiTop: Int,
    val roiBottom: Int,
    /** ঢাকা পড়া কতগুলো ক্যান্ডেল বানিয়ে ভরা হয়েছে */
    val gaps: Int
)

class Series(
    val o: DoubleArray,
    val h: DoubleArray,
    val l: DoubleArray,
    val c: DoubleArray
) {
    val n: Int get() = c.size
}

data class Setup(val id: String, val dir: Dir, val text: String)

data class Analysis(
    val ok: Boolean,
    val signal: Dir?,
    val setups: List<Setup>,
    /** WAIT-এর কারণ বা "OK" */
    val reason: String,
    val candleCount: Int,
    val info: String
)

/** ব্রোকারের চার্ট থিমের ক্যালিব্রেশন। Expert Option-এর ছবি থেকে মাপা। */
class Profile(
    val green: IntArray,
    val red: IntArray,
    val orange: IntArray,
    val blue: IntArray,
    val tol: Int,
    val roiTop: Double,
    val roiBottom: Double,
    val closeGap: Int,
    /** [x0, y0, x1, y1] স্ক্রিনের ভগ্নাংশে: চার্টের ওপর বসা বোতাম */
    val occluders: List<DoubleArray>
) {
    companion object {
        val EXPERT_OPTION = Profile(
            green = intArrayOf(14, 143, 94),
            red = intArrayOf(198, 69, 71),
            orange = intArrayOf(247, 167, 65),
            blue = intArrayOf(53, 167, 255),
            tol = 12,
            roiTop = 0.185,
            roiBottom = 0.79,
            closeGap = 5,
            occluders = listOf(
                doubleArrayOf(0.060, 0.198, 0.160, 0.242), // বাঁ-ওপরের বোতাম
                doubleArrayOf(0.060, 0.678, 0.160, 0.722), // রোবট বোতাম
                doubleArrayOf(0.060, 0.738, 0.160, 0.782), // টিউনার বোতাম
                doubleArrayOf(0.832, 0.460, 0.918, 0.500), // ডান চেভরন
                doubleArrayOf(0.840, 0.738, 0.936, 0.782)  // ডান-নিচের বোতাম
            )
        )

        /**
         * Quotex, পোর্ট্রেট মোড। ব্যবহারকারীর পাঠানো স্ক্রিনশট থেকে পিক্সেল মেপে বসানো
         * (আন্দাজে নয়): candle সবুজ ≈ rgb(20,165,90), লাল ≈ rgb(222,88,72)। ড্যাশড সাদা
         * লাইন (দামের অনুভূমিক রেখা ও ট্রেড শুরু/শেষের উল্লম্ব রেখা — দুটোই একই সাদা রঙ)
         * orange ও blue দুই ফিল্ডেই বসানো হয়েছে, যাতে গ্যাপ-ফিল ও প্রাইস-লাইন শনাক্তি দুটোই কাজ করে।
         *
         * শর্ত: অ্যাপ চালানোর আগে ওপরের প্রোমো ব্যানার (X চেপে) বন্ধ করে নিতে হবে —
         * ব্যানারের সবুজ রং candle-এর সবুজের প্রায় হুবহু একই, তাই খোলা থাকলে মিথ্যা candle
         * শনাক্ত হবে। এই ক্যালিব্রেশন এখনো আসল ডিভাইসে টেস্ট করা হয়নি (build environment-এ
         * Android চালানো যায় না), তাই প্রথমবার একটু কম-নির্ভুল হতে পারে — স্ক্রিনশট পাঠালে
         * roiTop/roiBottom আরও ঠিক করে দেওয়া যাবে।
         */
        val QUOTEX = Profile(
            green = intArrayOf(20, 165, 90),
            red = intArrayOf(222, 88, 72),
            orange = intArrayOf(237, 241, 251),
            blue = intArrayOf(237, 241, 251),
            tol = 28,
            roiTop = 0.065,
            roiBottom = 0.83,
            closeGap = 5,
            occluders = listOf(
                doubleArrayOf(0.010, 0.045, 0.140, 0.090), // বাঁ-ওপরের "..." বোতাম
                doubleArrayOf(0.010, 0.095, 0.140, 0.145), // ব্রিফকেস বোতাম
                doubleArrayOf(0.330, 0.035, 0.420, 0.070)  // "i" তথ্য বোতাম
            )
        )

        fun forPlatform(platform: String): Profile = if (platform == "quotex") QUOTEX else EXPERT_OPTION
    }
}
