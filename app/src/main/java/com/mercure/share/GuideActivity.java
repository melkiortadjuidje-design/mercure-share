package com.mercure.share;

import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/** Instructions de connexion, appareil par appareil, avec l'adresse réelle du partage. */
public class GuideActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        ShareState st = ShareState.get();
        Prefs prefs = new Prefs(this);
        boolean wdReady = st.wifiDirect == ShareState.WifiDirect.READY;
        String ip = st.isActive() ? NetUtils.primaryAddress(wdReady) : NetUtils.WIFI_DIRECT_IP;
        String port = String.valueOf(st.isActive() ? st.port : prefs.getPort());
        String pac = "http://" + ip + ":" + port + "/proxy.pac";
        String ipB = "<b><font color='#00E5FF'>" + ip + "</font></b>";
        String portB = "<b><font color='#00E5FF'>" + port + "</font></b>";
        String pacB = "<b><font color='#00E5FF'>" + pac + "</font></b>";

        LinearLayout list = findViewById(R.id.guideList);

        addCard(list, "Comment ça marche",
                "1. Activez votre VPN sur ce téléphone (Mercure VPN ou un autre).<br>"
                + "2. Appuyez sur <b>DÉMARRER</b> dans Mercure Share.<br>"
                + "3. Sur l'autre appareil, connectez-vous au Wi-Fi affiché (nom + mot de passe, ou QR code).<br>"
                + "4. Réglez le proxy de cet appareil : serveur " + ipB + ", port " + portB + ".<br><br>"
                + "Aucune application n'est à installer sur les autres appareils. "
                + "Le même réglage fonctionne aussi avec le point d'accès classique ou le partage USB : "
                + "utilisez alors l'adresse indiquée dans l'application.");

        addCard(list, "Android (téléphone, tablette)",
                "1. Paramètres › Wi-Fi › connectez-vous au réseau <b>DIRECT-…</b><br>"
                + "2. Appuyez sur le réseau (ou sur ⚙ / crayon) › <b>Modifier</b> › <b>Options avancées</b><br>"
                + "3. Proxy : <b>Manuel</b><br>"
                + "4. Nom d'hôte du proxy : " + ipB + "<br>"
                + "5. Port du proxy : " + portB + " › Enregistrer<br><br>"
                + "Variante : Proxy <b>Config. auto (PAC)</b> avec l'URL " + pacB);

        addCard(list, "iPhone / iPad",
                "1. Réglages › Wi-Fi › connectez-vous au réseau <b>DIRECT-…</b><br>"
                + "2. Touchez <b>ⓘ</b> à droite du réseau<br>"
                + "3. Tout en bas : <b>Configurer le proxy</b> › <b>Manuel</b><br>"
                + "4. Serveur : " + ipB + " — Port : " + portB + "<br>"
                + "5. Authentification désactivée › <b>Enregistrer</b><br><br>"
                + "Astuce : scannez le QR code avec l'appareil photo pour rejoindre le Wi-Fi.");

        addCard(list, "Windows 10 / 11",
                "1. Connectez le PC au Wi-Fi <b>DIRECT-…</b><br>"
                + "2. Paramètres › Réseau et Internet › <b>Proxy</b><br>"
                + "3. Configuration manuelle du proxy › <b>Configurer</b> (ou Modifier)<br>"
                + "4. Activez « Utiliser un serveur proxy »<br>"
                + "5. Adresse IP du proxy : " + ipB + " — Port : " + portB + " › Enregistrer<br><br>"
                + "Pensez à désactiver ce proxy quand vous quittez ce Wi-Fi.");

        addCard(list, "Mac",
                "1. Connectez le Mac au Wi-Fi <b>DIRECT-…</b><br>"
                + "2. Réglages Système › Wi-Fi › <b>Détails…</b> à côté du réseau › <b>Proxys</b><br>"
                + "3. Activez <b>Proxy Web (HTTP)</b> et <b>Proxy Web sécurisé (HTTPS)</b><br>"
                + "4. Pour les deux : serveur " + ipB + ", port " + portB + " › OK");

        addCard(list, "Linux",
                "Paramètres › Réseau › Proxy réseau › <b>Manuel</b> : HTTP et HTTPS sur " + ipB + " port " + portB
                + ".<br>Ou en SOCKS5 sur la même adresse et le même port.");

        addCard(list, "Smart TV / Android TV / box",
                "1. Paramètres › Réseau › Wi-Fi › réseau <b>DIRECT-…</b><br>"
                + "2. Paramètres avancés / IP › <b>Proxy manuel</b><br>"
                + "3. Hôte : " + ipB + " — Port : " + portB + "<br><br>"
                + "Certains téléviseurs (Samsung, LG récents…) n'ont pas de réglage proxy : "
                + "ils ne peuvent pas utiliser ce type de partage.");

        addCard(list, "PlayStation 4 / 5",
                "1. Paramètres › Réseau › Configurer la connexion Internet › Wi-Fi › réseau <b>DIRECT-…</b><br>"
                + "2. Choisissez la configuration <b>personnalisée / avancée</b><br>"
                + "3. Adresse IP, DNS, MTU : <b>Automatique</b><br>"
                + "4. Serveur proxy : <b>Utiliser</b> › adresse " + ipB + ", port " + portB);

        addCard(list, "Ce qui marche, ce qui ne marche pas",
                "✅ Navigation web, réseaux sociaux, YouTube, Netflix, téléchargements, mises à jour, la plupart des applications.<br>"
                + "⚠️ Les appels vocaux/vidéo et les jeux en ligne qui utilisent l'UDP peuvent ne pas passer par le proxy.<br>"
                + "⚠️ La Xbox et certaines TV ne proposent pas de réglage proxy.<br>"
                + "ℹ️ Les noms de sites (DNS) sont résolus par le téléphone, donc à travers le VPN.");

        addCard(list, "Dépannage",
                "• <b>Pas d'internet sur l'appareil</b> : vérifiez le proxy (adresse " + ipB + ", port " + portB + ") "
                + "et que le partage est bien démarré.<br>"
                + "• <b>Le VPN n'est pas utilisé</b> : dans votre application VPN, n'excluez pas Mercure Share "
                + "(« tunnel fractionné » / « split tunneling »).<br>"
                + "• <b>Le réseau DIRECT-… n'apparaît pas</b> : dans Réglages, choisissez la bande 2,4 GHz, "
                + "puis arrêtez et relancez le partage.<br>"
                + "• <b>Le partage se coupe écran éteint</b> : Réglages › « Empêcher Android d'arrêter le partage ».<br>"
                + "• <b>Wi-Fi Direct indisponible</b> : utilisez le point d'accès classique du téléphone et l'adresse « Point d'accès » affichée.<br>"
                + "• <b>Page d'aide</b> : sur l'autre appareil, ouvrez <b>http://" + ip + ":" + port + "</b> dans le navigateur.");
    }

    private void addCard(LinearLayout parent, String title, String html) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ContextCompat.getColor(this, R.color.cyan));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(t);

        TextView body = new TextView(this);
        body.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
        body.setTextColor(ContextCompat.getColor(this, R.color.text));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setLineSpacing(dp(3), 1f);
        body.setTextIsSelectable(true);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        body.setLayoutParams(blp);
        card.addView(body);

        parent.addView(card);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
