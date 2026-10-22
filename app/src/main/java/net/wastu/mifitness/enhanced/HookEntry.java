package net.wastu.mifitness.enhanced;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[MiFitnessEnhanced] ";
    private static final String TARGET_PKG = "com.xiaomi.wearable";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + "Initializing hooks for " + lpparam.packageName + " (process: " + lpparam.processName + ")");

        // Telemetry blocking (formerly mifitness-notrack)
        NoTrackHooks.install(lpparam.classLoader);

        // Notification listener hooks: Google Clock alarm bridge + silent notification filter
        NotificationHooks.install(lpparam.classLoader);
    }
}
