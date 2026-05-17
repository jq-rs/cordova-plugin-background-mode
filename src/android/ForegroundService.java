/*
 Copyright 2013 Sebastián Katzer

 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */

package de.appplant.cordova.plugin.background;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.app.NotificationChannel;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.content.pm.ServiceInfo;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;
import android.app.NotificationChannel;

import static android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;


/**
 * Puts the service in a foreground state, where the system considers it to be
 * something the user is actively aware of and thus not a candidate for killing
 * when low on memory.
 */
public class ForegroundService extends Service {

    private static final String TAG = "MlesForegroundService";

    // Fixed ID for the 'foreground' notification
    public static final int NOTIFICATION_ID = -574543954;

    // Default title of the background notification
    private static final String NOTIFICATION_TITLE = "MlesTalk";

    // Default text of the background notification
    private static final String NOTIFICATION_TEXT = "Running in background";

    // Notification when app needs attention
    private static final String NOTIFICATION_TITLE_ATTENTION = "MlesTalk needs attention";
    private static final String NOTIFICATION_TEXT_ATTENTION = "Tap to reopen";

    // Default icon of the background notification
    private static final String NOTIFICATION_ICON = "icon";

    // Binder given to clients
    private final IBinder binder = new ForegroundBinder();

    private static final long NOTIFICATION_THROTTLE_MS = 60000; // 1 minute
    private long lastNotificationTime = 0;

    private WifiLock wfl = null;

    private MlesMonitor mlesMonitor;

    // Store notification text for new messages
    private String notificationText = "New message";

    // Track if main app is alive
    private boolean mainAppNeedsAttention = false;

    /**
     * Allow clients to call on to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Class used for the client Binder.
     */
    class ForegroundBinder extends Binder {
        ForegroundService getService() {
            return ForegroundService.this;
        }
    }

    /**
     * Put the service in a foreground state to prevent app from being killed
     * by the OS.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        //Log.d(TAG, "════ SERVICE CREATED ════");
        keepAwake();
    }

    /**
     * No need to run headless on destroy.
     */
    @Override
    public void onDestroy() {
        //Log.d(TAG, "════ SERVICE DESTROYED ════");
        stopMlesMonitor();
        super.onDestroy();
        sleepWell();
    }

@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    //Log.d(TAG, "onStartCommand, action=" + (intent == null ? "null" : intent.getAction()));

    if (intent != null) {
        String action = intent.getAction();

        if ("START_MONITORING".equals(action)) {
            String channelsJson = intent.getStringExtra("channels");
            String text = intent.getStringExtra("text");
            if (text != null && !text.isEmpty()) {
                notificationText = text;
                //Log.d(TAG, "Stored notification text: " + notificationText);
            }
            //Log.d(TAG, "START_MONITORING, channels: " + channelsJson);
            startMlesMonitorWithChannels(channelsJson);

        } else if ("STOP_MONITORING".equals(action)) {
            //Log.d(TAG, "STOP_MONITORING");
            stopMlesMonitor();

        } else if (BackgroundMode.ACTION_UPDATE_NOTIFICATION.equals(action)) {
            String settingsJson = intent.getStringExtra("settings");
            if (settingsJson != null) {
                try {
                    JSONObject settings = new JSONObject(settingsJson);
                    updateNotification(settings);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse settings", e);
                }
            }
        }
    } else {
        // Service was killed and restarted by Android (START_STICKY) — intent is null.
        // Restart monitoring using the channel data the monitor saved to cache
        // when it was last started.
        startMlesMonitorFromCache();
    }

    return START_STICKY;
}

private void showNewMessageNotificationThrottled(String channel) {
    long now = System.currentTimeMillis();

    if ((now - lastNotificationTime) >= NOTIFICATION_THROTTLE_MS) {
        lastNotificationTime = now;
        showNewMessageNotification(channel);
        //Log.d(TAG, "Notification shown for: " + channel);
    } else {
        long remainingMs = NOTIFICATION_THROTTLE_MS - (now - lastNotificationTime);
        //Log.d(TAG, "Notification throttled, wait " + (remainingMs / 1000) + "s more");
    }
}

private void startMlesMonitorWithChannels(String channelsJson) {
    stopMlesMonitor(); // Stop existing if running

    try {
        mlesMonitor = new MlesMonitor(getApplicationContext());
        mlesMonitor.setOnNewMessageListener((channel, fromUser) -> {
            showNewMessageNotificationThrottled(channel);
        });
        mlesMonitor.setOnResyncListener((channelName) -> {
            //Log.d(TAG, "Channel synced: " + channelName + ", killing main UI process");
            killMainUIProcess();
        });
        mlesMonitor.startWithChannels(channelsJson);
        //Log.d(TAG, "MlesMonitor started with channels");
    } catch (Exception e) {
        Log.e(TAG, "Failed to start MlesMonitor", e);
    }
}

private void startMlesMonitorFromCache() {
    stopMlesMonitor();

    try {
        MlesMonitor monitor = new MlesMonitor(getApplicationContext());
        if (monitor.hasCachedChannels()) {
            mlesMonitor = monitor;
            mlesMonitor.setOnNewMessageListener((channel, fromUser) -> {
                showNewMessageNotificationThrottled(channel);
            });
            mlesMonitor.setOnResyncListener((channelName) -> {
                killMainUIProcess();
            });
            mlesMonitor.start();
            Log.d(TAG, "MlesMonitor restarted from cache after process kill");
        } else {
            Log.d(TAG, "No cached channels available after process kill");
        }
    } catch (Exception e) {
        Log.e(TAG, "Failed to restart MlesMonitor from cache", e);
    }
}

private void killMainUIProcess() {
    // Persist the current checksum state before killing so the cache is
    // fresh if this service is subsequently killed and restarted by Android.
    if (mlesMonitor != null) {
        mlesMonitor.updateCache();
    }

    try {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            Log.e(TAG, "ActivityManager is null, cannot kill main process");
            return;
        }

        String mainProcessName = getPackageName(); // e.g., "io.mles.mlestalk"
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();

        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                // Find the main process (without ":background" suffix)
                if (process.processName.equals(mainProcessName)) {
                    //Log.d(TAG, "Found main UI process with PID: " + process.pid + ", killing it");
                    android.os.Process.killProcess(process.pid);
                    return;
                }
            }
        }

        //Log.d(TAG, "Main UI process not found, it may not be running");
    } catch (Exception e) {
        Log.e(TAG, "Error killing main UI process: " + e.getMessage(), e);
    }
}

    /**
     * Check if main app process is running and update notification accordingly.
     */
    /*private boolean checkMainApp() {
        boolean mainRunning = isMainProcessRunning();
	return mainRunning;
    }*/

    /**
     * Check if the main process (not :background) is running.
     */
    private boolean isMainProcessRunning() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();

        String mainProcess = getPackageName(); // "io.mles.mlestalk" without ":background"

        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.processName.equals(mainProcess)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Put the service in a foreground state.
     */
    private void keepAwake() {
        JSONObject settings = BackgroundMode.getSettings();
        boolean isSilent = settings.optBoolean("silent", false);

        if (!isSilent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
                startForeground(NOTIFICATION_ID, makeNotification(), serviceType);
            } else {
                startForeground(NOTIFICATION_ID, makeNotification());
            }
        }

        //if (wfl == null) {
        //    WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        //    wfl = wm.createWifiLock(WIFI_MODE_FULL_HIGH_PERF, "backgroundmode:sync_all_wifi");
        //    wfl.acquire();
        //}
    }

    /**
     * Stop background mode.
     */
    private void sleepWell() {
        stopForeground(true);
        getNotificationManager().cancel(NOTIFICATION_ID);

        //if (wfl != null) {
        //    wfl.release();
        //    wfl = null;
        //}
    }

    /**
     * Create a notification using default settings.
     */
    private Notification makeNotification() {
        return makeNotification(BackgroundMode.getSettings());
    }

    /**
     * Create a notification as the visible part to be able to put the service
     * in a foreground state.
     */
    private Notification makeNotification(JSONObject settings) {
        String CHANNEL_ID = "cordova-plugin-background-mode-id";

        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence name = "cordova-plugin-background-mode";
            String description = "cordova-plugin-background-mode notification";
            int importance = NotificationManager.IMPORTANCE_HIGH;

            NotificationChannel mChannel = new NotificationChannel(CHANNEL_ID, name, importance);
            mChannel.setDescription(description);
            getNotificationManager().createNotificationChannel(mChannel);
        }

        String title = settings.optString("title", NOTIFICATION_TITLE);
        String text = settings.optString("text", NOTIFICATION_TEXT);
        boolean bigText = settings.optBoolean("bigText", false);
        String largeIcon = settings.optString("largeIcon", null);

        Context context = getApplicationContext();
        String pkgName = context.getPackageName();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkgName);

        Notification.Builder notification = new Notification.Builder(context)
                .setContentTitle(title)
                .setOngoing(true)
                .setSmallIcon(getIconResId(settings));

        if (largeIcon != null) {
            notification.setLargeIcon(BitmapFactory.decodeResource(getResources(), getLargeIconResId(largeIcon)));
        }

        if (Build.VERSION.SDK_INT >= 26) {
            notification.setChannelId(CHANNEL_ID);
        }

        if (settings.optBoolean("hidden", true)) {
            notification.setPriority(Notification.PRIORITY_MIN);
        }



        setColor(notification, settings);

        if (intent != null && settings.optBoolean("resume")) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags | PendingIntent.FLAG_MUTABLE;
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    context, NOTIFICATION_ID, intent, flags);

            notification.setContentIntent(contentIntent);
        }

        return notification.build();
    }

    /**
     * Update the notification.
     */
    protected void updateNotification(JSONObject settings) {
        boolean isSilent = settings.optBoolean("silent", false);

        if (isSilent) {
            stopForeground(true);
            return;
        }

        Notification notification = makeNotification(settings);
        getNotificationManager().notify(NOTIFICATION_ID, notification);
    }

    private int getIconResId(JSONObject settings) {
        String icon = settings.optString("icon", NOTIFICATION_ICON);
        int resId = getIconResId(icon, "mipmap");
        if (resId == 0) {
            resId = getIconResId(icon, "drawable");
        }
        return resId;
    }

    private int getIconResId(String icon, String type) {
        Resources res = getResources();
        String pkgName = getPackageName();
        int resId = res.getIdentifier(icon, type, pkgName);
        if (resId == 0) {
            resId = res.getIdentifier("icon", type, pkgName);
        }
        return resId;
    }

    private int getLargeIconResId(String icon) {
        int resId = getLargeIconResId(icon, "mipmap");
        if (resId == 0) {
            resId = getLargeIconResId(icon, "drawable");
        }
        return resId;
    }

    private int getLargeIconResId(String icon, String type) {
        Resources res = getResources();
        String pkgName = getPackageName();
        int resId = res.getIdentifier(icon, type, pkgName);
        if (resId == 0) {
            resId = res.getIdentifier("icon", type, pkgName);
        }
        return resId;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void setColor(Notification.Builder notification, JSONObject settings) {
        String hex = settings.optString("color", null);
        if (Build.VERSION.SDK_INT < 21 || hex == null) return;

        try {
            int aRGB = Integer.parseInt(hex, 16) + 0xFF000000;
            notification.setColor(aRGB);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

// Add new methods
private void startMlesMonitor() {
    try {
        mlesMonitor = new MlesMonitor(getApplicationContext());
        mlesMonitor.setOnNewMessageListener((channel, fromUser) -> {
            showNewMessageNotificationThrottled(channel);
        });
        mlesMonitor.start();
        //Log.d(TAG, "MlesMonitor started");
    } catch (Exception e) {
        Log.e(TAG, "Failed to start MlesMonitor", e);
    }
}

private void stopMlesMonitor() {
    if (mlesMonitor != null) {
        mlesMonitor.stop();
        mlesMonitor = null;
        //Log.d(TAG, "MlesMonitor stopped");
    }
}

private void showNewMessageNotification(String channel) {
    //if (true == checkMainApp())
    //   return;

    Context context = getApplicationContext();
    String pkgName = context.getPackageName();
    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(pkgName);

    if (launchIntent == null) {
        Log.e(TAG, "Could not get launch intent");
        return;
    }

    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        flags |= PendingIntent.FLAG_IMMUTABLE;
    }

    PendingIntent pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID + 1, launchIntent, flags);

    // Use a separate channel for message notifications with sound/vibration
    String MESSAGE_CHANNEL_ID = "mlestalk-messages";

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationChannel messageChannel = new NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "New Messages",
                NotificationManager.IMPORTANCE_HIGH
        );
        messageChannel.setDescription("Notifications for new messages");
        messageChannel.enableVibration(true);
        messageChannel.setVibrationPattern(new long[]{0, 250, 250, 250});
        messageChannel.enableLights(true);
        messageChannel.setLightColor(android.graphics.Color.BLUE);
        messageChannel.setSound(
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
        );

        getNotificationManager().createNotificationChannel(messageChannel);
    }

    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        builder = new Notification.Builder(context, MESSAGE_CHANNEL_ID);
    } else {
        builder = new Notification.Builder(context);
        // For older Android, set sound and vibration directly
        builder.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
        builder.setVibrate(new long[]{0, 250, 250, 250});
    }

    //Log.d(TAG, "Using notification text: " + notificationText);
    builder.setContentTitle(notificationText + " @ " + channel)
            .setSmallIcon(getIconResId("micon", "mipmap"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL);

    getNotificationManager().notify(NOTIFICATION_ID + 1, builder.build());
    //Log.d(TAG, "New message notification shown for: " + channel);
}
}
