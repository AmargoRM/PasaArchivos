package com.garua.pasaarchivos;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Busca versiones nuevas en GitHub (Releases del repositorio) y las instala con el instalador de Android.
 * Siempre pide confirmación a la persona; Android no deja instalar en silencio.
 */
final class Updater {
    static final String REPO = "AmargoRM/PasaArchivos";
    static final String INFO_URL = "https://github.com/" + REPO + "/releases/latest/download/version.json";
    private static final long EVERY_MS = 6L * 60 * 60 * 1000;   // revisa como máximo cada 6 horas

    static final class Info {
        final int code; final String name; final String apk; final String notes;
        Info(int c, String n, String a, String t) { code = c; name = n; apk = a; notes = t; }
    }

    interface Found { void onResult(Info info, String error); }
    interface Progress { void onProgress(int percent); void onError(String msg); }

    private static final Handler ui = new Handler(Looper.getMainLooper());

    /** Revisa si hay versión nueva. Con {@code force=false} respeta el intervalo de 6 horas. */
    static void check(Context ctx, boolean force, Found cb) {
        final Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("pasaarchivos", Context.MODE_PRIVATE);
        long last = sp.getLong("upd_last", 0);
        if (!force && System.currentTimeMillis() - last < EVERY_MS) {
            Info cached = fromPrefs(sp);
            ui.post(() -> cb.onResult(cached != null && cached.code > BuildConfig.VERSION_CODE ? cached : null, null));
            return;
        }
        new Thread(() -> {
            Info found = null; String err = null;
            try {
                JSONObject j = new JSONObject(get(INFO_URL));
                Info i = new Info(j.getInt("versionCode"), j.getString("versionName"), j.getString("apk"), j.optString("notes", ""));
                sp.edit().putLong("upd_last", System.currentTimeMillis()).putInt("upd_code", i.code)
                        .putString("upd_name", i.name).putString("upd_apk", i.apk).putString("upd_notes", i.notes).apply();
                if (i.code > BuildConfig.VERSION_CODE) found = i;
            } catch (Exception e) {
                err = "No se pudo revisar si hay actualizaciones (¿sin internet?).";
            }
            final Info f = found; final String e2 = err;
            ui.post(() -> cb.onResult(f, e2));
        }, "pa-update-check").start();
    }

    private static Info fromPrefs(SharedPreferences sp) {
        int code = sp.getInt("upd_code", 0);
        String apk = sp.getString("upd_apk", null);
        if (code == 0 || apk == null) return null;
        return new Info(code, sp.getString("upd_name", "?"), apk, sp.getString("upd_notes", ""));
    }

    /** True si Android ya permite que esta app instale actualizaciones. */
    static boolean canInstall(Context c) { return c.getPackageManager().canRequestPackageInstalls(); }

    /** Abre el ajuste de Android "Permitir de esta fuente". */
    static void askPermission(Activity a) {
        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
    }

    /** Descarga el APK y abre la confirmación de instalación de Android. */
    static void install(Context ctx, Info info, Progress p) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(c.getPackageName());
                int id = pi.createSession(params);
                session = pi.openSession(id);
                HttpURLConnection con = open(info.apk);
                long total = con.getContentLengthLong();
                try (InputStream in = con.getInputStream(); OutputStream out = session.openWrite("app.apk", 0, total)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0; int r, lastPct = -1;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        done += r;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) { lastPct = pct; final int x = pct; ui.post(() -> p.onProgress(x)); }
                        }
                    }
                    session.fsync(out);
                }
                Intent done = new Intent(c, InstallReceiver.class);
                PendingIntent pend = PendingIntent.getBroadcast(c, 3, done,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pend.getIntentSender());
                session.close();
            } catch (Exception e) {
                if (session != null) { try { session.abandon(); } catch (Exception ignored) {} }
                ui.post(() -> p.onError("No se pudo descargar la actualización. Probá de nuevo con buena señal."));
            }
        }, "pa-update-install").start();
    }

    private static HttpURLConnection open(String url) throws IOException {
        // GitHub redirige a otro servidor para las descargas; se siguen los saltos a mano.
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", "PasaArchivos/" + BuildConfig.VERSION_NAME);
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new IOException("Redirección sin destino");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) throw new IOException("HTTP " + code);
            return con;
        }
        throw new IOException("Demasiadas redirecciones");
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private Updater() {}
}
