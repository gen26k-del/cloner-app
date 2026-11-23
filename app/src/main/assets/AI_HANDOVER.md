# CloneMe — AI Handover Guide

> For any AI assistant or developer picking up this project cold. Read this first.
> Last updated: 2026-11-23, at version **1.0.150** (versionCode 135).

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
   off the main thread (v1.0.131). **First evidence to check:**
   MainStall lines in the next report and what trace activity surrounds
   them. Do not promise to fix the LOW_MEMORY deaths themselves — with
   ~150 MB per stub and many heavy clones resident, LMK reaping cached
   stubs is Android behaving normally.
6. **Per-clone notifications unreliable — pinned symptom: the Facebook
   clone (com.facebook.katana) receives NO notifications at all**
   (owner, 2026-11-23; total absence — not delay, not misattribution).
   Diagnosis first: v1.0.150 adds `NotifyPost:`/`NotifyCancel:` traces
   at the INotificationManagerProxy enqueue/cancel hooks plus a
   dedicated report section; **no notification behaviour was changed**.
   **First evidence to check:** whether ANY NotifyPost line with
   pkg=com.facebook.katana exists in the next report. Zero lines =
   katana never posts inside the clone (look at push/provider wakeup
   and process liveness); lines present = posts happen but never
   surface (look at BNotificationManager forwarding and channel
   handling). A fix belongs to a later build, once the trace decides
   which case it is.

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
