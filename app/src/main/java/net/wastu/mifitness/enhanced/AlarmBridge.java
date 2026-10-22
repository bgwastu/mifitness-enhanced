package net.wastu.mifitness.enhanced;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Field;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes the band ring for Google Clock alarms and expired timers.
 *
 * Mi Fitness only rings the band for phone alarms when Xiaomi's own clock binds to
 * AlarmConsoleService and calls onAlert. Google Clock never does, so we watch for its
 * firing notification and drive AlarmConsoleService ourselves. Dismiss/snooze on the band
 * reaches AlarmConsoleService.onStartCommand, which we forward to the clock's notification
 * actions; dismissing on the phone removes the notification, which stops the band.
 */
final class AlarmBridge {

    private static final String TAG = "[MiFitnessEnhanced/Alarm] ";
    private static final String CLOCK_PKG = "com.google.android.deskclock";
    private static final String ALARM_SERVICE_CLASS = "com.xiaomi.fitness.devicesettings.common.alarm.AlarmConsoleService";
    private static final String CONSOLE_ALARM_CLASS = "com.android.deskclock.console.ConsoleAlarm";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static ClassLoader classLoader;
    private static Object alarmService;
    private static boolean binding;
    private static Runnable pendingAlert;

    // State of the currently ringing clock alarm; only touched on the main thread
    private static String activeKey;
    private static int activeId = -1;
    private static Notification activeNotification;
    // Clock may re-post the firing notification briefly after a band action; don't ring again for it
    private static String handledKey;
    private static long handledAt;

    static void install(ClassLoader cl) {
        classLoader = cl;
        Class<?> serviceClass = XposedHelpers.findClassIfExists(ALARM_SERVICE_CLASS, cl);
        if (serviceClass == null) {
            XposedBridge.log(TAG + ALARM_SERVICE_CLASS + " not found, alarm bridge disabled");
            return;
        }
        try {
            // Band dismiss/snooze arrives as Companion.dismissAlarm -> startService(alarm_id, snooze)
            XposedHelpers.findAndHookMethod(serviceClass, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Intent intent = (Intent) param.args[0];
                        if (intent == null) {
                            return;
                        }
                        int alarmId = intent.getIntExtra("alarm_id", -1);
                        boolean snooze = intent.getBooleanExtra("snooze", false);
                        MAIN.post(() -> onBandAction(alarmId, snooze));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "onStartCommand hook failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "Hooked " + ALARM_SERVICE_CLASS);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking " + ALARM_SERVICE_CLASS + ": " + t);
        }
    }

    static boolean isFiringClockAlarm(StatusBarNotification sbn) {
        if (!CLOCK_PKG.equals(sbn.getPackageName())) {
            return false;
        }
        Notification n = sbn.getNotification();
        XposedBridge.log(TAG + "Clock notification " + sbn.getKey() + " category=" + (n != null ? n.category : null)
                + " fullScreen=" + (n != null && n.fullScreenIntent != null) + " channel=" + (n != null ? n.getChannelId() : null));
        // The ringing alarm is the full-screen alarm notification; "upcoming alarm" ones have no full-screen intent
        return n != null && Notification.CATEGORY_ALARM.equals(n.category) && n.fullScreenIntent != null;
    }

    static void onAlarmFiring(NotificationListenerService listener, StatusBarNotification sbn) {
        Context context = listener.getApplicationContext();
        MAIN.post(() -> {
            if (sbn.getKey().equals(handledKey) && System.currentTimeMillis() - handledAt < 5000) {
                return;
            }
            if (sbn.getKey().equals(activeKey)) {
                // Clock re-posts the same notification while ringing; keep the band ringing once
                activeNotification = sbn.getNotification();
                return;
            }
            activeKey = sbn.getKey();
            activeId = (sbn.getKey().hashCode() & 0x7fffffff) % 1_000_000 + 1;
            activeNotification = sbn.getNotification();
            final int id = activeId;
            final long when = sbn.getNotification().when > 0 ? sbn.getNotification().when : System.currentTimeMillis();
            final String label = labelOf(sbn.getNotification());
            XposedBridge.log(TAG + "Clock alarm firing: " + sbn.getKey() + " -> band alarm " + id + " \"" + label + "\"");
            withAlarmService(context, () -> alertBand(id, when, label));
        });
    }

    static void onNotificationRemoved(StatusBarNotification sbn) {
        if (!CLOCK_PKG.equals(sbn.getPackageName())) {
            return;
        }
        MAIN.post(() -> {
            if (activeKey == null || !activeKey.equals(sbn.getKey())) {
                return;
            }
            XposedBridge.log(TAG + "Clock alarm notification removed, stopping band alarm " + activeId);
            int id = activeId;
            clearActive();
            dismissBand(id);
        });
    }

    private static void onBandAction(int alarmId, boolean snooze) {
        if (alarmId == -1 || alarmId != activeId) {
            return;
        }
        XposedBridge.log(TAG + "Band " + (snooze ? "snoozed" : "dismissed") + " alarm " + alarmId);
        Notification notification = activeNotification;
        handledKey = activeKey;
        handledAt = System.currentTimeMillis();
        clearActive();
        if (alarmService != null) {
            XposedHelpers.setIntField(alarmService, "mAlarmId", -1);
        }
        PendingIntent action = findClockAction(notification, snooze);
        if (action == null) {
            XposedBridge.log(TAG + "No matching clock action found for " + (snooze ? "snooze" : "dismiss"));
            return;
        }
        try {
            action.send();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to send clock action: " + t);
        }
    }

    private static void clearActive() {
        activeKey = null;
        activeId = -1;
        activeNotification = null;
    }

    private static void alertBand(int id, long when, String label) {
        if (id != activeId) {
            return; // dismissed while we were binding
        }
        try {
            Object consoleAlarm = newConsoleAlarm(id, when, label);
            XposedHelpers.setIntField(alarmService, "mAlarmId", id);
            XposedHelpers.callMethod(alarmService, "alert", consoleAlarm);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to alert band: " + t);
        }
    }

    private static void dismissBand(int id) {
        if (alarmService == null || id == -1) {
            return;
        }
        try {
            Object consoleAlarm = newConsoleAlarm(id, 0L, null);
            XposedHelpers.callMethod(alarmService, "dismiss", consoleAlarm, false);
            XposedHelpers.setIntField(alarmService, "mAlarmId", -1);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to dismiss band alarm: " + t);
        }
    }

    /** Builds a ConsoleAlarm through its Parcelable layout (int id, long time, String label) so field renames don't matter. */
    private static Object newConsoleAlarm(int id, long when, String label) throws Exception {
        Class<?> clazz = XposedHelpers.findClass(CONSOLE_ALARM_CLASS, classLoader);
        Parcelable.Creator<?> creator = (Parcelable.Creator<?>) clazz.getField("CREATOR").get(null);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(id);
            parcel.writeLong(when);
            parcel.writeString(label);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    /** Binds (in-process) to AlarmConsoleService once and keeps it bound so its instance stays alive. */
    private static void withAlarmService(Context context, Runnable task) {
        if (alarmService != null) {
            task.run();
            return;
        }
        pendingAlert = task;
        if (binding) {
            return;
        }
        binding = true;
        Intent intent = new Intent().setComponent(new ComponentName(context.getPackageName(), ALARM_SERVICE_CLASS));
        boolean ok = context.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    alarmService = outerInstance(binder);
                    XposedBridge.log(TAG + "Bound to AlarmConsoleService");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "Could not resolve AlarmConsoleService instance: " + t);
                }
                binding = false;
                Runnable task = pendingAlert;
                pendingAlert = null;
                if (task != null && alarmService != null) {
                    task.run();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                alarmService = null;
            }
        }, Context.BIND_AUTO_CREATE);
        if (!ok) {
            binding = false;
            XposedBridge.log(TAG + "bindService to AlarmConsoleService failed");
        }
    }

    /** The binder is AlarmConsoleService's anonymous mBinder; its synthetic this$0 is the service. */
    private static Object outerInstance(IBinder binder) throws Exception {
        Class<?> serviceClass = XposedHelpers.findClass(ALARM_SERVICE_CLASS, classLoader);
        for (Class<?> c = binder.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (serviceClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(binder);
                }
            }
        }
        throw new IllegalStateException("no outer AlarmConsoleService field on " + binder.getClass());
    }

    /**
     * Alarms: Google Clock puts the label in the title (the text is just "<time> • Swipe to stop").
     * Timers: MetricStyle with no title; the label is in the first android.metrics entry.
     */
    private static String labelOf(Notification n) {
        Bundle extras = n.extras;
        if (extras == null) {
            return "Alarm";
        }
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (title != null && title.length() > 0) {
            return title.toString();
        }
        Object metrics = extras.get("android.metrics");
        if (metrics instanceof java.util.List && !((java.util.List<?>) metrics).isEmpty()
                && ((java.util.List<?>) metrics).get(0) instanceof Bundle) {
            CharSequence label = ((Bundle) ((java.util.List<?>) metrics).get(0)).getCharSequence("label");
            return label != null && label.length() > 0 ? label.toString() : "Timer";
        }
        return "Alarm";
    }

    private static PendingIntent findClockAction(Notification n, boolean snooze) {
        if (n == null || n.actions == null || n.actions.length == 0) {
            return null;
        }
        Notification.Action snoozeAction = null;
        Notification.Action dismissAction = null;
        for (Notification.Action action : n.actions) {
            String title = action.title != null ? action.title.toString().toLowerCase(Locale.ROOT) : "";
            // Timers have [Stop, Add 1 min]; treat "add a minute" as the band's snooze
            if (title.contains("snooze") || title.startsWith("add") || title.startsWith("+")) {
                snoozeAction = action;
            } else if (title.contains("dismiss") || title.contains("stop")) {
                dismissAction = action;
            }
        }
        // Fallback for non-English locales: Google Clock orders the actions [Snooze, Stop]
        if (snoozeAction == null && dismissAction == null && n.actions.length >= 2) {
            snoozeAction = n.actions[0];
            dismissAction = n.actions[n.actions.length - 1];
        }
        Notification.Action chosen = snooze ? snoozeAction : dismissAction;
        if (chosen == null && !snooze) {
            chosen = n.actions[n.actions.length - 1];
        }
        return chosen != null ? chosen.actionIntent : null;
    }
}
