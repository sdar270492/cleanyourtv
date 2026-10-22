package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the theme's wallpapers/ folder with the HD thumbnails of the latest videos of a few YouTube channels
 * (config.xml youtube_channels, or one handle or UC… channel ID per line in youtube_channels.txt next to config.json), with
 * "Title · Channel" in captions.txt. YouTube's RSS feeds answer 404, so the IDs come from the start of each
 * channel's /videos page: the read stops as soon as enough IDs are found. Files keep fixed names (yt-00.jpg…),
 * so a running WallpaperPlayer picks up new pictures on its next rotation. Any failure keeps the old pictures.
 */
final class YouTubeWallpapers {

    private static final long INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final int PER_CHANNEL = 1;
    private static final int MAX_PICTURES = 20;
    /** Video ID shown in each yt-NN.jpg, one per line: a picture whose video did not change is not downloaded again. */
    private static final String IDS_FILE = "yt-ids.txt";
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    private static final Pattern VIDEO_ID = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static boolean running;
    /** A failed attempt, kept in memory only: retried 15 minutes later or when the process restarts. */
    private static long failedAt;

    private YouTubeWallpapers() {
    }

    /** Fetches in the background when the last fetch is older than INTERVAL_MS; done(collectionChanged) runs on the main thread. */
    static void refreshIfDue(Context context, Done done) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("youtube_wallpapers", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (running || now - failedAt < 15 * 60 * 1000L) return;
        File dir = Config.dir(app);
        List<String> channels = channels(app, dir);
        if (dir == null || channels.isEmpty()) return;
        // A changed channel list fetches right away instead of waiting out the interval.
        String key = String.join(",", channels);
        if (key.equals(prefs.getString("channels", "")) && now - prefs.getLong("fetched", 0) < INTERVAL_MS) return;
        running = true;
        worker.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            boolean ok = false;
            boolean collectionChanged = false;
            try {
                File folder = new File(dir, ThemeFiles.WALLPAPERS);
                List<File> before = WallpaperRotation.images(folder);
                ok = fetch(channels, folder);
                // Same filenames: the running player shows the new pictures itself. Different ones need a new player.
                collectionChanged = !before.equals(WallpaperRotation.images(folder));
            } catch (Exception e) {
                // Offline or YouTube changed its page: keep the pictures already there and try again later.
                Log.w("YouTubeWallpapers", "fetch failed", e);
            }
            boolean changed = ok;
            boolean first = ok && collectionChanged;
            if (ok) prefs.edit().putLong("fetched", now).putString("channels", key).apply();
            else failedAt = now;
            Log.i("YouTubeWallpapers", "fetch " + (ok ? "ok" : "failed"));
            new Handler(Looper.getMainLooper()).post(() -> {
                running = false;
                if (changed) done.updated(first);
            });
        });
    }

    interface Done {
        void updated(boolean collectionChanged);
    }

    private static List<String> channels(Context context, File dir) {
        List<String> list = new ArrayList<>();
        File custom = dir == null ? null : new File(dir, "youtube_channels.txt");
        try {
            if (custom != null && custom.isFile()) {
                for (String line : Files.readAllLines(custom.toPath(), StandardCharsets.UTF_8)) {
                    // A handle (@name) or a channel ID (UC…), optionally followed by a comment: "UC… # Name".
                    String handle = line.trim().split("\\s+")[0].replaceFirst("^@", "");
                    if (!handle.isEmpty() && !handle.startsWith("#")) list.add(handle);
                }
            }
        } catch (IOException ignored) {
        }
        if (list.isEmpty()) {
            list.addAll(Arrays.asList(context.getResources().getStringArray(R.array.youtube_channels)));
        }
        return list;
    }

    private static boolean fetch(List<String> channels, File folder) throws IOException {
        List<List<String>> perChannel = new ArrayList<>();
        for (String handle : channels) {
            try {
                List<String> latest = latestIds(handle);
                Log.i("YouTubeWallpapers", handle + ": " + latest.size() + " videos");
                perChannel.add(latest);
            } catch (IOException e) {
                Log.w("YouTubeWallpapers", handle + ": " + e);
                perChannel.add(new ArrayList<>());
            }
        }
        // One video from each channel in turn, so every channel shows up before any repeats.
        List<String> ids = new ArrayList<>();
        for (int round = 0; round < PER_CHANNEL; round++) {
            for (List<String> list : perChannel) if (round < list.size()) ids.add(list.get(round));
        }
        if (ids.isEmpty()) return false;
        if (!folder.isDirectory() && !folder.mkdirs()) return false;
        StringBuilder captions = new StringBuilder();
        StringBuilder savedIds = new StringBuilder();
        List<String> oldIds = new ArrayList<>();
        File idsFile = new File(folder, IDS_FILE);
        if (idsFile.isFile()) oldIds.addAll(Files.readAllLines(idsFile.toPath(), StandardCharsets.UTF_8));
        java.util.Map<String, String> oldCaptions = WallpaperCaptions.read(folder);
        int saved = 0;
        for (String id : ids) {
            if (saved == MAX_PICTURES) break;
            String name = String.format(Locale.ROOT, "yt-%02d.jpg", saved);
            File target = new File(folder, name);
            boolean same = saved < oldIds.size() && id.equals(oldIds.get(saved)) && target.isFile();
            if (!same && !download("https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg", target)) continue;
            String text = same && oldCaptions.containsKey(name) ? oldCaptions.get(name) : caption(id);
            captions.append(name).append('\t').append(text).append('\n');
            savedIds.append(id).append('\n');
            saved++;
        }
        if (saved == 0) return false;
        // Only YouTube pictures from here on: drop the theme's own and any yt-NN left from a longer list.
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                boolean current = n.startsWith("yt-") && n.length() == 9 && Integer.parseInt(n.substring(3, 5)) < saved;
                if (!current && !n.equals(WallpaperCaptions.FILE_NAME) && !n.equals(IDS_FILE)) f.delete();
            }
        }
        Files.write(idsFile.toPath(), savedIds.toString().getBytes(StandardCharsets.UTF_8));
        File part = new File(folder, "captions.part");
        Files.write(part.toPath(), captions.toString().getBytes(StandardCharsets.UTF_8));
        return part.renameTo(new File(folder, WallpaperCaptions.FILE_NAME));
    }

    /** The newest video IDs on the channel's /videos tab; stops reading the page once it has them. */
    private static List<String> latestIds(String handle) throws IOException {
        String path = CHANNEL_ID.matcher(handle).matches() ? "channel/" + handle : "@" + URLEncoder.encode(handle, "UTF-8");
        HttpURLConnection c = open("https://www.youtube.com/" + path + "/videos");
        Set<String> ids = new LinkedHashSet<>();
        try (Reader in = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[16384];
            StringBuilder window = new StringBuilder();
            int n;
            while (ids.size() < PER_CHANNEL && (n = in.read(buf)) > 0) {
                window.append(buf, 0, n);
                Matcher m = VIDEO_ID.matcher(window);
                int end = 0;
                while (m.find() && ids.size() < PER_CHANNEL) {
                    ids.add(m.group(1));
                    end = m.end();
                }
                // Keep a tail so an ID split across two reads is still found.
                window.delete(0, Math.max(end, window.length() - 40));
            }
        } finally {
            c.disconnect();
        }
        return new ArrayList<>(ids);
    }

    private static String caption(String id) {
        try {
            HttpURLConnection c = open("https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + id, "UTF-8"));
            try (InputStream in = c.getInputStream()) {
                JSONObject json = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                String title = json.optString("title").replaceAll("\\s+", " ").trim();
                String author = json.optString("author_name").trim();
                return author.isEmpty() ? title : title + " · " + author;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean download(String url, File target) {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try {
            HttpURLConnection c = open(url);
            try {
                if (c.getResponseCode() != 200) return false;   // no HD thumbnail: skip, a blurry one looks worse
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            } finally {
                c.disconnect();
            }
            return part.renameTo(target);
        } catch (IOException e) {
            part.delete();
            return false;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)");
        c.setRequestProperty("Accept-Language", "es-419,es;q=0.9");
        c.setRequestProperty("Cookie", "CONSENT=YES+1");
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
