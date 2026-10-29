package io.github.rohan9405.feed;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;

/**
 * IMDb ratings through OMDb (omdbapi.com; IMDb has no free API). A free key allows 1,000 lookups a day. Every answer
 * is cached with the title's year and release date, and stays fresh as long as {@link CachePolicy} says (3 to 30
 * days by age, as in StreamRatings), so a refresh asks only for titles that are new or due. When OMDb cannot be
 * reached, or the refresh's budget is spent, the last rating known is shown rather than none.
 */
final class Omdb {
    static final String PREFS = "omdb";
    static final String KEY = "api_key";

    private final SharedPreferences prefs;
    private final String apiKey;
    private int budget;

    Omdb(Context ctx, int budget) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        apiKey = prefs.getString(KEY, "").trim();
        this.budget = budget;
    }

    static void setKey(Context ctx, String key) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key == null ? "" : key.trim()).apply();
    }

    static boolean hasKey(Context ctx) {
        return !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim().isEmpty();
    }

    /** One cached answer. */
    static final class Entry {
        final boolean found;
        final String rating;
        final String year;
        final String released;
        final long savedMs;

        Entry(boolean found, String rating, String year, String released, long savedMs) {
            this.found = found;
            this.rating = rating;
            this.year = year;
            this.released = released;
            this.savedMs = savedMs;
        }

        boolean fresh(long nowMs) {
            LocalDate today = LocalDate.now();
            return CachePolicy.fresh(savedMs, CachePolicy.ttlDays(found, year, released, today), nowMs);
        }

        String toJson() {
            try {
                return new JSONObject().put("found", found).put("rating", rating).put("year", year)
                        .put("released", released).put("ts", savedMs).toString();
            } catch (JSONException e) {
                return "";
            }
        }

        /** Null for a missing or unreadable entry (including the old "8.1@ms" format), which is then asked for again. */
        static Entry parse(String s) {
            if (s == null || !s.startsWith("{")) return null;
            try {
                JSONObject o = new JSONObject(s);
                return new Entry(o.optBoolean("found"), o.optString("rating"), o.optString("year"),
                        o.optString("released"), o.optLong("ts"));
            } catch (JSONException e) {
                return null;
            }
        }
    }

    /** "8.1", or empty when there is no key, no match, or no rating yet. */
    String rating(String title, String year) {
        String clean = Pick.cleanTitle(title);
        if (apiKey.isEmpty() || clean.isEmpty()) return "";
        String cacheKey = "r:" + clean.toLowerCase(Locale.ROOT) + "|" + year;
        Entry cached = Entry.parse(prefs.getString(cacheKey, null));
        long now = System.currentTimeMillis();
        String stale = cached == null ? "" : cached.rating;
        if (cached != null && cached.fresh(now)) return cached.rating;
        if (budget <= 0) return stale;
        budget--;
        Entry fetched = fetch(clean, year, now);
        if (fetched == null) return stale;   // network trouble: keep the last rating, cache nothing
        prefs.edit().putString(cacheKey, fetched.toJson()).apply();
        return fetched.rating;
    }

    /** Null on a network error. A year that finds nothing is retried without it. */
    private Entry fetch(String title, String year, long now) {
        try {
            Entry e = query(title, year, now);
            if (!e.found && !year.isEmpty()) e = query(title, "", now);
            return e;
        } catch (Exception e) {
            return null;
        }
    }

    private Entry query(String title, String year, long now) throws Exception {
        Uri.Builder b = Uri.parse("https://www.omdbapi.com/").buildUpon()
                .appendQueryParameter("apikey", apiKey).appendQueryParameter("t", title);
        if (!year.isEmpty()) b.appendQueryParameter("y", year);
        JSONObject o = new JSONObject(new String(Net.get(b.build().toString(), 64 * 1024), StandardCharsets.UTF_8));
        if (!"True".equalsIgnoreCase(o.optString("Response"))) {
            // "Invalid API key!" or "Request limit reached!" is not an answer about the title: don't cache it.
            String error = o.optString("Error").toLowerCase(Locale.ROOT);
            if (error.contains("key") || error.contains("limit")) throw new IllegalStateException(error);
            return new Entry(false, "", "", "", now);
        }
        String rating = o.optString("imdbRating");
        return new Entry(true, rating.matches("\\d(\\.\\d)?|10(\\.0)?") ? rating : "",
                o.optString("Year"), o.optString("Released"), now);
    }
}
