package fr.tbsa.serveur;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TBSA-Serveur : application Android (téléphone et Android TV).
 * Elle se connecte à TBSA-Serveur (sur le PC) et affiche son interface :
 * films locaux, séries, TV en direct et IPTV Xtream.
 */
public class MainActivity extends Activity {
    private static final int DEFAULT_PORT = 8585;
    private static final int[] PORTS = {8585, 8586, 8587, 8588};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private SharedPreferences prefs;
    private boolean tv;

    private FrameLayout root;
    private WebView web;
    private LinearLayout connect;
    private TextView status;
    private EditText input;
    private Button btnScan;
    private Button btnGo;

    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private WebChromeClient chrome;
    private ExecutorService pool;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tbsa", MODE_PRIVATE);
        UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        tv = um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1F2326);
        buildWeb();
        buildConnect();
        setContentView(root);

        String saved = prefs.getString("server", "");
        if (!saved.isEmpty()) {
            open(saved);
        } else {
            showConnect("Recherche du serveur…");
            scan();
        }
    }

    /* ---------- WebView ---------- */

    private void buildWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + (tv ? " TBSA-TV" : " TBSA-App"));
        web.setBackgroundColor(0xFF1F2326);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.addJavascriptInterface(new Bridge(), "TBSA");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r != null && r.isForMainFrame()) {
                    showConnect("Serveur injoignable. Vérifiez que TBSA-Serveur est lancé sur le PC.");
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView v, int code, String desc, String url) {
                if (url != null && url.equals(v.getUrl())) showConnect("Serveur injoignable. Vérifiez que TBSA-Serveur est lancé sur le PC.");
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = cb;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                immersive(true);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
                immersive(false);
            }
        };
        web.setWebChromeClient(chrome);

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        int flags = on
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_FLAG_VISIBLE;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /** Appelé par la page web (bouton « Changer de serveur » dans Réglages). */
    private class Bridge {
        @JavascriptInterface
        public void changeServer() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    prefs.edit().remove("server").apply();
                    showConnect("Choisissez un serveur.");
                }
            });
        }
    }

    /* ---------- Écran de connexion ---------- */

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String t, int sp, int color) {
        TextView tvw = new TextView(this);
        tvw.setText(t);
        tvw.setTextSize(sp);
        tvw.setTextColor(color);
        tvw.setGravity(Gravity.CENTER);
        return tvw;
    }

    private Button button(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(18);
        return b;
    }

    private void add(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(v, lp);
    }

    private void buildConnect() {
        connect = new LinearLayout(this);
        connect.setOrientation(LinearLayout.VERTICAL);
        connect.setGravity(Gravity.CENTER);
        connect.setPadding(dp(24), dp(24), dp(24), dp(24));
        connect.setBackgroundColor(0xFF1F2326);

        status = label("", 18, 0xFFECEFF1);
        btnScan = button("Rechercher le serveur");
        input = new EditText(this);
        input.setHint("ex. 192.168.1.20:8585");
        input.setHintTextColor(0xFF9AA3AA);
        input.setTextColor(0xFFECEFF1);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefs.getString("server", "").replace("http://", ""));
        btnGo = button("Se connecter");

        add(connect, label("TBSA-Serveur", 32, 0xFFE5A00D), 0);
        add(connect, status, 16);
        add(connect, btnScan, 24);
        add(connect, label("ou saisissez l’adresse affichée par le serveur :", 14, 0xFF9AA3AA), 20);
        add(connect, input, 4);
        add(connect, btnGo, 8);

        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        });
        btnGo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String a = input.getText().toString().trim();
                if (a.isEmpty()) {
                    status.setText("Saisissez l’adresse du serveur, ou lancez la recherche.");
                } else {
                    open(a);
                }
            }
        });

        root.addView(connect, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        connect.setVisibility(View.GONE);
    }

    private void showConnect(String msg) {
        status.setText(msg);
        web.setVisibility(View.GONE);
        connect.setVisibility(View.VISIBLE);
        btnScan.requestFocus();
    }

    private String normalize(String a) {
        a = a.trim();
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://" + a;
        String rest = a.substring(a.indexOf("//") + 2);
        if (!rest.contains(":")) a = a + ":" + DEFAULT_PORT;
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        return a;
    }

    private void open(String addr) {
        String base = normalize(addr);
        prefs.edit().putString("server", base).apply();
        connect.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.requestFocus();
        web.loadUrl(base + (tv ? "/?tv=1" : "/?tv=0"));
        Updater.check(this, base, prefs);
    }

    /* ---------- Recherche automatique du serveur (réseau local) ---------- */

    private String localPrefix() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String h = a.getHostAddress();
                        return h.substring(0, h.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean ping(String ip, int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + ":" + port + "/api/ping").openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(800);
            if (c.getResponseCode() != 200) return false;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line = r.readLine();
            r.close();
            return line != null && line.contains("TBSA-Serveur");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void scanEnded(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                scanning.set(false);
                btnScan.setEnabled(true);
                status.setText(msg);
            }
        });
    }

    private void scan() {
        if (!scanning.compareAndSet(false, true)) return;
        btnScan.setEnabled(false);
        status.setText("Recherche du serveur sur le réseau…");
        final String prefix = localPrefix();
        if (prefix == null) {
            scanEnded("Pas de réseau local. Connectez cet appareil au même Wi-Fi (ou câble) que le PC.");
            return;
        }
        final ExecutorService ex = Executors.newFixedThreadPool(48);
        pool = ex;
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicInteger left = new AtomicInteger(254 * PORTS.length);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            for (final int port : PORTS) {
                ex.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (!done.get() && ping(ip, port) && done.compareAndSet(false, true)) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    scanning.set(false);
                                    btnScan.setEnabled(true);
                                    open(ip + ":" + port);
                                }
                            });
                        }
                        if (left.decrementAndGet() == 0 && !done.get()) {
                            scanEnded("Serveur introuvable. Lancez TBSA-Serveur sur le PC (meme Wi-Fi), "
                                    + "autorisez-le dans le pare-feu, puis reessayez ou saisissez l'adresse.");
                        }
                    }
                });
            }
        }
        ex.shutdown();
    }

    /* ---------- Cycle de vie ---------- */

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        if (connect.getVisibility() == View.VISIBLE) {
            if (!prefs.getString("server", "").isEmpty()) {
                connect.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
            } else {
                finish();
            }
            return;
        }
        web.evaluateJavascript("(window.__back && window.__back()) ? 'yes' : 'no'",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (v == null || !v.contains("yes")) finish();
                    }
                });
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU && web.getVisibility() == View.VISIBLE) {
            web.evaluateJavascript("(function(){var b=document.getElementById('bu');if(b){b.click();}})()", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
    }

    @Override
    protected void onDestroy() {
        if (pool != null) pool.shutdownNow();
        web.destroy();
        super.onDestroy();
    }
}
