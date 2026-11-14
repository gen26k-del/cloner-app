



#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include <JniHook/JniHook.h>
#include <Hook/VMClassLoaderHook.h>
#include <Hook/UnixFileSystemHook.h>
#include <Hook/FileSystemHook.h>
#include <Hook/BinderHook.h>
#include <Hook/DexFileHook.h>
#include <Hook/RuntimeHook.h>
#include "Utils/HexDump.h"
#include "hidden_api.h"
#include <dlfcn.h>
#include <fcntl.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>
#include "xdl.h"
#include "Dobby/dobby.h"

struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    jmethodID loadEmptyDex;
    jmethodID loadEmptyDexL;
    int api_level;
} VMEnv;


JNIEnv *getEnv() {
    JNIEnv *env;
    VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

JNIEnv *ensureEnvCreated() {
    JNIEnv *env = getEnv();
    if (env == NULL) {
        VMEnv.vm->AttachCurrentThread(&env, NULL);
    }
    return env;
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    env = ensureEnvCreated();
    return env->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    env = ensureEnvCreated();
    return (jstring) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    env = ensureEnvCreated();
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);
}

jlongArray BoxCore::loadEmptyDex(JNIEnv *env) {
    env = ensureEnvCreated();
    return (jlongArray) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.loadEmptyDex);
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    BaseHook::init(env);
    UnixFileSystemHook::init(env);
    FileSystemHook::init();
    VMClassLoaderHook::init(env);

    BinderHook::init(env);
    DexFileHook::init(env);
}

void hideXposed(JNIEnv *env, jclass clazz) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(env->FindClass(VMCORE_CLASS));
    VMEnv.getCallingUidId = env->GetStaticMethodID(VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                      "(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                    "(Ljava/io/File;)Ljava/io/File;");
    VMEnv.loadEmptyDex = env->GetStaticMethodID(VMEnv.NativeCoreClass, "loadEmptyDex",
                                                "()[J");

    JniHook::InitJniHook(env, api_level);
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path,
               jstring relocate_path) {
    ALOGD("set addIORule");
    IO::addRule(env->GetStringUTFChars(target_path, JNI_FALSE),
                env->GetStringUTFChars(relocate_path, JNI_FALSE));
}

void enableIO(JNIEnv *env, jclass clazz) {
    ALOGD("set enableIO");
    IO::init(env);
    nativeHook(env);
}

bool disableHiddenApi(JNIEnv *env, jclass clazz) {
    ALOGD("set disableHiddenApi");
    if(!disable_hidden_api(env)){
        ALOGD("set disableHiddenApi Fail!!!");
        return false;
    }
    return true;
}

bool disableResourceLoading(JNIEnv *env, jclass clazz) {
    ALOGD("set disableResourceLoading");
    if(!disable_resource_loading()){
        ALOGD("set disableResourceLoading Fail!!!");
        return false;
    }
    return true;
}

// v1.0.120: native exit tracer. Chromium child processes die with
// ApplicationExitInfo reason EXIT_SELF while the Java shutdown hook
// (proven registered) never fires -- so the exit comes from native
// code. An atexit handler runs on any exit() call: it appends pid +
// time + cmdline + a native backtrace to exit_trace.txt. It does NOT
// run on SIGKILL/_exit()/abort(). v1.0.121 proved the tracer IS
// installed in every stub (INSTALLED lines, both sides) yet no
// atexit block ever appeared -- so the deaths bypass exit().
// v1.0.122: Dobby hooks on libc exit/_exit/_Exit catch the bypass
// (Chromium's base::ExitCurrentProcess uses _exit()). The hooks
// write the same backtrace, then call the original. All writers use
// only open/write/close + unwind/dladdr (no allocation, no JNI),
// so they cannot crash the exiting process.
static char gExitTracePath[512] = {0};
static bool gExitTracerInstalled = false;

struct ExitTraceState {
    void **frames;
    int max;
    int count;
};

static _Unwind_Reason_Code exitTraceUnwind(struct _Unwind_Context *context, void *arg) {
    ExitTraceState *state = static_cast<ExitTraceState *>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0 && state->count < state->max) {
        state->frames[state->count++] = reinterpret_cast<void *>(pc);
    }
    return state->count >= state->max ? _URC_END_OF_STACK : _URC_NO_REASON;
}

static void writeExitTrace(const char *label) {
    if (gExitTracePath[0] == '\0') return;
    int fd = open(gExitTracePath, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    char cmdline[256] = {0};
    int cfd = open("/proc/self/cmdline", O_RDONLY);
    if (cfd >= 0) {
        ssize_t n = read(cfd, cmdline, sizeof(cmdline) - 1);
        (void) n;
        close(cfd);
    }
    char header[1024];
    int len = snprintf(header, sizeof(header),
                       "=== %s pid=%d time=%ld cmdline=%s ===\n",
                       label, (int) getpid(), (long) time(NULL), cmdline);
    if (len > 0) {
        ssize_t w = write(fd, header, (size_t) len);
        (void) w;
    }
    void *frames[32];
    ExitTraceState state = {frames, 32, 0};
    _Unwind_Backtrace(exitTraceUnwind, &state);
    for (int i = 0; i < state.count; i++) {
        Dl_info info;
        char line[512];
        int llen;
        if (dladdr(frames[i], &info) != 0 && info.dli_fname != NULL) {
            uintptr_t offset = reinterpret_cast<uintptr_t>(frames[i]) -
                               reinterpret_cast<uintptr_t>(info.dli_fbase);
            if (info.dli_sname != NULL) {
                llen = snprintf(line, sizeof(line), "#%d pc=%p %s +0x%lx (%s +0x%lx)\n",
                                i, frames[i], info.dli_fname, (unsigned long) offset,
                                info.dli_sname,
                                (unsigned long) (reinterpret_cast<uintptr_t>(frames[i]) -
                                                 reinterpret_cast<uintptr_t>(info.dli_saddr)));
            } else {
                llen = snprintf(line, sizeof(line), "#%d pc=%p %s +0x%lx\n",
                                i, frames[i], info.dli_fname, (unsigned long) offset);
            }
        } else {
            llen = snprintf(line, sizeof(line), "#%d pc=%p <unknown>\n", i, frames[i]);
        }
        if (llen > 0) {
            ssize_t w = write(fd, line, (size_t) llen);
            (void) w;
        }
    }
    const char *tail = "=== END EXIT TRACE ===\n";
    ssize_t w2 = write(fd, tail, strlen(tail));
    (void) w2;
    close(fd);
}

static void exitTraceHandler() {
    writeExitTrace("atexit");
}

typedef void (*ExitFunc)(int);
static ExitFunc orig_exit = NULL;
static ExitFunc orig_underscore_exit = NULL;
static ExitFunc orig_capital_exit = NULL;

static void exitHook(int status) {
    writeExitTrace("exit-hook");
    if (orig_exit != NULL) orig_exit(status);
}

static void underscoreExitHook(int status) {
    writeExitTrace("_exit-hook");
    if (orig_underscore_exit != NULL) orig_underscore_exit(status);
}

static void capitalExitHook(int status) {
    writeExitTrace("_Exit-hook");
    if (orig_capital_exit != NULL) orig_capital_exit(status);
}

static void installExitHooks() {
    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (handle == NULL) return;
    void *exitAddr = xdl_dsym(handle, "exit", nullptr);
    void *underscoreExitAddr = xdl_dsym(handle, "_exit", nullptr);
    void *capitalExitAddr = xdl_dsym(handle, "_Exit", nullptr);
    int hookedExit = 0;
    int hookedUnderscore = 0;
    int hookedCapital = 0;
    if (exitAddr != NULL) {
        if (DobbyHook(exitAddr, (void *) exitHook, (void **) &orig_exit) == 0) {
            hookedExit = 1;
        } else {
            orig_exit = NULL;
        }
    }
    if (underscoreExitAddr != NULL && underscoreExitAddr != exitAddr) {
        if (DobbyHook(underscoreExitAddr, (void *) underscoreExitHook,
                      (void **) &orig_underscore_exit) == 0) {
            hookedUnderscore = 1;
        } else {
            orig_underscore_exit = NULL;
        }
    }
    if (capitalExitAddr != NULL && capitalExitAddr != exitAddr &&
        capitalExitAddr != underscoreExitAddr) {
        if (DobbyHook(capitalExitAddr, (void *) capitalExitHook,
                      (void **) &orig_capital_exit) == 0) {
            hookedCapital = 1;
        } else {
            orig_capital_exit = NULL;
        }
    }
    xdl_close(handle);
    int fd = open(gExitTracePath, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        char line[128];
        int len = snprintf(line, sizeof(line),
                           "EXIT HOOKS exit=%d _exit=%d _Exit=%d\n",
                           hookedExit, hookedUnderscore, hookedCapital);
        if (len > 0) {
            ssize_t w = write(fd, line, (size_t) len);
            (void) w;
        }
        close(fd);
    }
}

void installExitTracer(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    if (gExitTracerInstalled) return;
    gExitTracerInstalled = true;
    const char *p = env->GetStringUTFChars(path, JNI_FALSE);
    if (p == NULL) return;
    strncpy(gExitTracePath, p, sizeof(gExitTracePath) - 1);
    gExitTracePath[sizeof(gExitTracePath) - 1] = '\0';
    env->ReleaseStringUTFChars(path, p);
    atexit(exitTraceHandler);
    // v1.0.121: prove the native side ran AND the file is writable
    // from native code (the handler uses this same open path at
    // exit time). If this line appears but no exit block ever
    // does, the deaths bypass exit() entirely.
    int fd = open(gExitTracePath, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        char line[600];
        int len = snprintf(line, sizeof(line),
                           "NATIVE TRACER INSTALLED pid=%d path=%s\n",
                           (int) getpid(), gExitTracePath);
        if (len > 0) {
            ssize_t w = write(fd, line, (size_t) len);
            (void) w;
        }
        close(fd);
    }
    // v1.0.122: hook exit/_exit/_Exit so _exit-style deaths leave
    // a backtrace too. Failures are silent -- the atexit handler
    // above still covers plain exit() calls.
    installExitHooks();
}

static JNINativeMethod gMethods[] = {
        {"disableHiddenApi", "()Z",                               (void *) disableHiddenApi},
        {"disableResourceLoading", "()Z",                         (void *) disableResourceLoading},
        {"hideXposed", "()V",                                     (void *) hideXposed},
        {"addIORule",  "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",   "()V",                                     (void *) enableIO},
        {"init",       "(I)V",                                    (void *) init},
        {"installExitTracer", "(Ljava/lang/String;)V",              (void *) installExitTracer},
};

int registerNativeMethods(JNIEnv *env, const char *className,
                          JNINativeMethod *gMethods, int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == nullptr) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

int registerNatives(JNIEnv *env) {
    if (!registerNativeMethods(env, VMCORE_CLASS, gMethods,
                               sizeof(gMethods) / sizeof(gMethods[0])))
        return JNI_FALSE;
    return JNI_TRUE;
}

void registerMethod(JNIEnv *jenv) {
    registerNatives(jenv);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_EVERSION;
    }
    registerMethod(env);
    return JNI_VERSION_1_6;
}