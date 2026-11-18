package com.mercure.share;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

/** Génère les QR codes (connexion Wi-Fi en un scan). */
public final class QrUtils {

    private QrUtils() { }

    /** Texte standard reconnu par l'appareil photo d'Android et d'iPhone. */
    public static String wifiPayload(String ssid, String password) {
        return "WIFI:T:WPA;S:" + escape(ssid) + ";P:" + escape(password) + ";;";
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    public static Bitmap generate(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth();
            int h = m.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
