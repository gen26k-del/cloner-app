package top.niunaijun.blackbox.utils.compat;

import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ContentProviderCompat {

    // Provider call (:black-এর service lookup) মাঝে মাঝে ~১৯ সেকেন্ড আটকে
    // যায় — main thread ANR হয় ("CloneMe isn't responding") আর Chrome-এর
    // renderer timeout হয়ে "Aw, Snap!" দেখায়। client.call() ব্লকিং Binder
    // কল, কোনো timeout নেই। ৫ সেকেন্ডে উত্তর না এলে ব্যর্থ ধরে নাও, যাতে
    // caller (initAppProcessL-এর retry, getServiceInternal-এর fallback)
    // দ্রুত সিদ্ধান্ত নিতে পারে।
    private static final ExecutorService sCallExecutor = Executors.newCachedThreadPool();
    private static final long CALL_TIMEOUT_MS = 5000;

    public static Bundle call(Context context, Uri uri, String method, String arg, Bundle extras, int retryCount) throws IllegalAccessException {
        if (VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return context.getContentResolver().call(uri, method, arg, extras);
        }
        ContentProviderClient client = acquireContentProviderClientRetry(context, uri, retryCount);
        try {
            if (client == null) {
                throw new IllegalAccessException();
            }
            final ContentProviderClient c = client;
            Future<Bundle> future = sCallExecutor.submit(() -> c.call(method, arg, extras));
            try {
                return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IllegalAccessException("provider call timeout: " + method);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RemoteException) {
                    throw new IllegalAccessException(cause.getMessage());
                }
                throw new IllegalAccessException(String.valueOf(cause));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalAccessException("interrupted");
            }
        } finally {
            releaseQuietly(client);
        }
    }


    private static ContentProviderClient acquireContentProviderClient(Context context, Uri uri) {
        try {
            if (VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                return context.getContentResolver().acquireUnstableContentProviderClient(uri);
            }
            return context.getContentResolver().acquireContentProviderClient(uri);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static ContentProviderClient acquireContentProviderClientRetry(Context context, Uri uri, int retryCount) {
        ContentProviderClient client = acquireContentProviderClient(context, uri);
        if (client == null) {
            int retry = 0;
            long startTime = System.currentTimeMillis();
            long timeout = 2000; 
            
            while (retry < retryCount && client == null) {
                
                if (System.currentTimeMillis() - startTime > timeout) {
                    break;
                }
                
                SystemClock.sleep(200); 
                retry++;
                client = acquireContentProviderClient(context, uri);
            }
        }
        return client;
    }

    public static ContentProviderClient acquireContentProviderClientRetry(Context context, String name, int retryCount) {
        ContentProviderClient client = acquireContentProviderClient(context, name);
        if (client == null) {
            int retry = 0;
            long startTime = System.currentTimeMillis();
            long timeout = 2000; 
            
            while (retry < retryCount && client == null) {
                
                if (System.currentTimeMillis() - startTime > timeout) {
                    break;
                }
                
                SystemClock.sleep(200); 
                retry++;
                client = acquireContentProviderClient(context, name);
            }
        }
        return client;
    }

    private static ContentProviderClient acquireContentProviderClient(Context context, String name) {
        if (VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            return context.getContentResolver().acquireUnstableContentProviderClient(name);
        }
        return context.getContentResolver().acquireContentProviderClient(name);
    }

    private static void releaseQuietly(ContentProviderClient client) {
        if (client != null) {
            try {
                if (VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    client.close();
                } else {
                    client.release();
                }
            } catch (Exception ignored) {
            }
        }
    }
}