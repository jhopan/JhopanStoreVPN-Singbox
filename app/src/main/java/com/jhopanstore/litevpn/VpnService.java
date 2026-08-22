package com.jhopanstore.litevpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.jhopanstore.litevpn.core.SingboxConfig;
import com.jhopanstore.litevpn.core.VlessConfig;
import com.jhopanstore.litevpn.core.VlessParser;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.github.sagernet.libbox.libbox.BoxService;
import io.github.sagernet.libbox.libbox.InterfaceUpdateListener;
import io.github.sagernet.libbox.libbox.Libbox;
import io.github.sagernet.libbox.libbox.NetworkInterfaceIterator;
import io.github.sagernet.libbox.libbox.PlatformInterface;
import io.github.sagernet.libbox.libbox.SetupOptions;
import io.github.sagernet.libbox.libbox.StringIterator;
import io.github.sagernet.libbox.libbox.TunOptions;
import io.github.sagernet.libbox.libbox.WIFIState;

public final class VpnService extends android.net.VpnService {
    public interface Listener { void onState(String state); }
    private static volatile Listener listener;
    private static final String ACTION_STOP = "com.jhopanstore.litevpn.STOP";
    private static final String EXTRA_URI = "uri";
    private static final String STATUS_PREFS = "vpn_status";
    private static final String KEY_URI = "uri";
    private static final String KEY_STATE = "state";
    private static final String KEY_LAST_SEEN = "last_seen";
    private static final String KEY_LAST_PROBE = "last_probe_success";
    private static final int NOTIFICATION_ID = 7;
    private static final String CHANNEL = "vpn";
    private static final long HEALTH_CHECK_MS = 30_000;
    private static final String HEALTH_URL = "http://connectivitycheck.gstatic.com/generate_204";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final Object lifecycleLock = new Object();
    private BoxService service;
    private ParcelFileDescriptor tun;
    private InterfaceUpdateListener interfaceListener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean connecting;
    private boolean running;
    private int failedProbes;

    public static void setListener(Listener value) { listener = value; }
    public static void start(Context context, String uri) {
        Intent intent = new Intent(context, VpnService.class).putExtra(EXTRA_URI, uri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent); else context.startService(intent);
    }
    public static void stop(Context context) { context.startService(new Intent(context, VpnService.class).setAction(ACTION_STOP)); }
    private static void state(String value) { if (listener != null) listener.onState(value); }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            SetupOptions options = new SetupOptions();
            options.setBasePath(getFilesDir().getAbsolutePath());
            Libbox.setup(options);
        } catch (Exception error) { Log.e("VpnService", "libbox setup", error); }
        heartbeat.scheduleAtFixedRate(this::writeHeartbeat, 0, 3, TimeUnit.SECONDS);
        heartbeat.scheduleAtFixedRate(this::checkTunnel, HEALTH_CHECK_MS, HEALTH_CHECK_MS, TimeUnit.MILLISECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { disconnect(); return START_NOT_STICKY; }
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { disconnect(); return START_NOT_STICKY; }
        String uri = intent == null ? statusPrefs().getString(KEY_URI, null) : intent.getStringExtra(EXTRA_URI);
        if (uri == null) { setState("Disconnected"); stopSelf(); return START_NOT_STICKY; }
        synchronized (lifecycleLock) {
            if (running || connecting) return START_STICKY;
            connecting = true;
        }
        statusPrefs().edit().putString(KEY_URI, uri).apply();
        startForeground(NOTIFICATION_ID, notification("Connecting…"));
        setState("Connecting…");
        worker.execute(() -> connect(uri));
        return START_STICKY;
    }

    private void connect(String uri) {
        try {
            VlessConfig original = VlessParser.parse(uri);
            String dialAddress = resolveIpv4(original.address);
            VlessConfig config = new VlessConfig(dialAddress, original.port, original.uuid, original.path, original.sni, original.host, original.allowInsecure);
            String json = SingboxConfig.build(config, getFilesDir().getAbsolutePath());
            Libbox.checkConfig(json);
            closeCore();
            service = Libbox.newService(json, new Platform());
            service.start();
            synchronized (lifecycleLock) { connecting = false; running = true; failedProbes = 0; }
            updateNotification("Connected");
            setState("Connected");
        } catch (Exception error) {
            Log.e("VpnService", "connect", error);
            synchronized (lifecycleLock) { connecting = false; }
            disconnect();
        }
    }

    private String resolveIpv4(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address instanceof Inet4Address) return address.getHostAddress();
            }
        } catch (Exception error) { Log.w("VpnService", "DNS resolve failed for " + host, error); }
        return host;
    }

    private final class Platform implements PlatformInterface {
        @Override public int openTun(TunOptions options) {
            Builder builder = new Builder().setSession("JhopanStore VPN").setMtu(options.getMTU());
            builder.addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0).addDnsServer("8.8.8.8").addDnsServer("8.8.4.4");
            try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
            tun = builder.establish();
            return tun == null ? -1 : tun.getFd();
        }
        @Override public void autoDetectInterfaceControl(int fd) { if (!protect(fd)) Log.w("VpnService", "protect failed: " + fd); }
        @Override public void clearDNSCache() {}
        @Override public void closeDefaultInterfaceMonitor(InterfaceUpdateListener value) { stopNetworkMonitor(); }
        @Override public int findConnectionOwner(int protocol, String source, int sourcePort, String destination, int destinationPort) { return 0; }
        @Override public NetworkInterfaceIterator getInterfaces() { return new InterfaceIterator(readInterfaces()); }
        @Override public boolean includeAllNetworks() { return false; }
        @Override public String packageNameByUid(int uid) { return ""; }
        @Override public WIFIState readWIFIState() { return null; }
        @Override public void sendNotification(io.github.sagernet.libbox.libbox.Notification value) {}
        @Override public void startDefaultInterfaceMonitor(InterfaceUpdateListener value) { startNetworkMonitor(value); }
        @Override public int uidByPackageName(String packageName) { return 0; }
        @Override public boolean underNetworkExtension() { return false; }
        @Override public boolean usePlatformAutoDetectInterfaceControl() { return true; }
        @Override public boolean useProcFS() { return false; }
        @Override public void writeLog(String message) { Log.i("libbox", message); }
    }

    private List<io.github.sagernet.libbox.libbox.NetworkInterface> readInterfaces() {
        try {
            List<io.github.sagernet.libbox.libbox.NetworkInterface> result = new ArrayList<>();
            Enumeration<java.net.NetworkInterface> source = java.net.NetworkInterface.getNetworkInterfaces();
            while (source != null && source.hasMoreElements()) {
                java.net.NetworkInterface item = source.nextElement();
                io.github.sagernet.libbox.libbox.NetworkInterface output = new io.github.sagernet.libbox.libbox.NetworkInterface();
                output.setIndex(item.getIndex()); output.setName(item.getName()); output.setMTU(item.getMTU());
                int flags = item.isUp() ? 0x41 : 0;
                if (item.isLoopback()) flags |= 0x8;
                if (item.isPointToPoint()) flags |= 0x10; else if (!item.isLoopback()) flags |= 0x2;
                if (item.supportsMulticast()) flags |= 0x1000;
                output.setFlags(flags); output.setType(interfaceType(item.getName())); output.setMetered(false);
                List<String> addresses = new ArrayList<>();
                for (java.net.InterfaceAddress address : item.getInterfaceAddresses()) {
                    String value = address.getAddress().getHostAddress();
                    if (value != null) addresses.add(value.split("%")[0] + "/" + address.getNetworkPrefixLength());
                }
                output.setAddresses(new Strings(addresses)); output.setDNSServer(new Strings(Collections.emptyList()));
                result.add(output);
            }
            return result;
        } catch (Exception error) { Log.w("VpnService", "get interfaces", error); return Collections.emptyList(); }
    }

    private static int interfaceType(String name) {
        if (name.startsWith("wlan") || name.startsWith("wl")) return 0;
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) return 1;
        if (name.startsWith("eth")) return 2;
        return 3;
    }

    private synchronized void startNetworkMonitor(InterfaceUpdateListener value) {
        stopNetworkMonitor();
        interfaceListener = value;
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { reportNetwork(network); scheduleHealthCheck(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { reportNetwork(network); scheduleHealthCheck(); }
        };
        try { manager.registerDefaultNetworkCallback(networkCallback); manager.getActiveNetwork(); Network current = manager.getActiveNetwork(); if (current != null) reportNetwork(current); }
        catch (Exception error) { Log.w("VpnService", "network callback", error); }
    }

    private synchronized void stopNetworkMonitor() {
        if (networkCallback != null) {
            try { ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        networkCallback = null; interfaceListener = null;
    }

    private void reportNetwork(Network network) {
        InterfaceUpdateListener target = interfaceListener;
        if (target == null) return;
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            LinkProperties link = manager.getLinkProperties(network);
            if (caps == null || link == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || link.getInterfaceName() == null) return;
            java.net.NetworkInterface item = java.net.NetworkInterface.getByName(link.getInterfaceName());
            boolean metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            target.updateDefaultInterface(link.getInterfaceName(), item == null ? 0 : item.getIndex(), metered, false);
        } catch (Exception error) { Log.w("VpnService", "report network", error); }
    }

    private static final class Strings implements StringIterator {
        private final List<String> values; private int position;
        Strings(List<String> values) { this.values = values; }
        @Override public boolean hasNext() { return position < values.size(); }
        @Override public int len() { return values.size(); }
        @Override public String next() { return values.get(position++); }
    }
    private static final class InterfaceIterator implements NetworkInterfaceIterator {
        private final List<io.github.sagernet.libbox.libbox.NetworkInterface> values; private int position;
        InterfaceIterator(List<io.github.sagernet.libbox.libbox.NetworkInterface> values) { this.values = values; }
        @Override public boolean hasNext() { return position < values.size(); }
        @Override public io.github.sagernet.libbox.libbox.NetworkInterface next() { return values.get(position++); }
    }

    private SharedPreferences statusPrefs() { return getSharedPreferences(STATUS_PREFS, MODE_PRIVATE); }

    private void setState(String value) {
        statusPrefs().edit().putString(KEY_STATE, value).putLong(KEY_LAST_SEEN, running ? System.currentTimeMillis() : 0).apply();
        state(value);
    }

    private void writeHeartbeat() {
        if (running) statusPrefs().edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply();
    }

    private void scheduleHealthCheck() {
        heartbeat.schedule(this::checkTunnel, 2, TimeUnit.SECONDS);
    }

    private void checkTunnel() {
        synchronized (lifecycleLock) { if (!running || connecting) return; }
        boolean healthy = false;
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", SingboxConfig.PROXY_PORT));
            connection = (HttpURLConnection) new URL(HEALTH_URL).openConnection(proxy);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            int code = connection.getResponseCode();
            healthy = code == HttpURLConnection.HTTP_NO_CONTENT || code == HttpURLConnection.HTTP_OK;
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
        if (healthy) {
            synchronized (lifecycleLock) { failedProbes = 0; }
            statusPrefs().edit().putLong(KEY_LAST_PROBE, System.currentTimeMillis()).apply();
            return;
        }
        boolean reconnect;
        synchronized (lifecycleLock) { reconnect = ++failedProbes >= 2 && running && !connecting; if (reconnect) { running = false; connecting = true; failedProbes = 0; } }
        if (reconnect) reconnectTunnel();
    }

    private void reconnectTunnel() {
        String uri = statusPrefs().getString(KEY_URI, null);
        if (uri == null) { disconnect(); return; }
        updateNotification("Reconnecting…");
        setState("Reconnecting…");
        worker.execute(() -> connect(uri));
    }

    private void closeCore() {
        stopNetworkMonitor();
        try { if (service != null) service.close(); } catch (Exception ignored) {}
        service = null;
        try { if (tun != null) tun.close(); } catch (IOException ignored) {}
        tun = null;
    }
    private void disconnect() {
        synchronized (lifecycleLock) { connecting = false; running = false; }
        closeCore();
        statusPrefs().edit().remove(KEY_URI).putString(KEY_STATE, "Disconnected").putLong(KEY_LAST_SEEN, 0).putLong(KEY_LAST_PROBE, 0).apply();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        state("Disconnected");
        stopSelf();
    }
    @Override public void onDestroy() { synchronized (lifecycleLock) { running = false; connecting = false; } closeCore(); heartbeat.shutdownNow(); worker.shutdownNow(); statusPrefs().edit().putString(KEY_STATE, "Disconnected").putLong(KEY_LAST_SEEN, 0).putLong(KEY_LAST_PROBE, 0).apply(); super.onDestroy(); }
    @Override public void onRevoke() { disconnect(); super.onRevoke(); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "VPN aktif", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Status koneksi VPN");
            channel.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
    private Notification notification(String text) {
        PendingIntent content = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VpnService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle("JhopanStore VPN").setContentText(text).setSmallIcon(R.drawable.ic_vpn_key).setContentIntent(content).addAction(new Notification.Action.Builder(null, "Disconnect", stop).build()).setOngoing(true).build();
    }
    private void updateNotification(String text) { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(text)); }
}
