



#include "FileSystemHook.h"
#include "Log.h"
#include "xdl.h"
#include "Dobby/dobby.h"
#include <sys/stat.h>
#include <fcntl.h>
#include <stdarg.h>
#include <cstring>
#include <errno.h>


static int (*orig_open)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_open64)(const char *pathname, int flags, ...) = nullptr;


int new_open(const char *pathname, int flags, ...) {
    
    if (pathname != nullptr) {
        if (strstr(pathname, "resource-cache") || 
            strstr(pathname, "@idmap") || 
            strstr(pathname, ".frro") ||
            strstr(pathname, "systemui") ||
            strstr(pathname, "data@resource-cache@")) {
            ALOGD("FileSystemHook: Blocking problematic file access: %s", pathname);
            errno = ENOENT; 
            return -1;
        }
    }
    
    
    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);
    
    return orig_open(pathname, flags, mode);
}


int new_open64(const char *pathname, int flags, ...) {
    
    if (pathname != nullptr) {
        if (strstr(pathname, "resource-cache") || 
            strstr(pathname, "@idmap") || 
            strstr(pathname, ".frro") ||
            strstr(pathname, "systemui") ||
            strstr(pathname, "data@resource-cache@")) {
            ALOGD("FileSystemHook: Blocking problematic file access (64): %s", pathname);
            errno = ENOENT; 
            return -1;
        }
    }
    
    
    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);
    
    return orig_open64(pathname, flags, mode);
}

void FileSystemHook::init() {
    ALOGD("FileSystemHook: Initializing file system hooks");
    
    
    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    if (!handle) {
        ALOGE("FileSystemHook: Failed to open libc.so");
        return;
    }
    
    
    orig_open = (int (*)(const char*, int, ...))xdl_sym(handle, "open", nullptr);
    if (orig_open) {
        
        
        ALOGD("FileSystemHook: Found open function at %p", orig_open);
    } else {
        ALOGE("FileSystemHook: Failed to find open function");
    }

    // v1.0.170: replacements were DEFINED but NEVER INSTALLED (upstream
    // NewBlackbox issue #89 — init() only resolved the original symbols).
    // Install them now: DobbyHook on the resolved libc addresses, keeping
    // the original pointer only when the hook actually took (trampoline).
    if (orig_open) {
        void *trampoline = nullptr;
        int rc = DobbyHook((void *) orig_open, (void *) new_open, &trampoline);
        if (rc == 0 && trampoline != nullptr) {
            orig_open = (int (*)(const char *, int, ...)) trampoline;
            ALOGD("FileSystemHook: open hook installed");
        } else {
            ALOGD("FileSystemHook: open hook FAILED rc=%d", rc);
        }
    }
    
    
    orig_open64 = (int (*)(const char*, int, ...))xdl_sym(handle, "open64", nullptr);
    if (orig_open64) {
        ALOGD("FileSystemHook: Found open64 function at %p", orig_open64);
    } else {
        ALOGE("FileSystemHook: Failed to find open64 function");
    }

    if (orig_open64) {
        void *trampoline = nullptr;
        int rc = DobbyHook((void *) orig_open64, (void *) new_open64, &trampoline);
        if (rc == 0 && trampoline != nullptr) {
            orig_open64 = (int (*)(const char *, int, ...)) trampoline;
            ALOGD("FileSystemHook: open64 hook installed");
        } else {
            ALOGD("FileSystemHook: open64 hook FAILED rc=%d", rc);
        }
    }
    
    xdl_close(handle);
}
