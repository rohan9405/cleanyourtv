package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.util.LruCache;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * feed_package (config.xml or a theme): Continue watching and the apps' recommendation rows, from an add-on such as
 * Feed (addons/tv-feed). Like {@link GameDay}, the launcher only reads what the add-on publishes, art included, and
 * stays offline: posters are opened through the add-on's own provider, never fetched here.
 */
final class Feed {
    static final String META = "com.gathin.tvhome.feed";
    private static final String[] COLUMNS = {"row", "row_order", "title", "subtitle", "rating", "progress",
            "poster", "backdrop", "intent", "package", "hero"};

    /** One tile. */
    static final class Entry {
        final String row, title, subtitle, rating, poster, backdrop, intent, pkg;
        final int progress;

        Entry(String row, String title, String subtitle, String rating, int progress, String poster, String backdrop,
              String intent, String pkg) {
            this.row = row;
            this.title = title;
            this.subtitle = subtitle;
            this.rating = rating;
            this.progress = progress;
            this.poster = poster;
            this.backdrop = backdrop;
            this.intent = intent;
            this.pkg = pkg;
        }
    }

    static final class Row {
        final String title;
        final List<Entry> entries;

        Row(String title, List<Entry> entries) {
            this.title = title;
            this.entries = entries;
        }
    }

    private final Context ctx;
    private final String authority;
    private final Uri uri;
    private final Handler main;
    private final Runnable onChange;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ContentObserver observer;
    /** Decoded art, in RGB_565: a tile is about 160 KB, so this holds a screen of rows and a few backdrops. */
    private final LruCache<String, Bitmap> art = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private boolean watching;
    private List<Row> rows = Collections.emptyList();
    private List<Entry> hero = Collections.emptyList();

    private Feed(Context ctx, String authority, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.authority = authority;
        this.uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath("items").build();
        this.main = main;
        this.onChange = onChange;
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                read();
            }
        };
    }

    /** Null when the key is empty or the package names no provider of its own in a {@value #META} meta-data. */
    static Feed create(Context ctx, String pkg, Handler main, Runnable onChange) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.trim(), PackageManager.GET_META_DATA);
            String authority = info.metaData == null ? null : info.metaData.getString(META);
            if (authority == null || authority.trim().isEmpty()) return null;
            ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
            if (provider == null || !pkg.trim().equals(provider.packageName)) return null;
            return new Feed(ctx, authority.trim(), main, onChange);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    void start() {
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(uri, false, observer);
                watching = true;
            } catch (SecurityException e) {
                // The add-on went away; the rows keep what they had.
            }
        }
        read();
    }

    void stop() {
        if (watching) ctx.getContentResolver().unregisterContentObserver(observer);
        watching = false;
    }

    void shutdown() {
        stop();
        io.shutdownNow();
        art.evictAll();
    }

    List<Row> rows() {
        return rows;
    }

    List<Entry> hero() {
        return hero;
    }

    void read() {
        try {
            io.execute(() -> {
                List<Entry> entries = new ArrayList<>();
                List<Integer> orders = new ArrayList<>();
                List<Boolean> heroFlags = new ArrayList<>();
                try (Cursor c = ctx.getContentResolver().query(uri, COLUMNS, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        entries.add(new Entry(c.getString(0), c.getString(2), c.getString(3), c.getString(4), c.getInt(5),
                                c.getString(6), c.getString(7), c.getString(8), c.getString(9)));
                        orders.add(c.getInt(1));
                        heroFlags.add(c.getInt(10) == 1);
                    }
                } catch (RuntimeException gone) {
                    return;
                }
                List<Row> parsed = group(entries, orders);
                List<Entry> heroes = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) if (heroFlags.get(i)) heroes.add(entries.get(i));
                main.post(() -> {
                    rows = parsed;
                    hero = heroes;
                    onChange.run();
                });
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    /** Tiles into rows, by row order then first appearance; tiles without a title or a target are dropped. */
    static List<Row> group(List<Entry> entries, List<Integer> orders) {
        Map<String, List<Entry>> byTitle = new LinkedHashMap<>();
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.title == null || e.title.isEmpty() || e.intent == null || e.intent.isEmpty() || e.row == null) continue;
            List<Entry> list = byTitle.get(e.row);
            if (list == null) {
                byTitle.put(e.row, list = new ArrayList<>());
                order.put(e.row, orders.get(i));
            }
            list.add(e);
        }
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> e : byTitle.entrySet()) out.add(new Row(e.getKey(), e.getValue()));
        Collections.sort(out, (a, b) -> Integer.compare(order.get(a.title), order.get(b.title)));
        return out;
    }

    /** The art at {@code source}, decoded to about {@code widthPx}; onLoaded on the main thread, null when none. */
    void loadArt(String source, int widthPx, Consumer<Bitmap> onLoaded) {
        if (!ours(source)) {
            onLoaded.accept(null);
            return;
        }
        String key = source + "@" + widthPx;
        Bitmap cached = art.get(key);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }
        try {
            io.execute(() -> {
                Bitmap b = decode(Uri.parse(source), widthPx);
                main.post(() -> {
                    if (b != null) art.put(key, b);
                    onLoaded.accept(b);
                });
            });
        } catch (RejectedExecutionException shutDown) {
            onLoaded.accept(null);
        }
    }

    /** Only the add-on's own provider is opened: a row cannot point Home at another app's files. */
    boolean ours(String source) {
        if (source == null || source.isEmpty()) return false;
        Uri u = Uri.parse(source);
        return ContentResolver.SCHEME_CONTENT.equals(u.getScheme()) && authority.equals(u.getAuthority());
    }

    private Bitmap decode(Uri u, int widthPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= widthPx) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
                return BitmapFactory.decodeStream(in, null, d);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Opens a tile: the app's own intent from its row, kept to that app (no selector, never this launcher), else
     * the app's start screen.
     */
    static boolean open(Context ctx, Entry e) {
        PackageManager pm = ctx.getPackageManager();
        try {
            Intent i = Intent.parseUri(e.intent, Intent.URI_INTENT_SCHEME);
            i.setSelector(null);
            if (i.getPackage() == null && i.getComponent() == null && e.pkg != null && !e.pkg.isEmpty()) i.setPackage(e.pkg);
            String target = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
            if (target != null && !target.equals(ctx.getPackageName())) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Fall through to the app's start screen.
        }
        if (e.pkg == null || e.pkg.isEmpty()) return false;
        Intent launch = pm.getLeanbackLaunchIntentForPackage(e.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(e.pkg);
        if (launch == null) return false;
        try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
