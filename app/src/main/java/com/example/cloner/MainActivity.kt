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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap, val isSystemOnly: Boolean = false)

private const val PREFS = "cloner_prefs"
private const val KEY_CLONES = "clones"
private const val KEY_CHECKUP_PASSED = "checkup_passed"
private const val KEY_CHECKUP_FAILED = "checkup_failed"
private const val KEY_BATTERY_ASKED = "battery_asked"
private const val KEY_CUSTOM_LABELS = "custom_labels"

/** ব্র্যান্ড রঙ — CloneMe লোগোর সাথে মিলিয়ে */
private val BrandBlue = Color(0xFF1B9BF5)
private val AccentBlue = Color(0xFF0174B7)
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
                val pkgName = ri.activityInfo.packageName
                // v1.0.100: শুধু ইনস্টল করা অ্যাপ দেখাবে — ফোনের সিস্টেম অ্যাপ লুকাবে।
                // সিস্টেম অ্যাপ = FLAG_SYSTEM আছে কিন্তু FLAG_UPDATED_SYSTEM_APP নেই
                // (কখনো আপডেট হয়নি — কারখানার সংস্করণ)। আপডেট হওয়া সিস্টেম অ্যাপ
                // (Chrome, Facebook ইত্যাদি) ইনস্টলড হিসেবেই গণ্য হয়, দেখাবে।
                // Chrome সবসময় দেখাবে — সবচেয়ে জরুরি অ্যাপ।
                val aiFlags = ri.activityInfo.applicationInfo?.flags ?: 0
                val isSystemOnly = pkgName != "com.android.chrome" &&
                    (aiFlags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (aiFlags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    pkg = pkgName,
                    icon = ri.loadIcon(pm).toBitmap(144, 144).asImageBitmap(),
                    isSystemOnly = isSystemOnly
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

// v1.0.97: চেকআপে ব্যর্থ অ্যাপের প্যাকেজ নাম — এগুলো তালিকায় দেখানো
// হবে না (শুধু চলবে এমন অ্যাপই দেখাবে)। পাস করলে তালিকা থেকে সরবে।
private fun readFailedPkgs(ctx: Context): MutableSet<String> =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_CHECKUP_FAILED, emptySet())?.toMutableSet() ?: mutableSetOf()

private fun saveFailedPkgs(ctx: Context, failed: Set<String>) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_CHECKUP_FAILED, failed).apply()
}

// v1.0.100: ক্লোনের নিজের নাম (লং-প্রেস মেনু → নাম বদলান) — "pkg|userId|নাম" আকারে।
private fun readCustomLabels(ctx: Context): Map<CloneId, String> {
    val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_CUSTOM_LABELS, emptySet()).orEmpty()
    val out = mutableMapOf<CloneId, String>()
    for (entry in raw) {
        val parts = entry.split("|", limit = 3)
        if (parts.size == 3) {
            val uid = parts[1].toIntOrNull() ?: continue
            out[CloneId(parts[0], uid)] = parts[2]
        }
    }
    return out
}

private fun saveCustomLabels(ctx: Context, labels: Map<CloneId, String>) {
    val raw = labels.entries.map { "${it.key.pkg}|${it.key.userId}|${it.value}" }.toSet()
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_CUSTOM_LABELS, raw).apply()
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
            Toast.makeText(this, "Shortcuts are not supported on this phone", Toast.LENGTH_LONG).show()
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
    var tab by remember { mutableIntStateOf(1) } // v1.0.100: প্রথমে ক্লোন হোম (Multiple App-এর মতো); 0 = অ্যাপ বাছাই
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
    // v1.0.97: চেকআপে ব্যর্থ অ্যাপ — তালিকায় দেখানো হবে না। লোড হয়
    // LaunchedEffect(Unit)-এ, আপডেট হয় runFullCheckup-এ।
    var failedPkgs by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v1.0.97: লুকানো (ব্যর্থ/অসমর্থিত) অ্যাপগুলোও দেখানোর টগল — ডিফল্টে বন্ধ।
    var showHidden by remember { mutableStateOf(false) }
    // v1.0.100: গ্রিডে টিক দেওয়া অ্যাপ — "ক্লোন করো" বোতামে একসাথে ক্লোন হবে।
    var selectedPkgs by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v1.0.100: ক্লোন ট্যাবের এডিট মোড (Multiple App-এর পেন্সিল) + ডায়ালগ।
    var editMode by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CloneId?>(null) }
    var pendingShortcut by remember { mutableStateOf<CloneId?>(null) }
    // v1.0.100: স্ক্রিনশটের মতো হোম — হোম-সার্চ, ⋮ মেনু, লং-প্রেস মেনু, নাম বদলান, ডেটা মুছুন।
    var homeSearch by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingMenu by remember { mutableStateOf<CloneId?>(null) }
    var pendingRename by remember { mutableStateOf<CloneId?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingClearData by remember { mutableStateOf<CloneId?>(null) }
    var customLabels by remember { mutableStateOf<Map<CloneId, String>>(emptyMap()) }
    var checkupDialogOpen by remember { mutableStateOf(false) }

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
        // v1.0.100: GMS নিজে থেকেই ইনস্টল হবে (Multiple App-এর মতো — আলাদা বোতাম নেই)।
        if (gmsState == "ready") {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching { BlackBoxCore.get().installGms(0).success }
                    .getOrDefault(false)
                withContext(Dispatchers.Main) {
                    gmsState = if (ok) "installed" else "failed"
                }
            }
        }
        val parsed = saved.mapNotNull { CloneId.parse(it) }
            .filter { !isGmsSystemPackage(it.pkg) }
        clones.addAll((parsed + engineClones).distinct())
        apps.addAll(loaded)
        // v1.0.97: ব্যর্থ অ্যাপের তালিকা পড়ে নাও — এরা তালিকায় দেখাবে না
        failedPkgs = withContext(Dispatchers.IO) { readFailedPkgs(ctx) }
        // v1.0.100: ক্লোনের নিজের নামগুলো পড়ে নাও
        customLabels = withContext(Dispatchers.IO) { readCustomLabels(ctx) }
        loading = false
    }

    // ব্যাটারি অপটিমাইজেশন অবস্থা দেখো (নোটিফিকেশনের জন্য)
    // v1.0.100: ছাড়ের সিস্টেম ডায়ালগ প্রথমবার নিজে থেকেই আসবে — আলাদা কার্ড/বোতাম নেই।
    LaunchedEffect(Unit) {
        batteryIgnored = withContext(Dispatchers.IO) {
            runCatching {
                val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }.getOrDefault(true)
        }
        val asked = withContext(Dispatchers.IO) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BATTERY_ASKED, false)
        }
        if (!batteryIgnored && !asked) {
            withContext(Dispatchers.IO) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
            }
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${ctx.packageName}")
                )
                ctx.startActivity(intent)
            } catch (_: Exception) {
                toast("Settings → Battery → CloneMe → Unrestricted")
            }
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
            toast("Settings → Battery → CloneMe → Unrestricted")
        }
    }

    fun persist() = saveClones(ctx, clones.map { it.toString() }.toSet())

    fun doClone(app: AppInfo) {
        if (busyClone != null) return
        scope.launch(Dispatchers.IO) {
            val uid = Engine.current.nextUserId(app.pkg)
            if (uid < 0) {
                withContext(Dispatchers.Main) {
                    toast("Maximum number of clones reached")
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
                        toast("${app.label} cloned" + if (n > 1) " ($n)" else "")
                    },
                    onFailure = { toast(it.message ?: "Couldn't clone") }
                )
            }
        }
    }

    // v1.0.100: টিক দেওয়া সব অ্যাপ একসাথে ক্লোন করো — একটা একটা করে,
    // ক্রমানুসারে (ইঞ্জিন একসাথে একটাই ইনস্টল সামলায়)।
    fun doCloneSelected(selected: List<AppInfo>) {
        if (busyClone != null || selected.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            var ok = 0
            var fail = 0
            for (app in selected) {
                val uid = Engine.current.nextUserId(app.pkg)
                if (uid < 0) { fail++; continue }
                val newClone = CloneId(app.pkg, uid)
                withContext(Dispatchers.Main) { busyClone = newClone }
                val result = Engine.current.installClone(app.pkg, uid)
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = {
                            if (!clones.contains(newClone)) {
                                clones.add(newClone)
                                persist()
                            }
                            ok++
                        },
                        onFailure = { fail++ }
                    )
                }
            }
            withContext(Dispatchers.Main) {
                busyClone = null
                selectedPkgs = emptySet()
                toast("${ok} cloned" + if (fail > 0) ", $fail failed" else "")
            }
        }
    }

    // v1.0.98: ক্লোন চালু করার পর ৩০ সেকেন্ড ধরে দেখো — "CloneMe — Chrome
    // চলছে" ফোরগ্রাউন্ড নোটিফিকেশনটা আসল সিস্টেমে পোস্ট হয়েছে কিনা।
    // মেইন প্রসেস থেকে আসল NotificationManager-কে জিজ্ঞাসা করা হয়
    // (KeepAliveService-এর চ্যানেল আসল সিস্টেমে আছে — v1.0.95-এ প্রমাণিত)।
    // ফলাফল fgs_check.txt-এ লেখা হয়, স্মার্ট রিপোর্টে দেখা যায়।
    fun startFgsCheck() {
        scope.launch(Dispatchers.IO) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val wantId = ctx.packageName.hashCode()
            var seen = false
            var firstSeenAt = ""
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 30_000) {
                val list = runCatching { nm.activeNotifications }.getOrNull() ?: emptyArray()
                if (list.any { it.id == wantId }) {
                    seen = true
                    firstSeenAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date())
                    break
                }
                kotlinx.coroutines.delay(2000)
            }
            val out = buildString {
                append("সময়: ${java.util.Date()}\n")
                append("Chrome ফোরগ্রাউন্ড নোটিফিকেশন দেখা গেছে: ")
                append(if (seen) "হ্যাঁ ($firstSeenAt)\n" else "না (৩০ সেকেন্ডে একবারও না)\n")
                val list = runCatching { nm.activeNotifications }.getOrNull() ?: emptyArray()
                append("সক্রিয় নোটিফিকেশন: ${list.size}টি\n")
                for (sb in list) {
                    val title = runCatching {
                        sb.notification.extras.getString(android.app.Notification.EXTRA_TITLE)
                    }.getOrDefault("?")
                    append("- id=${sb.id} channel=${sb.notification.channelId} title=$title\n")
                }
            }
            runCatching { java.io.File(ctx.cacheDir, "fgs_check.txt").writeText(out) }
        }
    }

    fun doLaunch(clone: CloneId) {
        if (busyClone != null) return
        busyClone = clone
        scope.launch(Dispatchers.IO) {
            val result = Engine.current.launchClone(clone.pkg, clone.userId)
            withContext(Dispatchers.Main) {
                busyClone = null
                result.onFailure { toast(it.message ?: "Couldn't launch") }
                result.onSuccess {
                    // v1.0.98: ফোরগ্রাউন্ড নোটিফিকেশন আসলে পোস্ট হলো কিনা যাচাই শুরু করো।
                    startFgsCheck()
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
                                    "Process for ${clone.pkg} didn't start / died — please copy the trace and send it",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                // ট্রেস কার্ড নিজে থেকেই দেখাও — ব্যবহারকারীকে আর বোতাম চাপতে হবে না
                                scope.launch(Dispatchers.IO) {
                                    val text = runCatching {
                                        val f = java.io.File(ctx.cacheDir, "init_trace.txt")
                                        if (!f.exists()) "No trace file (init_trace.txt not found)"
                                        else f.readText().takeLast(4000).ifEmpty { "Trace file is empty" }
                                    }.getOrDefault("Couldn't read trace")
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
                    "com.android.chrome", "has died", "win death", "vm exiting"
                )
                val lines = out.lines().filter { line ->
                    val low = line.lowercase()
                    !low.contains("packageparser") &&
                    keys.any { low.contains(it) }
                }.takeLast(60)
                if (lines.isEmpty()) "No crash lines in log (log empty or log reading blocked on this phone)"
                else lines.joinToString("\n").take(4000)
            }.getOrDefault("Couldn't read log — failed to run logcat")
            withContext(Dispatchers.Main) { logText = text }
        }
    }

    // ট্রেস দেখা: স্টাব প্রসেস কতদূর গেলো (bind → onCreate → onResume)
    // init_trace.txt-এ সব প্রসেসের লাইন জমা থাকে, মরার পরও ফাইল থাকে।
    fun captureTrace() {
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                val f = java.io.File(ctx.cacheDir, "init_trace.txt")
                if (!f.exists()) "No trace file (init_trace.txt not found)"
                else f.readText().takeLast(4000).ifEmpty { "Trace file is empty" }
            }.getOrDefault("Couldn't read trace")
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
            // v1.0.98: ফোরগ্রাউন্ড নোটিফিকেশন যাচাই — "CloneMe — Chrome
            // চলছে" নোটিফিকেশনটা আসল সিস্টেমে পোস্ট হয়েছে কিনা। ফলাফল
            // fgs_check.txt-এ থাকে (ক্লোন চালু করার পর ৩০ সেকেন্ড পোল করা হয়)।
            sb.append("\n--- ফোরগ্রাউন্ড নোটিফিকেশন যাচাই ---\n")
            val fgsFile = java.io.File(ctx.cacheDir, "fgs_check.txt")
            if (fgsFile.exists()) {
                sb.append(runCatching { fgsFile.readText() }.getOrDefault("পড়া যায়নি").take(1000) + "\n")
            } else {
                sb.append("এখনো যাচাই হয়নি — ক্লোন চালু করার পর ৩০ সেকেন্ডের মধ্যে ফলাফল লেখা হয়।\n")
            }
            // ডায়াগনসিস মার্কার — Chromium renderer-এর মৃত্যু/ফিক্সের লাইন
            // (System linker, child process died, kept alive, RELRO) — ট্রেসের
            // শেষাংশে না থাকলেও এগুলো দেখা যাবে, যাতে রিপোর্ট পাঠালেই বোঝা যায়
            // ফিক্স চলেছিল কিনা আর renderer কখন মরল। বিরল লাইনগুলো (setup/
            // transact) পুরো ট্রেস থেকে নাও — শেষ ২০-এ চাপা পড়ে যায় বলে
            // v1.0.82-84-তে "relroInfo nulled"/"transact code=" কখনো দেখা যায়নি।
            val diagKeywords = listOf("System linker", "child process died", "kept alive", "RELRO disabled", "isolated rebind", "chromium child RELRO", "linker lookup", "LibraryLoader API", "Linker API", "sUseLinker", "splits:", "SplitCompat", "addDexPath", "probe", "dexElements", "ContextUtils", "loader hierarchy", "RELRO blocked", "relroInfo nulled", "binder wrapped", "setupConnection rewrite failed", "transact code=", "no-sandbox", "IChildProcessArgs fields", "relro null failed", "stopService chromium", "foreground promoted", "foreground promote failed", "fileDescriptorInfos", "in-process-gpu")
            val rareKeywords = listOf("transact code=", "relroInfo nulled", "RELRO blocked", "no-sandbox", "setupConnection rewrite failed", "IChildProcessArgs fields", "relro null failed", "stopService chromium", "foreground promoted", "foreground promote failed", "fileDescriptorInfos", "in-process-gpu")
            val rareLines = trace.lines().filter { line -> rareKeywords.any { k -> line.contains(k) } }.takeLast(30)
            // v1.0.96 রিপোর্ট-স্বাস্থ্য: একই লাইন বারবার আসে (যেমন
            // "foreground promoted" ২২ বার, শুধু সময়/PID আলাদা) — distinct()
            // তাতে কাজ হয় না, কারণ সময় আলাদা বলে লাইনগুলো আলাদা স্ট্রিং।
            // তাই "] "-এর পরের বার্তা দিয়ে গুছিয়ে এক লাইনে count দেখাও
            // (×N) — জায়গা বাঁচে, "child process died"-এর মতো বিরল লাইন
            // আর চাপা পড়ে না। শেষের ২০টি আলাদা বার্তা নাও।
            val diagCandidates = rareLines + trace.lines().filter { line -> diagKeywords.any { k -> line.contains(k) } }.takeLast(20)
            val diagGrouped = diagCandidates.groupBy { line ->
                val idx = line.indexOf("] ")
                if (idx >= 0) line.substring(idx + 2) else line
            }
            val diagLines = diagGrouped.entries.toList().takeLast(20).map { (_, lines) ->
                val first = lines.first()
                if (lines.size > 1) "$first (×${lines.size})" else first
            }
            sb.append("\n--- ডায়াগনসিস লাইন (renderer) ---\n")
            sb.append(if (diagLines.isEmpty()) "কোনো ডায়াগনসিস লাইন নেই\n" else diagLines.joinToString("\n") + "\n")
            // টম্বস্টোন ফ্রেম: renderer SIGSEGV-র কল স্ট্যাক — কোন ফাংশনে
            // মরলো বোঝার একমাত্র সূত্র। লগ ভিউ ফ্রেমগুলো কেটে ফেলে, তাই
            // রিপোর্টেই ধরো (সংক্ষিপ্ত আকারে: ফ্রেম নম্বর, pc, লাইব্রেরি)।
            var deathLog = ""
            val tombstone = runCatching {
                // v1.0.92: সময়-সীমা logcat — v1.0.91-তে -t 5000 (লাইন-সীমা)
                // Chrome-এর verbose লগে মাত্র কয়েক সেকেন্ড ধরে; মৃত্যুর ৫১
                // সেকেন্ড পরের রিপোর্টে উইন্ডো পেরিয়ে গেছে, তাই "মৃত্যুর লগ"
                // একদম খালি ছিল। এখন রিপোর্টের ৩ মিনিট আগের সময় থেকে সব
                // লাইন নাও — যত লাইনই হোক, keyword ফিল্টার আউটপুট ছোট রাখবে।
                // Locale.US জরুরি — ডিফল্ট locale-এ সংখ্যা বাংলা হলে logcat
                // সময় পার্স করতে পারবে না। খালি এলে লাইন-সীমায় ফিরে যাও।
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.MINUTE, -3)
                val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(cal.time)
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", since))
                var logOut = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                if (logOut.isBlank()) {
                    val proc2 = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "5000"))
                    logOut = proc2.inputStream.bufferedReader().readText()
                    proc2.waitFor()
                }
                // v1.0.91: মৃত্যুর লগ আরও চওড়া — v1.0.90-তে মৃত্যুর কোনো লাইন
                // আসেনি (VM exiting / has died / killed কিছুই না), অথচ ৫টি
                // child মরেছে। দুটি ফাঁক ধরা পড়েছে: (১) "Killing" (LMK/
                // ActivityManager-এর কিল) keyword-এ ছিল না — "killed" দিয়ে
                // "Killing" ধরা যায় না; (২) ব্রাউজারের নিজের চোখে মৃত্যুটা
                // ("Render process exited") ধরা হয়নি। তাই keyword বাড়ানো
                // হলো, উইন্ডো 3000→5000, লাইন 25→40।
                val deathKeys = listOf("VM exiting", "has died", "win death",
                    "lowmemorykiller", "Force finishing", "stopService chromium",
                    "onDestroy chromium", "ChildProcessService", "Aw, Snap",
                    "fatal signal", "SIGSYS", "seccomp", "killed",
                    "Render process", "exited with", "terminated", "crashed",
                    "ChildProcessLauncher", "RenderProcessHost", "Killing",
                    "low memory", "renderer process", "onServiceDisconnected",
                    "child process died")
                // v1.0.96 রিপোর্ট-স্বাস্থ্য: "Creating new ChildProcessService"
                // লাইনটাই deathKeys-এ ("ChildProcessService") ধরা পড়ে —
                // v1.0.95-র রিপোর্টে ১৮টি একই লাইন মৃত্যুর লগ ভরে দিয়েছিল,
                // আসল খুনি দেখা যায়নি। এখন ওগুলো বাদ দিয়ে শুধু সংখ্যা দেখাও।
                val deathMatched = logOut.lines().filter { line ->
                    deathKeys.any { line.contains(it, ignoreCase = true) }
                }
                val creatingCount = deathMatched.count { it.contains("Creating new ChildProcessService") }
                val deathLines = deathMatched.filterNot { it.contains("Creating new ChildProcessService") }.takeLast(40)
                val deathSummary = if (creatingCount > 0)
                    listOf("ChildProcessService নতুন তৈরি: ${creatingCount} বার — renderer বারবার নতুন করে তৈরি হচ্ছে (churn); উপরের লাইনগুলোতেই আসল মৃত্যুর কারণ খুঁজতে হবে")
                else emptyList()
                deathLog = (deathSummary + deathLines).joinToString("\n")
                val frameRe = Regex("#(\\d+) pc ([0-9a-f]+)")
                val offRe = Regex("\\(offset 0x[0-9a-f]+\\)")
                val allFrames = logOut.lines().filter { frameRe.containsMatchIn(it) }
                // সবচেয়ে সাম্প্রতিক টম্বস্টোনটাই নাও — আগের টম্বস্টোনের লেজ
                // (#96/#97) মিশে যাওয়া ঠেকাতে শেষ "#00 pc" লাইন থেকে শুরু করো।
                val lastZero = allFrames.indexOfLast { it.contains("#00 pc ") }
                val recent = if (lastZero >= 0) allFrames.subList(lastZero, allFrames.size) else allFrames
                val frames = recent.mapNotNull { line ->
                    val m = frameRe.find(line) ?: return@mapNotNull null
                    val lib = runCatching {
                        val afterBang = line.substringAfterLast('!', "")
                        if (afterBang.isNotEmpty()) afterBang.substringBefore(' ').substringBefore('(')
                        else line.substringAfterLast('/').substringBefore(' ').substringBefore('(')
                    }.getOrDefault("?")
                    val off = offRe.find(line)?.value ?: ""
                    "#${m.groupValues[1]} pc ${m.groupValues[2]} $lib $off".trim()
                }.takeLast(100)
                // BuildId: #00 ফ্রেমের লাইন থেকেই নাও (libchrome.so-র) — পুরো
                // logcat-এর শেষ BuildId লাইন অন্য লাইব্রেরির (যেমন libc.so-র) হয়।
                val buildId = recent.firstOrNull()
                    ?.substringAfter("BuildId:", "")?.trim()?.substringBefore(")")?.take(40).orEmpty()
                if (frames.isEmpty()) "" else (if (buildId.isEmpty()) "" else "BuildId: $buildId\n") + frames.joinToString("\n")
            }.getOrDefault("")
            sb.append("\n--- টম্বস্টোন ফ্রেম (renderer SIGSEGV) ---\n")
            sb.append(if (tombstone.isEmpty()) "কোনো টম্বস্টোন ফ্রেম পাওয়া যায়নি\n" else tombstone + "\n")
            sb.append("\n--- মৃত্যুর লগ ---\n")
            sb.append(if (deathLog.isEmpty()) "মৃত্যুর কোনো লগ পাওয়া যায়নি\n" else deathLog + "\n")
            sb.append("\n--- ট্রেস (শেষাংশ) ---\n")
            sb.append(if (traceTail.isEmpty()) "ট্রেস নেই\n" else traceTail + "\n")
            withContext(Dispatchers.Main) { reportText = sb.toString().take(20000) }
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
        // v1.0.97: ব্যর্থ তালিকাও পড়ে নাও; সব আবার চেক করলে মুছে ফেলো।
        val failed = if (recheckAll) {
            saveFailedPkgs(ctx, emptySet())
            failedPkgs = emptySet()
            mutableSetOf<String>()
        } else {
            readFailedPkgs(ctx)
        }
        // v1.0.97: hard-limit অ্যাপ চেকই কোরো না — ওরা চালু হয়েও কাজ করে না।
        val allTargets = apps.filter { !isGmsSystemPackage(it.pkg) && !isUnsupportedPackage(it.pkg) && !it.isSystemOnly }
        val skippedPassed = if (recheckAll) 0 else allTargets.count { it.pkg in passed }
        val targets = if (recheckAll) allTargets
        else allTargets.filter { it.pkg !in passed }
        if (targets.isEmpty()) {
            if (skippedPassed > 0) {
                toast("All apps passed before — tap 'Re-check All' to check again")
            } else {
                toast("No apps to check")
            }
            return
        }
        checkupRunning = true
        checkupStop = false
        checkupLines.clear()
        checkupProgress = "Starting…"
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
                        val line = "⏭️ ${app.label}: clone limit reached — skipped"
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
                    val line = "❌ ${app.label}: couldn't clone"
                    // v1.0.97: ক্লোনই হয়নি — ব্যর্থ-তালিকায় রাখো, দেখাবে না।
                    if (failed.add(pkg)) saveFailedPkgs(ctx, failed)
                    if (passed.remove(pkg)) savePassedPkgs(ctx, passed)
                    appendCheckupLine(ctx, line)
                    withContext(Dispatchers.Main) {
                        checkupLines.add(line)
                        failedPkgs = failed.toSet()
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
                // v1.0.99: রায় বের করার লজিক এক জায়গায় — দুইবার ডাকা যায়।
                fun evalOnce(): String {
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
                    return when {
                        hasResume -> "✅ ${app.label}: launched"
                        hasDone -> "⚠️ ${app.label}: onCreate done, no onResume"
                        hasStart -> "❌ ${app.label}: stuck/crashed in onCreate"
                        hasBind -> "❌ ${app.label}: bind done, activity didn't start"
                        alive -> "⚠️ ${app.label}: process alive but no activity seen"
                        else -> "❌ ${app.label}: didn't launch (process died)"
                    }
                }
                val verdict: String
                if (lr.isFailure) {
                    verdict = "❌ ${app.label}: launch call failed (${lr.exceptionOrNull()?.message ?: "?"})"
                    failCount++
                } else {
                    kotlinx.coroutines.delay(4000)
                    var v = evalOnce()
                    if (!v.startsWith("✅")) {
                        // v1.0.99: ভারী অ্যাপ (Facebook/Messenger) ধীরে চালু হয় —
                        // ৪ সেকেন্ডে রায় দিলে ভুল ❌ হয় (v1.0.97-এ Facebook-ই
                        // ভুল করে লুকিয়ে গিয়েছিল)। আরও ৬ সেকেন্ড দাও, ভালো
                        // রায় পেলে সেটাই নাও (✅ > ⚠️ > ❌)।
                        kotlinx.coroutines.delay(6000)
                        val v2 = evalOnce()
                        val rank = { s: String ->
                            when { s.startsWith("✅") -> 2; s.startsWith("⚠️") -> 1; else -> 0 }
                        }
                        if (rank(v2) > rank(v)) v = v2
                    }
                    verdict = v
                    if (verdict.startsWith("✅")) okCount++
                    else if (!verdict.startsWith("⚠️")) failCount++
                }
                // v1.0.99: শুধু ❌ লুকাবে। ✅ পাস-তালিকায়; ⚠️ কোনো তালিকাতেই
                // না — পরের চেকআপে আবার চেক হবে, তালিকায় দেখাবে। (v1.0.97-এ
                // ⚠️ লুকিয়ে TikTok/WhatsApp/Gmail হারিয়ে গিয়েছিল।)
                if (verdict.startsWith("✅")) {
                    if (passed.add(pkg)) savePassedPkgs(ctx, passed)
                } else if (passed.remove(pkg)) {
                    savePassedPkgs(ctx, passed)
                }
                if (verdict.startsWith("❌")) {
                    if (failed.add(pkg)) saveFailedPkgs(ctx, failed)
                } else if (failed.remove(pkg)) {
                    saveFailedPkgs(ctx, failed)
                }
                appendCheckupLine(ctx, verdict)
                withContext(Dispatchers.Main) {
                    checkupLines.add(verdict)
                    failedPkgs = failed.toSet()
                }
                // এই অ্যাপের প্রসেস থামাও, পরেরটার জন্য পরিষ্কার রাখো
                runCatching { BlackBoxCore.get().stopPackage(pkg, 0) }
            }
            val stopped = checkupStop
            if (!stopped) appendCheckupLine(ctx, "=== Checkup finished ===")
            val summary = buildString {
                append("=== CloneMe Check All Apps ===\n")
                append("Time: ${java.util.Date()}\n")
                append("Total: ${targets.size} apps")
                if (skippedPassed > 0) append(" (+ ${skippedPassed} passed skipped)")
                append("\n")
                append("✅ Passed: $okCount | ❌ Failed: $failCount | ⏭️ Skipped: $skipCount\n")
                if (stopped) append("(stopped midway)\n")
                append("\n")
                append(checkupLines.joinToString("\n"))
                append("\n")
            }
            withContext(Dispatchers.Main) {
                checkupRunning = false
                checkupProgress = if (stopped) "Stopped" else "Done — ✅ $okCount, ❌ $failCount"
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
                    if (ok) "Google services installed — try launching the clones again"
                    else "Google services install failed — try again"
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
                    if (!nowInstalled) "Google services removed"
                    else "Removal failed — try again"
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
                        toast("Clone deleted")
                    },
                    onFailure = {
                        // ইঞ্জিনে ডিলিট না থাকলে শুধু লিস্ট থেকে সরাও
                        clones.remove(clone)
                        persist()
                        toast("Removed from list")
                    }
                )
            }
        }
    }

    // v1.0.100: টাইলে দেখানোর নাম — নিজের নাম থাকলে সেটাই, নইলে
    // একই অ্যাপের একাধিক ক্লোনে নম্বর (Facebook, Facebook 2, ...)।
    fun displayTitle(clone: CloneId, app: AppInfo): String {
        customLabels[clone]?.let { return it }
        val siblings = clones.filter { it.pkg == clone.pkg }.sortedBy { it.userId }
        return if (siblings.size > 1) "${app.label} ${siblings.indexOf(clone) + 1}" else app.label
    }

    // v1.0.100: লং-প্রেস মেনু — জোর করে বন্ধ করুন।
    fun doForceStop(clone: CloneId) {
        scope.launch(Dispatchers.IO) {
            runCatching { BlackBoxCore.get().stopPackage(clone.pkg, clone.userId) }
            withContext(Dispatchers.Main) { toast("Stopped") }
        }
    }

    // v1.0.100: লং-প্রেস মেনু — ডেটা মুছুন (আনইনস্টল + একই জায়গায় আবার ইনস্টল)।
    fun doClearData(clone: CloneId) {
        if (busyClone != null) return
        busyClone = clone
        scope.launch(Dispatchers.IO) {
            runCatching { BlackBoxCore.get().stopPackage(clone.pkg, clone.userId) }
            Engine.current.uninstallClone(clone.pkg, clone.userId)
            val result = Engine.current.installClone(clone.pkg, clone.userId)
            withContext(Dispatchers.Main) {
                busyClone = null
                toast(if (result.isSuccess) "Data cleared" else "Couldn't clear data")
            }
        }
    }

    // v1.0.100: লং-প্রেস মেনু — ক্যাশ মুছুন (ভার্চুয়াল ক্যাশ ফোল্ডার খালি করো)।
    fun doClearCache(clone: CloneId) {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val dir = BEnvironment.getDataCacheDir(clone.pkg, clone.userId)
                var done = true
                dir.listFiles()?.forEach { if (!it.deleteRecursively()) done = false }
                done
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                toast(if (ok) "Cache cleared" else "Couldn't clear cache")
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

    // v1.0.100: অটো-চেকআপ বাদ (ব্যবহারকারীর নির্দেশ 2026-11-14 14:56 —
    // অ্যাপ খুললেই সব অ্যাপ চেকআপ বিরক্তিকর)। চেকআপ শুধু ⋮ মেনু থেকে
    // হাতে চাপলে চলবে (runFullCheckup)।

    val bgBrush = remember { Brush.verticalGradient(listOf(Color(0xFFE8F4FE), Color(0xFFF1E9FB))) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            // v1.0.100: Multiple App-এর "+" — গাঢ় নীল গোল-চৌকো বোতাম, অ্যাপ বাছাইয়ে যায়।
            if (tab == 1) {
                FloatingActionButton(
                    onClick = { query = ""; tab = 0 },
                    containerColor = AccentBlue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    Text("+", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { pad ->
        // v1.0.100: Multiple App-এর মতো হালকা নীল-বেগুনি গ্রেডিয়েন্ট ব্যাকগ্রাউন্ড।
        Column(
            Modifier.padding(pad).fillMaxSize()
                .background(bgBrush)
        ) {
            if (tab == 1) {
                // হোম টপ বার (স্ক্রিনশটের মতো): শিরোনাম + পেন্সিল + সার্চ + ⋮ মেনু।
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "CloneMe",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edit",
                            tint = if (editMode) AccentBlue else Color.Black
                        )
                    }
                    IconButton(
                        onClick = {
                            homeSearch = !homeSearch
                            if (!homeSearch) query = ""
                        },
                        modifier = Modifier.background(
                            if (homeSearch) Color(0xFFE0E0E0) else Color.Transparent,
                            CircleShape
                        )
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.Black)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = Color.Black)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Smart Report") },
                                onClick = { menuOpen = false; generateReport() }
                            )
                            DropdownMenuItem(
                                text = { Text("Check All Apps") },
                                onClick = { menuOpen = false; checkupDialogOpen = true; runFullCheckup() }
                            )
                            DropdownMenuItem(
                                text = { Text("Re-check All") },
                                onClick = { menuOpen = false; checkupDialogOpen = true; runFullCheckup(recheckAll = true) }
                            )
                        }
                    }
                }
                if (homeSearch) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search", fontSize = 15.sp, color = Color(0xFF9E9E9E)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF9E9E9E)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
                if (!Engine.current.isReady()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "Warning: virtualization engine not included, so clones won't work.",
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                // অ্যাপ বাছাই টপ বার (স্ক্রিনশটের মতো): ব্যাক + শিরোনাম + টগল।
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { query = ""; tab = 1 }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.Black)
                    }
                    Text(
                        "CloneMe",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = showHidden,
                        onCheckedChange = { showHidden = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentBlue,
                            checkedBorderColor = Color.Transparent,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFE0E0E0),
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search", fontSize = 15.sp, color = Color(0xFF9E9E9E)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF9E9E9E)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }
            if (loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = BrandBlue)
                        Text("Loading apps…", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            } else if (tab == 0) {
                // v1.0.100: শুধু চলবে এমন অ্যাপ দেখাও — ব্যর্থ/অসমর্থিত
                // অ্যাপ লুকিয়ে রাখো (উপরের টগলে দেখানো যায়)।
                val filtered = remember(apps, query, showHidden, failedPkgs) {
                    apps.filter {
                        (query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                            it.pkg.contains(query, ignoreCase = true)) &&
                        (showHidden || (!it.isSystemOnly && it.pkg !in failedPkgs && !isUnsupportedPackage(it.pkg) && !isGmsSystemPackage(it.pkg)))
                    }
                }
                // v1.0.100: স্ক্রিনশটের মতো HOT সেকশন — জনপ্রিয় অ্যাপ আগে।
                // HOT অ্যাপগুলো নিচের অক্ষর-সেকশনেও থাকবে (Multiple App-এর মতো — দুই জায়গায়)।
                val hotApps = remember(filtered) {
                    val hotPkgs = listOf(
                        "com.facebook.katana", "com.facebook.orca", "com.android.chrome",
                        "com.zhiliaoapp.musically", "com.whatsapp", "org.telegram.messenger",
                        "com.brave.browser", "com.google.android.gm", "com.lemon.lvovideoeditor",
                        "com.google.android.apps.photos", "com.google.android.apps.maps",
                        "com.google.android.apps.docs"
                    )
                    hotPkgs.mapNotNull { p -> filtered.find { it.pkg == p } }.take(12)
                }
                val sections = remember(filtered) {
                    filtered.groupBy { app ->
                        val c = app.label.firstOrNull()?.uppercaseChar() ?: '#'
                        if (c in 'A'..'Z') c else '#'
                    }.entries.sortedWith(compareBy({ if (it.key == '#') 1 else 0 }, { it.key }))
                }
                val selectedApps = remember(apps, selectedPkgs) { apps.filter { it.pkg in selectedPkgs } }
                if (filtered.isEmpty()) {
                    EmptyHint("No apps found", Modifier.weight(1f))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (hotApps.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "HOT",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color.Black,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            items(hotApps.size, key = { hotApps[it].pkg }) { idx ->
                                val app = hotApps[idx]
                                AppGridItem(
                                    app = app,
                                    selected = app.pkg in selectedPkgs,
                                    busy = busyClone?.pkg == app.pkg
                                ) {
                                    selectedPkgs = if (app.pkg in selectedPkgs)
                                        selectedPkgs - app.pkg else selectedPkgs + app.pkg
                                }
                            }
                        }
                        for ((letter, sectionApps) in sections) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    letter.toString(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color.Black,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            items(sectionApps.size, key = { sectionApps[it].pkg }) { idx ->
                                val app = sectionApps[idx]
                                AppGridItem(
                                    app = app,
                                    selected = app.pkg in selectedPkgs,
                                    busy = busyClone?.pkg == app.pkg
                                ) {
                                    selectedPkgs = if (app.pkg in selectedPkgs)
                                        selectedPkgs - app.pkg else selectedPkgs + app.pkg
                                }
                            }
                        }
                    }
                }
                // নিচে স্থায়ী "ক্লোন করো" বোতাম (স্ক্রিনশটের Clone Apps-এর মতো)।
                Button(
                    onClick = {
                        if (selectedApps.isEmpty()) toast("Select apps to clone")
                        else doCloneSelected(selectedApps)
                    },
                    enabled = busyClone == null,
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(
                        if (selectedApps.isEmpty()) "Clone Apps"
                        else "Clone Apps (${selectedApps.size})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                // v1.0.100: Multiple App-এর মতো ক্লোন হোম — অ্যাপ অনুযায়ী
                // গ্রুপ, ৪ কলামের গ্রিড; চাপলে চালু, চেপে ধরলে মেনু,
                // এডিট মোডে (পেন্সিল) চাপলে মোছার ডায়ালগ।
                val shownClones = remember(clones, apps, query) {
                    clones.filter { clone ->
                        if (query.isBlank()) true else {
                            val a = apps.find { it.pkg == clone.pkg }
                            a != null && (a.label.contains(query, ignoreCase = true) ||
                                a.pkg.contains(query, ignoreCase = true))
                        }
                    }.sortedWith(compareBy({ it.pkg }, { it.userId }))
                }
                if (clones.isEmpty()) {
                    EmptyHint("No clones yet.\nTap the + button below to clone.", Modifier.weight(1f))
                } else if (shownClones.isEmpty()) {
                    EmptyHint("No clones found", Modifier.weight(1f))
                } else {
                    val groups = remember(shownClones, apps) {
                        shownClones.groupBy { it.pkg }.entries.sortedBy { entry ->
                            apps.find { it.pkg == entry.key }?.label?.lowercase() ?: entry.key
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for ((pkg, groupClones) in groups) {
                            val app = apps.find { it.pkg == pkg } ?: continue
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    app.label,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            items(groupClones.size, key = { groupClones[it].toString() }) { idx ->
                                val clone = groupClones[idx]
                                val title = displayTitle(clone, app)
                                Box {
                                    CloneGridItem(
                                        app = app,
                                        title = title,
                                        editMode = editMode,
                                        busy = busyClone == clone,
                                        onClick = {
                                            if (editMode) pendingDelete = clone else doLaunch(clone)
                                        },
                                        onLongClick = { pendingMenu = clone }
                                    )
                                    // লং-প্রেস মেনু (স্ক্রিনশটের মতো গাঢ় পপআপ)।
                                    DropdownMenu(
                                        expanded = pendingMenu == clone,
                                        onDismissRequest = { pendingMenu = null },
                                        shape = RoundedCornerShape(16.dp),
                                        containerColor = Color(0xFF6B6A70)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Image(
                                                bitmap = app.icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        }
                                        HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
                                        DarkMenuItem("Delete", Icons.Filled.Delete) {
                                            pendingMenu = null; pendingDelete = clone
                                        }
                                        DarkMenuItem("Force stop", Icons.Filled.Warning) {
                                            pendingMenu = null; doForceStop(clone)
                                        }
                                        DarkMenuItem("Create shortcut", Icons.Filled.Share) {
                                            pendingMenu = null; pendingShortcut = clone
                                        }
                                        DarkMenuItem("Edit Name/Icon", Icons.Filled.Edit) {
                                            pendingMenu = null; renameText = title; pendingRename = clone
                                        }
                                        DarkMenuItem("Clear data", Icons.Filled.Delete) {
                                            pendingMenu = null; pendingClearData = clone
                                        }
                                        DarkMenuItem("Clear cache", Icons.Filled.Delete) {
                                            pendingMenu = null; doClearCache(clone)
                                        }
                                        DarkMenuItem("Clone apps", Icons.Filled.Add) {
                                            pendingMenu = null; doClone(app)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // v1.0.100: মোছার নিশ্চিতকরণ (Multiple App-এর Delete ডায়ালগের মতো)।
        val delClone = pendingDelete
        if (delClone != null) {
            val delApp = apps.find { it.pkg == delClone.pkg }
            val delTitle = if (delApp != null) displayTitle(delClone, delApp) else delClone.pkg
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete $delTitle?") },
                text = { Text("This clone will be deleted. All its data will be erased and cannot be recovered.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        doDelete(delClone)
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                }
            )
        }
        // v1.0.100: শর্টকাট ডায়ালগ (লং-প্রেস মেনু থেকে)।
        val scClone = pendingShortcut
        if (scClone != null) {
            val scApp = apps.find { it.pkg == scClone.pkg }
            val scTitle = if (scApp != null) displayTitle(scClone, scApp) else scClone.pkg
            AlertDialog(
                onDismissRequest = { pendingShortcut = null },
                title = { Text(scTitle) },
                text = { Text("Add a shortcut for this clone to the home screen?") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingShortcut = null
                        if (scApp != null) {
                            (ctx as? MainActivity)?.pinCloneShortcut(scClone, scApp, scTitle)
                                ?: toast("Couldn't add shortcut")
                        }
                    }) { Text("Add Shortcut") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingShortcut = null }) { Text("Cancel") }
                }
            )
        }
        // v1.0.100: নাম বদলানোর ডায়ালগ (লং-প্রেস মেনু থেকে)।
        val rnClone = pendingRename
        if (rnClone != null) {
            AlertDialog(
                onDismissRequest = { pendingRename = null },
                title = { Text("Edit Name") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = renameText.trim()
                        if (name.isNotEmpty()) {
                            val updated = customLabels.toMutableMap()
                            updated[rnClone] = name
                            customLabels = updated
                            scope.launch(Dispatchers.IO) { saveCustomLabels(ctx, updated) }
                        }
                        pendingRename = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        if (customLabels.containsKey(rnClone)) {
                            TextButton(onClick = {
                                val updated = customLabels.toMutableMap()
                                updated.remove(rnClone)
                                customLabels = updated
                                scope.launch(Dispatchers.IO) { saveCustomLabels(ctx, updated) }
                                pendingRename = null
                            }) { Text("Original name") }
                        }
                        TextButton(onClick = { pendingRename = null }) { Text("Cancel") }
                    }
                }
            )
        }
        // v1.0.100: ডেটা মোছার নিশ্চিতকরণ (লং-প্রেস মেনু থেকে)।
        val cdClone = pendingClearData
        if (cdClone != null) {
            val cdApp = apps.find { it.pkg == cdClone.pkg }
            val cdTitle = if (cdApp != null) displayTitle(cdClone, cdApp) else cdClone.pkg
            AlertDialog(
                onDismissRequest = { pendingClearData = null },
                title = { Text("Clear data for $cdTitle?") },
                text = { Text("All data for this clone (including login) will be erased. The clone will be like new.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingClearData = null
                        doClearData(cdClone)
                    }) { Text("Clear data") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingClearData = null }) { Text("Cancel") }
                }
            )
        }
        // v1.0.100: স্মার্ট রিপোর্ট ডায়ালগ (⋮ মেনু থেকে)।
        reportText?.let { report ->
            Dialog(onDismissRequest = { reportText = null }) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Smart Report",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            report,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        )
                        Row {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("report", report))
                                toast("Report copied")
                            }) { Text("Copy") }
                            TextButton(onClick = { reportText = null }) { Text("Close") }
                        }
                    }
                }
            }
        }
        // v1.0.100: ট্রেস ডায়ালগ (ক্লোন চালু না হলে নিজে থেকে দেখাবে)।
        traceText?.let { trace ->
            Dialog(onDismissRequest = { traceText = null }) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Startup Trace",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            trace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        )
                        Row {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("trace", trace))
                                toast("Trace copied")
                            }) { Text("Copy") }
                            TextButton(onClick = { traceText = null }) { Text("Close") }
                        }
                    }
                }
            }
        }
        // v1.0.100: চেকআপ ডায়ালগ (⋮ মেনু / অটো-চেকআপ থেকে)।
        if (checkupDialogOpen) {
            Dialog(onDismissRequest = { checkupDialogOpen = false }) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Check All Apps",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (checkupProgress.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(checkupProgress, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (checkupLines.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                checkupLines.joinToString("\n"),
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        Row {
                            if (checkupRunning) {
                                TextButton(onClick = { checkupStop = true }) { Text("Stop") }
                            }
                            TextButton(onClick = { checkupDialogOpen = false }) { Text("Close") }
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
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
fun AppGridItem(
    app: AppInfo,
    selected: Boolean,
    busy: Boolean,
    onToggle: () -> Unit
) {
    // v1.0.100: Multiple App-এর মতো গ্রিড আইটেম — সাদা টাইল, বড় আইকন,
    // নিচে নাম, কোণায় টিক-বাক্স। চাপলে টিক ওঠে/নামে।
    Card(
        modifier = Modifier.clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        // টিক-বাক্স কার্ডের কোণায় (স্ক্রিনশটের মতো), আইকনের উপরে নয়।
        Box {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Box {
                    Image(
                        bitmap = app.icon,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(13.dp))
                    )
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                            strokeWidth = 3.dp,
                            color = BrandBlue
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    app.label,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = Color(0xFF616161)
                )
            }
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .size(18.dp)
                    .background(if (selected) AccentBlue else Color.White, RoundedCornerShape(5.dp))
                    .border(1.5.dp, if (selected) AccentBlue else Color(0xFFD0D0D0), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DarkMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    // v1.0.100: লং-প্রেস মেনুর আইটেম — সাদা লেখা, ডানে আইকন (স্ক্রিনশটের মতো)।
    DropdownMenuItem(
        text = { Text(text, color = Color.White, fontSize = 14.sp) },
        trailingIcon = { Icon(icon, contentDescription = null, tint = Color.White) },
        onClick = onClick
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloneGridItem(
    app: AppInfo,
    title: String,
    editMode: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // v1.0.100: Multiple App-এর মতো ক্লোন টাইল — সাদা কার্ড, আইকন, নাম;
    // এডিট মোডে লাল "−" ব্যাজ; চেপে ধরলে মেনু।
    Card(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Box {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(13.dp))
                )
                if (editMode) {
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd)
                            .size(20.dp)
                            .background(Color(0xFFE53935), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("-", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                        strokeWidth = 3.dp,
                        color = BrandBlue
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = Color(0xFF616161)
            )
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
