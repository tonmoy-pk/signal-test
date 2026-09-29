# Super Shot v0.2.6

Expert Option চার্ট স্ক্রিন-ক্যাপচার করে ক্যান্ডেল পড়ে, প্রতি ক্যান্ডেল শেষের আগে UP / DOWN / WAIT দেখায়
এবং ২ ক্যান্ডেল পরে নিজে জিত/হার লগ করে। অ্যাপ বাংলা ও English দুই ভাষায় চলে (মূল স্ক্রিনের ওপরে থেকে ভাষা বাছাই)।

Build: GitHub-এ push (branch main) -> Actions -> "Build APK" -> artifact "supershot-apk".

Platform: মূল স্ক্রিনে Expert Option / Quotex বেছে নেওয়া যায় (engine/Model.kt-এর Profile.EXPERT_OPTION / Profile.QUOTEX)। Quotex-এর ক্যালিব্রেশন ব্যবহারকারীর পাঠানো স্ক্রিনশট থেকে পিক্সেল মেপে করা, কিন্তু এখনো আসল ডিভাইসে টেস্ট হয়নি — প্রথমবার signal ভুল দিলে debug ছবি (Pictures/SuperShot) পাঠালে roiTop/roiBottom/রং ঠিক করে দেওয়া যাবে। Quotex-এ শুরুর আগে প্রোমো ব্যানার বন্ধ করতে হবে।

ভাষা: app/src/main/res/values/strings.xml (English), values-bn/strings.xml (বাংলা)। নতুন লেখা যোগ করলে দুই ফাইলেই একই key দিতে হবে।

কোড কাঠামো:
- engine/ChartReader.kt  : ছবি -> ক্যান্ডেল (রং-মাস্ক + connected components)
- engine/Indicators.kt   : EMA, RSI(Wilder), MACD, Bollinger, ATR
- engine/SignalEngine.kt : ১৪টি নির্দিষ্ট সেটআপ + প্যাটার্ন (Doji, PinBar সহ) + সাপোর্ট/রেজিস্ট্যান্স
- engine/LogModel.kt     : লগ, জিত/হার নির্ধারণ (Resolver), সিদ্ধান্তের ধরন
- engine/Stats.kt        : Wilson আস্থা-সীমা, ব্রেক-ইভেন
- Lang.kt                : ভাষা নির্বাচন (bn / en)
- CaptureService.kt      : বাবল, সময়সূচি, ক্যাপচার
- MainActivity.kt        : সেটিংস + ফলাফল ড্যাশবোর্ড
