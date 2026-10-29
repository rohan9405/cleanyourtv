package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FeedTest {
    private static Feed.Entry e(String row, String title, String intent) {
        return new Feed.Entry(row, title, "", "", -1, "", "", intent, "pkg");
    }

    @Test
    public void rowsFollowTheirOrderNotTheirFirstTile() {
        List<Feed.Entry> entries = Arrays.asList(e("YouTube", "a", "x"), e("Continue watching", "b", "x"),
                e("YouTube", "c", "x"));
        List<Feed.Row> rows = Feed.group(entries, Arrays.asList(1, 0, 1));
        assertEquals(2, rows.size());
        assertEquals("Continue watching", rows.get(0).title);
        assertEquals("YouTube", rows.get(1).title);
        assertEquals(2, rows.get(1).entries.size());
    }

    @Test
    public void tilesWithoutTitleOrTargetAreDropped() {
        List<Feed.Entry> entries = Arrays.asList(e("R", "", "x"), e("R", "t", ""), e("R", "ok", "x"), e(null, "t", "x"));
        List<Feed.Row> rows = Feed.group(entries, Arrays.asList(0, 0, 0, 0));
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).entries.size());
        assertEquals("ok", rows.get(0).entries.get(0).title);
    }
}
