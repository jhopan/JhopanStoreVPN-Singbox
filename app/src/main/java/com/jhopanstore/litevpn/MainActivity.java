package com.jhopanstore.litevpn;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.jhopanstore.litevpn.core.VlessConfig;
import com.jhopanstore.litevpn.core.VlessParser;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

public final class MainActivity extends AppCompatActivity {
    private static final int VPN_PERMISSION = 10;
    private static final int IMPORT_FILE = 11;
    private static final int EXPORT_FILE = 12;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private EditText address, uuid, path, sni, host;
    private TextView status, traffic, hwid;
    private Button connect;
    private boolean connected;
    private long startRx = -1, startTx = -1, lastRx, lastTx, lastSample;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("vpn", MODE_PRIVATE);
        address = findViewById(R.id.address); uuid = findViewById(R.id.uuid); path = findViewById(R.id.path); sni = findViewById(R.id.sni); host = findViewById(R.id.host);
        status = findViewById(R.id.status); traffic = findViewById(R.id.traffic); hwid = findViewById(R.id.hwid); connect = findViewById(R.id.connect);
        load();
        hwid.setText("HWID: " + installationHwid());
        hwid.setOnClickListener(v -> copy(hwid.getText().toString()));
        connect.setOnClickListener(v -> { if (connected) disconnect(); else requestConnect(); });
        findViewById(R.id.importClipboard).setOnClickListener(v -> importText(clipboard()));
        findViewById(R.id.exportClipboard).setOnClickListener(v -> copy(exportLink()));

        VpnService.setListener(value -> runOnUiThread(() -> onVpnState(value)));
        requestNotificationPermission();
        handler.post(trafficTask);
    }

    @Override protected void onResume() {
        super.onResume();
        SharedPreferences vpnStatus = getSharedPreferences("vpn_status", MODE_PRIVATE);
        String value = vpnStatus.getString("state", "Disconnected");
        long lastSeen = vpnStatus.getLong("last_seen", 0);
        if ("Connected".equals(value) && System.currentTimeMillis() - lastSeen > 10_000) value = "Disconnected";
        onVpnState(value);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 13);
        }
    }

    private void requestConnect() {
        try {
            VlessParser.parse(exportLink());
            Intent intent = android.net.VpnService.prepare(this);
            if (intent == null) connect(); else startActivityForResult(intent, VPN_PERMISSION);
        } catch (Exception error) { show(error.getMessage()); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == VPN_PERMISSION && result == RESULT_OK) connect();
        if (request == IMPORT_FILE && result == RESULT_OK && data != null && data.getData() != null) readImport(data.getData());
        if (request == EXPORT_FILE && result == RESULT_OK && data != null && data.getData() != null) writeExport(data.getData());
    }
    private void connect() { save(); VpnService.start(this, exportLink()); }
    private void disconnect() { VpnService.stop(this); }
    private void onVpnState(String value) {
        status.setText(value);
        connected = "Connected".equals(value) || "Connecting…".equals(value) || "Reconnecting…".equals(value);
        connect.setText(connected ? "DISCONNECT" : "CONNECT");
        if (!connected) resetTraffic();
    }

    private final Runnable trafficTask = new Runnable() {
        @Override public void run() {
            if (connected) updateTraffic();
            handler.postDelayed(this, 1000);
        }
    };
    private void updateTraffic() {
        long rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long tx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
        if (rx < 0 || tx < 0) { traffic.setText("Traffic: unavailable"); return; }
        long now = System.currentTimeMillis();
        if (startRx < 0) { startRx = lastRx = rx; startTx = lastTx = tx; lastSample = now; }
        long elapsed = Math.max(1, now - lastSample);
        long downRate = (rx - lastRx) * 1000 / elapsed, upRate = (tx - lastTx) * 1000 / elapsed;
        traffic.setText("↓ " + bytes(rx - startRx) + " (" + bytes(downRate) + "/s)   ↑ " + bytes(tx - startTx) + " (" + bytes(upRate) + "/s)");
        lastRx = rx; lastTx = tx; lastSample = now;
    }
    private void resetTraffic() { startRx = startTx = -1; traffic.setText("Traffic: -"); }
    private static String bytes(long value) { return value < 1024 ? value + " B" : value < 1048576 ? String.format("%.1f KB", value / 1024d) : String.format("%.2f MB", value / 1048576d); }

    private void importText(String text) {
        try {
            VlessConfig config = VlessParser.parse(text);
            address.setText(config.address + ":" + config.port); uuid.setText(config.uuid); path.setText(config.path); sni.setText(config.sni); host.setText(config.host);
            save(); show("VLESS imported");
        } catch (Exception error) { show(error.getMessage()); }
    }
    private String exportLink() {
        String raw = address.getText().toString().trim(); int divider = raw.lastIndexOf(':');
        String server = divider > 0 ? raw.substring(0, divider) : raw;
        int port = divider > 0 ? parsePort(raw.substring(divider + 1)) : 443;
        String serverName = text(sni); if (serverName.isEmpty()) serverName = server;
        String wsHost = text(host); if (wsHost.isEmpty()) wsHost = serverName;
        return VlessParser.export(new VlessConfig(server, port, text(uuid), text(path).isEmpty() ? "/" : text(path), serverName, wsHost, true));
    }
    private static int parsePort(String value) { try { int port = Integer.parseInt(value); return port > 0 && port < 65536 ? port : 443; } catch (Exception ignored) { return 443; } }
    private static String text(EditText field) { return field.getText().toString().trim(); }
    private void load() { address.setText(prefs.getString("address", "")); uuid.setText(prefs.getString("uuid", "")); path.setText(prefs.getString("path", "/")); sni.setText(prefs.getString("sni", "")); host.setText(prefs.getString("host", "")); }
    private void save() { prefs.edit().putString("address", text(address)).putString("uuid", text(uuid)).putString("path", text(path)).putString("sni", text(sni)).putString("host", text(host)).apply(); }
    private String installationHwid() {
        String id = prefs.getString("installation_id", null);
        if (id == null) { id = UUID.randomUUID().toString(); prefs.edit().putString("installation_id", id).apply(); }
        try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte part : hash) out.append(String.format("%02X", part)); return out.substring(0, 24); } catch (Exception error) { return id; }
    }
    private String clipboard() { ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE); return manager.hasPrimaryClip() ? String.valueOf(manager.getPrimaryClip().getItemAt(0).getText()) : ""; }
    private void copy(String value) { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("JhopanStore VPN", value)); show("Copied"); }
    private void readImport(Uri uri) { try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(getContentResolver().openInputStream(uri)))) { importText(reader.readLine()); } catch (Exception error) { show("Import failed"); } }
    private void writeExport(Uri uri) { try (OutputStream output = getContentResolver().openOutputStream(uri)) { output.write(exportLink().getBytes(StandardCharsets.UTF_8)); show("Exported"); } catch (Exception error) { show("Export failed"); } }
    private void show(String value) { Toast.makeText(this, value == null ? "Error" : value, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy() { VpnService.setListener(null); handler.removeCallbacks(trafficTask); super.onDestroy(); }
}
