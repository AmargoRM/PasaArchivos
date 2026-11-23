package com.garua.pasaarchivos;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Servidor web mínimo dentro de la red local, protegido por código o por acceso guardado. */
final class WebServer {
    static final String APP = "PasaArchivos";
    static final int[] PORTS = { 8080, 8081, 8082, 8083 };
    private static final int MAX_FAILS = 5;
    private final Context ctx;
    private final String code;
    private final String host;      // "cel-xxxx.local"
    private final String label;     // "cel-xxxx"
    private final Map<String, Integer> fails = new HashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;
    private int port;

    WebServer(Context c, String localHost) {
        ctx = c.getApplicationContext();
        host = localHost;
        label = localHost.endsWith(".local") ? localHost.substring(0, localHost.length() - 6) : localHost;
        code = String.format("%04d", new SecureRandom().nextInt(10000));
        Pages.init(ctx);
    }

    String code() { return code; }
    int port() { return port; }

    void start() throws IOException {
        IOException last = null;
        for (int p : PORTS) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                server = ss; port = p; last = null; break;
            } catch (IOException e) { last = e; }
        }
        if (last != null) throw last;
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket s = server.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) { /* socket cerrado */ }
            }
        }, "pa-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            InputStream in = sock.getInputStream();
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 64 * 1024);
            String line = readLine(in);
            if (line == null) return;
            long clen = -1;
            String ua = "";
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c <= 0) continue;
                String key = h.substring(0, c).trim();
                if (key.equalsIgnoreCase("Content-Length")) {
                    try { clen = Long.parseLong(h.substring(c + 1).trim()); } catch (NumberFormatException ignored) {}
                } else if (key.equalsIgnoreCase("User-Agent")) {
                    ua = h.substring(c + 1).trim();
                }
            }
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            if (!method.equals("GET") && !method.equals("POST")) { text(out, 405, "Método no permitido."); out.flush(); return; }
            String path = parts[1], query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
            String ip = sock.getInetAddress().getHostAddress();
            route(method, path, query, clen, in, ip, ua, out);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private void route(String method, String path, String query, long clen, InputStream in, String ip, String ua,
                       OutputStream out) throws IOException {
        boolean post = method.equals("POST");
        if (!post) {
            if (path.equals("/") || path.isEmpty()) { html(out, 200, loginPage(null)); return; }
            if (path.equals("/ping")) { text(out, 200, host); return; }
            if (path.equals("/icon-192.png") || path.equals("/favicon.ico")) { asset(out, "icon-192.png"); return; }
            if (path.equals("/icon-512.png")) { asset(out, "icon-512.png"); return; }
            // Imagen que solo responde ESTE celular: el acceso de la PC la usa para encontrarlo.
            if (path.equals("/id/" + label + ".png")) { asset(out, "icon-192.png"); return; }
        }
        boolean byCode = path.startsWith("/c/");
        if (!byCode && !path.startsWith("/k/")) { text(out, 404, "No existe."); return; }
        String rest = path.substring(3);
        int slash = rest.indexOf('/');
        String given = slash < 0 ? rest : rest.substring(0, slash);
        String sub = slash < 0 ? "" : rest.substring(slash + 1);

        Chat.Pc pc;
        synchronized (fails) {
            Integer n = fails.get(ip);
            if (n != null && n >= MAX_FAILS) {
                html(out, 403, loginPage("Demasiados intentos fallidos. Detené PasaArchivos en el celular y abrilo de nuevo para obtener un código nuevo."));
                return;
            }
            pc = byCode ? null : Chat.byToken(given);
            boolean ok = byCode ? given.equals(code) : pc != null;
            if (!ok) {
                fails.put(ip, n == null ? 1 : n + 1);
                html(out, 403, loginPage(byCode ? "Código incorrecto."
                        : "Este acceso ya no es válido (quizá se quitó esta computadora en el celular). Escribí el código que muestra el celular."));
                return;
            }
            fails.remove(ip);
        }
        if (byCode) {
            if (post) { text(out, 405, "Método no permitido."); return; }
            Chat.Pc fresh = Chat.newPc(pcName(ua));
            fresh.lastSeen = System.currentTimeMillis();
            redirect(out, "/k/" + fresh.token + "/");
            return;
        }
        pc.lastSeen = System.currentTimeMillis();

        if (post) {
            if (sub.equals("up")) { receive(out, pc, query, clen, in); return; }
            if (sub.equals("tx")) { receiveText(out, pc, clen, in); return; }
            text(out, 405, "Método no permitido.");
            return;
        }
        if (slash < 0) { redirect(out, "/k/" + pc.token + "/"); return; }
        if (sub.isEmpty()) { html(out, 200, Pages.chat(pc.name, pc.token)); return; }
        if (sub.equals("m")) { sendMessages(out, pc, query); return; }
        if (sub.equals("acceso.html")) { sendLauncher(out, pc.token); return; }
        if (sub.equals("zip")) { sendZip(out, pc, query); return; }
        if (sub.startsWith("f/")) { sendFile(out, pc, parseId(sub.substring(2)), true); return; }
        if (sub.startsWith("i/")) { sendFile(out, pc, parseId(sub.substring(2)), false); return; }
        if (sub.startsWith("t/")) { sendThumb(out, pc, parseId(sub.substring(2))); return; }
        text(out, 404, "No existe.");
    }

    /** Nombre sugerido para una computadora nueva, según su navegador. */
    static String pcName(String ua) {
        String os = ua.contains("Windows") ? "Windows" : ua.contains("CrOS") ? "Chromebook" : ua.contains("Mac OS") ? "Mac"
                : ua.contains("Android") ? "Android" : ua.contains("Linux") ? "Linux" : "";
        String br = ua.contains("Edg/") ? "Edge" : ua.contains("Firefox/") ? "Firefox" : ua.contains("OPR/") ? "Opera"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "";
        String n = "PC" + (os.isEmpty() ? "" : " " + os) + (br.isEmpty() ? "" : " · " + br);
        return n;
    }

    private void sendMessages(OutputStream out, Chat.Pc pc, String query) throws IOException {
        long after = 0;
        for (String kv : query.split("&")) if (kv.startsWith("after=")) after = parseId(kv.substring(6));
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(jsonEsc(pc.name)).append("\",\"gen\":").append(Chat.gen()).append(",\"msgs\":[");
        boolean first = true;
        for (Chat.Msg m : Chat.after(pc.id, Math.max(after, 0))) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(m.id).append(",\"me\":").append(m.fromPc).append(",\"t\":").append(m.time);
            if (m.text != null) sb.append(",\"x\":\"").append(jsonEsc(m.text)).append('"');
            else sb.append(",\"n\":\"").append(jsonEsc(m.name)).append("\",\"s\":").append(m.size)
                   .append(",\"k\":\"").append(m.kind()).append("\",\"d\":").append(m.delivered);
            sb.append('}');
        }
        sb.append("]}");
        send(out, 200, "application/json; charset=utf-8", sb.toString());
    }

    // ---------- de la PC al celular ----------

    private void receive(OutputStream out, Chat.Pc pc, String query, long clen, InputStream in) throws IOException {
        if (clen < 0) { text(out, 411, "Falta el tamaño del archivo."); return; }
        String name = null;
        for (String kv : query.split("&")) {
            if (kv.startsWith("n=")) {
                try { name = URLDecoder.decode(kv.substring(2), "UTF-8"); } catch (Exception ignored) {}
            }
        }
        name = cleanName(name);
        try {
            final Chat.Msg[] holder = new Chat.Msg[1];
            Inbox.Rec r = Inbox.save(ctx, name, in, clen, u -> { if (holder[0] != null) Chat.setUri(holder[0], u); });
            Chat.Msg m = Chat.addFile(pc.id, true, r.name, r.size, r.mime, r.uri);
            holder[0] = m;
            if (m.uri == null && r.uri != null) Chat.setUri(m, r.uri);
            ShareService.incoming(ctx, pc, m);
            send(out, 200, "application/json; charset=utf-8", "{\"ok\":true,\"id\":" + m.id + "}");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "No se pudo guardar." : e.getMessage();
            send(out, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"" + jsonEsc(msg) + "\"}");
        }
    }

    private void receiveText(OutputStream out, Chat.Pc pc, long clen, InputStream in) throws IOException {
        if (clen < 0 || clen > 64 * 1024) { text(out, 413, "Mensaje demasiado largo."); return; }
        byte[] b = new byte[(int) clen];
        int off = 0, r;
        while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
        String t = new String(b, 0, off, StandardCharsets.UTF_8).trim();
        if (t.isEmpty()) { text(out, 400, "Mensaje vacío."); return; }
        Chat.Msg m = Chat.addText(pc.id, true, t);
        ShareService.incoming(ctx, pc, m);
        send(out, 200, "application/json; charset=utf-8", "{\"ok\":true,\"id\":" + m.id + "}");
    }

    static String cleanName(String n) {
        if (n == null) n = "";
        n = n.replace('/', '_').replace('\\', '_').replace(':', '_').replaceAll("[\\x00-\\x1f\"*?<>|]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isEmpty()) n = "archivo";
        if (n.length() > 120) {
            int d = n.lastIndexOf('.');
            String ext = (d > 0 && n.length() - d <= 10) ? n.substring(d) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n;
    }

    private static String jsonEsc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '<') sb.append("\\u003c");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    // ---------- del celular a la PC ----------

    private void asset(OutputStream out, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            copy(in, bo);
            byte[] d = bo.toByteArray();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " + d.length
                    + "\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(d);
        } catch (Exception e) { text(out, 404, ""); }
    }

    private static long parseId(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }

    static List<String> lanIps() {
        List<String> outIps = new ArrayList<>();
        try {
            for (NetworkInterface ni : LocalName.lanInterfaces()) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) outIps.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        return outIps;
    }

    /** Solo deja ver archivos de ESTA conversación. */
    private static Chat.Msg own(Chat.Pc pc, long id) {
        Chat.Msg m = Chat.msg(id);
        return m != null && m.pc.equals(pc.id) && m.isFile() ? m : null;
    }

    private void sendFile(OutputStream out, Chat.Pc pc, long id, boolean download) throws IOException {
        Chat.Msg it = own(pc, id);
        if (it == null) { text(out, 404, "Ese archivo ya no está en la conversación."); return; }
        InputStream src;
        try { src = it.uri == null ? null : ctx.getContentResolver().openInputStream(it.uri); }
        catch (Exception e) { src = null; }
        if (src == null) { text(out, 410, "El celular ya no puede leer este archivo. Volvé a enviarlo."); return; }
        try (InputStream in = src) {
            StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
            sb.append("Content-Type: ").append(it.mime == null ? "application/octet-stream" : it.mime).append("\r\n");
            if (it.size > 0) sb.append("Content-Length: ").append(it.size).append("\r\n");
            if (download) sb.append("Content-Disposition: attachment; filename*=UTF-8''").append(enc(it.name)).append("\r\n");
            sb.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            copy(in, out);
            out.flush();
            if (download && !it.fromPc) Chat.markDelivered(it);
        }
    }

    private void sendThumb(OutputStream out, Chat.Pc pc, long id) throws IOException {
        Chat.Msg it = own(pc, id);
        if (it == null || it.uri == null || !(it.isImage() || it.isVideo())) { text(out, 404, ""); return; }
        Bitmap bmp = Thumbs.load(ctx, it.uri, it.isImage(), 480);
        if (bmp == null) { text(out, 404, ""); return; }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bo);
        byte[] data = bo.toByteArray();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + data.length
                + "\r\nCache-Control: max-age=600\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
    }

    /** ?s=1,2,3 descarga esos; ?nuevos=1 descarga todo lo del celular que esta PC no bajó todavía. */
    private void sendZip(OutputStream out, Chat.Pc pc, String query) throws IOException {
        Set<Long> pick = null;
        boolean onlyNew = false;
        for (String kv : query.split("&")) {
            if (kv.equals("nuevos=1")) onlyNew = true;
            if (kv.startsWith("s=") && kv.length() > 2) {
                pick = new HashSet<>();
                String list;
                try { list = URLDecoder.decode(kv.substring(2), "UTF-8"); } catch (Exception e) { list = kv.substring(2); }
                for (String p : list.split(",")) { long v = parseId(p.trim()); if (v >= 0) pick.add(v); }
            }
        }
        List<Chat.Msg> chosen = new ArrayList<>();
        for (Chat.Msg m : Chat.messages(pc.id)) {
            if (!m.isFile() || m.uri == null) continue;
            if (pick != null ? pick.contains(m.id) : (!m.fromPc && (!onlyNew || !m.delivered))) chosen.add(m);
        }
        if (chosen.isEmpty()) { text(out, 404, "No hay archivos para descargar."); return; }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n"
                + "Content-Disposition: attachment; filename=\"PasaArchivos.zip\"\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        zip.setLevel(0); // fotos y videos ya vienen comprimidos
        Set<String> used = new HashSet<>();
        for (Chat.Msg it : chosen) {
            InputStream in;
            try { in = ctx.getContentResolver().openInputStream(it.uri); } catch (Exception e) { in = null; }
            if (in == null) continue;
            try (InputStream src = in) {
                zip.putNextEntry(new ZipEntry(unique(it.name, used)));
                copy(src, zip);
                zip.closeEntry();
            }
        }
        zip.finish();
        out.flush();
        for (Chat.Msg it : chosen) if (!it.fromPc) Chat.markDelivered(it);
    }

    private static String unique(String name, Set<String> used) {
        String n = name.replace('/', '_').replace('\\', '_');
        String base = n, ext = "";
        int dot = n.lastIndexOf('.');
        if (dot > 0) { base = n.substring(0, dot); ext = n.substring(dot); }
        int k = 1;
        while (!used.add(n)) n = base + " (" + (k++) + ")" + ext;
        return n;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    private static void text(OutputStream out, int status, String body) throws IOException {
        send(out, status, "text/plain; charset=utf-8", body);
    }
    private static void html(OutputStream out, int status, String body) throws IOException {
        send(out, status, "text/html; charset=utf-8", body);
    }
    private static void redirect(OutputStream out, String to) throws IOException {
        out.write(("HTTP/1.1 302 Found\r\nLocation: " + to + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }
    private static void send(OutputStream out, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(b);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
            if (bo.size() > 8192) return null;
        }
        if (c == -1 && bo.size() == 0) return null;
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return "archivo"; }
    }
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------- páginas ----------

    private String loginPage(String error) {
        return "<!doctype html><html lang=es>" + Pages.head("") + "<div class=box><img src=/icon-192.png alt=''><h2>" + APP + "</h2>"
            + (error != null ? "<p class=err>" + esc(error) + "</p>" : "")
            + "<p>Escribí el código de 4 dígitos que muestra el celular.</p>"
            + "<form onsubmit='return go()'><input id=k inputmode=numeric maxlength=4 autofocus autocomplete=off "
            + "oninput=\"if(this.value.trim().length==4)go()\"></form></div>"
            + "<script>function go(){var v=document.getElementById('k').value.trim();"
            + "if(v)location.href='/c/'+encodeURIComponent(v)+'/';return false}"
            + (error == null ? "try{var t=localStorage.getItem('pa-tok');if(t)location.replace('/k/'+t+'/')}catch(e){}" : "try{localStorage.removeItem('pa-tok')}catch(e){}")
            + "</script></html>";
    }

    // ---------- acceso para la PC ----------

    private void sendLauncher(OutputStream out, String tok) throws IOException {
        String icon = "";
        try (InputStream in = ctx.getAssets().open("icon-192.png")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            copy(in, bo);
            icon = "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {}
        StringBuilder ips = new StringBuilder();
        for (String ip : lanIps()) { if (ips.length() > 0) ips.append(','); ips.append('"').append(ip).append('"'); }
        byte[] b = Pages.launcher(icon, label, tok, ips.toString()).getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nContent-Disposition: attachment; filename=\"PasaArchivos.html\""
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
    }
}
