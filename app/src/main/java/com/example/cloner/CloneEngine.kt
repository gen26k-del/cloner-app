package com.example.cloner

/**
 * ভার্চুয়ালাইজেশন ইঞ্জিনের সংযোগস্থল।
 * আসল ইঞ্জিন (যেমন BlackBox-ভিত্তিক) যুক্ত করার পর
 * এই ইন্টারফেস ইমপ্লিমেন্ট করে Engine.current-এ বসাতে হবে।
 */
interface CloneEngine {
    val name: String
    fun isReady(): Boolean
    fun installClone(packageName: String): Result<Unit>
    fun launchClone(packageName: String): Result<Unit>
    fun uninstallClone(packageName: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("এই ইঞ্জিনে আনইনস্টল সাপোর্ট নেই"))
    fun listClones(): List<String>
}

/** ইঞ্জিন যুক্ত না থাকলে এটি ব্যবহার হয়। ক্লোন আসলে কাজ করবে না। */
object NoEngine : CloneEngine {
    override val name = "কোনো ইঞ্জিন নেই"
    override fun isReady() = false
    override fun installClone(packageName: String): Result<Unit> =
        Result.failure(IllegalStateException("ভার্চুয়ালাইজেশন ইঞ্জিন এখনো যুক্ত করা হয়নি"))
    override fun launchClone(packageName: String): Result<Unit> =
        Result.failure(IllegalStateException("ভার্চুয়ালাইজেশন ইঞ্জিন এখনো যুক্ত করা হয়নি"))
    override fun listClones(): List<String> = emptyList()
}

object Engine {
    var current: CloneEngine = NoEngine
}
