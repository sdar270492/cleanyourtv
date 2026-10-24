package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Muro sobre Órbita" Home (theme key video_wall): the focused YouTube video fills the screen behind a slow
 * Ken Burns drift, its title sits top left, a 3D wall of the latest videos floats in the lower third and the
 * apps are a row of glass pills at the bottom. The pictures come from YouTubeWallpapers (wallpapers/yt-NN.jpg,
 * captions.txt, yt-ids.txt).
 *
 * Built for smoothness on a 2 GB TV: the whole wall is one View drawn from small bitmaps prepared off the main
 * thread (card and reflection baked into one), moved by a spring that is retargeted on every key, so holding
 * Left/Right glides instead of stepping. The backdrop only changes once the wall settles, decoded in the
 * background, and its drift is a property animation that never redraws the picture.
 */
final class VideoWall {

    interface Host {
        void openApp(String pkg);
        void openLibrary();
        View topButton();
    }

    private static final String TAG_IDS = "yt-ids.txt";
    private static final int AMBER = 0xFFFFB454;
    private static final long HERO_SETTLE_MS = 170;

    private final Context ctx;
    private final Host host;
    private final boolean animate;
    private final float u;              // 1% of the screen width
    private final float margin;         // the top bar's side padding, so everything lines up with it
    private final List<Item> items = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> heroCache = new LruCache<String, Bitmap>(4 * 1280 * 720 * 4) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private final ImageView heroBack, heroFront;
    private final TextView channel, title, sub, wallLabel, empty;
    private final View metaBox;
    private final WallView wall;
    private final LinearLayout apps;
    private final List<TextView> pills = new ArrayList<>();
    private List<String> pillPkgs = new ArrayList<>();
    private ObjectAnimator drift;
    private final FrameLayout root;
    /** The drift waits while the remote is in use, so every frame of the GPU goes to the wall. */
    private boolean navigating;
    private final Runnable navigationDone = () -> {
        navigating = false;
        if (drift != null && drift.isPaused()) drift.resume();
    };

    private int index;
    private int shownHero = -1;
    private int lastPill;
    private boolean pillHadFocus;
    private final Runnable settleHero = this::showHero;

    static final class Item {
        final File picture;
        final String id, title, channel;
        Bitmap slab;   // card + reflection, made off the main thread
        Item(File picture, String id, String title, String channel) {
            this.picture = picture; this.id = id; this.title = title; this.channel = channel;
        }
    }

    VideoWall(Context ctx, FrameLayout root, int insertAt, boolean animate, Host host) {
        this.ctx = ctx;
        this.host = host;
        this.animate = animate;
        int w = ctx.getResources().getDisplayMetrics().widthPixels;
        float density = ctx.getResources().getDisplayMetrics().density;
        u = w / 100f;
        margin = 56 * density;

        // The shade is painted into each backdrop picture once, off the main thread (see heroBitmap), and the
        // backdrop is the only full-screen fill: root's own colour is dropped once a picture is up. Every
        // full-screen layer costs about a third of a frame on this class of GPU (Mali-G52, 4K panel).
        heroBack = heroView();
        heroBack.setBackgroundColor(Color.BLACK);
        heroFront = heroView();
        heroFront.setAlpha(0f);
        this.root = root;

        FrameLayout layer = new FrameLayout(ctx);
        layer.setClipChildren(false);
        layer.addView(heroBack, full());
        layer.addView(heroFront, full());

        // Title block, top left, under the clock.
        LinearLayout meta = new LinearLayout(ctx);
        meta.setOrientation(LinearLayout.VERTICAL);
        channel = text(1.0f, "outfit_regular", AMBER);
        channel.setLetterSpacing(0.2f);   // upper case is applied to the text itself: setAllCaps with letter spacing cuts it short
        title = text(3.3f, "outfit_medium", Color.WHITE);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setLineSpacing(0f, 1.0f);
        sub = text(1.2f, "outfit_light", 0xFFCFD5E2);
        TextView cta = text(1.1f, "outfit_medium", 0xFF0B0D12);
        cta.setText("▶  Ver ahora");
        GradientDrawable ctaBg = new GradientDrawable();
        ctaBg.setColor(Color.WHITE);
        ctaBg.setCornerRadius(0.6f * u);
        cta.setBackground(ctaBg);
        cta.setPadding(Math.round(1.6f * u), Math.round(0.7f * u), Math.round(1.6f * u), Math.round(0.7f * u));
        meta.addView(channel);
        meta.addView(title, gapTop(0.7f));
        meta.addView(sub, gapTop(0.7f));
        LinearLayout.LayoutParams ctaLp = gapTop(1.1f);
        ctaLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        meta.addView(cta, ctaLp);
        metaBox = meta;
        FrameLayout.LayoutParams metaLp = new FrameLayout.LayoutParams(Math.round(44 * u), ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = Math.round(margin);
        metaLp.topMargin = Math.round(8.2f * u);
        layer.addView(meta, metaLp);

        wallLabel = text(1.0f, "outfit_regular", 0xFFCFD5E2);
        wallLabel.setLetterSpacing(0.18f);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = Math.round(margin);
        labelLp.topMargin = Math.round(29.2f * u);
        layer.addView(wallLabel, labelLp);

        wall = new WallView(ctx);
        FrameLayout.LayoutParams wallLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(16.5f * u));
        wallLp.topMargin = Math.round(31.0f * u);
        layer.addView(wall, wallLp);

        empty = text(1.4f, "outfit_light", 0xFFCFD5E2);
        empty.setText("Preparando los videos de tus canales…");
        empty.setVisibility(View.GONE);
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        emptyLp.topMargin = Math.round(34 * u);
        layer.addView(empty, emptyLp);

        apps = new LinearLayout(ctx);
        apps.setOrientation(LinearLayout.HORIZONTAL);
        apps.setClipChildren(false);
        FrameLayout.LayoutParams appsLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(4.1f * u), Gravity.BOTTOM);
        appsLp.leftMargin = Math.round(margin);
        appsLp.rightMargin = Math.round(margin);
        appsLp.bottomMargin = Math.round(2.8f * u);
        layer.addView(apps, appsLp);

        root.addView(layer, insertAt, full());
        reload();
    }

    // ---- data --------------------------------------------------------------------------------------------

    /** Reads the pictures YouTubeWallpapers left in the theme folder; keeps the focused video if it is still there. */
    void reload() {
        String keep = index < items.size() ? items.get(index).id : null;
        items.clear();
        File dir = Config.dir(ctx);
        File folder = dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS);
        List<String> ids = new ArrayList<>();
        try {
            File f = folder == null ? null : new File(folder, TAG_IDS);
            if (f != null && f.isFile()) ids.addAll(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        Map<String, String> captions = WallpaperCaptions.read(folder);
        for (int i = 0; i < ids.size(); i++) {
            String name = String.format(Locale.ROOT, "yt-%02d.jpg", i);
            File pic = new File(folder, name);
            String id = ids.get(i).trim();
            if (id.isEmpty() || !pic.isFile()) continue;
            String text = captions.containsKey(name) ? captions.get(name) : "";
            int cut = text.lastIndexOf(" · ");
            items.add(new Item(pic, id, cut > 0 ? text.substring(0, cut) : text, cut > 0 ? text.substring(cut + 3) : ""));
        }
        index = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(keep)) index = i;
        shownHero = -1;
        boolean none = items.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        metaBox.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        wallLabel.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        wall.jumpTo(index);
        updateMeta(false);
        showHero();
        prepareSlabs(new ArrayList<>(items));
    }

    void setApps(List<String> pkgs, List<String> labels, boolean library) {
        if (pkgs.equals(pillPkgs) && pills.size() == pkgs.size() + (library ? 1 : 0)) return;
        boolean hadFocus = apps.hasFocus();
        pillPkgs = new ArrayList<>(pkgs);
        apps.removeAllViews();
        pills.clear();
        for (int i = 0; i < pkgs.size(); i++) {
            final String pkg = pkgs.get(i);
            addPill(labels.get(i), v -> host.openApp(pkg));
        }
        if (library) addPill("Todas las apps", v -> host.openLibrary());
        lastPill = Math.min(lastPill, Math.max(0, pills.size() - 1));
        if (hadFocus && !pills.isEmpty()) pills.get(lastPill).requestFocus();
    }

    private void addPill(String label, View.OnClickListener click) {
        TextView p = text(1.15f, "outfit_medium", 0);
        p.setText(label);
        p.setGravity(Gravity.CENTER);
        p.setSingleLine(true);
        p.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF0B0D12, Color.WHITE}));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, pill(Color.WHITE, Color.WHITE));
        bg.addState(new int[]{}, pill(0x1FFFFFFF, 0x29FFFFFF));
        p.setBackground(bg);
        p.setFocusable(true);
        p.setOnClickListener(click);
        final int at = pills.size();
        p.setOnFocusChangeListener((v, has) -> {
            if (has) { lastPill = at; pillHadFocus = true; }
            if (animate) v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(140).start();
        });
        p.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { focusWall(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && at == 0) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && at == pills.size() - 1;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (!pills.isEmpty()) lp.leftMargin = Math.round(u);
        apps.addView(p, lp);
        pills.add(p);
    }

    // ---- focus -------------------------------------------------------------------------------------------

    /** Where Home's focus goes: the wall, or the pill that opened the app we are coming back from. */
    void focus() {
        if (pillHadFocus && !pills.isEmpty()) pills.get(Math.min(lastPill, pills.size() - 1)).requestFocus();
        else focusWall();
    }

    /** The Home key: back to the newest video. */
    void home() {
        pillHadFocus = false;
        if (!items.isEmpty() && index != 0) select(0);
        focusWall();
    }

    private void focusWall() {
        pillHadFocus = false;
        if (items.isEmpty() && !pills.isEmpty()) pills.get(0).requestFocus();
        else wall.requestFocus();
    }

    void resume() {
        if (drift != null && !navigating) drift.resume();
    }

    void pause() {
        if (drift != null) drift.pause();
        main.removeCallbacks(settleHero);
        main.removeCallbacks(navigationDone);
        navigating = false;
    }

    void release() {
        if (drift != null) drift.cancel();
        worker.shutdownNow();
        heroCache.evictAll();
    }

    private void select(int i) {
        if (items.isEmpty()) return;
        i = Math.max(0, Math.min(items.size() - 1, i));
        if (i == index) return;
        index = i;
        navigating = true;
        if (drift != null && drift.isRunning()) drift.pause();
        main.removeCallbacks(navigationDone);
        main.postDelayed(navigationDone, 1400);
        wall.moveTo(i);
        updateMeta(true);
        main.removeCallbacks(settleHero);
        main.postDelayed(settleHero, HERO_SETTLE_MS);
    }

    private void play() {
        if (items.isEmpty()) return;
        Intent watch = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + items.get(index).id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(new Intent(watch).setPackage("com.google.android.youtube.tv"));
        } catch (ActivityNotFoundException e) {
            try { ctx.startActivity(watch); } catch (ActivityNotFoundException ignored) { }
        }
    }

    // ---- title block -------------------------------------------------------------------------------------

    private void updateMeta(boolean fade) {
        if (items.isEmpty()) return;
        Item it = items.get(index);
        Runnable set = () -> {
            channel.setText((it.channel.isEmpty() ? "Tus canales" : it.channel).toUpperCase(Locale.getDefault()));
            title.setText(it.title.isEmpty() ? "Video nuevo" : it.title);
            sub.setText("Nuevo en tus suscripciones");
            wallLabel.setText(String.format(Locale.ROOT, "NUEVO EN TUS CANALES · %d/%d", index + 1, items.size()));
        };
        if (!fade || !animate) { set.run(); return; }
        metaBox.animate().cancel();
        metaBox.animate().alpha(0f).translationY(0.4f * u).setDuration(90).withEndAction(() -> {
            set.run();
            metaBox.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        }).start();
        wallLabel.setText(String.format(Locale.ROOT, "NUEVO EN TUS CANALES · %d/%d", index + 1, items.size()));
    }

    // ---- backdrop ----------------------------------------------------------------------------------------

    private void showHero() {
        if (items.isEmpty() || shownHero == index) return;
        final int want = index;
        final Item it = items.get(want);
        Bitmap cached = heroCache.get(it.id);
        if (cached != null) { crossfade(cached, want); prefetchAround(want); return; }
        worker.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
            Bitmap b = heroBitmap(it.picture);
            if (b == null) return;
            b.prepareToDraw();
            main.post(() -> {
                heroCache.put(it.id, b);
                if (index == want) crossfade(b, want);
                prefetchAround(want);
            });
        });
    }

    private void prefetchAround(int at) {
        for (int d : new int[]{1, -1}) {
            int i = at + d;
            if (i < 0 || i >= items.size()) continue;
            Item it = items.get(i);
            if (heroCache.get(it.id) != null) continue;
            worker.execute(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                Bitmap b = heroBitmap(it.picture);
                if (b != null) { b.prepareToDraw(); main.post(() -> heroCache.put(it.id, b)); }
            });
        }
    }

    private void crossfade(Bitmap b, int at) {
        shownHero = at;
        if (root.getBackground() != null) {
            root.setBackground(null);
            heroBack.setBackground(null);
        }
        if (!animate || heroBack.getDrawable() == null) {
            heroBack.setImageBitmap(b);
            heroFront.setAlpha(0f);
            startDrift(heroBack);
            return;
        }
        heroFront.animate().cancel();
        heroFront.setImageBitmap(b);
        heroFront.setScaleX(heroBack.getScaleX());
        heroFront.setScaleY(heroBack.getScaleY());
        heroFront.setTranslationX(heroBack.getTranslationX());
        heroFront.setAlpha(0f);
        heroFront.animate().alpha(1f).setDuration(320).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
            heroBack.setImageBitmap(b);
            heroBack.setScaleX(heroFront.getScaleX());
            heroBack.setScaleY(heroFront.getScaleY());
            heroBack.setTranslationX(heroFront.getTranslationX());
            heroFront.setAlpha(0f);
            heroFront.setImageDrawable(null);
            startDrift(heroBack);
        }).start();
        startDrift(heroFront);
    }

    /** Ken Burns: an 18-second push in and back. Only view properties change, so the picture is never redrawn. */
    private void startDrift(View v) {
        if (!animate) return;
        if (drift != null && drift.getTarget() == v && drift.isStarted()) return;
        if (drift != null) drift.cancel();
        drift = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, v.getScaleX() < 1.001f ? 1f : v.getScaleX(), 1.07f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, v.getScaleY() < 1.001f ? 1f : v.getScaleY(), 1.07f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, v.getTranslationX(), -1.5f * u));
        drift.setDuration(18000);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        drift.start();
        if (navigating) drift.pause();
    }

    /** The video's picture with the left and bottom shade painted in, so the screen has a single full-screen layer. */
    private static Bitmap heroBitmap(File f) {
        Bitmap b;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            b = BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setDither(true);
        p.setShader(new LinearGradient(0, 0, w, 0, new int[]{0xEB04060C, 0x7304060C, 0x0004060C}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, h, 0, 0, new int[]{0xFA04060C, 0xCC04060C, 0x3304060C, 0x0004060C}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    private static Bitmap decode(File f, int sample) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    // ---- wall bitmaps ------------------------------------------------------------------------------------

    /** Card with rounded corners and its floor reflection baked into one bitmap per video, newest first. */
    private void prepareSlabs(List<Item> list) {
        final int cw = Math.round(15f * u * 1.3f);    // drawn at up to 1.28x: sharp when focused
        final int ch = Math.round(cw * 9f / 16f);
        final float radius = 0.6f * u * 1.3f;
        final int gap = Math.round(0.4f * u * 1.3f);
        final int refl = Math.round(ch * 0.4f);
        for (Item it : list) {
            worker.execute(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                Bitmap src = decode(it.picture, 2);
                if (src == null) return;
                Bitmap out = Bitmap.createBitmap(cw, ch + gap + refl, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                float s = Math.max(cw / (float) src.getWidth(), ch / (float) src.getHeight());
                m.setScale(s, s);
                m.postTranslate((cw - src.getWidth() * s) / 2f, (ch - src.getHeight() * s) / 2f);
                shader.setLocalMatrix(m);
                p.setShader(shader);
                c.drawRoundRect(new RectF(0, 0, cw, ch), radius, radius, p);
                // Reflection: the card upside down, fading out.
                c.save();
                c.translate(0, ch * 2f + gap);
                c.scale(1f, -1f);
                c.clipRect(0, ch - refl, cw, ch);
                c.drawRoundRect(new RectF(0, 0, cw, ch), radius, radius, p);
                c.restore();
                Paint fade = new Paint();
                fade.setShader(new LinearGradient(0, ch + gap, 0, ch + gap + refl, 0x40000000, 0x00000000, Shader.TileMode.CLAMP));
                fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
                c.drawRect(0, ch + gap, cw, ch + gap + refl, fade);
                src.recycle();
                out.prepareToDraw();
                main.post(() -> { it.slab = out; wall.invalidate(); });
            });
        }
    }

    // ---- views -------------------------------------------------------------------------------------------

    private ImageView heroView() {
        ImageView v = new ImageView(ctx);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(Math.max(1, Math.round(0.06f * u)), stroke);
        d.setCornerRadius(10 * u);
        return d;
    }

    private TextView text(float sizeU, String font, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeU * u);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        int id = ctx.getResources().getIdentifier(font, "font", ctx.getPackageName());
        if (id != 0) t.setTypeface(ResourcesCompat.getFont(ctx, id));
        return t;
    }

    private LinearLayout.LayoutParams gapTop(float gapU) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(gapU * u);
        return lp;
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---- the 3D wall -------------------------------------------------------------------------------------

    private final class WallView extends View {
        private final FloatValueHolder pos = new FloatValueHolder(0f);
        private final SpringAnimation spring = new SpringAnimation(pos);
        private final FloatValueHolder lift = new FloatValueHolder(0f);
        private final SpringAnimation liftSpring = new SpringAnimation(lift);
        private final Camera camera = new Camera();
        private final Matrix matrix = new Matrix();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final LightingColorFilter[] dim = new LightingColorFilter[33];
        private final RectF card = new RectF();
        private final Integer[] order = new Integer[64];

        WallView(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(false);
            setDefaultFocusHighlightEnabled(false);
            setClipToOutline(false);
            spring.setSpring(new SpringForce().setStiffness(animate ? 420f : 10000f).setDampingRatio(animate ? 0.86f : 1f));
            spring.setMinimumVisibleChange(0.002f);
            spring.addUpdateListener((a, v, vel) -> invalidate());
            liftSpring.setSpring(new SpringForce().setStiffness(animate ? 520f : 10000f).setDampingRatio(0.72f));
            liftSpring.setMinimumVisibleChange(0.002f);
            liftSpring.addUpdateListener((a, v, vel) -> invalidate());
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            glow.setColor(0xFF000000);
            placeholder.setColor(0x26FFFFFF);
            for (int i = 0; i < dim.length; i++) {
                int g = Math.round(255 * (1f - i * 0.025f));
                dim[i] = new LightingColorFilter(Color.rgb(g, g, g), 0);
            }
            camera.setLocation(0, 0, -16);
        }

        void jumpTo(int i) {
            spring.cancel();
            pos.setValue(i);
            invalidate();
        }

        void moveTo(int i) {
            if (!animate) { jumpTo(i); return; }
            spring.animateToFinalPosition(i);
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            if (gained) pillHadFocus = false;
            if (animate) liftSpring.animateToFinalPosition(gained ? 1f : 0f);
            else { lift.setValue(gained ? 1f : 0f); invalidate(); }
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: select(index - 1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: select(index + 1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!pills.isEmpty()) pills.get(Math.min(lastPill, pills.size() - 1)).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    View top = host.topButton();
                    if (top != null) top.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    return true;
            }
            return super.onKeyDown(keyCode, e);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent e) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (!e.isCanceled()) play();
                return true;
            }
            return super.onKeyUp(keyCode, e);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int n = items.size();
            if (n == 0) return;
            float p = pos.getValue();
            float lifted = lift.getValue();
            float cw = 15f * u;
            float ch = cw * 9f / 16f;
            float cx = getWidth() / 2f;
            float cy = ch * 0.62f;
            int count = 0;
            for (int i = 0; i < n && count < order.length; i++) if (Math.abs(i - p) < 3.9f) order[count++] = i;
            // Far cards first, the focused one last.
            java.util.Arrays.sort(order, 0, count, (a, b) -> Float.compare(Math.abs(b - p), Math.abs(a - p)));
            ring.setStrokeWidth(0.18f * u);
            for (int k = 0; k < count; k++) {
                int i = order[k];
                Item it = items.get(i);
                float o = i - p;
                float ab = Math.abs(o);
                float sign = Math.signum(o);
                float dx = ab < 1f ? o * 14.5f * u : sign * (14.5f * u + (ab - 1f) * 9.5f * u);
                float near = Math.max(0f, 1f - ab);
                float scale = 1f + near * (0.12f + 0.16f * lifted);
                float rot = Math.max(-1f, Math.min(1f, o)) * 40f;
                float alpha = Math.max(0f, 1f - ab * 0.18f) * Math.min(1f, (3.9f - ab) * 1.5f);
                int dimLevel = Math.min(dim.length - 1, Math.round(ab * 8));

                matrix.reset();
                camera.save();
                camera.translate(0, 0, ab * 7f * u);
                camera.rotateY(-rot);
                camera.getMatrix(matrix);
                camera.restore();
                matrix.preTranslate(-cw / 2f, -ch / 2f);
                matrix.postScale(scale, scale);
                matrix.postTranslate(cx + dx, cy);

                canvas.save();
                canvas.concat(matrix);
                card.set(0, 0, cw, ch);
                int a = Math.round(255 * alpha);
                if (it.slab == null) {
                    placeholder.setAlpha(Math.round(0x26 * alpha));
                    canvas.drawRoundRect(card, 0.6f * u, 0.6f * u, placeholder);
                } else {
                    paint.setAlpha(a);
                    paint.setColorFilter(dimLevel == 0 ? null : dim[dimLevel]);
                    float k2 = cw / it.slab.getWidth();
                    canvas.save();
                    canvas.scale(k2, k2);
                    canvas.drawBitmap(it.slab, 0, 0, paint);
                    canvas.restore();
                }
                if (ab < 0.5f && lifted > 0.01f) {
                    ring.setAlpha(Math.round(255 * lifted * (1f - ab * 2f)));
                    float inset = 0.09f * u;
                    card.inset(-inset, -inset);
                    canvas.drawRoundRect(card, 0.7f * u, 0.7f * u, ring);
                }
                canvas.restore();
            }
        }
    }
}
