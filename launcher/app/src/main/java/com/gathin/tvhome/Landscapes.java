package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Paisajes" screensaver (fork, with video_wall): real landscape photos from Wikimedia Commons' featured pictures
 * (free licences, no account), 24 at a time, refreshed every 3 days into landscapes/ next to config.json with
 * "Place · Author" in captions.txt. Scene shows them full screen with a slow drift, a crossfade every 30 s and a
 * large clock; it is used by LandscapeActivity (Back twice on Home, or Home left idle) and LandscapeDream (the
 * system screensaver). Any failure keeps the photos already there.
 */
final class Landscapes {

    static final String FOLDER = "landscapes";
    private static final long INTERVAL_MS = 3 * 24 * 60 * 60 * 1000L;
    private static final int WANTED = 24;
    private static final String API = "https://commons.wikimedia.org/w/api.php";
    private static final String AGENT = "tvhome/1.0 (https://github.com/sdar270492/cleanyourtv)";
    /** Category and how many requests to make from it: mostly landscapes, some mountains, some Peru. */
    private static final String[][] SOURCES = {
            {"Featured_pictures_of_landscapes", "5"},
            {"Featured_pictures_of_mountains", "2"},
            {"Featured_pictures_of_Peru", "2"},
    };
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static boolean running;
    private static long failedAt;

    private Landscapes() {
    }

    static File folder(Context ctx) {
        File dir = Config.dir(ctx);
        return dir == null ? null : new File(dir, FOLDER);
    }

    /** Fetches in the background when the photos are older than INTERVAL_MS (or missing). */
    static void refreshIfDue(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("landscapes", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        File folder = folder(app);
        if (running || folder == null || now - failedAt < 30 * 60 * 1000L) return;
        if (now - prefs.getLong("fetched", 0) < INTERVAL_MS && !photos(folder).isEmpty()) return;
        running = true;
        worker.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            boolean ok = false;
            try {
                ok = fetch(folder);
            } catch (Exception e) {
                Log.w("Landscapes", "fetch failed", e);
            }
            if (ok) prefs.edit().putLong("fetched", now).apply();
            else failedAt = now;
            Log.i("Landscapes", "fetch " + (ok ? "ok" : "failed"));
            running = false;
        });
    }

    static List<File> photos(File folder) {
        List<File> list = new ArrayList<>();
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return list;
        for (File f : files) if (f.getName().startsWith("ls-") && f.getName().endsWith(".jpg")) list.add(f);
        Collections.sort(list);
        return list;
    }

    private static boolean fetch(File folder) throws Exception {
        Random random = new Random();
        Map<String, String[]> picked = new LinkedHashMap<>();   // thumb URL -> {name, author}
        for (String[] source : SOURCES) {
            int requests = Integer.parseInt(source[1]);
            for (int r = 0; r < requests && picked.size() < WANTED * 2; r++) {
                String prefix = String.valueOf((char) ('A' + random.nextInt(26)));
                String url = API + "?action=query&format=json&formatversion=2&generator=categorymembers"
                        + "&gcmtitle=" + URLEncoder.encode("Category:" + source[0], "UTF-8")
                        + "&gcmtype=file&gcmlimit=20&gcmstartsortkeyprefix=" + prefix
                        + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiextmetadatafilter=Artist%7CObjectName&iiurlwidth=1920";
                JSONObject json = new JSONObject(new String(get(url), StandardCharsets.UTF_8));
                JSONObject query = json.optJSONObject("query");
                JSONArray pages = query == null ? null : query.optJSONArray("pages");
                if (pages == null) continue;
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < pages.length(); i++) order.add(i);
                Collections.shuffle(order, random);
                int fromThisRequest = 0;
                for (int i : order) {
                    if (fromThisRequest == 3) break;   // a sort-key prefix often lands on one photographer's series
                    JSONObject page = pages.getJSONObject(i);
                    JSONArray infos = page.optJSONArray("imageinfo");
                    if (infos == null || infos.length() == 0) continue;
                    JSONObject info = infos.getJSONObject(0);
                    int w = info.optInt("width"), h = info.optInt("height");
                    String thumb = info.optString("thumburl");
                    // Wide but not a panorama, and big enough for a 1080p screen.
                    if (w < 1920 || h == 0 || w / (float) h < 1.3f || w / (float) h > 1.95f || thumb.isEmpty()) continue;
                    JSONObject meta = info.optJSONObject("extmetadata");
                    String name = clean(field(meta, "ObjectName"), page.optString("title"));
                    String author = field(meta, "Artist").replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
                    if (author.length() > 40) author = author.substring(0, 40).trim() + "…";
                    picked.put(thumb, new String[]{name, author});
                    fromThisRequest++;
                }
            }
        }
        if (picked.isEmpty()) return false;
        List<String> urls = new ArrayList<>(picked.keySet());
        Collections.shuffle(urls, random);
        if (!folder.isDirectory() && !folder.mkdirs()) return false;
        StringBuilder captions = new StringBuilder();
        int saved = 0;
        for (String url : urls) {
            if (saved == WANTED) break;
            String fileName = String.format(Locale.ROOT, "ls-%02d.jpg", saved);
            File part = new File(folder, fileName + ".part");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(get(url));
            } catch (IOException e) {
                part.delete();
                continue;
            }
            if (!part.renameTo(new File(folder, fileName))) continue;
            String[] c = picked.get(url);
            captions.append(fileName).append('\t').append(c[1].isEmpty() ? c[0] : c[0] + " · " + c[1]).append('\n');
            saved++;
        }
        if (saved == 0) return false;
        for (File f : photos(folder)) {
            int n = Integer.parseInt(f.getName().substring(3, 5));
            if (n >= saved) f.delete();
        }
        File part = new File(folder, "captions.part");
        Files.write(part.toPath(), captions.toString().getBytes(StandardCharsets.UTF_8));
        return part.renameTo(new File(folder, WallpaperCaptions.FILE_NAME));
    }

    private static String field(JSONObject meta, String key) {
        JSONObject f = meta == null ? null : meta.optJSONObject(key);
        return f == null ? "" : f.optString("value", "").trim();
    }

    /** "Mallnitz Seebachtal 01" -> "Mallnitz Seebachtal"; falls back to the file name. */
    private static String clean(String name, String title) {
        String s = name.replaceAll("<[^>]+>", "").trim();
        if (s.isEmpty()) s = title.replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]{3,4}$", "").replace('_', ' ');
        s = s.replaceAll("\\s*-\\s*edit\\S*", "")
                .replaceAll("[\\s,_-]*(\\d{4}-\\d{2}-\\d{2}|\\d{8}|DSC\\S*|IMG\\S*|\\(\\d+\\)|\\d{1,3})(\\s|$)", " ")
                .replaceAll("\\s+", " ").trim();
        return s.length() > 60 ? s.substring(0, 60).trim() + "…" : s;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", AGENT);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** {"02:39", "pm"} (or {"14:39", ""} on a 24-hour TV): two-digit hours, a plain lower-case marker. */
    static String[] clockParts(Context ctx, Date now) {
        if (android.text.format.DateFormat.is24HourFormat(ctx)) {
            return new String[]{new SimpleDateFormat("HH:mm", Locale.ROOT).format(now), ""};
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTime(now);
        return new String[]{new SimpleDateFormat("hh:mm", Locale.ROOT).format(now),
                c.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM ? "am" : "pm"};
    }

    // ---- the screen --------------------------------------------------------------------------------------

    /**
     * Full-screen photos with a drift, a crossfade every 30 s and the clock bottom left. One full-screen layer at
     * rest: the shade that keeps the clock readable is painted into each photo when it is decoded.
     */
    static final class Scene {
        private static final long SHOW_MS = 30_000, FADE_MS = 1_600;

        final FrameLayout root;
        private final Context ctx;
        private final ImageView back, front;
        private final TextView clock, ampm, date, caption, empty;
        private final float u;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService decoder = Executors.newSingleThreadExecutor();
        private final List<File> photos = new ArrayList<>();
        private Map<String, String> captions = Collections.emptyMap();
        private int next;
        private boolean flip;
        private ObjectAnimator drift;
        private boolean started;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                updateClock();
                long now = System.currentTimeMillis();
                main.postDelayed(this, 60_000 - now % 60_000 + 50);
            }
        };
        private final Runnable advance = this::showNext;

        Scene(Context ctx) {
            this.ctx = ctx;
            u = ctx.getResources().getDisplayMetrics().widthPixels / 100f;
            root = new FrameLayout(ctx);
            root.setBackgroundColor(Color.BLACK);
            back = image();
            front = image();
            front.setAlpha(0f);
            root.addView(back, full());
            root.addView(front, full());

            LinearLayout block = new LinearLayout(ctx);
            block.setOrientation(LinearLayout.VERTICAL);
            clock = text(7.5f, "outfit_extralight", Color.WHITE);
            clock.setSingleLine(true);
            ampm = text(2.2f, "outfit_light", 0xDDFFFFFF);
            ampm.setSingleLine(true);
            // "02:39 pm": the am/pm marker sits on the digits' baseline.
            LinearLayout time = new LinearLayout(ctx);
            time.setOrientation(LinearLayout.HORIZONTAL);
            time.setBaselineAligned(true);
            time.addView(clock);
            LinearLayout.LayoutParams ampmLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ampmLp.leftMargin = Math.round(0.8f * u);
            time.addView(ampm, ampmLp);
            date = text(1.5f, "outfit_light", 0xEEFFFFFF);
            caption = text(0.95f, "outfit_regular", 0xB3FFFFFF);
            caption.setLetterSpacing(0.04f);
            // WRAP_CONTENT on purpose: a vertical LinearLayout's default child is MATCH_PARENT, which re-measured this
            // row to the date's width when the minute changed and cut the time to "02:4".
            block.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            block.addView(date, gapTop(0.4f));
            block.addView(caption, gapTop(1.4f));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            lp.leftMargin = Math.round(5 * u);
            lp.bottomMargin = Math.round(4.5f * u);
            root.addView(block, lp);

            empty = text(1.3f, "outfit_light", 0xCCFFFFFF);
            empty.setText("Preparando paisajes…");
            empty.setVisibility(View.GONE);
            root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }

        void start() {
            if (started) return;
            started = true;
            File folder = folder(ctx);
            photos.clear();
            photos.addAll(photos(folder));
            Collections.shuffle(photos);
            captions = WallpaperCaptions.read(folder);
            empty.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
            if (photos.isEmpty()) {
                // First run: the photos are still downloading. Look again every few seconds until they are there.
                refreshIfDue(ctx);
                main.postDelayed(() -> { stop(); start(); }, 8000);
            }
            tick.run();
            showNext();
        }

        void stop() {
            started = false;
            main.removeCallbacksAndMessages(null);
            if (drift != null) drift.cancel();
        }

        void release() {
            stop();
            decoder.shutdownNow();
        }

        private void showNext() {
            if (!started || photos.isEmpty()) return;
            File f = photos.get(next % photos.size());
            next++;
            decoder.execute(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                Bitmap b = shaded(f);
                if (b == null) { main.post(this::showNext); return; }
                b.prepareToDraw();
                main.post(() -> {
                    if (!started) return;
                    String text = captions.get(f.getName());
                    crossfade(b, text == null ? "" : text);
                    main.postDelayed(advance, SHOW_MS);
                });
            });
        }

        private void crossfade(Bitmap b, String text) {
            flip = !flip;
            root.setBackground(null);   // the photo covers the screen: no black fill under it
            front.animate().cancel();
            front.setImageBitmap(b);
            front.setAlpha(0f);
            front.setScaleX(1f);
            front.setScaleY(1f);
            front.setTranslationX(0f);
            startDrift(front);
            front.animate().alpha(1f).setDuration(back.getDrawable() == null ? 600 : FADE_MS)
                    .setInterpolator(new AccelerateDecelerateInterpolator()).withEndAction(() -> {
                        // The picture moves to the back view with its drift, so only one layer is drawn at rest.
                        Bitmap old = back.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                                ? ((android.graphics.drawable.BitmapDrawable) back.getDrawable()).getBitmap() : null;
                        back.setImageBitmap(b);
                        back.setScaleX(front.getScaleX());
                        back.setScaleY(front.getScaleY());
                        back.setTranslationX(front.getTranslationX());
                        float sx = front.getScaleX();
                        if (drift != null) drift.cancel();
                        front.setAlpha(0f);
                        front.setImageDrawable(null);
                        continueDrift(back, sx);
                        if (old != null && old != b) old.recycle();
                    }).start();
            caption.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                caption.setText(text);
                caption.animate().alpha(1f).setDuration(800).start();
            }).start();
        }

        private void startDrift(View v) {
            if (drift != null) drift.cancel();
            float dx = (flip ? -1.6f : 1.6f) * u;
            drift = ObjectAnimator.ofPropertyValuesHolder(v,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.09f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.09f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dx));
            drift.setDuration(SHOW_MS + FADE_MS * 2);
            drift.setInterpolator(new LinearInterpolator());
            drift.start();
        }

        /** Same drift, carried over to the back view from where the front one left it. */
        private void continueDrift(View v, float fromScale) {
            float dx = (flip ? -1.6f : 1.6f) * u;
            float done = Math.max(0f, Math.min(1f, (fromScale - 1f) / 0.09f));
            drift = ObjectAnimator.ofPropertyValuesHolder(v,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, fromScale, 1.09f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, fromScale, 1.09f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, v.getTranslationX(), dx));
            drift.setDuration(Math.max(1000, (long) ((SHOW_MS + FADE_MS * 2) * (1f - done))));
            drift.setInterpolator(new LinearInterpolator());
            drift.start();
        }

        private void updateClock() {
            Date now = new Date();
            String[] t = clockParts(ctx, now);
            clock.setText(t[0]);
            ampm.setText(t[1]);
            ampm.setVisibility(t[1].isEmpty() ? View.GONE : View.VISIBLE);
            String d = new SimpleDateFormat("EEEE, d 'de' MMMM", Locale.getDefault()).format(now);
            date.setText(d.substring(0, 1).toUpperCase(Locale.getDefault()) + d.substring(1));
        }

        /** The photo with a shade rising from the bottom left, under the clock. */
        private static Bitmap shaded(File f) {
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
            p.setShader(new LinearGradient(0, h, 0, h * 0.4f, new int[]{0xB3000000, 0x40000000, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.4f, w, h, p);
            p.setShader(new LinearGradient(0, 0, w * 0.55f, 0, new int[]{0x59000000, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.4f, w * 0.55f, h, p);
            return b;
        }

        private ImageView image() {
            ImageView v = new ImageView(ctx);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return v;
        }

        private TextView text(float sizeU, String font, int color) {
            TextView t = new TextView(ctx);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeU * u);
            t.setTextColor(color);
            t.setIncludeFontPadding(false);
            t.setShadowLayer(0.6f * u, 0, 0.1f * u, 0x66000000);
            int id = ctx.getResources().getIdentifier(font, "font", ctx.getPackageName());
            if (id != 0) t.setTypeface(ResourcesCompat.getFont(ctx, id));
            return t;
        }

        private LinearLayout.LayoutParams gapTop(float gapU) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(gapU * u);
            return lp;
        }

        private static FrameLayout.LayoutParams full() {
            return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }
}
