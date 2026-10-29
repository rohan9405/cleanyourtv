package io.github.rohan9405.feed;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * TMDB (themoviedb.org) for what the apps do not publish: titles new on each streaming service in India in the last
 * 10 days, for the header, and what is popular on a service whose app publishes no rows (Netflix, SonyLIV). The token
 * comes from the Mac (my-tv/api-keys.sh). Answers are kept for 6 hours, and a host that resets the connection (some
 * Indian ISPs do, for api.themoviedb.org) is retried on the other.
 */
final class Tmdb {
    static final String PREFS = "tmdb";
    static final String KEY = "token";
    static final int NEW_DAYS = 14;
    private static final long CACHE_MS = 6L * 60 * 60 * 1000;
    private static final String[] HOSTS = {"api.tmdb.org", "api.themoviedb.org"};
    static final String IMAGES = "https://image.tmdb.org/t/p/";

    /** One film or series. */
    static final class Title {
        long id;
        String kind = "";        // "movie" or "tv"
        /** "Drama, Thriller" from TMDB's genre ids. */
        String genres = "";
        /** "Season 3" when the title counts as new because a season of it premiered in the window. */
        String season = "";
        String name = "";
        String year = "";
        String overview = "";
        String backdrop = "";    // full image URL, 16:9
        double popularity;
    }

    private final String token;
    private final File cache;

    Tmdb(Context ctx) {
        token = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim();
        cache = new File(ctx.getCacheDir(), "tmdb");
        //noinspection ResultOfMethodCallIgnored
        cache.mkdirs();
    }

    static void setToken(Context ctx, String token) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, token == null ? "" : token.trim()).apply();
    }

    static boolean hasToken(Context ctx) {
        return !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim().isEmpty();
    }

    boolean usable() {
        return !token.isEmpty();
    }

    /** New in the last {@value #NEW_DAYS} days: series that premiered, films that premiered or came out digitally. */
    List<Title> newReleases(int provider, LocalDate today) {
        String from = today.minusDays(NEW_DAYS).toString(), to = today.toString();
        List<Title> out = new ArrayList<>();
        add(out, discover("tv", provider, "first_air_date.gte", from, "first_air_date.lte", to), "tv");
        add(out, discover("movie", provider, "primary_release_date.gte", from, "primary_release_date.lte", to), "movie");
        add(out, discover("movie", provider, "release_date.gte", from, "release_date.lte", to,
                "region", Providers.REGION, "with_release_type", "4|6"), "movie");
        return dedupe(out);
    }

    /**
     * Series whose new season premiered in the last {@value #NEW_DAYS} days. A series with only a new episode of an old
     * season (a long-running show airing weekly) is not new and stays out: each candidate's seasons are checked.
     */
    List<Title> newSeasons(int provider, LocalDate today) {
        LocalDate from = today.minusDays(NEW_DAYS);
        List<Title> airing = new ArrayList<>();
        add(airing, discover("tv", provider, "air_date.gte", from.toString(), "air_date.lte", today.toString()), "tv");
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < Math.min(20, airing.size()); i++) {
            Title t = airing.get(i);
            JSONObject show = get("/3/tv/" + t.id);
            JSONArray seasons = show == null ? null : show.optJSONArray("seasons");
            for (int k = 0; seasons != null && k < seasons.length(); k++) {
                JSONObject season = seasons.optJSONObject(k);
                if (season == null || season.optInt("season_number") < 1) continue;
                LocalDate premiere = date(season.optString("air_date"));
                if (premiere != null && !premiere.isBefore(from) && !premiere.isAfter(today)) {
                    t.season = "Season " + season.optInt("season_number");
                    out.add(t);
                    break;
                }
            }
        }
        return out;
    }

    private static LocalDate date(String s) {
        try {
            return s == null || s.isEmpty() ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** TMDB genre id -> name, movies and series together; empty when TMDB cannot be reached. */
    private java.util.Map<Integer, String> genreNames;

    private String genres(JSONArray ids) {
        if (ids == null || ids.length() == 0) return "";
        if (genreNames == null) {
            genreNames = new java.util.HashMap<>();
            for (String kind : new String[] {"movie", "tv"}) {
                JSONObject list = get("/3/genre/" + kind + "/list");
                JSONArray g = list == null ? null : list.optJSONArray("genres");
                for (int i = 0; g != null && i < g.length(); i++) {
                    JSONObject o = g.optJSONObject(i);
                    if (o != null) genreNames.put(o.optInt("id"), o.optString("name"));
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ids.length() && names.size() < 3; i++) {
            String n = genreNames.get(ids.optInt(i));
            if (n != null && !n.isEmpty() && !names.contains(n)) names.add(n);
        }
        return android.text.TextUtils.join(", ", names);
    }

    /** The most popular series and films on a service, mixed. */
    List<Title> popular(int provider) {
        List<Title> tv = new ArrayList<>(), movies = new ArrayList<>();
        add(tv, discover("tv", provider), "tv");
        add(movies, discover("movie", provider), "movie");
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(tv.size(), movies.size()); i++) {
            if (i < tv.size()) out.add(tv.get(i));
            if (i < movies.size()) out.add(movies.get(i));
        }
        return out;
    }

    private JSONObject discover(String kind, int provider, String... params) {
        Uri.Builder b = new Uri.Builder().path("/3/discover/" + kind)
                .appendQueryParameter("watch_region", Providers.REGION)
                .appendQueryParameter("with_watch_providers", String.valueOf(provider))
                .appendQueryParameter("with_watch_monetization_types", "flatrate")
                .appendQueryParameter("sort_by", "popularity.desc")
                .appendQueryParameter("include_adult", "false");
        for (int i = 0; i + 1 < params.length; i += 2) b.appendQueryParameter(params[i], params[i + 1]);
        return get(b.build().toString());
    }

    private void add(List<Title> out, JSONObject page, String kind) {
        JSONArray results = page == null ? null : page.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            Title t = new Title();
            t.id = r.optLong("id");
            t.kind = kind;
            t.genres = genres(r.optJSONArray("genre_ids"));
            t.name = r.optString("tv".equals(kind) ? "name" : "title");
            String date = r.optString("tv".equals(kind) ? "first_air_date" : "release_date");
            t.year = Pick.year(date);
            t.overview = r.optString("overview");
            String backdrop = r.isNull("backdrop_path") ? "" : r.optString("backdrop_path");
            t.backdrop = backdrop.isEmpty() ? "" : IMAGES + "w1280" + backdrop;
            t.popularity = r.optDouble("popularity", 0);
            if (!t.name.isEmpty()) out.add(t);
        }
    }

    private static List<Title> dedupe(List<Title> in) {
        List<Title> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Title t : in) if (seen.add(t.kind + "|" + t.name.toLowerCase())) out.add(t);
        return out;
    }

    /** A cached answer younger than 6 hours, else the API (both hosts, twice each); null when nothing answers. */
    private JSONObject get(String pathAndQuery) {
        if (token.isEmpty()) return null;
        File f = new File(cache, Images.hash(pathAndQuery) + ".json");
        if (System.currentTimeMillis() - f.lastModified() < CACHE_MS && f.length() > 0) {
            try (InputStream in = new FileInputStream(f)) {
                return new JSONObject(new String(Net.read(in, 1 << 20), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Fetch again.
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            for (String host : HOSTS) {
                try {
                    Net.Response r = Net.request("https://" + host + pathAndQuery, "Authorization", "Bearer " + token, 1 << 20);
                    if (r.code / 100 != 2) {
                        if (r.code == 401) return null;   // a bad token: no point in retrying
                        continue;
                    }
                    JSONObject o = new JSONObject(r.body);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(r.body.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // Served without caching.
                    }
                    return o;
                } catch (Exception e) {
                    // Reset or timeout: the other host, then once more.
                }
            }
        }
        return null;
    }
}
