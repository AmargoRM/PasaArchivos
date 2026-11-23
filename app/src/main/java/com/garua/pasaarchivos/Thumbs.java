package com.garua.pasaarchivos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;
import android.util.Size;
import java.io.InputStream;

/** Miniaturas de fotos y videos, con caché en memoria. */
final class Thumbs {
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static Bitmap load(Context ctx, Uri uri, boolean image, int px) {
        String key = uri + "#" + px;
        Bitmap b = cache.get(key);
        if (b != null) return b;
        b = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try { b = ctx.getContentResolver().loadThumbnail(uri, new Size(px, px), null); } catch (Exception ignored) {}
        }
        if (b == null && image) b = decode(ctx, uri, px);
        if (b != null) cache.put(key, b);
        return b;
    }

    private static Bitmap decode(Context ctx, Uri uri, int px) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream a = ctx.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(a, null, o); }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream b = ctx.getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(b, null, o2); }
            if (bmp == null) return null;
            int rot = 0;
            try (InputStream c = ctx.getContentResolver().openInputStream(uri)) {
                int or = new ExifInterface(c).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (or == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (or == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (or == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Exception ignored) {}
            if (rot != 0) {
                Matrix m = new Matrix(); m.postRotate(rot);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private Thumbs() {}
}
