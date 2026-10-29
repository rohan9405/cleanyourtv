package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class FeedTest {
    private static Feed.Entry e(String section, String tab, int order, String title, String rating) {
        return new Feed.Entry(section, tab, order, title, "", rating, -1, "", "", "x", tab, "", "", "");
    }

    @Test
    public void tabsKeepRankOrderAndDropUnusable() {
        List<Feed.Entry> in = Arrays.asList(e(Feed.APP, "p", 2, "two", ""), e(Feed.APP, "p", 1, "one", ""),
                e(Feed.APP, "p", 3, "", ""), e(Feed.CONTINUE, "p", 0, "cont", ""), e(Feed.APP, "", 1, "noTab", ""));
        Map<String, List<Feed.Entry>> tabs = Feed.group(in, Feed.APP);
        assertEquals(1, tabs.size());
        assertEquals("one", tabs.get("p").get(0).title);
        assertEquals("two", tabs.get("p").get(1).title);
    }

    private static List<Feed.Entry> ranked(String pkg, int n) {
        List<Feed.Entry> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(e(Feed.HERO, pkg, i + 1, pkg + i, String.valueOf(9 - i * 0.1)));
        return l;
    }

    @Test
    public void heroTakesFivePerDrawerAppInTurnThenFillsByRating() {
        Map<String, List<Feed.Entry>> c = new java.util.LinkedHashMap<>();
        c.put("netflix", ranked("netflix", 10));
        c.put("prime", ranked("prime", 2));
        c.put("apple", ranked("apple", 8));
        c.put("notInDrawer", ranked("notInDrawer", 5));
        c.put(Feed.YOUTUBE, ranked(Feed.YOUTUBE, 5));
        List<Feed.Entry> out = Feed.pickHero(c, Arrays.asList("netflix", Feed.YOUTUBE, "prime", "apple"), 5, 20);
        // 5 + 2 + 5 in turn, then the 5 + 3 left over from netflix and apple: 20 asked, 20 available.
        assertEquals(20, out.size());
        assertEquals("netflix0", out.get(0).title);
        assertEquals("prime0", out.get(1).title);
        assertEquals("apple0", out.get(2).title);
        for (Feed.Entry x : out) {
            assertFalse(x.tab.equals(Feed.YOUTUBE));
            assertFalse(x.tab.equals("notInDrawer"));
        }
        // The top-up comes best-rated first: netflix5 and apple5 are both 8.5, ahead of netflix6 (8.4).
        assertEquals(8.5, out.get(12).ratingValue(), 0.001);
    }

    @Test
    public void heroStopsAtTotal() {
        Map<String, List<Feed.Entry>> c = new java.util.HashMap<>();
        c.put("a", ranked("a", 10));
        c.put("b", ranked("b", 10));
        c.put("c", ranked("c", 10));
        c.put("d", ranked("d", 10));
        c.put("e", ranked("e", 10));
        assertEquals(20, Feed.pickHero(c, Arrays.asList("a", "b", "c", "d", "e"), 5, 20).size());
    }
}
