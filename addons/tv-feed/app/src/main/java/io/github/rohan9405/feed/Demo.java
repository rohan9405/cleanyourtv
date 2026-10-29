package io.github.rohan9405.feed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample rows with posters drawn here, for previewing Home on an emulator that has none of your apps:
 * {@code am start -n io.github.rohan9405.feed/.FeedActivity --ez demo true} (false goes back to the TV's rows).
 */
final class Demo {
    static final String PREFS = "feed";
    static final String KEY = "demo";
    /** Opens the Feed screen: the emulator has none of the apps the samples name. */
    private static final String OPEN = "intent:#Intent;component=io.github.rohan9405.feed/.FeedActivity;end";

    private Demo() {
    }

    static boolean on(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    static void set(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }

    static Store.Snapshot snapshot(Context ctx) {
        List<Item> items = new ArrayList<>();
        String cont = ctx.getString(R.string.continue_watching);
        add(ctx, items, cont, 0, "Panchayat", "S3 E4 · The Election", "9.0", 62, "com.amazon.amazonvideo.livingroom", 0xFF2E7D32, true);
        add(ctx, items, cont, 0, "Kota Factory", "S3 E2", "9.0", 35, "com.netflix.ninja", 0xFF37474F, true);
        add(ctx, items, cont, 0, "Scam 1992", "S1 E7 · Bull Run", "9.2", 80, "com.sonyliv", 0xFF6D4C41, false);
        add(ctx, items, cont, 0, "The Family Man", "S2 E1", "8.7", 15, "com.amazon.amazonvideo.livingroom", 0xFF1565C0, false);
        add(ctx, items, cont, 0, "Aspirants", "S2 E3", "9.1", 50, "com.amazon.amazonvideo.livingroom", 0xFF4E342E, false);
        String yt = "YouTube · Recommended for you";
        add(ctx, items, yt, 1, "How the Indian Railways run on time", "", "", -1, "com.google.android.youtube.tv", 0xFFC62828, true);
        add(ctx, items, yt, 1, "Street food tour of Old Delhi", "", "", -1, "com.google.android.youtube.tv", 0xFFAD1457, false);
        add(ctx, items, yt, 1, "Cricket highlights: final over", "", "", -1, "com.google.android.youtube.tv", 0xFF00838F, false);
        add(ctx, items, yt, 1, "Lo-fi beats to study to", "", "", -1, "com.google.android.youtube.tv", 0xFF5E35B1, false);
        add(ctx, items, yt, 1, "Building a house in 100 days", "", "", -1, "com.google.android.youtube.tv", 0xFF558B2F, false);
        add(ctx, items, yt, 1, "Himalaya trek, day 4", "", "", -1, "com.google.android.youtube.tv", 0xFF283593, false);
        String hs = "JioHotstar · Trending";
        add(ctx, items, hs, 2, "Special Ops", "", "8.6", -1, "in.startv.hotstar", 0xFF263238, true);
        add(ctx, items, hs, 2, "Aarya", "", "7.8", -1, "in.startv.hotstar", 0xFF880E4F, false);
        add(ctx, items, hs, 2, "Criminal Justice", "", "8.1", -1, "in.startv.hotstar", 0xFF3E2723, false);
        add(ctx, items, hs, 2, "Hostages", "", "7.4", -1, "in.startv.hotstar", 0xFF1A237E, false);
        String nf = "Netflix · New this week";
        add(ctx, items, nf, 3, "Sacred Games", "", "8.5", -1, "com.netflix.ninja", 0xFFB71C1C, true);
        add(ctx, items, nf, 3, "Delhi Crime", "", "8.5", -1, "com.netflix.ninja", 0xFF212121, false);
        add(ctx, items, nf, 3, "Jamtara", "", "7.8", -1, "com.netflix.ninja", 0xFF33691E, false);
        Map<String, int[]> probe = new LinkedHashMap<>();
        probe.put("(demo rows: no app on this TV was read)", new int[3]);
        return new Store.Snapshot(System.currentTimeMillis(), "", items, probe);
    }

    private static void add(Context ctx, List<Item> items, String row, int order, String title, String subtitle,
                            String rating, int progress, String pkg, int colour, boolean hero) {
        Item i = new Item();
        i.row = row;
        i.rowOrder = order;
        i.title = title;
        i.subtitle = subtitle;
        i.rating = rating;
        i.progress = progress;
        i.pkg = pkg;
        i.intent = OPEN;
        i.hero = hero;
        i.posterSource = "demo:" + title;
        i.poster = draw(ctx, i.posterSource, title, colour, false);
        if (hero) i.backdrop = draw(ctx, i.posterSource, title, colour, true);
        items.add(i);
    }

    /** A gradient card with the title on it, saved where FeedProvider serves art from. */
    private static String draw(Context ctx, String source, String title, int colour, boolean backdrop) {
        String name = Images.hash(source) + (backdrop ? "-b.jpg" : "-t.jpg");
        File f = new File(Images.dir(ctx), name);
        if (f.length() > 0) return name;
        int w = backdrop ? Images.BACKDROP_W : Images.TILE_W;
        int h = w * 9 / 16;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, colour, 0xFF0B0D12, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0x33FFFFFF);
        c.drawCircle(w * 0.78f, h * 0.35f, h * 0.45f, p);
        if (!backdrop) {
            p.setColor(0xFFFFFFFF);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            p.setTextSize(h * 0.13f);
            String t = title;
            while (p.measureText(t) > w * 0.86f && t.length() > 4) t = t.substring(0, t.length() - 2);
            if (!t.equals(title)) t = t.trim() + "…";
            c.drawText(t, w * 0.07f, h * 0.86f, p);
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            return "";
        } finally {
            b.recycle();
        }
        return name;
    }
}
