package com.example.cloner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap)

private const val PREFS = "cloner_prefs"
private const val KEY_CLONES = "clones"

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ClonerApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClonerApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busyPkg by remember { mutableStateOf<String?>(null) }
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

    Scaffold(topBar = { TopAppBar(title = { Text("অ্যাপ ক্লোনার") }) }) { pad ->
        Column(Modifier.padding(pad)) {
            if (!Engine.current.isReady()) {
                Text(
                    "সতর্কতা: ভার্চুয়ালাইজেশন ইঞ্জিন যুক্ত নেই, তাই ক্লোন কাজ করবে না।",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("অ্যাপ খুঁজুন") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("সব অ্যাপ") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("ক্লোন (${clones.size})") })
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("অ্যাপের তালিকা লোড হচ্ছে…", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            } else if (tab == 0) {
                val filtered = apps.filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                        it.pkg.contains(query, ignoreCase = true)
                }
                LazyColumn {
                    items(filtered, key = { it.pkg }) { app ->
                        AppRow(
                            app = app,
                            buttonLabel = if (app.pkg in clones) "ক্লোন হয়েছে ✓" else "ক্লোন",
                            busy = busyPkg == app.pkg,
                            buttonEnabled = app.pkg !in clones && busyPkg == null
                        ) { doClone(app) }
                    }
                }
            } else {
                val cloneApps = apps.filter { it.pkg in clones }
                if (cloneApps.isEmpty()) {
                    Text("এখনো কোনো ক্লোন নেই।", modifier = Modifier.padding(16.dp))
                }
                LazyColumn {
                    items(cloneApps, key = { it.pkg }) { app ->
                        CloneRow(
                            app = app,
                            busy = busyPkg == app.pkg,
                            onLaunch = { doLaunch(app) },
                            onDelete = { doDelete(app) }
                        )
                    }
                }
            }
        }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(44.dp))
        Text(app.label, modifier = Modifier.weight(1f))
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        } else {
            Button(onClick = onClick, enabled = buttonEnabled) { Text(buttonLabel) }
        }
    }
}

@Composable
fun CloneRow(
    app: AppInfo,
    busy: Boolean,
    onLaunch: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(44.dp))
        Text(app.label, modifier = Modifier.weight(1f))
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        } else {
            Button(onClick = onLaunch) { Text("চালু করুন") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "মুছুন")
            }
        }
    }
}
