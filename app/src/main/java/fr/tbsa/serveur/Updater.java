package fr.tbsa.serveur;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mise à jour de l'application depuis le serveur (TBSA-Serveur.apk placé à côté du serveur), sans désinstaller. */
final class Updater {
    private Updater() {
    }

    static void check(final Activity act, final String base, final SharedPreferences prefs) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/apk-info").openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(4000);
                    if (c.getResponseCode() != 200) return;
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    String line = r.readLine();
                    r.close();
                    c.disconnect();
                    if (line == null || !line.contains("\"has\":true")) return;
                    Matcher m = Pattern.compile("\"stamp\":\"([^\"]+)\"").matcher(line);
                    if (!m.find()) return;
                    final String stamp = m.group(1);
                    if (stamp.equals(prefs.getString("apkStamp", ""))) return;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!act.isFinishing()) ask(act, base, prefs, stamp);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private static void ask(final Activity act, final String base, final SharedPreferences prefs, final String stamp) {
        new AlertDialog.Builder(act)
                .setTitle("Mise à jour de TBSA")
                .setMessage("Une version de l'application est disponible sur votre serveur. L'installer maintenant ? (vos réglages sont conservés)")
                .setPositiveButton("Installer", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        install(act, base, prefs, stamp);
                    }
                })
                .setNegativeButton("Plus tard", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putString("apkStamp", stamp).apply();
                    }
                })
                .show();
    }

    private static void install(final Activity act, final String base, final SharedPreferences prefs, final String stamp) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(act, "Autorisez TBSA à installer des applications, puis relancez TBSA.", Toast.LENGTH_LONG).show();
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            } catch (Exception e) {
                act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
            return;
        }
        final AlertDialog dlg = new AlertDialog.Builder(act).setTitle("Mise à jour de TBSA").setMessage("Téléchargement… 0 %").setCancelable(false).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    File out = new File(act.getCacheDir(), "update.apk");
                    HttpURLConnection c = (HttpURLConnection) new URL(base + "/apk").openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(30000);
                    final long total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    long got = 0;
                    int last = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        got += n;
                        final int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        if (pct != last) {
                            last = pct;
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    dlg.setMessage("Téléchargement… " + pct + " %");
                                }
                            });
                        }
                    }
                    fo.close();
                    in.close();
                    c.disconnect();
                    if (total > 0 && got < total) err = "Téléchargement incomplet";
                } catch (Exception e) {
                    err = "Téléchargement impossible : " + e.getMessage();
                }
                final String fe = err;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dlg.dismiss();
                        if (fe != null) {
                            Toast.makeText(act, fe, Toast.LENGTH_LONG).show();
                            return;
                        }
                        prefs.edit().putString("apkStamp", stamp).apply();
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(Uri.parse("content://fr.tbsa.serveur.update/update.apk"), "application/vnd.android.package-archive");
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            act.startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(act, "Installation impossible : " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }
}
