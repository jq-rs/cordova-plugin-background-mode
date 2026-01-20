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

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import android.content.SharedPreferences;
import android.content.Context;

import static de.appplant.cordova.plugin.background.BackgroundModeExt.clearKeyguardFlags;

public class BackgroundMode extends CordovaPlugin {

    private static final String TAG = "MlesBackgroundMode";

    // Event types for callbacks
    private enum Event { ACTIVATE, DEACTIVATE, FAILURE }

    // Plugin namespace
    private static final String JS_NAMESPACE = "cordova.plugins.backgroundMode";

    // Flag indicates if the app is in background or foreground
    private boolean inBackground = false;

    // Flag indicates if the plugin is enabled or disabled
    private boolean isDisabled = true;

    // Flag indicates if the service is running
    private boolean isServiceRunning = false;

    // Default settings for the notification
    private static JSONObject defaultSettings = new JSONObject();

    // Intent actions for cross-process communication
    public static final String ACTION_UPDATE_NOTIFICATION = "de.appplant.cordova.plugin.background.UPDATE_NOTIFICATION";

    /**
     * Executes the request.
     *
     * @param action   The action to execute.
     * @param args     The exec() arguments.
     * @param callback The callback context used when
     *                 calling back into JavaScript.
     *
     * @return Returning false results in a "MethodNotFound" error.
     */
    @Override
    public boolean execute (String action, JSONArray args,
                            CallbackContext callback)
    {
        boolean validAction = true;

        switch (action)
        {
            case "configure":
                configure(args.optJSONObject(0), args.optBoolean(1));
                break;
            case "enable":
                enableMode();
                break;
            case "disable":
                disableMode();
                break;
	    case "setChannels":
		setChannels(args.optJSONObject(0));
		break;
            default:
                validAction = false;
        }

        if (validAction) {
            callback.success();
        } else {
            callback.error("Invalid action: " + action);
        }

        return validAction;
    }

    /**
     * Called when the system is about to start resuming a previous activity.
     *
     * @param multitasking Flag indicating if multitasking is turned on for app.
     */
    @Override
    public void onPause(boolean multitasking)
    {
        try {
            inBackground = true;
            startService();
        } finally {
            clearKeyguardFlags(cordova.getActivity());
        }
    }

    /**
     * Called when the activity is no longer visible to the user.
     */
    @Override
    public void onStop () {
        clearKeyguardFlags(cordova.getActivity());
    }

    /**
     * Called when the activity will start interacting with the user.
     *
     * @param multitasking Flag indicating if multitasking is turned on for app.
     */
    @Override
    public void onResume(boolean multitasking)
    {
	    inBackground = false;
	    stopMonitoring();
    }

    private void stopMonitoring() {
	    Activity context = cordova.getActivity();
	    Intent intent = new Intent(context, ForegroundService.class);
	    intent.setAction("STOP_MONITORING");
	    try {
		    context.startService(intent);
		    Log.d(TAG, "Sent STOP_MONITORING to service");
	    } catch (Exception e) {
		    Log.e(TAG, "Failed to stop monitoring: " + e.getMessage());
	    }
    }

    /**
     * Called when the activity will be destroyed.
     */
    @Override
    public void onDestroy()
    {
        Log.d(TAG, "onDestroy, kill pid");
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /**
     * Enable the background mode.
     */
    private void enableMode()
    {
        isDisabled = false;

        if (inBackground) {
            startService();
        }
    }

private void setChannels(JSONObject channels) {
    if (channels == null) return;

    try {
        SharedPreferences prefs = cordova.getActivity()
                .getSharedPreferences("MlesTalkChannels", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Clear old data
        editor.clear();

        // Save each channel's data
        Iterator<String> keys = channels.keys();
        while (keys.hasNext()) {
            String channelName = keys.next();
            JSONObject channelData = channels.getJSONObject(channelName);

            editor.putString("gMyName" + channelName, channelData.optString("name"));
            editor.putString("gMyChannel" + channelName, channelData.optString("channel"));
            editor.putString("gMyChannelDec" + channelName, channelData.optString("channel_dec"));
            editor.putString("gAddrPortInput" + channelName, channelData.optString("server"));
            editor.putString("gMsgChksum" + channelName, channelData.optString("msg_chksum"));
        }

        // Save active channels list
        editor.putString("gActiveChannelsJSON", channels.toString());
        editor.apply();

        Log.d(TAG, "Channels saved: " + channels.toString());

    } catch (Exception e) {
        Log.e(TAG, "Error saving channels", e);
    }
}

    /**
     * Disable the background mode.
     */
    private void disableMode()
    {
        stopService();
        isDisabled = true;
    }

    /**
     * Update the default settings and configure the notification.
     *
     * @param settings The settings
     * @param update A truthy value means to update the running service.
     */
    private void configure(JSONObject settings, boolean update)
    {
    	Log.d(TAG, "configure: settings=" + (settings == null ? "null" : settings.toString()));

    	// Check if we should stop monitoring
    	if (settings.optBoolean("stopMonitoring", false)) {
        	Log.d(TAG, "configure: stopMonitoring requested");
        	stopMonitoring();
    	}

        if (update) {
            updateNotification(settings);
        } else {
            setDefaultSettings(settings);
        }

	// Check if channels are included and start monitoring
	JSONObject channels = settings.optJSONObject("channels");
	if (channels != null && channels.length() > 0) {
		if (inBackground) {
			Log.d(TAG, "configure: found channels, starting monitoring");
			String text = settings.optString("text", "New message");
			startServiceWithChannels(channels.toString(), text);
        	} else {
            		Log.d(TAG, "configure: found channels but app is in foreground, skipping");
        	}
	}
    }

    private void startServiceWithChannels(String channelsJson, String text) {
	Activity context = cordova.getActivity();
	Intent intent = new Intent(context, ForegroundService.class);
	intent.setAction("START_MONITORING");
	intent.putExtra("channels", channelsJson);
	intent.putExtra("text", text);

	try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.startForegroundService(intent);
		} else {
			context.startService(intent);
		}
		isServiceRunning = true;
		Log.d(TAG, "Service started with channels");
	} catch (Exception e) {
		Log.e(TAG, "Error starting service: " + e.getMessage());
	}
    }

    /**
     * Update the default settings for the notification.
     *
     * @param settings The new default settings
     */
    private void setDefaultSettings(JSONObject settings)
    {
        defaultSettings = settings;
    }

    /**
     * Returns the settings for the new/updated notification.
     */
    static JSONObject getSettings () {
        return defaultSettings;
    }

    /**
     * Update the notification via Intent (works across processes).
     *
     * @param settings The config settings
     */
    private void updateNotification(JSONObject settings)
    {
        if (!isServiceRunning) return;

        Activity context = cordova.getActivity();
        Intent intent = new Intent(context, ForegroundService.class);
        intent.setAction(ACTION_UPDATE_NOTIFICATION);
        intent.putExtra("settings", settings.toString());

        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update notification: " + e.getMessage());
        }
    }

    /**
     * Start the background service.
     */
    private void startService()
    {
        Activity context = cordova.getActivity();

        if (isDisabled || isServiceRunning)
            return;

        Intent intent = new Intent(context, ForegroundService.class);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            fireEvent(Event.ACTIVATE, null);
            isServiceRunning = true;
            Log.d(TAG, "Service started");
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'%s'", e.getMessage()));
            Log.e(TAG, "Failed to start service: " + e.getMessage());
        }
    }

    /**
     * Stop the background service.
     */
    private void stopService()
    {
        Activity context = cordova.getActivity();
        Intent intent = new Intent(context, ForegroundService.class);

        if (!isServiceRunning) return;

        try {
            fireEvent(Event.DEACTIVATE, null);
            context.stopService(intent);
            isServiceRunning = false;
            Log.d(TAG, "Service stopped");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop service: " + e.getMessage());
        }
    }

    /**
     * Fire event with some parameters inside the web view.
     *
     * @param event The name of the event
     * @param params Optional arguments for the event
     */
    private void fireEvent (Event event, String params)
    {
        String eventName = event.name().toLowerCase();
        Boolean active   = event == Event.ACTIVATE;

        String str = String.format("%s._setActive(%b)",
                JS_NAMESPACE, active);

        str = String.format("%s;%s.on('%s', %s)",
                str, JS_NAMESPACE, eventName, params);

        str = String.format("%s;%s.fireEvent('%s',%s);",
                str, JS_NAMESPACE, eventName, params);

        final String js = str;

        cordova.getActivity().runOnUiThread(() -> webView.loadUrl("javascript:" + js));
    }
}
