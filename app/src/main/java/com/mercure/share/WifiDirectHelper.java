package com.mercure.share;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Crée un réseau Wi-Fi Direct (le téléphone devient un mini point d'accès)
 * auquel tous les appareils peuvent se connecter avec un nom et un mot de passe.
 */
public class WifiDirectHelper {

    public interface Callback {
        void onGroupReady(String ssid, String passphrase);
        void onError(String message);
        void onClientsChanged(int count);
    }

    private static final int MAX_CREATE_ATTEMPTS = 4;
    private static final int MAX_INFO_POLLS = 30;

    private final Context ctx;
    private final Callback cb;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private boolean stopped;
    private boolean useCustomConfig = true;

    private String networkName;
    private String passphrase;
    private int band;

    public WifiDirectHelper(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    public void start(String networkName, String passphrase, int band) {
        this.networkName = networkName;
        this.passphrase = passphrase;
        this.band = band;
        stopped = false;

        WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && !wifi.isWifiEnabled()) {
            cb.onError("Le Wi-Fi est désactivé. Activez-le (inutile de vous connecter à un réseau) puis redémarrez le partage.");
            return;
        }
        manager = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            cb.onError("Ce téléphone ne prend pas en charge le Wi-Fi Direct.");
            return;
        }
        try {
            channel = manager.initialize(ctx, Looper.getMainLooper(), () -> {
                if (!stopped) cb.onError("Le Wi-Fi Direct a été interrompu par le système.");
            });
        } catch (Exception e) {
            cb.onError("Wi-Fi Direct indisponible : " + e.getMessage());
            return;
        }
        // Supprime un ancien groupe éventuel, puis crée le nôtre.
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { handler.postDelayed(() -> createGroup(0), 400); }
                @Override public void onFailure(int reason) { createGroup(0); }
            });
        } catch (SecurityException e) {
            cb.onError("Autorisation « Appareils à proximité » ou « Position » refusée. Accordez-la dans les paramètres de l'application.");
        } catch (Exception e) {
            createGroup(0);
        }
    }

    private void createGroup(final int attempt) {
        if (stopped) return;
        WifiP2pManager.ActionListener listener = new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                pollGroupInfo(0);
            }

            @Override
            public void onFailure(int reason) {
                if (stopped) return;
                if (reason == WifiP2pManager.BUSY && attempt < MAX_CREATE_ATTEMPTS) {
                    handler.postDelayed(() -> createGroup(attempt + 1), 1500);
                } else if (useCustomConfig && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Certains téléphones refusent un nom personnalisé : on réessaie avec le nom par défaut.
                    useCustomConfig = false;
                    handler.postDelayed(() -> createGroup(0), 800);
                } else {
                    cb.onError(reasonText(reason));
                }
            }
        };
        try {
            if (useCustomConfig && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiP2pConfig config;
                try {
                    config = new WifiP2pConfig.Builder()
                            .setNetworkName(networkName)
                            .setPassphrase(passphrase)
                            .setGroupOperatingBand(toBand(band))
                            .enablePersistentMode(false)
                            .build();
                } catch (IllegalArgumentException e) {
                    useCustomConfig = false;
                    manager.createGroup(channel, listener);
                    return;
                }
                manager.createGroup(channel, config, listener);
            } else {
                manager.createGroup(channel, listener);
            }
        } catch (SecurityException e) {
            cb.onError("Autorisation « Appareils à proximité » ou « Position » refusée. Accordez-la dans les paramètres de l'application.");
        } catch (Exception e) {
            cb.onError("Impossible de créer le réseau Wi-Fi Direct : " + e.getMessage());
        }
    }

    private static int toBand(int band) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0;
        if (band == Prefs.BAND_2GHZ) return WifiP2pConfig.GROUP_OWNER_BAND_2GHZ;
        if (band == Prefs.BAND_5GHZ) return WifiP2pConfig.GROUP_OWNER_BAND_5GHZ;
        return WifiP2pConfig.GROUP_OWNER_BAND_AUTO;
    }

    private void pollGroupInfo(final int n) {
        if (stopped) return;
        try {
            manager.requestGroupInfo(channel, group -> {
                if (stopped) return;
                if (group != null && group.getNetworkName() != null && group.getPassphrase() != null) {
                    cb.onGroupReady(group.getNetworkName(), group.getPassphrase());
                    cb.onClientsChanged(clientCount(group));
                } else if (n < MAX_INFO_POLLS) {
                    handler.postDelayed(() -> pollGroupInfo(n + 1), 500);
                } else {
                    cb.onError("Le réseau Wi-Fi Direct n'a pas pu démarrer (délai dépassé).");
                }
            });
        } catch (SecurityException e) {
            cb.onError("Autorisation refusée pour lire le réseau Wi-Fi Direct.");
        } catch (Exception e) {
            cb.onError("Erreur Wi-Fi Direct : " + e.getMessage());
        }
    }

    /** Met à jour le nombre d'appareils connectés au réseau Wi-Fi Direct. */
    public void refreshClients() {
        if (stopped || manager == null || channel == null) return;
        try {
            manager.requestGroupInfo(channel, group -> {
                if (!stopped) cb.onClientsChanged(clientCount(group));
            });
        } catch (Exception ignored) {
        }
    }

    private static int clientCount(WifiP2pGroup group) {
        if (group == null || group.getClientList() == null) return 0;
        return group.getClientList().size();
    }

    public void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        if (manager != null && channel != null) {
            try {
                manager.removeGroup(channel, null);
            } catch (Exception ignored) {
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
            }
        }
        channel = null;
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "Ce téléphone ne prend pas en charge le Wi-Fi Direct.";
            case WifiP2pManager.BUSY:
                return "Le Wi-Fi Direct est occupé. Désactivez puis réactivez le Wi-Fi et réessayez.";
            default:
                return "Le Wi-Fi Direct a refusé de démarrer. Vérifiez que le Wi-Fi et la Position sont activés, puis réessayez.";
        }
    }
}
