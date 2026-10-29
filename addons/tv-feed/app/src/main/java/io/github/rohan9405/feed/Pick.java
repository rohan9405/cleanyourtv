package io.github.rohan9405.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The choices that need no Android: what stays in Continue watching, the subtitle line, the title OMDb is asked for. */
final class Pick {
    /** Rows linger for months (docs/watch-next.md); older than this they are left out. */
    static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    /** At or past this share of the runtime a "continue" row is as good as finished. */
    static final int DONE_PERCENT = 95;

    private static final Pattern YEAR = Pattern.compile("^(\\d{4})");
    private static final Pattern SEASON_TAIL = Pattern.compile(
            "\\s*[-:–(]?\\s*(season|series|staffel|saison)\\s*\\d+\\)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_TAIL = Pattern.compile("\\s*\\((19|20)\\d{2}\\)\\s*$");

    private Pick() {
    }

    /** 0 to 100, or -1 when the app gave no position or no duration. */
    static int progress(long positionMs, long durationMs) {
        if (positionMs <= 0 || durationMs <= 0) return -1;
        return (int) Math.max(0, Math.min(100, positionMs * 100 / durationMs));
    }

    /** A Watch Next row worth showing: engaged with in the last 30 days (when the app says when) and not finished. */
    static boolean keepContinue(long engagedMs, long nowMs, int progress) {
        if (engagedMs > 0 && nowMs - engagedMs > MAX_AGE_MS) return false;
        return progress < DONE_PERCENT;
    }

    /** "2019" from "2019", "2019-05-01" or "2019/05"; empty otherwise. */
    static String year(String releaseDate) {
        if (releaseDate == null) return "";
        Matcher m = YEAR.matcher(releaseDate.trim());
        return m.find() ? m.group(1) : "";
    }

    /** "S2 E5 · The Episode", or whichever parts the app filled. */
    static String subtitle(String season, String episode, String episodeTitle) {
        StringBuilder b = new StringBuilder();
        if (notEmpty(season)) b.append('S').append(season.trim());
        if (notEmpty(episode)) {
            if (b.length() > 0) b.append(' ');
            b.append('E').append(episode.trim());
        }
        if (notEmpty(episodeTitle)) {
            if (b.length() > 0) b.append(" · ");
            b.append(episodeTitle.trim());
        }
        return b.toString();
    }

    /** The show's name as OMDb knows it: "Dark - Season 2" and "Dark (2017)" are asked for as "Dark". */
    static String cleanTitle(String title) {
        if (title == null) return "";
        String t = title.trim();
        t = SEASON_TAIL.matcher(t).replaceFirst("");
        t = YEAR_TAIL.matcher(t).replaceFirst("");
        return t.trim();
    }

    /** Apps whose rows are videos or music, never films or series: no IMDb lookup for them. */
    static boolean rateable(String pkg) {
        if (pkg == null) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        return !(p.contains("youtube") || p.contains("spotify") || p.contains("music") || p.contains("twitch"));
    }

    /** One from each list in turn, until max: the hero rotation mixes the rows instead of showing one app's picks. */
    static <T> List<T> interleave(List<List<T>> lists, int max) {
        List<T> out = new ArrayList<>();
        for (int i = 0; out.size() < max; i++) {
            boolean any = false;
            for (List<T> l : lists) {
                if (i < l.size()) {
                    any = true;
                    out.add(l.get(i));
                    if (out.size() >= max) break;
                }
            }
            if (!any) break;
        }
        return out;
    }

    /**
     * The header's order: titles first released this year, then new seasons out this year, then the rest (a film from
     * last year that has only now come to streaming); within each, best IMDb rating, then most popular.
     */
    static List<Item> rankHero(List<Item> in, int thisYear) {
        List<Item> out = new ArrayList<>(in);
        out.sort((a, b) -> {
            int t = Integer.compare(heroTier(a, thisYear), heroTier(b, thisYear));
            if (t != 0) return t;
            int r = Double.compare(b.ratingValue(), a.ratingValue());
            return r != 0 ? r : Double.compare(b.popularity, a.popularity);
        });
        return out;
    }

    static int heroTier(Item i, int thisYear) {
        if (!String.valueOf(thisYear).equals(i.year)) return 2;
        return i.subtitle.isEmpty() ? 0 : 1;
    }

    /**
     * Recent enough for the header: from this year or last, or a new season (its year is the season's). An older film
     * that only has a fresh digital date in TMDB is a catalogue title, not a release.
     */
    static boolean recentEnough(String year, String season, int thisYear) {
        if (season != null && !season.isEmpty()) return true;
        try {
            return Integer.parseInt(year) >= thisYear - 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Best IMDb rating first; unrated titles (often too new to have one) after them, most popular first. */
    static List<Item> rank(List<Item> in) {
        List<Item> out = new ArrayList<>(in);
        out.sort((a, b) -> {
            int r = Double.compare(b.ratingValue(), a.ratingValue());
            return r != 0 ? r : Double.compare(b.popularity, a.popularity);
        });
        return out;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
