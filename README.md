# Mercure Share

Application Android qui partage la connexion du téléphone, **y compris son VPN**, avec tous vos appareils
(PC, Mac, iPhone, tablette, TV, PlayStation…), sans rien installer sur ces appareils.

## Fonctionnement
1. Le téléphone crée un réseau **Wi-Fi Direct** (`DIRECT-MS-…`) avec mot de passe et QR code.
2. Il lance un **proxy** (HTTP, HTTPS, SOCKS5 et SOCKS4 sur un seul port, 8080 par défaut).
3. Les appareils rejoignent ce Wi-Fi et règlent le proxy `192.168.49.1:8080`.
4. Tout leur trafic sort par le téléphone, donc par le VPN actif (DNS compris).

Fonctionne aussi avec le point d'accès classique ou le partage USB (l'adresse s'affiche dans l'app).

## Compiler l'APK avec GitHub (depuis le téléphone)
1. Créez un dépôt **privé** `mercure-share` sur GitHub.
2. Envoyez-y tout le contenu de ce dossier (le dossier `.github` compris).
3. Onglet **Actions** › « Compiler l'APK Mercure Share » : la compilation démarre seule (≈ 5 min).
4. Ouvrez la compilation terminée › **Artifacts** › `MercureShare-APK` › décompressez › installez `MercureShare.apk`.

## Important
- Gardez le dépôt **privé** : il contient la clé de signature (`app/mercure-share.jks`).
  Gardez aussi ce fichier : sans lui, les mises à jour ne pourront pas s'installer par-dessus l'ancienne version.
- Android 8.0 minimum. Nom de réseau personnalisé à partir d'Android 10.
- Limites : les appels et jeux en UDP peuvent ne pas passer ; la Xbox et certaines TV n'ont pas de réglage proxy.
