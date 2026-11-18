package id.mkoyeh.iminvent;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1201;
    private static final int SCAN_REQUEST = IntentIntegrator.REQUEST_CODE;
    private static final String PREFS = "im_invent_native";
    private static final String PREF_URL = "webapp_url";
    private static final String PREF_CRASH = "last_crash_report_rc13";
    private static final String PREF_STAGE = "last_scan_stage_rc13";
    // Configured specifically for the user-provided staging deployment. Not production.
    private static final String DEFAULT_STAGING_URL = "https://script.google.com/macros/s/AKfycbwDYbzHrredpCZ989Rmw9pBPrjDVmn-OuHnPk8_-zH6i0DZt22_MVt8B-JXo7u94aAoWQ/exec";
    private static final int NAVY = Color.rgb(15, 23, 42);
    private static final int GREEN = Color.rgb(16, 185, 129);

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final AtomicReference<String> pendingScan = new AtomicReference<>("");
    private volatile boolean webBridgeReady = false;
    private boolean scannerBusy = false;
    private String currentWebAppUrl = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashRecorder();
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(NAVY);
        // Android 15+ edge-to-edge: keep our toolbar below the status bar.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(),
                    0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView title = new TextView(this);
        title.setText("IM-INVENT • TEST RC13");
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, 1);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button scan = makeButton("SCAN", GREEN, Color.BLACK);
        scan.setOnClickListener(v -> startNativeScanner());
        scan.setOnLongClickListener(v -> { showManualCodeDialog(); return true; });
        bar.addView(scan, new LinearLayout.LayoutParams(dp(92), dp(44)));
        Button config = makeButton("⚙", Color.rgb(30,41,59), Color.WHITE);
        config.setOnClickListener(v -> showUrlDialog());
        config.setOnLongClickListener(v -> { showDiagnostics(); return true; });
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(54), dp(44));
        settingsParams.leftMargin = dp(8);
        bar.addView(config, settingsParams);
        root.addView(bar);

        webView = new WebView(this);
        webView.setBackgroundColor(NAVY);
        webView.getSettings().setJavaScriptEnabled(true);
        // A distinct marker lets the GAS iframe select the APK scanner rather than GitHub Pages.
        String originalAgent = webView.getSettings().getUserAgentString();
        webView.getSettings().setUserAgentString(originalAgent + " IMInventNative/1");
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setSupportMultipleWindows(false);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true); // Needed for GAS embedded HTML
        // Only safe native methods: opening Google's scanner and reading the result.
        // JavascriptInterface is exposed to all frames, so it MUST NOT expose token/data/file APIs.
        webView.addJavascriptInterface(new NativeBridge(), "IMNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // Native scanning route, reachable even from an Apps Script sandbox iframe.
                if ("iminvent-scan".equalsIgnoreCase(uri.getScheme())) {
                    if ("start".equalsIgnoreCase(uri.getHost())) startNativeScanner();
                    return true;
                }
                // An older deployed index.html may still try the GitHub scanner.
                // Intercept this *exact trusted URL* instead of launching external Chrome.
                if (isLegacyScannerUrl(uri)) {
                    startNativeScanner();
                    return true;
                }
                if ("https".equalsIgnoreCase(uri.getScheme()) && isGoogleWebAppHost(uri.getHost()))
                    return false;
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException e) { toast("Tidak ada browser untuk membuka link."); }
                    return true;
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                webBridgeReady = false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        currentWebAppUrl = getPreferences(MODE_PRIVATE).getString(PREF_URL, DEFAULT_STAGING_URL);
        if (!isAllowedUrl(currentWebAppUrl)) showUrlDialog();
        else webView.loadUrl(currentWebAppUrl);
        showPreviousCrashOnce();
    }

    private int dp(float dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    private Button makeButton(String label, int bg, int fg) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTextColor(fg);
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        return btn;
    }

    private boolean isGoogleWebAppHost(String host) {
        return "script.google.com".equalsIgnoreCase(host)
                || "script.googleusercontent.com".equalsIgnoreCase(host)
                || "accounts.google.com".equalsIgnoreCase(host);
    }

    private boolean isLegacyScannerUrl(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && "mkoyeh72.github.io".equalsIgnoreCase(uri.getHost())
                && uri.getPath() != null
                && (uri.getPath().equals("/im-invent-live-scanner")
                    || uri.getPath().equals("/im-invent-live-scanner/")
                    || uri.getPath().startsWith("/im-invent-live-scanner/"));
    }

    private void deliverScanToWebApp(String code) {
        if (webView == null || code == null || code.isEmpty()) return;
        final String safeCode = JSONObject.quote(code);
        final String js = "(function(){"
                + "var d={source:'IM-INVENT-NATIVE-ANDROID',type:'SCAN_RESULT',code:" + safeCode + "};"
                + "try{window.postMessage(d,window.location.origin);}catch(e){}"
                + "document.querySelectorAll('iframe').forEach(function(f){"
                + "try{var u=new URL(f.src,location.href);var h=u.hostname.toLowerCase();"
                + "if(u.protocol==='https:' && (h==='script.google.com' || h==='script.googleusercontent.com' || h.endsWith('.googleusercontent.com')))"
                + "f.contentWindow.postMessage(d,u.origin);"
                + "}catch(e){}"
                + "});"
                + "})()";
        // WebView calls must be protected against a destroyed or temporarily detached view.
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> sendToWebSafely(js), 300);
        handler.postDelayed(() -> sendToWebSafely(js), 850);
    }

    private void sendToWebSafely(String js) {
        if (isFinishing() || isDestroyed() || webView == null) return;
        try {
            webView.evaluateJavascript(js, null);
        } catch (RuntimeException e) {
            recordDiagnostic("WEBVIEW_DELIVERY_ERROR", e);
            toast("Pengiriman hasil ke WebApp gagal. Tahan tombol pengaturan untuk melihat diagnosa.");
        }
    }

    private void scanStage(String stage) {
        try { getPreferences(MODE_PRIVATE).edit().putString(PREF_STAGE, stage).commit(); }
        catch (RuntimeException ignored) { }
    }

    private String briefError(Throwable e) {
        if (e == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName());
        if (e.getMessage() != null) {
            String message = e.getMessage().replaceAll("https?://\\S+", "[URL]");
            sb.append(": ").append(message.substring(0, Math.min(300, message.length())));
        }
        StackTraceElement[] stack = e.getStackTrace();
        for (int i = 0; i < Math.min(12, stack.length); i++) {
            sb.append("\n  at ").append(stack[i]);
        }
        if (e.getCause() != null && e.getCause() != e) {
            sb.append("\nCaused by: ").append(e.getCause().getClass().getName());
        }
        return sb.toString();
    }

    private void recordDiagnostic(String stage, Throwable e) {
        try {
            String report = "IM-INVENT RC13\nStage: " + stage
                    + "\nAndroid: " + android.os.Build.VERSION.RELEASE
                    + " (API " + android.os.Build.VERSION.SDK_INT + ")\n"
                    + briefError(e);
            getPreferences(MODE_PRIVATE).edit().putString(PREF_CRASH, report).commit();
        } catch (Throwable ignored) { }
    }

    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            String stage = "unknown";
            try { stage = getPreferences(MODE_PRIVATE).getString(PREF_STAGE, "unknown"); }
            catch (Throwable ignored) { }
            recordDiagnostic("FATAL / " + stage + " / " + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    private void showPreviousCrashOnce() {
        String report = getPreferences(MODE_PRIVATE).getString(PREF_CRASH, "");
        if (report == null || report.isEmpty()) return;
        // Show at next launch, not while Android is handling the fatal exception.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) showDiagnostics();
        }, 600);
    }

    private void showDiagnostics() {
        final String report = getPreferences(MODE_PRIVATE).getString(PREF_CRASH, "");
        final String stage = getPreferences(MODE_PRIVATE).getString(PREF_STAGE, "none");
        final String content = (report == null || report.isEmpty())
                ? "Belum ada crash tercatat. Tahap terakhir: " + stage
                : report;
        new AlertDialog.Builder(this)
                .setTitle("Diagnostik APK RC13")
                .setMessage(content)
                .setPositiveButton("Salin laporan", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("IM-INVENT diagnostic", content));
                        toast("Diagnostik disalin. Hapus informasi pribadi sebelum dibagikan.");
                    }
                })
                .setNeutralButton("Hapus laporan", (d, w) ->
                        getPreferences(MODE_PRIVATE).edit().remove(PREF_CRASH).apply())
                .setNegativeButton("Tutup", null)
                .show();
    }

    private boolean isAllowedUrl(String input) {
        if (input == null) return false;
        try {
            Uri uri = Uri.parse(input.trim());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "script.google.com".equalsIgnoreCase(host)
                    && uri.getPath() != null
                    && uri.getPath().matches("/macros/s/[^/]+/exec/?");
        } catch (Exception ignored) { return false; }
    }

    private void showUrlDialog() {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setText(currentWebAppUrl);
        edit.setSingleLine(false);
        edit.setMinLines(2);
        edit.setHint("https://script.google.com/macros/s/.../exec");
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(20), dp(4), dp(20), dp(4));
        wrapper.addView(edit);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Alamat WebApp IM-INVENT")
                .setMessage("Tempel URL /exec deployment IM-INVENT yang sudah diuji. URL disimpan hanya di HP ini.")
                .setView(wrapper)
                .setCancelable(isAllowedUrl(currentWebAppUrl))
                .setPositiveButton("Simpan & Buka", null)
                .setNegativeButton(isAllowedUrl(currentWebAppUrl) ? "Batal" : "Keluar",
                        (d, w) -> { if (!isAllowedUrl(currentWebAppUrl)) finish(); })
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String url = edit.getText().toString().trim();
            if (!isAllowedUrl(url)) {
                edit.setError("Gunakan link Apps Script /exec yang valid dan HTTPS");
                return;
            }
            currentWebAppUrl = url;
            getPreferences(MODE_PRIVATE).edit().putString(PREF_URL, url).apply();
            pendingScan.set("");
            dialog.dismiss();
            webView.loadUrl(url);
        }));
        dialog.show();
    }

    private void toast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }

    // RC12: Embedded ZXing scanner. Uses the app camera, not the Play services scanning UI.
    // The Apps Script bridge is intentionally kept identical to RC11.
    private void startNativeScanner() {
        if (scannerBusy) return;
        if (!isAllowedUrl(currentWebAppUrl)) { showUrlDialog(); return; }
        scannerBusy = true;
        scanStage("NATIVE_SCANNER_OPEN");
        try {
            new IntentIntegrator(this)
                    .setDesiredBarcodeFormats(
                            IntentIntegrator.QR_CODE,
                            IntentIntegrator.CODE_128,
                            IntentIntegrator.CODE_39,
                            IntentIntegrator.DATA_MATRIX)
                    .setPrompt("Arahkan QR / barcode ke kotak. Tombol volume untuk zoom tidak diperlukan.")
                    .setOrientationLocked(true)
                    .setBeepEnabled(false)
                    .setBarcodeImageEnabled(false)
                    .initiateScan();
        } catch (RuntimeException e) {
            scannerBusy = false;
            recordDiagnostic("NATIVE_SCANNER_OPEN", e);
            toast("Scanner gagal dibuka. Tahan pengaturan untuk laporan error.");
        }
    }

    // Long-press SCAN is a controlled diagnostic fallback, not a stock transaction.
    private void showManualCodeDialog() {
        scanStage("MANUAL_DIALOG_OPEN");
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Contoh: DW-B66-4");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(10), dp(20), dp(10));
        wrap.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Tes kode manual (RC12)")
                .setMessage("Ketik kode barang untuk memeriksa jalur Android ke IM-INVENT. Tidak ada transaksi otomatis.")
                .setView(wrap)
                .setPositiveButton("Tampilkan detail", null)
                .setNegativeButton("Batal", null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(click -> {
                    String code = input.getText().toString().trim();
                    if (!validScannedCode(code)) {
                        input.setError("Kode tidak valid (1–512 karakter; tanpa baris baru)");
                        return;
                    }
                    dialog.dismiss();
                    try {
                        handleDecodedCode(code, "MANUAL");
                    } catch (RuntimeException e) {
                        recordDiagnostic("MANUAL_DECODE", e);
                        toast("Tes manual gagal. Tahan pengaturan untuk membaca laporan.");
                    }
                }));
        dialog.show();
    }

    private boolean validScannedCode(String code) {
        if (code == null || code.isEmpty() || code.length() > 512) return false;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < 32 || code.charAt(i) == 127) return false;
        }
        return true;
    }

    private void handleDecodedCode(String code, String engine) {
        if (!validScannedCode(code)) {
            toast("Kode kosong, mengandung karakter kontrol, atau terlalu panjang.");
            return;
        }
        scanStage("RESULT_RECEIVED_" + engine);
        JSONObject result = new JSONObject();
        try { result.put("ok", true); result.put("value", code); }
        catch (Exception ignored) { }
        pendingScan.set(result.toString());
        scanStage("WEB_DELIVERY_PENDING_" + engine);
        deliverScanToWebApp(code);
        // The dialog makes native recognition observable independently of Apps Script.
        new AlertDialog.Builder(this)
                .setTitle("QR TERBACA — " + engine)
                .setMessage("Kode yang dikenali:\n" + code
                        + "\n\nKode telah dikirim ke halaman IM-INVENT."
                        + "\nJika detail belum muncul setelah menutup dialog, masalahnya di bridge/WebApp, bukan pembacaan kamera.")
                .setPositiveButton("Lihat detail", (d, which) -> { })
                .show();
    }

    private class NativeBridge {
        @JavascriptInterface public void startScan() { runOnUiThread(() -> startNativeScanner()); }
        @JavascriptInterface public String pollResult() { return pendingScan.getAndSet(""); }
        @JavascriptInterface public void markReady() { webBridgeReady = true; }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCAN_REQUEST) {
            scannerBusy = false;
            scanStage("ZXING_ACTIVITY_RESULT");
            try {
                IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
                if (scanResult == null) {
                    toast("Hasil scanner tidak dikenali. Tahan pengaturan untuk laporan.");
                } else if (scanResult.getContents() == null) {
                    toast("Scanner dibatalkan atau izin kamera belum diberikan.");
                } else {
                    handleDecodedCode(scanResult.getContents().trim(), "ZXING");
                }
            } catch (RuntimeException e) {
                recordDiagnostic("ZXING_ACTIVITY_RESULT", e);
                toast("Kesalahan saat menerima hasil scan. Lihat diagnostik di pengaturan.");
            }
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else new AlertDialog.Builder(this).setTitle("Keluar IM-INVENT?")
                .setPositiveButton("Keluar", (d, w) -> finish())
                .setNegativeButton("Batal", null).show();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("IMNative");
            webView.destroy();
        }
        super.onDestroy();
    }
}
