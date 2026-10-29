package io.github.rohan9405.feed;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds Home's three sections and stores them:
 * <ul>
 *   <li><b>continue</b>: Continue watching (Watch Next) from the TV provider, browsers left out;</li>
 *   <li><b>app</b>: each app's top 10, from the rows it publishes, or from TMDB's most popular on that service in
 *       India when it publishes none (Netflix, SonyLIV);</li>
 *   <li><b>hero</b>: for each streaming service, the titles new in the last 10 days (TMDB), best IMDb rating first.
 *       Home picks 5 per drawer app from these and tops up to 20.</li>
 * </ul>
 * Runs only when Home asks and the last refresh is stale, or when the Feed screen says Refresh: no background service.
 */
final class Refresher {
    /** Not in the SDK as a constant: TvProvider defines it (docs/watch-next.md). */
    static final String READ_TV = "android.permission.READ_TV_LISTINGS";
    static final long STALE_MS = 20L * 60 * 1000;
    private static final int MAX_CONTINUE = 20;
    static final int TOP = 10;
    /** Header candidates kept per app, ranked: Home uses 5 and dips into the rest to fill 20. */
    private static final int HERO_PER_APP = 10;
    private static final int RATING_BUDGET = 200;
    /** Titles per service and genre in the genre pool. */
    private static final int POOL_PER_GENRE = 4;

    private static final ExecutorService ONE = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private Refresher() {
    }

    /** Starts a refresh unless one is running; onDone (any thread) once the new rows are stored. */
    static void kick(Context ctx, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return;
        ONE.execute(() -> {
            try {
                long started = android.os.SystemClock.elapsedRealtime();
                Store.put(app, run(app));
                android.util.Log.i("Feed", "refresh took " + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
                app.getContentResolver().notifyChange(FeedProvider.itemsUri(), null);
            } finally {
                RUNNING.set(false);
                if (onDone != null) onDone.run();
            }
        });
    }

    static boolean running() {
        return RUNNING.get();
    }

    private static Store.Snapshot run(Context ctx) {
        long now = System.currentTimeMillis();
        if (Demo.on(ctx)) return Demo.snapshot(ctx);
        if (ctx.checkSelfPermission(READ_TV) != PackageManager.PERMISSION_GRANTED) {
            return new Store.Snapshot(now, "READ_TV_LISTINGS is not granted", Collections.emptyList(), Collections.emptyMap());
        }
        ContentResolver cr = ctx.getContentResolver();
        PackageManager pm = ctx.getPackageManager();
        Map<String, int[]> probe = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        StringBuilder error = new StringBuilder();
        String continueRow = ctx.getString(R.string.continue_watching);

        // Continue watching, most recently watched first.
        List<long[]> order = new ArrayList<>();
        List<Item> cont = new ArrayList<>();
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                count(probe, pkg, 0);
                if (Providers.browser(pkg)) continue;
                int progress = Pick.progress(lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS),
                        lng(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS));
                long engaged = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                if (!Pick.keepContinue(engaged, now, progress)) continue;
                Item i = program(c, pkg);
                if (i == null) continue;
                i.section = Item.CONTINUE;
                i.tab = pkg;
                i.row = continueRow;
                i.progress = progress;
                i.subtitle = Pick.subtitle(str(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                        str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                        str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE));
                order.add(new long[] {engaged, cont.size()});
                cont.add(i);
            }
        } catch (RuntimeException e) {
            error.append("Watch Next: ").append(e.getClass().getSimpleName()).append(". ");
        }
        Collections.sort(order, (a, b) -> Long.compare(b[0], a[0]));
        for (long[] o : order) {
            if (items.size() < MAX_CONTINUE) items.add(cont.get((int) o[1]));
        }

        // Each app's own recommendations, the first ten it publishes across its channels.
        Set<Long> previewChannels = new HashSet<>();
        try (Cursor c = cr.query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!TvContract.Channels.TYPE_PREVIEW.equals(str(c, TvContract.Channels.COLUMN_TYPE))) continue;
                count(probe, str(c, TvContract.Channels.COLUMN_PACKAGE_NAME), 2);
                previewChannels.add(lng(c, TvContract.Channels._ID));
            }
        } catch (RuntimeException e) {
            error.append("Channels: ").append(e.getClass().getSimpleName()).append(". ");
        }
        Map<String, List<Item>> published = new LinkedHashMap<>();
        try (Cursor c = cr.query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = str(c, TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME);
                count(probe, pkg, 1);
                if (Providers.browser(pkg) || pkg.isEmpty()) continue;
                List<Item> list = published.get(pkg);
                if (list == null) published.put(pkg, list = new ArrayList<>());
                if (list.size() >= TOP) continue;
                Item i = program(c, pkg);
                if (i == null || has(list, i.title)) continue;
                i.section = Item.APP;
                i.tab = pkg;
                i.rowOrder = list.size() + 1;
                list.add(i);
            }
        } catch (RuntimeException e) {
            error.append("Preview: ").append(e.getClass().getSimpleName()).append(". ");
        }
        for (List<Item> l : published.values()) items.addAll(l);

        // TMDB: the header's new releases for every service installed here, and a top 10 for the ones that publish none.
        Tmdb tmdb = new Tmdb(ctx);
        if (tmdb.usable()) {
            LocalDate today = LocalDate.now();
            for (Map.Entry<String, Integer> p : Providers.TMDB.entrySet()) {
                String pkg = p.getKey();
                if (!installed(pm, pkg)) continue;
                // Premieres in the window, and series whose new season premiered in it; never a weekly episode of an old one.
                List<Tmdb.Title> fresh = tmdb.newReleases(p.getValue(), today);
                for (Tmdb.Title t : tmdb.newSeasons(p.getValue(), today)) {
                    boolean dup = false;
                    for (Tmdb.Title f : fresh) dup |= f.id == t.id && f.kind.equals(t.kind);
                    if (!dup) fresh.add(t);
                }
                for (Tmdb.Title t : fresh) items.add(fromTmdb(t, pkg, Item.HERO, 0));
                for (Tmdb.Title t : tmdb.genrePool(p.getValue(), POOL_PER_GENRE)) {
                    if (!hasTitle(items, pkg, t.name)) items.add(fromTmdb(t, pkg, Item.POOL, 0));
                }
                if (!published.containsKey(pkg)) {
                    List<Tmdb.Title> top = tmdb.popular(p.getValue());
                    for (int i = 0; i < Math.min(TOP, top.size()); i++) items.add(fromTmdb(top.get(i), pkg, Item.APP, i + 1));
                }
            }
        } else {
            error.append("No TMDB token: the header has no new releases. ");
        }

        // Ratings (the StreamRatings cache), then the header's ranking, then art.
        Omdb omdb = new Omdb(ctx, RATING_BUDGET);
        for (Item i : items) {
            if (!Pick.rateable(i.pkg)) continue;
            Omdb.Entry e = omdb.lookup(i.title, i.year);
            if (e == null) continue;
            i.rating = e.rating;
            if (i.genre.isEmpty()) i.genre = e.genre;
            if (i.kind.isEmpty()) i.kind = "series".equals(e.type) ? "tv" : "movie".equals(e.type) ? "movie" : "";
        }
        items = keepHeroTop(items);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> jobs = new ArrayList<>();
        for (Item i : items) {
            jobs.add(pool.submit(() -> {
                if (Item.HERO.equals(i.section)) i.backdrop = Images.cache(ctx, i.posterSource, true);
                else i.poster = Images.cache(ctx, i.posterSource, false);
            }));
        }
        for (Future<?> f : jobs) {
            try {
                f.get();
            } catch (Exception ignored) {
                // That tile shows its title on a plain card.
            }
        }
        pool.shutdown();
        Set<String> keep = new HashSet<>();
        for (Item i : items) {
            keep.add(i.poster);
            keep.add(i.backdrop);
        }
        Images.prune(ctx, keep);
        return new Store.Snapshot(now, error.toString().trim(), items, probe);
    }

    /** The header candidates ranked per app (best IMDb rating, then most popular), {@value #HERO_PER_APP} each. */
    static List<Item> keepHeroTop(List<Item> items) {
        Map<String, List<Item>> hero = new LinkedHashMap<>();
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (!Item.HERO.equals(i.section)) {
                out.add(i);
                continue;
            }
            List<Item> l = hero.get(i.tab);
            if (l == null) hero.put(i.tab, l = new ArrayList<>());
            l.add(i);
        }
        for (List<Item> l : hero.values()) {
            List<Item> ranked = Pick.rank(l);
            for (int i = 0; i < Math.min(HERO_PER_APP, ranked.size()); i++) {
                ranked.get(i).rowOrder = i + 1;
                out.add(ranked.get(i));
            }
        }
        return out;
    }

    private static Item fromTmdb(Tmdb.Title t, String pkg, String section, int order) {
        Item i = new Item();
        i.section = section;
        i.tab = pkg;
        i.pkg = pkg;
        i.title = t.name;
        i.year = t.year;
        i.kind = t.kind;
        i.overview = t.overview;
        i.genre = t.genres;
        i.subtitle = t.season;
        i.posterSource = t.backdrop;
        i.plainArt = true;
        i.popularity = t.popularity;
        i.rowOrder = order;
        i.intent = Providers.launchIntent(pkg);
        return i;
    }

    /** The service already has this title in Home (its own top 10, say): the pool does not need it twice. */
    private static boolean hasTitle(List<Item> items, String pkg, String title) {
        for (Item i : items) if (i.pkg.equals(pkg) && i.title.equalsIgnoreCase(title)) return true;
        return false;
    }

    private static boolean has(List<Item> list, String title) {
        for (Item i : list) if (i.title.equalsIgnoreCase(title)) return true;
        return false;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The parts every program row shares; null for a row without a title or a way to open it. */
    private static Item program(Cursor c, String pkg) {
        String title = str(c, TvContract.PreviewPrograms.COLUMN_TITLE);
        String intent = str(c, TvContract.PreviewPrograms.COLUMN_INTENT_URI);
        if (title.isEmpty() || intent.isEmpty()) return null;
        Item i = new Item();
        i.title = title;
        i.intent = intent;
        i.pkg = pkg;
        i.year = Pick.year(str(c, TvContract.PreviewPrograms.COLUMN_RELEASE_DATE));
        i.overview = str(c, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION);
        String poster = str(c, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
        i.posterSource = !poster.isEmpty() ? poster : str(c, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
        return i;
    }

    private static void count(Map<String, int[]> probe, String pkg, int column) {
        String key = pkg == null || pkg.isEmpty() ? "?" : pkg;
        int[] n = probe.get(key);
        if (n == null) probe.put(key, n = new int[3]);
        n[column]++;
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return "";
        String s = c.getString(i);
        return s == null ? "" : s.trim();
    }

    private static long lng(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return 0;
        try {
            return c.getLong(i);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
