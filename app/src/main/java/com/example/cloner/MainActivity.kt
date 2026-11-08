package com.example.cloner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap)

private const val PREFS = "cloner_prefs"
private const val KEY_CLONES = "clones"
private const val KEY_CHECKUP_PASSED = "checkup_passed"

/** ব্র্যান্ড রঙ — CloneMe লোগোর সাথে মিলিয়ে */
private val BrandBlue = Color(0xFF1B9BF5)
private val BrandCyan = Color(0xFF4FC3F7)
private val BrandNavy = Color(0xFF0A1A3C)
private val BrandDeepBlue = Color(0xFF14337A)

private val ClonerLightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF1FE),
    onPrimaryContainer = BrandNavy,
    secondary = BrandCyan,
    background = Color(0xFFF4F8FD),
    surface = Color.White,
    onSurface = Color(0xFF1B1B22)
)

@Composable
fun ClonerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClonerLightColors, content = content)
}

fun loadApps(ctx: Context): List<AppInfo> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = try {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    } catch (_: Exception) {
        return emptyList()
    }
    return resolved
        .filter { it.activityInfo.packageName != ctx.packageName }
        .distinctBy { it.activityInfo.packageName }
        // একটা অ্যাপের আইকন/লেবেল লোডে ব্যর্থ হলে পুরো তালিকা খালি হয়ে যেত —
        // এখন সেই অ্যাপটা শুধু বাদ যাবে, বাকিগুলো দেখাবে।
        .mapNotNull { ri ->
            runCatching {
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    pkg = ri.activityInfo.packageName,
                    icon = ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                )
            }.getOrNull()
        }
        .sortedBy { it.label.lowercase() }
}

// চেকআপের প্রতিটি রায় ফাইলে জমাও — অ্যাপ ক্র্যাশ হয়ে গেলেও আংশিক ফলাফল থাকবে।
private fun appendCheckupLine(ctx: Context, line: String) {
    runCatching {
        java.io.File(ctx.cacheDir, "checkup_report.txt").appendText(line + "\n")
    }
}

// প্যাকেজের অ্যাক্টিভিটি থেকে সবচেয়ে সম্ভাব্য লঞ্চার অ্যাক্টিভিটি বাছো।
// প্রথমে সব পুলে লঞ্চার-ধাঁচের নাম (.Main / Launcher / Splash / Tabbed / Home)
// খুঁজো — কোনো পুলে শুধু বিলিং/সিস্টেম অ্যাক্টিভিটি থাকলে সেটা নিয়ে
// অন্য পুলে আসল লঞ্চার খোঁজা বন্ধ করো না। লঞ্চার-ধাঁচের কিছু না মিললে
// তবেই যেকোনো একটা নাও। (android.* সিস্টেম অ্যাক্টিভিটি শেষে।)
fun pickLauncherActivity(
    activities: Array<android.content.pm.ActivityInfo>?
): android.content.pm.ActivityInfo? {
    val all = activities?.toList() ?: return null
    val exported = all.filter { it.exported }
    val ownExp = exported.filter { !it.name.startsWith("android.") }
    val ownAll = all.filter { !it.name.startsWith("android.") }
    fun pickLike(list: List<android.content.pm.ActivityInfo>) =
        list.firstOrNull { it.name.endsWith(".Main") }
            ?: list.firstOrNull {
                // Shortcut অ্যাক্টিভিটি (LauncherShortcutActivity) বাদ — ওটা মূল
                // অ্যাক্টিভিটি নয়, শর্টকাট ধরার হ্যান্ডলার।
                !it.name.contains("Shortcut") && (
                    it.name.contains("Main") || it.name.contains("Launcher") ||
                    it.name.contains("Splash") || it.name.contains("Tabbed") ||
                    it.name.contains("Home")
                )
            }
    return pickLike(ownExp) ?: pickLike(ownAll) ?: pickLike(exported) ?: pickLike(all)
        ?: ownExp.firstOrNull() ?: ownAll.firstOrNull()
        ?: exported.firstOrNull() ?: all.firstOrNull()
}

// ট্রেস-যাচাইয়ের জন্য আসল অ্যাক্টিভিটির নাম বের করো।
// লঞ্চ intent-এর কম্পোনেন্টই সবচেয়ে নির্ভরযোগ্য: Chrome-এর Main একটি
// activity-alias — ট্রেসে আসে আসল নাম (ChromeTabbedActivity), alias নয়।
// ভার্চুয়াল activities তালিকায় alias থাকে না বলে pickLauncherActivity
// ভুল অ্যাক্টিভিটি (যেমন PlayCoreDialogWrapperActivity) তুলে নেয়, আর
// তখন ট্রেসে ভুল নাম খুঁজে "অ্যাক্টিভিটি শুরু হয়নি" বলে — যদিও আসল
// অ্যাক্টিভিটি চলেছিল। হোস্ট PM alias-এর targetActivity জানে।
private fun resolveTraceActivityClass(
    ctx: Context,
    intentClass: String?,
    pkg: String,
    fallback: android.content.pm.ActivityInfo?
): String? {
    if (intentClass != null) {
        return runCatching {
            ctx.packageManager.getActivityInfo(
                android.content.ComponentName(pkg, intentClass), 0
            ).targetActivity ?: intentClass
        }.getOrElse { intentClass }
    }
    return fallback?.let { if (it.name.startsWith(".")) pkg + it.name else it.name }
}

private fun readSavedClones(ctx: Context): MutableSet<String> =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_CLONES, emptySet())?.toMutableSet() ?: mutableSetOf()

private fun saveClones(ctx: Context, clones: Set<String>) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_CLONES, clones).apply()
}

// চেকআপে পাস করা অ্যাপের প্যাকেজ নাম — এগুলো পরের চেকআপে আর চেক করা হবে না।
private fun readPassedPkgs(ctx: Context): MutableSet<String> =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_CHECKUP_PASSED, emptySet())?.toMutableSet() ?: mutableSetOf()

private fun savePassedPkgs(ctx: Context, passed: Set<String>) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_CHECKUP_PASSED, passed).apply()
}

class MainActivity : ComponentActivity() {

    companion object {
        /** হোম-স্ক্রিন শর্টকাট থেকে এলে এই প্যাকেজটা সরাসরি চালু হবে */
        var launchPkg: String? = null
        const val ACTION_LAUNCH_CLONE = "com.example.cloner.LAUNCH_CLONE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShortcutIntent(intent)
        setContent {
            ClonerTheme {
                ClonerAppScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action == ACTION_LAUNCH_CLONE) {
            intent.getStringExtra("clone_pkg")?.let { launchPkg = it }
        }
    }

    /** ক্লোনের হোম-স্ক্রিন শর্টকাট পিন করে (প্রতি ইনস্ট্যান্সে আলাদা) */
    fun pinCloneShortcut(clone: CloneId, app: AppInfo, title: String) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "এই ফোনে শর্টকাট সাপোর্ট করে না", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, ShortcutLaunchActivity::class.java).apply {
            action = ACTION_LAUNCH_CLONE
            putExtra("clone_pkg", clone.pkg)
            putExtra("clone_user", clone.userId)
        }
        val info = ShortcutInfo.Builder(this, "clone_${clone.pkg}_${clone.userId}")
            .setShortLabel(title)
            .setIcon(Icon.createWithBitmap(app.icon.asAndroidBitmap()))
            .setIntent(intent)
            .build()
        sm.requestPinShortcut(info, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClonerAppScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busyClone by remember { mutableStateOf<CloneId?>(null) }
    var crashReports by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var logText by remember { mutableStateOf<String?>(null) }
    var traceText by remember { mutableStateOf<String?>(null) }
    var reportText by remember { mutableStateOf<String?>(null) }
    var gmsState by remember { mutableStateOf("checking") } // checking/unsupported/installed/ready/installing/failed
    var batteryIgnored by remember { mutableStateOf(true) }
    val apps = remember { mutableStateListOf<AppInfo>() }
    val clones = remember { mutableStateListOf<CloneId>() }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    // ব্যাকগ্রাউন্ডে অ্যাপের তালিকা লোড — UI আটকাবে না
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { readSavedClones(ctx) }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { loadApps(ctx) }.getOrDefault(emptyList())
        }
        // ইঞ্জিনের নিজস্ব লিস্টের সাথে সংরক্ষিত লিস্ট মিলিয়ে নাও
        val engineClones = withContext(Dispatchers.IO) {
            runCatching { Engine.current.listClones() }.getOrDefault(emptyList())
        }
        // সব crash/ANR রিপোর্ট পড়ে নাও — সবচেয়ে নতুনটা শুধু দেখালে Chrome-এর
        // ক্র্যাশের পর ANR এলে Chrome-এর রিপোর্ট আর দেখা যায় না
        crashReports = withContext(Dispatchers.IO) {
            runCatching {
                ctx.cacheDir.listFiles { f ->
                    f.name.startsWith("last_crash") && f.name.endsWith(".txt")
                }?.sortedByDescending { it.lastModified() }
                    ?.map { it.name to it.readText().take(6000) }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
        // Google Play সার্ভিসের অবস্থা দেখো
        gmsState = withContext(Dispatchers.IO) {
            runCatching {
                val core = BlackBoxCore.get()
                when {
                    !core.isSupportGms() -> "unsupported"
                    core.isInstallGms(0) -> "installed"
                    else -> "ready"
                }
            }.getOrDefault("checking")
        }
        val parsed = saved.mapNotNull { CloneId.parse(it) }
            .filter { !isGmsSystemPackage(it.pkg) }
        clones.addAll((parsed + engineClones).distinct())
        apps.addAll(loaded)
        loading = false
    }

    // ব্যাটারি অপটিমাইজেশন অবস্থা দেখো (নোটিফিকেশনের জন্য)
    LaunchedEffect(Unit) {
        batteryIgnored = withContext(Dispatchers.IO) {
            runCatching {
                val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }.getOrDefault(true)
        }
    }

    fun doRequestBatteryExemption() {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${ctx.packageName}")
            )
            ctx.startActivity(intent)
        } catch (_: Exception) {
            toast("Settings → Battery → CloneMe → Unrestricted করুন")
        }
    }

    fun persist() = saveClones(ctx, clones.map { it.toString() }.toSet())

    fun doClone(app: AppInfo) {
        if (busyClone != null) return
        scope.launch(Dispatchers.IO) {
            val uid = Engine.current.nextUserId(app.pkg)
            if (uid < 0) {
                withContext(Dispatchers.Main) {
                    toast("সর্বোচ্চ ক্লোন সংখ্যা পূর্ণ হয়েছে")
                }
                return@launch
            }
            val newClone = CloneId(app.pkg, uid)
            withContext(Dispatchers.Main) { busyClone = newClone }
            val result = Engine.current.installClone(app.pkg, uid)
            withContext(Dispatchers.Main) {
                busyClone = null
                result.fold(
                    onSuccess = {
                        if (!clones.contains(newClone)) {
                            clones.add(newClone)
                            persist()
                        }
                        val n = clones.count { it.pkg == app.pkg }
                        toast("${app.label} ক্লোন হয়েছে" + if (n > 1) " ($n)" else "")
                    },
                    onFailure = { toast(it.message ?: "ক্লোন করা যায়নি") }
                )
            }
        }
    }

    fun doLaunch(clone: CloneId) {
        if (busyClone != null) return
        busyClone = clone
        scope.launch(Dispatchers.IO) {
            val result = Engine.current.launchClone(clone.pkg, clone.userId)
            withContext(Dispatchers.Main) {
                busyClone = null
                result.onFailure { toast(it.message ?: "চালু করা যায়নি") }
                result.onSuccess {
                    // সফল লঞ্চে আর মেসেজ দেখানো হয় না (বিরক্তিকর বলে ব্যবহারকারী জানিয়েছে)।
                    // ৫ সেকেন্ড পর: স্টাব প্রসেস (:p0/:p1...) বেঁচে আছে কিনা
                    scope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(5000)
                        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                            as android.app.ActivityManager
                        val alive = am.runningAppProcesses
                            ?.any { it.processName.startsWith("${ctx.packageName}:p") } == true
                        withContext(Dispatchers.Main) {
                            if (!alive) {
                                android.widget.Toast.makeText(
                                    ctx,
                                    "${clone.pkg}-এর প্রসেস চালু হয়নি / বন্ধ হয়ে গেছে — নিচের ট্রেসটা কপি করে পাঠাও",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                // ট্রেস কার্ড নিজে থেকেই দেখাও — ব্যবহারকারীকে আর বোতাম চাপতে হবে না
                                scope.launch(Dispatchers.IO) {
                                    val text = runCatching {
                                        val f = java.io.File(ctx.cacheDir, "init_trace.txt")
                                        if (!f.exists()) "ট্রেস ফাইল নেই (init_trace.txt পাওয়া যায়নি)"
                                        else f.readText().takeLast(4000).ifEmpty { "ট্রেস ফাইল খালি" }
                                    }.getOrDefault("ট্রেস পড়া যায়নি")
                                    withContext(Dispatchers.Main) { traceText = text }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // লগ পড়া: Chrome-এর প্রসেস মারা গেলে (নেটিভ ক্র্যাশ) জাভা রিপোর্ট
    // থাকে না — তখন logcat-ই একমাত্র সূত্র। একই UID-এর প্রসেসের লগ পড়া যায়।
    fun captureLog() {
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                val proc = Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-t", "1000"))
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                val keys = listOf(
                    "chrome", "fatal", "sigsegv", "sigabrt", "sigbus", "dlopen",
                    "chromium", "androidruntime", "tombstone", "crash", "linker",
                    "fatal signal", "native crash", "com.example.cloner",
                    "com.android.chrome", "has died", "win death"
                )
                val lines = out.lines().filter { line ->
                    val low = line.lowercase()
                    !low.contains("packageparser") &&
                    keys.any { low.contains(it) }
                }.takeLast(60)
                if (lines.isEmpty()) "লগে ক্র্যাশের কোনো লাইন পাওয়া যায়নি (লগ খালি বা এই ফোনে লগ পড়া বন্ধ আছে)"
                else lines.joinToString("\n").take(4000)
            }.getOrDefault("লগ পড়া যায়নি — logcat চালানো ব্যর্থ হয়েছে")
            withContext(Dispatchers.Main) { logText = text }
        }
    }

    // ট্রেস দেখা: স্টাব প্রসেস কতদূর গেলো (bind → onCreate → onResume)
    // init_trace.txt-এ সব প্রসেসের লাইন জমা থাকে, মরার পরও ফাইল থাকে।
    fun captureTrace() {
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                val f = java.io.File(ctx.cacheDir, "init_trace.txt")
                if (!f.exists()) "ট্রেস ফাইল নেই (init_trace.txt পাওয়া যায়নি)"
                else f.readText().takeLast(4000).ifEmpty { "ট্রেস ফাইল খালি" }
            }.getOrDefault("ট্রেস পড়া যায়নি")
            withContext(Dispatchers.Main) { traceText = text }
        }
    }

    // স্মার্ট রিপোর্ট: অ্যাপ নিজে থেকেই সবকিছু যাচাই করে রায়সহ রিপোর্ট বানায় —
    // ইনস্টল, লঞ্চ intent, কম্পোনেন্ট, নেটিভ লাইব্রেরি, স্টোরেজ, ট্রেস, ক্র্যাশ।
    fun generateReport() {
        val cloneList = clones.toList()
        val gms = gmsState
        scope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val ver = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrDefault("?")
            sb.append("=== CloneMe স্মার্ট রিপোর্ট ===\n")
            sb.append("সময়: ${java.util.Date()}\nভার্সন: $ver\n")
            sb.append("ইঞ্জিন: ${if (Engine.current.isReady()) "চালু" else "বন্ধ"}\n")
            sb.append("GMS: $gms\n")
            // বাইরের স্টোরেজ যাচাই (আগে /dev/null পাওয়া গিয়েছিল)
            val ext = runCatching { ctx.getExternalFilesDir(null) }.getOrNull()
            sb.append("বাইরের স্টোরেজ: ${ext?.absolutePath ?: "null (পাওয়া যায়নি)"}\n")
            if (ext != null && ext.absolutePath.startsWith("/dev/null")) {
                sb.append("⚠️ সতর্কতা: বাইরের স্টোরেজ পাথ /dev/null — ভুল!\n")
            }
            val pm = BlackBoxCore.getBPackageManager()
            val trace = runCatching {
                java.io.File(ctx.cacheDir, "init_trace.txt")
                    .takeIf { it.exists() }?.readText().orEmpty()
            }.getOrDefault("")
            val traceTail = trace.takeLast(1500)
            val verdicts = mutableListOf<String>()
            if (cloneList.isEmpty()) {
                sb.append("\nকোনো ক্লোন নেই।\n")
            }
            for (clone in cloneList) {
                val pkg = clone.pkg
                sb.append("\n--- ক্লোন: $pkg (user ${clone.userId}) ---\n")
                val installed = runCatching {
                    BlackBoxCore.get().isInstalled(pkg, clone.userId)
                }.getOrDefault(false)
                sb.append("ইনস্টল: ${if (installed) "হ্যাঁ" else "না"}\n")
                val intent = runCatching {
                    pm.getLaunchIntentForPackage(pkg, clone.userId)
                }.getOrNull()
                sb.append("লঞ্চ intent: ${intent?.component?.className ?: "null (মেলে না)"}\n")
                val pkgInfo = runCatching {
                    pm.getPackageInfo(
                        pkg,
                        android.content.pm.PackageManager.GET_ACTIVITIES,
                        clone.userId
                    )
                }.getOrNull()
                val target = pickLauncherActivity(pkgInfo?.activities)
                // আসল অ্যাক্টিভিটি: intent-এর কম্পোনেন্ট (alias হলে তার
                // target) — pickLauncherActivity শুধু ফলব্যাক, কারণ
                // ভার্চুয়াল activities তালিকায় alias থাকে না।
                var clsName: String? = resolveTraceActivityClass(
                    ctx, intent?.component?.className, pkg, target
                )
                var aiFound = false
                if (clsName != null) {
                    sb.append("মূল অ্যাক্টিভিটি: $clsName\n")
                    aiFound = runCatching {
                        pm.getActivityInfo(
                            android.content.ComponentName(pkg, clsName),
                            0,
                            clone.userId
                        ) != null
                    }.getOrDefault(false)
                    sb.append("কম্পোনেন্ট রেজোলভারে: ${if (aiFound) "আছে" else "নেই"}\n")
                } else {
                    sb.append("মূল অ্যাক্টিভিটি: পাওয়া যায়নি\n")
                }
                val libDir = runCatching { BEnvironment.getAppLibDir(pkg) }.getOrNull()
                val sos = libDir?.listFiles { f -> f.name.endsWith(".so") }
                    ?.map { it.name } ?: emptyList()
                sb.append("নেটিভ লাইব্রেরি: ${if (sos.isEmpty()) "নেই" else sos.size.toString() + "টি — " + sos.take(5).joinToString(", ")}\n")
                if (pkg == "com.android.chrome") {
                    sb.append("libchrome.so: ${if (sos.contains("libchrome.so")) "আছে" else "নেই"}\n")
                }
                val v = when {
                    !installed -> "ইনস্টল হয়নি"
                    intent == null && !aiFound -> "কম্পোনেন্ট রেজোলভারে নেই — ইনস্টল/রেজিস্ট্রেশন সমস্যা"
                    intent == null && aiFound -> "লঞ্চ intent মেলে না, কিন্তু কম্পোনেন্ট আছে — ফলব্যাক লঞ্চ ব্যবহার হচ্ছে"
                    else -> "লঞ্চ intent ঠিক আছে"
                }
                sb.append("রায়: $v\n")
                verdicts.add("$pkg: $v")
                // ট্রেস বিশ্লেষণ — পুরো ট্রেস খুঁজো (শুধু শেষাংশ নয়),
                // নইলে আগের অ্যাপগুলোকে ভুল করে "bind হয়নি" বলা হয়।
                if (trace.isNotEmpty()) {
                    val hasBind = trace.contains("handleBindApplication done: $pkg")
                    val hasCreateStart = clsName != null &&
                        trace.contains("callActivityOnCreate start: $clsName")
                    val hasCreateDone = clsName != null &&
                        trace.contains("callActivityOnCreate done: $clsName")
                    val hasResume = clsName != null &&
                        trace.contains("callActivityOnResume: $clsName")
                    val t = when {
                        !hasBind -> "ট্রেস: bind হয়নি — bind-এর আগেই প্রসেস মারা গেছে"
                        hasCreateStart && !hasCreateDone -> "ট্রেস: onCreate শুরু হয়েছে কিন্তু শেষ হয়নি — onCreate-এ আটকে/ক্র্যাশ"
                        hasCreateDone && !hasResume -> "ট্রেস: onCreate শেষ হয়েছে কিন্তু onResume হয়নি — অ্যাক্টিভিটি বন্ধ হয়ে গেছে"
                        hasResume -> "ট্রেস: onResume হয়েছে — অ্যাক্টিভিটি চালু হয়েছিল"
                        else -> "ট্রেস: bind হয়েছে, কিন্তু অ্যাক্টিভিটি শুরু হয়নি"
                    }
                    sb.append("$t\n")
                    verdicts.add("$pkg ট্রেস: $t")
                }
            }
            val crashes = ctx.cacheDir.listFiles { f ->
                f.name.startsWith("last_crash") && f.name.endsWith(".txt")
            }?.sortedByDescending { it.lastModified() }?.take(3) ?: emptyList()
            sb.append("\n--- ক্র্যাশ রিপোর্ট (সবগুলোর পুরো লেখা) ---\n")
            if (crashes.isEmpty()) {
                sb.append("কোনো জাভা ক্র্যাশ রিপোর্ট নেই (নেটিভ ক্র্যাশ হলে রিপোর্ট থাকে না)\n")
            } else {
                for (f in crashes) {
                    // শুধু প্রথম লাইন নয় — পুরো ক্র্যাশের লেখা দাও, যাতে এক রিপোর্টেই
                    // সব সমস্যার কারণ দেখা যায় (প্রতি ফাইলে প্রথম 2500 অক্ষর)
                    sb.append("\n[${f.name}]\n")
                    sb.append(f.readText().take(2500))
                    sb.append("\n")
                }
            }
            sb.append("\n=== সারাংশ ===\n")
            if (verdicts.isEmpty()) {
                sb.append("কোনো ক্লোন নেই — প্রথমে একটি অ্যাপ ক্লোন করো।\n")
            } else {
                sb.append(verdicts.joinToString("\n") + "\n")
            }
            // ডায়াগনসিস মার্কার — Chromium renderer-এর মৃত্যু/ফিক্সের লাইন
            // (System linker, child process died, kept alive, RELRO) — ট্রেসের
            // শেষাংশে না থাকলেও এগুলো দেখা যাবে, যাতে রিপোর্ট পাঠালেই বোঝা যায়
            // ফিক্স চলেছিল কিনা আর renderer কখন মরল।
            val diagKeywords = listOf("System linker", "child process died", "kept alive", "RELRO disabled", "isolated rebind", "chromium child RELRO", "linker lookup", "LibraryLoader API", "Linker API", "sUseLinker", "splits:", "SplitCompat", "addDexPath")
            val diagLines = trace.lines().filter { line -> diagKeywords.any { k -> line.contains(k) } }.takeLast(20)
            sb.append("\n--- ডায়াগনসিস লাইন (renderer) ---\n")
            sb.append(if (diagLines.isEmpty()) "কোনো ডায়াগনসিস লাইন নেই\n" else diagLines.joinToString("\n") + "\n")
            sb.append("\n--- ট্রেস (শেষাংশ) ---\n")
            sb.append(if (traceTail.isEmpty()) "ট্রেস নেই\n" else traceTail + "\n")
            withContext(Dispatchers.Main) { reportText = sb.toString().take(12000) }
        }
    }

    // সব অ্যাপ চেকআপ: প্রতিটি অ্যাপ ক্লোন করে (না থাকলে), চালিয়ে দেখে, রিপোর্ট বানায়।
    var checkupRunning by remember { mutableStateOf(false) }
    var checkupProgress by remember { mutableStateOf("") }
    var checkupStop by remember { mutableStateOf(false) }
    val checkupLines = remember { mutableStateListOf<String>() }

    // আগের চেকআপের রিপোর্ট আর দেখানো হয় না — নতুন চেকআপ চালালে নতুন
    // রিপোর্ট আসে, পুরনোটা মুছে যায়। (ব্যবহারকারী: আগের রিপোর্ট যাতে না দেয়।)

    // পাস করা অ্যাপ আর চেক করা হয় না — শুধু সমস্যার অ্যাপ আর নতুন অ্যাপ চেক হয়।
    // recheckAll=true হলে পাস করা অ্যাপগুলোও আবার চেক হয়।
    fun runFullCheckup(recheckAll: Boolean = false) {
        if (checkupRunning) return
        val passed = if (recheckAll) {
            savePassedPkgs(ctx, emptySet())
            mutableSetOf<String>()
        } else {
            readPassedPkgs(ctx)
        }
        val allTargets = apps.filter { !isGmsSystemPackage(it.pkg) }
        val skippedPassed = if (recheckAll) 0 else allTargets.count { it.pkg in passed }
        val targets = if (recheckAll) allTargets
        else allTargets.filter { it.pkg !in passed }
        if (targets.isEmpty()) {
            if (skippedPassed > 0) {
                toast("সব অ্যাপই আগে পাস করেছে — আবার চেক করতে 'সব আবার চেক করো' চাপুন")
            } else {
                toast("চেক করার মতো কোনো অ্যাপ নেই")
            }
            return
        }
        checkupRunning = true
        checkupStop = false
        checkupLines.clear()
        checkupProgress = "শুরু হচ্ছে…"
        // আগের চেকআপের ফাইল মুছে নতুন করে শুরু করো
        runCatching { java.io.File(ctx.cacheDir, "checkup_report.txt").delete() }
        scope.launch(Dispatchers.IO) {
            val pm = BlackBoxCore.getBPackageManager()
            var okCount = 0
            var failCount = 0
            var skipCount = 0
            for ((idx, app) in targets.withIndex()) {
                if (checkupStop) break
                withContext(Dispatchers.Main) {
                    checkupProgress = "${app.label} (${idx + 1}/${targets.size})"
                }
                val pkg = app.pkg
                // ক্লোন না থাকলে ক্লোন করো
                val already = clones.any { it.pkg == pkg }
                var cloned = already
                if (!already) {
                    val uid = Engine.current.nextUserId(pkg)
                    if (uid < 0) {
                        val line = "⏭️ ${app.label}: ক্লোন সংখ্যা পূর্ণ — বাদ"
                        appendCheckupLine(ctx, line)
                        withContext(Dispatchers.Main) {
                            checkupLines.add(line)
                        }
                        skipCount++
                        continue
                    }
                    val r = Engine.current.installClone(pkg, uid)
                    cloned = r.isSuccess
                    if (cloned) {
                        val nc = CloneId(pkg, uid)
                        withContext(Dispatchers.Main) {
                            if (!clones.contains(nc)) { clones.add(nc); persist() }
                        }
                    }
                }
                if (!cloned) {
                    val line = "❌ ${app.label}: ক্লোন হয়নি"
                    appendCheckupLine(ctx, line)
                    withContext(Dispatchers.Main) {
                        checkupLines.add(line)
                    }
                    failCount++
                    continue
                }
                // মূল অ্যাক্টিভিটির নাম
                val pkgInfo = runCatching {
                    pm.getPackageInfo(
                        pkg,
                        android.content.pm.PackageManager.GET_ACTIVITIES,
                        0
                    )
                }.getOrNull()
                val target = pickLauncherActivity(pkgInfo?.activities)
                // স্মার্ট রিপোর্টের মতোই: intent-এর কম্পোনেন্ট (alias হলে
                // তার target) — নইলে Chrome-কে ভুল করে "শুরু হয়নি" বলে।
                val launchIntentCls = runCatching {
                    pm.getLaunchIntentForPackage(pkg, 0)?.component?.className
                }.getOrNull()
                val clsName = resolveTraceActivityClass(
                    ctx, launchIntentCls, pkg, target
                )
                // ট্রেসের শুরুর অবস্থান
                val traceFile = java.io.File(ctx.cacheDir, "init_trace.txt")
                val traceBefore = runCatching { traceFile.readText().length }.getOrDefault(0)
                // চালাও
                val lr = Engine.current.launchClone(pkg, 0)
                val verdict: String
                if (lr.isFailure) {
                    verdict = "❌ ${app.label}: লঞ্চ কল ব্যর্থ (${lr.exceptionOrNull()?.message ?: "?"})"
                    failCount++
                } else {
                    kotlinx.coroutines.delay(4000)
                    val newTrace = runCatching {
                        val full = traceFile.readText()
                        if (full.length > traceBefore) full.substring(traceBefore) else ""
                    }.getOrDefault("")
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                        as android.app.ActivityManager
                    val alive = am.runningAppProcesses
                        ?.any { it.processName.startsWith("${ctx.packageName}:p") } == true
                    val hasResume = clsName != null &&
                        newTrace.contains("callActivityOnResume: $clsName")
                    val hasDone = clsName != null &&
                        newTrace.contains("callActivityOnCreate done: $clsName")
                    val hasStart = clsName != null &&
                        newTrace.contains("callActivityOnCreate start: $clsName")
                    val hasBind = newTrace.contains("handleBindApplication done: $pkg")
                    verdict = when {
                        hasResume -> "✅ ${app.label}: চালু হয়েছে"
                        hasDone -> "⚠️ ${app.label}: onCreate হয়েছে, onResume হয়নি"
                        hasStart -> "❌ ${app.label}: onCreate-এ আটকেছে/ক্র্যাশ"
                        hasBind -> "❌ ${app.label}: bind হয়েছে, অ্যাক্টিভিটি শুরু হয়নি"
                        alive -> "⚠️ ${app.label}: প্রসেস চলছে কিন্তু অ্যাক্টিভিটি দেখা যায়নি"
                        else -> "❌ ${app.label}: চালু হয়নি (প্রসেস মারা গেছে)"
                    }
                    if (verdict.startsWith("✅")) okCount++
                    else if (!verdict.startsWith("⚠️")) failCount++
                }
                // পাস করা অ্যাপ মনে রাখো (পরের চেকআপে বাদ যাবে);
                // ব্যর্থ হলে তালিকা থেকে সরাও, যাতে পরের বার আবার চেক হয়।
                if (verdict.startsWith("✅")) {
                    if (passed.add(pkg)) savePassedPkgs(ctx, passed)
                } else if (passed.remove(pkg)) {
                    savePassedPkgs(ctx, passed)
                }
                appendCheckupLine(ctx, verdict)
                withContext(Dispatchers.Main) { checkupLines.add(verdict) }
                // এই অ্যাপের প্রসেস থামাও, পরেরটার জন্য পরিষ্কার রাখো
                runCatching { BlackBoxCore.get().stopPackage(pkg, 0) }
            }
            val stopped = checkupStop
            if (!stopped) appendCheckupLine(ctx, "=== চেকআপ শেষ ===")
            val summary = buildString {
                append("=== CloneMe সব অ্যাপ চেকআপ ===\n")
                append("সময়: ${java.util.Date()}\n")
                append("মোট: ${targets.size}টি অ্যাপ")
                if (skippedPassed > 0) append(" (+ পাস করা ${skippedPassed}টি বাদ দেওয়া হয়েছে)")
                append("\n")
                append("✅ চালু: $okCount | ❌ ব্যর্থ: $failCount | ⏭️ বাদ: $skipCount\n")
                if (stopped) append("(মাঝপথে থামানো হয়েছে)\n")
                append("\n")
                append(checkupLines.joinToString("\n"))
                append("\n")
            }
            withContext(Dispatchers.Main) {
                checkupRunning = false
                checkupProgress = if (stopped) "থামানো হয়েছে" else "শেষ — ✅ $okCount, ❌ $failCount"
                reportText = summary.take(5000)
            }
        }
    }

    // Google Play সার্ভিস ভার্চুয়াল পরিবেশে ইনস্টল (ইন্টারনেট অ্যাপের জন্য)
    fun doInstallGms() {
        if (gmsState == "installing") return
        gmsState = "installing"
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { BlackBoxCore.get().installGms(0).success }
                .getOrDefault(false)
            withContext(Dispatchers.Main) {
                gmsState = if (ok) "installed" else "failed"
                toast(
                    if (ok) "Google সার্ভিস ইনস্টল হয়েছে — এবার ক্লোনগুলো আবার চালু করে দেখো"
                    else "Google সার্ভিস ইনস্টল ব্যর্থ — আবার চেষ্টা করো"
                )
            }
        }
    }

    fun doUninstallGms() {
        gmsState = "installing"
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { BlackBoxCore.get().uninstallGms(0) }
                .getOrDefault(false)
            // পুনরায় অবস্থা দেখো
            val nowInstalled = runCatching {
                BlackBoxCore.get().isInstallGms(0)
            }.getOrDefault(true)
            withContext(Dispatchers.Main) {
                gmsState = if (!nowInstalled) "ready" else "installed"
                toast(
                    if (!nowInstalled) "Google সার্ভিস সরিয়ে ফেলা হয়েছে"
                    else "সরানো ব্যর্থ — আবার চেষ্টা করো"
                )
            }
        }
    }

    fun doDelete(clone: CloneId) {
        if (busyClone != null) return
        busyClone = clone
        scope.launch(Dispatchers.IO) {
            val result = Engine.current.uninstallClone(clone.pkg, clone.userId)
            withContext(Dispatchers.Main) {
                busyClone = null
                result.fold(
                    onSuccess = {
                        clones.remove(clone)
                        persist()
                        toast("ক্লোন মুছে ফেলা হয়েছে")
                    },
                    onFailure = {
                        // ইঞ্জিনে ডিলিট না থাকলে শুধু লিস্ট থেকে সরাও
                        clones.remove(clone)
                        persist()
                        toast("তালিকা থেকে সরানো হয়েছে")
                    }
                )
            }
        }
    }

    // হোম-স্ক্রিন শর্টকাট থেকে এলে তালিকা লোডের পর সরাসরি চালু করো
    // (পুরনো শর্টকাটের জন্য, userId 0 ধরে)
    LaunchedEffect(loading) {
        if (!loading) {
            val lp = MainActivity.launchPkg
            if (lp != null) {
                MainActivity.launchPkg = null
                doLaunch(CloneId(lp, 0))
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        // পুরো স্ক্রিন স্ক্রলযোগ্য — লম্বা কার্ড (চেকআপের ফলাফল/রিপোর্ট) যাতে
        // ট্যাব আর অ্যাপের তালিকাকে স্ক্রিনের বাইরে ঠেলে দিতে না পারে।
        // (আগে বাইরের Column স্ক্রল হতো না, তাই অ্যাপের তালিকা দেখা যেত না।)
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            // ব্র্যান্ড হেডার
            HeaderCard(cloneCount = clones.size)

            // Crash রিপোর্ট থাকলে দেখাও — সবগুলো, প্রসেসের নামসহ
            crashReports.forEach { (fileName, report) ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "⚠️ সমস্যা হয়েছিল: $fileName",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            report,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                        )
                        Row {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("crash", "$fileName\n$report"))
                                toast("রিপোর্ট কপি হয়েছে")
                            }) { Text("কপি করুন") }
                            TextButton(onClick = {
                                ctx.cacheDir.listFiles { f ->
                                    f.name.startsWith("last_crash") && f.name.endsWith(".txt")
                                }?.forEach { it.delete() }
                                crashReports = emptyList()
                            }) { Text("সব মুছে ফেলুন") }
                        }
                    }
                }
            }

            // লগ দেখুন: Chrome-এর প্রসেস মারা গেলে এখান থেকে কারণ জানা যাবে
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { captureLog() }) { Text("📋 লগ দেখুন") }
                TextButton(onClick = { captureTrace() }) { Text("📜 ট্রেস দেখুন") }
                TextButton(onClick = { generateReport() }) { Text("🔍 স্মার্ট রিপোর্ট") }
            }
            logText?.let { log ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "📋 সিস্টেম লগ (ক্র্যাশের কারণ)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            log,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        )
                        Row {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("log", log))
                                toast("লগ কপি হয়েছে")
                            }) { Text("কপি করুন") }
                            TextButton(onClick = { logText = null }) { Text("বন্ধ করুন") }
                        }
                    }
                }
            }
            traceText?.let { trace ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "📜 স্টার্টআপ ট্রেস (Chrome কতদূর গেলো)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            trace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        )
                        Row {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("trace", trace))
                                toast("ট্রেস কপি হয়েছে")
                            }) { Text("কপি করুন") }
                            TextButton(onClick = { traceText = null }) { Text("বন্ধ করুন") }
                        }
                    }
                }
            }
            reportText?.let { report ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "🔍 স্মার্ট রিপোর্ট (অ্যাপ নিজে যাচাই করেছে)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            report,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState())
                        )
                        Row {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("report", report))
                                toast("রিপোর্ট কপি হয়েছে")
                            }) { Text("কপি করুন") }
                            TextButton(onClick = { reportText = null }) { Text("বন্ধ করুন") }
                        }
                    }
                }
            }
            // সব অ্যাপ চেকআপ: নিজেই সব অ্যাপ ক্লোন করে চালিয়ে দেখে, রিপোর্ট দেয়
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "🔍 সব অ্যাপ চেকআপ",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        "অ্যাপ নিজেই সব অ্যাপ ক্লোন করে একটা একটা চালিয়ে দেখবে, তারপর রিপোর্ট দেবে। চলাকালীন অ্যাপগুলো একটা একটা করে খুলবে — ফোনটা কিছুক্ষণ ব্যবহার করো না। আগে পাস করা অ্যাপগুলো আর চেক করা হবে না — শুধু সমস্যার অ্যাপ আর নতুন অ্যাপ চেক হবে।",
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Button(
                            onClick = { runFullCheckup() },
                            enabled = !checkupRunning
                        ) { Text("চেকআপ শুরু করো") }
                        // পাস করা অ্যাপগুলোও আবার চেক করতে চাইলে (ইঞ্জিন বদলের পর)
                        if (!checkupRunning) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { runFullCheckup(recheckAll = true) }) {
                                Text("সব আবার চেক করো")
                            }
                        }
                        if (checkupRunning) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { checkupStop = true }) { Text("থামান") }
                        }
                    }
                    if (checkupRunning || checkupProgress.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(checkupProgress, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (checkupLines.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            checkupLines.joinToString("\n"),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            if (!Engine.current.isReady()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text(
                        "সতর্কতা: ভার্চুয়ালাইজেশন ইঞ্জিন যুক্ত নেই, তাই ক্লোন কাজ করবে না।",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Google Play সার্ভিস — ইনস্টল থাকলে বড় কার্ড নয়, এক লাইনের ছোট স্ট্যাটাস
            if (gmsState == "installed") {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "✓ Google Play সার্ভিস সক্রিয়",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { doUninstallGms() }) {
                        Text("সরিয়ে ফেলুন", fontSize = 12.sp)
                    }
                }
            }
            if (gmsState == "ready" || gmsState == "installing" || gmsState == "failed") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (gmsState == "failed")
                            MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Google Play সার্ভিস",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                if (gmsState == "failed")
                                    "ইনস্টল ব্যর্থ হয়েছে — আবার চেষ্টা করুন"
                                else "ইন্টারনেট অ্যাপ (Facebook, ব্রাউজার) চালাতে এটা দরকার হতে পারে",
                                fontSize = 12.sp
                            )
                        }
                        if (gmsState == "installing") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Button(onClick = { doInstallGms() }) {
                                Text(if (gmsState == "failed") "আবার চেষ্টা" else "ইনস্টল করুন")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ব্যাটারি অপটিমাইজেশন কার্ড — নোটিফিকেশন বাঁচিয়ে রাখতে
            if (!batteryIgnored) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ব্যাকগ্রাউন্ড চালু রাখুন",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                "মেসেঞ্জারের নোটিফিকেশন ঠিকমতো পেতে ব্যাটারি সেভিং থেকে CloneMe-কে ছাড় দিন",
                                fontSize = 12.sp
                            )
                        }
                        Button(onClick = { doRequestBatteryExemption() }) {
                            Text("ছাড় দিন")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("অ্যাপ খুঁজুন") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = BrandBlue,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[tab]),
                        color = BrandBlue
                    )
                }
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("সব অ্যাপ", fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("আমার ক্লোন (${clones.size})", fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                )
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = BrandBlue)
                        Text("অ্যাপের তালিকা লোড হচ্ছে…", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            } else if (tab == 0) {
                val filtered = apps.filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                        it.pkg.contains(query, ignoreCase = true)
                }
                if (filtered.isEmpty()) {
                    EmptyHint("কোনো অ্যাপ পাওয়া যায়নি")
                } else {
                    // তালিকা আবার LazyColumn (লেজি) — সব রো একসাথে কম্পোজ হলে
                    // মূল থ্রেড ভারী হয়ে ANR ("CloneMe isn't responding") দেয়।
                    // নির্দিষ্ট উচ্চতা দেওয়া হয়েছে যাতে বাইরের স্ক্রল-করা
                    // Column-এর ভেতরে LazyColumn কাজ করতে পারে।
                    LazyColumn(
                        modifier = Modifier.padding(vertical = 4.dp).height(600.dp)
                    ) {
                        items(filtered, key = { it.pkg }) { app ->
                            val n = clones.count { it.pkg == app.pkg }
                            AppRow(
                                app = app,
                                buttonLabel = if (n > 0) "ক্লোন ($n)" else "ক্লোন",
                                busy = busyClone?.pkg == app.pkg,
                                buttonEnabled = busyClone == null
                            ) { doClone(app) }
                        }
                    }
                }
            } else {
                if (clones.isEmpty()) {
                    EmptyHint("এখনো কোনো ক্লোন নেই।\n\"সব অ্যাপ\" থেকে ক্লোন করুন।")
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(vertical = 4.dp).height(600.dp)
                    ) {
                        items(
                            clones.sortedWith(compareBy({ it.pkg }, { it.userId })),
                            key = { it.toString() }
                        ) { clone ->
                            val app = apps.find { it.pkg == clone.pkg }
                            if (app != null) {
                                val siblings = clones.filter { it.pkg == clone.pkg }
                                    .sortedBy { it.userId }
                                val idx = siblings.indexOf(clone) + 1
                                val title = if (siblings.size > 1)
                                    "${app.label} $idx" else app.label
                                CloneRow(
                                    app = app,
                                    title = title,
                                    busy = busyClone == clone,
                                    onLaunch = { doLaunch(clone) },
                                    onDelete = { doDelete(clone) },
                                    onShortcut = {
                                        (ctx as? MainActivity)?.pinCloneShortcut(clone, app, title)
                                            ?: toast("শর্টকাট যোগ করা যায়নি")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderCard(cloneCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(BrandNavy, BrandDeepBlue))
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "লোগো",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "CloneMe",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "এক ফোনে দুটো অ্যাকাউন্ট",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$cloneCount",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("ক্লোন", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, modifier = Modifier.padding(24.dp), color = Color.Gray)
    }
}

@Composable
fun AppRow(
    app: AppInfo,
    buttonLabel: String,
    busy: Boolean,
    buttonEnabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
            )
            Text(
                app.label,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp, color = BrandBlue)
            } else {
                Button(
                    onClick = onClick,
                    enabled = buttonEnabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) { Text(buttonLabel, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
fun CloneRow(
    app: AppInfo,
    title: String? = null,
    busy: Boolean,
    onLaunch: () -> Unit,
    onDelete: () -> Unit,
    onShortcut: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
            )
            Text(
                title ?: app.label,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp, color = BrandBlue)
            } else {
                Button(
                    onClick = onLaunch,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandCyan)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("চালু", fontSize = 13.sp)
                }
                IconButton(onClick = onShortcut) {
                    Icon(Icons.Filled.Home, contentDescription = "হোম স্ক্রিনে যোগ করুন", tint = BrandBlue)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "মুছুন", tint = Color(0xFFB3261E))
                }
            }
        }
    }
}
