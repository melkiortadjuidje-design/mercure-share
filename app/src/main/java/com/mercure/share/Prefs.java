package com.mercure.share;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Réglages enregistrés de l'application. */
public final class Prefs {

    public static final String NAME_PREFIX = "DIRECT-MS-";
    public static final int BAND_AUTO = 0;
    public static final int BAND_2GHZ = 1;
    public static final int BAND_5GHZ = 2;

    private static final String FILE = "mercure_share";
    private static final String K_SUFFIX = "net_suffix";
    private static final String K_PASS = "net_pass";
    private static final String K_PORT = "port";
    private static final String K_BAND = "band";
    private static final String K_WIFI_DIRECT = "wifi_direct";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!sp.contains(K_PASS)) {
            sp.edit().putString(K_PASS, generatePassword()).apply();
        }
    }

    public String getSuffix() { return sp.getString(K_SUFFIX, "Mercure"); }
    public String getNetworkName() { return NAME_PREFIX + getSuffix(); }
    public String getPassword() { return sp.getString(K_PASS, "mercure2026"); }
    public int getPort() { return sp.getInt(K_PORT, 8080); }
    public int getBand() { return sp.getInt(K_BAND, BAND_2GHZ); }
    public boolean isWifiDirectEnabled() { return sp.getBoolean(K_WIFI_DIRECT, true); }

    public void save(String suffix, String pass, int port, int band, boolean wifiDirect) {
        sp.edit()
                .putString(K_SUFFIX, suffix)
                .putString(K_PASS, pass)
                .putInt(K_PORT, port)
                .putInt(K_BAND, band)
                .putBoolean(K_WIFI_DIRECT, wifiDirect)
                .apply();
    }

    /** Suffixe du nom Wi-Fi : lettres, chiffres, - et _, 1 à 20 caractères. */
    public static boolean isValidSuffix(String s) {
        return s != null && s.matches("[A-Za-z0-9_-]{1,20}");
    }

    /** Mot de passe WPA2 : 8 à 63 caractères ASCII imprimables. */
    public static boolean isValidPassword(String s) {
        return s != null && s.length() >= 8 && s.length() <= 63 && s.matches("[\\x20-\\x7E]+");
    }

    public static String generatePassword() {
        final String chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
