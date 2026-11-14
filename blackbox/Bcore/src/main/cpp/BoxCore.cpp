



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
// code (exit() from libchrome or a library). An atexit handler runs
// on any exit() call: it appends pid + time + cmdline + a native
// backtrace to exit_trace.txt, showing WHERE the exit was called
// from. It does NOT run on SIGKILL/_exit()/abort(), which is itself
// informative (empty file + EXIT_SELF deaths => _exit-style exit).
// Uses _Unwind_Backtrace + dladdr: execinfo.h backtrace() is NOT
// declared by the NDK 29 headers at this API level (build failure
// on the first attempt). The handler only uses open/write/close +
// unwind/dladdr (no allocation, no JNI) so it cannot crash the
// exiting process.
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

static void exitTraceHandler() {
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
                       "=== NATIVE EXIT pid=%d time=%ld cmdline=%s ===\n",
                       (int) getpid(), (long) time(NULL), cmdline);
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
    const char *tail = "=== END NATIVE EXIT ===\n";
    ssize_t w2 = write(fd, tail, strlen(tail));
    (void) w2;
    close(fd);
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
    // exit time). If this line appears but no NATIVE EXIT block
    // ever does, the deaths bypass exit() entirely.
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