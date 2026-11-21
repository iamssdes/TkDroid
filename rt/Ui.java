package apple.rt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maps the tkinter widget API onto native Android views.
 *
 * Threading: the user's program (main script, callbacks, after() timers, event handlers) runs on one
 * dedicated "py" thread, so dialogs and the file picker can block like they do in Python. Android views
 * are only touched on the UI thread; every widget call made from the py thread is marshalled there.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class Ui {
    private Ui() {}

    static final String TAG = "ApkApple";
    static Context ctx;
    static Activity act;
    static Wid root;
    static Handler handler;           // UI thread
    static Handler pyHandler;         // py thread
    static Thread pyThread;
    static FrameLayout rootFrame;
    static LinearLayout content;
    static LinearLayout menuBarRow;
    static View menuBarHost;
    static Wid barMenu;
    static boolean overlay, draggable, started;
    /** Built-ins that `#builtins: NAME: disable` can switch off (set by MainActivity before start()). */
    public static boolean versionLabel = true, errorToast = true;
    /** dpooa + datasaving: the windows remember where they were (set by the generated MainActivity). */
    public static boolean windowMemory;
    /** ApkApple version this app was built with (set by the generated MainActivity), e.g. "v2.5.1 Patch 1". */
    public static String builtWith = "";
    static String title = "";
    static TextView titleView;
    static Object closeFn;
    static final AtomicInteger pending = new AtomicInteger();   // queued py-thread tasks (tests wait on it)
    static final List<Runnable> cancels = new ArrayList<Runnable>();

    // ------------------------------------------------------------------ threads

    static boolean onMain() { return Looper.myLooper() == Looper.getMainLooper(); }
    static boolean onPy() { return Thread.currentThread() == pyThread; }

    abstract static class Task { abstract Object run(); }

    /** Run on the UI thread and wait for the result (runs inline when already there). */
    static Object onUi(final Task t) {
        if (onMain()) return t.run();
        final Object[] res = new Object[2];
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(new Runnable() {
            public void run() {
                try { res[0] = t.run(); } catch (Throwable e) { res[1] = e; } finally { latch.countDown(); }
            }
        });
        try { latch.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        if (res[1] instanceof RuntimeException) throw (RuntimeException) res[1];
        if (res[1] != null) throw new RuntimeException((Throwable) res[1]);
        return res[0];
    }

    /** Run on the UI thread without waiting (ordering with onUi calls is preserved). */
    static void postUi(final Runnable r) {
        if (onMain()) r.run();
        else handler.post(r);
    }

    static void toPy(final Runnable r) {
        pending.incrementAndGet();
        pyHandler.post(new Runnable() {
            public void run() {
                try { r.run(); } finally { pending.decrementAndGet(); }
            }
        });
    }

    static void startPyThread() {
        final Object lock = new Object();
        pyThread = new Thread(null, new Runnable() {
            public void run() {
                Looper.prepare();
                synchronized (lock) { pyHandler = new Handler(Looper.myLooper()); lock.notifyAll(); }
                Looper.loop();
            }
        }, "apkapple-py", 32L << 20);
        pyThread.setDaemon(true);
        pyThread.start();
        synchronized (lock) {
            while (pyHandler == null) {
                try { lock.wait(50); } catch (InterruptedException e) { break; }
            }
        }
    }

    /** Run the user's program (module body) on the py thread. */
    public static void run(final Fn main) {
        toPy(new Runnable() {
            public void run() {
                try { main.call(); } catch (Throwable t) { scriptFailed(t); }
                Ext.saveState();
            }
        });
    }

    // ------------------------------------------------------------------ setup

    /** Returns true when the UI lives in a floating overlay (dpooa) and the activity should finish. */
    public static boolean start(Activity a, boolean wantOverlay, boolean drag) {
        if (started) return overlay;
        started = true;
        act = a;
        handler = new Handler(Looper.getMainLooper());
        draggable = drag || ovo.containsKey("drag");
        title = String.valueOf(a.getTitle());
        ctx = wantOverlay ? a.getApplicationContext() : a;
        Ext.baseDir = ctx.getFilesDir();
        Ext.closeHook = new Ext.CloseHook() { public void closed(String path) { savedFileClosed(path); } };
        Ext.reporter = new Ext.Reporter() { public void report(Throwable t) { callbackFailed(t); } };
        startPyThread();
        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        content.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        sv.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView mbh = new HorizontalScrollView(ctx);
        mbh.setHorizontalScrollBarEnabled(false);
        mbh.setBackgroundColor(0xFF263238);
        mbh.setVisibility(View.GONE);
        menuBarRow = new LinearLayout(ctx);
        menuBarRow.setOrientation(LinearLayout.HORIZONTAL);
        mbh.addView(menuBarRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        menuBarHost = mbh;
        column.addView(mbh, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View main = column;
        if (Debug.on) {
            main = Debug.wrap(ctx, column);            // app on top, log panel below it
            Py.sink = new Py.Sink() { public void out(String s, boolean err) { if (err) Debug.err(s); else Debug.out(s); } };
        }
        OvFrame mainFrame = new OvFrame(ctx);
        mainFrame.main = true;
        rootFrame = mainFrame;
        overlay = wantOverlay && buildOverlay(main);
        if (!overlay) {
            if (wantOverlay) { ctx = a; rootFrame.setBackgroundColor(0xFFFFFFFF); }   // permission missing: normal window
            rootFrame.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            a.setContentView(rootFrame);
        }
        if (versionLabel) addVersionLabel(a);
        root = new Wid("Tk", null);
        root.view = content;
        root.content = content;
        Py.root = root;
        return overlay;
    }

    /** Built-in: the app's version (versionName, e.g. "v1.4") and the ApkApple version it was built with, as small text at the bottom right, ending in an "About PocketTk" link. Touches outside the label pass through. */
    static void addVersionLabel(Activity a) {
        try {
            String v = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
            if (v == null || v.length() == 0) return;
            TextView t = new TextView(ctx);
            String txt = "v" + v;                    // "1.2.3" or "1.2.3-p2" (p = patch counter)
            int pi = v.lastIndexOf("-p");
            if (pi > 0) {
                try { txt = "v" + v.substring(0, pi) + " Patch " + Integer.parseInt(v.substring(pi + 2)); }
                catch (NumberFormatException ignored) { /* show it as is */ }
            }
            if (builtWith != null && builtWith.length() > 0) txt = txt + ", Built With PocketTk " + builtWith;
            // the end of the label is a link to the About PocketTk page
            String link = "About PocketTk";
            android.text.SpannableString sp = new android.text.SpannableString(txt + "  \u00B7  " + link);
            int from = sp.length() - link.length();
            sp.setSpan(new android.text.style.UnderlineSpan(), from, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new android.text.style.ForegroundColorSpan(0xCC4F9DDE), from, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            t.setText(sp);
            t.setGravity(Gravity.END);
            t.setTextColor(0x99808080);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            t.setPadding(dp(6), dp(6), dp(6), dp(3));         // a bigger spot to tap
            t.setClickable(true);
            t.setFocusable(false);
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openAbout(); } });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, dp(grip != null ? 30 : 0), 0);   // left of the resize grip when there is one
            rootFrame.addView(t, lp);
            versionView = t;
        } catch (Throwable ignored) { /* no label */ }
    }

    static TextView versionView;

    /** A notebook's tab strip is at the bottom of the screen: the version label sits just above it, not on it. */
    static void liftVersionLabel(int stripHeight) {
        TextView t = versionView;
        if (t == null || !(t.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) t.getLayoutParams();
        if (lp.bottomMargin == stripHeight) return;
        lp.bottomMargin = stripHeight;
        t.setLayoutParams(lp);
    }

    /** The About PocketTk page (apple.rt.About). Works from overlay windows too. */
    static void openAbout() {
        try {
            Intent i = new Intent(ctx, About.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) { warn("could not open the About PocketTk page: " + t); }
    }

    static WindowManager wm;
    static WindowManager.LayoutParams wlp;
    static View body;
    static View grip;
    static LinearLayout panelView;
    static FrameLayout.LayoutParams panelLp;
    static LinearLayout.LayoutParams scrollLp;
    static int savedH = -2, scrW, scrH;
    static boolean minimized, snapOn;

    // ---- dpooa window options (from `#dpooa:` comments, passed as "key=value;key=value")
    static final java.util.Map<String, String> ovo = new java.util.HashMap<String, String>();

    public static void ovSpec(String spec) {
        ovo.clear();
        if (spec == null) return;
        for (String kv : spec.split(";")) {
            if (kv.trim().length() == 0) continue;
            int i = kv.indexOf('=');
            if (i < 0) ovo.put(kv.trim(), "1"); else ovo.put(kv.substring(0, i).trim(), kv.substring(i + 1));
        }
    }

    /** "320" (dp), "50%" (of screen), "auto", "full"; returns pixels or -2 / -1. */
    static int dimPx(String v, int screen, int dflt) {
        if (v == null) return dflt;
        v = v.trim();
        if (v.equals("auto") || v.equals("wrap")) return -2;
        if (v.equals("full") || v.equals("match")) return -1;
        try {
            if (v.endsWith("%")) return Math.round(screen * Float.parseFloat(v.substring(0, v.length() - 1)) / 100f);
            return dp(Float.parseFloat(v));
        } catch (Throwable t) { return dflt; }
    }

    static int colorOf(String s, int dflt) {
        try {
            String h = s.startsWith("#") ? s.substring(1) : s;
            if (h.length() == 6) h = "FF" + h;
            return (int) Long.parseLong(h, 16);
        } catch (Throwable t) { return dflt; }
    }

    static void toTopStart() {
        if (wlp.gravity == (Gravity.TOP | Gravity.START)) return;
        int[] loc = new int[2];
        rootFrame.getLocationOnScreen(loc);
        wlp.gravity = Gravity.TOP | Gravity.START;
        wlp.x = loc[0];
        wlp.y = loc[1];
    }

    static void applyHeightMode() {
        if (panelLp == null || scrollLp == null) return;
        boolean fixed = !minimized && (wlp.height > 0 || wlp.height == -1);
        panelLp.height = fixed ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        scrollLp.height = fixed ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        scrollLp.weight = fixed ? 1f : 0f;
        panelView.requestLayout();
        body.requestLayout();
    }

    static void setMinimized(boolean hide) {
        if (body == null || hide == minimized) return;
        minimized = hide;
        if (hide && rootFrame instanceof OvFrame) ((OvFrame) rootFrame).endTyping();
        body.setVisibility(hide ? 8 : 0);
        if (grip != null) grip.setVisibility(hide ? 8 : 0);
        if (hide) { savedH = wlp.height; wlp.height = ViewGroup.LayoutParams.WRAP_CONTENT; }
        else wlp.height = savedH;
        applyHeightMode();
        updMain();
    }

    static String lockedTitle() { return ovo.containsKey("title") ? ovo.get("title") : title; }

    static int pxPerDp() { return Math.max(1, dp(100) / 100); }

    /** root.geometry("WxH+X+Y") inside a dpooa window (sizes in dp, like the #dpooa: comments). */
    static void applyGeometry(String g) {
        if (!overlay || wlp == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(?:(\\d+)x(\\d+))?\\s*(?:([+-]\\d+)([+-]\\d+))?\\s*$").matcher(g);
        if (!m.matches()) return;
        if (m.group(3) != null) toTopStart();
        if (m.group(1) != null) {
            wlp.width = dp(Integer.parseInt(m.group(1)));
            wlp.height = dp(Integer.parseInt(m.group(2)));
            minimized = false; body.setVisibility(0);
            if (grip != null) grip.setVisibility(0);
        }
        if (m.group(3) != null) {
            int x = Integer.parseInt(m.group(3)), y = Integer.parseInt(m.group(4));
            int w = wlp.width > 0 ? wlp.width : rootFrame.getWidth();
            int h = wlp.height > 0 ? wlp.height : rootFrame.getHeight();
            wlp.x = x >= 0 ? dp(x) : scrW - w - dp(-x);
            wlp.y = y >= 0 ? dp(y) : scrH - h - dp(-y);
        }
        applyHeightMode();
        updMain();
    }

    static String currentGeometry() {
        int d = pxPerDp();
        int[] loc = new int[2];
        if (overlay) rootFrame.getLocationOnScreen(loc);
        return (rootFrame.getWidth() / d) + "x" + (rootFrame.getHeight() / d) + "+" + (loc[0] / d) + "+" + (loc[1] / d);
    }

    static void setWindowAlpha(float a) {
        if (!overlay || wlp == null) return;
        wlp.alpha = Math.max(0.1f, Math.min(1f, a));
        updMain();
    }

    // ---- window memory (dpooa + datasaving): positions, sizes and the collapsed state are saved and restored ----

    static final Runnable memSaver = new Runnable() {
        public void run() { toPy(new Runnable() { public void run() { Ext.saveState(); } }); }
    };

    /** Writes the remembered window state to disk soon (a drag sends many updates; one write follows the last). */
    static void persistSoon() {
        if (!windowMemory || handler == null) return;
        handler.removeCallbacks(memSaver);
        handler.postDelayed(memSaver, 500);
    }

    static float dens() { return ctx.getResources().getDisplayMetrics().density; }

    /** {x, y, w?, h?, min} of a window in dp; w/h only when the size is fixed (an auto-size window stays auto). */
    static Map<Object, Object> memState(WindowManager.LayoutParams p, View frame, boolean min, int fullH) {
        int x = p.x, y = p.y;
        if (p.gravity != (Gravity.TOP | Gravity.START)) {          // placed with "at": read where it really is
            int[] loc = new int[2];
            if (frame.getWidth() == 0) return null;                // not laid out yet
            try { frame.getLocationOnScreen(loc); } catch (Throwable t) { return null; }
            x = loc[0]; y = loc[1];
        }
        float d = dens();
        Map<Object, Object> m = new LinkedHashMap<Object, Object>();
        m.put("x", (long) Math.round(x / d));
        m.put("y", (long) Math.round(y / d));
        if (p.width > 0) m.put("w", (long) Math.round(p.width / d));
        int h = min ? fullH : p.height;
        if (h > 0) m.put("h", (long) Math.round(h / d));
        m.put("min", Boolean.valueOf(min));
        return m;
    }

    static void rememberMain() {
        if (!windowMemory || !overlay || wlp == null || rootFrame == null) return;
        Map<Object, Object> m = memState(wlp, rootFrame, minimized, savedH);
        if (m == null) return;
        Ext.putWindowState("main", m);
        persistSoon();
    }

    /** Layout update of the main dpooa window, remembered for the next start. */
    static void updMain() {
        try { wm.updateViewLayout(rootFrame, wlp); } catch (Throwable ignored) { /* not attached yet */ }
        rememberMain();
    }

    static int memNum(Object v, int dflt) { return v instanceof Number ? Math.round(((Number) v).floatValue() * dens()) : dflt; }

    /** Puts a window back where it was. Returns the saved "collapsed" state (false when nothing was saved). */
    static boolean applyMem(Object st, WindowManager.LayoutParams p) {
        if (!(st instanceof Map)) return false;
        Map m = (Map) st;
        if (m.get("w") instanceof Number) p.width = Math.max(dp(140), Math.min(memNum(m.get("w"), p.width), scrW));
        if (m.get("h") instanceof Number) p.height = Math.max(dp(100), Math.min(memNum(m.get("h"), p.height), scrH));
        if (m.get("x") instanceof Number && m.get("y") instanceof Number) {
            p.gravity = Gravity.TOP | Gravity.START;
            int w = p.width > 0 ? p.width : dp(200);
            p.x = Math.max(dp(60) - w, Math.min(memNum(m.get("x"), p.x), scrW - dp(60)));    // keep a part of it on screen
            p.y = Math.max(0, Math.min(memNum(m.get("y"), p.y), scrH - dp(48)));
        }
        return Boolean.TRUE.equals(m.get("min"));
    }

    static boolean buildOverlay(View scroll) {
        try {
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                Intent i = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION", Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(0x10000000);
                ctx.startActivity(i);
                toast("ApkApple: allow \"Display over other apps\" for this app, then open it again. Showing a normal window for now.");
                return false;
            }
            wm = (WindowManager) ctx.getSystemService("window");
            scrW = ctx.getResources().getDisplayMetrics().widthPixels;
            scrH = ctx.getResources().getDisplayMetrics().heightPixels;
            int sw = scrW;
            LinearLayout panel = new LinearLayout(ctx);
            panelView = panel;
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(colorOf(ovo.get("bg"), 0xFFFFFFFF));
            LinearLayout bar = new LinearLayout(ctx);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackgroundColor(colorOf(ovo.get("bar"), 0xFF37474F));
            bar.setPadding(dp(10), dp(4), dp(4), dp(4));
            titleView = new TextView(ctx);
            titleView.setText(draggable ? "☰  " + lockedTitle() : lockedTitle());
            titleView.setTextColor(0xFFFFFFFF);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            body = scroll;
            if (!ovo.containsKey("nomin")) {
                Button min = new Button(ctx);
                min.setText("–");
                min.setTextColor(0xFFFFFFFF);
                min.setBackgroundColor(0x00000000);
                min.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { setMinimized(!minimized); }
                });
                bar.addView(min, new LinearLayout.LayoutParams(dp(44), dp(36)));
            }
            if (!ovo.containsKey("noclose")) {
                Button close = new Button(ctx);
                close.setText("×");
                close.setTextColor(0xFFFFFFFF);
                close.setBackgroundColor(0x00000000);
                close.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { closePressed(); }
                });
                bar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(36)));
            }
            panel.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            panel.addView(scroll, scrollLp);
            panelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rootFrame.addView(panel, panelLp);

            // Not focusable by default (0x8) so the keyboard and the back key keep going to whatever is behind the window
            // (another app, another window). It becomes focusable only while the user types into one of its fields
            // (see OvFrame), and 0x40000 (watch outside touch) tells it when the user went somewhere else.
            int flags = 0x20 | 0x200 | 0x8 | 0x40000;
            if (ovo.containsKey("click")) flags |= 0x10;           // not touchable: a click-through HUD
            wlp = new WindowManager.LayoutParams(
                dimPx(ovo.get("w"), sw, Math.max(dp(300), (int) (sw * 0.92f))),
                dimPx(ovo.get("h"), scrH, ViewGroup.LayoutParams.WRAP_CONTENT), 2038, flags, -3);
            ((OvFrame) rootFrame).lp = wlp;
            wlp.gravity = Gravity.TOP | Gravity.START;
            wlp.x = (sw - (wlp.width > 0 ? wlp.width : sw)) / 2;
            wlp.y = dp(80);
            String at = ovo.get("at");
            if (at != null) {
                int m = dp(8);
                if (at.equals("center")) { wlp.gravity = Gravity.CENTER; wlp.x = 0; wlp.y = 0; }
                else if (at.equals("top")) { wlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; wlp.x = 0; wlp.y = m; }
                else if (at.equals("bottom")) { wlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; wlp.x = 0; wlp.y = m; }
                else if (at.equals("left")) { wlp.gravity = Gravity.START | Gravity.CENTER_VERTICAL; wlp.x = m; wlp.y = 0; }
                else if (at.equals("right")) { wlp.gravity = Gravity.END | Gravity.CENTER_VERTICAL; wlp.x = m; wlp.y = 0; }
                else if (at.equals("topleft")) { wlp.gravity = Gravity.TOP | Gravity.START; wlp.x = m; wlp.y = m; }
                else if (at.equals("topright")) { wlp.gravity = Gravity.TOP | Gravity.END; wlp.x = m; wlp.y = m; }
                else if (at.equals("bottomleft")) { wlp.gravity = Gravity.BOTTOM | Gravity.START; wlp.x = m; wlp.y = m; }
                else if (at.equals("bottomright")) { wlp.gravity = Gravity.BOTTOM | Gravity.END; wlp.x = m; wlp.y = m; }
            } else {
                if (ovo.containsKey("x")) wlp.x = dimPx(ovo.get("x"), sw, wlp.x);
                if (ovo.containsKey("y")) wlp.y = dimPx(ovo.get("y"), scrH, wlp.y);
            }
            wlp.softInputMode = 0x10;
            if (ovo.containsKey("alpha")) {
                try { wlp.alpha = Math.max(0.1f, Math.min(1f, Float.parseFloat(ovo.get("alpha")))); } catch (Throwable ignored) { /* keep opaque */ }
            }
            snapOn = ovo.containsKey("snap");
            boolean startMin = ovo.containsKey("min0");
            if (windowMemory) {                                     // where the user left it wins over pos/size/min0
                Object st = Ext.windowState("main");
                if (st != null) startMin = applyMem(st, wlp);
            }
            if (draggable) {
                bar.setOnTouchListener(new View.OnTouchListener() {
                    float dx, dy;
                    public boolean onTouch(View v, MotionEvent e) {
                        int a = e.getAction();
                        if (a == 0) { toTopStart(); dx = wlp.x - e.getRawX(); dy = wlp.y - e.getRawY(); }
                        else if (a == 2) {
                            wlp.x = (int) (dx + e.getRawX());
                            wlp.y = (int) (dy + e.getRawY());
                            updMain();
                        } else if (a == 1 && snapOn) {
                            int w = wlp.width > 0 ? wlp.width : rootFrame.getWidth();
                            wlp.x = (wlp.x + w / 2 < scrW / 2) ? dp(4) : scrW - w - dp(4);
                            updMain();
                        }
                        return true;
                    }
                });
            }
            if (ovo.containsKey("resize")) {
                TextView g = new TextView(ctx);
                g.setText("◢");
                g.setTextColor(0xFF78909C);
                g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
                g.setGravity(Gravity.CENTER);
                grip = g;
                rootFrame.addView(g, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM | Gravity.END));
                g.setOnTouchListener(new View.OnTouchListener() {
                    float sx, sy; int w0, h0;
                    public boolean onTouch(View v, MotionEvent e) {
                        int a = e.getAction();
                        if (a == 0) {
                            toTopStart();
                            sx = e.getRawX(); sy = e.getRawY();
                            w0 = wlp.width > 0 ? wlp.width : rootFrame.getWidth();
                            h0 = wlp.height > 0 ? wlp.height : rootFrame.getHeight();
                            wlp.width = w0; wlp.height = h0;
                            applyHeightMode();
                        } else if (a == 2) {
                            wlp.width = Math.max(dp(140), w0 + (int) (e.getRawX() - sx));
                            wlp.height = Math.max(dp(100), h0 + (int) (e.getRawY() - sy));
                            updMain();
                        }
                        return true;
                    }
                });
            }
            applyHeightMode();
            if (startMin) {
                minimized = true;
                savedH = wlp.height;
                wlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                body.setVisibility(8);
                if (grip != null) grip.setVisibility(8);
                applyHeightMode();
            }
            rootFrame.getViewTreeObserver().addOnWindowFocusChangeListener(new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
                public void onWindowFocusChanged(boolean hasFocus) { if (hasFocus) dialogFrame = null; }
            });
            wm.addView(rootFrame, wlp);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "overlay failed", t);
            toast("ApkApple: could not create the overlay (" + t + "). Showing a normal window.");
            return false;
        }
    }

    static void closePressed() {
        if (closeFn != null) call(closeFn);
        else quit();
    }

    // ------------------------------------------------------------------ dpooa: Toplevel = its own window

    static final List<OvWin> wins = new ArrayList<OvWin>();
    static int topSeq;                     // Toplevels created so far (the fallback name of one made without name=)
    static FrameLayout dialogFrame;        // the Toplevel window that last had the focus (dialogs open there); null = main window

    /**
     * Root view of every dpooa window. The window is NOT focusable by default, so it never takes the keyboard or the
     * back key away from other apps or from the other dpooa windows. It becomes focusable only while the user types
     * into one of its own fields, and gives the keyboard back as soon as the user touches anything outside it, presses
     * back, or the field loses focus. Outside dpooa (lp == null) it is a plain FrameLayout.
     */
    static final class OvFrame extends FrameLayout {
        WindowManager.LayoutParams lp;     // the window's own params (set once the window exists)
        boolean main;                      // the main dpooa window (not a Toplevel)
        boolean typing;
        View typingView;

        OvFrame(Context c) { super(c); }

        private void remember() { dialogFrame = main ? null : this; }   // dialogs open in the window the user touched last

        private boolean canType() { return lp != null && (lp.flags & 0x10) == 0; }   // 0x10: click-through HUD, no input

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_OUTSIDE) {
                endTyping();                                   // the user touched another window or app
            } else if (a == MotionEvent.ACTION_DOWN) {
                remember();
                EditText t = inputAt(this, e.getX(), e.getY());
                if (t != null) beginTyping(t);
            }
            return super.dispatchTouchEvent(e);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (typing && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) endTyping();   // back closes the keyboard and lets go of the focus
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus && typing) showKeyboard();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnGlobalFocusChangeListener(new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
                public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                    if (!typing || newFocus instanceof EditText) return;
                    post(new Runnable() {          // re-check after the layout settles: focus may only be moving
                        public void run() {
                            View f = findFocus();
                            if (typing && !(f instanceof EditText)) endTyping();
                        }
                    });
                }
            });
        }

        /** The window takes the keyboard (becomes focusable) and shows it for this field. */
        void beginTyping(View field) {
            if (!canType() || !isAttachedToWindow()) return;
            typing = true;
            typingView = field;
            post(new Runnable() {                  // not in the middle of the touch event that is still being delivered
                public void run() {
                    if (!typing) return;
                    if ((lp.flags & 0x8) != 0) {
                        lp.flags &= ~0x8;
                        try { wm.updateViewLayout(OvFrame.this, lp); } catch (Throwable ignored) { /* not attached */ }
                    }
                    if (typingView != null) typingView.requestFocus();
                    showKeyboard();
                }
            });
        }

        /** Gives the keyboard (and the back key) back to whatever is behind this window. */
        void endTyping() {
            if (lp == null) return;
            boolean wasTyping = typing;
            typing = false;
            typingView = null;
            if (wasTyping) {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) ctx.getSystemService("input_method");
                    if (imm != null && getWindowToken() != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
                } catch (Throwable ignored) { /* no keyboard to hide */ }
            }
            if ((lp.flags & 0x8) == 0 && (lp.flags & 0x10) == 0) {
                lp.flags |= 0x8;
                if (isAttachedToWindow()) try { wm.updateViewLayout(this, lp); } catch (Throwable ignored) { /* gone */ }
            }
        }

        private void showKeyboard() {
            final View v = typingView;
            if (v == null) return;
            Runnable r = new Runnable() {
                public void run() {
                    if (!typing || typingView != v || !v.isAttachedToWindow()) return;
                    try {
                        if (!v.hasFocus()) v.requestFocus();
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager) ctx.getSystemService("input_method");
                        if (imm != null) imm.showSoftInput(v, 0);
                    } catch (Throwable ignored) { /* try again below */ }
                }
            };
            r.run();
            postDelayed(r, 120);                   // the window gets its focus a moment after the flag changes
            postDelayed(r, 350);
        }
    }

    /** The editable text field under the finger (x, y in v's own coordinates), or null. */
    static EditText inputAt(View v, float x, float y) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (x < 0 || y < 0 || x > v.getWidth() || y > v.getHeight()) return null;
        if (v instanceof EditText) {
            EditText et = (EditText) v;
            return et.isEnabled() && et.getKeyListener() != null ? et : null;   // readonly fields have no key listener
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View c = g.getChildAt(i);
                float cx = x + g.getScrollX() - c.getLeft(), cy = y + g.getScrollY() - c.getTop();
                if (c.getVisibility() != View.VISIBLE || cx < 0 || cy < 0 || cx > c.getWidth() || cy > c.getHeight()) continue;
                EditText r = inputAt(c, cx, cy);
                if (r != null) return r;
                if (c.isClickable()) return null;   // a button or a dialog's backdrop on top takes the touch
            }
        }
        return null;
    }

    /** A script asked for the keyboard (focus_set, a dialog's text field): make its dpooa window take it. */
    static void wantKeyboard(View field) {
        for (android.view.ViewParent p = field.getParent(); p != null; p = p.getParent()) {
            if (p instanceof OvFrame) { ((OvFrame) p).beginTyping(field); return; }
        }
    }

    /** With dpooa, `Toplevel(root)` opens a floating window of its own (title bar, minimize, close, drag). */
    static final class OvWin {
        static final Object PASS = new Object();
        Wid owner;
        FrameLayout frame;
        LinearLayout panel;
        ScrollView scroll;
        TextView titleTv;
        WindowManager.LayoutParams lp;
        LinearLayout.LayoutParams scrollLp;
        FrameLayout.LayoutParams panelLp;
        View grip;
        String title;
        Object closeFn;
        boolean minimized, shown, dead;
        int savedH = -2;

        String key;        // the name its place is remembered under (Toplevel(root, name="x"), else its creation number)

        OvWin(Wid owner, String title, String name) {
            this.owner = owner;
            this.title = title;
            this.key = name != null && name.length() > 0 ? name : "toplevel-" + (++topSeq);
            frame = new OvFrame(ctx);
            panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(colorOf(ovo.get("bg"), 0xFFFFFFFF));
            LinearLayout bar = new LinearLayout(ctx);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackgroundColor(colorOf(ovo.get("bar"), 0xFF37474F));
            bar.setPadding(dp(10), dp(4), dp(4), dp(4));
            titleTv = new TextView(ctx);
            titleTv.setTextColor(0xFFFFFFFF);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            showTitle();
            bar.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!ovo.containsKey("nomin")) {
                Button min = new Button(ctx);
                min.setText("–");
                min.setTextColor(0xFFFFFFFF);
                min.setBackgroundColor(0x00000000);
                min.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { setMinimized(!minimized); }
                });
                bar.addView(min, new LinearLayout.LayoutParams(dp(44), dp(36)));
            }
            Button close = new Button(ctx);
            close.setText("×");
            close.setTextColor(0xFFFFFFFF);
            close.setBackgroundColor(0x00000000);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { closePressed(); }
            });
            bar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(36)));
            panel.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            scroll = new ScrollView(ctx);
            scroll.setFillViewport(true);
            int pad = dp(12);
            owner.content.setPadding(pad, pad, pad, pad);
            scroll.addView(owner.content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            panel.addView(scroll, scrollLp);
            panelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            frame.addView(panel, panelLp);

            int n = wins.size() % 5;                  // cascade, so a new window does not hide the one that opened it
            int width = Math.min(dp(320), (int) (scrW * 0.92f));
            lp = new WindowManager.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, 2038, 0x20 | 0x200 | 0x8 | 0x40000, -3);   // not focusable until typing, see OvFrame
            ((OvFrame) frame).lp = lp;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Math.max(0, (scrW - width) / 2 + dp(24) * n);
            lp.y = dp(80) + dp(32) * n;
            lp.softInputMode = 0x10;
            if (ovo.containsKey("alpha")) {
                try { lp.alpha = Math.max(0.1f, Math.min(1f, Float.parseFloat(ovo.get("alpha")))); } catch (Throwable ignored) { /* keep opaque */ }
            }
            bar.setOnTouchListener(new View.OnTouchListener() {      // drag by the title bar
                float dx, dy;
                public boolean onTouch(View v, MotionEvent e) {
                    int ac = e.getAction();
                    if (ac == 0) { dx = lp.x - e.getRawX(); dy = lp.y - e.getRawY(); }
                    else if (ac == 2) { lp.x = (int) (dx + e.getRawX()); lp.y = (int) (dy + e.getRawY()); update(); }
                    return true;
                }
            });
            if (ovo.containsKey("resize")) {
                TextView g = new TextView(ctx);
                g.setText("◢");
                g.setTextColor(0xFF78909C);
                g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
                g.setGravity(Gravity.CENTER);
                grip = g;
                frame.addView(g, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM | Gravity.END));
                g.setOnTouchListener(new View.OnTouchListener() {
                    float sx, sy; int w0, h0;
                    public boolean onTouch(View v, MotionEvent e) {
                        int ac = e.getAction();
                        if (ac == 0) {
                            sx = e.getRawX(); sy = e.getRawY();
                            w0 = lp.width > 0 ? lp.width : frame.getWidth();
                            h0 = lp.height > 0 ? lp.height : frame.getHeight();
                            lp.width = w0; lp.height = h0;
                            applyHeightMode();
                        } else if (ac == 2) {
                            lp.width = Math.max(dp(140), w0 + (int) (e.getRawX() - sx));
                            lp.height = Math.max(dp(100), h0 + (int) (e.getRawY() - sy));
                            update();
                        }
                        return true;
                    }
                });
            }
            frame.getViewTreeObserver().addOnWindowFocusChangeListener(new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
                public void onWindowFocusChanged(boolean hasFocus) { if (hasFocus) dialogFrame = frame; }
            });
            boolean startMin = windowMemory && applyMem(Ext.windowState(key), lp);   // back where it was last time
            applyHeightMode();
            wins.add(this);
            if (startMin && !ovo.containsKey("nomin")) setMinimized(true);
            show();
            remember();
        }

        /** Remembers this window's place (datasaving); it is written to disk a moment after the last change. */
        void remember() {
            if (!windowMemory || dead) return;
            Map<Object, Object> m = memState(lp, frame, minimized, savedH);
            if (m == null) return;
            Ext.putWindowState(key, m);
            persistSoon();
        }

        void showTitle() { titleTv.setText("☰  " + title); }

        void update() {
            if (!shown) return;
            try { wm.updateViewLayout(frame, lp); } catch (Throwable ignored) { /* not attached */ }
            remember();
        }

        void applyHeightMode() {
            boolean fixed = !minimized && (lp.height > 0 || lp.height == -1);
            panelLp.height = fixed ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
            scrollLp.height = fixed ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
            scrollLp.weight = fixed ? 1f : 0f;
            panel.requestLayout();
            scroll.requestLayout();
        }

        void setMinimized(boolean hide) {
            if (hide == minimized) return;
            minimized = hide;
            if (hide) ((OvFrame) frame).endTyping();
            scroll.setVisibility(hide ? 8 : 0);
            if (grip != null) grip.setVisibility(hide ? 8 : 0);
            if (hide) { savedH = lp.height; lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; }
            else lp.height = savedH;
            applyHeightMode();
            update();
        }

        void show() {
            if (shown || dead) return;
            try { wm.addView(frame, lp); shown = true; }
            catch (Throwable t) { Log.e(TAG, "window show failed", t); }
        }

        void hide() {
            if (!shown) return;
            ((OvFrame) frame).endTyping();
            try { wm.removeView(frame); } catch (Throwable ignored) { /* gone already */ }
            shown = false;
            if (dialogFrame == frame) dialogFrame = null;
        }

        void raise() {
            if (!shown || dead) return;
            try { wm.removeView(frame); wm.addView(frame, lp); } catch (Throwable ignored) { /* ignore */ }
        }

        void close() {
            if (dead) return;
            remember();                  // a closed window still remembers its place for when it is opened again
            hide();
            dead = true;
            wins.remove(this);
        }

        void closePressed() {
            if (closeFn != null) call(closeFn);
            else close();
        }

        String geometry() {
            int d = pxPerDp();
            int[] loc = new int[2];
            try { frame.getLocationOnScreen(loc); } catch (Throwable ignored) { /* not attached */ }
            return (frame.getWidth() / d) + "x" + (frame.getHeight() / d) + "+" + (loc[0] / d) + "+" + (loc[1] / d);
        }

        /** "WxH+X+Y" in dp; negative offsets count from the right / bottom. */
        void applyGeometry(String g) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(?:(\\d+)x(\\d+))?\\s*(?:([+-]\\d+)([+-]\\d+))?\\s*$").matcher(g);
            if (!m.matches()) return;
            if (m.group(1) != null) {
                lp.width = dp(Integer.parseInt(m.group(1)));
                lp.height = dp(Integer.parseInt(m.group(2)));
                minimized = false;
                scroll.setVisibility(0);
                if (grip != null) grip.setVisibility(0);
            }
            if (m.group(3) != null) {
                int x = Integer.parseInt(m.group(3)), y = Integer.parseInt(m.group(4));
                int w = lp.width > 0 ? lp.width : frame.getWidth();
                int h = lp.height > 0 ? lp.height : frame.getHeight();
                lp.x = x >= 0 ? dp(x) : scrW - w - dp(-x);
                lp.y = y >= 0 ? dp(y) : scrH - h - dp(-y);
            }
            applyHeightMode();
            update();
        }

        /** Window methods of the Toplevel; PASS = not a window method (the widget code handles it). */
        Object method(String name, Map<String, Object> kw, Object a0, Object a1, int npos) {
            switch (name) {
                case "title": case "wm_title":
                    if (a0 != null) { title = Py.str(a0); showTitle(); }
                    return title;
                case "protocol":
                    if (npos > 1 && Py.str(a0).equals("WM_DELETE_WINDOW")) closeFn = a1;
                    return null;
                case "geometry": case "wm_geometry":
                    if (a0 == null) return geometry();
                    applyGeometry(Py.str(a0));
                    return null;
                case "attributes": case "wm_attributes":
                    if (a0 != null && Py.str(a0).equals("-alpha") && a1 != null) {
                        lp.alpha = Math.max(0.1f, Math.min(1f, (float) Py.dbl(a1)));
                        update();
                    }
                    return null;
                case "iconify": setMinimized(true); return null;
                case "deiconify": show(); setMinimized(false); return null;
                case "withdraw": hide(); return null;
                case "lift": case "tkraise": raise(); return null;
                case "destroy": close(); return null;
                case "config": case "configure":
                    if (kw.containsKey("title")) { title = Py.str(kw.get("title")); showTitle(); }
                    return PASS;
                default: return PASS;
            }
        }
    }

    /** Back button; true when handled. */
    public static boolean back() {
        if (!cancels.isEmpty()) {
            Runnable r = cancels.remove(cancels.size() - 1);
            r.run();
            return true;
        }
        if (closeFn != null) { call(closeFn); return true; }
        return false;
    }

    public static void quit() {
        Ext.saveState();
        postUi(new Runnable() {
            public void run() {
                for (OvWin ow : new ArrayList<OvWin>(wins)) ow.close();
                try { if (overlay && wm != null) wm.removeView(rootFrame); } catch (Throwable ignored) { /* gone already */ }
                if (act != null) try { act.finish(); } catch (Throwable ignored) { /* ignore */ }
                if (overlay) android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    /** Number of queued py-thread tasks (the test harness waits for 0). */
    public static int busy() { return pending.get(); }

    public static void onPause() { toPy(new Runnable() { public void run() { Ext.saveState(); } }); }

    public static boolean isOverlay() { return overlay; }

    public static Object tk() { return root; }

    /** Widget created with **kwargs / *args: first plain argument is the parent. */
    public static Object wk(String kind, Object[] args) {
        Object parent = null;
        List<Object> kv = new ArrayList<Object>();
        for (Object x : args) {
            if (x instanceof Py.Kw) {
                Py.Kw k = (Py.Kw) x;
                if (k.k.equals("master") || k.k.equals("parent")) parent = k.v; else { kv.add(k.k); kv.add(k.v); }
            } else if (parent == null) parent = x;
        }
        return w(kind, parent, kv.toArray());
    }

    /** input(prompt): a text dialog; empty string when cancelled. */
    public static Object input(Object prompt) {
        Object r = simple("askstring", "Input", prompt == null ? "" : prompt, null, null, null, null);
        return r == null ? "" : r;
    }

    /** ttk.LabeledScale: a Frame with a Label that shows the number and a Scale, both on one variable. */
    public static Object labeledScale(final Object parent, final Object... kv) {
        Object var = null, from = 0L, to = 10L, compound = "top";
        List<Object> rest = new ArrayList<Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            if (k.equals("variable")) var = kv[i + 1];
            else if (k.equals("from_")) from = kv[i + 1];
            else if (k.equals("to")) to = kv[i + 1];
            else if (k.equals("compound")) compound = kv[i + 1];
        }
        if (!(var instanceof Py.Var)) var = new Py.Var("IntVar", from);
        Object frame = w("Frame", parent);
        Object lab = w("Label", frame, "textvariable", var);
        Object sc = w("Scale", frame, "variable", var, "from_", from, "to", to, "orient", "horizontal");
        boolean labelFirst = !"bottom".equals(String.valueOf(compound));
        Object[] order = labelFirst ? new Object[]{lab, sc} : new Object[]{sc, lab};
        for (Object o : order) ((Py.Obj) o).callm("pack", new Object[]{Py.kw("fill", "x")});
        return frame;
    }

    public static Object dummy() {
        return new Py.Obj() { public Object callm(String n, Object[] a) { return null; } };
    }

    /** Pixels per dp, exact (dp(1) is rounded to a whole pixel, which scaled canvas drawing wrongly on 2.625-density phones). */
    static float density() {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, ctx.getResources().getDisplayMetrics());
    }

    static int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    /** A runtime warning: logcat, plus the debug panel when the app was built with --debug. */
    static void warn(String msg) {
        Log.w(TAG, msg);
        Debug.warn(msg);
    }

    static void toast(final String s) {
        postUi(new Runnable() {
            public void run() {
                try { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show(); } catch (Throwable t) { Log.e(TAG, "toast failed", t); }
            }
        });
    }

    // ------------------------------------------------------------------ errors

    static void writeErrorLog(String tb) {
        try {
            File f = new File(ctx.getFilesDir(), "error.log");
            OutputStream os = new FileOutputStream(f, true);
            try { os.write((new java.util.Date() + "\n" + tb + "\n\n").getBytes("UTF-8")); } finally { os.close(); }
        } catch (Throwable ignored) { /* best effort */ }
    }

    static boolean isExit(Throwable t) { return Py.wrap(t).type.equals("SystemExit"); }

    /** The main script raised: show the traceback on screen and in the log. */
    static void scriptFailed(Throwable t) {
        if (isExit(t)) { quit(); return; }
        final String tb = Ext.traceback(t);
        Log.e(TAG, tb);
        Debug.err(tb + "\n");
        writeErrorLog(tb);
        postUi(new Runnable() {
            public void run() {
                try {
                    TextView tv = new TextView(ctx);
                    tv.setText("ApkApple: your program stopped with an error\n\n" + tb);
                    tv.setTextColor(0xFF000000);
                    tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                    tv.setTextIsSelectable(true);
                    tv.setPadding(dp(16), dp(16), dp(16), dp(16));
                    content.removeAllViews();
                    content.addView(tv);
                } catch (Throwable ignored) { /* nothing else to do */ }
            }
        });
    }

    public static void fatal(Throwable t) { scriptFailed(t); }

    /** A callback / event handler / thread raised: log it and show a toast; the app keeps running. */
    static void callbackFailed(Throwable t) {
        if (isExit(t)) { quit(); return; }
        String tb = Ext.traceback(t);
        Log.e(TAG, tb);
        Debug.err(tb + "\n");
        writeErrorLog(tb);
        String last = tb.substring(tb.lastIndexOf('\n') + 1);
        int ln = tb.lastIndexOf(", line ");
        String where = "";
        if (ln >= 0) {
            int e = tb.indexOf('\n', ln);
            where = " (line " + tb.substring(ln + 7, e < 0 ? tb.length() : e) + ")";
        }
        if (errorToast) toast("Error" + where + ": " + last);
    }

    /** Run a user callback on the py thread; errors become a toast instead of crashing the app. */
    static void call(final Object f, final Object... args) {
        if (f == null) return;
        Runnable r = new Runnable() {
            public void run() {
                try { Py.call(f, args); } catch (Throwable t) { callbackFailed(t); }
                Ext.saveState();
            }
        };
        if (onPy()) r.run(); else toPy(r);
    }

    // ------------------------------------------------------------ public API

    public static Object w(final String kind, final Object parent, final Object... kv) {
        return onUi(new Task() {
            Object run() {
                Object par = Oop.unwrap(parent);
                Wid p = par instanceof Wid ? (Wid) par : root;
                if (kind.equals("Tk")) return root;
                Wid w = new Wid(kind, p);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
                w.config(m);
                if (kind.equals("Toplevel")) {
                    String ttl = m.containsKey("title") ? Py.str(m.get("title")) : "Window";
                    if (overlay && wm != null) {
                        w.win = new OvWin(w, ttl, m.containsKey("name") ? Py.str(m.get("name")) : null);          // dpooa: a new floating window, not a panel in the main one
                    } else {
                        TextView head = new TextView(ctx);
                        head.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                        head.setText(ttl);
                        ((ViewGroup) w.content).addView(head, 0);
                        w.pack(new LinkedHashMap<String, Object>());
                    }
                }
                if (kind.equals("OptionMenu")) w.initOptionMenu(m);
                return w;
            }
        });
    }


    // ------------------------------------------------------------ menus

    static Runnable menuCancel;
    static FrameLayout menuScrim;

    static void closeMenu() {
        if (menuScrim != null) { try { rootFrame.removeView(menuScrim); } catch (Throwable ignored) { /* gone */ } menuScrim = null; }
        if (menuCancel != null) { cancels.remove(menuCancel); menuCancel = null; }
    }

    static void rebuildBar() {
        if (menuBarRow == null) return;
        menuBarRow.removeAllViews();
        if (barMenu == null) { menuBarHost.setVisibility(View.GONE); return; }
        menuBarHost.setVisibility(View.VISIBLE);
        for (final Map<String, Object> it : barMenu.mItems) {
            String type = String.valueOf(it.get("type"));
            if (type.equals("separator")) continue;
            final TextView tv = new TextView(ctx);
            tv.setText(String.valueOf(it.get("label") == null ? "" : it.get("label")));
            boolean off = "disabled".equals(it.get("state"));
            tv.setTextColor(off ? 0xFF78909C : 0xFFFFFFFF);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
            if (!off) tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Object sub = it.get("menu");
                    if (sub instanceof Wid) showMenu((Wid) sub, null, Gravity.TOP | Gravity.START);
                    else barMenu.menuInvoke(it);
                }
            });
            menuBarRow.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** Popup list for a Menu (UI thread). Cascades open in place with a back row. */
    static void showMenu(Wid m, String title, int gravity) {
        closeMenu();
        final FrameLayout scrim = new FrameLayout(ctx);
        scrim.setBackgroundColor(0x55000000);
        scrim.setClickable(true);
        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFFFFFFFF);
        final ScrollView host = new ScrollView(ctx);
        host.addView(card);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        clp.setMargins(dp(8), dp(8), dp(8), dp(8));
        scrim.addView(host, clp);
        scrim.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeMenu(); } });
        menuScrim = scrim;
        menuCancel = new Runnable() { public void run() { closeMenu(); } };
        cancels.add(menuCancel);
        fillMenu(card, m, null);
        rootFrame.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    static void fillMenu(final LinearLayout card, final Wid m, final Wid parentMenu) {
        card.removeAllViews();
        int minW = ctx.getResources().getDisplayMetrics().widthPixels / 2;
        card.setMinimumWidth(minW);
        if (parentMenu != null) {
            TextView back = new TextView(ctx);
            back.setText("‹  Back");
            back.setTextColor(0xFF607D8B);
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            back.setPadding(dp(16), dp(12), dp(16), dp(12));
            back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { fillMenu(card, parentMenu, null); } });
            card.addView(back);
        }
        boolean marks = false;
        for (Map<String, Object> it : m.mItems) {
            String t = String.valueOf(it.get("type"));
            if (t.equals("checkbutton") || t.equals("radiobutton")) marks = true;
        }
        for (final Map<String, Object> it : m.mItems) {
            final String t = String.valueOf(it.get("type"));
            if (t.equals("separator")) {
                View sep = new View(ctx);
                sep.setBackgroundColor(0xFFCFD8DC);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                sl.setMargins(0, dp(4), 0, dp(4));
                card.addView(sep, sl);
                continue;
            }
            boolean off = "disabled".equals(it.get("state"));
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            String mark = "";
            if (marks) mark = m.menuChecked(it) ? (t.equals("radiobutton") ? "●  " : "✓  ") : "    ";
            TextView lab = new TextView(ctx);
            lab.setText(mark + String.valueOf(it.get("label") == null ? "" : it.get("label")));
            lab.setTextColor(off ? 0xFFB0BEC5 : 0xFF000000);
            lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            row.addView(lab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final Object sub = it.get("menu");
            String acc = it.get("accelerator") == null ? (t.equals("cascade") ? "›" : "") : Py.str(it.get("accelerator"));
            if (!acc.isEmpty()) {
                TextView at = new TextView(ctx);
                at.setText("   " + acc);
                at.setTextColor(0xFF78909C);
                at.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                row.addView(at, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (!off) row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (t.equals("cascade") && sub instanceof Wid) { fillMenu(card, (Wid) sub, m); return; }
                    closeMenu();
                    m.menuInvoke(it);
                }
            });
            card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    // ------------------------------------------------------------------ dialogs

    static final class Dlg {
        String title = "", msg = "", initial = "";
        String[] buttons = new String[0];
        String[] items;
        boolean input, password;
        View extra;
    }

    interface Done { void done(int index, String text); }

    /** UI thread only. index = button / item number, -1 when cancelled. */
    static void showCard(final Dlg d, final Done cb) {
        final FrameLayout scrim = new FrameLayout(ctx);
        final FrameLayout host = dialogFrame != null ? dialogFrame : rootFrame;   // a Toplevel window or the main one
        final int minH0 = host.getMinimumHeight();
        if (host != rootFrame) host.setMinimumHeight(Math.max(minH0, dp(240)));   // room for the card
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFFFFFFFF);
        int p = dp(16);
        card.setPadding(p, p, p, p);
        final boolean[] finished = new boolean[1];
        final EditText[] et = new EditText[1];
        final Runnable[] cancel = new Runnable[1];
        final Done wrapped = new Done() {
            public void done(int i, String t) {
                if (finished[0]) return;
                finished[0] = true;
                cancels.remove(cancel[0]);
                host.removeView(scrim);
                if (host instanceof OvFrame) ((OvFrame) host).endTyping();
                if (host != rootFrame) host.setMinimumHeight(minH0);
                cb.done(i, t);
            }
        };
        cancel[0] = new Runnable() { public void run() { wrapped.done(-1, null); } };
        if (!d.title.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(d.title);
            t.setTextColor(0xFF000000);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (!d.msg.isEmpty()) {
            TextView m = new TextView(ctx);
            m.setText(d.msg);
            m.setTextColor(0xFF222222);
            m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            m.setPadding(0, dp(8), 0, dp(8));
            card.addView(m, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (d.input) {
            et[0] = new EditText(ctx);
            et[0].setSingleLine(true);
            et[0].setTextColor(0xFF000000);
            et[0].setText(d.initial);
            if (d.password) et[0].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            card.addView(et[0], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (d.items != null) {
            LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < d.items.length; i++) {
                final int idx = i;
                final String text = d.items[i];
                TextView row = new TextView(ctx);
                row.setText(text);
                row.setTextColor(0xFF000000);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                row.setPadding(dp(8), dp(12), dp(8), dp(12));
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { wrapped.done(idx, text); }
                });
                list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            ScrollView sv = new ScrollView(ctx);
            sv.addView(list);
            int maxH = ctx.getResources().getDisplayMetrics().heightPixels / 2;
            card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, dp(48) * Math.max(1, d.items.length))));
        }
        if (d.extra != null) card.addView(d.extra, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        for (int i = 0; i < d.buttons.length; i++) {
            final int idx = i;
            Button b = new Button(ctx);
            b.setText(d.buttons[i]);
            b.setAllCaps(false);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { wrapped.done(idx, et[0] == null ? null : et[0].getText().toString()); }
            });
            row.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        clp.setMargins(dp(20), dp(20), dp(20), dp(20));
        scrim.addView(card, clp);
        host.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cancels.add(cancel[0]);
        if (et[0] != null) { et[0].requestFocus(); wantKeyboard(et[0]); }
    }

    /** Show a dialog and wait for the answer (py thread). Returns {index, text}. */
    static Object[] ask(final Dlg d) {
        final Object[] out = new Object[]{-1, null};
        if (onMain()) {            // cannot block the UI thread: show it and answer "cancelled"
            showCard(d, new Done() { public void done(int i, String t) { } });
            return out;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(new Runnable() {
            public void run() {
                try {
                    showCard(d, new Done() {
                        public void done(int i, String t) { out[0] = i; out[1] = t; latch.countDown(); }
                    });
                } catch (Throwable e) { Log.e(TAG, "dialog failed", e); latch.countDown(); }
            }
        });
        try { latch.await(); } catch (InterruptedException e) { /* cancelled */ }
        return out;
    }

    static Dlg dlg(Object title, Object msg, String... buttons) {
        Dlg d = new Dlg();
        d.title = title == null ? "" : Py.str(title);
        d.msg = msg == null ? "" : Py.str(msg);
        d.buttons = buttons;
        return d;
    }

    /** Built-in confirmation for `#datasaving: datareset` (skipped by `#datasaving: datareset: noconfirm`). */
    public static boolean confirmReset() {
        return (Integer) ask(dlg("Reset data", "Erase all saved data and start over? This cannot be undone.", "Reset", "Cancel"))[0] == 0;
    }

    /** messagebox.* */
    public static Object msg(String kind, Object title, Object message) {
        switch (kind) {
            case "askyesno": return Boolean.valueOf((Integer) ask(dlg(title, message, "Yes", "No"))[0] == 0);
            case "askokcancel": return Boolean.valueOf((Integer) ask(dlg(title, message, "OK", "Cancel"))[0] == 0);
            case "askretrycancel": return Boolean.valueOf((Integer) ask(dlg(title, message, "Retry", "Cancel"))[0] == 0);
            case "askquestion": return (Integer) ask(dlg(title, message, "Yes", "No"))[0] == 0 ? "yes" : "no";
            case "askyesnocancel": {
                int i = (Integer) ask(dlg(title, message, "Yes", "No", "Cancel"))[0];
                return i == 0 ? Boolean.TRUE : i == 1 ? Boolean.FALSE : null;
            }
            default: ask(dlg(title, message, "OK")); return "ok";
        }
    }

    /** simpledialog.askstring / askinteger / askfloat */
    public static Object simple(String kind, Object title, Object prompt, Object initial, Object minv, Object maxv, Object show) {
        while (true) {
            Dlg d = dlg(title, prompt, "OK", "Cancel");
            d.input = true;
            d.initial = initial == null ? "" : Py.str(initial);
            d.password = show != null && !Py.str(show).isEmpty();
            Object[] r = ask(d);
            if ((Integer) r[0] != 0) return null;
            String s = (String) r[1];
            if (s == null) s = "";
            try {
                if (kind.equals("askstring")) return s;
                Object v = kind.equals("askinteger") ? Py.toInt(s) : Py.toFloat(s);
                if (minv != null && Py.cmp(v, minv) < 0) throw new Py.PyError("ValueError", "Value must be at least " + Py.str(minv));
                if (maxv != null && Py.cmp(v, maxv) > 0) throw new Py.PyError("ValueError", "Value must be at most " + Py.str(maxv));
                return v;
            } catch (Py.PyError e) {
                ask(dlg("Illegal value", e.type.equals("ValueError") && e.getMessage().startsWith("Value must")
                        ? e.getMessage() : "Please enter a valid " + (kind.equals("askinteger") ? "integer." : "number."), "OK"));
            }
        }
    }


    // ------------------------------------------------------------ colorchooser

    static final int[] PALETTE = {0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF3F51B5, 0xFF2196F3, 0xFF00BCD4,
            0xFF4CAF50, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFF9800, 0xFF795548, 0xFF9E9E9E, 0xFF000000, 0xFFFFFFFF};

    /** colorchooser.askcolor(color=None, title=None) -> ((r, g, b), "#rrggbb") or (None, None). */
    public static Object askcolor(Object... a) {
        Object color = null, title = null;
        int pi = 0;
        for (Object x : a) {
            if (x instanceof Py.Kw) {
                Py.Kw k = (Py.Kw) x;
                if (k.k.equals("color") || k.k.equals("initialcolor")) color = k.v;
                else if (k.k.equals("title")) title = k.v;
            } else if (pi++ == 0) color = x;
        }
        final int[] rgb = {255, 255, 255};
        if (color instanceof List && ((List) color).size() >= 3) {
            for (int i = 0; i < 3; i++) rgb[i] = Math.max(0, Math.min(255, (int) Py.toInt(((List) color).get(i))));
        } else if (color != null) {
            try {
                int c = Color.parseColor(Py.str(color).trim());
                rgb[0] = Color.red(c); rgb[1] = Color.green(c); rgb[2] = Color.blue(c);
            } catch (Throwable t) { /* keep white */ }
        }
        final Object[] res = new Object[1];
        final Dlg d = dlg(title == null ? "Choose color" : title, "", "OK", "Cancel");
        onUi(new Task() { Object run() { d.extra = colorPanel(rgb); return null; } });
        Object[] r = ask(d);
        if ((Integer) r[0] != 0) return Py.tupleOf(null, null);
        return Py.tupleOf(Py.tupleOf((long) rgb[0], (long) rgb[1], (long) rgb[2]), hex(rgb));
    }

    static String hex(int[] rgb) { return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]); }

    static View colorPanel(final int[] rgb) {
        final LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        final View swatch = new View(ctx);
        final TextView code = new TextView(ctx);
        code.setTextColor(0xFF000000);
        code.setGravity(Gravity.CENTER);
        final SeekBar[] bars = new SeekBar[3];
        final boolean[] busy = new boolean[1];
        final Runnable refresh = new Runnable() {
            public void run() {
                swatch.setBackgroundColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
                code.setText(hex(rgb));
                busy[0] = true;
                for (int i = 0; i < 3; i++) if (bars[i] != null) bars[i].setProgress(rgb[i]);
                busy[0] = false;
            }
        };
        col.addView(swatch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        col.addView(code, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String[] names = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            final int ch = i;
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            TextView n = new TextView(ctx);
            n.setText(names[i]);
            n.setTextColor(0xFF000000);
            line.addView(n, new LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));
            SeekBar sb = new SeekBar(ctx);
            sb.setMax(255);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (busy[0]) return;
                    rgb[ch] = p;
                    swatch.setBackgroundColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
                    code.setText(hex(rgb));
                }
                public void onStartTrackingTouch(SeekBar s) { }
                public void onStopTrackingTouch(SeekBar s) { }
            });
            bars[i] = sb;
            line.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            col.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout pal = new LinearLayout(ctx);
        pal.setOrientation(LinearLayout.HORIZONTAL);
        for (final int c : PALETTE) {
            View sq = new View(ctx);
            sq.setBackgroundColor(c);
            sq.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    rgb[0] = Color.red(c); rgb[1] = Color.green(c); rgb[2] = Color.blue(c);
                    refresh.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(28), 1f);
            lp.setMargins(dp(1), dp(8), dp(1), 0);
            pal.addView(sq, lp);
        }
        col.addView(pal, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refresh.run();
        return col;
    }

    // ------------------------------------------------------------ native file picker

    static final class PickReq {
        boolean save, multiple;
        String title = "", initialName = "";
        String[] mimes = new String[]{"*/*"};
        CountDownLatch latch = new CountDownLatch(1);
        List<String> result = new ArrayList<String>();
    }

    static PickReq pickReq;
    static final Map<String, Uri> saveTargets = new HashMap<String, Uri>();
    static int pickSeq = 0;

    static void setBusy(boolean on) {
        try {
            File f = new File(ctx.getFilesDir(), ".busy");
            if (on) f.createNewFile(); else f.delete();
        } catch (Throwable ignored) { /* best effort */ }
    }

    static String[] mimesFor(Object filetypes, Object defext) {
        List<String> out = new ArrayList<String>();
        List<Object> pats = new ArrayList<Object>();
        if (filetypes instanceof List) {
            for (Object ft : (List) filetypes) {
                if (ft instanceof List && ((List) ft).size() > 1) {
                    Object p = ((List) ft).get(1);
                    if (p instanceof List) pats.addAll((List) p);
                    else for (String s : Py.str(p).split("[ ;]+")) pats.add(s);
                }
            }
        }
        if (pats.isEmpty() && defext != null && !Py.str(defext).isEmpty()) pats.add("*" + (Py.str(defext).startsWith(".") ? "" : ".") + Py.str(defext));
        for (Object p : pats) {
            String s = Py.str(p).toLowerCase();
            String ext = s.startsWith("*.") ? s.substring(2) : s.startsWith(".") ? s.substring(1) : s;
            String m;
            if (ext.equals("*") || ext.isEmpty()) return new String[]{"*/*"};
            switch (ext) {
                case "txt": case "log": case "md": m = "text/plain"; break;
                case "csv": m = "text/csv"; break;
                case "json": m = "application/json"; break;
                case "xml": m = "text/xml"; break;
                case "html": case "htm": m = "text/html"; break;
                case "py": m = "text/x-python"; break;
                case "pdf": m = "application/pdf"; break;
                case "png": m = "image/png"; break;
                case "jpg": case "jpeg": m = "image/jpeg"; break;
                case "gif": m = "image/gif"; break;
                case "webp": m = "image/webp"; break;
                case "bmp": m = "image/bmp"; break;
                case "mp3": m = "audio/mpeg"; break;
                case "wav": m = "audio/x-wav"; break;
                case "mp4": m = "video/mp4"; break;
                case "zip": m = "application/zip"; break;
                default: return new String[]{"*/*"};
            }
            if (!out.contains(m)) out.add(m);
        }
        return out.isEmpty() ? new String[]{"*/*"} : out.toArray(new String[0]);
    }

    /** Called by PickActivity: the intent that opens the system picker. */
    public static Intent pickIntent() {
        PickReq r = pickReq;
        Intent i = new Intent(r.save ? "android.intent.action.CREATE_DOCUMENT" : "android.intent.action.OPEN_DOCUMENT");
        i.addCategory("android.intent.category.OPENABLE");
        if (r.save) {
            i.setType(r.mimes.length == 1 ? r.mimes[0] : "*/*");
            if (!r.initialName.isEmpty()) i.putExtra("android.intent.extra.TITLE", r.initialName);
        } else {
            i.setType(r.mimes.length == 1 ? r.mimes[0] : "*/*");
            if (r.mimes.length > 1) i.putExtra("android.intent.extra.MIME_TYPES", r.mimes);
            if (r.multiple) i.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        }
        return i;
    }

    static String nameOf(Uri u) {
        String s = u.getLastPathSegment();
        if (s == null) return "picked";
        int k = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        s = k >= 0 ? s.substring(k + 1) : s;
        return s.isEmpty() ? "picked" : s;
    }

    /** Called by PickActivity with the picker result; copies content into the app's cache folder. */
    public static void pickResult(int resultCode, final Intent data) {
        final PickReq r = pickReq;
        if (r == null) return;
        if (resultCode != -1 || data == null) { r.latch.countDown(); return; }
        final List<Uri> uris = new ArrayList<Uri>();
        if (data.getData() != null) uris.add(data.getData());
        else if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        new Thread(new Runnable() {
            public void run() {
                try {
                    for (Uri u : uris) {
                        File dir = new File(ctx.getCacheDir(), (r.save ? "saving/" : "picked/") + (++pickSeq));
                        dir.mkdirs();
                        File f = new File(dir, nameOf(u));
                        if (r.save) {
                            saveTargets.put(f.getPath(), u);
                        } else {
                            InputStream in = ctx.getContentResolver().openInputStream(u);
                            OutputStream out = new FileOutputStream(f);
                            try {
                                byte[] buf = new byte[16384];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            } finally { in.close(); out.close(); }
                        }
                        r.result.add(f.getPath());
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "picker copy failed", t);
                    toast("File picker: " + t);
                    r.result.clear();
                } finally {
                    r.latch.countDown();
                }
            }
        }, "apkapple-pick").start();
    }

    /** A file opened for writing at a picker-provided path was closed: copy it to the chosen document. */
    static void savedFileClosed(String path) {
        Uri u = saveTargets.get(path);
        if (u == null) return;
        try {
            InputStream in = new java.io.FileInputStream(path);
            OutputStream out = ctx.getContentResolver().openOutputStream(u, "wt");
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { in.close(); out.close(); }
        } catch (Throwable t) {
            Log.e(TAG, "saving to the chosen file failed", t);
            toast("Could not save the file: " + t);
        }
    }

    /** Blocking picker (py thread). */
    static List<String> pick(boolean save, boolean multiple, Object titleArg, Object initialfile, Object filetypes, Object defext) {
        PickReq r = new PickReq();
        r.save = save;
        r.multiple = multiple;
        r.title = titleArg == null ? "" : Py.str(titleArg);
        String name = initialfile == null ? "" : Py.str(initialfile);
        if (save && name.isEmpty()) name = "untitled" + (defext == null ? "" : (Py.str(defext).startsWith(".") ? "" : ".") + Py.str(defext));
        r.initialName = name;
        r.mimes = mimesFor(filetypes, defext);
        pickReq = r;
        setBusy(true);
        try {
            final Intent go = new Intent();
            go.setClassName(ctx.getPackageName(), "apple.gen.PickActivity");
            go.addFlags(0x10000000);
            onUi(new Task() { Object run() { ctx.startActivity(go); return null; } });
            r.latch.await();
        } catch (InterruptedException e) {
            /* treated as cancel */
        } catch (RuntimeException e) {
            toast("File picker failed: " + e);
        } finally {
            setBusy(false);
        }
        return r.result;
    }

    /** filedialog.* (also bare names with #use: filepick). Supports plain and callback=/command= styles. */
    public static Object filedialog(final String fn, final Object... a) {
        final Map<String, Object> kw = new LinkedHashMap<String, Object>();
        for (Object x : a) if (x instanceof Py.Kw) kw.put(((Py.Kw) x).k, ((Py.Kw) x).v);
        final Object cb = kw.containsKey("callback") ? kw.get("callback") : kw.get("command");
        if (cb != null) {
            new Thread(new Runnable() {
                public void run() {
                    Object r;
                    try { r = filedialogNow(fn, kw); } catch (Throwable t) { callbackFailed(t); return; }
                    call(cb, r);
                }
            }, "apkapple-pick-cb").start();
            return null;
        }
        return filedialogNow(fn, kw);
    }

    static Object filedialogNow(String fn, Map<String, Object> kw) {
        Object ttl = kw.get("title"), init = kw.get("initialfile"), ft = kw.get("filetypes"), de = kw.get("defaultextension");
        if (fn.equals("askdirectory")) {
            File d = ctx.getExternalFilesDir(null);
            if (d == null) d = ctx.getFilesDir();
            return d.getPath();
        }
        boolean save = fn.startsWith("asksaveas");
        boolean multi = fn.equals("askopenfilenames");
        List<String> r = pick(save, multi, ttl, init, ft, de);
        if (multi) { List<Object> l = new ArrayList<Object>(r); return new Py.Tuple(l); }
        if (fn.equals("askopenfile") || fn.equals("asksaveasfile")) {
            if (r.isEmpty()) return null;
            Object mode = kw.get("mode");
            return Ext.open(new Object[]{r.get(0), mode == null ? (save ? "w" : "r") : mode});
        }
        return r.isEmpty() ? "" : r.get(0);
    }

    // ------------------------------------------------------------------ colors

    static Integer color(Object o) {
        if (o == null) return null;
        String s = Py.str(o).trim().toLowerCase();
        if (s.isEmpty()) return null;
        switch (s) {
            case "orange": return 0xFFFFA500;
            case "pink": return 0xFFFFC0CB;
            case "brown": return 0xFFA52A2A;
            case "gold": return 0xFFFFD700;
            case "violet": return 0xFFEE82EE;
            case "purple": return 0xFF800080;
            case "navy": return 0xFF000080;
            case "teal": return 0xFF008080;
            case "lightblue": return 0xFFADD8E6;
            case "lightgreen": return 0xFF90EE90;
            case "lightyellow": return 0xFFFFFFE0;
            case "darkgreen": return 0xFF006400;
            case "darkblue": return 0xFF00008B;
            case "darkred": return 0xFF8B0000;
            case "gray": case "grey": return 0xFF808080;
            case "lightgray": case "lightgrey": return 0xFFD3D3D3;
            case "darkgray": case "darkgrey": return 0xFFA9A9A9;
            case "systembuttonface": case "systemwindow": case "white smoke": return 0xFFF0F0F0;
        }
        try {
            if (s.startsWith("#") && s.length() == 4) {
                s = "#" + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2) + s.charAt(3) + s.charAt(3);
            }
            return Color.parseColor(s);
        } catch (Throwable t) {
            warn("unknown color " + o);
            return null;
        }
    }

    static boolean flag(Object o) {
        if (o instanceof String) {
            String s = ((String) o).toLowerCase();
            return s.equals("yes") || s.equals("true") || s.equals("1") || s.equals("on");
        }
        return Py.truth(o);
    }

    static String optStr(Map<String, Object> m, String k, String d) {
        Object v = m.get(k);
        return v == null ? d : Py.str(v).toLowerCase();
    }

    static int[] pad(Object o) {
        if (o == null) return new int[]{0, 0};
        if (o instanceof List) {
            List l = (List) o;
            if (l.size() >= 2) return new int[]{dp((float) Py.dbl(l.get(0))), dp((float) Py.dbl(l.get(1)))};
            if (l.size() == 1) o = l.get(0);
        }
        if (Py.isNum(o)) { int v = dp((float) Py.dbl(o)); return new int[]{v, v}; }
        return new int[]{0, 0};
    }

    // ------------------------------------------------------------------ canvas


    // ----------------------------------------------------------------- images (PhotoImage)

    /** tkinter's PhotoImage: a bitmap in "tk pixels" (1 pixel = 1 dp). Widgets and canvases that show it are redrawn when it changes. */
    public static final class Img implements Py.Obj {
        Bitmap bm;
        final List<Object> users = new ArrayList<Object>();
        Img(Bitmap b) { bm = b; }

        static Bitmap decode(Map<String, Object> m) {
            Object data = m.get("data"), file = m.get("file");
            Bitmap b = null;
            if (data != null) {
                byte[] raw;
                if (data instanceof byte[]) raw = (byte[]) data;
                else {
                    try { raw = android.util.Base64.decode(Py.str(data).trim(), android.util.Base64.DEFAULT); }
                    catch (Throwable t) { raw = null; }
                }
                if (raw != null) b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (b == null) throw new Py.PyError("TclError", "image data is not valid or the format is not supported");
            } else if (file != null) {
                String f = Py.str(file);
                String asset = m.get("asset") == null ? null : Py.str(m.get("asset"));
                if (asset != null) {
                    try { InputStream in = ctx.getAssets().open(asset); b = BitmapFactory.decodeStream(in); in.close(); } catch (Throwable t) { b = null; }
                }
                if (b == null) {
                    File fl = new File(f);
                    if (!fl.isAbsolute()) fl = new File(ctx.getFilesDir(), f);
                    if (!fl.isFile()) fl = new File(f);
                    if (fl.isFile()) b = BitmapFactory.decodeFile(fl.getPath());
                }
                if (b == null) throw new Py.PyError("TclError", "couldn't open \"" + f + "\": no such file or directory");
            }
            int w = m.get("width") == null ? 0 : (int) Py.dbl(m.get("width")), h = m.get("height") == null ? 0 : (int) Py.dbl(m.get("height"));
            if (b == null) b = Bitmap.createBitmap(Math.max(w, 1), Math.max(h, 1), Bitmap.Config.ARGB_8888);
            else if (w > 0 || h > 0) b = Bitmap.createScaledBitmap(b, w > 0 ? w : b.getWidth(), h > 0 ? h : b.getHeight(), false);
            return b;
        }

        Bitmap mutable() { return bm.copy(Bitmap.Config.ARGB_8888, true); }
        int wd() { return bm.getWidth(); }
        int ht() { return bm.getHeight(); }
        void changed() {
            for (Object u : new ArrayList<Object>(users)) {
                if (u instanceof Wid) ((Wid) u).applyImage();
                else if (u instanceof CanvasView) ((CanvasView) u).invalidate();
            }
        }
        Drawable drawable() {
            BitmapDrawable d = new BitmapDrawable(ctx.getResources(), bm);
            d.setBounds(0, 0, dp(wd()), dp(ht()));
            return d;
        }

        static int arg(Object[] a, int i, int dflt) { return a.length > i && a[i] != null ? (int) Py.dbl(a[i]) : dflt; }

        public Object callm(String name, Object[] a) {
            switch (name) {
                case "width": return (long) wd();
                case "height": return (long) ht();
                case "copy": return new Img(mutable());
                case "zoom": { int x = arg(a, 0, 1), y = arg(a, 1, x); return new Img(Bitmap.createScaledBitmap(bm, Math.max(1, wd() * x), Math.max(1, ht() * y), false)); }
                case "subsample": { int x = Math.max(1, arg(a, 0, 1)), y = Math.max(1, arg(a, 1, x)); return new Img(Bitmap.createScaledBitmap(bm, Math.max(1, wd() / x), Math.max(1, ht() / y), false)); }
                case "blank": bm = Bitmap.createBitmap(wd(), ht(), Bitmap.Config.ARGB_8888); changed(); return null;
                case "get": {
                    int x = arg(a, 0, 0), y = arg(a, 1, 0);
                    if (x < 0 || y < 0 || x >= wd() || y >= ht()) throw new Py.PyError("TclError", "coordinates out of range");
                    int c = bm.getPixel(x, y);
                    List<Object> r = new ArrayList<Object>();
                    r.add((long) ((c >> 16) & 255)); r.add((long) ((c >> 8) & 255)); r.add((long) (c & 255));
                    return new Py.Tuple(r);
                }
                case "put": return put(a);
                case "configure": case "config": {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    for (Object x : a) if (x instanceof Py.Kw) m.put(((Py.Kw) x).k, ((Py.Kw) x).v);
                    if (m.containsKey("file") || m.containsKey("data") || m.containsKey("width") || m.containsKey("height")) { bm = decode(m); changed(); }
                    return null;
                }
                case "cget": { String k = Py.str(a[0]); return k.equals("width") ? (Object) (long) wd() : k.equals("height") ? (Object) (long) ht() : ""; }
                case "type": return "photo";
                case "name": return "pyimage" + System.identityHashCode(this);
                default: throw new Py.PyError("AttributeError", "'PhotoImage' object has no attribute '" + name + "'");
            }
        }

        Object put(Object[] a) {
            Object data = a.length > 0 ? a[0] : null;
            int x1 = 0, y1 = 0, x2 = -1, y2 = -1;
            Object to = null;
            for (int i = 1; i < a.length; i++) {
                if (a[i] instanceof Py.Kw) { if (((Py.Kw) a[i]).k.equals("to")) to = ((Py.Kw) a[i]).v; }
                else if (to == null && i == 1) to = a[i];
            }
            if (to instanceof List) {
                List<?> t = (List<?>) to;
                if (t.size() >= 2) { x1 = (int) Py.dbl(t.get(0)); y1 = (int) Py.dbl(t.get(1)); }
                if (t.size() >= 4) { x2 = (int) Py.dbl(t.get(2)); y2 = (int) Py.dbl(t.get(3)); }
            }
            List<List<Integer>> rows = new ArrayList<List<Integer>>();
            for (String row : splitBraces(data instanceof List ? joinList((List<?>) data) : Py.str(data))) {
                List<Integer> r = new ArrayList<Integer>();
                for (String c : splitBraces(row)) { Integer v = color(c); r.add(v == null ? 0 : v); }
                rows.add(r);
            }
            if (rows.isEmpty()) return null;
            if (bm.getWidth() < 1) return null;
            Bitmap m = mutable();
            int needW = x1 + rows.get(0).size(), needH = y1 + rows.size();
            if (x2 > x1 || y2 > y1) { needW = Math.max(needW, x2); needH = Math.max(needH, y2); }
            if (needW > m.getWidth() || needH > m.getHeight()) {
                Bitmap g = Bitmap.createBitmap(Math.max(needW, m.getWidth()), Math.max(needH, m.getHeight()), Bitmap.Config.ARGB_8888);
                for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++) g.setPixel(x, y, m.getPixel(x, y));
                m = g;
            }
            int endX = x2 > x1 ? x2 : x1 + rows.get(0).size(), endY = y2 > y1 ? y2 : y1 + rows.size();
            for (int y = y1; y < endY && y < m.getHeight(); y++) {
                List<Integer> r = rows.get((y - y1) % rows.size());
                for (int x = x1; x < endX && x < m.getWidth(); x++) m.setPixel(x, y, r.get((x - x1) % r.size()));
            }
            bm = m;
            changed();
            return null;
        }

        static String joinList(List<?> l) {
            StringBuilder b = new StringBuilder();
            for (Object o : l) { b.append(o instanceof List ? "{" + joinList((List<?>) o) + "}" : Py.str(o)).append(' '); }
            return b.toString().trim();
        }

        /** "{a b} {c d}" or "a b c" -> its top-level pieces. */
        static List<String> splitBraces(String s) {
            List<String> r = new ArrayList<String>();
            int i = 0, n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == '{') {
                    int d = 1, j = i + 1;
                    while (j < n && d > 0) { char x = s.charAt(j); if (x == '{') d++; else if (x == '}') d--; j++; }
                    r.add(s.substring(i + 1, Math.max(i + 1, j - 1)));
                    i = j;
                } else {
                    int j = i;
                    while (j < n && !Character.isWhitespace(s.charAt(j))) j++;
                    r.add(s.substring(i, j));
                    i = j;
                }
            }
            return r;
        }
    }

    /** PhotoImage(file=..., data=..., width=..., height=...): kv is name, value pairs. */
    public static Object photo(final Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return new Img(Img.decode(m));
    }

    static Img imgOf(Object v) {
        Object o = Oop.unwrap(v);
        return o instanceof Img ? (Img) o : null;
    }

    static final class CItem {
        Img img;
        String anchor = "center";
        double start = 0, extent = 90;          // arc
        String style = "pieslice";              // arc
        String arrow = "none";                  // line
        float[] dash;
        boolean smooth, hidden, bold, italic, noFill;
        String justify = "left";
        float wrap;                             // text width= (wrap width)
        final List<Object[]> binds = new ArrayList<Object[]>();   // {sequence, fn} from tag_bind
        int id;
        String type;
        double[] c;
        Integer fill, outline;
        float width = 1f;
        String text = "";
        float size = 12f;
        final List<String> tags = new ArrayList<String>();
    }

    static final class CanvasView extends View {
        final List<CItem> items = new ArrayList<CItem>();
        int currentId = -1;
        int cw = 300, ch = 200;
        final Paint paint = new Paint();
        CanvasView(Context c) { super(c); }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(resolveSize(dp(cw), wSpec), resolveSize(dp(ch), hSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            float d = density();
            canvas.save();
            canvas.scale(d, d);
            paint.setAntiAlias(true);
            for (CItem it : new ArrayList<CItem>(items)) draw(canvas, it);
            canvas.restore();
        }

        void draw(Canvas cv, CItem it) {
            double[] c = it.c;
            if (it.hidden || c == null) return;
            switch (it.type) {
                case "line": {
                    if (c.length < 4) break;
                    int col = it.fill != null ? it.fill : 0xFF000000;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(it.width);
                    paint.setColor(col);
                    if (it.smooth && c.length >= 6) cv.drawPath(CanvasKit.smoothPath(c, false), paint);
                    else for (int i = 0; i + 3 < c.length; i += 2) CanvasKit.dashed(cv, (float) c[i], (float) c[i + 1], (float) c[i + 2], (float) c[i + 3], it.dash, paint);
                    int n = c.length;
                    if (it.arrow.equals("first") || it.arrow.equals("both")) CanvasKit.arrowHead(cv, (float) c[0], (float) c[1], (float) c[2], (float) c[3], it.width, paint);
                    if (it.arrow.equals("last") || it.arrow.equals("both")) CanvasKit.arrowHead(cv, (float) c[n - 2], (float) c[n - 1], (float) c[n - 4], (float) c[n - 3], it.width, paint);
                    break;
                }
                case "polygon": {
                    if (c.length < 6) break;
                    Path path = it.smooth ? CanvasKit.smoothPath(c, true) : new Path();
                    if (!it.smooth) {
                        path.moveTo((float) c[0], (float) c[1]);
                        for (int i = 2; i + 1 < c.length; i += 2) path.lineTo((float) c[i], (float) c[i + 1]);
                        path.close();
                    }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(it.fill != null ? it.fill : 0xFF000000);
                    if (!it.noFill) cv.drawPath(path, paint);
                    if (it.outline != null) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(it.width);
                        paint.setColor(it.outline);
                        cv.drawPath(path, paint);
                    }
                    break;
                }
                case "arc": {
                    if (c.length < 4) break;
                    RectF r = CanvasKit.rect(c);
                    float st = (float) -it.start, sw = (float) -it.extent;
                    if (!it.style.equals("arc") && it.fill != null) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(it.fill);
                        cv.drawArc(r, st, sw, it.style.equals("pieslice"), paint);
                    }
                    if (it.outline != null || it.fill == null || it.style.equals("arc")) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(it.width);
                        paint.setColor(it.style.equals("arc") && it.fill != null && it.outline == null ? it.fill : it.outline != null ? it.outline : 0xFF000000);
                        cv.drawArc(r, st, sw, it.style.equals("pieslice"), paint);
                    }
                    break;
                }
                case "rectangle": case "oval": {
                    float x1 = (float) Math.min(c[0], c[2]), y1 = (float) Math.min(c[1], c[3]);
                    float x2 = (float) Math.max(c[0], c[2]), y2 = (float) Math.max(c[1], c[3]);
                    if (it.fill != null) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(it.fill);
                        if (it.type.equals("rectangle")) cv.drawRect(x1, y1, x2, y2, paint); else cv.drawOval(new RectF(x1, y1, x2, y2), paint);
                    }
                    if (it.outline != null || it.fill == null) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(it.width);
                        paint.setColor(it.outline != null ? it.outline : 0xFF000000);
                        if (it.type.equals("rectangle")) cv.drawRect(x1, y1, x2, y2, paint); else cv.drawOval(new RectF(x1, y1, x2, y2), paint);
                    }
                    break;
                }
                case "text": {
                    if (c.length < 2) break;
                    CanvasKit.drawText(cv, it, paint, it.fill != null ? it.fill : 0xFF000000);
                    break;
                }
                case "image": {
                    if (it.img == null || c.length < 2) break;
                    float w = it.img.wd(), h = it.img.ht(), x = (float) c[0], y = (float) c[1];
                    String an = it.anchor.equals("center") ? "" : it.anchor;     // "center" itself contains n and e
                    if (an.contains("w")) { /* left edge at x */ } else if (an.contains("e")) x -= w; else x -= w / 2;
                    if (an.contains("n")) { /* top edge at y */ } else if (an.contains("s")) y -= h; else y -= h / 2;
                    cv.drawBitmap(it.img.bm, null, new RectF(x, y, x + w, y + h), paint);
                    break;
                }
                default: break;
            }
        }
    }

    // ----------------------------------------------------------------- widget

    public static final class Wid implements Py.Obj {
        static final int STEPS = 1000;

        final String kind;
        final Wid parent;
        View view;
        ViewGroup content;
        GridLayout grid;
        boolean horiz;
        boolean updating;
        int kids;
        int nextRow;
        Py.Var textvar, var;
        Object value;
        Object command;
        Object selFn;
        Object lo = 0L, hi = 100L;
        Object resolution;
        int steps = STEPS;
        final Map<String, Object> opts = new LinkedHashMap<String, Object>();
        // composite widgets
        TextView inner;                 // Spinbox text field
        LinearLayout lbBox;             // Listbox rows
        final List<String> lbItems = new ArrayList<String>();
        final List<Boolean> lbSel = new ArrayList<Boolean>();
        View fillView, restView;        // Progressbar
        double pbMax = 100, pbVal = 0;
        List<Object> choices = new ArrayList<Object>();
        CanvasView canvas;
        final List<Object[]> bindTags = new ArrayList<Object[]>();      // {tag, sequence, fn} from Canvas.tag_bind
        Tree tree;
        int nextItem = 1;
        // Notebook (tabs)
        LinearLayout tabBar;
        HorizontalScrollView nbStrip;      // the scrolling holder of the tab strip (at the bottom of the notebook)
        LinearLayout nbPagesBox;
        final List<Wid> nbPages = new ArrayList<Wid>();
        final List<String> nbTitles = new ArrayList<String>();
        int nbSel = -1;
        Object nbFn;
        int nbAccent = 0xFFD81B60, nbSelColor = 0xFFFFFFFF, nbIdle = 0xFF9E9E9E, nbBg = 0xFF000000;
        final Map<Long, Runnable> timers = new HashMap<Long, Runnable>();
        long nextTimer = 1;
        OvWin win;      // dpooa: this Toplevel is a floating window of its own

        Wid(String kind, Wid parent) {
            this.kind = kind;
            this.parent = parent;
            Context a = ctx;
            switch (kind) {
                case "Tk":
                    break;
                case "Notebook": {
                    LinearLayout outer = new LinearLayout(a);
                    outer.setOrientation(LinearLayout.VERTICAL);
                    HorizontalScrollView hs = new HorizontalScrollView(a);
                    hs.setFillViewport(true);
                    hs.setHorizontalScrollBarEnabled(false);
                    tabBar = new LinearLayout(a);
                    tabBar.setOrientation(LinearLayout.HORIZONTAL);
                    tabBar.setBackgroundColor(nbBg);
                    hs.addView(tabBar, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    // the pages take all the room above; the tab strip sits at the bottom of the notebook, full width
                    LinearLayout pages = new LinearLayout(a);
                    pages.setOrientation(LinearLayout.VERTICAL);
                    nbStrip = hs;
                    nbPagesBox = pages;
                    hs.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { nbEdgeToEdge(); }
                    });
                    outer.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                    outer.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    view = outer;
                    content = pages;
                    break;
                }
                case "Frame": case "LabelFrame": case "Toplevel": case "PanedWindow": case "Labelframe": {
                    LinearLayout l = new LinearLayout(a);
                    l.setOrientation(LinearLayout.VERTICAL);
                    view = l;
                    content = l;
                    break;
                }
                case "Label": case "Message":
                    view = new TextView(a);
                    break;
                case "Button": {
                    Button b = new Button(a);
                    b.setAllCaps(false);
                    b.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { fire(); }
                    });
                    view = b;
                    break;
                }
                case "Entry": {
                    EditText e = new EditText(a);
                    e.setSingleLine(true);
                    view = e;
                    break;
                }
                case "Text": {
                    EditText t = new EditText(a);
                    t.setMinLines(4);
                    t.setGravity(Gravity.TOP | Gravity.START);
                    view = t;
                    break;
                }
                case "Checkbutton": {
                    CheckBox c = new CheckBox(a);
                    c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        public void onCheckedChanged(CompoundButton b, boolean checked) {
                            if (updating) return;
                            if (var != null) widgetSet(checked ? onValue() : offValue());
                            fire();
                        }
                    });
                    view = c;
                    setVar(new Py.Var("BooleanVar", null));
                    break;
                }
                case "Radiobutton": {
                    RadioButton r = new RadioButton(a);
                    r.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            if (var != null) widgetSet(value);
                            fire();
                        }
                    });
                    view = r;
                    setVar(new Py.Var("StringVar", null));
                    break;
                }
                case "Scale": {
                    SeekBar s = new SeekBar(a);
                    s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                            if (updating || !fromUser) return;
                            Object v = scaleValue();
                            if (var != null) widgetSet(v);
                            if (command != null) Ui.call(command, v);
                        }
                        public void onStartTrackingTouch(SeekBar b) {}
                        public void onStopTrackingTouch(SeekBar b) {}
                    });
                    view = s;
                    applyRange();
                    break;
                }
                case "Treeview": {
                    tree = new Tree(this, a);
                    view = tree.view;
                    break;
                }
                case "Listbox": {
                    ScrollView sv = new ScrollView(a);
                    lbBox = new LinearLayout(a);
                    lbBox.setOrientation(LinearLayout.VERTICAL);
                    sv.addView(lbBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    sv.setBackgroundColor(0xFFF5F5F5);
                    view = sv;
                    break;
                }
                case "Combobox": case "OptionMenu": case "Menubutton": {
                    Button b = new Button(a);
                    b.setAllCaps(false);
                    b.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { openChoices(); }
                    });
                    view = b;
                    break;
                }
                case "Spinbox": {
                    LinearLayout row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    Button minus = new Button(a);
                    minus.setText("−");
                    minus.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { spin(-1); } });
                    EditText e = new EditText(a);
                    e.setSingleLine(true);
                    inner = e;
                    Button plus = new Button(a);
                    plus.setText("+");
                    plus.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { spin(1); } });
                    row.addView(minus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    row.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(plus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    view = row;
                    break;
                }
                case "Progressbar": {
                    LinearLayout row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    fillView = new View(a);
                    fillView.setBackgroundColor(0xFF2196F3);
                    restView = new View(a);
                    restView.setBackgroundColor(0xFFDDDDDD);
                    row.addView(fillView, new LinearLayout.LayoutParams(0, dp(10), 0f));
                    row.addView(restView, new LinearLayout.LayoutParams(0, dp(10), 1f));
                    view = row;
                    break;
                }
                case "Separator": {
                    TextView sep = new TextView(a);
                    sep.setBackgroundColor(0xFFBBBBBB);
                    sep.setMinHeight(dp(2));
                    sep.setMinWidth(dp(60));
                    view = sep;
                    break;
                }
                case "Canvas": {
                    canvas = new CanvasView(a);
                    canvas.setBackgroundColor(0xFFFFFFFF);
                    view = canvas;
                    break;
                }
                case "Scrollbar": case "Menu": case "Sizegrip": {
                    View hidden = new View(a);
                    hidden.setVisibility(8);
                    view = hidden;
                    break;
                }
                default: {
                    TextView u = new TextView(a);
                    u.setText("[" + kind + " is not supported by ApkApple]");
                    view = u;
                    warn("unsupported widget: " + kind);
                }
            }
        }

        // ---------------------------------------------------------- behaviour

        TextView tview() { return view instanceof TextView ? (TextView) view : inner; }

        void fire() {
            if (command != null) Ui.call(command);
        }

        Object onValue() { return opts.containsKey("onvalue") ? opts.get("onvalue") : (Object) 1L; }
        Object offValue() { return opts.containsKey("offvalue") ? opts.get("offvalue") : (Object) 0L; }

        /** A widget changed its variable: update it on the py thread without echoing back to this widget. */
        void widgetSet(final Object v) {
            final Py.Var target = var;
            if (target == null) return;
            final Wid self = this;
            toPy(new Runnable() { public void run() { target.setFromText(v, self); } });
        }

        void setVar(final Py.Var v) {
            var = v;
            v.listeners.add(new Py.WL(this) {
                public Object call(Object... x) {
                    if (var != v) return null;
                    postUi(new Runnable() { public void run() { syncFromVar(); } });
                    return null;
                }
            });
            postUi(new Runnable() { public void run() { syncFromVar(); } });
        }

        void syncFromVar() {
            if (var == null) return;
            updating = true;
            try {
                Object val = var.get();
                if (view instanceof CompoundButton) {
                    boolean checked;
                    if (kind.equals("Radiobutton")) checked = Py.eq(val, value);
                    else checked = opts.containsKey("onvalue") ? Py.eq(val, opts.get("onvalue")) : Py.truth(val);
                    ((CompoundButton) view).setChecked(checked);
                } else if (view instanceof SeekBar) {
                    ((SeekBar) view).setProgress(progressFor(val));
                } else if (kind.equals("Progressbar")) {
                    pbVal = Py.dbl(val);
                    layoutBar();
                }
            } finally { updating = false; }
        }

        void bindText(final Py.Var v) {
            textvar = v;
            final TextView tv = tview();
            tv.setText(Py.str(v.get()));
            final Wid self = this;
            v.listeners.add(new Py.WL(this) {
                public Object call(Object... x) {
                    if (textvar != v) return null;
                    final String s = Py.str(v.get());
                    postUi(new Runnable() {
                        public void run() {
                            if (!tv.getText().toString().equals(s)) {
                                updating = true;
                                try { tv.setText(s); } finally { updating = false; }
                            }
                        }
                    });
                    return null;
                }
            });
            if (tv instanceof EditText) {
                tv.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}
                    public void afterTextChanged(Editable e) {
                        if (updating || textvar != v) return;
                        final String text = e.toString();
                        toPy(new Runnable() { public void run() { v.setFromText(text, self); } });
                    }
                });
            }
        }

        // scale helpers
        void applyRange() {
            if (!(view instanceof SeekBar)) return;
            double l = Py.dbl(lo), h = Py.dbl(hi);
            boolean ints = Py.norm(lo) instanceof Long && Py.norm(hi) instanceof Long && resolution == null;
            steps = (ints && h - l >= 1 && h - l <= 10000) ? (int) (h - l) : STEPS;
            ((SeekBar) view).setMax(steps);
        }

        int progressFor(Object val) {
            double l = Py.dbl(lo), h = Py.dbl(hi);
            if (h == l) return 0;
            double v;
            try { v = Py.dbl(val instanceof String ? Py.toFloat(val) : val); } catch (RuntimeException e) { return 0; }
            return (int) Math.round((v - l) / (h - l) * steps);
        }

        Object scaleValue() {
            double l = Py.dbl(lo), h = Py.dbl(hi);
            double v = l + (h - l) * ((SeekBar) view).getProgress() / steps;
            if (resolution != null) {
                double r = Py.dbl(resolution);
                if (r > 0) v = Math.round(v / r) * r;
            }
            boolean ints = Py.norm(lo) instanceof Long && Py.norm(hi) instanceof Long && (resolution == null || Py.norm(resolution) instanceof Long);
            return ints ? (Object) Math.round(v) : (Object) v;
        }

        // ---- progressbar
        void layoutBar() {
            if (fillView == null) return;
            double frac = pbMax <= 0 ? 0 : Math.max(0, Math.min(1, pbVal / pbMax));
            ((LinearLayout.LayoutParams) fillView.getLayoutParams()).weight = (float) frac;
            ((LinearLayout.LayoutParams) restView.getLayoutParams()).weight = (float) (1 - frac);
            view.requestLayout();
        }

        // ---- listbox
        void lbRebuild() {
            lbBox.removeAllViews();
            for (int i = 0; i < lbItems.size(); i++) {
                final int idx = i;
                TextView t = new TextView(ctx);
                t.setText(lbItems.get(i));
                t.setTextColor(0xFF000000);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                t.setPadding(dp(8), dp(8), dp(8), dp(8));
                t.setBackgroundColor(lbSel.get(i) ? 0xFFB3D9FF : 0x00000000);
                t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { lbClick(idx); } });
                lbBox.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        void lbClick(int idx) {
            String mode = optStr(opts, "selectmode", "browse");
            boolean multi = mode.equals("multiple") || mode.equals("extended");
            if (!multi) for (int i = 0; i < lbSel.size(); i++) lbSel.set(i, false);
            lbSel.set(idx, multi ? !lbSel.get(idx) : true);
            lbRebuild();
            if (selFn != null) Ui.call(selFn, new Ext.Event(this));
        }

        int lbIdx(Object o, boolean forInsert) {
            if (o instanceof Number) return (int) ((Number) o).longValue();
            String s = Py.str(o).trim().toLowerCase();
            if (s.equals("end")) return forInsert ? lbItems.size() : lbItems.size() - 1;
            if (s.equals("active") || s.equals("anchor")) { for (int i = 0; i < lbSel.size(); i++) if (lbSel.get(i)) return i; return 0; }
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }

        // ---- combobox / optionmenu / spinbox
        void initOptionMenu(Map<String, Object> m) {
            Object v = m.get("variable");
            if (v instanceof Py.Var) { opts.put("textvariable", v); bindText((Py.Var) v); }
            Object vals = m.get("values");
            if (vals instanceof List) {
                choices = new ArrayList<Object>((List) vals);
                if (textvar != null && Py.str(textvar.get()).isEmpty() && !choices.isEmpty()) textvar.setFromText(choices.get(0), null);
                if (!choices.isEmpty()) setText(Py.str(textvar != null ? textvar.get() : choices.get(0)));
            }
        }

        void openChoices() {
            if (!view.isEnabled()) return;
            Dlg d = dlg(opts.get("label") == null ? "" : opts.get("label"), "", "Cancel");
            d.items = new String[choices.size()];
            for (int i = 0; i < d.items.length; i++) d.items[i] = Py.str(choices.get(i));
            showCard(d, new Done() {
                public void done(int i, String t) {
                    if (i < 0 || i >= choices.size()) return;
                    final Object chosen = choices.get(i);
                    setText(Py.str(chosen));
                    final Wid self = Wid.this;
                    if (textvar != null) { final Py.Var tv = textvar; toPy(new Runnable() { public void run() { tv.setFromText(chosen, self); } }); }
                    if (selFn != null) Ui.call(selFn, new Ext.Event(self));
                    if (command != null) Ui.call(command, chosen);
                }
            });
        }

        void spin(int dir) {
            TextView e = inner;
            String cur = e.getText().toString();
            if (!choices.isEmpty()) {
                int i = -1;
                for (int k = 0; k < choices.size(); k++) if (Py.str(choices.get(k)).equals(cur)) i = k;
                i = Math.max(0, Math.min(choices.size() - 1, i + dir));
                e.setText(Py.str(choices.get(i)));
            } else {
                double inc = opts.containsKey("increment") ? Py.dbl(opts.get("increment")) : 1.0;
                double v;
                try { v = Double.parseDouble(cur); } catch (NumberFormatException ex) { v = Py.dbl(lo); }
                v += dir * inc;
                double l = Py.dbl(lo), h = Py.dbl(hi);
                boolean bounded = opts.containsKey("from_") && opts.containsKey("to");
                if (bounded) {
                    if (v > h) v = flag(opts.get("wrap")) ? l : h;
                    if (v < l) v = flag(opts.get("wrap")) ? h : l;
                }
                boolean whole = v == Math.rint(v) && inc == Math.rint(inc);
                e.setText(whole ? String.valueOf((long) v) : Py.fmtDouble(v));
            }
            fire();
        }

        // ------------------------------------------------------------ options

        void setText(String s) {
            TextView tv = tview();
            shownText = s;
            if (imageOpt != null && (compoundOpt.equals("none") || compoundOpt.isEmpty()) && !(view instanceof EditText)) s = "";
            if (tv != null) {
                updating = true;
                try { tv.setText(s); } finally { updating = false; }
            }
        }

        void config(Map<String, Object> m) {
            for (Map.Entry<String, Object> e : m.entrySet()) option(e.getKey(), e.getValue());
            if (kind.equals("LabelFrame") || kind.equals("Labelframe")) labelFrameTitle();
        }


        // ------------------------------------------------------------- Menu

        final List<Map<String, Object>> mItems = new ArrayList<Map<String, Object>>();

        int menuIndex(Object x) {
            if (x == null) return -1;
            if (x instanceof Number) return ((Number) x).intValue();
            String t = Py.str(x);
            if (t.equals("end") || t.equals("last")) return mItems.size() - 1;
            if (t.equals("none")) return -1;
            try { return Integer.parseInt(t); } catch (NumberFormatException e) { /* label */ }
            for (int i = 0; i < mItems.size(); i++) if (t.equals(String.valueOf(mItems.get(i).get("label")))) return i;
            return -1;
        }

        Map<String, Object> menuItem(Object x) {
            int i = menuIndex(x);
            if (i < 0 || i >= mItems.size()) throw new Py.PyError("TclError", "bad menu entry index \"" + Py.str(x) + "\"");
            return mItems.get(i);
        }

        void menuChanged() { if (this == barMenu) rebuildBar(); }

        void menuAdd(int at, String type, Map<String, Object> kw) {
            Map<String, Object> it = new LinkedHashMap<String, Object>(kw);
            it.put("type", type);
            Object sub = it.get("menu");
            if (sub != null) it.put("menu", Oop.unwrap(sub));
            if (type.equals("checkbutton") && !(it.get("variable") instanceof Py.Var)) it.put("variable", new Py.Var("BooleanVar", null));
            if (type.equals("radiobutton") && !(it.get("variable") instanceof Py.Var)) it.put("variable", new Py.Var("StringVar", null));
            if (at < 0 || at > mItems.size()) mItems.add(it); else mItems.add(at, it);
            menuChanged();
        }

        Object menuCall(String name, List<Object> pos, Map<String, Object> kw) {
            Object a0 = pos.isEmpty() ? null : pos.get(0);
            if (name.startsWith("add_")) { menuAdd(-1, name.substring(4), kw); return null; }
            if (name.startsWith("insert_")) { menuAdd(menuIndex(a0), name.substring(7), kw); return null; }
            switch (name) {
                case "add": menuAdd(-1, Py.str(a0), kw); return null;
                case "insert": menuAdd(menuIndex(a0), Py.str(pos.get(1)), kw); return null;
                case "delete": {
                    if (a0 == null) return null;
                    int i = menuIndex(a0), j = pos.size() > 1 ? menuIndex(pos.get(1)) : i;
                    if (i < 0) return null;
                    for (int k = Math.min(j, mItems.size() - 1); k >= i; k--) mItems.remove(k);
                    menuChanged();
                    return null;
                }
                case "entryconfig": case "entryconfigure": {
                    Map<String, Object> it = menuItem(a0);
                    if (kw.isEmpty()) return null;
                    for (Map.Entry<String, Object> e : kw.entrySet())
                        it.put(e.getKey(), e.getKey().equals("menu") ? Oop.unwrap(e.getValue()) : e.getValue());
                    menuChanged();
                    return null;
                }
                case "entrycget": return menuItem(a0).get(Py.str(pos.get(1)));
                case "index": { int i = menuIndex(a0); return i < 0 ? null : (Object) (long) i; }
                case "type": return String.valueOf(menuItem(a0).get("type"));
                case "invoke": menuInvoke(menuItem(a0)); return null;
                case "tk_popup": case "post": case "tk_popup_": {
                    final Wid self = this;
                    postUi(new Runnable() { public void run() { showMenu(self, null, Gravity.CENTER); } });
                    return null;
                }
                case "unpost": postUi(new Runnable() { public void run() { closeMenu(); } }); return null;
                case "grab_release": case "tk_bindForTraversal": return null;
                case "size": return (long) mItems.size();
            }
            return NOPE;
        }

        boolean menuChecked(Map<String, Object> it) {
            Object v = it.get("variable");
            if (!(v instanceof Py.Var)) return false;
            Object val = ((Py.Var) v).get();
            String t = String.valueOf(it.get("type"));
            if (t.equals("radiobutton")) return Py.eq(val, it.get("value"));
            if (it.containsKey("onvalue")) return Py.eq(val, it.get("onvalue"));
            return Py.truth(val);
        }

        void menuInvoke(final Map<String, Object> it) {
            final String t = String.valueOf(it.get("type"));
            toPy(new Runnable() {
                public void run() {
                    try {
                        Object v = it.get("variable");
                        if (t.equals("checkbutton") && v instanceof Py.Var) {
                            Object on = it.containsKey("onvalue") ? it.get("onvalue") : (Object) 1L;
                            Object off = it.containsKey("offvalue") ? it.get("offvalue") : (Object) 0L;
                            ((Py.Var) v).set(menuChecked(it) ? off : on);
                        } else if (t.equals("radiobutton") && v instanceof Py.Var) {
                            ((Py.Var) v).set(it.get("value"));
                        }
                        Object cmd = it.get("command");
                        if (cmd != null) Py.call(cmd);
                    } catch (Throwable e) { callbackFailed(e); }
                    Ext.saveState();
                }
            });
        }

        // ------------------------------------------------------ Notebook tabs

        void nbRebuild() {
            tabBar.removeAllViews();
            for (int i = 0; i < nbPages.size(); i++) {
                final int idx = i;
                LinearLayout cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setMinimumWidth(dp(88));
                TextView tv = new TextView(ctx);
                tv.setText(nbTitles.get(i));
                tv.setGravity(Gravity.CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                tv.setPadding(dp(14), dp(14), dp(14), dp(12));
                View bar = new View(ctx);
                cell.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
                cell.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { final int to = idx; Ui.toPy(new Runnable() { public void run() { nbSelect(to); } }); }
                });
                tabBar.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            nbStyle();
        }

        /**
         * When this notebook is the whole main window (nothing else shares its screen), it goes edge to edge: no padding or
         * margins around it, so the tab strip touches the left, right and bottom of the screen with no gaps, and the version
         * label sits just above the strip. Runs after each layout; it only changes things that are not already zero.
         */
        void nbEdgeToEdge() {
            try {
                if (view == null || Ui.content == null) return;
                List<ViewGroup> chain = new ArrayList<ViewGroup>();
                View cur = view;
                while (cur != Ui.content) {
                    if (!(cur.getParent() instanceof ViewGroup)) return;             // not in the main window
                    ViewGroup g = (ViewGroup) cur.getParent();
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View sib = g.getChildAt(i);
                        if (sib != cur && sib.getVisibility() != View.GONE) return;  // something else shares the screen
                    }
                    chain.add(g);
                    cur = g;
                }
                boolean changed = false;
                for (ViewGroup g : chain) {
                    if (g.getPaddingLeft() != 0 || g.getPaddingTop() != 0 || g.getPaddingRight() != 0 || g.getPaddingBottom() != 0) {
                        g.setPadding(0, 0, 0, 0);
                        changed = true;
                    }
                }
                for (View x = view; x != Ui.content && x != null; x = (x.getParent() instanceof View) ? (View) x.getParent() : null) {
                    ViewGroup.LayoutParams lp = x.getLayoutParams();
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                        if (m.leftMargin != 0 || m.topMargin != 0 || m.rightMargin != 0 || m.bottomMargin != 0) {
                            m.setMargins(0, 0, 0, 0);
                            x.setLayoutParams(m);
                            changed = true;
                        }
                    }
                }
                if (!nbPadded) { nbPadded = true; nbPagesBox.setPadding(dp(12), dp(12), dp(12), dp(12)); changed = true; }
                if (changed) return;                                                 // another layout pass is coming
                int[] a = new int[2], b = new int[2];
                nbStrip.getLocationOnScreen(a);
                rootFrame.getLocationOnScreen(b);
                liftVersionLabel(Math.max(0, (b[1] + rootFrame.getHeight()) - a[1]));   // just above the strip
            } catch (Throwable ignored) { /* layout only */ }
        }

        boolean nbPadded;

        void nbStyle() {
            for (int i = 0; i < tabBar.getChildCount(); i++) {
                LinearLayout cell = (LinearLayout) tabBar.getChildAt(i);
                TextView tv = (TextView) cell.getChildAt(0);
                boolean on = i == nbSel;
                tv.setTextColor(on ? nbSelColor : nbIdle);
                tv.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
                cell.getChildAt(1).setBackgroundColor(on ? nbAccent : 0x00000000);
            }
        }

        /** Shows page i (runs on the py thread; the UI work is marshalled). */
        void nbSelect(final int i) {
            if (nbPages.isEmpty()) return;
            final int to = Math.max(0, Math.min(nbPages.size() - 1, i));
            final boolean changed = to != nbSel;
            nbSel = to;
            onUi(new Task() {
                Object run() {
                    for (int j = 0; j < nbPages.size(); j++) {
                        View v = nbPages.get(j).view;
                        if (v != null) v.setVisibility(j == to ? 0 : 8);
                    }
                    nbStyle();
                    return null;
                }
            });
            if (changed && nbFn != null) Ui.call(nbFn, new Ext.Event(this));
        }

        void nbAdd(Wid c, String text, int at) {
            if (nbPages.contains(c)) { nbTitles.set(nbPages.indexOf(c), text); }
            else {
                Map<String, Object> o = new LinkedHashMap<String, Object>();
                o.put("fill", "both");
                c.pack(o);
                if (at < 0 || at > nbPages.size()) at = nbPages.size();
                nbPages.add(at, c);
                nbTitles.add(at, text);
                if (nbSel >= at && nbSel >= 0) nbSel++;
            }
            if (nbSel < 0) nbSel = 0;
            onUi(new Task() { Object run() { nbRebuild(); return null; } });
            int keep = nbSel;
            nbSel = -2;               // force the page visibility refresh without an event
            nbSelectQuiet(keep);
        }

        void nbSelectQuiet(int i) {
            Object saved = nbFn;
            nbFn = null;
            nbSelect(i);
            nbFn = saved;
        }

        int nbIndex(Object t) {
            if (t instanceof Wid) return nbPages.indexOf(t);
            if (t instanceof Oop.Inst) return nbPages.indexOf(Oop.unwrap(t));
            if (t instanceof Number) return (int) ((Number) t).longValue();
            String s = Py.str(t);
            if (s.equals("end")) return nbPages.size();
            if (s.equals("current")) return nbSel;
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
        }

        Object nbCall(String name, List<Object> pos, Map<String, Object> kw) {
            Object a0 = pos.isEmpty() ? null : pos.get(0);
            switch (name) {
                case "add": {
                    Wid c = (Wid) Oop.unwrap(a0);
                    nbAdd(c, kw.containsKey("text") ? Py.str(kw.get("text")) : "Tab " + (nbPages.size() + 1), -1);
                    return null;
                }
                case "insert": {
                    int at = nbIndex(a0);
                    Wid c = (Wid) Oop.unwrap(pos.get(1));
                    nbAdd(c, kw.containsKey("text") ? Py.str(kw.get("text")) : "Tab " + (nbPages.size() + 1), at);
                    return null;
                }
                case "forget": case "hide": {
                    final int i = nbIndex(a0);
                    if (i < 0 || i >= nbPages.size()) throw new Py.PyError("TclError", "Slave index " + Py.str(a0) + " out of bounds");
                    final Wid c = nbPages.remove(i);
                    nbTitles.remove(i);
                    if (nbSel >= nbPages.size()) nbSel = nbPages.size() - 1;
                    else if (nbSel > i) nbSel--;
                    onUi(new Task() { Object run() { c.detach(); nbRebuild(); return null; } });
                    int keep = nbSel;
                    nbSel = -2;
                    if (keep >= 0) nbSelectQuiet(keep);
                    return null;
                }
                case "select": {
                    if (a0 == null) return nbSel >= 0 && nbSel < nbPages.size() ? nbPages.get(nbSel) : "";
                    int i = nbIndex(a0);
                    if (i < 0 || i >= nbPages.size()) throw new Py.PyError("TclError", "Slave index " + Py.str(a0) + " out of bounds");
                    nbSelect(i);
                    return null;
                }
                case "index": {
                    int i = nbIndex(a0);
                    if (i < 0) throw new Py.PyError("TclError", "tab '" + Py.str(a0) + "' not found");
                    return (long) i;
                }
                case "tabs": return new ArrayList<Object>(nbPages);
                case "tab": {
                    final int i = nbIndex(a0);
                    if (i < 0 || i >= nbPages.size()) throw new Py.PyError("TclError", "Slave index " + Py.str(a0) + " out of bounds");
                    if (pos.size() > 1 && kw.isEmpty()) return Py.str(pos.get(1)).equals("-text") || Py.str(pos.get(1)).equals("text") ? nbTitles.get(i) : null;
                    if (kw.containsKey("text")) {
                        nbTitles.set(i, Py.str(kw.get("text")));
                        onUi(new Task() { Object run() { nbRebuild(); return null; } });
                        return null;
                    }
                    if (kw.isEmpty() && pos.size() == 1) {
                        Map<Object, Object> d = new LinkedHashMap<Object, Object>();
                        d.put("text", nbTitles.get(i));
                        return d;
                    }
                    return null;
                }
                case "enable_traversal": return null;
                default: return NOPE;
            }
        }

        void labelFrameTitle() {
            Object t = opts.get("text");
            if (t == null || content == null) return;
            View first = ((ViewGroup) content).getChildAt(0);
            TextView head;
            if (first instanceof TextView && "apple-title".equals(first.getTag())) head = (TextView) first;
            else {
                head = new TextView(ctx);
                head.setTag("apple-title");
                head.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                ((ViewGroup) content).addView(head, 0);
            }
            head.setText(Py.str(t));
        }

        void option(String k, Object v) {
            opts.put(k, v);
            if (view == null) return;
            if (tabBar != null && (k.equals("bg") || k.equals("background") || k.equals("fg") || k.equals("foreground")
                    || k.equals("accent") || k.equals("idle") || k.equals("tabbg"))) {
                int c;
                try { c = Color.parseColor(Py.str(v)); } catch (Throwable t) { return; }
                if (k.equals("bg") || k.equals("background") || k.equals("tabbg")) { nbBg = c; tabBar.setBackgroundColor(c); }
                else if (k.equals("fg") || k.equals("foreground")) nbSelColor = c;
                else if (k.equals("accent")) nbAccent = c;
                else nbIdle = c;
                nbStyle();
                return;
            }
            if (k.equals("menu") && (kind.equals("Tk") || kind.equals("Toplevel"))) {
                Object mo = Oop.unwrap(v);
                if (mo instanceof Wid && (kind.equals("Tk") || barMenu == null)) { barMenu = (Wid) mo; rebuildBar(); }
                else if (v == null || "".equals(v)) { barMenu = null; rebuildBar(); }
                return;
            }
            if (tree != null && (k.equals("columns") || k.equals("show") || k.equals("selectmode") || k.equals("height") || k.equals("displaycolumns"))) { tree.option(k, v); return; }
            switch (k) {
                case "text":
                    if (textvar == null && tview() != null && !(view instanceof ViewGroup)) setText(Py.str(v));
                    else if (kind.equals("Spinbox") && textvar == null) setText(Py.str(v));
                    break;
                case "textvariable":
                    if (v instanceof Py.Var && tview() != null) bindText((Py.Var) v);
                    break;
                case "variable":
                    if (v instanceof Py.Var) {
                        if (kind.equals("OptionMenu")) break;
                        setVar((Py.Var) v);
                    }
                    break;
                case "value":
                    if (kind.equals("Progressbar")) { pbVal = Py.dbl(v); layoutBar(); }
                    else { value = v; syncFromVar(); }
                    break;
                case "maximum": pbMax = Py.dbl(v); layoutBar(); break;
                case "values":
                    if (v instanceof List) {
                        choices = new ArrayList<Object>((List) v);
                        if (kind.equals("Combobox") && textvar == null && !choices.isEmpty() && text().isEmpty()) setText("");
                    }
                    break;
                case "onvalue": case "offvalue":
                    syncFromVar();
                    break;
                case "command":
                    command = v;
                    break;
                case "fg": case "foreground": {
                    Integer c = color(v);
                    if (c != null && tview() != null) tview().setTextColor(c);
                    break;
                }
                case "bg": case "background": {
                    Integer c = color(v);
                    if (c != null && !kind.equals("Progressbar")) view.setBackgroundColor(c);
                    break;
                }
                case "font": applyFont(v); break;
                case "state": {
                    String s = Py.str(v).toLowerCase();
                    view.setEnabled(!s.equals("disabled"));
                    if (s.equals("readonly") && view instanceof EditText) ((EditText) view).setKeyListener(null);
                    break;
                }
                case "show":
                    if (view instanceof EditText && v != null && !Py.str(v).isEmpty()) {
                        ((EditText) view).setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    }
                    break;
                case "width":
                    if (canvas != null) { canvas.cw = (int) Py.dbl(v); canvas.requestLayout(); }
                    else if (view instanceof EditText && Py.isNum(v)) ((EditText) view).setMinEms((int) Py.dbl(v) * 2 / 3 + 1);
                    else if (kind.equals("Listbox") || kind.equals("Spinbox") || kind.equals("Progressbar")) { /* sized by layout */ }
                    break;
                case "length":
                    if (kind.equals("Progressbar") && Py.isNum(v)) {
                        ViewGroup.LayoutParams lp = view.getLayoutParams();
                        view.setMinimumWidth(dp((float) Py.dbl(v)));
                    }
                    break;
                case "height":
                    if (canvas != null) { canvas.ch = (int) Py.dbl(v); canvas.requestLayout(); }
                    else if (kind.equals("Text") && Py.isNum(v)) ((EditText) view).setMinLines((int) Py.dbl(v));
                    else if (kind.equals("Listbox") && Py.isNum(v)) view.setMinimumHeight(dp(36) * (int) Py.dbl(v));
                    break;
                case "justify": case "anchor": {
                    String s = Py.str(v).toLowerCase();
                    if (view instanceof TextView) {
                        int g = s.equals("center") ? Gravity.CENTER_HORIZONTAL
                                : (s.equals("right") || s.equals("e") || s.equals("ne") || s.equals("se")) ? Gravity.END
                                : Gravity.START;
                        ((TextView) view).setGravity(g);
                    }
                    break;
                }
                case "padx": case "pady":
                    if (!(view instanceof ViewGroup)) {
                        int px = pad(opts.get("padx"))[0], py = pad(opts.get("pady"))[0];
                        view.setPadding(px, py, px, py);
                    }
                    break;
                case "from_": lo = v; applyRange(); syncFromVar(); break;
                case "to": hi = v; applyRange(); syncFromVar(); break;
                case "resolution": resolution = v; applyRange(); break;
                case "increment": case "wrap": case "selectmode": case "label": break;
                case "title":
                    if (kind.equals("Tk")) { title = Py.str(v); applyTitle(); }
                    break;
                case "relief": case "bd": case "borderwidth": case "cursor": case "takefocus":
                case "highlightthickness": case "activebackground": case "activeforeground":
                case "orient": case "wrap_": case "wraplength": case "undo":
                case "insertbackground": case "selectcolor": case "exportselection":
                case "image": imageOpt = imgOf(v); if (imageOpt != null && !imageOpt.users.contains(this)) imageOpt.users.add(this); applyImage(); break;
                case "compound": compoundOpt = v == null ? "none" : Py.str(v).toLowerCase(); applyImage(); break;
                case "labelanchor": case "underline": case "tearoff":
                case "name": case "yscrollcommand": case "xscrollcommand":
                case "mode": case "style": case "highlightbackground": case "selectbackground":
                case "selectforeground": case "disabledforeground": case "listvariable": case "validate":
                case "validatecommand": case "invalidcommand": case "postcommand": case "indicatoron":
                case "maxundo": case "spacing1": case "spacing2": case "spacing3": case "padding":
                    break;
                default:
                    warn("ignored option " + k + " on " + kind);
            }
        }

        Img imageOpt;
        String compoundOpt = "none";
        String shownText;

        /** image= (and compound=) on a Label or Button: the picture is a compound drawable of the text view. */
        void applyImage() {
            TextView tv = view instanceof TextView && !(view instanceof EditText) ? (TextView) view : null;
            if (tv == null) return;
            Drawable d = imageOpt == null ? null : imageOpt.drawable();
            boolean both = !compoundOpt.equals("none") && !compoundOpt.isEmpty();
            if (d == null) { tv.setCompoundDrawables(null, null, null, null); }
            else if (!both || compoundOpt.equals("center")) tv.setCompoundDrawables(null, d, null, null);
            else if (compoundOpt.equals("left")) tv.setCompoundDrawables(d, null, null, null);
            else if (compoundOpt.equals("right")) tv.setCompoundDrawables(null, null, d, null);
            else if (compoundOpt.equals("bottom")) tv.setCompoundDrawables(null, null, null, d);
            else tv.setCompoundDrawables(null, d, null, null);
            tv.setCompoundDrawablePadding(dp(4));
            // with an image and no compound= tkinter shows only the image
            boolean hideText = d != null && !both;
            String want = hideText ? "" : (shownText == null ? tv.getText().toString() : shownText);
            if (!tv.getText().toString().equals(want)) tv.setText(want);
        }

        void applyFont(Object v) {
            if (!(view instanceof TextView)) return;
            TextView tv = (TextView) view;
            String family = null;
            Double size = null;
            boolean bold = false, italic = false;
            List<Object> parts = new ArrayList<Object>();
            if (v instanceof List) parts.addAll((List) v);
            else if (v instanceof String) for (String p : ((String) v).trim().split("\\s+")) parts.add(p);
            for (Object p : parts) {
                if (Py.isNum(p)) size = Math.abs(Py.dbl(p));
                else {
                    String s = Py.str(p);
                    String l = s.toLowerCase();
                    if (l.equals("bold")) bold = true;
                    else if (l.equals("italic")) italic = true;
                    else if (l.equals("normal") || l.equals("roman") || l.equals("underline") || l.equals("overstrike")) { /* ignore */ }
                    else if (l.matches("-?\\d+")) size = Math.abs(Double.parseDouble(l));
                    else if (family == null) family = s;
                }
            }
            if (size != null) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) (double) size);
            int style = (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
            Typeface base = Typeface.DEFAULT;
            if (family != null) {
                String f = family.toLowerCase();
                if (f.contains("courier") || f.contains("mono") || f.contains("consolas")) base = Typeface.MONOSPACE;
                else if (f.contains("times") || f.contains("serif") || f.contains("georgia")) base = Typeface.SERIF;
            }
            tv.setTypeface(base, style);
        }

        // ------------------------------------------------------------- layout

        void detach() {
            if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        }

        Wid host() { return parent == null ? root : parent; }

        void pack(Map<String, Object> o) {
            if (kind.equals("Tk") || view == null) return;
            Wid p = host();
            if (p.content == null) return;
            detach();
            String side = optStr(o, "side", "top");
            String fill = optStr(o, "fill", "none");
            boolean expand = flag(o.get("expand"));
            if (kind.equals("Notebook")) { fill = "both"; expand = true; }   // the tab strip is at the bottom of the screen, so the notebook fills it
            boolean sideH = side.equals("left") || side.equals("right");
            if (p.kids == 0 && p.content instanceof LinearLayout) {
                p.horiz = sideH;
                ((LinearLayout) p.content).setOrientation(sideH ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            }
            boolean fx = fill.equals("x") || fill.equals("both");
            boolean fy = fill.equals("y") || fill.equals("both");
            int w = fx ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
            int h = fy ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
            float weight = 0f;
            if (p.horiz) { if (fx || expand) { w = 0; weight = 1f; } }
            else { if (fy || expand) { h = 0; weight = 1f; } }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
            int[] px = pad(o.get("padx")), py = pad(o.get("pady"));
            lp.setMargins(px[0], py[0], px[1], py[1]);
            String anchor = optStr(o, "anchor", "center");
            boolean ac = anchor.equals("center");   // "center" itself contains the letters n and e
            if (p.horiz) {
                lp.gravity = (!ac && anchor.contains("n")) ? Gravity.TOP : (!ac && anchor.contains("s")) ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL;
            } else {
                lp.gravity = (!ac && anchor.contains("w")) ? Gravity.START : (!ac && anchor.contains("e")) ? Gravity.END : Gravity.CENTER_HORIZONTAL;
            }
            p.content.addView(view, lp);
            p.kids++;
        }

        void grid(Map<String, Object> o) {
            if (kind.equals("Tk") || view == null) return;
            Wid p = host();
            if (p.content == null) return;
            detach();
            if (p.grid == null) {
                p.grid = new GridLayout(ctx);
                p.content.addView(p.grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            int row = o.containsKey("row") ? (int) Py.toLong(o.get("row")) : p.nextRow;
            int col = o.containsKey("column") ? (int) Py.toLong(o.get("column")) : 0;
            int rs = o.containsKey("rowspan") ? (int) Py.toLong(o.get("rowspan")) : 1;
            int cs = o.containsKey("columnspan") ? (int) Py.toLong(o.get("columnspan")) : 1;
            p.nextRow = Math.max(p.nextRow, row + rs);
            String sticky = optStr(o, "sticky", "");
            boolean sw = sticky.contains("w"), se = sticky.contains("e"), sn = sticky.contains("n"), ss = sticky.contains("s");
            GridLayout.Alignment ca = (sw && se) ? GridLayout.FILL : sw ? GridLayout.LEFT : se ? GridLayout.RIGHT : GridLayout.CENTER;
            GridLayout.Alignment ra = (sn && ss) ? GridLayout.FILL : sn ? GridLayout.TOP : ss ? GridLayout.BOTTOM : GridLayout.CENTER;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(row, rs, ra, (sn && ss) ? 1f : 0f),
                    GridLayout.spec(col, cs, ca, (sw && se) ? 1f : 0f));
            if (sw && se) lp.width = 0;
            int[] px = pad(o.get("padx")), py = pad(o.get("pady"));
            lp.setMargins(px[0], py[0], px[1], py[1]);
            p.grid.addView(view, lp);
            p.kids++;
        }

        // ---------------------------------------------------- text-like access

        String text() { TextView tv = tview(); return tv != null ? tv.getText().toString() : ""; }

        int index(Object idx, String text) {
            if (idx instanceof Number) return Math.max(0, Math.min(text.length(), ((Number) idx).intValue()));
            String s = Py.str(idx).trim().toLowerCase();
            int len = text.length();
            if (s.equals("end") || s.equals("insert") || s.equals("current")) return len;
            if (s.startsWith("end-")) {
                // A Tk Text always has one hidden newline after its last character, so "end" is one position past the
                // text and "end-1c" is the end of the text itself. (An Entry has no such newline.)
                int endPos = kind.equals("Text") ? len + 1 : len;
                try { return Math.max(0, Math.min(len, endPos - Integer.parseInt(s.substring(4).replace("c", "").trim()))); } catch (NumberFormatException e) { return len; }
            }
            if (s.contains(".")) {
                try {
                    String[] pr = s.split("\\.");
                    int line = Integer.parseInt(pr[0]), c = pr[1].startsWith("end") ? Integer.MAX_VALUE : Integer.parseInt(pr[1]);
                    int off = 0;
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < line - 1 && i < lines.length; i++) off += lines[i].length() + 1;
                    int ll = line - 1 < lines.length && line >= 1 ? lines[line - 1].length() : 0;
                    return Math.max(0, Math.min(len, off + Math.min(c, ll)));
                } catch (NumberFormatException e) { return len; }
            }
            try { return Math.max(0, Math.min(len, Integer.parseInt(s))); } catch (NumberFormatException e) { return len; }
        }

        // ------------------------------------------------------------ canvas API

        CItem citem(String type, List<Object> pos, Map<String, Object> kw) {
            CItem it = new CItem();
            it.id = nextItem++;
            it.type = type;
            List<Double> cs = new ArrayList<Double>();
            for (Object p : pos) {
                if (p instanceof List) for (Object q : (List) p) cs.add(Py.dbl(q));
                else if (Py.isNum(p)) cs.add(Py.dbl(p));
            }
            it.c = new double[cs.size()];
            for (int i = 0; i < it.c.length; i++) it.c[i] = cs.get(i);
            if (type.equals("text")) { for (Object p : pos) if (p instanceof String) it.text = (String) p; }
            applyItemOpts(it, kw);
            for (Object[] bt : bindTags) if (it.tags.contains(String.valueOf(bt[0])) || String.valueOf(it.id).equals(String.valueOf(bt[0]))) it.binds.add(new Object[]{bt[1], bt[2]});
            canvas.items.add(it);
            canvas.invalidate();
            return it;
        }

        void applyItemOpts(CItem it, Map<String, Object> kw) {
            for (Map.Entry<String, Object> e : kw.entrySet()) {
                switch (e.getKey()) {
                    case "fill": { Object v = e.getValue(); it.noFill = v == null || Py.str(v).isEmpty(); it.fill = it.noFill ? null : color(v); break; }
                    case "outline": { Object v = e.getValue(); it.outline = v == null || Py.str(v).isEmpty() ? null : color(v); break; }
                    case "width": it.width = (float) Py.dbl(e.getValue()); if (it.type.equals("text")) it.wrap = it.width; break;
                    case "text": it.text = Py.str(e.getValue()); break;
                    case "start": it.start = Py.dbl(e.getValue()); break;
                    case "extent": it.extent = Py.dbl(e.getValue()); break;
                    case "style": it.style = Py.str(e.getValue()).toLowerCase(); break;
                    case "arrow": it.arrow = Py.str(e.getValue()).toLowerCase(); break;
                    case "smooth": it.smooth = flag(e.getValue()); break;
                    case "justify": it.justify = Py.str(e.getValue()).toLowerCase(); break;
                    case "state": it.hidden = Py.str(e.getValue()).equals("hidden"); break;
                    case "dash": {
                        Object v = e.getValue();
                        List<Float> d = new ArrayList<Float>();
                        if (v instanceof List) for (Object x : (List) v) d.add((float) Py.dbl(x));
                        else if (Py.isNum(v)) { d.add((float) Py.dbl(v)); d.add((float) Py.dbl(v)); }
                        else for (char ch : Py.str(v).toCharArray()) d.add(ch == '.' ? 2f : ch == '-' ? 6f : ch == ',' ? 4f : ch == ' ' ? 4f : 0f);
                        d.removeAll(java.util.Collections.singleton(0f));
                        if (d.size() % 2 == 1) d.add(d.get(d.size() - 1));
                        it.dash = null;
                        if (!d.isEmpty()) { it.dash = new float[d.size()]; for (int k = 0; k < it.dash.length; k++) it.dash[k] = d.get(k); }
                        break;
                    }
                    case "image": {
                        if (it.img != null) it.img.users.remove(canvas);
                        it.img = imgOf(e.getValue());
                        if (it.img != null && !it.img.users.contains(canvas)) it.img.users.add(canvas);
                        break;
                    }
                    case "anchor": it.anchor = Py.str(e.getValue()).toLowerCase(); break;
                    case "tags": case "tag": {
                        it.tags.clear();
                        Object t = e.getValue();
                        if (t instanceof List) for (Object x : (List) t) it.tags.add(Py.str(x)); else for (String x : Py.str(t).split("\\s+")) if (!x.isEmpty()) it.tags.add(x);
                        break;
                    }
                    case "font": {
                        Object f = e.getValue();
                        List<Object> parts = f instanceof List ? (List<Object>) f : new ArrayList<Object>(java.util.Arrays.asList((Object[]) Py.str(f).split("\\s+")));
                        for (Object x : parts) {
                            if (Py.isNum(x)) it.size = (float) Math.abs(Py.dbl(x));
                            else { String w = Py.str(x).toLowerCase(); if (w.contains("bold")) it.bold = true; if (w.contains("italic")) it.italic = true; }
                        }
                        break;
                    }
                    default: break;
                }
            }
        }

        boolean itemTouchOn;

        /** Taps and drags on canvas items run the functions given to tag_bind. */
        void installItemTouch() {
            if (itemTouchOn) return;
            itemTouchOn = true;
            final View.OnTouchListener prev = canvasTouch;
            final Wid self = this;
            canvasTouch = new View.OnTouchListener() {
                public boolean onTouch(View v, MotionEvent e) {
                    if (prev != null) prev.onTouch(v, e);
                    float d = density();
                    double x = e.getX() / d, y = e.getY() / d;
                    int act = e.getAction();
                    CItem top = null;
                    for (int i = canvas.items.size() - 1; i >= 0 && top == null; i--) if (CanvasKit.hit(canvas.items.get(i), x, y, 3, canvas.paint)) top = canvas.items.get(i);
                    if (act == 0) canvas.currentId = top == null ? -1 : top.id;
                    if (top == null) return true;
                    String want = act == 0 ? "press" : act == 1 ? "release" : act == 2 ? "motion" : "";
                    for (Object[] b : new ArrayList<Object[]>(top.binds)) {
                        String q = String.valueOf(b[0]);
                        boolean press = q.contains("Button-1") || q.contains("ButtonPress-1") || q.equals("<1>");
                        boolean rel = q.contains("ButtonRelease");
                        boolean mot = q.contains("Motion");
                        if (rel) press = false;
                        if ((want.equals("press") && press && !mot) || (want.equals("release") && rel) || (want.equals("motion") && mot))
                            Ui.call(b[1], new Ext.Event(self, "x", (long) x, "y", (long) y));
                    }
                    return true;
                }
            };
            view.setOnTouchListener(canvasTouch);
        }

        List<CItem> citems(Object sel) {
            List<CItem> r = new ArrayList<CItem>();
            String s = Py.str(sel);
            for (CItem it : canvas.items) {
                boolean ok = s.equals("all") || s.equals(String.valueOf(it.id)) || it.tags.contains(s) || (s.equals("current") && it.id == canvas.currentId);
                if (ok && !r.contains(it)) r.add(it);
            }
            return r;
        }

        CItem one(Object sel) {
            List<CItem> l = citems(sel);
            return l.isEmpty() ? null : l.get(0);
        }

        Object ids(List<CItem> l) {
            List<Object> r = new ArrayList<Object>();
            for (CItem it : l) r.add((long) it.id);
            return new Py.Tuple(r);
        }

        double[] nums(List<Object> pos, int from) {
            List<Double> cs = new ArrayList<Double>();
            for (int i = from; i < pos.size(); i++) { Object p = pos.get(i); if (p instanceof List) for (Object q : (List) p) cs.add(Py.dbl(q)); else cs.add(Py.dbl(p)); }
            double[] r = new double[cs.size()];
            for (int i = 0; i < r.length; i++) r[i] = cs.get(i);
            return r;
        }

        // ---------------------------------------------------------- method API

        @Override public Object callm(final String name, final Object[] a) {
            if (onMain()) return callm0(name, a);
            return onUi(new Task() { Object run() { return callm0(name, a); } });
        }

        void applyTitle() {
            if (overlay && titleView != null) titleView.setText(draggable ? "☰  " + lockedTitle() : lockedTitle());
            else if (act != null) act.setTitle(title);
        }

        Object callm0(String name, Object[] a) {
            List<Object> pos = new ArrayList<Object>();
            Map<String, Object> kw = new LinkedHashMap<String, Object>();
            for (Object x : a) {
                if (x instanceof Py.Kw) kw.put(((Py.Kw) x).k, ((Py.Kw) x).v); else pos.add(x);
            }
            Object a0 = pos.size() > 0 ? pos.get(0) : null;
            Object a1 = pos.size() > 1 ? pos.get(1) : null;
            if (win != null) {
                Object r = win.method(name, kw, a0, a1, pos.size());
                if (r != OvWin.PASS) return r;
            }
            if (name.startsWith("@")) return opts.get(name.substring(1));
            if (name.equals("__getitem__")) return opts.get(Py.str(a0));
            if (name.equals("__setitem__")) { option(Py.str(a0), a1); return null; }
            if (canvas != null) {
                Object r = canvasCall(name, pos, kw);
                if (r != NOPE) return r;
            }
            if (lbBox != null) {
                Object r = listboxCall(name, pos);
                if (r != NOPE) return r;
            }
            if (tree != null) {
                Object r = tree.call(name, pos, kw);
                if (r != Tree.NOPE) return r;
            }
            if (kind.equals("Menu")) {
                Object r = menuCall(name, pos, kw);
                if (r != NOPE) return r;
            }
            if (tabBar != null) {
                Object r = nbCall(name, pos, kw);
                if (r != NOPE) return r;
            }
            if (fillView != null) {
                switch (name) {
                    case "step": pbVal = Math.min(pbMax, pbVal + (pos.isEmpty() ? 1.0 : Py.dbl(a0))); layoutBar(); pushPb(); return null;
                    case "start": case "stop": return null;
                }
            }
            switch (name) {
                case "pack": case "place": pack(kw); return null;
                case "grid": grid(kw); return null;
                case "pack_forget": case "grid_forget": case "place_forget": detach(); return null;
                case "add": return null;
                case "destroy":
                    if (win != null) win.close();
                    else if (kind.equals("Tk")) quit(); else detach();
                    return null;
                case "quit": quit(); return null;
                case "config": case "configure": {
                    if (pos.isEmpty() && kw.isEmpty()) return null;
                    if (a0 instanceof String && kw.isEmpty()) return opts.get(a0);
                    config(kw);
                    if (kind.equals("Tk")) { if (kw.containsKey("title")) { title = Py.str(kw.get("title")); applyTitle(); } }
                    return null;
                }
                case "cget": return opts.get(Py.str(a0));
                case "get": {
                    if (view instanceof SeekBar) return scaleValue();
                    if (fillView != null) return pbVal;
                    if (kind.equals("Spinbox") || tview() instanceof EditText) {
                        String t = text();
                        if (kind.equals("Text") && !pos.isEmpty()) {
                            int from = index(a0, t), to = pos.size() > 1 ? index(a1, t) : Math.min(t.length(), from + 1);
                            return to > from ? t.substring(from, to) : "";
                        }
                        return t;
                    }
                    if (var != null) return var.get();
                    return text();
                }
                case "set":
                    if (view instanceof SeekBar) {
                        if (var != null) widgetSet(a0);
                        ((SeekBar) view).setProgress(progressFor(a0));
                    } else if (kind.equals("Combobox") || kind.equals("OptionMenu")) {
                        setText(Py.str(a0));
                        if (textvar != null) widgetSet2(a0);
                    }
                    return null;
                case "current": {
                    if (pos.isEmpty()) {
                        for (int i = 0; i < choices.size(); i++) if (Py.str(choices.get(i)).equals(text())) return (long) i;
                        return -1L;
                    }
                    int i = (int) Py.toLong(a0);
                    if (i >= 0 && i < choices.size()) { setText(Py.str(choices.get(i))); if (textvar != null) widgetSet2(choices.get(i)); }
                    return null;
                }
                case "insert": {
                    TextView tv = tview();
                    if (!(tv instanceof EditText)) return null;
                    EditText et = (EditText) tv;
                    String cur = et.getText().toString();
                    et.getText().insert(index(a0, cur), Py.str(a1));
                    return null;
                }
                case "delete": {
                    TextView tv = tview();
                    if (!(tv instanceof EditText)) return null;
                    EditText et = (EditText) tv;
                    String cur = et.getText().toString();
                    int from = index(a0, cur);
                    int to = pos.size() > 1 ? index(a1, cur) : Math.min(cur.length(), from + 1);
                    if (to > from) et.getText().delete(from, to);
                    return null;
                }
                case "focus": case "focus_set": case "focus_force":
                    if (view != null) { view.requestFocus(); if (view instanceof EditText) wantKeyboard(view); }
                    return null;
                case "invoke":
                    if (view instanceof CompoundButton) {
                        ((CompoundButton) view).toggle();
                    } else fire();
                    return null;
                case "select": case "deselect": case "toggle": {
                    if (view instanceof CompoundButton) {
                        CompoundButton cb = (CompoundButton) view;
                        if (name.equals("toggle")) cb.toggle(); else cb.setChecked(name.equals("select"));
                        if (kind.equals("Radiobutton") && var != null && name.equals("select")) widgetSet(value);
                    }
                    return null;
                }
                case "bind": {
                    bind(Py.str(a0), a1);
                    return null;
                }
                case "after": {
                    if (pos.isEmpty()) return null;
                    if (pos.size() < 2) {
                        // after(ms): sleep on the py thread like Tk's blocking form
                        try { Thread.sleep((long) Py.dbl(a0)); } catch (InterruptedException e) { /* ignore */ }
                        return null;
                    }
                    final Object fn = a1;
                    final Object[] extra = pos.subList(2, pos.size()).toArray();
                    final long id = nextTimer++;
                    Runnable r = new Runnable() {
                        public void run() { timers.remove(id); Ui.call(fn, extra); }
                    };
                    timers.put(id, r);
                    handler.postDelayed(r, (long) Py.dbl(a0));
                    return "after#" + id;
                }
                case "after_idle": {
                    final Object fn = a0;
                    handler.post(new Runnable() { public void run() { Ui.call(fn); } });
                    return null;
                }
                case "after_cancel": {
                    String s = Py.str(a0);
                    if (s.startsWith("after#")) {
                        Runnable r = timers.remove(Long.parseLong(s.substring(6)));
                        if (r != null) handler.removeCallbacks(r);
                    }
                    return null;
                }
                case "title": case "wm_title":
                    if (a0 != null) { title = Py.str(a0); applyTitle(); }
                    return title;
                case "protocol":
                    if (pos.size() > 1 && Py.str(a0).equals("WM_DELETE_WINDOW")) closeFn = a1;
                    return null;
                case "winfo_width": case "winfo_reqwidth": return view == null ? 0L : (Object) (long) view.getWidth();
                case "winfo_height": case "winfo_reqheight": return view == null ? 0L : (Object) (long) view.getHeight();
                case "winfo_screenwidth": return (long) ctx.getResources().getDisplayMetrics().widthPixels;
                case "winfo_screenheight": return (long) ctx.getResources().getDisplayMetrics().heightPixels;
                case "geometry": case "wm_geometry":
                    if (a0 == null) return overlay ? currentGeometry() : null;
                    applyGeometry(Py.str(a0));
                    return null;
                case "attributes": case "wm_attributes":
                    if (overlay && a0 != null && Py.str(a0).equals("-alpha") && a1 != null) setWindowAlpha((float) Py.dbl(a1));
                    return null;
                case "iconify": setMinimized(true); return null;
                case "deiconify": setMinimized(false); return null;
                case "mainloop": case "update": case "update_idletasks": case "resizable":
                case "minsize": case "maxsize": case "iconbitmap": case "iconphoto":
                case "columnconfigure": case "rowconfigure": case "grid_columnconfigure":
                case "grid_rowconfigure": case "pack_propagate": case "grid_propagate": case "option_add":
                case "lift": case "lower": case "withdraw": case "state": case "tkraise":
                case "focus_get": case "selection_clear": case "select_range":
                case "tag_configure": case "see": case "icursor": case "bell":
                case "yview": case "xview": case "add_command": case "add_cascade": case "add_separator":
                case "add_checkbutton": case "add_radiobutton": case "overrideredirect": case "tab":
                case "select_clear": case "configure_": case "theme_use": case "map": case "tag_add": case "tag_remove":
                    return null;
                default:
                    warn("unsupported method " + name + " on " + kind);
                    return null;
            }
        }

        void widgetSet2(Object v) { widgetSet0(v); }

        void widgetSet0(final Object v) {
            final Py.Var tv = textvar;
            final Wid self = this;
            toPy(new Runnable() { public void run() { tv.setFromText(v, self); } });
        }

        void pushPb() {
            if (var != null) {
                final Object v = pbVal;
                final Py.Var t = var;
                toPy(new Runnable() { public void run() { t.setFromText(v, null); } });
            }
        }

        void bind(String seq, final Object fn) {
            if (view == null || fn == null) return;
            if (seq.contains("NotebookTabChanged")) { nbFn = fn; return; }
            if (seq.contains("Button-3") || seq.contains("Button-2")) {
                final Wid self = this;
                view.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) { Ui.call(fn, new Ext.Event(self, "x", 0L, "y", 0L)); return true; }
                });
                return;
            }
            if (tree != null && (seq.contains("Treeview") || seq.contains("Double") || seq.contains("Return") || seq.contains("Button-1"))) { tree.bind(seq, fn); return; }
            if (seq.contains("ListboxSelect") || seq.contains("ComboboxSelected") || seq.contains("Spinbox")) { selFn = fn; return; }
            final Wid self = this;
            if (canvas != null && (seq.contains("Button-1") || seq.contains("Motion") || seq.contains("ButtonRelease"))) {
                final boolean press = seq.contains("Button-1") && !seq.contains("Motion") && !seq.contains("Release");
                final boolean motion = seq.contains("Motion");
                final boolean release = seq.contains("Release");
                final View.OnTouchListener prev = canvasTouch;
                canvasTouch = new View.OnTouchListener() {
                    public boolean onTouch(View v, MotionEvent e) {
                        if (prev != null) prev.onTouch(v, e);
                        int act = e.getAction();
                        float d = density();
                        if ((press && act == 0) || (motion && act == 2) || (release && act == 1)) {
                            Ui.call(fn, new Ext.Event(self, "x", (long) (e.getX() / d), "y", (long) (e.getY() / d)));
                        }
                        return true;
                    }
                };
                view.setOnTouchListener(canvasTouch);
                return;
            }
            if (seq.contains("Return") || seq.contains("KP_Enter")) {
                if (tview() instanceof EditText) {
                    ((EditText) tview()).setOnEditorActionListener(new TextView.OnEditorActionListener() {
                        public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                            Ui.call(fn, new Ext.Event(self, "keysym", "Return", "char", "\r"));
                            return true;
                        }
                    });
                }
            } else if (seq.contains("Button-1") || seq.contains("ButtonRelease-1") || seq.contains("Double-Button-1")) {
                view.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { Ui.call(fn, new Ext.Event(self, "x", 0L, "y", 0L)); }
                });
            } else if (seq.contains("KeyRelease") || seq.contains("KeyPress") || seq.contains("Key>") || seq.equals("<Key>")) {
                if (tview() instanceof EditText) {
                    tview().addTextChangedListener(new TextWatcher() {
                        public void beforeTextChanged(CharSequence s, int x, int y, int z) {}
                        public void onTextChanged(CharSequence s, int x, int y, int z) {}
                        public void afterTextChanged(Editable e) {
                            String t = e.toString();
                            String ch = t.isEmpty() ? "" : t.substring(t.length() - 1);
                            Ui.call(fn, new Ext.Event(self, "char", ch, "keysym", ch));
                        }
                    });
                }
            }
        }

        View.OnTouchListener canvasTouch;

        static final Object NOPE = new Object();

        Object listboxCall(String name, List<Object> pos) {
            Object a0 = pos.size() > 0 ? pos.get(0) : null;
            Object a1 = pos.size() > 1 ? pos.get(1) : null;
            switch (name) {
                case "insert": {
                    int at = Math.max(0, Math.min(lbItems.size(), lbIdx(a0, true)));
                    for (int i = 1; i < pos.size(); i++) { lbItems.add(at, Py.str(pos.get(i))); lbSel.add(at, Boolean.FALSE); at++; }
                    lbRebuild();
                    return null;
                }
                case "delete": {
                    int from = Math.max(0, lbIdx(a0, false));
                    int to = pos.size() > 1 ? lbIdx(a1, false) : from;
                    for (int i = Math.min(to, lbItems.size() - 1); i >= from; i--) { lbItems.remove(i); lbSel.remove(i); }
                    lbRebuild();
                    return null;
                }
                case "get": {
                    if (pos.size() == 1) {
                        int i = lbIdx(a0, false);
                        return i >= 0 && i < lbItems.size() ? lbItems.get(i) : "";
                    }
                    int from = Math.max(0, lbIdx(a0, false)), to = Math.min(lbItems.size() - 1, lbIdx(a1, false));
                    List<Object> r = new ArrayList<Object>();
                    for (int i = from; i <= to; i++) r.add(lbItems.get(i));
                    return new Py.Tuple(r);
                }
                case "size": return (long) lbItems.size();
                case "curselection": {
                    List<Object> r = new ArrayList<Object>();
                    for (int i = 0; i < lbSel.size(); i++) if (lbSel.get(i)) r.add((long) i);
                    return new Py.Tuple(r);
                }
                case "selection_set": case "select_set": {
                    int from = lbIdx(a0, false), to = pos.size() > 1 ? lbIdx(a1, false) : from;
                    if (!optStr(opts, "selectmode", "browse").matches("multiple|extended")) for (int i = 0; i < lbSel.size(); i++) lbSel.set(i, false);
                    for (int i = Math.max(0, from); i <= to && i < lbSel.size(); i++) lbSel.set(i, Boolean.TRUE);
                    lbRebuild();
                    return null;
                }
                case "selection_clear": case "select_clear": {
                    int from = lbIdx(a0, false), to = pos.size() > 1 ? lbIdx(a1, false) : from;
                    for (int i = Math.max(0, from); i <= to && i < lbSel.size(); i++) lbSel.set(i, Boolean.FALSE);
                    lbRebuild();
                    return null;
                }
                case "selection_includes": { int i = lbIdx(a0, false); return Boolean.valueOf(i >= 0 && i < lbSel.size() && lbSel.get(i)); }
                case "index": return (long) lbIdx(a0, true);
                case "activate": case "see": case "itemconfig": case "itemconfigure": return null;
                default: return NOPE;
            }
        }

        Object canvasCall(String name, List<Object> pos, Map<String, Object> kw) {
            Object a0 = pos.size() > 0 ? pos.get(0) : null;
            Object a1 = pos.size() > 1 ? pos.get(1) : null;
            Paint mp = canvas.paint;
            switch (name) {
                case "create_line": return (long) citem("line", pos, kw).id;
                case "create_rectangle": return (long) citem("rectangle", pos, kw).id;
                case "create_oval": return (long) citem("oval", pos, kw).id;
                case "create_polygon": return (long) citem("polygon", pos, kw).id;
                case "create_text": return (long) citem("text", pos, kw).id;
                case "create_image": return (long) citem("image", pos, kw).id;
                case "create_arc": return (long) citem("arc", pos, kw).id;
                case "delete": {
                    if (pos.isEmpty()) a0 = "all";
                    for (Object sel : pos.isEmpty() ? java.util.Collections.<Object>singletonList("all") : pos) canvas.items.removeAll(citems(sel));
                    canvas.invalidate();
                    return null;
                }
                case "move": {
                    double dx = Py.dbl(pos.get(1)), dy = Py.dbl(pos.get(2));
                    for (CItem it : citems(a0)) for (int i = 0; i + 1 < it.c.length; i += 2) { it.c[i] += dx; it.c[i + 1] += dy; }
                    canvas.invalidate();
                    return null;
                }
                case "moveto": {
                    List<CItem> hit = citems(a0);
                    float x1 = Float.MAX_VALUE, y1 = Float.MAX_VALUE;
                    for (CItem it : hit) { float[] b = CanvasKit.bbox(it, mp); if (b != null) { x1 = Math.min(x1, b[0]); y1 = Math.min(y1, b[1]); } }
                    if (x1 == Float.MAX_VALUE) return null;
                    double dx = a1 == null || "".equals(a1) ? 0 : Py.dbl(a1) - x1, dy = pos.size() < 3 || "".equals(pos.get(2)) ? 0 : Py.dbl(pos.get(2)) - y1;
                    for (CItem it : hit) for (int i = 0; i + 1 < it.c.length; i += 2) { it.c[i] += dx; it.c[i + 1] += dy; }
                    canvas.invalidate();
                    return null;
                }
                case "scale": {
                    double ox = Py.dbl(pos.get(1)), oy = Py.dbl(pos.get(2)), sx = Py.dbl(pos.get(3)), sy = Py.dbl(pos.get(4));
                    for (CItem it : citems(a0)) for (int i = 0; i + 1 < it.c.length; i += 2) { it.c[i] = ox + (it.c[i] - ox) * sx; it.c[i + 1] = oy + (it.c[i + 1] - oy) * sy; }
                    canvas.invalidate();
                    return null;
                }
                case "coords": {
                    List<CItem> hit = citems(a0);
                    if (hit.isEmpty()) return new Py.Tuple(new ArrayList<Object>());
                    if (pos.size() > 1) { hit.get(0).c = nums(pos, 1); canvas.invalidate(); return null; }
                    List<Object> r = new ArrayList<Object>();
                    for (double d : hit.get(0).c) r.add(d);
                    return new Py.Tuple(r);
                }
                case "itemconfig": case "itemconfigure": {
                    for (CItem it : citems(a0)) applyItemOpts(it, kw);
                    canvas.invalidate();
                    return null;
                }
                case "itemcget": {
                    CItem it = one(a0);
                    if (it == null) return "";
                    switch (Py.str(a1).replace("-", "")) {
                        case "fill": return it.fill == null ? "" : String.format("#%06x", it.fill & 0xFFFFFF);
                        case "outline": return it.outline == null ? "" : String.format("#%06x", it.outline & 0xFFFFFF);
                        case "width": return (double) it.width;
                        case "text": return it.text;
                        case "state": return it.hidden ? "hidden" : "normal";
                        case "tags": return new Py.Tuple(new ArrayList<Object>(it.tags));
                        case "start": return it.start;
                        case "extent": return it.extent;
                        case "anchor": return it.anchor;
                        default: return "";
                    }
                }
                case "type": { CItem it = one(a0); return it == null ? null : it.type; }
                case "gettags": { CItem it = one(a0); return new Py.Tuple(it == null ? new ArrayList<Object>() : new ArrayList<Object>(it.tags)); }
                case "bbox": {
                    float x1 = Float.MAX_VALUE, y1 = Float.MAX_VALUE, x2 = -Float.MAX_VALUE, y2 = -Float.MAX_VALUE;
                    for (CItem it : pos.isEmpty() ? canvas.items : citems(a0)) {
                        float[] b = CanvasKit.bbox(it, mp);
                        if (b == null) continue;
                        x1 = Math.min(x1, b[0]); y1 = Math.min(y1, b[1]); x2 = Math.max(x2, b[2]); y2 = Math.max(y2, b[3]);
                    }
                    if (x1 == Float.MAX_VALUE) return null;
                    List<Object> r = new ArrayList<Object>();
                    r.add((long) Math.floor(x1)); r.add((long) Math.floor(y1)); r.add((long) Math.ceil(x2)); r.add((long) Math.ceil(y2));
                    return new Py.Tuple(r);
                }
                case "find_all": return ids(canvas.items);
                case "find_withtag": return ids(citems(a0));
                case "find_closest": {
                    double x = Py.dbl(a0), y = Py.dbl(a1), best = Double.MAX_VALUE;
                    CItem bi = null;
                    for (CItem it : canvas.items) {
                        float[] b = CanvasKit.bbox(it, mp);
                        if (b == null || it.hidden) continue;
                        double dx = Math.max(Math.max(b[0] - x, 0), x - b[2]), dy = Math.max(Math.max(b[1] - y, 0), y - b[3]);
                        double d = Math.hypot(dx, dy);
                        if (d <= best) { best = d; bi = it; }          // later (higher) items win ties
                    }
                    return ids(bi == null ? new ArrayList<CItem>() : java.util.Collections.singletonList(bi));
                }
                case "find_overlapping": case "find_enclosed": {
                    double x1 = Py.dbl(a0), y1 = Py.dbl(a1), x2 = Py.dbl(pos.get(2)), y2 = Py.dbl(pos.get(3));
                    List<CItem> r = new ArrayList<CItem>();
                    for (CItem it : canvas.items) {
                        float[] b = CanvasKit.bbox(it, mp);
                        if (it.hidden) continue;
                        if (name.equals("find_overlapping") ? CanvasKit.overlaps(b, x1, y1, x2, y2) : CanvasKit.enclosed(b, x1, y1, x2, y2)) r.add(it);
                    }
                    return ids(r);
                }
                case "find_above": case "find_below": {
                    CItem it = one(a0);
                    int i = it == null ? -1 : canvas.items.indexOf(it);
                    int j = name.equals("find_above") ? i + 1 : i - 1;
                    return ids(i >= 0 && j >= 0 && j < canvas.items.size() ? java.util.Collections.singletonList(canvas.items.get(j)) : new ArrayList<CItem>());
                }
                case "addtag_withtag": case "addtag_all": case "addtag_above": case "addtag_below": case "addtag_closest": case "addtag_enclosed": case "addtag_overlapping": {
                    String tag = Py.str(a0);
                    List<CItem> hit;
                    if (name.equals("addtag_all")) hit = new ArrayList<CItem>(canvas.items);
                    else if (name.equals("addtag_withtag")) hit = citems(a1);
                    else {
                        String find = "find_" + name.substring(7);
                        Object ids = canvasCall(find, new ArrayList<Object>(pos.subList(1, pos.size())), kw);
                        hit = new ArrayList<CItem>();
                        for (Object id : (List<?>) ids) hit.addAll(citems(String.valueOf(id)));
                    }
                    for (CItem it : hit) if (!it.tags.contains(tag)) it.tags.add(tag);
                    return null;
                }
                case "dtag": {
                    for (CItem it : citems(a0)) { if (pos.size() > 1) it.tags.remove(Py.str(a1)); else it.tags.clear(); }
                    return null;
                }
                case "tag_raise": case "lift": case "tag_lower": case "lower": {
                    List<CItem> hit = citems(a0);
                    boolean up = name.equals("tag_raise") || name.equals("lift");
                    CItem ref = pos.size() > 1 ? one(a1) : null;
                    canvas.items.removeAll(hit);
                    int at = up ? canvas.items.size() : 0;
                    if (ref != null && canvas.items.contains(ref)) at = canvas.items.indexOf(ref) + (up ? 1 : 0);
                    canvas.items.addAll(at, hit);
                    canvas.invalidate();
                    return null;
                }
                case "tag_bind": {
                    if (pos.size() < 3) return null;
                    final String sel = Py.str(a0), seq = Py.str(a1);
                    final Object fn = pos.get(2);
                    for (CItem it : canvas.items) if (it.tags.contains(sel) || String.valueOf(it.id).equals(sel)) it.binds.add(new Object[]{seq, fn});
                    bindTags.add(new Object[]{sel, seq, fn});
                    installItemTouch();
                    return null;
                }
                case "tag_unbind": {
                    for (CItem it : citems(a0)) it.binds.clear();
                    for (java.util.Iterator<Object[]> i = bindTags.iterator(); i.hasNext(); ) if (i.next()[0].equals(Py.str(a0))) i.remove();
                    return null;
                }
                case "canvasx": case "canvasy": return a0;
                case "focus": case "icursor": case "select_clear": case "scan_mark": case "scan_dragto": return null;
                case "winfo_width": return (long) (canvas.getWidth() / density());
                case "winfo_height": return (long) (canvas.getHeight() / density());
                default: return NOPE;
            }
        }
    }
}
