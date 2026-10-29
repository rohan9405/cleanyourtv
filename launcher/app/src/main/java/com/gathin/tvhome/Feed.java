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
 * feed_package (config.xml or a theme): what Home shows besides the apps, from an add-on such as Feed
 * (addons/tv-feed): the header's new releases, Continue watching, and each app's top 10. Like {@link GameDay}, the
 * launcher only reads what the add-on publishes, art included, and stays offline: posters are opened through the
 * add-on's own provider, never fetched here.
 */
final class Feed {
    static final String META = "com.gathin.tvhome.feed";
    static final String HERO = "hero";
    static final String CONTINUE = "continue";
    static final String APP = "app";
    /** Each service's titles by genre: only for the genre rows. */
    static final String POOL = "pool";
    /** Never in the header: its rows are videos, not releases. */
    static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String[] COLUMNS = {"row", "row_order", "title", "subtitle", "rating", "progress",
            "poster", "backdrop", "intent", "package", "section", "tab", "overview", "kind", "year", "genre", "plain_art"};

    /** One tile. */
    static final class Entry {
        final String section, tab, title, subtitle, rating, poster, backdrop, intent, pkg, overview, kind, year;
        /** "Drama, Thriller", empty when unknown. */
        final String genre;
        /** The art has no title on it (TMDB backdrops), so Home writes the title over it. */
        boolean plainArt;
        final int progress, order;

        Entry(String section, String tab, int order, String title, String subtitle, String rating, int progress,
              String poster, String backdrop, String intent, String pkg, String overview, String kind, String year,
              String genre) {
            this.section = section == null ? APP : section;
            this.tab = tab == null ? "" : tab;
            this.order = order;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.rating = rating == null ? "" : rating;
            this.progress = progress;
            this.poster = poster == null ? "" : poster;
            this.backdrop = backdrop == null ? "" : backdrop;
            this.intent = intent == null ? "" : intent;
            this.pkg = pkg == null ? "" : pkg;
            this.overview = overview == null ? "" : overview;
            this.kind = kind == null ? "" : kind;
            this.year = year == null ? "" : year;
            this.genre = genre == null ? "" : genre;
        }

        double ratingValue() {
            try {
                return rating.isEmpty() ? 0 : Double.parseDouble(rating);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** Artwork without the title on it: Home writes the title over it. */
        boolean plainArt() {
            return plainArt;
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
    private final LruCache<String, Bitmap> art = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private boolean watching;
    private List<Entry> continueRow = Collections.emptyList();
    private Map<String, List<Entry>> tabs = Collections.emptyMap();
    private Map<String, List<Entry>> hero = Collections.emptyMap();
    private Map<String, List<Entry>> pool = Collections.emptyMap();

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
                // The add-on went away; Home keeps what it had.
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

    List<Entry> continueRow() {
        return continueRow;
    }

    /** package -> its top 10, in rank order. */
    Map<String, List<Entry>> tabs() {
        return tabs;
    }

    /** package -> its titles by genre, for the genre rows. */
    Map<String, List<Entry>> pool() {
        return pool;
    }

    /** package -> its header candidates, best first. */
    Map<String, List<Entry>> heroCandidates() {
        return hero;
    }

    void read() {
        try {
            io.execute(() -> {
                List<Entry> entries = new ArrayList<>();
                try (Cursor c = ctx.getContentResolver().query(uri, COLUMNS, null, null, null)) {
                    // By name: the add-on may answer with its columns in its own order, or with more of them.
                    while (c != null && c.moveToNext()) {
                        entries.add(new Entry(s(c, "section"), s(c, "tab"), i(c, "row_order"), s(c, "title"),
                                s(c, "subtitle"), s(c, "rating"), i(c, "progress"), s(c, "poster"), s(c, "backdrop"),
                                s(c, "intent"), s(c, "package"), s(c, "overview"), s(c, "kind"), s(c, "year"),
                                s(c, "genre")));
                        entries.get(entries.size() - 1).plainArt = i(c, "plain_art") == 1;
                    }
                } catch (RuntimeException gone) {
                    return;
                }
                List<Entry> cont = new ArrayList<>();
                Map<String, List<Entry>> byTab = group(entries, APP);
                Map<String, List<Entry>> byHero = group(entries, HERO);
                Map<String, List<Entry>> byPool = group(entries, POOL);
                for (Entry e : entries) if (CONTINUE.equals(e.section) && usable(e)) cont.add(e);
                main.post(() -> {
                    continueRow = cont;
                    tabs = byTab;
                    hero = byHero;
                    pool = byPool;
                    onChange.run();
                });
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    private static String s(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static int i(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? -1 : c.getInt(i);
    }

    /** One section's tiles by app, each list in its rank order; tiles without a title or a target are dropped. */
    static Map<String, List<Entry>> group(List<Entry> entries, String section) {
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (!section.equals(e.section) || !usable(e) || e.tab.isEmpty()) continue;
            List<Entry> l = out.get(e.tab);
            if (l == null) out.put(e.tab, l = new ArrayList<>());
            l.add(e);
        }
        for (List<Entry> l : out.values()) Collections.sort(l, (a, b) -> Integer.compare(a.order, b.order));
        return out;
    }

    private static boolean usable(Entry e) {
        return !e.title.isEmpty() && !e.intent.isEmpty();
    }

    /**
     * The header's rotation: up to {@code perApp} of the best from each drawer app (YouTube left out), taken in turn
     * so the apps alternate, then topped up to {@code total} with the best-rated of what is left from those apps.
     */
    static List<Entry> pickHero(Map<String, List<Entry>> candidates, List<String> drawer, int perApp, int total) {
        List<List<Entry>> firsts = new ArrayList<>();
        List<Entry> rest = new ArrayList<>();
        for (String pkg : drawer) {
            if (YOUTUBE.equals(pkg)) continue;
            List<Entry> l = candidates.get(pkg);
            if (l == null || l.isEmpty()) continue;
            firsts.add(l.subList(0, Math.min(perApp, l.size())));
            rest.addAll(l.subList(Math.min(perApp, l.size()), l.size()));
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; out.size() < total; i++) {
            boolean any = false;
            for (List<Entry> l : firsts) {
                if (i < l.size() && out.size() < total) {
                    out.add(l.get(i));
                    any = true;
                }
            }
            if (!any) break;
        }
        Collections.sort(rest, (a, b) -> Double.compare(b.ratingValue(), a.ratingValue()));
        for (Entry e : rest) {
            if (out.size() >= total) break;
            out.add(e);
        }
        return out;
    }

    /**
     * The genre rows, from the drawer apps' top 10s and their genre pools (YouTube's videos have no genre), each title
     * once. A genre needs {@code minTitles} titles to get a tab; the tabs go most titles first, up to
     * {@code maxGenres}. Each row takes the apps in turn, in drawer order, each app's best-rated first, so one app with
     * many titles cannot fill a row on its own.
     */
    static Map<String, List<Entry>> genreRows(Map<String, List<Entry>> tabs, Map<String, List<Entry>> pool,
                                              List<String> drawer, int perGenre, int maxGenres, int minTitles) {
        // genre -> app -> titles
        Map<String, Map<String, List<Entry>>> byGenre = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String pkg : drawer) {
            List<Entry> all = new ArrayList<>();
            if (tabs.get(pkg) != null) all.addAll(tabs.get(pkg));
            if (pool.get(pkg) != null) all.addAll(pool.get(pkg));
            for (Entry e : all) {
                if (e.genre.isEmpty() || !seen.add(e.title.toLowerCase(java.util.Locale.ROOT))) continue;
                java.util.Set<String> named = new java.util.HashSet<>();
                for (String g : e.genre.split(",")) {
                    String name = genreName(g);
                    if (name.isEmpty() || !named.add(name)) continue;
                    Map<String, List<Entry>> apps = byGenre.get(name);
                    if (apps == null) byGenre.put(name, apps = new LinkedHashMap<>());
                    List<Entry> list = apps.get(pkg);
                    if (list == null) apps.put(pkg, list = new ArrayList<>());
                    list.add(e);
                    counts.merge(name, 1, Integer::sum);
                }
            }
        }
        List<String> names = new ArrayList<>(byGenre.keySet());
        names.removeIf(n -> counts.get(n) < minTitles);
        Collections.sort(names, (a, b) -> Integer.compare(counts.get(b), counts.get(a)));
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        for (String name : names) {
            if (out.size() >= maxGenres) break;
            List<List<Entry>> perApp = new ArrayList<>();
            for (List<Entry> l : byGenre.get(name).values()) {
                List<Entry> sorted = new ArrayList<>(l);
                Collections.sort(sorted, (a, b) -> Double.compare(b.ratingValue(), a.ratingValue()));
                perApp.add(sorted);
            }
            List<Entry> row = new ArrayList<>();
            for (int i = 0; row.size() < perGenre; i++) {
                boolean any = false;
                for (List<Entry> l : perApp) {
                    if (i < l.size() && row.size() < perGenre) {
                        row.add(l.get(i));
                        any = true;
                    }
                }
                if (!any) break;
            }
            out.put(name, row);
        }
        return out;
    }

    /** One name per genre: TMDB's series genres pair two ("Action & Adventure"); OMDb names them singly ("Action"). */
    static String genreName(String raw) {
        String g = raw.trim();
        switch (g) {
            case "Action & Adventure":
                return "Action";
            case "Sci-Fi & Fantasy":
            case "Science Fiction":
                return "Sci-Fi";
            case "War & Politics":
                return "War";
            default:
                return g;
        }
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
            if (i.getPackage() == null && i.getComponent() == null && !e.pkg.isEmpty()) i.setPackage(e.pkg);
            String target = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
            if (target != null && !target.equals(ctx.getPackageName())) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Fall through to the app's start screen.
        }
        if (e.pkg.isEmpty()) return false;
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
