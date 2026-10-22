package net.wastu.mifitness.enhanced;

import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class NotificationHooks {

    private static final String TAG = "[MiFitnessEnhanced/Notify] ";
    private static final String NOTIFY_SERVICE_CLASS = "com.xiaomi.fitness.notify.BaseNotifySyncService";

    static void install(ClassLoader classLoader) {
        Class<?> serviceClass = XposedHelpers.findClassIfExists(NOTIFY_SERVICE_CLASS, classLoader);
        if (serviceClass == null) {
            XposedBridge.log(TAG + NOTIFY_SERVICE_CLASS + " not found, notification hooks disabled");
            return;
        }

        AlarmBridge.install(classLoader);

        try {
            XposedHelpers.findAndHookMethod(serviceClass, "onNotificationPosted", StatusBarNotification.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        NotificationListenerService service = (NotificationListenerService) param.thisObject;
                        StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                        if (sbn == null) {
                            return;
                        }
                        if (AlarmBridge.isFiringClockAlarm(sbn)) {
                            // Mi Fitness drops ongoing notifications anyway; route it to the band alarm instead
                            AlarmBridge.onAlarmFiring(service, sbn);
                            param.setResult(null);
                            return;
                        }
                        if (isSilent(service, sbn)) {
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "onNotificationPosted hook failed: " + t);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(serviceClass, "onNotificationRemoved", StatusBarNotification.class,
                    NotificationListenerService.RankingMap.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                                if (sbn != null) {
                                    AlarmBridge.onNotificationRemoved(sbn);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "onNotificationRemoved hook failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + "Hooked " + NOTIFY_SERVICE_CLASS);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking " + NOTIFY_SERVICE_CLASS + ": " + t);
        }
    }

    /** Notifications Android shows in the "Silent" section (channel importance LOW or MIN). */
    private static boolean isSilent(NotificationListenerService service, StatusBarNotification sbn) {
        NotificationListenerService.RankingMap rankingMap = service.getCurrentRanking();
        if (rankingMap == null) {
            return false;
        }
        NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
        if (!rankingMap.getRanking(sbn.getKey(), ranking)) {
            return false;
        }
        int importance = ranking.getImportance();
        return importance != NotificationManager.IMPORTANCE_UNSPECIFIED
                && importance < NotificationManager.IMPORTANCE_DEFAULT;
    }
}
