package com.rentschler.pricescanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends ComponentActivity {
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final int REQ_CAMERA = 1;
    private static final String REPO = "rentschlerm/price-scanner";
    private static final String UI_URL = "https://raw.githubusercontent.com/" + REPO + "/main/app/src/main/assets/index.html";
    private static final String RELEASE_API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private String updateApkUrl = null;
    private boolean pendingInstall = false;

    private WebView web;
    private FrameLayout camBox;
    private PreviewView previewView;
    private AimView aimView;
    private TextView torchBtn, zoomBtn;
    private PermissionRequest pendingRequest;
    private final Bridge bridge = new Bridge();

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private BarcodeScanner scanner;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private boolean wantCamera = false;
    private boolean torchOn = false;
    private boolean zoomed = false;

    // Accuracy: a code must be read in 2 frames (within 1.2 s) before it is reported.
    private String candidate = null;
    private long candidateAt = 0;
    private String lastSent = null;
    private long lastSentAt = 0;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ----- Native camera area (shown only while scanning) -----
        camBox = new FrameLayout(this);
        camBox.setBackgroundColor(Color.BLACK);
        int camHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.34f);
        root.addView(camBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, camHeight));
        camBox.setVisibility(View.GONE);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        camBox.addView(previewView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        aimView = new AimView(this);
        camBox.addView(aimView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams ctlLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        ctlLp.setMargins(0, dp(10), dp(10), 0);
        camBox.addView(ctl, ctlLp);
        torchBtn = pill("🔦 Light");
        zoomBtn = pill("2×");
        TextView pauseBtn = pill("Pause");
        ctl.addView(torchBtn);
        ctl.addView(zoomBtn);
        ctl.addView(pauseBtn);
        torchBtn.setOnClickListener(v -> {
            if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
            torchOn = !torchOn;
            camera.getCameraControl().enableTorch(torchOn);
            torchBtn.setText(torchOn ? "🔦 Off" : "🔦 Light");
        });
        zoomBtn.setOnClickListener(v -> {
            if (camera == null) return;
            zoomed = !zoomed;
            camera.getCameraControl().setZoomRatio(zoomed ? 2.0f : 1.0f);
            zoomBtn.setText(zoomed ? "1×" : "2×");
        });
        pauseBtn.setOnClickListener(v -> {
            stopCameraInternal();
            js("window.__onCameraState && window.__onCameraState(false)");
        });

        TextView hint = new TextView(this);
        hint.setText("Tap to focus");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(12);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.setMargins(0, 0, 0, dp(8));
        camBox.addView(hint, hintLp);

        previewView.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint pt = previewView.getMeteringPointFactory().createPoint(ev.getX(), ev.getY());
                camera.getCameraControl().startFocusAndMetering(
                        new FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                                .setAutoCancelDuration(4, TimeUnit.SECONDS).build());
                v.performClick();
            }
            return true;
        });

        // ----- WebView with the app UI -----
        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (APP_HOST.equals(u.getHost()) && "/assets/index.html".equals(u.getPath())) {
                    File f = uiFile();
                    if (f.exists() && f.length() > 1000) {
                        try { return new WebResourceResponse("text/html", "utf-8", new FileInputStream(f)); } catch (Exception ignored) { }
                    }
                }
                return loader.shouldInterceptRequest(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (APP_HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        pendingRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }
        });
        web.addJavascriptInterface(bridge, "AndroidBridge");

        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF,
                        Barcode.FORMAT_CODABAR, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                .build());

        web.loadUrl("https://" + APP_HOST + "/assets/index.html");
    }

    // ---------------- Self-update ----------------
    private File uiFile() { return new File(getFilesDir(), "ui/index.html"); }

    private int nativeVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    private static int minNative(String html) {
        Matcher m = Pattern.compile("name=\"min-native\"\\s+content=\"(\\d+)\"").matcher(html);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Downloads the newest screen (index.html) from GitHub; it is used from the next reload. */
    private void checkUiUpdate() {
        try {
            String html = new String(bridge.request(UI_URL + "?t=" + System.currentTimeMillis(), null), StandardCharsets.UTF_8);
            if (!html.contains("<html") || !html.contains("</html>") || !html.contains("Price Scanner")) return;
            if (minNative(html) > nativeVersion()) return; // needs a newer app; the update button handles that
            File f = uiFile();
            String current = null;
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) { current = new String(readAll(in), StandardCharsets.UTF_8); }
            } else {
                try (InputStream in = getAssets().open("index.html")) { current = new String(readAll(in), StandardCharsets.UTF_8); }
            }
            if (html.equals(current)) return;
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File tmp = new File(dir, "index.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(html.getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
            js("window.__onUiUpdated && window.__onUiUpdated()");
        } catch (Exception ignored) { }
    }

    /** Checks GitHub Releases for a newer APK. */
    private void checkAppUpdate() {
        try {
            JSONObject rel = new JSONObject(new String(bridge.request(RELEASE_API, null), StandardCharsets.UTF_8));
            String tag = rel.optString("tag_name", "");
            Matcher m = Pattern.compile("(\\d+)$").matcher(tag);
            if (!m.find()) return;
            int latest = Integer.parseInt(m.group(1));
            if (latest <= nativeVersion()) return;
            org.json.JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) return;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) { updateApkUrl = a.optString("browser_download_url"); break; }
            }
            if (updateApkUrl != null) js("window.__onAppUpdate && window.__onAppUpdate(" + JSONObject.quote(tag) + ")");
        } catch (Exception ignored) { }
    }

    private File updateFile() { return new File(getCacheDir(), "update.apk"); }

    private void downloadAndInstall() {
        if (updateApkUrl == null) return;
        new Thread(() -> {
            try {
                js("window.__onUpdateStatus && window.__onUpdateStatus('Downloading update…')");
                byte[] data = bridge.request(updateApkUrl, null);
                try (FileOutputStream out = new FileOutputStream(updateFile())) { out.write(data); }
                runOnUiThread(this::launchInstaller);
            } catch (Exception e) {
                js("window.__onUpdateStatus && window.__onUpdateStatus('Update download failed — check internet and try again')");
            }
        }).start();
    }

    private void launchInstaller() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = true;
            js("window.__onUpdateStatus && window.__onUpdateStatus('Allow “Install unknown apps” for Price Scanner, then come back')");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        pendingInstall = false;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", updateFile());
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private TextView pill(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(18));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    // ---------------- Camera ----------------
    private void startCameraInternal() {
        wantCamera = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        camBox.setVisibility(View.VISIBLE);
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                if (!wantCamera) return;
                cameraProvider.unbindAll();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                @SuppressWarnings("deprecation")
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                torchOn = false;
                torchBtn.setText("🔦 Light");
                torchBtn.setVisibility(camera.getCameraInfo().hasFlashUnit() ? View.VISIBLE : View.GONE);
                if (zoomed) camera.getCameraControl().setZoomRatio(2.0f);
                js("window.__onCameraState && window.__onCameraState(true)");
            } catch (Exception e) {
                js("window.__onCameraState && window.__onCameraState(false, " + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCameraInternal() {
        wantCamera = false;
        if (cameraProvider != null) cameraProvider.unbindAll();
        camera = null;
        camBox.setVisibility(View.GONE);
    }

    @ExperimentalGetImage
    private void analyze(ImageProxy proxy) {
        Image media = proxy.getImage();
        if (media == null) { proxy.close(); return; }
        InputImage img = InputImage.fromMediaImage(media, proxy.getImageInfo().getRotationDegrees());
        scanner.process(img)
                .addOnSuccessListener(this::onBarcodes)
                .addOnCompleteListener(t -> proxy.close());
    }

    private void onBarcodes(List<Barcode> list) {
        if (list == null || list.isEmpty()) return;
        String value = null;
        for (Barcode b : list) {
            if (b.getRawValue() != null && !b.getRawValue().isEmpty()) { value = b.getRawValue(); break; }
        }
        if (value == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!value.equals(candidate) || now - candidateAt > 1200) {
            candidate = value;
            candidateAt = now;
            return; // wait for a second matching read
        }
        candidateAt = now;
        if (value.equals(lastSent) && now - lastSentAt < 1800) { lastSentAt = now; return; }
        lastSent = value;
        lastSentAt = now;
        aimView.flash();
        js("window.__onNativeScan && window.__onNativeScan(" + JSONObject.quote(value) + ")");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingRequest != null) {
            if (granted) pendingRequest.grant(pendingRequest.getResources());
            else pendingRequest.deny();
            pendingRequest = null;
        }
        if (wantCamera) {
            if (granted) startCameraInternal();
            else {
                wantCamera = false;
                js("window.__onCameraState && window.__onCameraState(false, 'denied')");
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__onBack ? window.__onBack() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (pendingInstall && updateFile().exists()) launchInstaller();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExecutor.shutdown();
        if (scanner != null) scanner.close();
    }

    // ---------------- Aim box overlay ----------------
    private static class AimView extends View {
        private final Paint shade = new Paint();
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint laser = new Paint();
        private long flashUntil = 0;

        AimView(Context c) {
            super(c);
            shade.setColor(0x55000000);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(6f);
            laser.setColor(0xFFF43F5E);
            laser.setStrokeWidth(4f);
        }

        void flash() {
            flashUntil = SystemClock.elapsedRealtime() + 350;
            post(this::invalidate);
            postDelayed(this::invalidate, 400);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            RectF r = new RectF(w * 0.08f, h * 0.22f, w * 0.92f, h * 0.78f);
            c.drawRect(0, 0, w, r.top, shade);
            c.drawRect(0, r.bottom, w, h, shade);
            c.drawRect(0, r.top, r.left, r.bottom, shade);
            c.drawRect(r.right, r.top, w, r.bottom, shade);
            boolean hit = SystemClock.elapsedRealtime() < flashUntil;
            frame.setColor(hit ? 0xFF4ADE80 : 0xEEFFFFFF);
            c.drawRoundRect(r, 24f, 24f, frame);
            if (!hit) c.drawLine(r.left + 20, r.centerY(), r.right - 20, r.centerY(), laser);
        }
    }

    // ---------------- JavaScript bridge ----------------
    private class Bridge {
        @JavascriptInterface
        public boolean hasNativeScanner() { return true; }

        @JavascriptInterface
        public int nativeVersion() { return MainActivity.this.nativeVersion(); }

        @JavascriptInterface
        public void checkForUpdates() {
            new Thread(() -> { checkUiUpdate(); checkAppUpdate(); }).start();
        }

        @JavascriptInterface
        public void installUpdate() { downloadAndInstall(); }

        @JavascriptInterface
        public void startCamera() { runOnUiThread(MainActivity.this::startCameraInternal); }

        @JavascriptInterface
        public void stopCamera() { runOnUiThread(MainActivity.this::stopCameraInternal); }

        @JavascriptInterface
        public void fetchText(final String url, final String callbackId) {
            new Thread(() -> {
                boolean ok = false;
                String result;
                try {
                    result = new String(request(url, null), StandardCharsets.UTF_8);
                    ok = true;
                } catch (Exception e) {
                    result = e.getMessage() != null ? e.getMessage() : "No internet connection";
                }
                callback(callbackId, ok, result);
            }).start();
        }

        @JavascriptInterface
        public void fetchBase64(final String url, final String callbackId) {
            new Thread(() -> {
                boolean ok = false;
                String result;
                try {
                    byte[] data = request(url, null);
                    result = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                    ok = true;
                } catch (Exception e) {
                    result = e.getMessage() != null ? e.getMessage() : "No internet connection";
                }
                callback(callbackId, ok, result);
            }).start();
        }

        @JavascriptInterface
        public void postJson(final String url, final String body, final String callbackId) {
            new Thread(() -> {
                boolean ok = false;
                String result;
                try {
                    result = new String(request(url, body), StandardCharsets.UTF_8);
                    ok = true;
                } catch (Exception e) {
                    result = e.getMessage() != null ? e.getMessage() : "No internet connection";
                }
                callback(callbackId, ok, result);
            }).start();
        }

        @JavascriptInterface
        public void copyText(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Price list", text));
            });
        }

        private void callback(String id, boolean ok, String result) {
            js("window.__nativeCb(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(result) + ")");
        }

        /** GET (body == null) or POST, following redirects (POST becomes GET after a redirect, as browsers do). */
        byte[] request(String url, String body) throws Exception {
            String current = url;
            String method = body == null ? "GET" : "POST";
            for (int i = 0; i < 8; i++) {
                HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PriceScanner");
                c.setRequestMethod(method);
                if ("POST".equals(method)) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "text/plain;charset=utf-8");
                    try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                    method = "GET";
                    c.disconnect();
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                return bos.toByteArray();
            }
            throw new Exception("Too many redirects");
        }
    }
}
