
package de.appplant.cordova.plugin.background;

import android.os.PowerManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.util.Log;
import android.app.Service;

import android.os.SystemClock;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;

import static android.content.Context.POWER_SERVICE;
import static android.os.PowerManager.PARTIAL_WAKE_LOCK;
import static android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;

public class AlarmReceiver extends BroadcastReceiver {
	private PowerManager.WakeLock wakeLock;
	private AlarmManager alarmMgr;
	private PendingIntent alarmIntent;
	private WifiLock wfl;
	
    @Override
    public void onReceive(Context context, Intent intent) {
        PowerManager pm = (PowerManager)context.getSystemService(POWER_SERVICE);
		WifiManager wm = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);

        wakeLock = pm.newWakeLock(
                PARTIAL_WAKE_LOCK, "backgroundmode:wakelock");

        wakeLock.acquire();		
		wfl = wm.createWifiLock(WIFI_MODE_FULL_HIGH_PERF, "backgroundmode:sync_all_wifi");
		wfl.acquire();
				
		alarmMgr = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
		Intent nextIntent = new Intent(context, AlarmReceiver.class);

		alarmIntent = PendingIntent.getBroadcast(context, 101, nextIntent, 0);
		alarmMgr.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
										   SystemClock.elapsedRealtime() + 120 * 1000, alarmIntent);

		wfl.release();
		wfl = null;
		wakeLock.release();
        wakeLock = null;
	}
}