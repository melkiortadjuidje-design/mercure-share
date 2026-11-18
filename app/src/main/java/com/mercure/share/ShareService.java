package com.mercure.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.mercure.share.proxy.ProxyServer;

import java.io.IOException;

/**
 * Service de premier plan : garde le partage actif même écran éteint.
 * Il lance le proxy et le réseau Wi-Fi Direct.
 */
public class ShareService extends Service {

    public static final String ACTION_START = "com.mercure.share.START";
    public static final String ACTION_STOP = "com.mercure.share.STOP";

    private static final String CHANNEL_ID = "mercure_share";
    private static final int NOTIF_ID = 42;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProxyServer proxy;
    private WifiDirectHelper wifiDirect;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastUp, lastDown;
    private int tick;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ShareService.class).setAction(ACTION_START);
        androidx.core.content.ContextCompat.startForegroundService(ctx, i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, ShareService.class).setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Exception e) {
            ctx.stopService(new Intent(ctx, ShareService.class));
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopSharing();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat(buildNotification("Démarrage du partage…"));
        if (proxy == null) startSharing();
        return START_STICKY;
    }

    // ------------------------------------------------------------------ démarrage / arrêt

    private void startSharing() {
        final ShareState st = ShareState.get();
        final Prefs prefs = new Prefs(this);
        st.reset();
        st.status = ShareState.Status.STARTING;
        st.port = prefs.getPort();
        st.startedAt = System.currentTimeMillis();
        st.notifyChanged();

        acquireLocks();

        final ProxyServer p = new ProxyServer(prefs.getPort(), msg -> { });
        proxy = p;
        new Thread(() -> {
            try {
                p.start();
                handler.post(() -> {
                    if (proxy != p) { // arrêté pendant le démarrage
                        new Thread(p::stop).start();
                        return;
                    }
                    st.status = ShareState.Status.RUNNING;
                    st.notifyChanged();
                    updateNotification();
                    if (prefs.isWifiDirectEnabled()) startWifiDirect(prefs);
                    handler.postDelayed(ticker, 1000);
                });
            } catch (IOException e) {
                handler.post(() -> {
                    st.status = ShareState.Status.ERROR;
                    st.error = "Le port " + prefs.getPort() + " est déjà utilisé. Changez-le dans les réglages.";
                    st.notifyChanged();
                    proxy = null;
                    releaseLocks();
                    stopForegroundCompat();
                    stopSelf();
                });
            }
        }, "ms-start").start();
    }

    private void startWifiDirect(Prefs prefs) {
        final ShareState st = ShareState.get();
        st.wifiDirect = ShareState.WifiDirect.CREATING;
        st.notifyChanged();
        wifiDirect = new WifiDirectHelper(this, new WifiDirectHelper.Callback() {
            @Override public void onGroupReady(String ssid, String passphrase) {
                st.wifiDirect = ShareState.WifiDirect.READY;
                st.wifiDirectError = null;
                st.ssid = ssid;
                st.passphrase = passphrase;
                st.notifyChanged();
                updateNotification();
            }

            @Override public void onError(String message) {
                st.wifiDirect = ShareState.WifiDirect.FAILED;
                st.wifiDirectError = message;
                st.notifyChanged();
                updateNotification();
            }

            @Override public void onClientsChanged(int count) {
                st.wifiClients = count;
                st.notifyChanged();
            }
        });
        wifiDirect.start(prefs.getNetworkName(), prefs.getPassword(), prefs.getBand());
    }

    private void stopSharing() {
        handler.removeCallbacks(ticker);
        if (wifiDirect != null) {
            wifiDirect.stop();
            wifiDirect = null;
        }
        if (proxy != null) {
            final ProxyServer p = proxy;
            proxy = null;
            new Thread(p::stop, "ms-stop").start();
        }
        releaseLocks();
        ShareState st = ShareState.get();
        boolean hadError = st.status == ShareState.Status.ERROR;
        String error = st.error;
        st.reset();
        if (hadError) { // on garde le message d'erreur visible à l'écran
            st.status = ShareState.Status.ERROR;
            st.error = error;
        }
        st.notifyChanged();
    }

    @Override
    public void onDestroy() {
        stopSharing();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Le partage continue même si l'application est balayée des applications récentes.
        super.onTaskRemoved(rootIntent);
    }

    // ------------------------------------------------------------------ statistiques

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            ProxyServer p = proxy;
            if (p == null) return;
            ShareState st = ShareState.get();
            long up = p.getBytesUp();
            long down = p.getBytesDown();
            st.rateUp = Math.max(0, up - lastUp);
            st.rateDown = Math.max(0, down - lastDown);
            lastUp = up;
            lastDown = down;
            st.bytesUp = up;
            st.bytesDown = down;
            st.connections = p.getActiveConnections();
            st.proxyClients = p.getActiveClientCount();
            st.clientIps = p.getActiveClients();
            st.notifyChanged();
            tick++;
            if (tick % 3 == 0 && wifiDirect != null) wifiDirect.refreshClients();
            if (tick % 2 == 0) updateNotification();
            handler.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ verrous

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MercureShare:partage");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "MercureShare:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        wakeLock = null;
        wifiLock = null;
    }

    // ------------------------------------------------------------------ notification

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void updateNotification() {
        if (proxy == null) return;
        ShareState st = ShareState.get();
        String text;
        if (st.wifiDirect == ShareState.WifiDirect.READY) {
            text = "Wi-Fi : " + st.ssid + " · " + st.proxyClients + " appareil(s) · ↓ " + NetUtils.formatRate(st.rateDown);
        } else {
            text = "Proxy " + NetUtils.primaryAddress(false) + ":" + st.port + " · " + st.proxyClients
                    + " appareil(s) · ↓ " + NetUtils.formatRate(st.rateDown);
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.notify(NOTIF_ID, buildNotification(text)); } catch (Exception ignored) { }
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Partage actif",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Indique que Mercure Share partage la connexion");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ShareService.class).setAction(ACTION_STOP), flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Mercure Share actif")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setColor(0xFF00E5FF)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(0, "Arrêter", stop)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }
}
