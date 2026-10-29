package io.github.rohan9405.feed;

import org.json.JSONException;
import org.json.JSONObject;

/** One tile: a Continue watching row or a recommendation from an app's channel. */
final class Item {
    String row = "";
    int rowOrder;
    String title = "";
    String subtitle = "";
    String year = "";
    /** IMDb rating such as "8.1", empty when unknown. */
    String rating = "";
    /** 0 to 100, -1 unknown. */
    int progress = -1;
    /** Where the app's artwork lives (usually https); downloaded into the cache. */
    String posterSource = "";
    /** Cache file names served by FeedProvider, empty until downloaded. */
    String poster = "";
    String backdrop = "";
    String intent = "";
    String pkg = "";
    boolean hero;

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("row", row).put("row_order", rowOrder).put("title", title).put("subtitle", subtitle)
                .put("year", year).put("rating", rating).put("progress", progress)
                .put("poster_source", posterSource).put("poster", poster).put("backdrop", backdrop)
                .put("intent", intent).put("package", pkg).put("hero", hero);
    }

    static Item fromJson(JSONObject o) {
        Item i = new Item();
        i.row = o.optString("row");
        i.rowOrder = o.optInt("row_order");
        i.title = o.optString("title");
        i.subtitle = o.optString("subtitle");
        i.year = o.optString("year");
        i.rating = o.optString("rating");
        i.progress = o.optInt("progress", -1);
        i.posterSource = o.optString("poster_source");
        i.poster = o.optString("poster");
        i.backdrop = o.optString("backdrop");
        i.intent = o.optString("intent");
        i.pkg = o.optString("package");
        i.hero = o.optBoolean("hero");
        return i;
    }
}
