package com.garua.pasaarchivos;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Archivos recibidos desde la PC. Se guardan en Descargas/PasaArchivos del celular. */
final class Inbox {
    static final String FOLDER = "PasaArchivos";

    /** Resultado de guardar: nombre final y ubicación (la ubicación puede llegar un poco después en Android viejo). */
    static final class Rec {
        final String name; final long size; final String mime; volatile Uri uri;
        Rec(String n, long s, Uri u) { name = n; size = s; uri = u; mime = mimeOf(n); }
    }

    interface Late { void onUri(Uri u); }

    static String mimeOf(String name) {
        int d = name.lastIndexOf('.');
        String m = d < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(d + 1).toLowerCase());
        return m == null ? "application/octet-stream" : m;
    }

    /** Copia exactamente {@code len} bytes de {@code in} a un archivo nuevo en Descargas/PasaArchivos. */
    static Rec save(Context ctx, String name, InputStream in, long len, Late late) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) return saveMediaStore(ctx, name, in, len);
        return saveFile(ctx, name, in, len, late);
    }

    private static Rec saveMediaStore(Context ctx, String name, InputStream in, long len) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("No se pudo crear el archivo en Descargas.");
        boolean ok = false;
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("No se pudo escribir en Descargas.");
            copyExactly(in, out, len);
            ok = true;
        } finally {
            if (!ok) { try { cr.delete(uri, null, null); } catch (Exception ignored) {} }
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        String real = name;
        try (android.database.Cursor c = cr.query(uri, new String[] { MediaStore.MediaColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) real = c.getString(0);
        } catch (Exception ignored) {}
        return new Rec(real, len, uri);
    }

    private static Rec saveFile(Context ctx, String name, InputStream in, long len, Late late) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Sin permiso para guardar en Descargas.");
        File f = new File(dir, name);
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int k = 1; f.exists(); k++) f = new File(dir, base + " (" + k + ")" + ext);
        boolean ok = false;
        try (OutputStream out = new FileOutputStream(f)) {
            copyExactly(in, out, len);
            ok = true;
        } finally {
            if (!ok) f.delete();
        }
        final Rec r = new Rec(f.getName(), len, null);
        MediaScannerConnection.scanFile(ctx.getApplicationContext(), new String[] { f.getAbsolutePath() }, null,
                (p, u) -> { r.uri = u; if (late != null && u != null) late.onUri(u); });
        return r;
    }

    private static void copyExactly(InputStream in, OutputStream out, long len) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = len;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new IOException("La PC cortó el envío antes de terminar.");
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private Inbox() {}
}
