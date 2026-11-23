package com.garua.pasaarchivos;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Conversaciones con cada computadora. Cada PC tiene su propio acceso (token) y su historial.
 * Se guarda en el almacenamiento privado de la app y sobrevive a reinicios.
 */
final class Chat {
    private static final int KEEP = 400;   // mensajes que se conservan por computadora

    static final class Pc {
        final String id; String token; String name; final long created;
        volatile long lastSeen; int unread;
        Pc(String id, String token, String name, long created) { this.id = id; this.token = token; this.name = name; this.created = created; }
        boolean online() { return lastSeen > 0 && System.currentTimeMillis() - lastSeen < 8000; }
    }

    static final class Msg {
        final long id; final String pc; final boolean fromPc; final long time;
        final String text;                  // mensaje de texto, o null si es archivo
        final String name; final long size; final String mime; Uri uri;
        boolean delivered;                  // la PC ya lo descargó (si es del celular)
        Msg(long id, String pc, boolean fromPc, long time, String text, String name, long size, String mime, Uri uri) {
            this.id = id; this.pc = pc; this.fromPc = fromPc; this.time = time; this.text = text;
            this.name = name; this.size = size; this.mime = mime; this.uri = uri;
        }
        boolean isFile() { return text == null; }
        boolean isImage() { return mime != null && mime.startsWith("image/"); }
        boolean isVideo() { return mime != null && mime.startsWith("video/"); }
        String kind() { return isFile() ? (isImage() ? "img" : isVideo() ? "vid" : "doc") : "txt"; }
    }

    private static Context ctx;
    private static final List<Pc> pcs = new ArrayList<>();
    private static final List<Msg> msgs = new ArrayList<>();
    private static long nextId = 1;
    private static int version = 0;
    private static int gen = 0;            // cambia cuando se borra algo (la página de la PC recarga todo)

    static synchronized void init(Context c) {
        if (ctx != null) return;
        ctx = c.getApplicationContext();
        load();
    }

    static synchronized int version() { return version; }
    static synchronized int gen() { return gen; }
    private static void changed() { version++; save(); }

    // ---------- computadoras ----------

    static synchronized List<Pc> pcs() {
        List<Pc> out = new ArrayList<>(pcs);
        out.sort((a, b) -> Long.compare(lastActivity(b), lastActivity(a)));
        return out;
    }

    private static long lastActivity(Pc p) {
        long t = p.created;
        for (int i = msgs.size() - 1; i >= 0; i--) if (msgs.get(i).pc.equals(p.id)) { t = Math.max(t, msgs.get(i).time); break; }
        return t;
    }

    static synchronized Pc byToken(String token) {
        for (Pc p : pcs) if (p.token.equals(token)) return p;
        return null;
    }

    static synchronized Pc byId(String id) {
        for (Pc p : pcs) if (p.id.equals(id)) return p;
        return null;
    }

    static synchronized Pc newPc(String suggestedName) {
        String name = suggestedName == null || suggestedName.isEmpty() ? "Computadora" : suggestedName;
        int n = 1;
        String base = name;
        while (nameTaken(name)) name = base + " " + (++n);
        Pc p = new Pc(rand(8), rand(22), name, System.currentTimeMillis());
        pcs.add(p);
        changed();
        return p;
    }

    private static boolean nameTaken(String n) {
        for (Pc p : pcs) if (p.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    static synchronized void rename(String id, String name) {
        Pc p = byId(id);
        if (p != null && name != null && !name.trim().isEmpty()) { p.name = name.trim(); changed(); }
    }

    /** Quita la computadora, su historial y su acceso. Los archivos recibidos siguen en Descargas. */
    static synchronized void remove(String id) {
        pcs.removeIf(p -> p.id.equals(id));
        msgs.removeIf(m -> m.pc.equals(id));
        changed();
    }

    static synchronized void markRead(String id) {
        Pc p = byId(id);
        if (p != null && p.unread != 0) { p.unread = 0; changed(); }
    }

    static synchronized int totalUnread() {
        int n = 0;
        for (Pc p : pcs) n += p.unread;
        return n;
    }

    // ---------- mensajes ----------

    static synchronized List<Msg> messages(String pcId) {
        List<Msg> out = new ArrayList<>();
        for (Msg m : msgs) if (m.pc.equals(pcId)) out.add(m);
        return out;
    }

    static synchronized List<Msg> after(String pcId, long afterId) {
        List<Msg> out = new ArrayList<>();
        for (Msg m : msgs) if (m.pc.equals(pcId) && m.id > afterId) out.add(m);
        return out;
    }

    static synchronized Msg msg(long id) {
        for (Msg m : msgs) if (m.id == id) return m;
        return null;
    }

    static synchronized Msg last(String pcId) {
        for (int i = msgs.size() - 1; i >= 0; i--) if (msgs.get(i).pc.equals(pcId)) return msgs.get(i);
        return null;
    }

    static synchronized Msg addText(String pcId, boolean fromPc, String text) {
        Msg m = new Msg(nextId++, pcId, fromPc, System.currentTimeMillis(), text, null, 0, null, null);
        return push(m);
    }

    static synchronized Msg addFile(String pcId, boolean fromPc, String name, long size, String mime, Uri uri) {
        if (!fromPc) {
            for (Msg x : msgs) if (x.pc.equals(pcId) && !x.fromPc && uri.equals(x.uri) && !x.delivered) return x;
        }
        Msg m = new Msg(nextId++, pcId, fromPc, System.currentTimeMillis(), null, name, size, mime, uri);
        return push(m);
    }

    private static Msg push(Msg m) {
        msgs.add(m);
        if (m.fromPc) { Pc p = byId(m.pc); if (p != null) p.unread++; }
        int count = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).pc.equals(m.pc) && ++count > KEEP) msgs.remove(i);
        }
        changed();
        return m;
    }

    static synchronized void markDelivered(Msg m) {
        if (!m.delivered) { m.delivered = true; changed(); }
    }

    static synchronized void setUri(Msg m, Uri u) { m.uri = u; changed(); }

    // ---------- borrar ----------

    /** Borra un mensaje. Con {@code alsoFile}, si lo mandó la PC, borra también el archivo de Descargas. */
    static synchronized void removeMsg(long id, boolean alsoFile) {
        for (int i = 0; i < msgs.size(); i++) {
            Msg m = msgs.get(i);
            if (m.id != id) continue;
            if (alsoFile) deleteReceived(m);
            msgs.remove(i);
            gen++;
            changed();
            return;
        }
    }

    static synchronized void clearPc(String pcId, boolean alsoFiles) {
        boolean any = false;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (!m.pc.equals(pcId)) continue;
            if (alsoFiles) deleteReceived(m);
            msgs.remove(i);
            any = true;
        }
        if (any) { gen++; changed(); }
    }

    /** Borra lo que tenga más de {@code maxAgeMs}. Devuelve cuántos mensajes quitó. */
    static synchronized int purge(long maxAgeMs, boolean alsoFiles) {
        long limit = System.currentTimeMillis() - maxAgeMs;
        int n = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (m.time >= limit) continue;
            if (alsoFiles) deleteReceived(m);
            msgs.remove(i);
            n++;
        }
        if (n > 0) { gen++; changed(); }
        return n;
    }

    /** Solo borra archivos que llegaron de una PC; nunca los originales del celular. */
    private static void deleteReceived(Msg m) {
        if (!m.fromPc || !m.isFile() || m.uri == null || ctx == null) return;
        try { ctx.getContentResolver().delete(m.uri, null, null); } catch (Exception ignored) {}
    }

    static final String PREF_DAYS = "limpieza_dias", PREF_FILES = "limpieza_archivos";

    static int autoDays(Context c) { return c.getSharedPreferences("pasaarchivos", Context.MODE_PRIVATE).getInt(PREF_DAYS, 0); }
    static boolean autoFiles(Context c) { return c.getSharedPreferences("pasaarchivos", Context.MODE_PRIVATE).getBoolean(PREF_FILES, false); }

    static void setAuto(Context c, int days, boolean files) {
        c.getSharedPreferences("pasaarchivos", Context.MODE_PRIVATE).edit().putInt(PREF_DAYS, days).putBoolean(PREF_FILES, files).apply();
    }

    /** Aplica la limpieza automática elegida (si hay). */
    static void autoClean(Context c) {
        int days = autoDays(c);
        if (days > 0) purge(days * 24L * 60 * 60 * 1000, autoFiles(c));
    }

    // ---------- guardar y cargar ----------

    private static File file() { return new File(ctx.getFilesDir(), "chat.json"); }

    private static void save() {
        if (ctx == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("next", nextId);
            JSONArray a = new JSONArray();
            for (Pc p : pcs) {
                a.put(new JSONObject().put("id", p.id).put("token", p.token).put("name", p.name)
                        .put("created", p.created).put("unread", p.unread));
            }
            root.put("pcs", a);
            JSONArray b = new JSONArray();
            for (Msg m : msgs) {
                JSONObject o = new JSONObject().put("id", m.id).put("pc", m.pc).put("fromPc", m.fromPc).put("time", m.time)
                        .put("delivered", m.delivered);
                if (m.text != null) o.put("text", m.text);
                else o.put("name", m.name).put("size", m.size).put("mime", m.mime).put("uri", m.uri == null ? "" : m.uri.toString());
                b.put(o);
            }
            root.put("msgs", b);
            File tmp = new File(ctx.getFilesDir(), "chat.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file())) { file().delete(); tmp.renameTo(file()); }
        } catch (Exception ignored) {}
    }

    private static void load() {
        File f = file();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] d = new byte[(int) f.length()];
            int off = 0, r;
            while (off < d.length && (r = in.read(d, off, d.length - off)) > 0) off += r;
            JSONObject root = new JSONObject(new String(d, 0, off, StandardCharsets.UTF_8));
            nextId = root.optLong("next", 1);
            JSONArray a = root.optJSONArray("pcs");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Pc p = new Pc(o.getString("id"), o.getString("token"), o.getString("name"), o.optLong("created"));
                p.unread = o.optInt("unread");
                pcs.add(p);
            }
            JSONArray b = root.optJSONArray("msgs");
            for (int i = 0; b != null && i < b.length(); i++) {
                JSONObject o = b.getJSONObject(i);
                String u = o.optString("uri", "");
                Msg m = new Msg(o.getLong("id"), o.getString("pc"), o.getBoolean("fromPc"), o.getLong("time"),
                        o.has("text") ? o.getString("text") : null, o.optString("name", null), o.optLong("size"),
                        o.optString("mime", null), u.isEmpty() ? null : Uri.parse(u));
                m.delivered = o.optBoolean("delivered");
                msgs.add(m);
                nextId = Math.max(nextId, m.id + 1);
            }
        } catch (Exception ignored) {}
    }

    static String rand(int len) {
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    private Chat() {}
}
