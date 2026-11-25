package com.garua.pasaarchivos;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String EXTRA_PC = "pc";
    /** Conversación abierta en pantalla (para no avisar con notificación lo que ya se está viendo). */
    static volatile String openChat;

    private static final int PICK_MEDIA = 1, PICK_FILES = 2;
    private static final int PRI = Color.rgb(15, 118, 110);
    private static final int PRI2 = Color.rgb(12, 74, 110);
    private static final int SOFT = Color.rgb(230, 244, 241);
    private static final int MINE = Color.rgb(211, 240, 234);
    private static final int BG = Color.rgb(244, 241, 234);
    private static final int CHATBG = Color.rgb(236, 231, 220);
    private static final int INK = Color.rgb(23, 33, 43);
    private static final int MUT = Color.rgb(102, 114, 126);
    private static final int LINE = Color.rgb(227, 222, 211);
    private static final int OK = Color.rgb(21, 128, 61);
    private static final int ERR = Color.rgb(179, 38, 30);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final List<Uri> pending = new ArrayList<>();   // compartido desde otra app sin computadora elegida

    private FrameLayout frame;
    private int insetTop, insetBottom;
    private boolean pendingInstall;
    private Updater.Info update;
    private String shownSig = "";
    private OnBackInvokedCallback backCb;

    // pantalla de inicio
    private View home;
    private LinearLayout homeHeader, pcList, updateBox, connectBody, pendingBox;
    private TextView statusPill, updateTitle, updateNotes, updateBtn, connectTitle, chevron, addrView, altView, batteryLink, pendingText, cleanLink;
    private final TextView[] digits = new TextView[4];
    private boolean connectOpen, connectTouched;

    // pantalla de conversación
    private View chat;
    private LinearLayout chatTop, feed, composer;
    private ScrollView feedScroll;
    private TextView chatName, chatState;
    private EditText input;

    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(false); handler.postDelayed(this, 1200); }
    };

    // ---------- ciclo de vida ----------

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Chat.init(this);
        frame = new FrameLayout(this);
        frame.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bot, ime = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars());
                top = s.top; bot = s.bottom;
                ime = ins.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                top = legacyTop(ins); bot = legacyBottom(ins);
            }
            insetTop = top; insetBottom = Math.max(bot, ime);
            applyInsets();
            return ins;
        });
        setContentView(frame);
        buildHome();
        buildChat();
        buildViewer();
        showHome();
        startShare();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 5);
        } else if (Build.VERSION.SDK_INT < 29 && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { "android.permission.WRITE_EXTERNAL_STORAGE" }, 6);
        }
        handleIntent(getIntent());
    }

    @SuppressWarnings("deprecation") private static int legacyTop(WindowInsets i) { return i.getSystemWindowInsetTop(); }
    @SuppressWarnings("deprecation") private static int legacyBottom(WindowInsets i) { return i.getSystemWindowInsetBottom(); }

    private void applyInsets() {
        if (viewerTop != null) viewerTop.setPadding(dp(6), insetTop + dp(6), dp(10), dp(8));
        homeHeader.setPadding(dp(20), insetTop + dp(18), dp(20), dp(20));
        ((View) pcList.getParent()).setPadding(0, 0, 0, insetBottom);
        chatTop.setPadding(dp(6), insetTop + dp(6), dp(10), dp(8));
        composer.setPadding(dp(8), dp(8), dp(8), dp(8) + insetBottom);
    }

    private void startShare() {
        try { startForegroundService(new Intent(this, ShareService.class)); } catch (Exception ignored) {}
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        startShare();
        handleIntent(i);
    }

    @Override protected void onResume() {
        super.onResume();
        try { Chat.autoClean(this); } catch (Exception ignored) {}
        handler.post(ticker);
        if (pendingInstall && update != null && Updater.canInstall(this)) { pendingInstall = false; doInstall(); }
        Updater.check(this, false, (info, err) -> { if (info != null) showUpdate(info); });
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override protected void onStop() { super.onStop(); openChat = null; }
    @Override protected void onStart() { super.onStart(); if (chat.getVisibility() == View.VISIBLE) openChat = currentPc; }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (!handleBack()) super.onBackPressed();
    }

    /** Atrás dentro de la app: cierra el visor, o vuelve de la conversación a la lista. */
    private boolean handleBack() {
        if (viewer != null && viewer.getVisibility() == View.VISIBLE) { closeViewer(); return true; }
        if (chat.getVisibility() == View.VISIBLE) { showHome(); return true; }
        return false;
    }

    // ---------- lo que llega de otras apps ----------

    private void handleIntent(Intent i) {
        if (i == null) return;
        String pcId = i.getStringExtra(EXTRA_PC);
        if (pcId != null && Chat.byId(pcId) != null) { i.removeExtra(EXTRA_PC); showChat(pcId); return; }
        if (i.getAction() == null) return;
        List<Uri> got = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class) : legacyUri(i);
            if (u != null) got.add(u);
            String t = i.getStringExtra(Intent.EXTRA_TEXT);
            if (u == null && t != null) {
                String sc = Build.VERSION.SDK_INT >= 29 ? i.getStringExtra(Intent.EXTRA_SHORTCUT_ID) : null;
                i.setAction(null);
                if (sc != null && sc.startsWith("pc_") && Chat.byId(sc.substring(3)) != null) {
                    Chat.addText(sc.substring(3), false, t); showChat(sc.substring(3)); return;
                }
                shareText(t);
                return;
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> us = Build.VERSION.SDK_INT >= 33 ? i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class) : legacyUris(i);
            if (us != null) for (Uri u : us) if (u != null) got.add(u);
        }
        String direct = Build.VERSION.SDK_INT >= 29 ? i.getStringExtra(Intent.EXTRA_SHORTCUT_ID) : null;
        i.setAction(null);
        if (got.isEmpty()) return;
        if (direct != null && direct.startsWith("pc_") && Chat.byId(direct.substring(3)) != null) {
            String id = direct.substring(3);
            showChat(id);
            for (Uri u : got) sendUri(id, u);
            refresh(true);
            scrollDown();
            Toast.makeText(this, got.size() == 1 ? "Listo para " + Chat.byId(id).name : got.size() + " archivos listos para " + Chat.byId(id).name,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<Chat.Pc> pcs = Chat.pcs();
        if (currentPc != null && chat.getVisibility() == View.VISIBLE) { for (Uri u : got) sendUri(currentPc, u); return; }
        if (pcs.size() == 1) { showChat(pcs.get(0).id); for (Uri u : got) sendUri(pcs.get(0).id, u); return; }
        pending.addAll(got);
        if (pcs.isEmpty()) { showHome(); refresh(true); return; }
        choosePc(got.size() == 1 ? "¿A qué computadora lo mandás?" : "¿A qué computadora mandás los " + got.size() + " archivos?", id -> {
            showChat(id);
            for (Uri u : pending) sendUri(id, u);
            pending.clear();
        });
    }

    private void shareText(String t) {
        List<Chat.Pc> pcs = Chat.pcs();
        if (pcs.isEmpty()) { Toast.makeText(this, "Primero conectá una computadora.", Toast.LENGTH_LONG).show(); return; }
        if (pcs.size() == 1) { Chat.addText(pcs.get(0).id, false, t); showChat(pcs.get(0).id); return; }
        choosePc("¿A qué computadora mandás el texto?", id -> { Chat.addText(id, false, t); showChat(id); });
    }

    interface PcChoice { void chosen(String id); }

    private void choosePc(String title, PcChoice c) {
        List<Chat.Pc> pcs = Chat.pcs();
        String[] names = new String[pcs.size()];
        for (int k = 0; k < names.length; k++) names[k] = pcs.get(k).name + (pcs.get(k).online() ? "  ●" : "");
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(names, (d, w) -> c.chosen(pcs.get(w).id))
                .setNegativeButton("Cancelar", (d, w) -> pending.clear())
                .show();
    }

    @SuppressWarnings("deprecation") private static Uri legacyUri(Intent i) { return i.getParcelableExtra(Intent.EXTRA_STREAM); }
    @SuppressWarnings("deprecation") private static ArrayList<Uri> legacyUris(Intent i) { return i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); }

    // ---------- elegir y enviar archivos ----------

    private void pickMedia() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*" });
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try { startActivityForResult(i, PICK_MEDIA); } catch (Exception e) { pickFiles(); }
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, PICK_FILES); } catch (Exception ignored) {}
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != PICK_MEDIA && req != PICK_FILES) || res != RESULT_OK || data == null || currentPc == null) return;
        ClipData cd = data.getClipData();
        if (cd != null) for (int k = 0; k < cd.getItemCount(); k++) sendUri(currentPc, cd.getItemAt(k).getUri());
        else if (data.getData() != null) sendUri(currentPc, data.getData());
        refresh(true);
        scrollDown();
    }

    private void sendUri(String pcId, Uri u) {
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
        String name = null; long size = -1;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            name = u.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "archivo";
        }
        String mime = null;
        try { mime = getContentResolver().getType(u); } catch (Exception ignored) {}
        if (mime == null) mime = Inbox.mimeOf(name);
        Chat.addFile(pcId, false, name, size, mime, u);
    }

    // ---------- piezas comunes ----------

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String t, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return v;
    }

    private GradientDrawable shape(int fill, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable bubbleShape(int fill, boolean mine) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        float r = dp(16), s = dp(5);
        g.setCornerRadii(mine ? new float[] { r, r, r, r, s, s, r, r } : new float[] { r, r, r, r, r, r, s, s });
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private TextView link(String t, int color, View.OnClickListener l) {
        TextView v = label(t, 14, color, true);
        v.setPadding(dp(4), dp(12), dp(4), dp(12));
        v.setClickable(true);
        v.setOnClickListener(l);
        return v;
    }

    private TextView roundIcon(String glyph, int bg, int fg, int sizeDp, float textSp, View.OnClickListener l) {
        TextView v = label(glyph, textSp, fg, false);
        v.setGravity(Gravity.CENTER);
        v.setBackground(shape(bg, sizeDp / 2f, 0));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        if (l != null) { v.setClickable(true); v.setOnClickListener(l); }
        return v;
    }

    private static String ext(String n) {
        int d = n.lastIndexOf('.');
        return d < 0 || n.length() - d > 6 ? "ARCH" : n.substring(d + 1).toUpperCase(Locale.ROOT);
    }

    private static String human(long b) {
        if (b <= 0) return "";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return Math.round(b / 1024.0) + " KB";
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    private static String hm(long t) { return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(t)); }

    private String when(long t) {
        if (DateUtils.isToday(t)) return hm(t);
        if (DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS)) return "ayer";
        return new SimpleDateFormat("d MMM", new Locale("es")).format(new Date(t));
    }

    private void open(Uri uri, String mime) {
        if (uri == null) {
            try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); } catch (Exception ignored) {}
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime == null ? "*/*" : mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "Abrir con")); }
        catch (Exception e) { Toast.makeText(this, "Ese archivo ya no está disponible en el celular.", Toast.LENGTH_SHORT).show(); }
    }

    // ---------- pantalla de inicio: lista de computadoras ----------

    private void buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        homeHeader = new LinearLayout(this);
        homeHeader.setOrientation(LinearLayout.VERTICAL);
        homeHeader.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { PRI, PRI2 }));
        homeHeader.addView(label(WebServer.APP, 27, Color.WHITE, true));
        TextView sub = label("Chateá con tus computadoras y pasá archivos en los dos sentidos, por wifi.", 14,
                Color.argb(215, 255, 255, 255), false);
        sub.setPadding(0, dp(2), 0, dp(12));
        homeHeader.addView(sub);
        statusPill = label("", 13, Color.WHITE, true);
        statusPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusPill.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        homeHeader.addView(statusPill);
        root.addView(homeHeader);

        updateBox = new LinearLayout(this);
        updateBox.setOrientation(LinearLayout.VERTICAL);
        updateBox.setBackground(shape(Color.rgb(255, 246, 220), 16, Color.rgb(240, 213, 138)));
        updateBox.setPadding(dp(16), dp(14), dp(16), dp(14));
        updateBox.setLayoutParams(lp(-1, -2, 16, 14, 16, 0));
        updateTitle = label("", 15, INK, true);
        updateBox.addView(updateTitle);
        updateNotes = label("", 13, MUT, false);
        updateNotes.setPadding(0, dp(4), 0, dp(10));
        updateBox.addView(updateNotes);
        updateBtn = label("", 15, Color.WHITE, true);
        updateBtn.setGravity(Gravity.CENTER);
        updateBtn.setBackground(shape(PRI, 12, 0));
        updateBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(46)));
        updateBtn.setClickable(true);
        updateBtn.setOnClickListener(v -> startUpdate());
        updateBox.addView(updateBtn);
        updateBox.setVisibility(View.GONE);
        root.addView(updateBox);

        pendingBox = new LinearLayout(this);
        pendingBox.setOrientation(LinearLayout.VERTICAL);
        pendingBox.setBackground(shape(SOFT, 16, Color.rgb(190, 224, 218)));
        pendingBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        pendingBox.setLayoutParams(lp(-1, -2, 16, 14, 16, 0));
        pendingText = label("", 14, INK, false);
        pendingBox.addView(pendingText);
        pendingBox.setVisibility(View.GONE);
        root.addView(pendingBox);

        TextView h = label("COMPUTADORAS", 12, MUT, true);
        h.setLetterSpacing(0.06f);
        h.setPadding(dp(22), dp(20), dp(22), dp(8));
        root.addView(h);
        pcList = new LinearLayout(this);
        pcList.setOrientation(LinearLayout.VERTICAL);
        pcList.setBackground(shape(Color.WHITE, 18, LINE));
        pcList.setLayoutParams(lp(-1, -2, 16, 0, 16, 0));
        root.addView(pcList);

        // Conectar una computadora (plegable)
        LinearLayout conn = new LinearLayout(this);
        conn.setOrientation(LinearLayout.VERTICAL);
        conn.setBackground(shape(Color.WHITE, 18, LINE));
        conn.setPadding(dp(18), dp(4), dp(18), dp(4));
        conn.setLayoutParams(lp(-1, -2, 16, 14, 16, 0));
        LinearLayout ch = new LinearLayout(this);
        ch.setGravity(Gravity.CENTER_VERTICAL);
        ch.setPadding(0, dp(12), 0, dp(12));
        ch.setClickable(true);
        connectTitle = label("＋  Conectar una computadora", 15, PRI, true);
        connectTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        chevron = label("▾", 18, MUT, true);
        ch.addView(connectTitle);
        ch.addView(chevron);
        ch.setOnClickListener(v -> { connectTouched = true; setConnectOpen(!connectOpen); });
        conn.addView(ch);
        connectBody = new LinearLayout(this);
        connectBody.setOrientation(LinearLayout.VERTICAL);
        connectBody.setPadding(0, 0, 0, dp(14));
        connectBody.addView(label("En la computadora, conectada a la misma red wifi, abrí el navegador y escribí:", 14, MUT, false));
        addrView = label("", 19, INK, true);
        addrView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        addrView.setTextIsSelectable(true);
        addrView.setGravity(Gravity.CENTER);
        addrView.setBackground(shape(SOFT, 12, Color.rgb(190, 224, 218)));
        addrView.setPadding(dp(10), dp(14), dp(10), dp(14));
        addrView.setLayoutParams(lp(-1, -2, 0, 12, 0, 0));
        connectBody.addView(addrView);
        altView = label("", 12, MUT, false);
        altView.setPadding(0, dp(8), 0, 0);
        connectBody.addView(altView);
        TextView cl = label("Código que pide la página", 13, MUT, false);
        cl.setPadding(0, dp(14), 0, dp(8));
        connectBody.addView(cl);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < 4; i++) {
            TextView d = label("", 30, INK, true);
            d.setGravity(Gravity.CENTER);
            d.setBackground(shape(Color.rgb(255, 246, 220), 12, Color.rgb(240, 213, 138)));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(54), dp(64));
            dl.setMargins(dp(5), 0, dp(5), 0);
            d.setLayoutParams(dl);
            digits[i] = d;
            row.addView(d);
        }
        connectBody.addView(row);
        TextView after = label("Al entrar, esa computadora aparece en la lista de arriba. En su página tocá "
                + "«Guardar acceso» para no volver a escribir nada.", 12, MUT, false);
        after.setPadding(0, dp(12), 0, 0);
        connectBody.addView(after);
        conn.addView(connectBody);
        root.addView(conn);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(dp(20), dp(8), dp(20), dp(24));
        batteryLink = link("Evitar que Android cierre PasaArchivos", PRI, v -> askBattery());
        foot.addView(batteryLink);
        cleanLink = link("", PRI, v -> cleanSettings());
        foot.addView(cleanLink);
        foot.addView(link("Buscar actualizaciones", PRI, v -> manualCheck()));
        foot.addView(link("Detener PasaArchivos", ERR, v -> {
            startService(new Intent(this, ShareService.class).setAction(ShareService.ACTION_STOP));
            finishAndRemoveTask();
        }));
        TextView ver = label("Versión " + BuildConfig.VERSION_NAME + " · sigue activo aunque cierres esta pantalla; "
                + "lo indica la notificación.", 12, MUT, false);
        ver.setPadding(dp(4), dp(10), dp(4), 0);
        foot.addView(ver);
        root.addView(foot);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.addView(root);
        home = sv;
        frame.addView(home, new FrameLayout.LayoutParams(-1, -1));
    }

    private void setConnectOpen(boolean open) {
        connectOpen = open;
        connectBody.setVisibility(open ? View.VISIBLE : View.GONE);
        chevron.setText(open ? "▴" : "▾");
    }

    private View pcRow(Chat.Pc p, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);
        row.setOnClickListener(v -> showChat(p.id));
        row.setOnLongClickListener(v -> { pcMenu(p.id); return true; });

        FrameLayout av = new FrameLayout(this);
        av.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(50)));
        TextView ic = label("💻", 22, INK, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(shape(SOFT, 25, 0));
        av.addView(ic, new FrameLayout.LayoutParams(-1, -1));
        if (p.online()) {
            View dot = new View(this);
            GradientDrawable g = shape(Color.rgb(34, 197, 94), 8, 0);
            g.setStroke(dp(2), Color.WHITE);
            dot.setBackground(g);
            av.addView(dot, new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.BOTTOM | Gravity.END));
        }
        row.addView(av);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView n = label(p.name, 16, INK, true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(n);
        Chat.Msg m = Chat.last(p.id);
        String prev = m == null ? (p.online() ? "Conectada ahora" : "Sin mensajes todavía")
                : (m.fromPc ? "" : "Vos: ") + (m.isFile() ? (m.isImage() ? "📷 " : m.isVideo() ? "🎬 " : "📎 ") + m.name : m.text.replace('\n', ' '));
        TextView pv = label(prev, 14, p.unread > 0 ? INK : MUT, p.unread > 0);
        pv.setSingleLine(true);
        pv.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(pv);
        row.addView(mid);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(label(m == null ? "" : when(m.time), 12, p.unread > 0 ? PRI : MUT, p.unread > 0));
        if (p.unread > 0) {
            TextView badge = label(String.valueOf(p.unread), 12, Color.WHITE, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(shape(PRI, 11, 0));
            badge.setMinWidth(dp(22));
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(22));
            bp.topMargin = dp(4);
            bp.gravity = Gravity.END;
            right.addView(badge, bp);
        }
        row.addView(right);

        if (last) return row;
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        View div = new View(this);
        div.setBackgroundColor(Color.rgb(240, 236, 227));
        wrap.addView(div, lp(-1, 1, 76, 0, 0, 0));
        return wrap;
    }

    private void pcMenu(String id) {
        Chat.Pc p = Chat.byId(id);
        if (p == null) return;
        new AlertDialog.Builder(this).setTitle(p.name)
            .setItems(new String[] { "Cambiar nombre", "Vaciar la conversación", "Quitar esta computadora" }, (d, w) -> {
                if (w == 0) renamePc(id); else if (w == 1) clearChat(id); else removePc(id);
            }).show();
    }

    private void renamePc(String id) {
        Chat.Pc p = Chat.byId(id);
        if (p == null) return;
        EditText e = new EditText(this);
        e.setText(p.name);
        e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle("Nombre de la computadora").setView(box)
            .setPositiveButton("Guardar", (d, w) -> { Chat.rename(id, e.getText().toString()); refresh(true); })
            .setNegativeButton("Cancelar", null).show();
    }

    private void removePc(String id) {
        Chat.Pc p = Chat.byId(id);
        if (p == null) return;
        new AlertDialog.Builder(this).setTitle("Quitar «" + p.name + "»")
            .setMessage("Se borra esta conversación del celular y el acceso guardado en esa computadora deja de funcionar. "
                    + "Los archivos que ya recibiste siguen en Descargas › PasaArchivos. Para volver a conectarla vas a "
                    + "necesitar el código otra vez.")
            .setPositiveButton("Quitar", (d, w) -> { Chat.remove(id); showHome(); })
            .setNegativeButton("Cancelar", null).show();
    }

    // ---------- pantalla de conversación ----------

    private String currentPc;

    private void buildChat() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CHATBG);

        chatTop = new LinearLayout(this);
        chatTop.setGravity(Gravity.CENTER_VERTICAL);
        chatTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { PRI, PRI2 }));
        TextView back = label("←", 24, Color.WHITE, false);
        back.setGravity(Gravity.CENTER);
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        back.setClickable(true);
        back.setOnClickListener(v -> showHome());
        chatTop.addView(back);
        TextView av = label("💻", 19, Color.WHITE, false);
        av.setGravity(Gravity.CENTER);
        av.setBackground(shape(Color.argb(45, 255, 255, 255), 20, 0));
        av.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        chatTop.addView(av);
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(10), 0, dp(6), 0);
        tt.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        tt.setClickable(true);
        tt.setOnClickListener(v -> { if (currentPc != null) renamePc(currentPc); });
        chatName = label("", 17, Color.WHITE, true);
        chatName.setSingleLine(true);
        chatName.setEllipsize(TextUtils.TruncateAt.END);
        chatState = label("", 12, Color.argb(220, 255, 255, 255), false);
        tt.addView(chatName);
        tt.addView(chatState);
        chatTop.addView(tt);
        TextView menu = label("⋮", 22, Color.WHITE, true);
        menu.setGravity(Gravity.CENTER);
        menu.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(44)));
        menu.setClickable(true);
        menu.setOnClickListener(v -> {
            if (currentPc == null) return;
            new AlertDialog.Builder(this)
                .setItems(new String[] { "Cambiar nombre", "Vaciar esta conversación", "Abrir la carpeta Descargas", "Quitar esta computadora" }, (d, w) -> {
                    if (w == 0) renamePc(currentPc);
                    else if (w == 1) clearChat(currentPc);
                    else if (w == 2) open(null, null);
                    else removePc(currentPc);
                }).show();
        });
        chatTop.addView(menu);
        root.addView(chatTop);

        feedScroll = new ScrollView(this);
        feedScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        feed = new LinearLayout(this);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.setPadding(dp(10), dp(10), dp(10), dp(10));
        feedScroll.addView(feed);
        root.addView(feedScroll);

        composer = new LinearLayout(this);
        composer.setGravity(Gravity.BOTTOM);
        composer.setBackgroundColor(Color.rgb(247, 244, 238));
        TextView media = roundIcon("🖼", Color.WHITE, PRI, 44, 19, v -> pickMedia());
        TextView files = roundIcon("📎", Color.WHITE, PRI, 44, 19, v -> pickFiles());
        ((LinearLayout.LayoutParams) files.getLayoutParams()).leftMargin = dp(6);
        composer.addView(media);
        composer.addView(files);
        input = new EditText(this);
        input.setHint("Mensaje");
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(shape(Color.WHITE, 22, LINE));
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setMinHeight(dp(44));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.setMargins(dp(6), 0, dp(6), 0);
        input.setLayoutParams(ip);
        composer.addView(input);
        composer.addView(roundIcon("➤", PRI, Color.WHITE, 44, 18, v -> sendText()));
        root.addView(composer);

        chat = root;
        frame.addView(chat, new FrameLayout.LayoutParams(-1, -1));
    }

    private void sendText() {
        String t = input.getText().toString().trim();
        if (t.isEmpty() || currentPc == null) return;
        Chat.addText(currentPc, false, t);
        input.setText("");
        refresh(true);
        scrollDown();
    }

    private void showHome() {
        currentPc = null;
        openChat = null;
        chat.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        setBack(false);
        hideKeyboard();
        refresh(true);
    }

    private void showChat(String pcId) {
        if (Chat.byId(pcId) == null) return;
        currentPc = pcId;
        openChat = pcId;
        Chat.markRead(pcId);
        home.setVisibility(View.GONE);
        chat.setVisibility(View.VISIBLE);
        setBack(true);
        shownSig = "";
        refresh(true);
        scrollDown();
        if (!pending.isEmpty()) {
            final List<Uri> copy = new ArrayList<>(pending);
            pending.clear();
            new AlertDialog.Builder(this).setTitle("Archivos esperando")
                .setMessage("Tenés " + copy.size() + (copy.size() == 1 ? " archivo compartido" : " archivos compartidos")
                        + " desde otra app. ¿Los mandás a " + Chat.byId(pcId).name + "?")
                .setPositiveButton("Mandar", (d, w) -> { for (Uri u : copy) sendUri(pcId, u); refresh(true); scrollDown(); })
                .setNegativeButton("Descartar", null).show();
        }
    }

    private void setBack(boolean inChat) {
        if (Build.VERSION.SDK_INT < 33) return;
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (inChat && backCb == null) {
            backCb = this::handleBack;
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCb);
        } else if (!inChat && backCb != null) {
            d.unregisterOnBackInvokedCallback(backCb);
            backCb = null;
        }
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
            im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    private void scrollDown() { feedScroll.post(() -> feedScroll.fullScroll(View.FOCUS_DOWN)); }

    private View dayChip(String text) {
        TextView d = label(text, 12, MUT, false);
        d.setBackground(shape(Color.argb(190, 255, 255, 255), 10, 0));
        d.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        p.setMargins(0, dp(10), 0, dp(6));
        d.setLayoutParams(p);
        return d;
    }

    private View bubble(Chat.Msg m) {
        boolean mine = !m.fromPc;
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.78);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(bubbleShape(mine ? MINE : Color.WHITE, mine));
        b.setPadding(dp(8), dp(6), dp(8), dp(5));
        b.setElevation(dp(0.5f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.gravity = mine ? Gravity.END : Gravity.START;
        bp.setMargins(mine ? dp(48) : 0, dp(3), mine ? 0 : dp(48), dp(3));
        b.setLayoutParams(bp);

        if (!m.isFile()) {
            // Texto simple: sin "seleccionable", que en algunos Android medía la burbuja como si estuviera vacía.
            TextView t = new TextView(this);
            t.setTextSize(15.5f);
            t.setTextColor(INK);
            t.setMaxWidth(maxW - dp(16));
            t.setText(m.text);
            Linkify.addLinks(t, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
            t.setLinkTextColor(PRI2);
            t.setPadding(dp(4), dp(2), dp(4), 0);
            b.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else if ((m.isImage() || m.isVideo()) && m.uri != null) {
            FrameLayout ph = new FrameLayout(this);
            int w = Math.min(maxW, dp(260));
            ph.setLayoutParams(new LinearLayout.LayoutParams(w, (int) (w * 0.75f)));
            ph.setBackground(shape(Color.rgb(229, 224, 212), 12, 0));
            ph.setClipToOutline(true);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setTag(m.uri);
            ph.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            final Uri u = m.uri;
            final boolean img = m.isImage();
            thumbs.execute(() -> {
                Bitmap bm = Thumbs.load(this, u, img, 480);
                if (bm != null) iv.post(() -> { if (u.equals(iv.getTag())) iv.setImageBitmap(bm); });
            });
            if (m.isVideo()) {
                TextView play = label("▶", 18, Color.WHITE, true);
                play.setGravity(Gravity.CENTER);
                play.setBackground(shape(Color.argb(140, 0, 0, 0), 24, 0));
                ph.addView(play, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
            }
            ph.setClickable(true);
            ph.setOnClickListener(v -> openViewer(m));
            b.addView(ph);
            TextView cap = label(m.name + (m.size > 0 ? "  ·  " + human(m.size) : ""), 12, MUT, false);
            cap.setSingleLine(true);
            cap.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            cap.setMaxWidth(w);
            cap.setPadding(dp(2), dp(5), dp(2), 0);
            b.addView(cap);
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setOnClickListener(v -> open(m.uri, m.mime));
            TextView ex = label(ext(m.name), 11, Color.WHITE, true);
            ex.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { Color.rgb(20, 184, 166), PRI2 });
            g.setCornerRadius(dp(9));
            ex.setBackground(g);
            ex.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(52)));
            row.addView(ex);
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(10), 0, dp(4), 0);
            TextView n = label(m.name, 14, INK, true);
            n.setMaxWidth(maxW - dp(70));
            n.setMaxLines(2);
            n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            tx.addView(n);
            if (m.size > 0) tx.addView(label(human(m.size), 12, MUT, false));
            row.addView(tx);
            b.addView(row);
        }

        String st = hm(m.time);
        if (mine && m.isFile()) st += m.delivered ? "  ·  ✓ Descargado en la PC" : "  ·  Listo para la PC";
        TextView meta = label(st, 11, mine && m.delivered ? OK : MUT, mine && m.delivered);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.gravity = Gravity.END;
        mp.topMargin = dp(3);
        meta.setLayoutParams(mp);
        b.addView(meta);
        b.setOnLongClickListener(v -> { msgMenu(m); return true; });
        for (int k = 0; k < b.getChildCount(); k++) b.getChildAt(k).setOnLongClickListener(v -> { msgMenu(m); return true; });
        return b;
    }

    private void msgMenu(Chat.Msg m) {
        List<String> opts = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (m.isFile()) { opts.add("Abrir"); acts.add(() -> open(m.uri, m.mime)); }
        else {
            opts.add("Copiar texto");
            acts.add(() -> {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                cm.setPrimaryClip(ClipData.newPlainText("PasaArchivos", m.text));
                Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show();
            });
        }
        opts.add("Borrar del chat");
        acts.add(() -> { Chat.removeMsg(m.id, false); refresh(true); });
        if (m.isFile() && m.fromPc && m.uri != null) {
            opts.add("Borrar del chat y del celular");
            acts.add(() -> new AlertDialog.Builder(this).setTitle("Borrar «" + m.name + "»")
                .setMessage("Se quita del chat y se borra de Descargas › PasaArchivos. No se puede recuperar.")
                .setPositiveButton("Borrar", (d, w) -> { Chat.removeMsg(m.id, true); refresh(true); })
                .setNegativeButton("Cancelar", null).show());
        }
        new AlertDialog.Builder(this).setItems(opts.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private void clearChat(String pcId) {
        Chat.Pc p = Chat.byId(pcId);
        if (p == null) return;
        android.widget.CheckBox files = new android.widget.CheckBox(this);
        files.setText("Borrar también del celular los archivos que mandó esta computadora");
        files.setTextSize(14);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(files);
        new AlertDialog.Builder(this).setTitle("Vaciar la conversación con " + p.name)
            .setMessage("Se borran todos los mensajes y archivos del chat. Tus fotos y archivos originales del celular no se tocan.")
            .setView(box)
            .setPositiveButton("Vaciar", (d, w) -> { Chat.clearPc(pcId, files.isChecked()); refresh(true); })
            .setNegativeButton("Cancelar", null).show();
    }

    private static final int[] CLEAN_DAYS = { 0, 1, 10, 30 };
    private static final String[] CLEAN_NAMES = { "Nunca", "Cada día (lo de más de 1 día)", "Lo de más de 10 días", "Lo de más de 1 mes" };

    private String cleanLabel() {
        int d = Chat.autoDays(this);
        return d == 0 ? "Limpieza automática: apagada" : "Limpieza automática: " + (d == 1 ? "1 día" : d == 30 ? "1 mes" : d + " días")
                + (Chat.autoFiles(this) ? " · también archivos" : "");
    }

    private void cleanSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        int cur = Chat.autoDays(this);
        for (int i = 0; i < CLEAN_DAYS.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(CLEAN_NAMES[i]);
            rb.setTextSize(15);
            rb.setTag(CLEAN_DAYS[i]);
            rg.addView(rb);
            if (CLEAN_DAYS[i] == cur) rg.check(rb.getId());
        }
        box.addView(rg);
        android.widget.CheckBox files = new android.widget.CheckBox(this);
        files.setText("Borrar también de Descargas › PasaArchivos los archivos que llegaron de las computadoras");
        files.setTextSize(14);
        files.setChecked(Chat.autoFiles(this));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(10);
        box.addView(files, fp);
        TextView note = label("Lo que mandás desde el celular nunca se borra de tu galería: solo se quita del chat. "
                + "Si marcás la casilla, los archivos recibidos se borran sin preguntar y no se pueden recuperar.", 12, MUT, false);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        new AlertDialog.Builder(this).setTitle("Limpieza automática").setView(box)
            .setPositiveButton("Guardar", (d, w) -> {
                View sel = rg.findViewById(rg.getCheckedRadioButtonId());
                int days = sel == null ? 0 : (Integer) sel.getTag();
                Chat.setAuto(this, days, days > 0 && files.isChecked());
                int n = Chat.autoDays(this) > 0 ? Chat.purge(days * 24L * 60 * 60 * 1000, Chat.autoFiles(this)) : 0;
                if (n > 0) Toast.makeText(this, "Se limpiaron " + n + (n == 1 ? " mensaje" : " mensajes") + " viejos.", Toast.LENGTH_LONG).show();
                refresh(true);
            })
            .setNegativeButton("Cancelar", null).show();
    }

    private View emptyChat(Chat.Pc p) {
        TextView e = label("💬\n\nEsta es tu conversación con " + p.name + ".\n\n"
                + "Mandá fotos con 🖼, cualquier archivo con 📎 o escribí un mensaje. "
                + "Lo que esa computadora te mande aparece aquí y se guarda en Descargas › PasaArchivos.", 14, MUT, false);
        e.setGravity(Gravity.CENTER);
        e.setBackground(shape(Color.argb(200, 255, 255, 255), 16, 0));
        e.setPadding(dp(22), dp(26), dp(22), dp(26));
        e.setLayoutParams(lp(-1, -2, 18, 40, 18, 0));
        return e;
    }

    // ---------- refresco ----------

    private void refresh(boolean force) {
        WebServer server = ShareService.server;
        if (server == null) {
            statusPill.setText(ShareService.startError != null ? "●  " + ShareService.startError : "●  Iniciando…");
            statusPill.setBackground(shape(Color.argb(90, 179, 38, 30), 20, 0));
        } else {
            int online = 0;
            for (Chat.Pc p : Chat.pcs()) if (p.online()) online++;
            statusPill.setText(online == 0 ? "●  Activo · esperando computadoras"
                    : "●  " + online + (online == 1 ? " computadora conectada" : " computadoras conectadas"));
            statusPill.setBackground(shape(online > 0 ? OK : Color.argb(60, 255, 255, 255), 20, 0));
        }

        if (chat.getVisibility() == View.VISIBLE) refreshChat(force);
        else refreshHome(force, server);
    }

    private String shortcutSig = "";

    /** Publica cada computadora como destino directo del menú Compartir (como los contactos de WhatsApp). */
    private void publishShortcuts(List<Chat.Pc> pcs) {
        StringBuilder sig = new StringBuilder();
        for (Chat.Pc p : pcs) sig.append(p.id).append('=').append(p.name).append(';');
        if (sig.toString().equals(shortcutSig)) return;
        shortcutSig = sig.toString();
        try {
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;
            List<android.content.pm.ShortcutInfo> list = new ArrayList<>();
            java.util.Set<String> cats = java.util.Collections.singleton("com.garua.pasaarchivos.category.PC");
            int max = Math.min(pcs.size(), Math.max(1, sm.getMaxShortcutCountPerActivity() - 1));
            for (int k = 0; k < max; k++) {
                Chat.Pc p = pcs.get(k);
                Intent open = new Intent(Intent.ACTION_VIEW, null, this, MainActivity.class).putExtra(EXTRA_PC, p.id);
                android.content.pm.ShortcutInfo.Builder b = new android.content.pm.ShortcutInfo.Builder(this, "pc_" + p.id)
                        .setShortLabel(p.name)
                        .setLongLabel("Enviar a " + p.name)
                        .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
                        .setCategories(cats)
                        .setIntent(open)
                        .setRank(k);
                if (Build.VERSION.SDK_INT >= 30) b.setLongLived(true);
                list.add(b.build());
            }
            sm.setDynamicShortcuts(list);
        } catch (Exception ignored) {}
    }

    private void refreshHome(boolean force, WebServer server) {
        List<Chat.Pc> pcs = Chat.pcs();
        publishShortcuts(pcs);
        StringBuilder sig = new StringBuilder("h").append(Chat.version());
        for (Chat.Pc p : pcs) sig.append(p.online() ? '1' : '0');
        if (force || !sig.toString().equals(shownSig)) {
            shownSig = sig.toString();
            pcList.removeAllViews();
            if (pcs.isEmpty()) {
                TextView e = label("Todavía no conectaste ninguna computadora.\nSeguí los pasos de abajo; tarda un minuto.", 14, MUT, false);
                e.setGravity(Gravity.CENTER);
                e.setPadding(dp(20), dp(26), dp(20), dp(26));
                pcList.addView(e);
            }
            for (int i = 0; i < pcs.size(); i++) pcList.addView(pcRow(pcs.get(i), i == pcs.size() - 1));
            connectTitle.setText(pcs.isEmpty() ? "＋  Conectar una computadora" : "＋  Conectar otra computadora");
            if (!connectTouched) setConnectOpen(pcs.isEmpty());
        }
        pendingBox.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        if (!pending.isEmpty()) pendingText.setText("📎  " + pending.size() + (pending.size() == 1 ? " archivo espera" : " archivos esperan")
                + " una computadora. Conectá una (abajo) y abrí su conversación para mandarlos.");

        String code = server == null ? "    " : server.code();
        for (int i = 0; i < 4; i++) digits[i].setText(String.valueOf(code.charAt(i)));
        if (server == null) {
            addrView.setText("—");
            altView.setText("");
        } else {
            List<String> ips = WebServer.lanIps();
            if (ips.isEmpty()) {
                addrView.setText("Sin wifi.\nConectá el celular a la red.");
                altView.setText("");
            } else {
                StringBuilder sb = new StringBuilder();
                for (String ip : ips) { if (sb.length() > 0) sb.append("\n"); sb.append(ip).append(":").append(server.port()); }
                addrView.setText(sb.toString());
                LocalName ln = ShareService.localName;
                altView.setText(ShareService.nameOn && ln != null ? "Nombre de este celular en la red: " + ln.host() : "");
            }
        }
        batteryLink.setVisibility(batteryFree() ? View.GONE : View.VISIBLE);
        cleanLink.setText(cleanLabel());
    }

    private void refreshChat(boolean force) {
        Chat.Pc p = currentPc == null ? null : Chat.byId(currentPc);
        if (p == null) { showHome(); return; }
        chatName.setText(p.name);
        chatState.setText(p.online() ? "● Conectada ahora"
                : p.lastSeen > 0 ? "Desconectada · vista a las " + hm(p.lastSeen) : "Desconectada · abrí su acceso en esa computadora");
        String sig = "c" + p.id + ":" + Chat.version();
        if (!force && sig.equals(shownSig)) return;
        boolean atBottom = !feedScroll.canScrollVertically(1);
        shownSig = sig;
        Chat.markRead(p.id);
        feed.removeAllViews();
        List<Chat.Msg> list = Chat.messages(p.id);
        if (list.isEmpty()) feed.addView(emptyChat(p));
        String lastDay = "";
        for (Chat.Msg m : list) {
            String day = DateUtils.isToday(m.time) ? "Hoy" : DateUtils.isToday(m.time + DateUtils.DAY_IN_MILLIS) ? "Ayer"
                    : new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es")).format(new Date(m.time));
            if (!day.equals(lastDay)) { feed.addView(dayChip(day)); lastDay = day; }
            feed.addView(bubble(m));
        }
        if (atBottom || force) scrollDown();
    }

    // ---------- batería y actualizaciones ----------

    private boolean batteryFree() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) { return true; }
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void showUpdate(Updater.Info info) {
        update = info;
        updateTitle.setText("Hay una versión nueva: " + info.name);
        updateNotes.setText(info.notes == null || info.notes.isEmpty() ? "Tenés la " + BuildConfig.VERSION_NAME + "." : info.notes);
        updateBtn.setText("⬇  Actualizar ahora");
        updateBtn.setEnabled(true);
        updateBox.setVisibility(View.VISIBLE);
    }

    private void manualCheck() {
        Toast.makeText(this, "Buscando actualizaciones…", Toast.LENGTH_SHORT).show();
        Updater.check(this, true, (info, err) -> {
            if (info != null) showUpdate(info);
            else Toast.makeText(this, err != null ? err : "Ya tenés la versión más reciente (" + BuildConfig.VERSION_NAME + ").",
                    Toast.LENGTH_LONG).show();
        });
    }

    private void startUpdate() {
        if (update == null) return;
        if (!Updater.canInstall(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Un permiso, una sola vez")
                .setMessage("Para instalar sus propias actualizaciones, Android te va a pedir que actives "
                        + "«Permitir de esta fuente» para PasaArchivos. Activalo y volvé con la flecha de atrás; "
                        + "la descarga empieza sola.")
                .setPositiveButton("Ir al permiso", (d, w) -> { pendingInstall = true; Updater.askPermission(this); })
                .setNegativeButton("Ahora no", null)
                .show();
            return;
        }
        doInstall();
    }

    private void doInstall() {
        updateBtn.setEnabled(false);
        updateBtn.setText("Descargando…");
        Updater.install(this, update, new Updater.Progress() {
            @Override public void onProgress(int pct) {
                updateBtn.setText(pct >= 100 ? "Abriendo el instalador…" : "Descargando  " + pct + " %");
            }
            @Override public void onError(String msg) {
                updateBtn.setEnabled(true);
                updateBtn.setText("⬇  Reintentar");
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------- visor de fotos y videos ----------

    private FrameLayout viewer;
    private LinearLayout viewerTop;
    private ImageView vImg;
    private android.widget.VideoView vVid;
    private TextView vName, vCount;
    private List<Chat.Msg> vList = new ArrayList<>();
    private int vIdx;
    private float vScale = 1f;
    private int vToken;

    private void buildViewer() {
        viewer = new FrameLayout(this);
        viewer.setBackgroundColor(Color.BLACK);
        viewer.setVisibility(View.GONE);
        viewer.setClickable(true);

        vImg = new ImageView(this);
        vImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.addView(vImg, new FrameLayout.LayoutParams(-1, -1));

        vVid = new android.widget.VideoView(this);
        vVid.setVisibility(View.GONE);
        viewer.addView(vVid, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        viewerTop = new LinearLayout(this);
        viewerTop.setGravity(Gravity.CENTER_VERTICAL);
        viewerTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.argb(190, 0, 0, 0), Color.argb(0, 0, 0, 0) }));
        TextView close = label("←", 24, Color.WHITE, false);
        close.setGravity(Gravity.CENTER);
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        close.setClickable(true);
        close.setOnClickListener(v -> closeViewer());
        viewerTop.addView(close);
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(6), 0, dp(6), 0);
        tt.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        vName = label("", 15, Color.WHITE, true);
        vName.setSingleLine(true);
        vName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        vCount = label("", 12, Color.argb(200, 255, 255, 255), false);
        tt.addView(vName);
        tt.addView(vCount);
        viewerTop.addView(tt);
        TextView share = label("Compartir", 14, Color.WHITE, true);
        share.setGravity(Gravity.CENTER);
        share.setPadding(dp(14), 0, dp(14), 0);
        share.setBackground(shape(Color.argb(60, 255, 255, 255), 18, 0));
        share.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(36)));
        share.setClickable(true);
        share.setOnClickListener(v -> shareCurrent());
        viewerTop.addView(share);
        viewer.addView(viewerTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        final android.view.ScaleGestureDetector sg = new android.view.ScaleGestureDetector(this,
            new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                    setZoom(vScale * d.getScaleFactor());
                    return true;
                }
            });
        final android.view.GestureDetector gd = new android.view.GestureDetector(this,
            new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                    setZoom(vScale > 1.05f ? 1f : 2.5f);
                    return true;
                }
                @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                    viewerTop.setVisibility(viewerTop.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
                    return true;
                }
                @Override public boolean onScroll(android.view.MotionEvent a, android.view.MotionEvent b, float dx, float dy) {
                    if (vScale <= 1.01f) return false;
                    float mx = vImg.getWidth() * (vScale - 1) / 2f, my = vImg.getHeight() * (vScale - 1) / 2f;
                    vImg.setTranslationX(Math.max(-mx, Math.min(mx, vImg.getTranslationX() - dx)));
                    vImg.setTranslationY(Math.max(-my, Math.min(my, vImg.getTranslationY() - dy)));
                    return true;
                }
                @Override public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                    if (vScale > 1.01f || a == null) return false;
                    float dx = b.getX() - a.getX();
                    if (Math.abs(dx) > dp(60) && Math.abs(vx) > Math.abs(vy)) { showAt(vIdx + (dx < 0 ? 1 : -1)); return true; }
                    return false;
                }
            });
        vImg.setOnTouchListener((v, e) -> { sg.onTouchEvent(e); gd.onTouchEvent(e); return true; });
        frame.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void setZoom(float z) {
        vScale = Math.max(1f, Math.min(5f, z));
        vImg.setScaleX(vScale);
        vImg.setScaleY(vScale);
        if (vScale <= 1.01f) { vImg.setTranslationX(0); vImg.setTranslationY(0); }
    }

    private void openViewer(Chat.Msg m) {
        if (m.uri == null || currentPc == null) { open(m.uri, m.mime); return; }
        vList = new ArrayList<>();
        for (Chat.Msg x : Chat.messages(currentPc)) if (x.uri != null && (x.isImage() || x.isVideo())) vList.add(x);
        int at = 0;
        for (int i = 0; i < vList.size(); i++) if (vList.get(i).id == m.id) at = i;
        hideKeyboard();
        viewer.setVisibility(View.VISIBLE);
        viewerTop.setVisibility(View.VISIBLE);
        getWindow().setStatusBarColor(Color.BLACK);
        setBack(true);
        showAt(at);
    }

    private void showAt(int i) {
        if (vList.isEmpty() || i < 0 || i >= vList.size()) return;
        vIdx = i;
        final Chat.Msg m = vList.get(i);
        vName.setText(m.name);
        vCount.setText((i + 1) + " de " + vList.size() + (m.size > 0 ? "  ·  " + human(m.size) : ""));
        setZoom(1f);
        vVid.stopPlayback();
        final int token = ++vToken;
        if (m.isVideo()) {
            vImg.setImageDrawable(null);
            vImg.setVisibility(View.GONE);
            vVid.setVisibility(View.VISIBLE);
            android.widget.MediaController mc = new android.widget.MediaController(this);
            mc.setAnchorView(vVid);
            vVid.setMediaController(mc);
            vVid.setVideoURI(m.uri);
            vVid.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "No se pudo reproducir aquí; se abre con otra app.", Toast.LENGTH_SHORT).show();
                open(m.uri, m.mime);
                return true;
            });
            vVid.start();
        } else {
            vVid.setVisibility(View.GONE);
            vImg.setVisibility(View.VISIBLE);
            final int px = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
            Bitmap quick = Thumbs.load(this, m.uri, true, 480);
            vImg.setImageBitmap(quick);
            thumbs.execute(() -> {
                Bitmap big = Thumbs.full(this, m.uri, Math.min(px, 2560));
                vImg.post(() -> {
                    if (token != vToken) return;
                    if (big != null) vImg.setImageBitmap(big);
                    else if (quick == null) Toast.makeText(this, "Esta imagen ya no está disponible en el celular.", Toast.LENGTH_SHORT).show();
                });
            });
        }
    }

    private void closeViewer() {
        vToken++;
        vVid.stopPlayback();
        vImg.setImageDrawable(null);
        viewer.setVisibility(View.GONE);
        if (chat.getVisibility() != View.VISIBLE) setBack(false);
    }

    private void shareCurrent() {
        if (vList.isEmpty()) return;
        Chat.Msg m = vList.get(vIdx);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(m.mime == null ? "*/*" : m.mime);
        i.putExtra(Intent.EXTRA_STREAM, m.uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "Compartir")); } catch (Exception ignored) {}
    }
}
