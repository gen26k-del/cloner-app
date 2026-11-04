# অ্যাপ ক্লোনারে VirtualApp/BlackBox ইঞ্জিন যোগ করার রোডম্যাপ

তোমার বর্তমান প্রজেক্টে UI আর `CloneEngine` ইন্টারফেস তৈরি আছে — এটা ভালো ভিত্তি।
আসল ক্লোনিং করাতে হলে এই ইন্টারফেসের পেছনে একটা virtualization engine বসাতে হবে।
বাস্তবে সবচেয়ে ব্যবহারযোগ্য ওপেন-সোর্স পথ হলো **BlackBox** (VirtualApp-এর সক্রিয় ফর্ক)।

## ইঞ্জিন পছন্দ: কেন BlackBox?

- VirtualApp-এর মূল রেপো অনেক পুরনো; BlackBox ফর্কগুলো Android 14/16 পর্যন্ত সাপোর্ট করে।
- রুট ছাড়াই userspace-এ চলে — APK সিস্টেমে ইনস্টল না করেই sandbox-এর ভেতরে চালায়।
- পরিচিত ফর্ক: `ALEX5402/NewBlackbox`, `ZiTanIOI/BlackBox` (Apache 2.0 লাইসেন্স)।
- সতর্কতা: এগুলো কমিউনিটি ফর্ক — স্থিতিশীলতা ডিভাইসভেদে বদলায়, "শেখা ও পরীক্ষার জন্য" মানসিকতা নিয়ে এগোও।

## Phase 0 — প্রস্তুতি (১–২ দিন)

1. একটা ফর্ক বেছে নাও (যেমন `ALEX5402/NewBlackbox`) — README-তে Android ভার্সন সাপোর্ট দেখে নাও।
2. NDK ইনস্টল করো — ফর্কের ডকুমেন্টে যে NDK ভার্সন pin করা আছে (যেমন 29.x), ঠিক সেটাই ব্যবহার করো।
3. JDK ভার্সন মিলিয়ে নাও — কোনো কোনো ফর্কে JDK 21 লাগে।
4. আলাদা git branch-এ কাজ শুরু করো (`feature/blackbox-engine`)।

## Phase 1 — মাল্টি-মডিউল কাঠামো (২–৩ দিন)

BlackBox রিমোট dependency হিসেবে আসে না — **লোকাল Gradle মডিউল** হিসেবে যোগ করতে হয়:

```
blackbox/
  Bcore/             <- মূল ইঞ্জিন (Java + C++/NDK); :app এটার ওপর নির্ভর করে
  black-reflection/  <- Bcore-এর ভেতরের reflection ইউটিলিটি
  compiler/          <- annotation processor
```

`settings.gradle.kts`-এ যোগ করো:

```kotlin
include(":blackbox:Bcore")
include(":blackbox:black-reflection")
include(":blackbox:compiler")
```

`app/build.gradle.kts`-এ:

```kotlin
implementation(project(":blackbox:Bcore"))
```

মনে রাখার বিষয়:
- NDK `abiFilters`: শুধু `armeabi-v7a` আর `arm64-v8a` রাখো।
- অনেক ফর্ক **ইচ্ছা করে `targetSdk 28`** রাখে — নতুন Android-এর আচরণ পরিবর্তন এড়াতে। তোমার বর্তমান `targetSdk 34` থেকে নামাতে হতে পারে; এটা সচেতন সিদ্ধান্ত।
- `minSdk 21` পর্যন্ত নামানো যায়; তোমার `minSdk 26` ঠিক আছে।

## Phase 2 — Engine Initialization (১–২ দিন)

BlackBox-এর init **দুই ধাপে**, `Application` ক্লাসে — ক্রম বাধ্যতামূলক:

```kotlin
class ClonerApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // ধাপ ১
        BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
            override fun getHostPackageName() = packageName
            override fun isEnableDaemonService() = true
            override fun requestInstallPackage(file: File?, userId: Int) = false
        })
    }

    override fun onCreate() {
        super.onCreate()
        // ধাপ ২
        BlackBoxCore.get().doCreate()
        Engine.current = BlackBoxCloneEngine()  // তোমার ইন্টারফেসে বসাও
    }
}
```

`AndroidManifest.xml`-এ `android:name=".ClonerApp"` বসাতে ভুলো না।

## Phase 3 — তোমার `CloneEngine` ইন্টারফেসে অ্যাডাপ্টার (২–৩ দিন)

BlackBox-এর API একটা singleton দিয়ে চলে: `BlackBoxCore.get()`। তোমার ইন্টারফেসের ম্যাপিং:

| তোমার `CloneEngine` মেথড | BlackBox API |
|---|---|
| `installClone(packageName)` | `installPackageAsUser(packageName: String, userId: Int)` — সিস্টেমে থাকা অ্যাপের APK পাথ নিজে বের করে নেয় |
| APK ফাইল থেকে ইনস্টল (নতুন মেথড) | `installPackageAsUser(apkFile: File, userId: Int)` |
| `launchClone(packageName)` | `launchApk(packageName: String, userId: Int)` |
| `listClones()` | `getInstalledApplications(flags: Int, userId: Int)` → packageName-এর লিস্ট |
| ক্লোন ডিলিট (নতুন মেথড) | `uninstallPackage(packageName: String)` |

সব কলে `userId = 0` ব্যবহার করো (BlackBox-এর ডিফল্ট ভার্চুয়াল ইউজার)।

> নমুনা অ্যাডাপ্টার কোড এই ফোল্ডারের `BlackBoxCloneEngine.kt`-এ দেওয়া আছে। মেথড সিগনেচার ফর্কভেদে সামান্য বদলাতে পারে — ফর্কের সোর্স দেখে মিলিয়ে নিও।

## Phase 4 — UI/UX আপডেট (৩–৪ দিন)

1. **দুই ধরনের ইনস্টল**: "সিস্টেম অ্যাপ ক্লোন" (packageName দিয়ে) + "APK ফাইল থেকে ইনস্টল" (ফাইল পিকার → cache-এ কপি → `File` overload)।
2. `loadApps()` ব্যাকগ্রাউন্ড থ্রেডে নাও (এখন main thread-এ চলে — অনেক অ্যাপ থাকলে UI আটকাবে)।
3. ক্লোন লিস্টে **ডিলিট বাটন** যোগ করো (`uninstallPackage` + লিস্ট রিফ্রেশ)।
4. ইনস্টল/লঞ্চ coroutine-এ চালিয়ে progress indicator দেখাও — BlackBox অপারেশন সময় নেয়।
5. এরর মেসেজ বাংলায় স্পষ্ট করো (যেমন: "৬৪-বিট ডিভাইসে এই অ্যাপ সাপোর্ট করে না")।

## Phase 5 — Manifest ও Permission (আধা দিন)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

প্রথমটা daemon service-এর জন্য, দ্বিতীয়টা ইনস্টল অপারেশনের জন্য। Android 8+ এ foreground service notification লাগে — সেটা সামলাতে হবে।

## Phase 6 — টেস্টিং (চলমান)

- **ডিভাইস ম্যাট্রিক্স**: arm64 ফিজিক্যাল ডিভাইসে টেস্ট করো; x86 এমুলেটরে native অংশ নাও চলতে পারে।
- **Android ভার্সন**: 10, 12, 14 — আলাদা আলাদা আচরণ হয় (বিশেষ করে activity launch hook-এ)।
- **টেস্ট অ্যাপ**: প্রথমে সহজ অ্যাপ (ক্যালকুলেটর, নোট অ্যাপ) দিয়ে; Google Play Services-নির্ভর অ্যাপ (যেমন Instagram) পরে — এগুলোতে ফেক GMS সেটআপ লাগতে পারে।
- প্রতিটা টেস্টে logcat-এ `BlackBox` ট্যাগ ফিল্টার করে ক্র্যাশ দেখো।

## Phase 7 — পলিসি ও বাস্তবতা (সিদ্ধান্তের আগে পড়ো)

- **Play Store**: এই ধরনের virtualization অ্যাপ পলিসি ঝুঁকিতে থাকে — সাধারণত Play-এর বাইরে (APK/GitHub) বিতরণ করা হয়।
- **স্থিতিশীলতা**: কোনো ফর্কই ১০০% অ্যাপ চালানোর গ্যারান্টি দেয় না; WebView, push notification, কিছু banking অ্যাপ ভাঙতে পারে।
- **রক্ষণাবেক্ষণ**: প্রতি নতুন Android ভার্সনে hook ভাঙে — ফর্ক আপডেটের ওপর নির্ভর করতে হয়।

## মোটামুটি সময়

সব মিলিয়ে একা কাজ করলে **৩–৫ সপ্তাহ** (পার্ট-টাইম), যার বেশিরভাগ যাবে Phase 1–এর বিল্ড সেটআপ আর Phase 6-এর ডিভাইস টেস্টিং-এ।

---

### পরের ধাপের জন্য প্রস্তাব

চাইলে আমি এখন তোমার বর্তমান প্রজেক্টে Phase 4-এর UI ফিক্সগুলো (background loading, search, delete, persistence) করে দিতে পারি — এগুলো engine ছাড়াই করা যায়। নাকি Phase 1-এর Gradle কাঠামো সাজিয়ে দেবো?
