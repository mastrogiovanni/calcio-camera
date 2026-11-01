package it.calciocamera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.net.Inet4Address;
import java.util.Locale;

public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener, CameraController.Listener {
    private static final int BG = 0xFF0D1110, PANEL = 0xFF171D19, MUTED = 0xFF9BA79E;
    private static final int INK = 0xFFF1F5EF, GREEN = 0xFFB8F568, RED = 0xFFFF7979;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextureView texture;
    private TextView status, stats, network, source, hint, empty;
    private Button transmit, lens, quality, frameRate, flash;
    private EditText name;
    private CameraController camera;
    private SharedPreferences preferences;
    private boolean resumed, cameraReady, streaming, pending, front, torchOn, runtimePresent;
    private int height = 720, fps = 30, bufferWidth, bufferHeight, rotation, displayDegrees;
    private long startedAt, lastStats, pendingAt;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("camera", MODE_PRIVATE);
        height = preferences.getInt("height", 720);
        fps = preferences.getInt("fps", 30);
        front = preferences.getBoolean("front", false);
        runtimePresent = new File(getApplicationInfo().nativeLibraryDir, "libndi.so").isFile();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        camera = new CameraController(this, this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(12) + bars.bottom);
            } else {
                v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout header = row();
        TextView title = text("CALCIO / CAMERA", 19, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(.08f);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1));
        status = text("●  ANTEPRIMA", 12, MUTED);
        status.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(status);
        Button help = button("Collega OBS  ↗", PANEL, INK);
        help.setOnClickListener(v -> showGuide());
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(dp(155), dp(44));
        helpParams.setMarginStart(dp(16));
        header.addView(help, helpParams);
        root.addView(header);

        LinearLayout body = row();
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyParams.topMargin = dp(12);
        root.addView(body, bodyParams);
        FrameLayout viewer = new FrameLayout(this);
        viewer.setBackground(round(0xFF050806, 16));
        viewer.setClipToOutline(true);
        body.addView(viewer, new LinearLayout.LayoutParams(0, -1, 1));
        texture = new TextureView(this);
        texture.setSurfaceTextureListener(this);
        viewer.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        empty = text("La tua inquadratura, in diretta.\nConsenti l’accesso alla fotocamera per iniziare.", 16, MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), 0, dp(24), 0);
        empty.setOnClickListener(v -> requestCamera());
        viewer.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = text("  VIDEO / NDI®  ", 11, GREEN);
        badge.setBackground(round(0xDD18251B, 5));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, dp(28), Gravity.TOP | Gravity.START);
        badgeParams.setMargins(dp(16), dp(16), 0, 0);
        viewer.addView(badge, badgeParams);
        stats = text("IN ATTESA DELLA FOTOCAMERA", 11, INK);
        stats.setBackground(round(0xCC101710, 6));
        stats.setPadding(dp(10), dp(6), dp(10), dp(6));
        FrameLayout.LayoutParams statsParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        statsParams.setMargins(dp(16), 0, dp(16), dp(16));
        viewer.addView(stats, statsParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(dp(286), -1);
        scrollParams.setMarginStart(dp(18));
        body.addView(scroll, scrollParams);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(controls);
        controls.addView(text("LA TUA SORGENTE", 11, MUTED));
        name = new EditText(this);
        name.setSingleLine(true);
        name.setTextColor(INK);
        name.setTextSize(19);
        name.setSelectAllOnFocus(true);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        name.setText(preferences.getString("name", "Calcio Camera"));
        controls.addView(name, new LinearLayout.LayoutParams(-1, dp(52)));
        source = text("Video locale • nessun audio", 12, MUTED);
        controls.addView(source);
        network = text("Ricerca rete locale…", 12, MUTED);
        network.setPadding(0, dp(12), 0, dp(10));
        controls.addView(network);

        LinearLayout formats = row();
        quality = button(height + "p", PANEL, INK);
        frameRate = button(fps + " fps", PANEL, INK);
        formats.addView(quality, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams fpsParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        fpsParams.setMarginStart(dp(8));
        formats.addView(frameRate, fpsParams);
        controls.addView(formats);
        quality.setOnClickListener(v -> { height = height == 720 ? 1080 : 720; quality.setText(height + "p"); save(); openCamera(); });
        frameRate.setOnClickListener(v -> { fps = fps == 30 ? 15 : 30; frameRate.setText(fps + " fps"); save(); openCamera(); });
        lens = button(front ? "↺  Fotocamera anteriore" : "↺  Fotocamera posteriore", PANEL, INK);
        addControl(controls, lens);
        lens.setOnClickListener(v -> { front = !front; lens.setText(front ? "↺  Fotocamera anteriore" : "↺  Fotocamera posteriore"); save(); openCamera(); });
        flash = button("Torcia spenta", PANEL, INK);
        flash.setEnabled(false);
        addControl(controls, flash);
        flash.setOnClickListener(v -> { torchOn = !torchOn; flash.setText(torchOn ? "Torcia accesa" : "Torcia spenta"); camera.toggleTorch(); });
        transmit = button(runtimePresent ? "Avvia trasmissione" : "Configura runtime NDI", GREEN, BG);
        transmit.setEnabled(false);
        addControl(controls, transmit);
        transmit.setOnClickListener(v -> toggleTransmission());
        hint = text(runtimePresent ? "Pronta per OBS. Stessa rete locale su entrambi i dispositivi." : "Runtime NDI Android da importare. L’anteprima funziona già.", 12, MUTED);
        hint.setPadding(0, dp(12), 0, dp(12));
        controls.addView(hint);
        TextView ndiLink = text("Tecnologia NDI ↗", 11, MUTED);
        ndiLink.setPadding(0, dp(8), 0, dp(8));
        ndiLink.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://ndi.video/"))));
        controls.addView(ndiLink);
        controls.addView(text("NDI® is a registered trademark of Vizrt NDI AB", 9, MUTED));
        setContentView(root);
    }

    private void toggleTransmission() {
        if (streaming || pending) { camera.stop(); return; }
        if (!runtimePresent) { showRuntimeGuide(); return; }
        String clean = name.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
        if (clean.isEmpty()) { name.setError("Inserisci un nome per la sorgente"); return; }
        if (!hasLan()) { onError("Connetti il telefono alla rete Wi-Fi o Ethernet del computer con OBS."); return; }
        name.setText(clean);
        save();
        pending = true;
        pendingAt = SystemClock.elapsedRealtime();
        status.setText("●  AVVIO…");
        transmit.setText("Annulla avvio");
        setControls(false);
        hint.setText("Pubblicazione della sorgente sulla rete locale…");
        camera.start(clean);
    }

    private void setControls(boolean enabled) {
        name.setEnabled(enabled);
        lens.setEnabled(enabled);
        quality.setEnabled(enabled);
        frameRate.setEnabled(enabled);
    }

    @Override public void onReady(int w, int h, int r, int actualFps, boolean torch) {
        if (!resumed) return;
        cameraReady = true;
        bufferWidth = w; bufferHeight = h; rotation = r;
        transformPreview();
        empty.setVisibility(View.GONE);
        transmit.setEnabled(true);
        flash.setEnabled(torch);
        torchOn = false;
        flash.setText(torch ? "Torcia spenta" : "Torcia non disponibile");
        boolean swap = r == 90 || r == 270;
        source.setText((swap ? h + " × " + w : w + " × " + h) + " • fino a " + actualFps + " fps • solo video");
        stats.setText("ANTEPRIMA LOCALE");
    }

    @Override public void onStarted(String sourceName) {
        if (!resumed) return;
        pending = false;
        streaming = true;
        startedAt = lastStats = SystemClock.elapsedRealtime();
        status.setText("●  IN TRASMISSIONE");
        status.setTextColor(GREEN);
        transmit.setText("■  Interrompi trasmissione");
        transmit.setBackground(round(0xFF472826, 10));
        transmit.setTextColor(0xFFFFB6AC);
        source.setText(sourceName);
        hint.setText("In OBS: aggiungi una sorgente NDI e seleziona questo nome.");
    }

    @Override public void onRotationChanged(int degrees) {
        rotation = degrees;
        if (resumed) transformPreview();
    }

    @Override public void onStats(float measuredFps, int receivers, long sent, boolean transmitting) {
        if (!resumed) return;
        lastStats = SystemClock.elapsedRealtime();
        String display = String.format(Locale.ITALY, "%.1f FPS", measuredFps);
        if (transmitting) {
            long seconds = (lastStats - startedAt) / 1000;
            display += String.format(Locale.ITALY, "  /  %d RICEVITORI  /  %02d:%02d  /  %d FRAME", receivers, seconds / 60, seconds % 60, sent);
            hint.setText(receivers > 0 ? "Sorgente ricevuta da " + receivers + " dispositivo/i NDI." : "Sorgente pubblicata. In attesa di un ricevitore: apri la sorgente NDI in OBS.");
        } else display += "  /  ANTEPRIMA LOCALE";
        stats.setText(display);
    }

    @Override public void onStopped() {
        streaming = pending = false;
        setControls(true);
        status.setText("●  ANTEPRIMA");
        status.setTextColor(MUTED);
        transmit.setText(runtimePresent ? "Avvia trasmissione" : "Configura runtime NDI");
        transmit.setBackground(round(GREEN, 10));
        transmit.setTextColor(BG);
        hint.setText(runtimePresent ? "La trasmissione è ferma. Premi Avvia per pubblicare la sorgente." : "Runtime NDI Android da importare. L’anteprima funziona già.");
    }

    @Override public void onError(String message) {
        if (!resumed) return;
        streaming = pending = false;
        setControls(true);
        status.setText("●  ATTENZIONE");
        status.setTextColor(RED);
        transmit.setText(runtimePresent ? "Avvia trasmissione" : "Configura runtime NDI");
        transmit.setBackground(round(GREEN, 10));
        transmit.setTextColor(BG);
        hint.setText(message);
        if (!cameraReady) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(message + "\nTocca per riprovare.");
        }
    }

    @Override public void onCameraError(String message) {
        cameraReady = false;
        transmit.setEnabled(false);
        flash.setEnabled(false);
        onError(message);
    }

    private void openCamera() {
        if (!resumed || !texture.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        cameraReady = false;
        transmit.setEnabled(false);
        flash.setEnabled(false);
        displayDegrees = getDisplayRotation();
        camera.open(texture.getSurfaceTexture(), front, height, fps, displayDegrees);
    }

    @SuppressWarnings("deprecation")
    private int getDisplayRotation() {
        int r = getWindowManager().getDefaultDisplay().getRotation();
        return r == Surface.ROTATION_90 ? 90 : r == Surface.ROTATION_180 ? 180 : r == Surface.ROTATION_270 ? 270 : 0;
    }

    private void transformPreview() {
        if (bufferWidth == 0 || texture.getWidth() == 0) return;
        float vw = texture.getWidth(), vh = texture.getHeight();
        boolean swap = rotation == 90 || rotation == 270;
        float ow = swap ? bufferHeight : bufferWidth, oh = swap ? bufferWidth : bufferHeight;
        float scale = Math.max(vw / ow, vh / oh);
        float left = (vw - ow * scale) / 2, top = (vh - oh * scale) / 2;
        float[] dst = new float[8];
        float[] corners = {0, 0, bufferWidth, 0, bufferWidth, bufferHeight, 0, bufferHeight};
        for (int i = 0; i < 8; i += 2) {
            float x = corners[i], y = corners[i + 1], dx, dy;
            switch (rotation) {
                case 90: dx = bufferHeight - y; dy = x; break;
                case 180: dx = bufferWidth - x; dy = bufferHeight - y; break;
                case 270: dx = y; dy = bufferWidth - x; break;
                default: dx = x; dy = y;
            }
            dst[i] = left + dx * scale;
            dst[i + 1] = top + dy * scale;
        }
        Matrix matrix = new Matrix();
        matrix.setPolyToPoly(new float[]{0, 0, vw, 0, vw, vh, 0, vh}, 0, dst, 0, 4);
        texture.setTransform(matrix);
    }

    private boolean hasLan() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        for (Network n : manager.getAllNetworks()) {
            NetworkCapabilities c = manager.getNetworkCapabilities(n);
            if (c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return true;
        }
        return false;
    }

    private void updateNetwork() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        for (Network n : manager.getAllNetworks()) {
            NetworkCapabilities c = manager.getNetworkCapabilities(n);
            if (c == null || (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties properties = manager.getLinkProperties(n);
            if (properties == null) continue;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address && !address.getAddress().isLoopbackAddress()) {
                    network.setText("●  LAN  /  " + address.getAddress().getHostAddress());
                    network.setTextColor(GREEN);
                    return;
                }
            }
        }
        network.setText("○  Connetti alla rete locale di OBS");
        network.setTextColor(MUTED);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateNetwork();
            long now = SystemClock.elapsedRealtime();
            if ((pending && now - pendingAt > 8000) || (streaming && now - lastStats > 5000)) {
                camera.stop();
                ui.postDelayed(() -> onError("Nessun frame dalla fotocamera. Interrompi e riapri l’app per riprovare."), 100);
            }
            if (resumed) ui.postDelayed(this, 1000);
        }
    };

    private void save() {
        preferences.edit().putString("name", name.getText().toString().trim()).putInt("height", height)
            .putInt("fps", fps).putBoolean("front", front).apply();
    }

    private void requestCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, 10);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 10) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openCamera();
        else {
            empty.setText("Accesso alla fotocamera necessario.\nTocca per aprire le impostazioni.");
            empty.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        }
    }

    private void showGuide() {
        new AlertDialog.Builder(this).setTitle("Dal telefono a OBS")
            .setMessage("1. Collega telefono e computer alla stessa rete locale. Preferisci Wi-Fi 5/6 GHz o Ethernet USB.\n\n2. Installa DistroAV e il runtime NDI richiesto sul computer, poi riavvia OBS.\n\n3. Avvia la trasmissione su questo telefono.\n\n4. In OBS: Fonti → + → NDI Source. Seleziona il nome mostrato nell’app.\n\nSe la sorgente non compare, controlla firewall, VPN e isolamento client del router. Tieni l’app aperta: passando in background la trasmissione si interrompe.\n\nQuesta versione trasmette soltanto video. Il numero di ricevitori indica connessioni NDI, non necessariamente OBS.")
            .setPositiveButton("Apri DistroAV", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DistroAV/DistroAV"))))
            .setNegativeButton("Chiudi", null).show();
    }
    private void showRuntimeGuide() {
        new AlertDialog.Builder(this).setTitle("Completa l’integrazione NDI")
            .setMessage("Questa build contiene l’anteprima e l’integrazione NDI, ma manca il runtime proprietario Android.\n\nSul computer:\n1. Scarica l’SDK NDI ufficiale con supporto Android, secondo i termini applicabili.\n2. Esegui scripts/import-ndi.py indicando la cartella SDK.\n3. Compila con ./gradlew assembleNdiDebug e reinstalla l’APK.\n\nIl file necessario è libndi.so per arm64-v8a. Il runtime NDI per macOS o Windows non può essere usato sul telefono.")
            .setPositiveButton("Sito NDI", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://ndi.video/for-developers/"))))
            .setNegativeButton("Chiudi", null).show();
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            int current = getDisplayRotation();
            if (resumed && current != displayDegrees) {
                displayDegrees = current;
                camera.updateDisplayRotation(current);
            }
        }
    };

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        ((DisplayManager) getSystemService(DISPLAY_SERVICE)).registerDisplayListener(displayListener, ui);
        requestCamera(); ui.post(tick);
    }
    @Override protected void onPause() {
        resumed = false; cameraReady = false;
        ((DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(displayListener);
        transmit.setEnabled(false); flash.setEnabled(false);
        ui.removeCallbacks(tick); save(); camera.pause(); super.onPause();
    }
    @Override protected void onDestroy() { camera.destroy(); ui.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { if (!streaming && !pending) openCamera(); else transformPreview(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { cameraReady = false; camera.releaseTexture(s); return false; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(3), 1); return t;
    }
    private Button button(String label, int bg, int fg) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(fg);
        b.setBackground(round(bg, 10)); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(8), 0, dp(8), 0); return b;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void addControl(LinearLayout parent, View view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(46)); p.topMargin = dp(8); parent.addView(view, p);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
