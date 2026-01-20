package de.appplant.cordova.plugin.background;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import io.whitfin.siphash.SipHash;


/**
 * Monitors Mles channels for new messages using OkHttp WebSocket
 */
public class MlesMonitor {
    private static final String TAG = "MlesMonitor";
    private static final String PREFS_NAME = "MlesTalkChannels";
    private static final String PREFS_ACTIVE_CHANNELS = "MlesActiveChannelsCache";
    private static final byte[] SIPHASH_KEY = new byte[16]; // all zeros
    private static final long SYNC_TIMEOUT_MS = 5 * 60 * 1000;

    private static final int BASE_RECONNECT_DELAY = 5000;  // 5 seconds
    private static final int MAX_RECONNECT_DELAY = 180000; // 3 minutes
    private Map<String, Integer> reconnectAttempts = new HashMap<>();
    private Map<String, Boolean> connectionStable = new HashMap<>();

    private Context context;
    private OkHttpClient client;
    private Map<String, WebSocket> connections = new HashMap<>();
    private Map<String, Boolean> channelSynced = new HashMap<>();
    private Map<String, Long> syncStartTime = new HashMap<>();
    private OnNewMessageListener listener;
    private OnResyncListener resyncListener;
    private volatile boolean isRunning = false;
    private volatile boolean networkAvailable = true;

    private final android.os.Handler reconnectHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Map<String, Runnable> pendingReconnects = new HashMap<>();

    // Network callback to handle reconnection when network returns
    private ConnectivityManager.NetworkCallback networkCallback;
    private List<ChannelConfig> activeChannels = new ArrayList<>();

    public interface OnNewMessageListener {
        void onNewMessage(String channel, String fromUser);
    }

    public interface OnResyncListener {
        void onResync(String channelName);
    }

    public MlesMonitor(Context context) {
        this.context = context;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(90, TimeUnit.SECONDS) // Keep connection alive
                .build();
    }

    public void setOnNewMessageListener(OnNewMessageListener listener) {
        this.listener = listener;
    }

    public void setOnResyncListener(OnResyncListener listener) {
        this.resyncListener = listener;
    }

    /**
     * Start monitoring all channels
     */
    public void start() {
        if (isRunning) {
            //Log.d(TAG, "Already running");
            return;
        }

        isRunning = true;

        // Try to load from cache first (for process restart)
        List<ChannelConfig> channels = loadChannelsFromCache();

        // If cache is empty, load from SharedPreferences
        if (channels.isEmpty()) {
            channels = loadChannels();
        }

        //Log.d(TAG, "Found " + channels.size() + " channels to monitor");

        // Store channels for network callback
        activeChannels = channels;

        // Save to cache for future restarts
        saveChannelsToCache(channels);

        // Register network callback
        registerNetworkCallback();

        for (ChannelConfig channel : channels) {
            scheduleReconnect(channel);
        }
    }

    /**
     * Stop all connections
     */
    public void stop() {
        isRunning = false;

        // Unregister network callback
        unregisterNetworkCallback();
        cancelAllReconnects();

        for (Map.Entry<String, WebSocket> entry : connections.entrySet()) {
            try {
                entry.getValue().close(1000, "Service stopping");
            } catch (Exception e) {
                Log.e(TAG, "Error closing WebSocket: " + entry.getKey(), e);
            }
        }
        connections.clear();
        reconnectAttempts.clear();
        connectionStable.clear();
        channelSynced.clear();
        syncStartTime.clear();
        activeChannels.clear();

        // NOTE: We don't clear the cache here - it's needed for START_STICKY restart
        // If you want to clear cache on stop, uncomment: clearChannelsCache();

        //Log.d(TAG, "All connections closed");
    }

    /**
     * Reload channels and reconnect
     */
    public void reload() {
        stop();
        start();
    }

    /**
     * Check if there are cached channels available for recovery
     * Useful to call in Service.onStartCommand() to determine if we can auto-restart
     */
    public boolean hasCachedChannels() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_ACTIVE_CHANNELS, Context.MODE_PRIVATE);
            String cacheJson = prefs.getString("cached_channels", null);
            return cacheJson != null && !cacheJson.isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "Error checking cached channels", e);
            return false;
        }
    }

    /**
     * Start monitoring with channels JSON from Intent
     */
    public void startWithChannels(String channelsJson) {
        if (isRunning) {
            //Log.d(TAG, "Already running, stopping first");
            stop();
        }

        isRunning = true;
        List<ChannelConfig> channels = parseChannelsJson(channelsJson);
        //Log.d(TAG, "Starting with " + channels.size() + " channels from Intent");

        if (channels.isEmpty()) {
            //Log.w(TAG, "No valid channels to monitor");
            return;
        }

        // Store channels for network callback
        activeChannels = channels;

        // Save to cache for process restart
        saveChannelsToCache(channels);

        // Register network callback
        registerNetworkCallback();

        for (ChannelConfig config : channels) {
            scheduleReconnect(config);
        }
    }

    // NEW: Register network state callback
    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "Network callback not supported on this Android version");
            return;
        }

        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                Log.e(TAG, "ConnectivityManager not available");
                return;
            }

            NetworkRequest.Builder builder = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

            // Add VALIDATED capability on Android M+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }

            NetworkRequest request = builder.build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    //Log.d(TAG, "Network available - triggering reconnect for all channels");
		    networkAvailable = true;
                    onNetworkAvailable();
                }

                @Override
                public void onLost(Network network) {
                    //Log.d(TAG, "Network lost");
		    networkAvailable = false;
                    onNetworkLost();
                }
            };

            cm.registerNetworkCallback(request, networkCallback);
            //Log.d(TAG, "Network callback registered");

        } catch (Exception e) {
            Log.e(TAG, "Error registering network callback", e);
        }
    }

    // NEW: Unregister network callback
    private void unregisterNetworkCallback() {
        if (networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                    //Log.d(TAG, "Network callback unregistered");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering network callback", e);
            }
            networkCallback = null;
        }
    }

    // NEW: Handle network available event
    private void onNetworkAvailable() {
        if (!isRunning) {
            //Log.d(TAG, "onNetworkAvailable: not running, ignoring");
            return;
        }

        // Reset backoff for all channels when network returns
        for (ChannelConfig channel : activeChannels) {
            reconnectAttempts.put(channel.channelName, 0);

            // If not connected, reconnect immediately
            if (!connections.containsKey(channel.channelName)) {
                //Log.d(TAG, "Network available - reconnecting: " + channel.channelName);
                connectToChannel(channel);
            }
        }
    }

    // Handle network lost event
    private void onNetworkLost() {
        cancelAllReconnects();
        for (Map.Entry<String, WebSocket> entry : connections.entrySet()) {
            try {
                entry.getValue().close(1000, "Network lost");
            } catch (Exception e) {
                Log.e(TAG, "Error closing WebSocket on network lost", e);
            }
        }
        connections.clear();
    }

    /**
     * Parse channels from JSON string passed via Intent
     */
    private List<ChannelConfig> parseChannelsJson(String channelsJson) {
        List<ChannelConfig> channels = new ArrayList<>();

        if (channelsJson == null || channelsJson.isEmpty()) {
            //Log.w(TAG, "parseChannelsJson: empty JSON");
            return channels;
        }

        try {
            //Log.d(TAG, "Parsing channels JSON: " + channelsJson);
            JSONObject json = new JSONObject(channelsJson);
            Iterator<String> keys = json.keys();

            while (keys.hasNext()) {
                String channelName = keys.next();
                JSONObject data = json.getJSONObject(channelName);

                String userName = data.optString("name", null);
                String channel = data.optString("channel", null);
                String channel_dec = data.optString("channel_dec", null);
                String serverPort = data.optString("server", "mles.io:443");
                String msgChksum = data.optString("msg_chksum", "0");

                if (userName == null || channel == null || userName.isEmpty() || channel.isEmpty()) {
                    //Log.w(TAG, "Skipping channel, missing data: " + channelName);
                    continue;
                }

                // Parse server:port
                String server = "mles.io";
                int port = 443;
                if (serverPort != null && !serverPort.isEmpty()) {
                    String[] parts = serverPort.split(":");
                    if (parts.length >= 1 && !parts[0].isEmpty()) {
                        server = parts[0];
                    }
                    if (parts.length >= 2) {
                        try {
                            port = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException e) {
                            port = 443;
                        }
                    }
                }

                //Log.d(TAG, "Parsed channel: " + channel + " channel_dec: " + channel_dec + " user: " + userName + " @ " + server + ":" + port + " chksum: " + msgChksum);
                channels.add(new ChannelConfig(userName, channel, channel_dec, server, port, msgChksum));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing channels JSON", e);
        }

        return channels;
    }

    /**
     * Channel configuration holder
     */
    private static class ChannelConfig {
        String userName;
        String channelName;
        String channelDecrypted;
        String server;
        int port;
        String msgChksum;

        ChannelConfig(String userName, String channelName, String channelDecrypted, String server, int port, String msgChksum) {
            this.userName = userName;
            this.channelName = channelName;
            this.channelDecrypted = channelDecrypted;
            this.server = server;
            this.port = port;
            this.msgChksum = msgChksum;
        }

        String getWsUrl() {
            return "wss://" + server + ":" + port;
        }

        void setChksum(String msgChksum) {
            this.msgChksum = msgChksum;
        }

        String getChksum() {
            return this.msgChksum;
        }
    }

    /**
     * Load channels from SharedPreferences
     */
    private List<ChannelConfig> loadChannels() {
        List<ChannelConfig> channels = new ArrayList<>();

        try {
            @SuppressWarnings("deprecation")
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
            String activeChannelsJson = prefs.getString("gActiveChannelsJSON", null);

            if (activeChannelsJson == null || activeChannelsJson.isEmpty()) {
                //Log.d(TAG, "No active channels found");
                return channels;
            }

            //Log.d(TAG, "Active channels JSON: " + activeChannelsJson);

            // Parse JSON: {"channel1":"channel1", "channel2":"channel2"}
            JSONObject json = new JSONObject(activeChannelsJson);
            Iterator<String> keys = json.keys();

            while (keys.hasNext()) {
                String channelName = keys.next();
                ChannelConfig config = loadChannelConfig(prefs, channelName);
                if (config != null) {
                    channels.add(config);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading channels", e);
        }

        return channels;
    }

    /**
     * Save channels to cache for process restart recovery
     */
    private void saveChannelsToCache(List<ChannelConfig> channels) {
        if (channels == null || channels.isEmpty()) {
            //Log.d(TAG, "No channels to save to cache");
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_ACTIVE_CHANNELS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            // Create JSON array of channel configs
            JSONObject cacheJson = new JSONObject();
            for (ChannelConfig config : channels) {
                JSONObject channelJson = new JSONObject();
                channelJson.put("userName", config.userName);
                channelJson.put("channelName", config.channelName);
                channelJson.put("channelDecrypted", config.channelDecrypted);
                channelJson.put("server", config.server);
                channelJson.put("port", config.port);
                channelJson.put("msgChksum", config.msgChksum);

                cacheJson.put(config.channelName, channelJson);
            }

            editor.putString("cached_channels", cacheJson.toString());
            editor.apply();

            //Log.d(TAG, "Saved " + channels.size() + " channels to cache");

        } catch (Exception e) {
            Log.e(TAG, "Error saving channels to cache", e);
        }
    }

    /**
     * Load channels from cache (for process restart)
     */
    private List<ChannelConfig> loadChannelsFromCache() {
        List<ChannelConfig> channels = new ArrayList<>();

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_ACTIVE_CHANNELS, Context.MODE_PRIVATE);
            String cacheJson = prefs.getString("cached_channels", null);

            if (cacheJson == null || cacheJson.isEmpty()) {
                //Log.d(TAG, "No cached channels found");
                return channels;
            }

            //Log.d(TAG, "Loading channels from cache");

            JSONObject json = new JSONObject(cacheJson);
            Iterator<String> keys = json.keys();

            while (keys.hasNext()) {
                String channelName = keys.next();
                JSONObject channelJson = json.getJSONObject(channelName);

                String userName = channelJson.optString("userName", null);
                String channel = channelJson.optString("channelName", null);
                String channelDec = channelJson.optString("channelDecrypted", null);
                String server = channelJson.optString("server", "mles.io");
                int port = channelJson.optInt("port", 443);
                String msgChksum = channelJson.optString("msgChksum", "0");

                if (userName != null && channel != null) {
                    channels.add(new ChannelConfig(userName, channel, channelDec, server, port, msgChksum));
                    //Log.d(TAG, "Restored channel from cache: " + channel);
                }
            }

            //Log.d(TAG, "Loaded " + channels.size() + " channels from cache");

        } catch (Exception e) {
            Log.e(TAG, "Error loading channels from cache", e);
        }

        return channels;
    }

    /**
     * Clear cached channels
     */
    private void clearChannelsCache() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_ACTIVE_CHANNELS, Context.MODE_PRIVATE);
            prefs.edit().clear().apply();
            //Log.d(TAG, "Cleared channels cache");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing channels cache", e);
        }
    }

    /**
     * Load configuration for a specific channel
     */
    private ChannelConfig loadChannelConfig(SharedPreferences prefs, String channelName) {
        try {
            String userName = prefs.getString("gMyName" + channelName, null);
            String channel = prefs.getString("gMyChannel" + channelName, null);
            String channelDec = prefs.getString("gMyChannelDec" + channelName, null);
            String addrPort = prefs.getString("gAddrPortInput" + channelName, "mles.io:443");
            String msgChksum = prefs.getString("gMsgChksum" + channelName, "0");

            //Log.d(TAG, "Channel " + channelName + ", Channel decrypted " + channelDec + ": name=" + userName + ", channel=" + channel + ", server=" + addrPort + ", chksum=" + msgChksum);

            // Parse server:port
            String server = "mles.io";
            int port = 443;

            if (addrPort != null && !addrPort.isEmpty()) {
                String[] parts = addrPort.split(":");
                if (parts.length >= 1 && !parts[0].isEmpty()) {
                    server = parts[0];
                }
                if (parts.length >= 2) {
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        port = 443;
                    }
                }
            }

            return new ChannelConfig(userName, channel, channelDec, server, port, msgChksum);

        } catch (Exception e) {
            //Log.e(TAG, "Error loading channel config: " + channelName, e);
            return null;
        }
    }

    /**
     * Connect to a channel via WebSocket
     */
    private void connectToChannel(ChannelConfig config) {
        if (!isRunning) return;

        try {
            String url = config.getWsUrl();
            //Log.d(TAG, "Connecting to: " + url + " for channel: " + config.channelName);

            Request request = new Request.Builder()
                    .url(url)
                    .header("Sec-WebSocket-Protocol", "mles-websocket")
                    .build();

            WebSocket ws = client.newWebSocket(request, new MlesWebSocketListener(config));
            connections.put(config.channelName, ws);

        } catch (Exception e) {
            //Log.e(TAG, "Error connecting to: " + config.channelName, e);
            scheduleReconnect(config);
        }
    }

    private void cancelAllReconnects() {
        for (Runnable r : pendingReconnects.values()) {
            reconnectHandler.removeCallbacks(r);
        }
        pendingReconnects.clear();
    }

    /**
     * Schedule reconnection after failure with exponential backoff
     */
    private void scheduleReconnect(ChannelConfig config) {
	if (!isRunning || !networkAvailable) {
            //Log.d(TAG, "scheduleReconnect: skipped (running=" + isRunning +
                    ", network=" + networkAvailable + ")");
            return;
        }

        Runnable old = pendingReconnects.remove(config.channelName);
        if (old != null) {
            reconnectHandler.removeCallbacks(old);
        }

        int attempts = reconnectAttempts.getOrDefault(config.channelName, 0) + 1;
        reconnectAttempts.put(config.channelName, attempts);

        // Exponential backoff: 5s, 10s, 20s, 40s, 80s, 160s, 180s (max)
        int delay = Math.min(BASE_RECONNECT_DELAY * (1 << (attempts - 1)), MAX_RECONNECT_DELAY);

        //Log.d(TAG, "Scheduling reconnect for: " + config.channelName + " in " + (delay/1000) + "s (attempt " + attempts + ")");

        Runnable task = () -> {
            pendingReconnects.remove(config.channelName);
            if (isRunning && networkAvailable &&
                    !connections.containsKey(config.channelName)) {
                //Log.d(TAG, "Reconnecting to: " + config.channelName);
                connectToChannel(config);
            }
        };

        pendingReconnects.put(config.channelName, task);
        reconnectHandler.postDelayed(task, delay);
    }

    /**
     * Reset backoff only when connection is truly stable
     */
    private void markConnectionStable(String channelName) {
        if (!connectionStable.getOrDefault(channelName, false)) {
            connectionStable.put(channelName, true);
            reconnectAttempts.put(channelName, 0);
            //Log.d(TAG, "Connection stable, reset backoff for: " + channelName);
        }
    }

    /**
     * WebSocket listener for Mles channel
     */
    private class MlesWebSocketListener extends WebSocketListener {

        private final ChannelConfig config;
        private boolean hasReceivedMessage = false;

        MlesWebSocketListener(ChannelConfig config) {
            this.config = config;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            //Log.d(TAG, "WebSocket opened: " + config.channelName);

            // Send join message - Mles protocol first frame
            String joinMessage = String.format(
                    "{\"uid\":\"%s\",\"channel\":\"%s\"}",
                    config.userName,
                    config.channelName
            );

            //Log.d(TAG, "Sending join: " + joinMessage);
            webSocket.send(joinMessage);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (!hasReceivedMessage) {
                hasReceivedMessage = true;
                markConnectionStable(config.channelName);
            }
            handleMessage(text, config);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            if (!hasReceivedMessage) {
                hasReceivedMessage = true;
                markConnectionStable(config.channelName);
            }
            handleMessage(bytes, config);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            //Log.d(TAG, "WebSocket closing: " + config.channelName + " - " + reason);
            webSocket.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            //Log.d(TAG, "WebSocket closed: " + config.channelName + " - " + reason);
            connections.remove(config.channelName);
            channelSynced.put(config.channelName, false);
            if (networkAvailable) {
                scheduleReconnect(config);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            if (!isRunning) {
                // Expected during shutdown - don't log as error
                //Log.d(TAG, "WebSocket closed during shutdown: " + config.channelName);
            } else {
                Log.e(TAG, "WebSocket failure: " + config.channelName);
                connections.remove(config.channelName);
                channelSynced.put(config.channelName, false);
                if (networkAvailable) {
                    scheduleReconnect(config);
                }
            }
        }
    }

    /**
     * Handle incoming message - notify if enough time has passed
     */
    private void handleMessage(String message, ChannelConfig config) {
        //Log.d(TAG, "String message for channel "+ config.channelName + ": " + message);
    }

    /**
     * Handle incoming message - sync first, then notify on new
     */
    private void handleMessage(ByteString message, ChannelConfig config) {
        byte[] messageBytes = message.toByteArray();

        // Compute SipHash with zero key
        long hash = SipHash.hash(SIPHASH_KEY, messageBytes);
        String hashHex = SipHash.toHexString(hash);

        String lastChksum = config.getChksum();
        //Log.d(TAG, "Last chksum for channel "+ config.channelName + ": " + lastChksum + ", got " + hashHex + " message: " + message);
        Boolean synced = channelSynced.get(config.channelName);
        long now = System.currentTimeMillis();

        // Not synced yet - waiting for initial checksum match
        if (synced == null || !synced) {
            if (lastChksum == null || lastChksum.isEmpty() || lastChksum.equals("0")) {
                // No previous checksum - sync immediately
                //Log.d(TAG, "No previous checksum for " + config.channelName + ", synced");
                channelSynced.put(config.channelName, true);
                config.setChksum(hashHex);
                if (resyncListener != null) {
                    resyncListener.onResync(config.channelName);
                }
            } else if (lastChksum.equals(hashHex)) {
                // Found the last known message - now synced
                //Log.d(TAG, "Synced to last message on " + config.channelName);
                channelSynced.put(config.channelName, true);
                syncStartTime.remove(config.channelName);
                if (resyncListener != null) {
                    resyncListener.onResync(config.channelName);
                }
            } else {
                // Check for timeout
                Long startTime = syncStartTime.get(config.channelName);
                if (startTime == null) {
                    // Start the timer
                    syncStartTime.put(config.channelName, now);
                    //Log.d(TAG, "Catching up on " + config.channelName + ", waiting for sync");
                } else if (now - startTime > SYNC_TIMEOUT_MS) {
                    // Timeout - sync to latest
                    //Log.d(TAG, "Sync timeout on " + config.channelName + ", syncing to latest");
                    channelSynced.put(config.channelName, true);
                    config.setChksum(hashHex);
                    syncStartTime.remove(config.channelName);
                    if (resyncListener != null) {
                        resyncListener.onResync(config.channelName);
                    }
                } else {
                    // Still catching up, ignore
                    //Log.d(TAG, "Catching up on " + config.channelName + ", ignoring");
                }
            }
            return;
        }

        // Already synced - check for new messages
        if (lastChksum != null && lastChksum.equals(hashHex)) {
            // Same message, ignore
            return;
        }

        // New message!
        config.setChksum(hashHex);
        //Log.d(TAG, "New message on " + config.channelName + ", checksum: " + hashHex);

        if (listener != null) {
            listener.onNewMessage(config.channelDecrypted, null);
        }
    }
}
