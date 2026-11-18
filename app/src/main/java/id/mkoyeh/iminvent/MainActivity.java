        // Resend on resumed WebView; frontend deduplicates results; pollResult is a second fallback.
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> webView.evaluateJavascript(js, null), 300);
        handler.postDelayed(() -> webView.evaluateJavascript(js, null), 850);
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

    private void startNativeScanner() {
        if (scannerBusy) return;
        if (!isAllowedUrl(currentWebAppUrl)) { showUrlDialog(); return; }
        scannerBusy = true;
        GmsBarcodeScannerOptions opts = new GmsBarcodeScannerOptions.Builder().enableAutoZoom().build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, opts);
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    scannerBusy = false;
                    String code = barcode.getRawValue() == null ? "" : barcode.getRawValue().trim();
                    if (code.isEmpty() || code.length() > 512) { toast("Kode QR kosong atau terlalu panjang."); return; }
                    // Only a barcode string crosses the JS bridge. Backend still authorizes transactions.
                    JSONObject result = new JSONObject();
                    try { result.put("ok", true); result.put("value", code); } catch (Exception ignored) { }
                    pendingScan.set(result.toString());
                    deliverScanToWebApp(code);
                    toast("QR terbaca. Memeriksa detail alat di IM-INVENT...");
                })
                .addOnCanceledListener(() -> { scannerBusy = false; })
                .addOnFailureListener(e -> {
                    scannerBusy = false;
                    toast("Scanner gagal. Periksa Google Play services dan koneksi awal: " + e.getMessage());
                });
    }

    private class NativeBridge {
        @JavascriptInterface public void startScan() { runOnUiThread(() -> startNativeScanner()); }
        @JavascriptInterface public String pollResult() { return pendingScan.getAndSet(""); }
        @JavascriptInterface public void markReady() { webBridgeReady = true; }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
