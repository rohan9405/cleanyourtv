package io.github.rohan9405.feed;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * IMDb ratings through OMDb (omdbapi.com; IMDb has no free API). A free key allows 1,000 lookups a day; every
 * answer is cached for a week (a miss for two days), so a refresh asks only for titles it has not seen.
 */
final class Omdb {
    static final String PREFS = "omdb";
    static final String KEY = "api_key";
    private static final long HIT_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long MISS_MS = 2L * 24 * 60 * 60 * 1000;

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

    /** "8.1", or empty when there is no key, no match, or no rating yet. */
    String rating(String title, String year) {
        String clean = Pick.cleanTitle(title);
        if (apiKey.isEmpty() || clean.isEmpty()) return "";
        String cacheKey = "r:" + clean.toLowerCase(Locale.ROOT) + "|" + year;
        String cached = prefs.getString(cacheKey, null);
        long now = System.currentTimeMillis();
        if (cached != null) {
            int at = cached.lastIndexOf('@');
            long when = at > 0 ? parseLong(cached.substring(at + 1)) : 0;
            String value = at > 0 ? cached.substring(0, at) : "";
            if (now - when < (value.isEmpty() ? MISS_MS : HIT_MS)) return value;
        }
        if (budget <= 0) return cached == null ? "" : cached.substring(0, Math.max(0, cached.lastIndexOf('@')));
        budget--;
        String value = fetch(clean, year);
        if (value == null) return "";   // network trouble: try again next refresh, cache nothing
        prefs.edit().putString(cacheKey, value + "@" + now).apply();
        return value;
    }

    /** Null on a network error, empty when OMDb has no rating. A year that finds nothing is retried without it. */
    private String fetch(String title, String year) {
        try {
            String r = query(title, year);
            if (r.isEmpty() && !year.isEmpty()) r = query(title, "");
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private String query(String title, String year) throws Exception {
        Uri.Builder b = Uri.parse("https://www.omdbapi.com/").buildUpon()
                .appendQueryParameter("apikey", apiKey).appendQueryParameter("t", title);
        if (!year.isEmpty()) b.appendQueryParameter("y", year);
        JSONObject o = new JSONObject(new String(Net.get(b.build().toString(), 64 * 1024), StandardCharsets.UTF_8));
        if (!"True".equalsIgnoreCase(o.optString("Response"))) return "";
        String rating = o.optString("imdbRating");
        return rating.matches("\\d(\\.\\d)?|10(\\.0)?") ? rating : "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
