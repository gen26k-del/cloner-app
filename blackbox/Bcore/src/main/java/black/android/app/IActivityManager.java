package black.android.app;

import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BField;
import top.niunaijun.blackreflection.annotation.BMethod;
import top.niunaijun.blackreflection.annotation.BParamClassName;

@BClassName("android.app.IActivityManager")
public interface IActivityManager {
    @BMethod
    Integer getTaskForActivity(IBinder IBinder0, boolean boolean1);

    // v1.0.168 (DualSpaceLivre port): acknowledge proxy-service creation so
    // ActivityManager does not raise a false service ANR.
    @BMethod
    void serviceDoneExecuting(IBinder token, int type, int startId, int result);

    @BMethod
    void overridePendingTransition(IBinder IBinder0, String String1, int int2, int int3);

    @BMethod
    void setRequestedOrientation(IBinder IBinder0, int int1);

    @BMethod
    Integer startActivities();

    @BMethod
    Integer startActivity(@BParamClassName("android.app.IApplicationThread") Object caller, String callingPackage,
                          Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
                          int startFlags, @BParamClassName("android.app.ProfilerInfo") Object profilerInfo, Bundle bOptions);

    @BClassName("android.app.IActivityManager$ContentProviderHolder")
    interface ContentProviderHolderMIUI {
        @BField
        ProviderInfo info();

        @BField
        boolean noReleaseNeeded();

        @BField
        IInterface provider();

        @BField
        boolean waitProcessStart();
    }

    @BClassName("android.app.IActivityManager$ContentProviderHolder")
    interface ContentProviderHolder {
        @BField
        ProviderInfo info();

        @BField
        boolean noReleaseNeeded();

        @BField
        IInterface provider();
    }
}
