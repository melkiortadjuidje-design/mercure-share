package com.mercure.share;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** État partagé entre le service de partage et les écrans. */
public final class ShareState {

    public enum Status { STOPPED, STARTING, RUNNING, ERROR }

    public enum WifiDirect { DISABLED, CREATING, READY, FAILED }

    public interface Listener {
        void onShareStateChanged(ShareState state);
    }

    private static final ShareState INSTANCE = new ShareState();

    public static ShareState get() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public volatile Status status = Status.STOPPED;
    public volatile String error;

    public volatile WifiDirect wifiDirect = WifiDirect.DISABLED;
    public volatile String wifiDirectError;
    public volatile String ssid;
    public volatile String passphrase;
    public volatile int wifiClients;

    public volatile int port;
    public volatile int proxyClients;
    public volatile int connections;
    public volatile long bytesUp;
    public volatile long bytesDown;
    public volatile long rateUp;
    public volatile long rateDown;
    public volatile long startedAt;
    public volatile List<String> clientIps = new ArrayList<>();

    private ShareState() { }

    public boolean isActive() {
        return status == Status.RUNNING || status == Status.STARTING;
    }

    public void reset() {
        status = Status.STOPPED;
        error = null;
        wifiDirect = WifiDirect.DISABLED;
        wifiDirectError = null;
        ssid = null;
        passphrase = null;
        wifiClients = 0;
        proxyClients = 0;
        connections = 0;
        bytesUp = 0;
        bytesDown = 0;
        rateUp = 0;
        rateDown = 0;
        startedAt = 0;
        clientIps = new ArrayList<>();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Prévient les écrans (toujours sur le fil principal). */
    public void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) l.onShareStateChanged(this);
        });
    }
}
