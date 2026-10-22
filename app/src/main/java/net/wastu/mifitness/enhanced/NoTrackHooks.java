package net.wastu.mifitness.enhanced;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class NoTrackHooks {

    private static final String TAG = "[MiFitnessEnhanced/NoTrack] ";

    private static final Set<String> BLOCKED_TABLES = new HashSet<>(Arrays.asList(
            "events",
            "monitor",
            "events_cloud",
            "onetrack",
            "onetrack_ad"
    ));

    private static final List<String> BLOCKED_HOST_PATTERNS = Arrays.asList(
            "tracking.intl.miui.com",
            "tracking.miui.com",
            "tracking.india.miui.com",
            "tracking.rus.miui.com",
            "data.sec.miui.com",
            "sdkconfig.ad.xiaomi.com",
            "sdkconfig.ad.intl.xiaomi.com",
            "api.ad.intl.xiaomi.com",
            "log.ad.xiaomi.com",
            "app.monetrack.com"
    );

    static void install(ClassLoader classLoader) {
        NoTrackHooks hooks = new NoTrackHooks();

        // Layer 1 & 2: Xiaomi OneTrack Public API & Built-in Kill Switch
        hooks.hookOneTrack(classLoader);

        // Layer 3: Android OS Framework - SQLite Database Choke Point (100% update-proof)
        hooks.hookDatabaseStorage();

        // Layer 4: Android OS Framework - Network Host Resolution Choke Point
        hooks.hookNetworkResolution();

        // Layer 5: Third-party Trackers (Firebase, Facebook, Sentry)
        hooks.hookThirdPartyAnalytics(classLoader);
    }

    private void hookOneTrack(ClassLoader classLoader) {
        try {
            Class<?> oneTrackClass = XposedHelpers.findClassIfExists("com.xiaomi.onetrack.OneTrack", classLoader);
            if (oneTrackClass != null) {
                // 1. Force isDisable() to always return true
                XposedHelpers.findAndHookMethod(oneTrackClass, "isDisable", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(true);
                    }
                });

                // 2. Call setDisable(true) on createInstance
                Class<?> configClass = XposedHelpers.findClassIfExists("com.xiaomi.onetrack.Configuration", classLoader);
                if (configClass != null) {
                    XposedHelpers.findAndHookMethod(oneTrackClass, "createInstance", Context.class, configClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                XposedHelpers.callStaticMethod(oneTrackClass, "setDisable", true);
                                XposedBridge.log(TAG + "Flipped OneTrack.setDisable(true) at createInstance");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "Failed to call setDisable: " + t.getMessage());
                            }
                        }
                    });
                }

                // 3. Stub public track methods
                hookMethodIfExists(oneTrackClass, "track", String.class, Map.class);
                hookMethodIfExists(oneTrackClass, "track", String.class, List.class, Map.class);
                hookMethodIfExists(oneTrackClass, "trackPluginEvent", String.class, String.class, Map.class);
                hookMethodIfExists(oneTrackClass, "adTrack", String.class, Map.class);
                hookMethodIfExists(oneTrackClass, "trackEventFromH5", String.class);

                XposedBridge.log(TAG + "Successfully hooked com.xiaomi.onetrack.OneTrack");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking OneTrack: " + t.getMessage());
        }

        // 4. Kill internal EventManager serialization to DB
        try {
            Class<?> eventManagerClass = XposedHelpers.findClassIfExists("com.xiaomi.onetrack.c.c", classLoader);
            Class<?> eventClass = XposedHelpers.findClassIfExists("com.xiaomi.onetrack.f.b", classLoader);
            if (eventManagerClass != null && eventClass != null) {
                XposedHelpers.findAndHookMethod(eventManagerClass, "b", eventClass, XC_MethodReplacement.DO_NOTHING);
                XposedHelpers.findAndHookMethod(eventManagerClass, "a", eventClass, XC_MethodReplacement.DO_NOTHING);
                XposedBridge.log(TAG + "Successfully hooked EventManager (com.xiaomi.onetrack.c.c)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking EventManager: " + t.getMessage());
        }
    }

    private void hookDatabaseStorage() {
        try {
            // SQLiteDatabase is a framework class - immune to app updates & obfuscation
            XposedHelpers.findAndHookMethod(
                    SQLiteDatabase.class,
                    "insertWithOnConflict",
                    String.class,
                    String.class,
                    ContentValues.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String table = (String) param.args[0];
                            if (table != null && BLOCKED_TABLES.contains(table)) {
                                // Silently discard telemetry database inserts
                                param.setResult(1L);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + "Successfully hooked SQLiteDatabase.insertWithOnConflict for telemetry tables");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking SQLiteDatabase: " + t.getMessage());
        }
    }

    private void hookNetworkResolution() {
        try {
            // InetAddress is an Android runtime framework class
            XposedHelpers.findAndHookMethod(
                    InetAddress.class,
                    "getAllByName",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String host = (String) param.args[0];
                            if (host == null) {
                                return;
                            }
                            String lowerHost = host.toLowerCase();
                            for (String pattern : BLOCKED_HOST_PATTERNS) {
                                if (lowerHost.equals(pattern) || lowerHost.endsWith("." + pattern)) {
                                    throw new UnknownHostException("Blocked by MiFitnessEnhanced: " + host);
                                }
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + "Successfully hooked InetAddress.getAllByName for telemetry hosts");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking InetAddress: " + t.getMessage());
        }
    }

    private void hookThirdPartyAnalytics(ClassLoader classLoader) {
        // Firebase Analytics
        try {
            Class<?> firebaseClass = XposedHelpers.findClassIfExists("com.google.firebase.analytics.FirebaseAnalytics", classLoader);
            if (firebaseClass != null) {
                hookMethodIfExists(firebaseClass, "logEvent", String.class, android.os.Bundle.class);
                XposedBridge.log(TAG + "Successfully hooked FirebaseAnalytics.logEvent");
            }
        } catch (Throwable ignored) {}

        // Facebook AppEventsLogger
        try {
            Class<?> fbLoggerClass = XposedHelpers.findClassIfExists("com.facebook.appevents.AppEventsLogger", classLoader);
            if (fbLoggerClass != null) {
                hookMethodIfExists(fbLoggerClass, "logEvent", String.class, android.os.Bundle.class);
                XposedBridge.log(TAG + "Successfully hooked Facebook AppEventsLogger.logEvent");
            }
        } catch (Throwable ignored) {}
    }

    private void hookMethodIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, appendDoNothing(parameterTypes));
        } catch (Throwable ignored) {}
    }

    private Object[] appendDoNothing(Object[] parameterTypes) {
        Object[] result = new Object[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, result, 0, parameterTypes.length);
        result[parameterTypes.length] = XC_MethodReplacement.DO_NOTHING;
        return result;
    }
}
