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

    /** Chat servido por el celular (dirección http). */
    static String chat(String pcName, String token) {
        return asset("pc.html")
            .replace("{{CSS}}", asset("base.css"))
            .replace("{{NAME}}", WebServer.esc(pcName))
            .replace("{{TOKEN}}", token)
            .replace("/*{{BOOT}}*/", "B='';start();");
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
