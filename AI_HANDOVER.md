# CloneMe — AI Handover Guide

> For any AI assistant or developer picking up this project cold. Read this first.
> Last updated: 2026-11-26, at version **1.0.161** (versionCode 146).

> ⭐ **KEEP THIS FILE CURRENT — standing rule from the owner.**
> This document is the project's memory. **Every release MUST update it in the
> same round of work**, before the build is delivered:
> 1. Update the "Last updated / version" line above.
> 2. Add the new version's entry to §6 (what changed, what the device test
>    proved — and if a fix did NOT work, write that too).
> 3. Refresh §7 so the open-problems list matches reality (remove solved
>    items, add new ones, update the "first evidence to check").
> 4. **Sync the embedded copy** at `app/src/main/assets/AI_HANDOVER.md` —
>    this guide ships INSIDE every APK (see §3), so whoever is handed just
>    the APK file can unzip it and read this guide. The two copies must
>    never drift apart.
> The file lives in the repo root, so the latest commit always carries the
> matching guide: whoever receives the newest source (or this file from the
> newest version) can understand the app as it exists *now* and produce the
> next update. A release whose handover update is missing is unfinished.
>
> **Source code:** https://github.com/gen26k-del/cloner-app (branch `main`).
> If you received only the APK, the full source is there; this guide is in
> the repo root and also inside the APK at `assets/AI_HANDOVER.md`.

## 1. What this app is

**CloneMe** (package `com.example.cloner`) is an Android **app cloner**: it runs
copies ("clones") of installed apps inside its own virtual environment, so the
user can run e.g. two Facebook / Messenger / WhatsApp accounts on one phone.
It is a fork of the open-source **BlackBox** engine (upstream: ALEX5402/NewBlackbox).

The owner (rifa) tests on a **Tecno Camon 20 Pro, Android 14**. His target apps,
in priority order: **Facebook, Messenger, Chrome** first, then WhatsApp, Gemini,
Google app, Instagram, TikTok, ChatGPT, Claude.

## 2. Repository layout

| Path | What |
|---|---|
| `app/` | The host app (Kotlin + Compose UI, clone list, smart report). Namespace `com.example.cloner`. |
| `blackbox/Bcore/` | The BlackBox virtual engine (Java). All virtualization lives here. |
| `blackbox/black-reflection/`, `blackbox/compiler/` | Support modules of the engine. |
| `ci/debug.keystore.b64` | **The fixed signing key (base64). See §4 — do not touch.** |
| `.github/workflows/build.yml` | CI: builds the APK on every push. See §3. |
| `VERSIONS.md` | The previous developer's changelog (stops at v1.0.100; later versions are in git log + §6 of this file). |

There is **no Gradle wrapper** in the repo. CI uses Gradle **8.14.5**, JDK **21**,
NDK **29.0.13846066**. `targetSdk` is intentionally **28** (engine hooks break on
newer target behaviour) — do not "modernize" it casually.

## 3. How to build

Push to `main` → GitHub Actions workflow **"Build APK"** runs `gradle assembleDebug`
→ artifact **`app-debug`** (a zip containing `app-debug.apk`) on the run page.
That is the only supported build path. (Local Gradle builds are possible in
principle with the same Gradle/JDK/NDK versions, but CI is the reference.)

**This guide is embedded in the APK itself**: the copy at
`app/src/main/assets/AI_HANDOVER.md` is packaged into every build at
`assets/AI_HANDOVER.md`. Unzipping any shipped APK yields the guide that
matches that version. (Verify after building: `unzip -l app-debug.apk | grep AI_HANDOVER`.)

Every build MUST bump `versionCode` (+1) and `versionName` in
`app/build.gradle.kts`. Version names are Bengali feature labels, e.g.
`"1.0.144 স্থায়ী-সাইন"`.

## 4. ⭐ Signing — the one thing you must never break

Android only installs an update over an installed app if **both APKs carry the
same signing certificate**. A different signature forces a full uninstall, which
**wipes all clones and all logged-in accounts** (the user then has to log in to
Facebook etc. again and gets verification challenges — a real harm to him).

Therefore this project signs every build with **one fixed debug keystore**:

- The keystore is stored base64-encoded at `ci/debug.keystore.b64`
  (alias `androiddebugkey`, passwords `android` — standard debug values).
- `app/build.gradle.kts` decodes it at configuration time to
  `build/fixed-debug.keystore` and pins the `debug` signingConfig to it.
- Expected signer certificate SHA-256 of every shipped APK:
  **`D3:EF:90:54:82:FF:17:3A:96:0F:48:E3:31:48:5E:EF:87:02:3D:25:45:34:5D:6C:77:32:B9:75:F9:79:81:87`**
- Before delivering any build, verify: zip SHA-256 matches the digest GitHub
  shows for the artifact, `aapt dump badging` shows the expected
  versionCode/versionName, and `apksigner verify --print-certs` shows the
  fingerprint above. If the fingerprint differs, **do not deliver** — users
  would lose their logins.

**Never** regenerate, replace, or delete the keystore, and never remove the
signing block. (Builds up to v1.0.143 were signed with per-run ephemeral CI
keys — that is why users had to uninstall for those. v1.0.144 is the first
fixed-signature build and needs one last uninstall; every build after it
updates in place.)

## 5. How the engine works (the short tour)

- The host app asks the engine to launch a clone; the engine
  (`BlackBoxCore`, `core/system/BActivityManagerService`,
  `core/system/BProcessManagerService`) starts a **stub process**
  (`com.example.cloner:p0…pN`), then binds the guest app into it:
  `BActivityThread.handleBindApplication` creates the guest `Application`,
  installs its providers, and only then activities launch.
- Guest system calls are intercepted by **hook/proxy classes** in
  `fake/service/` (e.g. `IActivityManagerProxy`, `IActivityTaskManagerProxy`,
  `IPackageManagerProxy`). Shared activity-start logic lives in
  `fake/service/ActivityManagerCommonProxy.java`; the three proxies pick it up
  via `@ScanClass`.
- Virtual package resolution is in `core/system/pm/BPackageManagerService.java`.
  It dereferences **activity aliases** correctly (returns the real target).
- Guest content providers are wrapped by
  `fake/service/context/providers/ContentProviderStub.java`.
- Chromium-based guests (Chrome) get special handling in
  `app/dispatcher/AppServiceDispatcher.java` (binder wrapping of
  `IChildProcessService`, RELRO nulling, `--no-sandbox` append,
  `--enable-features` rewriting).

### The smart report (your main diagnostic tool)

The host app can generate a **smart report** (Bengali UI) that the user pastes
to you. It reads a trace file (`cacheDir/init_trace.txt`) that engine code
writes via `BlackBoxCore.traceInitPublic(...)`. Markers that matter:

- `handleBindApplication start/done: <pkg>` — bind began / completed.
- `AppInstrumentation.callActivityOnCreate start/done: <real class>` and
  `callActivityOnResume: <real class>` — activity lifecycle. **Markers always
  carry the REAL class name, never an alias.**
- `CrossClone: ...` — (v1.0.143+) every activity start whose target package
  differs from the caller's: the intent, whether it launched virtually, or
  whether it **fell through to the real AMS** (i.e. was dropped).
- `ChromeArgs: final enable-features=... noSandbox=...` — (v1.0.143+) the exact
  command line a Chrome child process received.
- Diagnosis section also surfaces lines matching keywords in `MainActivity.kt`
  (`diagKeywords` / `rareKeywords`, around the report builder).

⚠️ **Trust the user's on-screen experience over the report.** The report's
verdict logic once misread alias-launched apps (Facebook/Messenger) as
"bind done, activity never started" while they ran fine — see §6, v1.0.142.
When they disagree, the user is right and the report is lying.

## 6. What was fixed, version by version (2026-11-22)

- **v1.0.140** — Host startup hang: `ContentProviderCompat.call()` bounded the
  call but not the provider *acquire* (could park forever in
  `ActivityThread.acquireProvider` during `doCreate`). Acquire now has a 5 s
  timeout on the same executor. Plus: `BProcessManagerService.startProcessLocked`
  no longer leaves a poisoned process record when init throws (cleanup + one
  bounded waiter retry). Plus a three-way bind verdict in the report
  (not launched / died during bind / bind done).
- **v1.0.141** — Meta apps' background provider calls (`ContentProvider.delete`
  on Facebook's push-dedup provider) threw `SecurityException` ("Component
  access not allowed" — the check is calling-UID based, and in-process calls
  carry the host's UID). `ContentProviderStub.invoke` now returns a safe
  default for `SecurityException` instead of crashing the guest.
  (Note: Facebook/Messenger themselves opened fine before and after; this
  removed a crash file and a background-thread death.)
- **v1.0.142** — (a) **Report verdict fix**: `resolveTraceActivityClass` in
  `MainActivity.kt` resolved launcher aliases via the host PackageManager with
  flags `0`; Meta's aliases are manifest-disabled, so resolution failed and the
  verdict searched the trace for the alias name, which never appears. Now uses
  `MATCH_DISABLED_COMPONENTS`, plus a package-level proof-of-life
  (`callActivityOnResume: <pkg>.`). (b) **Cross-clone handoff attempt**:
  `BPackageManagerService.chooseBestActivity` returned `null` on a tied
  multi-match (AOSP resolver semantics, but there is no resolver on this path);
  it now returns the first match. And `ActivityManagerCommonProxy.StartActivity`
  gained a **cross-clone rescue**: when virtual resolution fails but the
  intent's target package is itself a clone, it resolves that clone's launcher
  activity and routes the launch virtually instead of falling through to the
  real AMS. **Result: the Facebook→Messenger handoff STILL did not work on the
  user's phone** — the rescue path was not the one the tap takes. Kept in place
  (harmless), superseded by the tracing below.
- **v1.0.143** — Instrumentation: `CrossClone:` trace lines in the
  StartActivity / StartActivities hooks (intent target, virtual launch,
  fall-through), and the `ChromeArgs:` one-line summary in
  `AppServiceDispatcher.forwardSetupConnectionNulled`. Both keywords added to
  the report's keyword lists.
- **v1.0.144** — Fixed signing (§4). First build that updates in place from
  now on. **Device test (2026-11-22, three smart reports):** installed fine;
  Facebook/Messenger/WhatsApp clones all reach onResume; the user still
  experiences WhatsApp as "doesn't open" (opens but not usable/stable — his
  word stands over the trace). Chrome's tab renderer still died during video
  (15:15:34, `primaryMainFrameRenderProcessGone` status 0 + VideoPersist
  dismiss) — the §7.1 problem is unchanged. No `CrossClone:` or `ChromeArgs:`
  line appeared in ANY of the three reports — root cause found in the report
  builder itself (see v1.0.145).
- **v1.0.145** — (a) **Report fix**: the diagnosis section keeps only the
  last 20 *grouped* messages (`diagGrouped.takeLast(20)` in
  `MainActivity.kt`), so `ChromeArgs:`/`CrossClone:` lines were crowded out
  by "foreground promoted"/"child process died" floods even when written.
  The report now has a dedicated section ("ChromeArgs / CrossClone (আলাদা
  অংশ)") that counts ALL such lines in the full trace and prints the last
  10 of each — a count of 0 in the next report PROVES the line was never
  written; a non-zero count finally shows the values. (b) **App icon**:
  an adaptive icon was added — but with a NEW design (white squares on a
  deep-blue grid) that the owner REJECTED the same day; see v1.0.146.
- **v1.0.146** — **Original logo restored** (built but NOT shipped —
  superseded by v1.0.147 before delivery). The v1.0.145 redesign was a
  mistake: the app already HAD a logo (the mipmap PNGs: light-blue back
  square + blue front square + white glyph on white), and the owner never
  asked for a redesign — he had only asked why the installer showed no
  proper logo. The adaptive drawables now recreate the ORIGINAL design as
  vectors (geometry/colours measured from the xxxhdpi PNG).
- **v1.0.147** — **The robot image traced and replaced.** The blue-grid
  ANDROID ROBOT picture on HiOS's install/security-scan screen is NOT a
  HiOS placeholder after all: it is an unreferenced PNG inside the Bcore
  library (`blackbox/Bcore/src/main/res/drawable/ic_launcher.png`, the
  old Android Studio default) that gets packaged into the APK, and the
  HiOS scan screen displays it. Nothing in the code references
  `@drawable/ic_launcher`. Fix: an app-module vector drawable of the same
  type+name (`app/src/main/res/drawable/ic_launcher.xml`, original-logo
  design) — app resources override library resources at merge time, so
  the APK's `res/drawable/ic_launcher` is now the real logo.
  **Device verdict (2026-11-22): the HiOS scan screen STILL shows the
  robot** even though the shipped APK contains no robot image anywhere
  (full listing checked) — so that screen's picture is HiOS-side
  (its own placeholder/cache for sideloaded apps) and CANNOT be changed
  from inside the app. The override is still correct hygiene (the APK no
  longer carries the robot), but do not promise the scan screen again.
- **v1.0.148** — **Exact original logo, no redraw.** The owner rejected
  the vector recreation too ("আসল লোগো আগেরটা আসেনি" — the recreation
  looked bigger than the original because adaptive-icon layers are
  centre-cropped/zoomed by launchers). Final approach: the ORIGINAL
  xxxhdpi PNG travels as base64 at `ci/logo-xxxhdpi.b64`;
  `app/build.gradle.kts` decodes it at configuration time into
  `build/generated/logoRes/drawable-nodpi/ic_launcher_orig.png` and
  registers that dir as a main res srcDir. `mipmap-anydpi-v26/
  ic_launcher{,_round}.xml` and `drawable/ic_launcher.xml` are now plain
  `<bitmap>` aliases to `@drawable/ic_launcher_orig` — no adaptive icon,
  no vectors, the exact original pixels. The 146/147 vector drawables
  (`ic_launcher_{background,foreground}.xml`) remain in the tree but are
  unreferenced. **Lesson: never recreate an existing asset by eye when
  the original file is available — ship the original.** Owner confirmed
  on device 2026-11-22: "লোগো ঠিক আছে এবার" — icon saga closed.
- **v1.0.149** — **Handoff fix + launch guard + launcher behaviour**
  (diagnosis: `cloner-app-diagnosis3.md` in the workspace, code-proven).
  (a) **FB→Messenger**: `IPackageManagerProxy` had NO
  `queryIntentActivities` hook — the call fell through to the real
  system PM, where virtual apps are invisible, so Facebook's Messenger
  presence check returned empty and it silently gave up (also broke
  guest `getLaunchIntentForPackage`). Added the hook forwarding to
  `BPackageManagerService.queryIntentActivities` with the CALLER's own
  userId (clone pairs stay matched per user slot: FB #1 → Messenger #1;
  a slot without a Messenger clone correctly finds nothing), falling
  back to the real PM only when the virtual result is empty. PMCheck
  traces added (cross-package, virtual targets only) in getPackageInfo /
  getApplicationInfo / getActivityInfo / resolveIntent /
  queryIntentActivities. (b) **Wrong-process launch guard**:
  `HCallbackProxy.handleLaunchActivity` never compared the incoming
  activity's package with the process's bound package when already
  initialised — the 2026-11-22 16:12:34 crash (FbMainTabActivity
  instantiated in a Chrome-bound process, "fbApp has not been
  initialized"; crash files are named from /proc/self/cmdline, so the
  Chrome binding is proven). Now a mismatch routes through the unbound
  recovery (restartProcess + launch rewrite) and every assignment is
  traced (`launch assigned:` guest-side, `launch assigned(server):` in
  ActivityStack). (c) **Launcher behaviour (owner's requirement)**:
  MainActivity is now `singleTask` — the launcher icon always brings
  CloneMe's home/list forward; per-clone home shortcuts still open the
  clone directly via ShortcutLaunchActivity (unchanged). (d) Chrome:
  `bindService orphan:` traces name each process displaced by an
  isolated rebind (orphans are NOT killed — v1.0.62/67/74 history in
  ActiveServices.java shows killing live children is worse), plus a
  `bindService:` correlation line (service → chosenProcess/bpid).
  Report keywords extended (PMCheck, LAUNCH MISMATCH, launch assigned,
  bindService orphan). **First evidence to check in the next report:**
  PMCheck lines with caller=com.facebook.katana target=com.facebook.orca
  (found=true expected), whether the Messages tap now opens Messenger,
  any LAUNCH MISMATCH lines, orphan counts during Chrome use.
  **Device verdict (2026-11-23): the handoff WORKS** — PMCheck found=true
  in both directions, `CrossClone:` fired on the Messages tap (13:09:59,
  fb-messenger://threads → IntentHandlerActivity), and the owner confirms
  Messenger opens. It opens SLOWLY on a cold start (the Messenger process
  had just been through a LOW_MEMORY kill wave); warm taps are the
  remaining check. See §7.2 — this item is closed.
- **v1.0.150** — **Crash guard + diagnostics** (device evidence:
  2026-11-23 smart reports). (a) **Aura "Missing ComponentInfo!" crash
  FIXED.** Root cause: `ComponentResolver.newResult` copies
  `PackageManagerCompat.generate*Info()` output into a ResolveInfo, and
  those helpers return null when the package is not installed/visible
  for that user — leaving a ResolveInfo with activityInfo, serviceInfo
  AND providerInfo all null, whose `toString()` → `getComponentInfo()`
  throws `IllegalStateException`. It killed com.facebook.aura's
  firebase-iid thread through `IPackageManagerProxy`'s
  QueryBroadcastReceivers hook (the hook's own log line stringified the
  poisoned list). The PM proxy now drops malformed ResolveInfo entries
  before returning lists (`queryBroadcastReceivers`,
  `queryIntentActivities`) and treats a malformed single result
  (`resolveIntent`/`resolveService`) as no result; the hook logs a count
  instead of the raw list. (There is NO `queryIntentServices` hook in
  the proxy — that call falls through to the real PM — so nothing to
  fix there.) (b) **updateServiceGroup swallowed.**
  `IActivityManagerProxy` had no `updateServiceGroup` hook, so
  Chromium's service-group updates hit the real AM, which rejects the
  guest's proxy connection ("Could not find connection for
  BinderProxy") — logged at exactly the moment a Chrome child dies
  (2026-11-23 04:08 report). Now a quiet no-op, mirroring the v1.0.125
  `setProcessImportant` guard. This removes an error path; it is NOT
  claimed to stop renderer deaths. (c) **ANR evidence — tracing only,
  NOT a cure.** The host main process ANRs ("CloneMe isn't
  responding") during mass clone restarts under memory pressure
  (device: 2026-11-23 03:47 and ~13:12), but the 13:12 ANR left NO
  crash file: the system dialog (~5 s input timeout) can fire before
  the watchdog's 8 s threshold writes one. The watchdog now also writes
  a `MainStall:` line into the init trace, once per stall episode,
  when the main thread is blocked >4 s. The launch path itself already
  runs off the main thread (Dispatchers.IO + 20 s timeout, v1.0.131) —
  no speculative rewrite was attempted, and the LOW_MEMORY stub deaths
  behind the pressure are Android-normal (see §7 note), not promised
  fixable. (d) **Notification trace — diagnosis only, no behaviour
  change.** The owner reports the **Facebook clone receives NO
  notifications at all** (2026-11-23; total absence, not delay or
  misattribution). `INotificationManagerProxy` now writes
  `NotifyPost:`/`NotifyCancel:` trace lines (clone package from the
  guest's bound package, userId, id, tag) at the enqueue/cancel hooks,
  and the smart report gained a dedicated "NotifyPost / NotifyCancel"
  section with totals + last 10. **First evidence to check in the next
  report:** NotifyPost lines for com.facebook.katana (zero lines =
  katana's process never posts inside the clone; lines present = posts
  happen but never surface — the two cases need different fixes),
  MainStall lines around any ANR, and the aura crash file staying gone.
  **Device verdict (2026-11-23, two v1.0.150 reports):** NotifyPost =
  0 across ALL clones in both windows while NotifyCancel fired
  normally (katana id=6/10004/103773363, whatsapp id=2) — katana
  never posts inside the clone. The clone's FB shows 6 unread on its
  in-app bell badge (server fetch works when opened); the original
  FB app on the same phone notifies normally. See §7.6 / v1.0.151.
- **v1.0.151** — **Push investigation + one broadcast fix** (device
  evidence above; code investigation this round). What the code
  actually shows: (a) The guest notification POST path is sound —
  `INotificationManagerProxy.EnqueueNotificationWithTag` forwards
  to `BNotificationManagerService`, which remaps the id, rewrites
  the channel to `<id>@black-<userId>` and calls the real
  NotificationManager under the host package; the v1.0.150
  NotifyPost trace sits at the first hop of that path, and the same
  apps' cancels pass through the same proxy, so "katana never calls
  notify() inside the clone" is a sound conclusion, not a trace
  blind spot (short of an app posting through an unproxied binder,
  for which there is no evidence). (b) Real-GMS FCM delivery is
  **structurally unreachable** for a clone: GMS dispatches FCM as a
  package-targeted broadcast (`com.google.android.c2dm.intent.
  RECEIVE`, setPackage = the app's package), and AMS delivers
  package-targeted broadcasts only to that package — the REAL
  Facebook app, which is exactly why the original notifies and the
  clone cannot. No host-side receiver can intercept another
  package's FCM. The only in-environment route is the guest GMS
  clone's own MCS connection → guest GMS sends the C2DM broadcast
  through the hooked `broadcastIntent` → BAMS shadow → guest
  FirebaseInstanceIdReceiver → FirebaseMessagingService → notify();
  every in-env hop exists in code, but whether the guest GMS's
  checkin/MCS connection is alive on the device is NOT provable
  from static code. (c) **FIXED — a real break in the broadcast
  chain:** `ProxyBroadcastReceiver.onReceive` silently dropped
  every broadcast without the `_B_|_target_` stub extra.
  `BroadcastManager.registerPackage` registers these receivers in
  :black with every guest manifest receiver's raw intent-filter —
  precisely to catch system broadcasts for guest manifest
  receivers — so the drop made that whole registration a no-op:
  guest MANIFEST receivers never received system broadcasts at
  all (guest RUNTIME receivers were unaffected — they go through
  the RegisterReceiver hook + InnerReceiverDelegate). Raw
  broadcasts are now forwarded into virtual dispatch for all
  existing users: ProxyBroadcastReceiver itself fans out using
  only pre-existing AIDL client calls (`BlackBoxCore.getUsers()`
  + `getBPackageManager().queryBroadcastReceivers(...)` per user,
  then the existing `scheduleBroadcastReceiver(...)` for each
  user with a matching manifest receiver; delivery only to
  already-running processes, same rule as the stub path), with
  a 2 s dedupe window in the receiver (several :black proxy
  receivers can match one action; key includes google.message_id
  so distinct FCM messages are never deduped). **Build note —
  do not repeat:** the first v1.0.151 attempt put this fan-out
  in a new `BActivityManagerService` helper method instead.
  Every CI build containing that BAMS change failed at
  `gradle assembleDebug` (5 runs, bisected to that file alone —
  even a pure method-extraction refactor of it), while the exact
  same code compiles clean under javac with the full classpath
  and annotation processing. The CI log could not be retrieved
  (API rate limits / auth), so the Gradle-level cause is
  UNDETERMINED; the BAMS helper was dropped and the fan-out
  moved into ProxyBroadcastReceiver as above. Do not add methods
  to BActivityManagerService without first securing a CI log. This fix is a prerequisite for
  any broadcast-driven wakeup of manifest receivers; it does NOT
  by itself admit real-GMS FCM (see (b)) and is not claimed to fix
  notifications. (d) **Push tracing — diagnosis only, no behaviour
  change:** `PushRx:` (raw C2DM broadcast arrives at a :black
  proxy receiver), `PushBcast:` (a guest — e.g. guest GMS — sends
  a C2DM broadcast inside the env), `PushSvc:` (startService for
  MESSAGING_EVENT / INSTANCE_ID_EVENT or a firebase/mqtt service
  class, with resolved=), `NotifyChannel:` (a guest creates its
  notification channels — pkg + ids). The smart report gained a
  dedicated "PushTrace" section (totals + last 10 of each) and the
  four prefixes joined the diagnosis keyword lists. (e) Facebook's
  own MQTT channel: no proxy-level block was found (services
  resolve and start virtually; setServiceForeground is hooked) —
  but katana's processes die repeatedly under LOW_MEMORY on this
  phone (device evidence), which kills any MQTT connection, and
  FCM is precisely the wakeup path that never fires. **First
  evidence to check in the next report:** the PushTrace section —
  any PushBcast with from=com.google.android.gms (guest GMS is
  dispatching FCM inside the env) or PushSvc for katana (delivery
  reached the guest's messaging service); NotifyChannel lines for
  katana (did its notification subsystem ever initialize?); and
  whether system broadcasts now reach guest manifest receivers of
  running clones (the (c) fix).
- **v1.0.152** — **Trace depth + one init guard** (device evidence:
  the v1.0.151 smart report, 2026-11-23 18:58). What that report
  proved: PushRx=0 and PushBcast=0 (guest GMS never dispatches FCM
  inside the env — the v1.0.151 structural verdict stands),
  NotifyPost still 0, NotifyCancel=19, NotifyChannel=21 (katana's
  notification subsystem IS initialized), PushSvc=14 — katana's
  `MqttServiceV2` resolves and starts repeatedly (15:26–15:57) but
  nothing showed whether the connection lives. Host main-process
  MainStall: ~6,004–6,009 ms blocks at 17:07 / 17:17 / 17:28 /
  17:38 (a ~10-minute cadence — the recurring "CloneMe isn't
  responding"), plus one 5,690 ms stall in :p3. And :black died
  with INIT_FAILURE on SCREEN_OFF (18:38:47) and SCREEN_ON
  (18:58:06) broadcast wakes, amid repeated SIGNALED deaths of
  main/:black between 18:13–18:51. Changes: (a) **MainStall stack
  capture** — the ClonerApp watchdog now appends the main thread's
  top 12 stack frames (`MainStallStack:` lines) in the same stall
  episode, sampled once per episode from
  `Looper.getMainLooper().thread` (deliberately NOT
  getAllStackTraces, which the watchdog avoids for hang risk);
  zero cost when there is no stall. (b) **MqttTrace** — a new tag
  in the report's PushTrace section: AMS-level startService/
  stopService for mqtt classes (IActivityManagerProxy, beside
  PushSvc) plus guest-process create / startCommand / destroy
  for mqtt services (AppServiceDispatcher, all destroy paths:
  whole-process onDestroy, stopService, unbind). Create→destroy
  spacing is the service's lifetime — the framework-visible
  proxy for whether MQTT can carry pushes while alive. Socket-
  level connects are NOT hooked (OsStub hooks only getuid/stat;
  a connect hook sits on the hot path of ALL guest traffic and
  is neither minimal nor safe) — do not expect socket proof
  from this tag. (c) **:black cold-wake guard + tracing** — the
  exact INIT_FAILURE mechanism is NOT provable from static code
  (candidates: an Error escaping the Exception-only init catches
  under memory pressure, or the system killing the process
  mid-init during a broadcast wake). Two small moves: the
  process-survival catches in ClonerApp (attachBaseContext /
  doCreate) and BlackBoxCore.doCreate now catch **Throwable**
  and write a `BlackInit:` trace line, so an init Error degrades
  to fallback/NoEngine instead of killing the process; and
  ProxyBroadcastReceiver writes `BlackWake:` for SCREEN_ON/OFF
  so the next report can line up wake → init markers
  (doCreate start/completed, BlackInit) → death record. No
  BActivityManagerService changes (per the v1.0.151 build note).
  **First evidence to check in the next report:** MainStallStack
  frames under a ~6 s stall (the 10-minute cadence suggests a
  periodic job — the frames should name it), MqttTrace
  create→destroy lifetimes for MqttServiceV2, BlackWake lines
  vs :black deaths, and any BlackInit line naming a throwable.
- **v1.0.153** — **Smart-report completeness ONLY** (device
  evidence: the owner's screenshot, 2026-11-23 19:24 — the
  v1.0.152 report **ends mid-word inside the app's own report
  dialog**, at "--- PushTrace (আলাদা অংশ) ---\nPu"). Root
  cause found in `MainActivity.generateReport`: the finished
  report string was hard-capped — `reportText =
  sb.toString().take(20000)` — and by the time the builder
  reached the PushTrace section the text was already ~20,000
  chars, so everything from "Pu…" on (PushTrace details,
  MqttTrace, tombstone, death logs, trace tail) was silently
  cut. NOT a capped file read: the trace is read in full and
  every section IS assembled into the StringBuilder; only the
  final hand-off to the dialog truncated it. Changes (report
  builder only, no behaviour changes anywhere else): (a) the
  20,000-char cap is **removed** — the dialog now receives the
  complete report; (b) every generated report is also written
  in full to **`smart_report.txt`** in the app's external
  files dir (`/storage/emulated/0/Android/data/com.example.
  cloner/files/`, the path the report header already prints),
  and when that write succeeds the dialog text gets one
  Bengali note line at the top saying so — the file is the
  reliable way to share a complete report; (c) the PushTrace
  section (incl. the MqttTrace block) moved to **right after
  the per-clone summary (সারাংশ)**, before the crash/death
  sections, and a dedicated **MainStall / MainStallStack**
  section (totals + last 10 each) now sits just before it —
  previously MainStall lines only surfaced inside the grouped
  diagnosis section. All other sections and their relative
  order are unchanged. **First evidence to check:** a
  v1.0.153 report (or its smart_report.txt) that runs to the
  true end ("--- ট্রেস (শেষাংশ) ---" last), with the early
  PushTrace/MqttTrace and MainStallStack sections present —
  that report finally carries the v1.0.152 evidence §7 items
  5 and 6 are waiting on.
- **v1.0.154** — **ANR fix (root-caused) + broadcast-wake failure
  capture** (device evidence: the v1.0.153 smart report,
  2026-11-23 20:47 — the first complete report since the
  take(20000) fix). What that report proved: (A) **ANR root
  cause, stack-proven.** MainStallStack under the ~6 s stalls
  (6,003–6,009 ms at the usual ~10-min cadence, plus 6,546 ms
  in :p3): `FutureTask.awaitDone → FutureTask.get →
  ContentProviderCompat.call → ProviderCall.callSafely →
  BlackBoxCore.getServiceInternal (BlackBoxCore.java:393) →
  BlackBoxCore.getService → BlackManager.getService →
  BActivityManager.onServiceUnbind (BActivityManager.java:270)
  → AppServiceDispatcher.onUnbind`. On a guest service unbind,
  the main thread made a SYNCHRONOUS provider call to :black
  and parked on FutureTask.get until timeout whenever :black
  was slow/dead/restarting. (B) **Broadcast-wake INIT_FAILURE
  deaths are broader than the v1.0.152 guard:** 20:25:48
  :black via ProxyBroadcastReceiver (SCREEN_OFF), 20:25:56
  :p0 via guest receiver X.1Dt (SCREEN_OFF), 20:26:05 :p1 via
  com.google.android.gms.scheduler.DeviceStateReceiver
  (SCREEN_OFF), 20:25:28 :p2 via X.0Agj (SCREEN_OFF),
  20:36:34 :p0 via com.google.android.gms.gcm.
  DozeChangeReceiver (LIGHT_DEVICE_IDLE_MODE_CHANGED) — all
  "user request after error". The .152 Throwable catches
  cover ClonerApp attachBaseContext/onCreate and
  BlackBoxCore.doCreate — and ClonerApp IS the Application
  class for every host process (main, :black and the :p0…:pN
  stubs all run it), so Application-stage init was already
  guarded; the fatal stage sits AFTER that: in receiver
  dispatch (ProxyBroadcastReceiver.onReceive's stub path
  caught only RemoteException, and ProxyBroadcastRecord.
  create was unguarded) or in guest bind (BActivityThread.
  bindApplication's call sites were unguarded). (C) **MQTT:**
  MqttTrace shows katana + orca MqttServiceV2 create/
  startCommand firing in bursts (19:24–19:28; PushSvc=48
  cumulative), but PushRx/PushBcast still 0 and NotifyPost
  still 0 (owner confirms: no clone notifications) — the
  services start, but the host processes keep dying per (B),
  so no connection survives. No MQTT code changes this
  build; (B) is the lever. Changes: (1) **ANR fix** — new
  `BlackBoxCore.getServiceCachedOnly(name)` (service-map hit
  + isBinderAlive only; no provider call, no wait) and
  `BlackManager.getServiceCachedOnly()` (own live cache
  first, then the core cache; builds the AIDL interface from
  the cached binder). `BActivityManager.onServiceUnbind`
  (client-side fake.frameworks — NOT the server-side
  BActivityManagerService) now uses the cached-only lookup
  and traces `UnbindSvc: cached=true/false`. Null was
  already tolerated on this exact path (onServiceUnbind
  returned null and AppServiceDispatcher.onUnbind returned
  false early — the same end state the old code reached
  after its ~6 s block when :black was dead), and when the
  binder IS cached (the common case) the remote unbind
  proceeds exactly as before. `getService()` semantics for
  all other callers are UNCHANGED. No BActivityManagerService
  changes anywhere. (2) **Wake-death capture + one safe
  swallow** — `ProxyBroadcastReceiver.onReceive`'s whole
  body now sits inside a Throwable guard: any throwable is
  written to the init trace as `BlackInitFail: receiver
  wake action=<action> -> <class>: <message>` and SWALLOWED
  (a broadcast receiver must never crash its host process;
  dropping one wake broadcast is recoverable — the next
  broadcast or launch re-triggers delivery — and a taken
  PendingResult is finished in the catch so the broadcast
  cannot time out either). `BActivityThread.bindApplication`
  (both the handler-posted and the direct call site) now
  traces `BlackInitFail: guest bind <pkg> -> <class>:
  <message>` and RETHROWS — swallowing there is NOT safe:
  handleBindApplication sets mBoundApplication mid-way
  (isInit() flips true), so a half-bound process kept alive
  would be poisoned; the failure stage can be read off the
  existing start / got packageInfo / done markers beside
  the BlackInitFail line. (3) Report: `UnbindSvc` and
  `BlackInitFail` added to the diagnosis + rare keyword
  lists in MainActivity.kt. **First evidence to check in
  the next report:** UnbindSvc lines (cached=true expected
  in normal use; cached=false lines are the former ANR
  moments, now free), the MainStall/MainStallStack totals
  (the ~6 s / ~10-min cadence should be gone or sharply
  reduced — any remaining stall's stack names the next
  path), BlackInitFail lines (receiver-wake lines name the
  exact throwable that used to kill :black; guest-bind
  lines name the bind failure), and the death records
  (INIT_FAILURE on SCREEN_ON/OFF wakes should drop if the
  receiver guard is the operative fix).
- **v1.0.155** — **Guest stall capture + launch timing
  (tracing only — NO ANR cure claimed)** (device evidence:
  the v1.0.154 smart report, 2026-11-24 01:38, plus the
  owner's verdict 01:45–01:51). What that round proved:
  (A) The v1.0.154 unbind fix worked exactly as designed —
  `UnbindSvc: cached=true` ×14, zero `cached=false`, and
  the old ~6 s unbind stack is GONE from MainStallStack.
  (B) But the owner still got "CloneMe isn't responding"
  MANY times on .154, returning after Wait — **his verdict
  outranks the trace** (§5 rule), so the ANR is NOT cured;
  the remaining stalls live somewhere the old watchdog
  could not see. The whole .154 window produced ONE
  captured stall: a guest process (:p3) blocked 4,938 ms
  in `ZipFile.<init>`/initCEN — just over the 4 s
  threshold. (C) The owner's refined symptom: after
  tapping a shared-post link in the Messenger clone, the
  Facebook clone opened and then HEAVY actions stalled —
  feed/photo posts scrolled fine, but tapping Reels
  videos did nothing and other heavy actions failed to
  respond (partial responsiveness). The trace shows a
  4-activity deep-link launch burst inside katana at
  01:37:08–09 (IntentUriHandler →
  StoryDeepLinkLoadingActivity →
  NotificationsLandToFeedDeepLinkLoadingActivity ×2 →
  TopOfFeedHighlightLaunchActivity ×2). Prime suspect
  now: guest-process main-thread stalls of ~2–4 s on
  heavy launches — under the old 4 s capture threshold,
  and invisible after the single first sample. Changes
  (tracing only): (1) **Watchdog** (ClonerApp, runs in
  every host process): in guest stub processes (:pN) the
  stall threshold drops to 2 s (ticker 500 ms, watchdog
  1 s; host main/:black unchanged at 4 s/2 s/3 s), each
  stall episode now records up to SIX stack samples
  (first = the `MainStall:` line, repeats =
  `MainStallStack: == sample N ==` headers), and every
  sample is tagged `fg=<pkg>/<class>` (last resumed
  activity in that process) or `launching=<class>` (a
  launch is pending) from new BActivityThread statics.
  The 8 s ANR crash-file logic is unchanged. (2)
  **Launch timing**: `HCallbackProxy.handleLaunchActivity`
  records the launch start
  (`BActivityThread.noteLaunchStart155`);
  `AppInstrumentation.callActivityOnResume` computes
  start→resume elapsed and writes `LaunchTime: pkg=…
  activity=… elapsed=…ms`, plus `SlowLaunch:` when
  elapsed > 2,500 ms. The report gains a dedicated
  "LaunchTime / SlowLaunch" section right after MainStall
  (totals + last 10 each), both keywords join the
  diagnosis/rare lists, the MainStallStack print window
  widens to the last 30 lines so repeat samples survive,
  and everything flows into smart_report.txt as before.
  (3) The launch path was re-read for another .154-class
  block (a synchronous binder/provider wait on a main
  thread): the only synchronous wait there is
  `bindApplication` itself — the framework-mandated bind
  the launch cannot proceed without, NOT a tolerated-null
  path like onServiceUnbind — so no fix was attempted;
  tracing only, per the one-hypothesis rule. No
  BActivityManagerService changes. **First evidence to
  check in the next report:** guest (:pN) MainStall
  episodes with repeat samples + fg tags (do the stacks
  sit in launch/video paths?), SlowLaunch lines naming
  the Reels/video activities and their elapsed times,
  and whether the owner's popup count changes at all —
  his count is the verdict, not the trace totals.
- **v1.0.156** — **Startup-ANR chain + fresh-clone
  broadcast crash + guest unbind stall (three fixes)**
  (device evidence: the second v1.0.154 report,
  2026-11-24 13:12 — a REAL captured ANR with full
  stacks — plus the v1.0.155 report, 2026-11-24
  13:34:53, and the owner's answers 13:35–13:37).
  (1) **Fresh-clone broadcast crash** (the owner's
  "second Facebook clone opens then closes
  immediately"): katana user-1 launched to its login
  screen twice, fast (LaunchTime 1,624 ms / 836 ms,
  SlowLaunch 0), then died at 13:34:50 — NPE reading
  `PackageItemInfo.packageName` on a null object inside
  `BActivityManagerService.sendBroadcast`, rethrown
  through the Parcel into the guest's
  `firebase-iid-executor` thread via
  `BActivityManager.sendBroadcast` ←
  `IActivityManagerProxy$BroadcastIntent.hook`;
  uncaught there, the whole guest process died. The
  IID broadcast path fires on a FRESH clone's first
  registration, which is why the long-registered
  user-0 katana never hits it. Fix is CLIENT-side only
  (BActivityManagerService stays untouched — §8 rule):
  `BActivityManager.sendBroadcast` now also catches
  RuntimeException (the AIDL-rethrown server exception
  is NOT a RemoteException, so the old catch missed
  it), traces `BcastGuard: svc …` and returns null —
  the pre-existing service-null flow; the
  BroadcastIntent hook additionally wraps the call in
  a Throwable guard tracing `BcastGuard: hook …`.
  Broadcasts are fire-and-forget, so a swallowed
  failure is benign. (2) **Startup ANR chain**
  (captured ANR 2026-11-24 04:46:08 on .154):
  (a) host main thread blocked 6,003 ms in
  `ClonerApp.onCreate → BlackBoxCore.doCreate →
  ensureBlackProcessInitialized →
  isBlackProcessRunning → ProviderCall →
  FutureTask.get`. `ensureBlackProcessInitialized`
  (sole caller: doCreate) no longer waits on the main
  thread: a new `isBlackProcessRunningCached()`
  answers from memory only (a volatile seen-running
  flag, set by isBlackProcessRunning on success, or
  any live cached service binder); on a cold start it
  calls startBlackProcess() and runs the SAME bounded
  verification (≤5 checks, 500 ms apart) on a daemon
  background thread, tracing
  `BlackInitAsync: running=…`. Init semantics are
  preserved because the lazy path (getServiceInternal
  / areServicesAvailable) still does its own bounded
  provider fetch at first real use — only the startup
  wait moved off the main thread. (b) :black main
  thread blocked 6,006 ms in `Settings.scanPackage →
  updatePackageLP → FileUtils.toByteArray →
  Linux.readBytes`: the stream reader used a
  **100-byte buffer**, so reading each multi-MB
  package-conf parcel took tens of thousands of
  native reads. The buffer is now 8,192 bytes —
  identical bytes out, ~80× fewer reads. The scan
  itself stays synchronous ON PURPOSE:
  `BlackBoxSystem.startup()` consumes `mPackages`
  immediately after systemReady() (the pre-install
  check), so deferring the scan would race a real
  consumer. (3) **Guest unbind stall** (:p3, 4,282 ms,
  11:45:18 on .154): the guest-side
  `IActivityManagerProxy$UnbindService.hook →
  BActivityManager.unbindService` made a synchronous
  binder call into :black on the guest main thread.
  unbindService is void and result-free, so
  `BActivityManager.unbindService` now dispatches the
  remote call to a background executor (trace
  `UnbindAsync: queued`); the v1.0.154
  server-callback cached fix is untouched. **First
  evidence to check in the next report:** BcastGuard
  lines + whether a fresh clone survives its first
  open; BlackInitAsync lines and whether the ~6 s
  startup MainStall entries stop; UnbindAsync lines
  vs. any remaining :pN unbind stacks. The owner's
  verdict governs, as always.
- **v1.0.157** — **Startup receiver-registration deferred
  (the :black cold-wake / ANR-loop fix)** (device evidence:
  the v1.0.156 smart report, 2026-11-25 10:31, plus the
  owner's verdict 10:32 — **the popup is NOT fixed on
  .156**: "CloneMe isn't responding" keeps returning even
  after he closes it). What that report proved: (A) The
  .156 fixes are all live (BlackInitAsync running=true,
  UnbindSvc cached=true ×12, 8 KB scan buffer) and there
  is NO new crash file — the only crash is the old
  .155-era katana IID NPE (24 Nov 13:37), so the fresh-
  clone broadcast crash did not recur. (B) But :black
  still died with **INIT_FAILURE on SCREEN_OFF
  (09:48:54) and SCREEN_ON (10:04:05) broadcast wakes**
  — "user request after error: Broadcast of Intent …
  cmp=…ProxyBroadcastReceiver" — amid repeated SIGNALED
  deaths of main+:black pairs (09:50 / 10:08 / 10:18 /
  10:29): the restart loop behind the returning popup.
  (C) MainStall 09:09–09:14: main thread blocked
  2,005–6,011 ms across main, :p0–:p6 and :black; the
  :black stack is the smoking gun —
  `SystemCallProvider.onCreate → BlackBoxSystem.startup
  → BActivityManagerService.systemReady →
  BroadcastManager.startup (line 77) → registerPackage
  (line 89) → ContextImpl.registerReceiver →
  registerReceiverWithFeature` binder wait, 6,011 ms.
  Root cause: `BActivityManagerService.systemReady()`
  does exactly one thing — `BroadcastManager.startup()`
  — and that method synchronously registered a
  ProxyBroadcastReceiver with the real AMS for EVERY
  intent filter of EVERY guest manifest receiver, on
  :black's main thread, inside process initialization
  (SystemCallProvider.onCreate). A broadcast-wake cold
  start therefore had to finish that whole binder loop
  (plus the PMS package scan) before init could
  complete; under memory pressure it blew the init
  window and AMS killed :black with INIT_FAILURE, and
  every death re-triggered the same slow startup across
  the stub pool — the loop the owner experiences as the
  endlessly returning popup. The screen wakes themselves
  are legitimate (guest manifest receivers — e.g. GMS —
  declare SCREEN_ON/OFF; the v1.0.151 fan-out delivers
  them to running guests), so the registrations are
  KEPT; only their timing moves. **Change (one file,
  `core/system/am/BroadcastManager.java`; NO
  BActivityManagerService changes):** `startup()` now
  snapshots the package list (already a copy) and runs
  the registerPackage loop on a daemon background
  thread ("BcastReg"), tracing `BcastReg: deferred done
  packages=N elapsed=Mms` at completion; `startup()`
  itself returns immediately, so :black's init no longer
  waits on the AMS registration loop. A duplicate guard
  (`mRegisteredPackages`, held under the existing
  mReceivers lock, maintained by onPackageInstalled /
  onPackageUninstalled) keeps the deferred sweep and
  the install path from ever registering a package
  twice; the sweep skips packages uninstalled after the
  snapshot, and one package's failure no longer aborts
  the rest (the old synchronous throw would have broken
  all of BlackBoxSystem.startup). Behaviour is
  equivalent in steady state: every receiver still ends
  up registered, just moments after startup instead of
  during it. Deliberately NOT changed: the PMS package
  scan stays synchronous (BlackBoxSystem.startup's
  pre-install check consumes it — see the v1.0.156
  note), and no screen-broadcast registration is
  dropped. Companion change: `BcastReg` added to the
  smart report's diagnosis/rare keyword lists
  (MainActivity.kt). **First evidence to check in the
  next report:** a `BcastReg: deferred done` line per
  :black start (registration completed, short elapsed),
  NO new ~6 s :black MainStall with the BroadcastManager
  stack, INIT_FAILURE deaths on SCREEN_ON/OFF wakes
  gone or sharply reduced, and — the verdict that
  counts — the owner's popup count.
- **v1.0.158** — **Guest-process wake budgets cut
  (popup fix round 2: the :pN cold-start init window)**
  (device evidence: the v1.0.157 smart report,
  2026-11-25 17:31, session 17:03–17:31, plus the
  owner's verdict 17:37 — **the popup STILL came on
  .157**). What .157 proved: the BroadcastManager
  stall is gone (no registerReceiver stack), :black
  now SURVIVES SCREEN_OFF (17:31:25) and SCREEN_ON
  (17:31:40) wakes (BlackWake lines, no :black death),
  no Java crashes, katana launches in 80–152 ms. What
  remains: **:p0 died INIT_FAILURE at 17:31:38 on the
  SCREEN_OFF wake** ("user request after error:
  Broadcast of Intent { act=SCREEN_OFF …
  cmp=com.example.cloner/X.0Agj }", rss ~1,041,096
  kB), amid LOW_MEMORY kills of :p3/:p5 in the same
  minutes — and the only two MainStalls (~2 s, both in
  :p2) name the guest startup path itself: (a)
  `ClonerApp.attachBaseContext → BlackBoxCore.
  doAttachBaseContext (1067) → HookManager.init (228)
  → injectAll (257) → ClassInvocationStub.injectHook
  (58) → initAnnotation (71) → Class.getAnnotation →
  AnnotationMember.<init>`; (b) `ClonerApp.onCreate →
  BlackBoxCore.doCreate (1089) →
  ensureProperInitialization (1955) →
  ServiceManager.initBlackManager (71) →
  BlackBoxCore.areServicesAvailable (304) →
  CountDownLatch.await` — the main thread PARKED.
  Root cause: a guest stub process cold-started by
  AMS (here, for a broadcast delivery) must finish
  the FULL client init on its main thread before it
  can answer: two native waits with 8 s caps in
  setEssentialProperties, the ~90-stub hook injection
  (in this fork `isBlackProcess()` means BAppClient —
  BlackBoxCore:1367 — so guests take the full list),
  NativeCore.init waited up to 8 s in
  ensureProperInitialization, and the 8-service fetch
  parking the main thread up to 6 s on the latch. The
  caps alone sum to ~30 s; under memory pressure the
  real cost blows the init window, AMS kills the
  process (INIT_FAILURE), and a main thread parked
  for seconds during a broadcast-triggered start is
  what raises the package's "isn't responding"
  dialog. The virtual broadcast path itself never
  cold-starts a guest (BActivityManagerService.
  scheduleBroadcastReceiver only delivers to
  processes with a live bActivityThread), so nothing
  engine-side needs a guest's full init before it can
  *start* — and both waits already degrade by design
  (latch timeout → proceed, missing binders lazy-load
  later via getServiceInternal; native timeout →
  "skipped/timed out, continuing", the native work
  keeps running on its daemon thread). **Changes
  (NO BActivityManagerService changes; the .157
  BroadcastManager fix untouched):** (1)
  `BlackBoxCore.areServicesAvailable()` — for guest
  (BAppClient) processes the service-latch budget is
  1500 ms instead of 6000 ms (Main/Server unchanged);
  a `SvcWait:` trace records proc type, budget,
  elapsed, done, and cached count (n/8). (2)
  `BlackBoxCore.ensureProperInitialization()` — for
  guest processes the NativeCore.init wait is
  3000 ms instead of 8000 ms (others unchanged). (3)
  `HookManager.injectAll()` — timing trace only
  (`HookInit: stubs=N elapsed=Mms`); injection
  semantics deliberately NOT changed this round (no
  compiler to verify a restructure, and every guest
  entry point depends on the hooks being complete) —
  the trace exists so the next report shows whether
  injection is now the dominant remaining cost. (4)
  `SvcWait` + `HookInit` added to the smart report's
  diagnosis/rare keyword lists (MainActivity.kt).
  When :black is healthy the 8 parallel service calls
  return in well under 1.5 s, so normal launches are
  unchanged; the budgets only bite when :black is
  cold/slow — exactly the wake-under-pressure case
  that was dying. **First evidence to check in the
  next report:** SvcWait lines for client processes
  (budget=1500ms; done=true with a healthy :black;
  done=false + low cached count = the budget fired
  and lazy-load took over), HookInit elapsed for :pN
  processes, NO new :pN INIT_FAILURE on SCREEN_ON/OFF
  wakes, and — the verdict that counts — the owner's
  popup count. **Honest limits:** .158 does not
  reduce hook-injection cost, does not touch the
  attach-path native waits in setEssentialProperties
  (8 s caps ×2), and cannot fix LOW_MEMORY reaping
  itself; if the popup persists and HookInit shows
  injection dominating, that is the .159 target. Also
  unidentified: the exit record's component
  `com.example.cloner/X.0Agj` is an R8-obfuscated
  name and no mapping file ships in the repo, so the
  exact AMS-side component that started :p0 cannot be
  named from source — the fix does not depend on it,
  since EVERY guest cold start pays the same init
  path.
- **v1.0.159** — **Notifications: connection-level
  tracing (NO behaviour change — an investigation
  build, deliberately)** (owner's order 2026-11-25
  18:31: "নোটিফিকেশন ঠিক কর", right after the
  v1.0.158 delivery; the .158 popup verdict is still
  his to give). The push chain was re-traced in code
  this round; what is now PROVEN vs still unknown:
  (a) **How a guest perceives GMS:**
  `IPackageManagerProxy.GetPackageInfo` answers for
  com.google.android.gms from the VIRTUAL PM only
  (GMS is not in AppSystemEnv's open-package list, so
  there is no real-PM fallthrough; not cloned →
  null). The `PMCheck` trace only prints when the
  target is virtually installed (tracePmCheck), and
  the .157 report HAS PMCheck lines for
  target=com.google.android.gms found=true — so
  **GMS is installed as a clone in katana's user
  slot**, with its real versionCode: every
  GMS-availability check inside katana passes
  against the GUEST GMS. Katana therefore registers
  for FCM and has no reason to take a no-GMS
  fallback. (b) **Where registration traffic goes:**
  Firebase's registration leaves the app via
  bindService to a com.google.android.gms component,
  a c2dm REGISTER broadcast, or startService.
  bindService lands in
  `IActivityManagerProxy.BindServiceCommon`:
  virtual resolution is plain manifest resolution
  (`BPackageManagerService.resolveService` — no
  permission checks anywhere in the service path);
  resolved → virtual bind into the stub process
  hosting guest GMS; unresolved-but-virtual →
  **silently returns 0** (the caller sees a plain
  bind failure and Firebase retries with backoff
  forever). Until this build NONE of those outcomes
  was traced for GMS targets. The c2dm broadcast
  net (PushBcast) covers only the three c2dm
  actions, yet the v1.0.156 crash proves katana's
  firebase-iid thread DOES send a broadcast on
  first registration — if that broadcast uses a
  cloud-messaging action, the old net misses it
  and PushBcast=0 overstates "nothing attempted".
  (c) **Provider entanglement:** the
  GetContentProvider hook forwards every authority
  containing com.google.android.gms /
  com.google.android.gsf to the REAL GMS/GSF
  providers (host package as caller on Q+) — so
  the guest GMS reads the REAL GMS's provider
  state (gservices etc.); whether its own
  checkin/MCS can authenticate independently of
  the real GMS's identity is NOT provable from
  static code. (d) **MQTT:** the dispatcher
  already traces create/startCommand/destroy for
  MqttServiceV2, but katana's BINDS to its own
  MQTT service were invisible — the ~5 s-apart
  startService/startCommand pairs in the .157
  report cannot be separated into "cold-process
  start latency" vs "bind failing and retrying"
  without them. (e) The guest GMS clone exists
  and its manifest receivers wake stub processes
  (v1.0.154 report: gms.scheduler.DeviceState
  Receiver, gms.gcm.DozeChangeReceiver), but
  whether its checkin/GCM services ever START is
  unobserved — GMS service starts were not
  traced. **Changes (one engine file,
  `fake/service/IActivityManagerProxy.java`, plus
  report plumbing in MainActivity.kt; NO
  BActivityManagerService changes; the .157/.158
  fixes untouched):** (1) `GmsBind:` —
  BindServiceCommon now traces every bind whose
  target package is com.google.android.gms/gsf,
  at every return point: from / target / action /
  component / resolved / outcome (virtual =
  reached the guest GMS through the virtual AMS;
  failed0 = caller silently got bind-failure 0;
  real = fell through to the real AMS under the
  host identity). (2) The same return points trace
  MQTT-class binds as `MqttTrace: bindService
  from=… class=… outcome=…`. (3) `GmsSvc:` — the
  StartService hook traces every startService
  whose target is a Google package (gms/gsf/
  vending), from any caller including guest GMS
  itself, with resolved=. (4) `FcmReg:` — the
  BroadcastIntent hook additionally traces
  broadcasts targeted at com.google.android.gms
  or carrying cloudmessaging/firebase actions
  (c2dm actions excluded — they stay in
  PushBcast). (5) Report: GmsBind/GmsSvc/FcmReg
  added to both keyword lists and as totals +
  last-10 blocks in the PushTrace section.
  **Why no fix in this build:** every candidate
  was weighed and none is provable — lying in the
  PM ("GMS not installed" for katana) to force an
  MQTT fallback is speculative (current FB builds'
  fallback behaviour is not in our code) and would
  break katana's other GMS consumers; a
  keep-alive for the MQTT process has no
  engine-side kill to defend against (its deaths
  are LMK + the wake failures .157/.158 attack);
  c2dm routing has nothing arriving to route
  (the .151 fan-out stands). Shipping a guess
  here risks the working FB/Messenger clones.
  **First evidence to check in the next report:**
  GmsBind lines for katana (present+virtual =
  registration reaches guest GMS → the guest
  GMS's liveness is the problem; present+failed0
  = the bind itself is the break, with the exact
  component named; absent = katana never even
  binds → read with FcmReg); FcmReg lines (the
  firebase-iid broadcast's real action); GmsSvc
  lines with from=com.google.android.gms (guest
  GMS starting its own services — pair with the
  existing "handleBindApplication start/done:
  com.google.android.gms" markers); MqttTrace
  bindService outcomes for MqttServiceV2; and
  NotifyPost, still the bottom line.
- **v1.0.160** — **Popup fix round 3: the package-scan path**
  (device evidence: the .159 smart_report.txt of 2026-11-26
  16:21, pasted in full by the owner; owner ordered this
  build directly: "তাড়াতাড়ি ঠিক করে দাও"). What that report
  proved: (A) the notification traces are STILL empty
  (GmsBind/FcmReg/GmsSvc/MqttTrace all 0) because the report
  contains NO clone activity at all — it even prints "কোনো
  ক্লোন নেই" — so the .159 evidence is still outstanding;
  do not treat .160 as a notifications build. (B) The popup
  path is now stack-proven end to end: 11 MainStalls of
  4.6–6.5 s across the day in main and :black, and BOTH
  stacks name the package-scan path —
  BPackageManagerService.mPackageChangedHandler.onReceive
  (PACKAGE_ADDED/REMOVED, delivered on the main thread) →
  Settings.scanPackage → updatePackageLP, blocking once in
  FileUtils.toByteArray (whole conf read) and once in
  BPackageSettings.save → BPackage.writeToParcel (whole
  package parcel). Every host app install/update fires that
  broadcast, so the freeze recurs all day; :black also died
  INIT_FAILURE on 9 SCREEN_ON/OFF wakes the same day.
  Changes (BPackageManagerService + Settings ONLY —
  BActivityManagerService untouched, per the standing rule):
  (1) the receiver no longer scans inline: schedulePackageScan()
  runs scanPackage() on a dedicated daemon "PkgScan"
  single-thread executor, coalescing bursts into at most one
  extra pass, with a `PkgScan: off-main done elapsed=` trace
  (keyword added to the smart report's diag lists in
  MainActivity.kt); (2) Settings.updatePackageLP no longer
  calls save() on every load — the conf was just read from
  disk unchanged and save() parcels the whole package — it
  now saves ONLY when the system-app reinstall branch
  actually replaced the pkg (pkgChanged). systemReady()'s
  own scan stays synchronous (startup ordering) but is now
  read-only and much cheaper. **First evidence to check:**
  PkgScan lines present, NO new MainStall stacks through
  onReceive/scanPackage/save, :black INIT_FAILURE wake
  deaths gone or sharply reduced, and — decisive as always —
  the owner's popup count.
- **v1.0.161** — **Popup fix round 4: :black init no longer
  runs the package scan** (device evidence: the .160
  smart_report.txt of 2026-11-26 20:10 + the owner's verdict
  the same evening — **the popup STILL comes on .160**; the
  owner ordered this build directly). What the .160 report
  proved: (A) all 8 clones exist (katana ×2, orca, WhatsApp,
  Chrome, Telegram, aura ×2) — the .159 report #2 "কোনো
  ক্লোন নেই" line was a misread; (B) NO new MainStall after
  the 17:13 install — the 11 listed stalls are all .159-era
  (03:08–12:57), so the .160 scan-off-main fix holds in the
  trace; (C) BUT :black still died INIT_FAILURE on screen
  wakes on .160 (19:32 SCREEN_OFF, 19:35 SCREEN_ON, 19:38
  SCREEN_ON, 20:10 SCREEN_OFF, plus main-process SIGNALED
  deaths 19:37 / 20:08) — the wake-death/restart churn is
  the remaining popup suspect; (D) no clone was opened after
  the install, so the PushTrace/GmsBind evidence is STILL
  empty — notifications remain unanswered, and no PkgScan
  line appeared (the PACKAGE_ADDED receiver never fired in
  the window). Root cause chased in code: :black binding
  runs SystemCallProvider.onCreate →
  BlackBoxSystem.startup() → every service's systemReady()
  synchronously on the main thread, and
  BPackageManagerService.systemReady() still ran the FULL
  package scan + component-resolver rebuild inline (the
  .160 entry above kept it synchronous "for startup
  ordering") — so on wakes the process could not finish
  binding inside the window. Changes
  (BActivityManagerService untouched, per the standing
  rule): (1) BPackageManagerService.systemReady() now
  starts a daemon "PmReady" thread that runs the exact
  same scan + resolver rebuild, then counts down a ready
  latch and traces `PmReady: deferred done packages=N
  elapsed=Xms` (keyword added to the smart report's diag
  lists); a new public awaitReady(timeoutMs) exposes the
  latch. (2) BroadcastManager.startup()'s deferred BcastReg
  thread now awaits PM ready (20 s) BEFORE snapshotting
  the package list — otherwise it would snapshot an empty
  list and register no guest receivers. (3) BlackBoxSystem
  .startup()'s pre-install pass (which reads isInstalled)
  now runs on a "PreInstall" daemon thread that awaits
  the same latch, instead of blocking onCreate. **First
  evidence to check:** PmReady lines present; BcastReg
  still completes with packages=15; :black INIT_FAILURE
  wake deaths gone or sharply reduced across a full
  evening of screen on/off; and — decisive as always —
  the owner's popup count on .161.

## 7. Open problems (start here)

1. **Chrome clone crashes while browsing / on YouTube.** The browser process
   stays alive, but tab renderers die (`primaryMainFrameRenderProcessGone`,
   termination status 0; native `_exit(0)` traces from `ChildProcessMain`).
   Long-standing across many versions; mitigations exist (RELRO nulling,
   `--no-sandbox`, keep-alive on unbind/destroy) but the churn persists.
   **Already tried — do not repeat blindly:** RELRO blocking/nulling
   (v1.0.80s), `--no-sandbox` append (v1.0.84), child keep-alive on
   unbind/destroy (v1.0.132), `KeepChildProcessAfterIPCReset` enable-features
   merge (v1.0.139 — whether the flag truly reaches the child was unverifiable
   until the v1.0.143 `ChromeArgs:` line). A v1.0.127 experiment proved the
   parcel rewrite itself is NOT the cause (untouched forward dies identically).
   **First evidence to check in a v1.0.143+ report:** the `ChromeArgs:` line —
   does the child actually receive `KeepChildProcessAfterIPCReset` in
   `--enable-features`? If yes and renderers still die, that flag does not
   cover this exit family; say so plainly and weigh effort vs. a work-profile
   second Chrome for this one app.
2. **Facebook → Messenger handoff — FIXED in v1.0.149, verified
   2026-11-23.** Root cause was the missing `queryIntentActivities`
   hook (fell through to the real PM, where virtual apps are invisible).
   Device proof: PMCheck found=true in both directions (katana→orca
   13:06:31, orca→katana 13:05:46), `CrossClone:` fired on the owner's
   Messages tap (13:09:59), and the owner confirms Messenger opens.
   Remaining nuance: the first open after a LOW_MEMORY kill wave is
   slow (full cold start of the Messenger process); that is the
   memory-pressure problem in item 5's family, not a handoff defect.
   The **wrong-process launch** behind the 2026-11-22 16:12:34 katana
   crash is likewise guarded in v1.0.149 (HCallbackProxy package guard
   + `launch assigned` traces); no LAUNCH MISMATCH lines and no fbApp
   crash repeat in the 2026-11-23 reports. (v1.0.142's
   chooseBestActivity tie→first-match change and StartActivity
   cross-clone rescue remain in place, harmless.)
3. **Several other apps don't open properly** (WhatsApp / Gemini / Google-app
   class, per the user). Take them one at a time, evidence first (a report
   from a build whose verdicts can be trusted — v1.0.142+), and remember the
   rule in §5: the user's experience outranks the trace.
   **WhatsApp corroborated (v1.0.154 report, 2026-11-24):** its trace
   verdict reads "bind হয়েছে, কিন্তু অ্যাক্টিভিটি শুরু হয়নি" — bind
   completes but no activity ever starts, matching the owner's
   "খোলে না". (For Meta apps that same verdict text was once a report
   artifact — §6 v1.0.142 — but for WhatsApp no onResume marker exists
   either, so treat it as real until a device test says otherwise.)
4. **Google Photos clone** opens but crashed on a tap with
   `SecurityException: Package ... does not belong to <uid>` from
   `ICrossProfileApps.getTargetUserProfiles` (same calling-UID family as the
   v1.0.141 provider issue; there is no cross-profile proxy yet).
5. **CloneMe main-process ANR** ("CloneMe isn't responding"; the owner
   taps Wait and it later returns). Seen 2026-11-23 03:47 (crash file)
   and ~13:12 (owner-confirmed dialog, NO crash file) — both times while
   many heavy clones were restarting at once under memory pressure
   (INIT_FAILURE restarts on SCREEN_ON/OFF broadcast deliveries +
   LOW_MEMORY kills across the stub pool). v1.0.150 added `MainStall:`
   tracing only (watchdog writes one trace line per stall episode at
   >4 s blocked) — **no cure is claimed**. The launch path already runs
   off the main thread (v1.0.131). The 2026-11-23 18:58 report shows
   the stalls at ~6,004–6,009 ms on a ~10-minute cadence (17:07 /
   17:17 / 17:28 / 17:38) in the host main process, plus :black
   INIT_FAILURE deaths on SCREEN_ON (18:58:06) and SCREEN_OFF
   (18:38:47) broadcast wakes. v1.0.152 adds `MainStallStack:` frames
   to each stall episode, `BlackWake:`/`BlackInit:` tracing for the
   wake deaths, and widens the init catches to Throwable (see the §6
   v1.0.152 entry). **First evidence to check:** the MainStallStack
   frames — the cadence smells like a periodic job and the frames
   should name the blocking code path — and whether :black still
   dies on screen wakes (any BlackInit line names the throwable).
   Do not promise to fix the LOW_MEMORY deaths themselves — with
   ~150 MB per stub and many heavy clones resident, LMK reaping cached
   stubs is Android behaving normally. **Update (v1.0.153):** the
   v1.0.152 device window DID run (19:15–19:23), but its report was
   cut by the report builder's 20,000-char cap before any
   MainStallStack line (see §6 v1.0.153) — the stack evidence is
   still outstanding; read it from the first v1.0.153 report or its
   smart_report.txt, where the MainStall section now sits early.
   **Update (v1.0.154):** that report arrived (2026-11-23 20:47)
   and the stack names the culprit exactly: the guest-service
   UNBIND path making a synchronous provider call to :black on
   the main thread (FutureTask.get, ~6 s timeout — full chain in
   the §6 v1.0.154 entry). v1.0.154 fixes that path with a
   cached-only binder lookup (`UnbindSvc:` trace) and leaves
   getService() untouched for all other callers. **First evidence
   to check:** UnbindSvc lines in the next report, and whether
   the ~6 s / ~10-min MainStall cadence is gone; if a stall
   remains, its MainStallStack names the NEXT blocking path —
   do not assume unbind was the only one.
   **Update (v1.0.154 report + owner verdict, 2026-11-24):**
   the unbind fix is proven (UnbindSvc cached=true ×14, zero
   cached=false, old stack gone) — and the popup STILL came
   many times on .154 (owner, 01:45). Do not call the ANR
   fixed. The symptom has also sharpened: it strikes at
   LAUNCH time, not idle mid-use — after a Messenger→Facebook
   shared-link handoff the FB clone stayed partially
   responsive (feed scrolled) while heavy launches (Reels/
   video open) stalled. v1.0.155 instruments exactly that:
   2 s guest-process stall capture with repeat samples + fg
   tags, and LaunchTime/SlowLaunch per-activity timing (see
   §6). **First evidence to check:** :pN MainStall samples
   and SlowLaunch lines in the next report, read against the
   owner's own popup count.
   **Update (v1.0.155 report + v1.0.156, 2026-11-24):** the
   .155 window (~20 min) added NO new MainStall lines (total
   still 21, all pre-.155) and launches were fast — but the
   window is too short to conclude anything. The second
   .154 report (13:12) captured a REAL ANR (04:46:08) with
   the next root cause in full: the STARTUP chain —
   doCreate → ensureBlackProcessInitialized →
   isBlackProcessRunning blocking the host main thread
   6,003 ms, while :black's own main thread blocked
   6,006 ms reading package confs in Settings.scanPackage
   (100-byte read buffer in FileUtils.toByteArray).
   Process restarts are frequent under the LOW_MEMORY
   churn, so this startup block is the leading explanation
   for the popup returning again and again. v1.0.156
   targets all three legs (async ensure + cached check,
   8 KB read buffer, async guest unbind — see §6).
   **First evidence to check:** BlackInitAsync lines,
   absence of new ~6 s startup MainStall entries across a
   long window with restarts, and the owner's popup count.
   **Update (v1.0.156 report + owner verdict, 2026-11-25):**
   .156's three legs are live (BlackInitAsync, UnbindSvc
   cached=true ×12) and the startup MainStall entries in
   the old doCreate/scan stacks are gone — but the popup
   STILL returns (owner, 10:32), and the report shows why:
   :black still died INIT_FAILURE on SCREEN_OFF (09:48:54)
   and SCREEN_ON (10:04:05) broadcast wakes, and its main
   thread blocked 6,011 ms in the one heavy startup step
   .156 did not touch — BroadcastManager.startup's
   synchronous registerReceiver loop (called from
   BAMS.systemReady inside SystemCallProvider.onCreate,
   i.e. during :black process init). v1.0.157 defers that
   registration to a background thread (see §6) — the
   wake itself is legitimate and kept; the init weight
   was the failure. **First evidence to check:**
   `BcastReg: deferred done` lines, no new :black
   BroadcastManager-stack stalls, INIT_FAILURE wake
   deaths gone/reduced, and the owner's popup count.
   **Update (v1.0.157 report + owner verdict,
   2026-11-25):** .157's fix is proven for :black
   (BcastReg deferred registration; :black survives
   both screen wakes; the old 6 s BroadcastManager
   stack is gone; no crashes; fast launches) — and
   the popup STILL came (owner, 17:37). The failure
   has moved into the GUEST processes: :p0 died
   INIT_FAILURE on the SCREEN_OFF wake (17:31:38,
   ~1 GB rss, amid LOW_MEMORY kills), and the only
   two MainStalls (~2 s, :p2) name the guest startup
   path — HookManager.injectAll's annotation scan
   during attachBaseContext, and the
   areServicesAvailable latch park during doCreate.
   v1.0.158 cuts the guest budgets on those waits
   (latch 6 s → 1.5 s, NativeCore.init wait 8 s →
   3 s, guests only) and adds SvcWait / HookInit
   traces (see §6). **First evidence to check:**
   SvcWait + HookInit lines in the next report, any
   :pN INIT_FAILURE entries on screen wakes, and the
   owner's popup count. If HookInit shows injection
   now dominates the guest init window, .159 goes
   after the injection cost itself. The popup is
   still OPEN — narrowed to the guest wake path,
   not fixed.
   **Update (v1.0.158 verdict + .159 report + v1.0.160,
   2026-11-26):** the owner reports the popup on .158
   is reduced to OCCASIONAL ("মাঝে মাঝে ডিস্টাব দেয়")
   but the app also HANGS at times — not fixed. The
   .159 report (2026-11-26 16:21) then stack-proved
   the remaining all-day stall: the PACKAGE_ADDED/
   REMOVED receiver running a full package scan on
   the main thread (conf read + an unconditional
   whole-package save per package). v1.0.160 moves
   that scan to a background thread and makes the
   save conditional (see §6). **First evidence to
   check:** PkgScan traces, no scan-path MainStall
   stacks, and the owner's popup count on .160.
   **Update (v1.0.160 verdict + v1.0.161, 2026-11-26):**
   the owner reports the popup STILL comes on .160.
   Its report shows no new MainStall after install,
   but :black still dies INIT_FAILURE on screen
   wakes — because the same full package scan still
   ran synchronously inside :black's process init
   (BPackageManagerService.systemReady, called from
   SystemCallProvider.onCreate during binding).
   v1.0.161 defers that scan to a PmReady background
   thread with a ready latch for its two ordered
   consumers (BroadcastManager registration,
   BlackBoxSystem pre-install). **First evidence to
   check:** PmReady traces, :black wake deaths in
   the death-records section, and the owner's popup
   count on .161.
6. **Per-clone notifications unreliable — pinned symptom: the Facebook
   clone (com.facebook.katana) receives NO notifications at all, while
   the ORIGINAL Facebook app (outside CloneMe) notifies normally**
   (owner, 2026-11-23; total absence — not delay, not misattribution —
   and the fault is clone-side only). **Trace verdict (v1.0.150,
   two reports, 2026-11-23): never-posts.** NotifyPost = 0 across
   all clones while NotifyCancel fires normally — katana never
   calls notify() inside the clone, though its in-app bell badge
   shows 6 unread (server fetch on open works). **Investigation
   (v1.0.151, code-proven; full detail in the §6 v1.0.151 entry):**
   the post path itself is sound (proxy → BNotificationManager
   Service → real NotificationManager under the host package).
   Real-GMS FCM is structurally unreachable for clones — GMS
   dispatches FCM as a package-targeted broadcast and AMS delivers
   those only to the real package. The only possible route is the
   guest GMS clone's own MCS connection dispatching inside the
   env; whether that connection is alive is the remaining unknown.
   v1.0.151 fixed a separate real break found on the way (raw
   system broadcasts to guest MANIFEST receivers were silently
   dropped by ProxyBroadcastReceiver — now forwarded) and added
   PushRx/PushBcast/PushSvc/NotifyChannel tracing + a PushTrace
   report section to isolate the push chain to one hop.
   **First evidence to check:** the PushTrace section of the next
   report. PushBcast from=com.google.android.gms present = guest
   GMS dispatches FCM inside the env (then check PushSvc/Notify
   Post downstream); ALL push lines zero = nothing push-shaped
   ever moves inside the env — guest GMS checkin/MCS is dead or
   katana registered with the real GMS, and effort should go to
   the guest GMS's liveness (its own traces/PMCheck activity),
   not to the notification path. Also check NotifyChannel for
   katana: no line = its notification subsystem never even
   initialized in-clone. **Update (2026-11-23 18:58 report):** the
   FCM branch is now confirmed dead (PushRx=0, PushBcast=0), but
   NotifyChannel=21 (katana's subsystem initialized) and
   PushSvc=14 (its MQTT service starts repeatedly) — the MQTT
   channel is the last untried in-env route. v1.0.152 adds
   `MqttTrace` (AMS start/stop + guest create/startCommand/
   destroy) to measure that service's lifetime. **First evidence
   to check:** MqttTrace lifetimes for
   com.facebook.mqtt.service.MqttServiceV2 — long create→destroy
   gaps mean the service (and plausibly its connection) lives;
   rapid create/destroy cycling means it crash-loops under the
   same memory pressure and MQTT cannot carry pushes either.
   **Update (v1.0.153):** the v1.0.152 window showed MqttTrace
   startService/startCommand lines (19:16:15, ×7) but the report
   itself was truncated by the 20,000-char cap before the
   MqttTrace block (see §6 v1.0.153) — create→destroy lifetimes
   are still unmeasured and this evidence is still outstanding;
   read it from the first v1.0.153 report's early PushTrace
   section or its smart_report.txt. **Update (v1.0.153 report,
   2026-11-23 20:47):** MqttTrace now measures the service:
   katana + orca MqttServiceV2 create/startCommand fire in
   bursts (PushSvc=48 cumulative) — the MQTT services DO
   start — but the processes hosting them keep dying in
   broadcast-wake INIT_FAILUREs (SCREEN_OFF and idle-mode
   wakes hitting :black and the stub processes, 20:25–20:36),
   so no MQTT connection survives long enough to deliver.
   Notifications are now a PROCESS-SURVIVAL problem, not a
   notification-path problem. v1.0.154 attacks the wake
   deaths (ProxyBroadcastReceiver Throwable guard +
   BlackInitFail capture on the receiver and guest-bind
   paths; see §6). **First evidence to check:** BlackInitFail
   lines and whether INIT_FAILURE wake deaths drop in the
   next report; if the processes survive, re-read the
   MqttTrace create→destroy lifetimes — long lifetimes with
   NotifyPost still 0 would move suspicion to the MQTT
   connection itself.
   **Update (v1.0.154 report, 2026-11-24 01:38):** deaths in
   that window were one synchronised SIGNALED wave (app
   restart-like), LOW_MEMORY on :p3/:p7, and ONE INIT_FAILURE
   (:black, SCREEN_ON, 00:47:49) whose build attribution is
   uncertain (the owner was mid-Y17-flash until 00:33; .154's
   install time is unconfirmed, and the pasted report was
   truncated before any BlackInitFail section). NotifyPost
   still 0, NotifyCancel 51. Unresolved: whether the receiver
   guard changed wake deaths at all — needs a clean .154+
   window with the smart_report.txt file, not a paste.
   **Update (v1.0.159, 2026-11-25):** the owner ordered the
   notifications fix directly. The code re-trace (§6 v1.0.159)
   proved the shape of the dead end: GMS **is** cloned in
   katana's user slot (PMCheck only prints for virtually
   installed targets, and gms shows found=true), so katana's
   GMS-availability checks pass against the guest GMS and it
   registers for FCM instead of falling back — but whether
   that registration bind reaches the guest GMS, silently
   fails (BindServiceCommon returns 0 with no trace), or
   falls through to the real AMS was unobserved, as were
   the guest GMS's own service starts and the non-c2dm
   action of the firebase-iid thread's first-registration
   broadcast (proven to exist by the v1.0.156 crash).
   v1.0.159 is a tracing-only build for exactly those three
   hops: `GmsBind:` (bind outcomes to gms/gsf targets),
   `FcmReg:` (registration broadcasts beyond the c2dm
   trio), `GmsSvc:` (startService to Google packages,
   incl. from guest GMS itself), plus MQTT-class binds
   folded into `MqttTrace:`. No fix is claimed — the
   candidate fixes (PM lie to force MQTT fallback,
   MQTT keep-alive, c2dm routing) were all weighed and
   none is provable yet; see the §6 entry for the
   reasoning. **First evidence to check:** the PushTrace
   section of the first .159 report — GmsBind outcome
   for katana decides the next build: virtual → guest
   GMS liveness work (pair with GmsSvc from=gms and
   the gms bind markers); failed0 → the bind path
   itself, component named in the line; no GmsBind and
   no FcmReg at all → katana attempts nothing
   FCM-shaped in-clone and suspicion moves to its
   in-app push arming (e.g. its own settings/token
   state), not to transport.

7. **Fresh clone opens then closes immediately (second
   Facebook clone, katana user 1) — FIXED in v1.0.156,
   pending the owner's device verdict.** Root cause
   (v1.0.155 report, 2026-11-24 13:34:50): on a fresh
   clone's first launch, the guest's Firebase IID thread
   sends a broadcast; :black's
   `BActivityManagerService.sendBroadcast` NPEs on a null
   PackageItemInfo, and the exception is rethrown into the
   guest thread uncaught, killing the process ~2 s after
   the login screen appears. Long-registered clones never
   fire that path, so only NEW clones die. v1.0.156 guards
   the client side (BActivityManager.sendBroadcast
   RuntimeException catch + BroadcastIntent hook Throwable
   guard, `BcastGuard:` traces) — BActivityManagerService
   itself untouched per the §8 rule. **First evidence to
   check:** create/open a fresh clone on .156 — it must
   reach and STAY on its login screen; BcastGuard lines in
   the report show the guard absorbing the failure.
   **Update (2026-11-25):** the v1.0.156 report contains
   NO new crash file — the only crash is the old .155-era
   IID NPE (24 Nov 13:37) — so the crash did not recur in
   that window; katana launches at 08:21 / 09:10 completed
   with normal LaunchTime lines. The owner's explicit
   stay-open verdict for the second clone is still the
   deciding test.

**Fixed in v1.0.150 — do not chase again:** the com.facebook.aura
"Missing ComponentInfo!" crash (malformed ResolveInfo from the virtual
PM; root cause and fix in the §6 v1.0.150 entry).

Also known: the guest processes are heavy (~150 MB per stub). Launching many
heavy clones within minutes gets background stubs killed by LMK
(`LOW_MEMORY`, importance 400) — that is Android behaving normally, not a bug
to chase.

**App icon — the ORIGINAL logo stands (exact pixels since v1.0.148).**
The app's logo is the overlapping-squares design (light-blue back square
#AEDCFB, blue front square #1B9BF5, white glyph, white canvas) from the
mipmap PNGs. Since v1.0.148 the launcher icon is the original PNG itself
(see the §6 entry for the base64/generated-res mechanism) — **do NOT
redesign or redraw the logo; if it must change, show the owner the exact
image first and ship the exact file.** A redesign (v1.0.145) and a vector
recreation (v1.0.146/147) were both shipped and both rejected. The
blue-grid robot on HiOS's install/security-scan screen is HiOS-side
(placeholder/cache) — it persists even with no robot image in the APK,
so it cannot be fixed from the app.

## 8. Working rules (learned the hard way)

- **Read the real file before editing.** Paraphrased/summary-based edit
  anchors silently fail. Verify every edit landed, and review the full diff
  before pushing.
- **One hypothesis per build, evidence before claims.** Do not tell the user
  something is fixed until his own device test says so. If a fix didn't work,
  say so plainly and instrument instead of guessing again.
- **The user is the final judge.** He tests on his phone and reports in
  Bengali; reply in Bengali, keep it short, one question at a time.
- Every delivery: verify artifact hash vs GitHub's digest, badging, and the
  signer fingerprint (§4); state the download link's expiry; remind him
  whether this build needs an uninstall (only v1.0.144 does — the last one)
  or updates in place (all later builds).
- If you push through the Muse GitHub connector (snapshot pushes): it
  accepts UTF-8 text files only — no binaries, no deletions, and **no
  `.github/workflows/` changes** (403). Workflow edits must go through the
  GitHub web UI as the repo owner.
