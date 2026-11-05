package ru.inventory.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String DATA_FILE = "inventory.json";
    private static final int REQ_SAVE = 101;
    private static final int REQ_OPEN = 102;
    private static final int BG = 0xFF0B0D14;

    private WebView web;
    private WebView printView;           // держим ссылку, пока идёт печать
    private String pendingSaveContent;   // содержимое файла, ждущее выбора места

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl(APP_URL);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", value -> {
            if (!"true".equals(value)) super.onBackPressed();
        });
    }

    private void callJs(String js) {
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;

        if (requestCode == REQ_SAVE) {
            String content = pendingSaveContent;
            pendingSaveContent = null;
            boolean ok = false;
            if (uri != null && content != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
                    if (os != null) { os.write(content.getBytes(StandardCharsets.UTF_8)); ok = true; }
                } catch (Exception e) { ok = false; }
            }
            callJs("window.onAndroidSaved && window.onAndroidSaved(" + ok + ")");
        } else if (requestCode == REQ_OPEN && uri != null) {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new Exception("no stream");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) text = text.substring(1);
                callJs("window.onAndroidFile && window.onAndroidFile(" + JSONObject.quote(text) + ")");
            } catch (Exception e) {
                toast("Не удалось прочитать файл");
            }
        }
    }

    /** Методы, доступные странице как window.Android.* */
    private class Bridge {

        @JavascriptInterface
        public String loadData() {
            File f = new File(getFilesDir(), DATA_FILE);
            if (!f.exists()) return "";
            try (InputStream is = new java.io.FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean saveData(String json) {
            File dir = getFilesDir();
            File tmp = new File(dir, DATA_FILE + ".tmp");
            File dst = new File(dir, DATA_FILE);
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.getFD().sync();
            } catch (Exception e) {
                return false;
            }
            return tmp.renameTo(dst);
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void saveFile(String name, String mime, String content) {
            runOnUiThread(() -> {
                pendingSaveContent = content;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (Exception e) {
                    pendingSaveContent = null;
                    toast("Нет приложения для сохранения файлов");
                }
            });
        }

        @JavascriptInterface
        public void shareFile(String name, String mime, String content) {
            runOnUiThread(() -> {
                try {
                    File dir = new File(getCacheDir(), "share");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, name);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Отправить таблицу"));
                } catch (Exception e) {
                    toast("Не удалось отправить файл");
                }
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Инвентаризация", text));
            });
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void openFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (Exception e) {
                    toast("Нет приложения для выбора файлов");
                }
            });
        }

        @JavascriptInterface
        public void print(String html, String jobName) {
            runOnUiThread(() -> {
                final WebView pv = new WebView(MainActivity.this);
                printView = pv;
                pv.setWebViewClient(new WebViewClient() {
                    private boolean started = false;

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (started) return;
                        started = true;
                        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                        if (pm == null) { toast("Печать недоступна"); return; }
                        PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(jobName);
                        PrintAttributes attrs = new PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                                .build();
                        pm.print(jobName, adapter, attrs);
                    }
                });
                pv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
            });
        }
    }
}
