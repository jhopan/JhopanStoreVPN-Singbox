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
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import com.jhopanstore.litevpn.core.Installation;
import com.jhopanstore.litevpn.core.License;
import com.jhopanstore.litevpn.core.LicenseCodec;
import com.jhopanstore.litevpn.core.VlessConfig;
import com.jhopanstore.litevpn.core.VlessParser;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends AppCompatActivity {
    private static final int VPN_PERMISSION = 10;
    private static final int IMPORT_FILE = 11;
    private static final int EXPORT_FILE = 12;
    private static final int EXPORT_LICENSE = 14;
    private static final String JVS_MIME = "application/x-jhopanstore-vpn";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private EditText address, uuid, path, sni, host;
    private TextView status, traffic;
    private String hwid;
    private android.view.View configFields;
    private android.widget.TextView lockBanner;
    private License license;
    private Button connect;
    private boolean connected;
    private boolean showTraffic = true;
    private long totalRx, totalTx, lastRx, lastTx, lastSample;
    private boolean hasBaseline;
    private int uid;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("JhopanStore VPN");
        setSupportActionBar(toolbar);
        prefs = getSharedPreferences("vpn", MODE_PRIVATE);
        address = findViewById(R.id.address); uuid = findViewById(R.id.uuid); path = findViewById(R.id.path); sni = findViewById(R.id.sni); host = findViewById(R.id.host);
        status = findViewById(R.id.status); traffic = findViewById(R.id.traffic); connect = findViewById(R.id.connect);
        configFields = findViewById(R.id.configFields); lockBanner = findViewById(R.id.lockBanner);
        load();
        hwid = Installation.id(this);
        loadLicense();
        applyLockState();
        uid = android.os.Process.myUid();
        totalRx = prefs.getLong("traffic_total_rx", 0);
        totalTx = prefs.getLong("traffic_total_tx", 0);
        showTraffic = prefs.getBoolean("show_traffic", true);
        traffic.setVisibility(showTraffic ? android.view.View.VISIBLE : android.view.View.GONE);
        connect.setOnClickListener(v -> { if (connected) disconnect(); else requestConnect(); });

        VpnService.setListener(value -> runOnUiThread(() -> onVpnState(value)));
        requestNotificationPermission();
        batteryGuard();
        handleSharedFile(getIntent());
    }

    private void batteryGuard() {
        if (prefs.getBoolean("battery_guard_asked", false)) return;
        prefs.edit().putBoolean("battery_guard_asked", true).apply();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        new AlertDialog.Builder(this)
            .setTitle("Mode 24/7")
            .setMessage("Agar VPN tetap hidup saat layar mati, matikan penghemat daya (battery optimization) untuk JhopanStore VPN dan aktifkan Autostart di pengaturan." )
            .setPositiveButton("Matikan penghemat daya", (d, w) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (Exception error) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            })
            .setNegativeButton("Nanti", null)
            .show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedFile(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        SharedPreferences vpnStatus = getSharedPreferences("vpn_status", MODE_PRIVATE);
        String value = vpnStatus.getString("state", "Disconnected");
        long lastSeen = vpnStatus.getLong("last_seen", 0);
        if ("Connected".equals(value) && System.currentTimeMillis() - lastSeen > 35_000) value = "Disconnected";
        onVpnState(value);
        handler.post(trafficTask);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(trafficTask);
        saveTotals();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        menu.findItem(R.id.action_traffic).setChecked(showTraffic);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_import_clipboard) { importText(clipboard()); return true; }
        if (id == R.id.action_import_file) { openImportFile(); return true; }
        if (id == R.id.action_export_clipboard) { copy(exportLink()); return true; }
        if (id == R.id.action_export_file) { createExportFile(); return true; }
        if (id == R.id.action_export_license) { showExportLicenseDialog(); return true; }
        if (id == R.id.action_hwid) { copy(hwid); return true; }
        if (id == R.id.action_about) { showAbout(); return true; }
        if (id == R.id.action_traffic) { toggleTraffic(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showAbout() {
        try {
            android.view.View view = getLayoutInflater().inflate(R.layout.dialog_about, null);
            TextView version = view.findViewById(R.id.about_version);
            try { version.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName); }
            catch (Exception ignored) {}
            androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(view)
                .create();
            dialog.setOnShowListener(d -> {
                try { dialog.getWindow().setBackgroundDrawableResource(android.graphics.Color.parseColor("#1A1A1A")); } catch (Exception ignored) {}
            });
            view.findViewById(R.id.about_telegram).setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/jhopan_05"))); }
                catch (Exception error) { show("No browser"); }
            });
            view.findViewById(R.id.about_website).setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://jhopanstore.my.id"))); }
                catch (Exception error) { show("No browser"); }
            });
            dialog.show();
        } catch (Exception error) {
            show("About: " + error.getClass().getSimpleName());
        }
    }

    private void toggleTraffic() {
        showTraffic = !showTraffic;
        prefs.edit().putBoolean("show_traffic", showTraffic).apply();
        traffic.setVisibility(showTraffic ? android.view.View.VISIBLE : android.view.View.GONE);
        hasBaseline = false;
        invalidateOptionsMenu();
        show(showTraffic ? "Traffic meter on" : "Traffic meter off");
    }

    private void openImportFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(JVS_MIME).addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, IMPORT_FILE); }
        catch (Exception error) { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE), IMPORT_FILE); }
    }

    private void createExportFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(JVS_MIME).putExtra(Intent.EXTRA_TITLE, "jhopanstore-vpn.jvs");
        startActivityForResult(intent, EXPORT_FILE);
    }

    private void handleSharedFile(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) readImport(intent.getData());
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 13);
        }
    }

    private void requestConnect() {
        try {
            String uri = activeUri();
            VlessParser.parse(uri);
            Intent intent = android.net.VpnService.prepare(this);
            if (intent == null) connect(uri); else startActivityForResult(intent, VPN_PERMISSION);
        } catch (Exception error) { show(error.getMessage()); }
    }

    private String activeUri() {
        if (license != null) {
            if (LicenseCodec.expired(license)) throw new IllegalStateException("Lisensi kedaluwarsa");
            return license.vless;
        }
        return exportLink();
    }

    private void applyLockState() {
        boolean locked = license != null && license.lock;
        configFields.setVisibility(locked ? android.view.View.GONE : android.view.View.VISIBLE);
        lockBanner.setVisibility(locked ? android.view.View.VISIBLE : android.view.View.GONE);
        if (locked) {
            String meta = "Terpasang via lisensi • HWID " + hwid;
            if (license.expiry > 0) meta += "\nBerlaku s.d. " + SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM).format(new Date(license.expiry));
            lockBanner.setText("🔒 " + (license.name.isEmpty() ? "JhopanStore VPN" : license.name) + "\n" + meta);
        }
    }

    private void loadLicense() {
        String stored = prefs.getString("license_payload", null);
        if (stored == null) { license = null; return; }
        try {
            license = LicenseCodec.decode(stored, hwid);
            if (LicenseCodec.expired(license)) { show("Lisensi kedaluwarsa"); prefs.edit().remove("license_payload").apply(); license = null; }
        } catch (Exception error) {
            prefs.edit().remove("license_payload").apply();
            license = null;
        }
    }

    private void showExportLicenseDialog() {
        try {
            VlessParser.parse(exportLink()); // config must be valid before selling it
        } catch (Exception error) { show(error.getMessage()); return; }
        android.view.View form = getLayoutInflater().inflate(R.layout.dialog_export_license, null);
        new AlertDialog.Builder(this)
            .setTitle("Export Locked Config")
            .setView(form)
            .setPositiveButton("Buat File", (d, w) -> {
                String name = ((EditText) form.findViewById(R.id.lic_name)).getText().toString().trim();
                String customerHwid = ((EditText) form.findViewById(R.id.lic_hwid)).getText().toString().trim().toUpperCase(Locale.US);
                String expiryText = ((EditText) form.findViewById(R.id.lic_expiry)).getText().toString().trim();
                boolean lock = ((android.widget.CheckBox) form.findViewById(R.id.lic_lock)).isChecked();
                if (customerHwid.length() != 24) { show("HWID harus 24 karakter"); return; }
                long expiry = 0;
                if (!expiryText.isEmpty()) {
                    try {
                        SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
                        format.setLenient(false);
                        expiry = format.parse(expiryText).getTime() + 86_400_000L; // end of that day
                    } catch (Exception error) { show("Tanggal salah (dd/mm/yyyy)"); return; }
                }
                try {
                    String payload = LicenseCodec.encode(new License(exportLink(), name, customerHwid, lock, expiry));
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(JVS_MIME).putExtra(Intent.EXTRA_TITLE, "jhopanstore-locked.jvs");
                    pendingExportPayload = payload;
                    startActivityForResult(intent, EXPORT_LICENSE);
                } catch (Exception error) { show("Export gagal: " + error.getClass().getSimpleName()); }
            })
            .setNegativeButton("Batal", null)
            .show();
    }
    private String pendingExportPayload;

    private void importText(String text) {
        String value = text == null ? "" : text.trim();
        if (LicenseCodec.isEncoded(value)) { importLicense(value); return; }
        try {
            VlessConfig config = VlessParser.parse(value);
            address.setText(config.address + ":" + config.port); uuid.setText(config.uuid); path.setText(config.path); sni.setText(config.sni); host.setText(config.host);
            save(); show("VLESS imported");
        } catch (Exception error) { show(error.getMessage()); }
    }

    private void importLicense(String payload) {
        try {
            License incoming = LicenseCodec.decode(payload, hwid);
            if (LicenseCodec.expired(incoming)) { show("Lisensi kedaluwarsa"); return; }
            prefs.edit().putString("license_payload", payload).apply();
            license = incoming;
            applyLockState();
            show("Lisensi terpasang" + (incoming.lock ? " (config terkunci)" : ""));
        } catch (Exception error) {
            String message = error instanceof javax.crypto.AEADBadTagException
                ? "File bukan untuk HWID device ini"
                : "Lisensi gagal: " + error.getMessage();
            show(message);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == VPN_PERMISSION && result == RESULT_OK) connect(activeUri());
        if (request == IMPORT_FILE && result == RESULT_OK && data != null && data.getData() != null) readImport(data.getData());
        if (request == EXPORT_FILE && result == RESULT_OK && data != null && data.getData() != null) writeExport(data.getData());
        if (request == EXPORT_LICENSE && result == RESULT_OK && data != null && data.getData() != null) writeLicense(data.getData());
    }

    private void connect(String uri) { save(); VpnService.start(this, uri); }
    private void disconnect() { VpnService.stop(this); }
    private void onVpnState(String value) {
        status.setText(value);
        connected = "Connected".equals(value) || "Connecting…".equals(value) || "Checking internet…".equals(value) || "Reconnecting…".equals(value);
        connect.setText(connected ? "DISCONNECT" : "CONNECT");
        if (!connected) resetTraffic();
    }

    private final Runnable trafficTask = new Runnable() {
        @Override public void run() {
            if (connected && showTraffic) updateTraffic();
            handler.postDelayed(this, 2000);
        }
    };

    private void updateTraffic() {
        long rx = android.net.TrafficStats.getUidRxBytes(uid);
        long tx = android.net.TrafficStats.getUidTxBytes(uid);
        if (rx < 0 || tx < 0) { traffic.setText("Traffic: unavailable"); return; }
        long now = System.currentTimeMillis();
        if (!hasBaseline) { lastRx = rx; lastTx = tx; lastSample = now; hasBaseline = true; }
        long dRx = rx - lastRx, dTx = tx - lastTx;
        if (dRx < 0 || dTx < 0) { lastRx = rx; lastTx = tx; lastSample = now; return; }
        totalRx += dRx; totalTx += dTx;
        long elapsed = Math.max(1, now - lastSample);
        long downRate = dRx * 1000 / elapsed, upRate = dTx * 1000 / elapsed;
        traffic.setText("↓ " + bytes(totalRx) + " (" + bytes(downRate) + "/s)   ↑ " + bytes(totalTx) + " (" + bytes(upRate) + "/s)");
        lastRx = rx; lastTx = tx; lastSample = now;
    }

    private void resetTraffic() { hasBaseline = false; if (showTraffic) traffic.setText("↓ " + bytes(totalRx) + "   ↑ " + bytes(totalTx)); }
    private void saveTotals() { prefs.edit().putLong("traffic_total_rx", totalRx).putLong("traffic_total_tx", totalTx).apply(); }
    private static String bytes(long value) { return value < 1024 ? value + " B" : value < 1048576 ? String.format("%.1f KB", value / 1024d) : String.format("%.2f MB", value / 1048576d); }

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

    private String clipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        return manager.hasPrimaryClip() ? String.valueOf(manager.getPrimaryClip().getItemAt(0).coerceToText(this)) : "";
    }

    private void copy(String value) { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("JhopanStore VPN", value)); show("Copied"); }

    private void readImport(Uri uri) {
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
            importText(reader.readLine());
        } catch (Exception error) { show("Import failed"); }
    }

    private void writeExport(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            output.write(exportLink().getBytes(StandardCharsets.UTF_8)); show("Exported");
        } catch (Exception error) { show("Export failed"); }
    }

    private void writeLicense(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            output.write(pendingExportPayload.getBytes(StandardCharsets.UTF_8)); show("Locked config exported");
        } catch (Exception error) { show("Export failed"); }
        pendingExportPayload = null;
    }

    private void show(String value) { Toast.makeText(this, value == null ? "Error" : value, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy() { VpnService.setListener(null); handler.removeCallbacks(trafficTask); saveTotals(); super.onDestroy(); }
}
