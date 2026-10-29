package io.github.rohan9405.feed;


import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * Reads Continue watching (Watch Next) and the apps' recommendation channels from the TV provider, looks up
 * ratings, caches the art, and stores the result. Runs only when Home asks and the last refresh is stale, or when
 * the Feed screen says Refresh: no background service, no alarms.
 */
final class Refresher {
    /** Not in the SDK as a constant: TvProvider defines it (docs/watch-next.md). */
    static final String READ_TV = "android.permission.READ_TV_LISTINGS";
    static final long STALE_MS = 20L * 60 * 1000;
    private static final int MAX_CONTINUE = 20;
    private static final int MAX_CHANNELS = 6;
    private static final int MAX_PER_CHANNEL = 15;
    private static final int MAX_HERO = 8;
    private static final int RATING_BUDGET = 60;

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
                Store.put(app, run(app));
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
        if (ctx.checkSelfPermission(Refresher.READ_TV) != PackageManager.PERMISSION_GRANTED) {
            return new Store.Snapshot(now, "READ_TV_LISTINGS is not granted", Collections.emptyList(), Collections.emptyMap());
        }
        ContentResolver cr = ctx.getContentResolver();
        Map<String, int[]> probe = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        String error = "";
        String continueRow = ctx.getString(R.string.continue_watching);

        // Continue watching, most recently watched first.
        List<long[]> order = new ArrayList<>();
        List<Item> cont = new ArrayList<>();
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                count(probe, pkg, 0);
                int progress = Pick.progress(lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS),
                        lng(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS));
                long engaged = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                if (!Pick.keepContinue(engaged, now, progress)) continue;
                Item i = program(c, pkg);
                if (i == null) continue;
                i.row = continueRow;
                i.rowOrder = 0;
                i.progress = progress;
                i.subtitle = Pick.subtitle(str(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                        str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                        str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE));
                order.add(new long[] {engaged, cont.size()});
                cont.add(i);
            }
        } catch (RuntimeException e) {
            error = "Watch Next: " + e.getClass().getSimpleName();
        }
        Collections.sort(order, (a, b) -> Long.compare(b[0], a[0]));
        List<Item> sorted = new ArrayList<>();
        for (long[] o : order) {
            if (sorted.size() < MAX_CONTINUE) sorted.add(cont.get((int) o[1]));
        }
        items.addAll(sorted);

        // The apps' channels ("YouTube · Recommended for you"), in the provider's order.
        Map<Long, String> channelNames = new HashMap<>();
        Map<Long, String> channelPkgs = new HashMap<>();
        try (Cursor c = cr.query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                long id = lng(c, TvContract.Channels._ID);
                String type = str(c, TvContract.Channels.COLUMN_TYPE);
                if (!TvContract.Channels.TYPE_PREVIEW.equals(type)) continue;
                String pkg = str(c, TvContract.Channels.COLUMN_PACKAGE_NAME);
                count(probe, pkg, 2);
                channelNames.put(id, str(c, TvContract.Channels.COLUMN_DISPLAY_NAME));
                channelPkgs.put(id, pkg);
            }
        } catch (RuntimeException e) {
            error = (error.isEmpty() ? "" : error + "; ") + "Channels: " + e.getClass().getSimpleName();
        }
        Map<Long, List<Item>> byChannel = new LinkedHashMap<>();
        try (Cursor c = cr.query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = str(c, TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME);
                count(probe, pkg, 1);
                long channel = lng(c, TvContract.PreviewPrograms.COLUMN_CHANNEL_ID);
                List<Item> list = byChannel.get(channel);
                if (list == null) byChannel.put(channel, list = new ArrayList<>());
                if (list.size() >= MAX_PER_CHANNEL) continue;
                Item i = program(c, pkg);
                if (i != null) list.add(i);
            }
        } catch (RuntimeException e) {
            error = (error.isEmpty() ? "" : error + "; ") + "Preview: " + e.getClass().getSimpleName();
        }
        PackageManager pm = ctx.getPackageManager();
        List<List<Item>> heroLists = new ArrayList<>();
        heroLists.add(sorted.subList(0, Math.min(3, sorted.size())));
        int rowOrder = 1;
        for (Map.Entry<Long, List<Item>> e : byChannel.entrySet()) {
            if (rowOrder > MAX_CHANNELS || e.getValue().isEmpty()) continue;
            String pkg = channelPkgs.get(e.getKey());
            String name = channelNames.get(e.getKey());
            String row = rowTitle(appLabel(pm, pkg != null ? pkg : e.getValue().get(0).pkg), name);
            for (Item i : e.getValue()) {
                i.row = row;
                i.rowOrder = rowOrder;
            }
            items.addAll(e.getValue());
            heroLists.add(e.getValue().subList(0, Math.min(2, e.getValue().size())));
            rowOrder++;
        }
        for (Item i : Pick.interleave(heroLists, MAX_HERO)) i.hero = true;

        // Ratings, then art, a few at a time.
        Omdb omdb = new Omdb(ctx, RATING_BUDGET);
        for (Item i : items) {
            if (Pick.rateable(i.pkg)) i.rating = omdb.rating(i.title, i.year);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> jobs = new ArrayList<>();
        for (Item i : items) {
            jobs.add(pool.submit(() -> {
                i.poster = Images.cache(ctx, i.posterSource, false);
                if (i.hero) i.backdrop = Images.cache(ctx, i.posterSource, true);
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
        return new Store.Snapshot(now, error, items, probe);
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
        String poster = str(c, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
        i.posterSource = !poster.isEmpty() ? poster : str(c, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
        return i;
    }

    static String rowTitle(String app, String channel) {
        if (channel == null || channel.isEmpty()) return app;
        if (app.isEmpty() || channel.toLowerCase().contains(app.toLowerCase())) return channel;
        return app + " · " + channel;
    }

    private static String appLabel(PackageManager pm, String pkg) {
        if (pkg == null) return "";
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
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
