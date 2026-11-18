package com.mercure.share.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serveur proxy de Mercure Share.
 *
 * Un seul port accepte trois protocoles, détectés automatiquement :
 *  - HTTP / HTTPS (méthode CONNECT)  -> navigateurs, iPhone, Windows, Mac, TV, consoles
 *  - SOCKS5 (sans mot de passe)      -> applications avancées
 *  - SOCKS4 / SOCKS4a
 *
 * Le trafic sortant est émis par le téléphone : s'il a un VPN actif,
 * tout passe par ce VPN (DNS compris, car les noms sont résolus ici).
 *
 * Pages locales servies sur le même port :
 *  - http://IP:PORT/           page d'aide
 *  - http://IP:PORT/proxy.pac  configuration automatique du proxy
 *
 * Cette classe n'utilise aucune API Android : elle est testable sur PC.
 */
public class ProxyServer {

    public interface Listener {
        void onLog(String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int HEADER_TIMEOUT_MS = 30000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final long CLIENT_ACTIVE_WINDOW_MS = 2 * 60 * 1000;

    private final int port;
    private final Listener listener;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private ExecutorService pool;
    private Thread acceptThread;

    private final AtomicLong bytesUp = new AtomicLong();     // appareils -> internet
    private final AtomicLong bytesDown = new AtomicLong();   // internet -> appareils
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong totalConnections = new AtomicLong();
    private final Map<String, Long> clientsLastSeen = new ConcurrentHashMap<>();
    private final List<Socket> openSockets = new ArrayList<>();

    public ProxyServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ cycle de vie

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress((InetAddress) null, port), 128); // toutes les interfaces
        serverSocket = ss;
        running = true;
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ms-proxy-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        acceptThread = new Thread(this::acceptLoop, "ms-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log("Proxy démarré sur le port " + port);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        closeQuietly(serverSocket);
        synchronized (openSockets) {
            for (Socket s : openSockets) closeQuietly(s);
            openSockets.clear();
        }
        if (pool != null) pool.shutdownNow();
        activeConnections.set(0);
        clientsLastSeen.clear();
        log("Proxy arrêté");
    }

    public boolean isRunning() { return running; }
    public int getPort() { return port; }
    public long getBytesUp() { return bytesUp.get(); }
    public long getBytesDown() { return bytesDown.get(); }
    public int getActiveConnections() { return Math.max(0, activeConnections.get()); }
    public long getTotalConnections() { return totalConnections.get(); }

    /** Nombre d'appareils ayant utilisé le proxy ces 2 dernières minutes. */
    public int getActiveClientCount() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = clientsLastSeen.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > CLIENT_ACTIVE_WINDOW_MS) it.remove();
        }
        return clientsLastSeen.size();
    }

    public List<String> getActiveClients() {
        getActiveClientCount();
        return new ArrayList<>(clientsLastSeen.keySet());
    }

    // ------------------------------------------------------------------ acceptation

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);
                track(client);
                pool.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running) log("Erreur d'acceptation : " + e.getMessage());
            } catch (Exception e) {
                if (running) log("Erreur : " + e);
            }
        }
    }

    private void handleClient(Socket client) {
        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
        String clientIp = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "?";
        if (!client.getInetAddress().isLoopbackAddress()) {
            clientsLastSeen.put(clientIp, System.currentTimeMillis());
        }
        try {
            client.setSoTimeout(HEADER_TIMEOUT_MS);
            InputStream in = client.getInputStream();
            int first = in.read();
            if (first == -1) return;
            if (first == 0x05) {
                handleSocks5(client, in);
            } else if (first == 0x04) {
                handleSocks4(client, in);
            } else {
                handleHttp(client, in, first);
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException ignored) {
        } catch (Exception e) {
            log("Erreur client " + clientIp + " : " + e);
        } finally {
            closeQuietly(client);
            untrack(client);
            activeConnections.decrementAndGet();
        }
    }

    // ------------------------------------------------------------------ HTTP

    private void handleHttp(Socket client, InputStream in, int firstByte) throws IOException {
        byte[] head = readHeaders(in, firstByte);
        if (head == null) return;
        String headStr = new String(head, StandardCharsets.ISO_8859_1);
        int lineEnd = headStr.indexOf("\r\n");
        if (lineEnd < 0) return;
        String requestLine = headStr.substring(0, lineEnd);
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) {
            sendSimpleResponse(client, 400, "Bad Request", "Requête invalide");
            return;
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        String version = parts[2];
        String headerBlock = headStr.substring(lineEnd + 2); // en-têtes + \r\n final

        if (method.equals("CONNECT")) {
            HostPort hp = HostPort.parse(target, 443);
            if (hp == null) { sendSimpleResponse(client, 400, "Bad Request", "Cible invalide"); return; }
            if (isSelf(client, hp.host, hp.port)) { sendSimpleResponse(client, 400, "Bad Request", "Boucle refusée"); return; }
            Socket remote;
            try {
                remote = connectRemote(hp.host, hp.port);
            } catch (IOException e) {
                sendSimpleResponse(client, 502, "Bad Gateway", "Impossible de joindre " + hp.host);
                return;
            }
            OutputStream out = client.getOutputStream();
            out.write("HTTP/1.1 200 Connection established\r\nProxy-Agent: MercureShare\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            relay(client, in, remote);
            return;
        }

        // Requête "origin-form" (GET /...) : adressée au proxy lui-même -> pages locales
        if (target.startsWith("/")) {
            serveLocal(client, target);
            return;
        }

        // Requête HTTP classique via proxy : "GET http://hote:port/chemin HTTP/1.1"
        String lower = target.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://")) {
            sendSimpleResponse(client, 400, "Bad Request", "Seul http:// est accepté ici (https passe par CONNECT)");
            return;
        }
        String rest = target.substring(7);
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "/";
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        HostPort hp = HostPort.parse(authority, 80);
        if (hp == null) { sendSimpleResponse(client, 400, "Bad Request", "Hôte invalide"); return; }
        if (isSelf(client, hp.host, hp.port)) {
            serveLocal(client, path);
            return;
        }

        // Réécriture des en-têtes : on retire ceux propres au proxy et on force la fermeture,
        // pour qu'une connexion du client ne serve qu'à un seul site.
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n");
        boolean hasHost = false;
        for (String line : headerBlock.split("\r\n")) {
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (name.equals("proxy-connection") || name.equals("proxy-authorization")
                    || name.equals("connection") || name.equals("keep-alive")) continue;
            if (name.equals("host")) hasHost = true;
            sb.append(line).append("\r\n");
        }
        if (!hasHost) sb.append("Host: ").append(authority).append("\r\n");
        sb.append("Connection: close\r\n\r\n");

        Socket remote;
        try {
            remote = connectRemote(hp.host, hp.port);
        } catch (IOException e) {
            sendSimpleResponse(client, 502, "Bad Gateway", "Impossible de joindre " + hp.host);
            return;
        }
        byte[] newHead = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        OutputStream rout = remote.getOutputStream();
        rout.write(newHead);
        rout.flush();
        bytesUp.addAndGet(newHead.length);
        relay(client, in, remote);
    }

    /** Lit les en-têtes HTTP jusqu'à la ligne vide. */
    private byte[] readHeaders(InputStream in, int firstByte) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        buf.write(firstByte);
        int state = 0; // détection de \r\n\r\n
        int prev = firstByte;
        while (true) {
            int b = in.read();
            if (b == -1) return null;
            buf.write(b);
            if (buf.size() > MAX_HEADER_BYTES) return null;
            if (b == '\n') {
                // accepte \r\n\r\n et \n\n
                if (state == 1 || prev == '\n') break;
                state = 1;
            } else if (b != '\r') {
                state = 0;
            }
            prev = b;
        }
        return buf.toByteArray();
    }

    // ------------------------------------------------------------------ SOCKS5

    private void handleSocks5(Socket client, InputStream in) throws IOException {
        OutputStream out = client.getOutputStream();
        int nMethods = in.read();
        if (nMethods < 0) return;
        byte[] methods = readFully(in, nMethods);
        boolean noAuth = false;
        for (byte m : methods) if (m == 0x00) noAuth = true;
        if (!noAuth) {
            out.write(new byte[]{0x05, (byte) 0xFF});
            out.flush();
            return;
        }
        out.write(new byte[]{0x05, 0x00});
        out.flush();

        byte[] req = readFully(in, 4); // VER CMD RSV ATYP
        if (req[0] != 0x05) return;
        int cmd = req[1];
        int atyp = req[3];
        String host;
        if (atyp == 0x01) {
            byte[] a = readFully(in, 4);
            host = (a[0] & 0xFF) + "." + (a[1] & 0xFF) + "." + (a[2] & 0xFF) + "." + (a[3] & 0xFF);
        } else if (atyp == 0x03) {
            int len = in.read();
            if (len < 0) return;
            host = new String(readFully(in, len), StandardCharsets.ISO_8859_1);
        } else if (atyp == 0x04) {
            host = InetAddress.getByAddress(readFully(in, 16)).getHostAddress();
        } else {
            socks5Reply(out, 0x08);
            return;
        }
        byte[] p = readFully(in, 2);
        int port = ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);

        if (cmd != 0x01) { // seul CONNECT est géré
            socks5Reply(out, 0x07);
            return;
        }
        if (isSelf(client, host, port)) { socks5Reply(out, 0x02); return; }
        Socket remote;
        try {
            remote = connectRemote(host, port);
        } catch (IOException e) {
            socks5Reply(out, 0x05);
            return;
        }
        socks5Reply(out, 0x00);
        relay(client, in, remote);
    }

    private void socks5Reply(OutputStream out, int code) throws IOException {
        out.write(new byte[]{0x05, (byte) code, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    // ------------------------------------------------------------------ SOCKS4 / 4a

    private void handleSocks4(Socket client, InputStream in) throws IOException {
        OutputStream out = client.getOutputStream();
        byte[] hdr = readFully(in, 7); // CMD PORT(2) IP(4)
        int cmd = hdr[0];
        int port = ((hdr[1] & 0xFF) << 8) | (hdr[2] & 0xFF);
        String host = (hdr[3] & 0xFF) + "." + (hdr[4] & 0xFF) + "." + (hdr[5] & 0xFF) + "." + (hdr[6] & 0xFF);
        readNullTerminated(in); // identifiant utilisateur, ignoré
        if ((hdr[3] & 0xFF) == 0 && (hdr[4] & 0xFF) == 0 && (hdr[5] & 0xFF) == 0 && (hdr[6] & 0xFF) != 0) {
            host = readNullTerminated(in); // SOCKS4a : nom de domaine
        }
        if (cmd != 0x01 || isSelf(client, host, port)) {
            out.write(new byte[]{0x00, 0x5B, 0, 0, 0, 0, 0, 0});
            out.flush();
            return;
        }
        Socket remote;
        try {
            remote = connectRemote(host, port);
        } catch (IOException e) {
            out.write(new byte[]{0x00, 0x5B, 0, 0, 0, 0, 0, 0});
            out.flush();
            return;
        }
        out.write(new byte[]{0x00, 0x5A, 0, 0, 0, 0, 0, 0});
        out.flush();
        relay(client, in, remote);
    }

    private String readNullTerminated(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) > 0) {
            b.write(c);
            if (b.size() > 255) break;
        }
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------ relais

    private Socket connectRemote(String host, int port) throws IOException {
        Socket remote = new Socket();
        remote.setTcpNoDelay(true);
        remote.setKeepAlive(true);
        // La résolution DNS se fait sur le téléphone, donc à travers le VPN s'il est actif.
        remote.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        track(remote);
        return remote;
    }

    /** Relaie les données dans les deux sens jusqu'à fermeture d'un côté. */
    private void relay(Socket client, InputStream clientIn, Socket remote) {
        try {
            client.setSoTimeout(0);
            remote.setSoTimeout(0);
            final InputStream remoteIn = remote.getInputStream();
            final OutputStream remoteOut = remote.getOutputStream();
            final OutputStream clientOut = client.getOutputStream();

            Thread down = new Thread(() -> {
                pipe(remoteIn, clientOut, bytesDown);
                // Le site a fermé : on ferme tout (les données déjà envoyées sont livrées).
                closeQuietly(client);
                closeQuietly(remote);
            }, "ms-relay-down");
            down.setDaemon(true);
            down.start();

            pipe(clientIn, remoteOut, bytesUp);
            // L'appareil a fini d'envoyer : on laisse au site le temps de répondre.
            shutdownOutput(remote);
            try { down.join(60000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(remote);
            untrack(remote);
        }
    }

    private void pipe(InputStream in, OutputStream out, AtomicLong counter) {
        byte[] buf = new byte[BUFFER_SIZE];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
                counter.addAndGet(n);
            }
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ pages locales

    private void serveLocal(Socket client, String path) throws IOException {
        String localIp = client.getLocalAddress().getHostAddress();
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.equals("/proxy.pac") || path.equals("/wpad.dat")) {
            String pac = buildPac(localIp, port);
            sendBody(client, 200, "OK", "application/x-ns-proxy-autoconfig", pac);
        } else if (path.equals("/") || path.equals("/index.html")) {
            sendBody(client, 200, "OK", "text/html; charset=utf-8", buildHelpPage(localIp, port));
        } else if (path.equals("/status")) {
            String json = "{\"running\":true,\"clients\":" + getActiveClientCount()
                    + ",\"connections\":" + getActiveConnections()
                    + ",\"up\":" + getBytesUp() + ",\"down\":" + getBytesDown() + "}";
            sendBody(client, 200, "OK", "application/json", json);
        } else {
            sendSimpleResponse(client, 404, "Not Found", "Page introuvable");
        }
    }

    public static String buildPac(String ip, int port) {
        return "function FindProxyForURL(url, host) {\n"
                + "  if (isPlainHostName(host) || host == \"localhost\" || host == \"" + ip + "\" ||\n"
                + "      shExpMatch(host, \"127.*\") || shExpMatch(host, \"192.168.49.*\"))\n"
                + "    return \"DIRECT\";\n"
                + "  return \"PROXY " + ip + ":" + port + "; SOCKS5 " + ip + ":" + port + "; SOCKS " + ip + ":" + port + "\";\n"
                + "}\n";
    }

    private String buildHelpPage(String ip, int port) {
        return "<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Mercure Share</title><style>"
                + "body{margin:0;font-family:system-ui,sans-serif;background:#0A0E1A;color:#E6EAF2;padding:20px}"
                + "h1{color:#00E5FF;font-size:22px;letter-spacing:2px}"
                + ".c{background:#121829;border:1px solid #1F2A44;border-radius:14px;padding:16px;margin:12px 0}"
                + "b{color:#00E5FF}code{background:#0A0E1A;padding:2px 6px;border-radius:6px;color:#7CFFB2}"
                + "li{margin:6px 0}</style></head><body>"
                + "<h1>MERCURE SHARE</h1>"
                + "<div class=\"c\">Serveur proxy : <b>" + ip + "</b><br>Port : <b>" + port + "</b><br>"
                + "Configuration automatique : <code>http://" + ip + ":" + port + "/proxy.pac</code></div>"
                + "<div class=\"c\"><b>iPhone / iPad</b><ol><li>Réglages &gt; Wi-Fi &gt; touchez (i) à côté du réseau</li>"
                + "<li>Configurer le proxy &gt; Manuel</li><li>Serveur <code>" + ip + "</code>, Port <code>" + port + "</code></li></ol></div>"
                + "<div class=\"c\"><b>Android</b><ol><li>Paramètres &gt; Wi-Fi &gt; réseau connecté &gt; Modifier (crayon)</li>"
                + "<li>Options avancées &gt; Proxy &gt; Manuel</li><li>Nom d'hôte <code>" + ip + "</code>, Port <code>" + port + "</code></li></ol></div>"
                + "<div class=\"c\"><b>Windows 10 / 11</b><ol><li>Paramètres &gt; Réseau et Internet &gt; Proxy</li>"
                + "<li>Configuration manuelle &gt; Modifier &gt; Activer</li><li>Adresse <code>" + ip + "</code>, Port <code>" + port + "</code> &gt; Enregistrer</li></ol></div>"
                + "<div class=\"c\"><b>Mac</b><ol><li>Réglages Système &gt; Wi-Fi &gt; Détails &gt; Proxys</li>"
                + "<li>Activez Proxy Web (HTTP) et Proxy Web sécurisé (HTTPS)</li><li>Serveur <code>" + ip + "</code>, Port <code>" + port + "</code></li></ol></div>"
                + "<div class=\"c\"><b>TV / PlayStation</b><br>Paramètres réseau &gt; configuration manuelle &gt; Serveur proxy : "
                + "<code>" + ip + "</code> port <code>" + port + "</code></div>"
                + "</body></html>";
    }

    private void sendSimpleResponse(Socket client, int code, String reason, String message) throws IOException {
        String html = "<html><body style=\"font-family:sans-serif;background:#0A0E1A;color:#E6EAF2\">"
                + "<h3 style=\"color:#00E5FF\">Mercure Share</h3><p>" + message + "</p></body></html>";
        sendBody(client, code, reason, "text/html; charset=utf-8", html);
    }

    private void sendBody(Socket client, int code, String reason, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    // ------------------------------------------------------------------ utilitaires

    /** Empêche un appareil de faire boucler le proxy sur lui-même. */
    private boolean isSelf(Socket client, String host, int targetPort) {
        if (targetPort != port) return false;
        String local = client.getLocalAddress().getHostAddress();
        return host.equals(local) || host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost")
                || host.equals("0.0.0.0");
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new IOException("Fin de flux");
            off += n;
        }
        return b;
    }

    private void track(Socket s) {
        synchronized (openSockets) { openSockets.add(s); }
    }

    private void untrack(Socket s) {
        synchronized (openSockets) { openSockets.remove(s); }
    }

    private static void shutdownOutput(Socket s) {
        try { if (!s.isClosed() && !s.isOutputShutdown()) s.shutdownOutput(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }

    private void log(String msg) {
        if (listener != null) {
            try { listener.onLog(msg); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ hôte:port

    static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) { this.host = host; this.port = port; }

        static HostPort parse(String s, int defaultPort) {
            if (s == null || s.isEmpty()) return null;
            String host;
            int port = defaultPort;
            if (s.startsWith("[")) { // IPv6 [::1]:443
                int end = s.indexOf(']');
                if (end < 0) return null;
                host = s.substring(1, end);
                if (end + 1 < s.length() && s.charAt(end + 1) == ':') {
                    port = parsePort(s.substring(end + 2), defaultPort);
                }
            } else {
                int colon = s.lastIndexOf(':');
                if (colon > 0 && s.indexOf(':') == colon) {
                    host = s.substring(0, colon);
                    port = parsePort(s.substring(colon + 1), defaultPort);
                } else {
                    host = s;
                }
            }
            if (host.isEmpty() || port <= 0 || port > 65535) return null;
            return new HostPort(host, port);
        }

        private static int parsePort(String p, int def) {
            try { return Integer.parseInt(p.trim()); } catch (NumberFormatException e) { return -1; }
        }
    }
}
