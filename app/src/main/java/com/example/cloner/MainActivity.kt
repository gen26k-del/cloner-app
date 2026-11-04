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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap)

private const val PREFS = "cloner_prefs"
private const val KEY_CLONES = "clones"

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
    @Suppress("DEPRECATION")
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .filter { it.activityInfo.packageName != ctx.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            AppInfo(
                label = ri.loadLabel(pm).toString(),
                pkg = ri.activityInfo.packageName,
                icon = ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            )
        }
        .sortedBy { it.label.lowercase() }
}

private fun readSavedClones(ctx: Context): MutableSet<String> =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_CLONES, emptySet())?.toMutableSet() ?: mutableSetOf()

private fun saveClones(ctx: Context, clones: Set<String>) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_CLONES, clones).apply()
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

    /** ক্লোন করা অ্যাপের হোম-স্ক্রিন শর্টকাট পিন করে */
    fun pinCloneShortcut(app: AppInfo) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "এই ফোনে শর্টকাট সাপোর্ট করে না", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_CLONE
            putExtra("clone_pkg", app.pkg)
        }
        val info = ShortcutInfo.Builder(this, "clone_${app.pkg}")
            .setShortLabel(app.label)
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
    var busyPkg by remember { mutableStateOf<String?>(null) }
    var crashReport by remember { mutableStateOf<String?>(null) }
    var gmsState by remember { mutableStateOf("checking") } // checking/unsupported/installed/ready/installing/failed
    val apps = remember { mutableStateListOf<AppInfo>() }
    val clones = remember { mutableStateListOf<String>() }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    // ব্যাকগ্রাউন্ডে অ্যাপের তালিকা লোড — UI আটকাবে না
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { readSavedClones(ctx) }
        val loaded = withContext(Dispatchers.IO) { loadApps(ctx) }
        // ইঞ্জিনের নিজস্ব লিস্টের সাথে সংরক্ষিত লিস্ট মিলিয়ে নাও
        val engineClones = withContext(Dispatchers.IO) {
            runCatching { Engine.current.listClones() }.getOrDefault(emptyList())
        }
        // শেষ crash-এর রিপোর্ট থাকলে পড়ে নাও
        val crashFile = java.io.File(ctx.cacheDir, CRASH_REPORT_FILE)
        if (crashFile.exists()) {
            crashReport = withContext(Dispatchers.IO) {
                runCatching { crashFile.readText().take(4000) }.getOrNull()
            }
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
        clones.addAll((saved + engineClones).distinct())
        apps.addAll(loaded)
        loading = false
    }

    fun persist() = saveClones(ctx, clones.toSet())

    fun doClone(app: AppInfo) {
        if (busyPkg != null) return
        busyPkg = app.pkg
        scope.launch(Dispatchers.IO) {
            val result = Engine.current.installClone(app.pkg)
            withContext(Dispatchers.Main) {
                busyPkg = null
                result.fold(
                    onSuccess = {
                        if (app.pkg !in clones) {
                            clones.add(app.pkg)
                            persist()
                        }
                        toast("${app.label} ক্লোন হয়েছে")
                    },
                    onFailure = { toast(it.message ?: "ক্লোন করা যায়নি") }
                )
            }
        }
    }

    fun doLaunch(app: AppInfo) {
        if (busyPkg != null) return
        busyPkg = app.pkg
        scope.launch(Dispatchers.IO) {
            val result = Engine.current.launchClone(app.pkg)
            withContext(Dispatchers.Main) {
                busyPkg = null
                result.onFailure { toast(it.message ?: "চালু করা যায়নি") }
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

    fun doDelete(app: AppInfo) {
        if (busyPkg != null) return
        busyPkg = app.pkg
        scope.launch(Dispatchers.IO) {
            val result = Engine.current.uninstallClone(app.pkg)
            withContext(Dispatchers.Main) {
                busyPkg = null
                result.fold(
                    onSuccess = {
                        clones.remove(app.pkg)
                        persist()
                        toast("${app.label} মুছে ফেলা হয়েছে")
                    },
                    onFailure = {
                        // ইঞ্জিনে ডিলিট না থাকলে শুধু লিস্ট থেকে সরাও
                        clones.remove(app.pkg)
                        persist()
                        toast("${app.label} তালিকা থেকে সরানো হয়েছে")
                    }
                )
            }
        }
    }

    // হোম-স্ক্রিন শর্টকাট থেকে এলে তালিকা লোডের পর সরাসরি চালু করো
    LaunchedEffect(loading) {
        if (!loading) {
            val lp = MainActivity.launchPkg
            if (lp != null) {
                MainActivity.launchPkg = null
                apps.find { it.pkg == lp }?.let { doLaunch(it) }
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(Modifier.padding(pad)) {
            // ব্র্যান্ড হেডার
            HeaderCard(cloneCount = clones.size)

            // Crash রিপোর্ট থাকলে দেখাও
            crashReport?.let { report ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "⚠️ শেষবার সমস্যা হয়েছিল (crash/hang)",
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
                                cm.setPrimaryClip(ClipData.newPlainText("crash", report))
                                toast("রিপোর্ট কপি হয়েছে")
                            }) { Text("কপি করুন") }
                            TextButton(onClick = {
                                java.io.File(ctx.cacheDir, CRASH_REPORT_FILE).delete()
                                crashReport = null
                            }) { Text("মুছে ফেলুন") }
                        }
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

            // Google Play সার্ভিস কার্ড — ইন্টারনেট অ্যাপের জন্য
            if (gmsState == "installed") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Google Play সার্ভিস সক্রিয় ✓",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                "কোনো অ্যাপ আটকে গেলে এটা সরিয়ে আবার চেষ্টা করে দেখতে পারো",
                                fontSize = 12.sp
                            )
                        }
                        TextButton(onClick = { doUninstallGms() }) {
                            Text("সরিয়ে ফেলুন")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
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
                    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                        items(filtered, key = { it.pkg }) { app ->
                            AppRow(
                                app = app,
                                buttonLabel = if (app.pkg in clones) "ক্লোন হয়েছে ✓" else "ক্লোন",
                                busy = busyPkg == app.pkg,
                                buttonEnabled = app.pkg !in clones && busyPkg == null
                            ) { doClone(app) }
                        }
                    }
                }
            } else {
                val cloneApps = apps.filter { it.pkg in clones }
                if (cloneApps.isEmpty()) {
                    EmptyHint("এখনো কোনো ক্লোন নেই।\n\"সব অ্যাপ\" থেকে ক্লোন করুন।")
                } else {
                    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                        items(cloneApps, key = { it.pkg }) { app ->
                            CloneRow(
                                app = app,
                                busy = busyPkg == app.pkg,
                                onLaunch = { doLaunch(app) },
                                onDelete = { doDelete(app) },
                                onShortcut = {
                                    (ctx as? MainActivity)?.pinCloneShortcut(app)
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
                app.label,
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
