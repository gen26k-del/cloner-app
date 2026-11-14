package com.example.cloner

/**
 * একটা ক্লোনের পরিচয়: প্যাকেজ + ভার্চুয়াল ইউজার।
 * একই অ্যাপের একাধিক ক্লোন = একই pkg, আলাদা userId (আলাদা ডেটা)।
 */
data class CloneId(val pkg: String, val userId: Int) {
    override fun toString(): String = "$pkg:$userId"

    companion object {
        /** "pkg:userId" বা পুরনো "pkg" ফরম্যাট থেকে পার্স করে */
        fun parse(s: String): CloneId? = try {
            val i = s.lastIndexOf(':')
            if (i < 0) {
                CloneId(s, 0) // পুরনো ফরম্যাট
            } else {
                CloneId(s.substring(0, i), s.substring(i + 1).toInt())
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * ভার্চুয়াল GMS-এর সাথে আসা সিস্টেম প্যাকেজ — এগুলো ব্যবহারকারীর ক্লোন না,
 * তাই ক্লোন লিস্টে দেখানো উচিত না (না হলে GMS ইনস্টল করলেই ৪টা "এক্সট্রা ক্লোন" দেখায়)।
 */
val GmsSystemPackages = setOf(
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.google.android.gsf.login",
    "com.android.vending",
    "com.google.android.backuptransport",
    "com.google.android.backup",
    "com.google.android.configupdater",
    "com.google.android.syncadapters.contacts",
    "com.google.android.feedback",
    "com.google.android.onetimeinitializer",
    "com.google.android.partnersetup",
    "com.google.android.setupwizard",
    "com.google.android.syncadapters.calendar"
)

fun isGmsSystemPackage(pkg: String): Boolean = pkg in GmsSystemPackages

/**
 * v1.0.97: চালু হলেও কাজ করে না — এমন অ্যাপ (hard limit)। চেকআপ এদের
 * ধরতে পারে না, কারণ অ্যাক্টিভিটি ঠিকই চালু হয় — ব্যর্থতাটা সার্ভার/
 * লগইন ধাপে। তাই তালিকা আর চেকআপ — দুই জায়গা থেকেই বাদ। ব্যবহারকারী
 * চেয়েছে: শুধু চলবে এমন অ্যাপই দেখাবে, অতিরিক্ত কিছু থাকবে না।
 * (প্যাকেজ নাম ফোনের রিপোর্ট/ট্রেস থেকে যাচাই করা; ChatGPT/YouTube-এর
 * নাম দুটি অফিসিয়াল প্যাকেজ নাম — না মিললে শুধু কোনো প্রভাব পড়বে না।)
 */
val UnsupportedPackages = setOf(
    "com.openai.chatgpt",          // ChatGPT: সবসময় "An error has occurred" (সার্ভার integrity check)
    "com.google.android.youtube",  // YouTube অ্যাপ: "device not compatible" (ডিভাইস সার্টিফিকেশন)
    "com.instagram.android",       // Instagram: লগইন পেজের পর আটকে যায়
    "com.facebook.lite",           // Lite: onCreate-এ NPE, bind শেষ হয় না
    "com.instagram.lite",          // Lite: একই NPE
    "com.google.android.apps.tachyon", // Meet: bind-এর আগেই Firebase deadlock
    "com.transsion.smartmessage",  // বিল্ট-ইন SMS: UID মেলে না (সিস্টেম অ্যাপ)
    "com.google.android.setupwizard", // Setup Wizard: লঞ্চার অ্যাক্টিভিটি নেই
    "com.android.settings"         // Settings: সিস্টেম অ্যাপ, ক্লোন করার মতো নয়
)

fun isUnsupportedPackage(pkg: String): Boolean = pkg in UnsupportedPackages

/**
 * ভার্চুয়ালাইজেশন ইঞ্জিনের সংযোগস্থল।
 * আসল ইঞ্জিন (যেমন BlackBox-ভিত্তিক) যুক্ত করার পর
 * এই ইন্টারফেস ইমপ্লিমেন্ট করে Engine.current-এ বসাতে হবে।
 */
interface CloneEngine {
    val name: String
    fun isReady(): Boolean
    fun installClone(packageName: String, userId: Int = 0): Result<Unit>
    fun launchClone(packageName: String, userId: Int = 0): Result<Unit>
    fun uninstallClone(packageName: String, userId: Int = 0): Result<Unit> =
        Result.failure(UnsupportedOperationException("এই ইঞ্জিনে আনইনস্টল সাপোর্ট নেই"))
    fun listClones(): List<CloneId>
    /** এই প্যাকেজের পরের ফাঁকা userId; জায়গা না থাকলে -1 */
    fun nextUserId(packageName: String): Int = 0
}

/** ইঞ্জিন যুক্ত না থাকলে এটি ব্যবহার হয়। ক্লোন আসলে কাজ করবে না। */
object NoEngine : CloneEngine {
    override val name = "কোনো ইঞ্জিন নেই"
    override fun isReady() = false
    override fun installClone(packageName: String, userId: Int): Result<Unit> =
        Result.failure(IllegalStateException("ভার্চুয়ালাইজেশন ইঞ্জিন এখনো যুক্ত করা হয়নি"))
    override fun launchClone(packageName: String, userId: Int): Result<Unit> =
        Result.failure(IllegalStateException("ভার্চুয়ালাইজেশন ইঞ্জিন এখনো যুক্ত করা হয়নি"))
    override fun listClones(): List<CloneId> = emptyList()
}

object Engine {
    var current: CloneEngine = NoEngine

}
