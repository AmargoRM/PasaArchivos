package com.garua.pasaarchivos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import java.security.SecureRandom;

/**
 * Mantiene el servidor encendido aunque se cierre la pantalla o la app.
 * Muestra una notificación fija mientras está activo; se apaga con "Detener".
 */
public class ShareService extends Service {
    static final String ACTION_STOP = "com.garua.pasaarchivos.STOP";
    private static final String CHANNEL = "activo";
    private static final int NOTIF_ID = 7;

    static volatile WebServer server;
    static volatile LocalName localName;
    static volatile boolean nameOn;
    static volatile String startError;

    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock mcLock;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (Exception e) {
            // Android no permitió arrancar en segundo plano: se arranca de nuevo al abrir la app.
            stopSelf();
            return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pasaarchivos");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                mcLock = wm.createMulticastLock("pasaarchivos");
                mcLock.setReferenceCounted(false);
                mcLock.acquire();
            }
        } catch (Exception ignored) {}
        Chat.init(this);
        final LocalName ln = new LocalName(pref(this, "name", 4, "cel-"));
        final WebServer ws = new WebServer(this, ln.host());
        new Thread(() -> {
            try { ws.start(); server = ws; startError = null; }
            catch (Exception e) { startError = "No se pudo abrir el puerto de red. Detené y volvé a abrir la app."; }
            try { nameOn = ln.start(); localName = ln; } catch (Exception ignored) {}
        }, "pa-start").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;  // si Android lo mata, lo intenta levantar de nuevo
    }

    @Override public void onDestroy() {
        WebServer ws = server; server = null;
        if (ws != null) ws.stop();
        LocalName ln = localName; localName = null; nameOn = false;
        if (ln != null) ln.stop();
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        try { if (mcLock != null && mcLock.isHeld()) mcLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "PasaArchivos activo", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ShareService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("PasaArchivos está activo")
                .setContentText("La PC puede conectarse. Tocá para abrir.")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Detener", stop).build())
                .build();
    }

    /** Aviso cuando llega algo de una computadora y la persona no está mirando esa conversación. */
    static void incoming(Context c, Chat.Pc pc, Chat.Msg m) {
        if (pc.id.equals(MainActivity.openChat)) return;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("mensajes", "Archivos y mensajes recibidos", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            Intent open = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_PC, pc.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, pc.id.hashCode(), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String body = m.isFile() ? "📎 " + m.name : m.text;
            Notification n = new Notification.Builder(c, "mensajes")
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(pc.name)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(pc.id.hashCode(), n);
        } catch (Exception ignored) {}
    }

    /** Valor aleatorio que se genera una vez y queda guardado en el celular. */
    static String pref(Context c, String key, int len, String prefix) {
        SharedPreferences sp = c.getSharedPreferences("pasaarchivos", MODE_PRIVATE);
        String t = sp.getString(key, null);
        return t != null ? t : renew(c, key, len, prefix);
    }

    static String renew(Context c, String key, int len, String prefix) {
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < len; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        c.getSharedPreferences("pasaarchivos", MODE_PRIVATE).edit().putString(key, sb.toString()).apply();
        return sb.toString();
    }
}
