package com.mercure.share;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Outils réseau : VPN actif, adresses locales, formatage. */
public final class NetUtils {

    public static final String WIFI_DIRECT_IP = "192.168.49.1";

    public static final class LocalAddress {
        public final String label;
        public final String ip;
        public final String iface;

        LocalAddress(String label, String ip, String iface) {
            this.label = label;
            this.ip = ip;
            this.iface = iface;
        }
    }

    private NetUtils() { }

    /** Vrai si un VPN (n'importe quelle application) est actif sur le téléphone. */
    public static boolean isVpnActive(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Adresses IPv4 du téléphone sur lesquelles les appareils peuvent joindre le proxy. */
    public static List<LocalAddress> getLocalAddresses() {
        List<LocalAddress> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase(Locale.ROOT);
                if (isHidden(name)) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        list.add(new LocalAddress(labelFor(name), a.getHostAddress(), ni.getName()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // Wi-Fi Direct en premier
        Collections.sort(list, (x, y) -> Integer.compare(rank(x.iface), rank(y.iface)));
        return list;
    }

    private static boolean isHidden(String n) {
        return n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("rmnet") || n.startsWith("ccmni")
                || n.startsWith("dummy") || n.startsWith("ipsec") || n.startsWith("clat") || n.startsWith("v4-")
                || n.startsWith("r_rmnet") || n.startsWith("seth") || n.startsWith("ifb") || n.startsWith("ip6")
                || n.startsWith("sit") || n.startsWith("lo");
    }

    private static int rank(String iface) {
        String n = iface.toLowerCase(Locale.ROOT);
        if (n.startsWith("p2p")) return 0;
        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("wlan1")) return 1;
        if (n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")) return 2;
        if (n.startsWith("bt")) return 3;
        return 4;
    }

    private static String labelFor(String n) {
        if (n.startsWith("p2p")) return "Wi-Fi Direct";
        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("wlan1")) return "Point d'accès";
        if (n.startsWith("wlan")) return "Wi-Fi";
        if (n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")) return "Partage USB";
        if (n.startsWith("bt")) return "Bluetooth";
        if (n.startsWith("eth")) return "Ethernet";
        return n;
    }

    /** Meilleure adresse à afficher en grand. */
    public static String primaryAddress(boolean wifiDirectReady) {
        List<LocalAddress> list = getLocalAddresses();
        if (wifiDirectReady) {
            for (LocalAddress a : list) if (a.iface.toLowerCase(Locale.ROOT).startsWith("p2p")) return a.ip;
            return WIFI_DIRECT_IP;
        }
        for (LocalAddress a : list) if (rank(a.iface) <= 2) return a.ip;
        if (!list.isEmpty()) return list.get(0).ip;
        return WIFI_DIRECT_IP;
    }

    public static String formatBytes(long b) {
        if (b < 1024) return b + " o";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format(Locale.FRANCE, "%.1f Ko", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.FRANCE, "%.1f Mo", mb);
        return String.format(Locale.FRANCE, "%.2f Go", mb / 1024.0);
    }

    public static String formatRate(long bytesPerSecond) {
        return formatBytes(bytesPerSecond) + "/s";
    }

    public static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.FRANCE, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
