package com.garua.pasaarchivos;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Plantillas HTML guardadas en assets/web. */
final class Pages {
    private static Context ctx;
    private static final Map<String, String> cache = new HashMap<>();

    static void init(Context c) { ctx = c.getApplicationContext(); }

    static synchronized String asset(String name) {
        String s = cache.get(name);
        if (s != null) return s;
        try (InputStream in = ctx.getAssets().open("web/" + name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            s = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            s = "";
        }
        cache.put(name, s);
        return s;
    }

    static String head(String extraCss) {
        return "<meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
             + "<meta name=theme-color content='#0f766e'><link rel=icon href=/icon-192.png>"
             + "<title>" + WebServer.APP + "</title><style>" + asset("base.css") + extraCss + "</style>";
    }

    /** Chat servido por el celular (dirección http). Si el celular cambia de dirección, lo busca y se reconecta. */
    static String chat(String pcName, String token, String label, String ipsCsv) {
        String find = asset("find.html")
            .replace("{{ICON}}", "/icon-192.png")
            .replace("{{LABEL}}", label)
            .replace("{{TOKEN}}", token)
            .replace("{{IPS}}", ipsCsv);
        return asset("pc.html")
            .replace("{{CSS}}", asset("base.css"))
            .replace("{{NAME}}", WebServer.esc(pcName))
            .replace("{{TOKEN}}", token)
            .replace("/*{{BOOT}}*/", "")
            .replace("<!--{{FIND}}-->", find);
    }

    /** Tras escribir el código: ¿es una computadora ya conocida (mismo chat) o una nueva? */
    static String choose(String code, java.util.List<Chat.Pc> pcs) {
        StringBuilder rows = new StringBuilder();
        for (Chat.Pc p : pcs) {
            rows.append("<a class=opt href='/c/").append(code).append("/usar/").append(p.id).append("'><span class=ic>&#128187;</span><span><b>")
                .append(WebServer.esc(p.name)).append("</b><small>Seguir en el mismo chat, con su historial</small></span></a>");
        }
        return "<!doctype html><html lang=es>" + head(".box{max-width:460px;text-align:left}.box h2{text-align:center}"
                + ".box>p{text-align:center}.opt{display:flex;gap:12px;align-items:center;padding:12px 14px;border:1px solid var(--line);"
                + "border-radius:12px;margin:8px 0;text-decoration:none;color:var(--ink)}.opt:hover{border-color:var(--pri);background:var(--soft)}"
                + ".opt .ic{font-size:22px}.opt b{display:block;font-size:15px}.opt small{color:var(--mut);font-size:12.5px}"
                + ".new{border-style:dashed}")
            + "<div class=box><h2>¿Qué computadora es esta?</h2>"
            + "<p>Si ya la habías conectado antes (por ejemplo, el celular cambió de dirección), elegila para seguir en el mismo chat.</p>"
            + rows
            + "<a class='opt new' href='/c/" + code + "/nueva'><span class=ic>&#10133;</span><span><b>Es una computadora nueva</b>"
            + "<small>Empieza un chat aparte</small></span></a></div></html>";
    }

    /**
     * Acceso para guardar en la PC: el mismo chat, pero vive en el archivo (file://) y busca al celular solo.
     * Así el navegador no muestra "No es seguro" y sobrevive a cambios de dirección del celular.
     */
    static String launcher(String icon, String label, String token, String ipsCsv, String pcName) {
        String find = asset("find.html")
            .replace("{{ICON}}", icon)
            .replace("{{LABEL}}", label)
            .replace("{{TOKEN}}", token)
            .replace("{{IPS}}", ipsCsv);
        return asset("pc.html")
            .replace("{{CSS}}", asset("base.css"))
            .replace("{{NAME}}", WebServer.esc(pcName))
            .replace("{{TOKEN}}", token)
            .replace("<!--{{FIND}}-->", find);
    }

    private Pages() {}
}
