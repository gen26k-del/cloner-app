package top.niunaijun.blackbox.core.system.notification;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import black.android.app.BRNotificationChannel;
import black.android.app.BRNotificationChannelGroup;
import black.android.app.BRNotificationO;
import black.android.app.NotificationChannelContext;
import black.android.app.NotificationChannelGroupContext;
import black.android.app.NotificationOContext;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class BNotificationManagerService extends IBNotificationManagerService.Stub implements ISystemService {
    private final static BNotificationManagerService sService = new BNotificationManagerService();
    public static final String CHANNEL_BLACK = "@black-";
    public static final String GROUP_BLACK = "@black-group-";

    private NotificationChannelManager mNotificationChannelManager;
    private final Map<String, NotificationRecord> mNotificationRecords = new HashMap<>();
    private final Map<Integer, String> mPosterPackageByPid = new HashMap<>();

    private final NotificationManager mRealNotificationManager =
            (NotificationManager) BlackBoxCore.getContext().getSystemService(Context.NOTIFICATION_SERVICE);

    public static BNotificationManagerService get() {
        return sService;
    }

    @Override
    public void systemReady() {
        mNotificationChannelManager = NotificationChannelManager.get();
    }


    // v1.0.167: poster package resolution without the silent drop.
    // Order: live process record -> last-poster pid cache -> (enqueue
    // only) owner of the notification's channel -> null.
    private String resolvePosterPackage(int pid) {
        ProcessRecord record = BProcessManagerService.get().findProcessByPid(pid);
        if (record != null) {
            return record.getPackageName();
        }
        synchronized (mPosterPackageByPid) {
            return mPosterPackageByPid.get(pid);
        }
    }

    private void rememberPoster(int pid, String packageName) {
        if (packageName == null) return;
        synchronized (mPosterPackageByPid) {
            mPosterPackageByPid.put(pid, packageName);
        }
    }

    private String findPackageByChannel(Notification notification) {
        try {
            if (notification == null || !BuildCompat.isOreo()) return null;
            NotificationOContext notificationOContext = BRNotificationO.get(notification);
            if (notificationOContext._check_mChannelId() == null) return null;
            String channelId = notificationOContext.mChannelId();
            if (channelId == null) return null;
            synchronized (mNotificationRecords) {
                for (Map.Entry<String, NotificationRecord> entry : mNotificationRecords.entrySet()) {
                    NotificationRecord record = entry.getValue();
                    synchronized (record.mNotificationChannels) {
                        if (record.mNotificationChannels.containsKey(channelId)) {
                            String key = entry.getKey();
                            int dash = key.lastIndexOf('-');
                            return dash > 0 ? key.substring(0, dash) : key;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void traceNotifyEvent(String line) {
        try {
            BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(), line);
        } catch (Throwable ignored) { }
    }

    // v1.0.167: VA-style notification fixer (VirtualApp's
    // NotificationFixer, in essence). A resource icon that points at
    // the guest package is loaded from the guest's own resources and
    // converted to a self-contained bitmap icon, then the
    // notification is rebuilt via recoverBuilder. On ANY failure the
    // original notification is returned unchanged - the fixer must
    // never make posting worse than today.
    private Notification fixNotificationIcons(Notification notification, String packageName, int userId) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return notification;
            Icon small = notification.getSmallIcon();
            Icon large = notification.getLargeIcon();
            Icon newSmall = fixIcon(small, userId);
            Icon newLarge = fixIcon(large, userId);
            if (newSmall == small && newLarge == large) return notification;
            Notification.Builder builder =
                    Notification.Builder.recoverBuilder(BlackBoxCore.getContext(), notification);
            if (newSmall != null && newSmall != small) builder.setSmallIcon(newSmall);
            if (newLarge != null && newLarge != large) builder.setLargeIcon(newLarge);
            Notification rebuilt = builder.build();
            traceNotifyEvent("NotifyFix: icon pkg=" + packageName + " rebuilt");
            return rebuilt;
        } catch (Throwable t) {
            traceNotifyEvent("NotifyFix: icon pkg=" + packageName
                    + " failed " + t.getClass().getSimpleName());
            return notification;
        }
    }

    private Icon fixIcon(Icon icon, int userId) {
        if (icon == null) return null;
        try {
            if (icon.getType() != Icon.TYPE_RESOURCE) return icon;
            String resPackage = icon.getResPackage();
            if (resPackage == null || resPackage.equals(BlackBoxCore.getHostPkg())) return icon;
            Drawable drawable = null;
            try {
                Context guestContext = BlackBoxCore.getContext()
                        .createPackageContext(resPackage, Context.CONTEXT_IGNORE_SECURITY);
                drawable = guestContext.getResources().getDrawable(icon.getResId(), null);
            } catch (Throwable ignored) { }
            if (drawable == null) {
                try {
                    ApplicationInfo appInfo = BPackageManagerService.get()
                            .getApplicationInfo(resPackage, 0, userId);
                    if (appInfo != null && appInfo.sourceDir != null) {
                        AssetManager assetManager =
                                AssetManager.class.getDeclaredConstructor().newInstance();
                        AssetManager.class.getMethod("addAssetPath", String.class)
                                .invoke(assetManager, appInfo.sourceDir);
                        Resources resources = new Resources(assetManager,
                                BlackBoxCore.getContext().getResources().getDisplayMetrics(),
                                BlackBoxCore.getContext().getResources().getConfiguration());
                        drawable = resources.getDrawable(icon.getResId(), null);
                    }
                } catch (Throwable ignored) { }
            }
            if (drawable == null) return icon;
            Bitmap bitmap = drawableToBitmap(drawable);
            if (bitmap == null) return icon;
            return Icon.createWithBitmap(bitmap);
        } catch (Throwable ignored) {
            return icon;
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0) width = 96;
        if (height <= 0) height = 96;
        Bitmap bitmap = Bitmap.createBitmap(width, height,
                drawable.getOpacity() != PixelFormat.OPAQUE
                        ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    private NotificationRecord getNotificationRecord(String packageName, int userId) {
        String key = packageName + "-" + userId;
        synchronized (mNotificationRecords) {
            NotificationRecord notificationRecord = mNotificationRecords.get(key);
            if (notificationRecord == null) {
                notificationRecord = new NotificationRecord();
                mNotificationRecords.put(key, notificationRecord);
            }
            return notificationRecord;
        }
    }

    private void removeNotificationRecord(String packageName, int userId) {
        String key = packageName + "-" + userId;
        synchronized (mNotificationRecords) {
            mNotificationRecords.remove(key);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public NotificationChannel getNotificationChannel(String channelId, int userId) throws RemoteException {
        int callingPid = getCallingPid();
        String packageName = resolvePosterPackage(callingPid);
        if (packageName == null)
            return null;
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannels) {
            return notificationRecord.mNotificationChannels.get(channelId);
        }
    }

    @Override
    public List<NotificationChannel> getNotificationChannels(String packageName, int userId) throws RemoteException {
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannels) {
            return new ArrayList<>(notificationRecord.mNotificationChannels.values());
        }
    }

    @Override
    public List<NotificationChannelGroup> getNotificationChannelGroups(String packageName, int userId) throws RemoteException {
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannelGroups) {
            return new ArrayList<>(notificationRecord.mNotificationChannelGroups.values());
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void createNotificationChannel(NotificationChannel notificationChannel, int userId) {
        int callingPid = getCallingPid();
        String packageName = resolvePosterPackage(callingPid);
        if (packageName == null) {
            traceNotifyEvent("NotifyDrop: channel pid=" + callingPid + " user=" + userId);
            return;
        }
        handleNotificationChannel(notificationChannel, userId);
        mRealNotificationManager.createNotificationChannel(notificationChannel);

        resetNotificationChannel(notificationChannel);
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannels) {
            notificationRecord.mNotificationChannels.put(notificationChannel.getId(), notificationChannel);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void deleteNotificationChannel(String channelId, int userId) {
        int callingPid = getCallingPid();
        String packageName = resolvePosterPackage(callingPid);
        if (packageName == null)
            return;
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannels) {
            NotificationChannel remove = notificationRecord.mNotificationChannels.remove(channelId);
            if (remove != null) {
                String blackChannelId = getBlackChannelId(remove.getId(), userId);
                mRealNotificationManager.deleteNotificationChannel(blackChannelId);
            }
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void createNotificationChannelGroup(NotificationChannelGroup notificationChannelGroup, int userId) {
        int callingPid = getCallingPid();
        String packageName = resolvePosterPackage(callingPid);
        if (packageName == null) {
            traceNotifyEvent("NotifyDrop: channelGroup pid=" + callingPid + " user=" + userId);
            return;
        }
        handleNotificationGroup(notificationChannelGroup, userId);
        mRealNotificationManager.createNotificationChannelGroup(notificationChannelGroup);

        resetNotificationGroup(notificationChannelGroup);
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannelGroups) {
            notificationRecord.mNotificationChannelGroups.put(notificationChannelGroup.getId(), notificationChannelGroup);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void deleteNotificationChannelGroup(String groupId, int userId) {
        int callingPid = getCallingPid();
        String packageName = resolvePosterPackage(callingPid);
        if (packageName == null)
            return;
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannelGroups) {
            NotificationChannelGroup remove = notificationRecord.mNotificationChannelGroups.remove(groupId);
            if (remove != null) {
                String blackGroupId = getBlackGroupId(remove.getId(), userId);
                mRealNotificationManager.deleteNotificationChannelGroup(blackGroupId);
            }
        }
    }

    @Override
    public void enqueueNotificationWithTag(int id, String tag, Notification notification, int userId) {
        int callingPid = Binder.getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        String packageName = processByPid != null ? processByPid.getPackageName() : null;
        if (packageName == null) {
            // v1.0.167: silent-drop fix. Up to .166 a post from a guest
            // process :black had no record for was discarded with no
            // log at all. Resolution is layered (poster cache, then
            // the notification's channel owner); if even that fails
            // the notification is still posted under "unknown" - a
            // lost notification is worse than an imperfectly keyed
            // one. Every fallback is traced as NotifyDrop.
            packageName = resolvePosterPackage(callingPid);
            String via = "cache";
            if (packageName == null) {
                packageName = findPackageByChannel(notification);
                via = "channel";
            }
            if (packageName == null) {
                packageName = "unknown";
                via = "unknown";
            }
            traceNotifyEvent("NotifyDrop: enqueue pid=" + callingPid + " user=" + userId
                    + " id=" + id + " via=" + via + " pkg=" + packageName);
        }
        rememberPoster(callingPid, packageName);
        notification = fixNotificationIcons(notification, packageName, userId);
        int notificationId = getNotificationId(userId, id, packageName);

        if (BuildCompat.isOreo()) {
            NotificationOContext notificationOContext = BRNotificationO.get(notification);
            
            if (notificationOContext._check_mChannelId() != null) {
                String blackChannelId = getBlackChannelId(notificationOContext.mChannelId(), userId);
                notificationOContext._set_mChannelId(blackChannelId);
            }
            
            if (notificationOContext._check_mGroupKey() != null) {
                String blackGroupId = getBlackGroupId(notificationOContext.mGroupKey(), userId);
                notificationOContext._set_mGroupKey(blackGroupId);
            }
        }
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mIds) {
            notificationRecord.mIds.add(notificationId);
        }
        mRealNotificationManager.notify(notificationId, notification);
    }

    @Override
    public void cancelNotificationWithTag(int id, String tag, int userId) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        String packageName = processByPid != null ? processByPid.getPackageName() : null;
        if (packageName == null) {
            packageName = resolvePosterPackage(callingPid);
            if (packageName == null) {
                traceNotifyEvent("NotifyDrop: cancel pid=" + callingPid
                        + " user=" + userId + " id=" + id);
                return;
            }
            traceNotifyEvent("NotifyDrop: cancel pid=" + callingPid + " user=" + userId
                    + " id=" + id + " via=cache pkg=" + packageName);
        }
        int notificationId = getNotificationId(userId, id, packageName);
        mRealNotificationManager.cancel(notificationId);

        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mIds) {
            notificationRecord.mIds.remove(notificationId);
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void handleNotificationChannel(NotificationChannel notificationChannel, int userId) {
        NotificationChannelContext channelContext = BRNotificationChannel.get(notificationChannel);
        String channelId = channelContext.mId();
        String blackChannelId = getBlackChannelId(channelId, userId);
        channelContext._set_mId(blackChannelId);

        notificationChannel.setGroup(getBlackGroupId(notificationChannel.getGroup(), userId));
    }

    private void resetNotificationChannel(NotificationChannel notificationChannel) {
        NotificationChannelContext channelContext = BRNotificationChannel.get(notificationChannel);
        String channelId = channelContext.mId();
        String realChannelId = getRealChannelId(channelId);
        channelContext._set_mId(realChannelId);
    }

    private void handleNotificationGroup(NotificationChannelGroup notificationChannelGroup, int userId) {
        NotificationChannelGroupContext groupContext = BRNotificationChannelGroup.get(notificationChannelGroup);
        String groupId = groupContext.mId();
        String blackGroupId = getBlackGroupId(groupId, userId);
        groupContext._set_mId(blackGroupId);

        List<NotificationChannel> notificationChannels = groupContext.mChannels();
        if (notificationChannels != null) {
            for (NotificationChannel notificationChannel : notificationChannels) {
                createNotificationChannel(notificationChannel, userId);
            }
        }
    }

    private void resetNotificationGroup(NotificationChannelGroup notificationChannelGroup) {
        NotificationChannelGroupContext groupContext = BRNotificationChannelGroup.get(notificationChannelGroup);
        String groupId = groupContext.mId();
        String realGroupId = getRealGroupId(groupId);
        groupContext._set_mId(realGroupId);

        List<NotificationChannel> notificationChannels = groupContext.mChannels();
        if (notificationChannels != null) {
            for (NotificationChannel notificationChannel : notificationChannels) {
                resetNotificationChannel(notificationChannel);
            }
        }
    }

    @SuppressLint("NewApi")
    public void deletePackageNotification(String packageName, int userId) {
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        if (BuildCompat.isOreo()) {
            for (NotificationChannelGroup value : notificationRecord.mNotificationChannelGroups.values()) {
                String blackGroupId = getBlackGroupId(value.getId(), userId);
                mRealNotificationManager.deleteNotificationChannelGroup(blackGroupId);
            }
            for (NotificationChannel value : notificationRecord.mNotificationChannels.values()) {
                String blackChannelId = getBlackChannelId(value.getId(), userId);
                mRealNotificationManager.deleteNotificationChannel(blackChannelId);
            }
        }
        for (Integer id : notificationRecord.mIds) {
            mRealNotificationManager.cancel(id);
        }
        removeNotificationRecord(packageName, userId);
    }

    private String getBlackChannelId(String channelId, int userId) {
        if (channelId == null || channelId.contains(CHANNEL_BLACK)) {
            return channelId;
        }
        return channelId + CHANNEL_BLACK + userId;
    }

    private String getRealChannelId(String channelId) {
        if (channelId == null || !channelId.contains(CHANNEL_BLACK)) {
            return channelId;
        }
        return channelId.split(CHANNEL_BLACK)[0];
    }

    private String getBlackGroupId(String groupId, int userId) {
        if (groupId == null || groupId.contains(GROUP_BLACK))
            return groupId;
        return groupId + GROUP_BLACK + userId;
    }

    private String getRealGroupId(String groupId) {
        if (groupId == null || !groupId.contains(GROUP_BLACK))
            return groupId;
        return groupId.split(GROUP_BLACK)[0];
    }

    public static int getNotificationId(int userId, int notificationId, String packageName) {
        return (packageName + userId + notificationId).hashCode();
    }
}
