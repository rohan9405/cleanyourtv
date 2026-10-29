package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The home screen when feed_package is on, in place of the dock and grid, top to bottom:
 * <ol>
 *   <li>the header: a release from the last 10 days on one of the drawer's services, its app's logo, IMDb rating and a
 *       line of plot over its backdrop, turning every few seconds; nothing focusable;</li>
 *   <li>the drawer: the owner's favourite apps on one line, the tiles shrinking so all of them fit. Hold OK on one
 *       for Move, Remove and App info, and on any app below to add it (as on Google TV);</li>
 *   <li>Continue watching;</li>
 *   <li>Top 10: one row and a tab per drawer app; moving across the tabs changes the row;</li>
 *   <li>every other app.</li>
 * </ol>
 * Up from the drawer goes to the top bar, so nothing but the header sits above it.
 */
final class FeedHome {
    interface Host {
        /** Up from the drawer. */
        void onUp();
    }

    private static final long TURN_MS = 8000;
    private static final float BACKDROP_TOP = 0.75f;
    private static final float BACKDROP_SCROLLED = 0.28f;
    private static final int IMDB_YELLOW = 0xFFF5C518;
    private static final int HERO_PER_APP = 5;
    private static final int HERO_TOTAL = 20;
    private static final int GRID_COLUMNS = 6;
    private static final String PREFS = "feed_home";
    private static final String DRAWER = "drawer";

    private static final int S_DRAWER = 0, S_CONTINUE = 1, S_TABS = 2, S_TAB_ROW = 3, S_APPS = 4;

    private final Activity act;
    private final Feed feed;
    private final Host host;
    private final Handler handler;
    private final float d;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final Set<String> hidden;
    private final List<String> defaultDrawer;
    private final Typeface light, regular, medium;

    private final LinearLayout container;
    private final ImageView backdrop;
    private final Scroll scroll;
    private final LinearLayout content;
    private final LinearLayout heroBlock;
    private final ImageView heroLogo;
    private final TextView heroKicker, heroTitle, heroMeta, heroPlot;
    private final LinearLayout heroTag;
    private final LinearLayout heroDots;
    private final LinearLayout drawerBand;
    private final TextView continueLabel, tabsLabel, appsLabel;
    private final RecyclerView continueRow, tabRow, appsGrid;
    private final LinearLayout tabStrip;
    private final View[] sections = new View[5];

    private final Map<String, App> apps = new HashMap<>();
    private List<String> drawer = new ArrayList<>();
    private final List<String> tabPkgs = new ArrayList<>();
    private String selectedTab = "";
    private List<Feed.Entry> heroes = Collections.emptyList();
    private int heroTurn = -1;
    private String backdropSource = "";
    private int lastDrawer, lastContinue, lastTabRow;
    private int movingIndex = -1;
    private final Runnable turn = this::nextHero;
    private final Rect rect = new Rect();

    /** An installed TV app. */
    static final class App {
        final String pkg;
        final String label;
        final Drawable banner;
        final Drawable icon;

        App(String pkg, String label, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }
    }

    FeedHome(Activity act, Feed feed, Host host, Handler handler, LinearLayout container, ImageView backdrop, View scrim,
             List<String> dockApps, List<String> hiddenApps) {
        this.act = act;
        this.feed = feed;
        this.host = host;
        this.handler = handler;
        this.container = container;
        this.backdrop = backdrop;
        d = act.getResources().getDisplayMetrics().density;
        pm = act.getPackageManager();
        prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        hidden = new HashSet<>(hiddenApps);
        defaultDrawer = new ArrayList<>(dockApps);
        light = font(R.font.outfit_light);
        regular = font(R.font.outfit_regular);
        medium = font(R.font.outfit_medium);

        container.removeAllViews();
        container.setVisibility(View.VISIBLE);
        backdrop.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        // Dark on the left, where the header's text sits, and at the bottom, where the rows go.
        scrim.setBackground(new LayerDrawable(new Drawable[] {
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF2080A10, 0xA6080A10, 0x00080A10}),
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00080A10, 0x33080A10, 0xF2080A10})}));

        scroll = new Scroll(act, null);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // The page is clipped to its own bounds only when its parent clips children: rows scrolling up then pass under
        // the top bar instead of over the clock. Tiles keep room to grow from the rows' padding.
        container.setClipChildren(true);
        container.setClipToPadding(true);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            float a = y > dp(40) ? BACKDROP_SCROLLED : BACKDROP_TOP;
            if (backdrop.getAlpha() > 0 && Math.abs(backdrop.getAlpha() - a) > 0.01f) {
                backdrop.animate().alpha(a).setDuration(300).start();
            }
        });
        container.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content = column();
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(4), 0, dp(4), dp(48));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 1. Header.
        heroBlock = column();
        heroBlock.setGravity(Gravity.BOTTOM);
        heroBlock.setPadding(dp(8), 0, 0, dp(22));
        LinearLayout kickerRow = row();
        kickerRow.setGravity(Gravity.CENTER_VERTICAL);
        heroLogo = new ImageView(act);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heroLogo.setClipToOutline(true);
        heroLogo.setBackground(rounded(0x00000000, dp(6)));
        kickerRow.addView(heroLogo, new LinearLayout.LayoutParams(dp(26), dp(26)));
        heroKicker = text(12, 0xD9FFFFFF, medium);
        heroKicker.setAllCaps(true);
        heroKicker.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams klp = wrap();
        klp.leftMargin = dp(10);
        kickerRow.addView(heroKicker, klp);
        heroBlock.addView(kickerRow, wrap());
        heroTitle = text(44, Color.WHITE, light);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setLetterSpacing(-0.01f);
        heroTitle.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        heroBlock.addView(heroTitle, tlp);
        LinearLayout metaRow = row();
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        heroTag = row();
        metaRow.addView(heroTag, wrap());
        heroMeta = text(14, 0xCCFFFFFF, regular);
        LinearLayout.LayoutParams mlp = wrap();
        mlp.leftMargin = dp(12);
        metaRow.addView(heroMeta, mlp);
        LinearLayout.LayoutParams mrlp = wrap();
        mrlp.topMargin = dp(12);
        heroBlock.addView(metaRow, mrlp);
        heroPlot = text(15, 0xB3FFFFFF, regular);
        heroPlot.setMaxLines(2);
        heroPlot.setEllipsize(TextUtils.TruncateAt.END);
        heroPlot.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(10);
        heroBlock.addView(heroPlot, plp);
        heroDots = row();
        LinearLayout.LayoutParams dlp = wrap();
        dlp.topMargin = dp(14);
        heroBlock.addView(heroDots, dlp);
        content.addView(heroBlock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        // 2. Drawer.
        drawerBand = row();
        drawerBand.setGravity(Gravity.CENTER);
        drawerBand.setClipChildren(false);
        drawerBand.setClipToPadding(false);
        drawerBand.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable band = rounded(0x1AFFFFFF, dp(22));
        band.setStroke(Math.max(1, dp(1) / 2), 0x29FFFFFF);
        drawerBand.setBackground(band);
        content.addView(drawerBand, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sections[S_DRAWER] = drawerBand;

        // 3. Continue watching.
        continueLabel = label(act.getString(R.string.feed_continue));
        content.addView(continueLabel, labelParams());
        continueRow = row(S_CONTINUE);
        content.addView(continueRow, rowParams(TILE_H + 28));
        sections[S_CONTINUE] = continueRow;

        // 4. Top 10, a tab per drawer app.
        tabsLabel = label(act.getString(R.string.feed_top10));
        content.addView(tabsLabel, labelParams());
        tabStrip = row();
        tabStrip.setClipChildren(false);
        tabStrip.setClipToPadding(false);
        tabStrip.setPadding(dp(8), dp(4), dp(8), dp(4));
        content.addView(tabStrip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sections[S_TABS] = tabStrip;
        tabRow = row(S_TAB_ROW);
        LinearLayout.LayoutParams trp = rowParams(TILE_H + 28);
        trp.topMargin = dp(8);
        content.addView(tabRow, trp);
        sections[S_TAB_ROW] = tabRow;

        // 5. Every other app.
        appsLabel = label(act.getString(R.string.feed_apps));
        content.addView(appsLabel, labelParams());
        appsGrid = new RecyclerView(act);
        appsGrid.setLayoutManager(new GridLayoutManager(act, GRID_COLUMNS));
        appsGrid.setNestedScrollingEnabled(false);
        appsGrid.setClipChildren(false);
        appsGrid.setClipToPadding(false);
        appsGrid.setPadding(dp(8), dp(8), dp(8), dp(8));
        appsGrid.setItemAnimator(null);
        appsGrid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content.addView(appsGrid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sections[S_APPS] = appsGrid;

        // The header fills what the screen has above the drawer, so the drawer sits low with nothing over it but the header.
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot || r - l != or - ol) handler.post(this::layoutSizes);
        });
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------

    /** Home is on screen: apps may have changed while it was not. Rebuilding drops focus, so it goes back to the drawer. */
    void start() {
        loadApps();
        buildDrawer();
        buildApps();
        update();
        handler.post(this::ensureFocus);
    }

    /** Nothing focused, or focus lost in the hidden grid: back to the drawer. The top bar keeps its focus. */
    void ensureFocus() {
        View f = act.getCurrentFocus();
        if (f == null || !f.isShown()) focusDrawer();
    }

    void stop() {
        handler.removeCallbacks(turn);
        endMove(false);
    }

    /** The add-on published new rows, or the drawer changed. */
    void update() {
        continueRow.setAdapter(new Tiles(feed.continueRow(), S_CONTINUE, false));
        continueLabel.setVisibility(feed.continueRow().isEmpty() ? View.GONE : View.VISIBLE);
        continueRow.setVisibility(feed.continueRow().isEmpty() ? View.GONE : View.VISIBLE);
        buildTabs();
        heroes = Feed.pickHero(feed.heroCandidates(), drawer, HERO_PER_APP, HERO_TOTAL);
        buildDots();
        heroTurn = -1;
        handler.removeCallbacks(turn);
        nextHero();
    }

    /** Focus the drawer, on the tile it was last on. */
    void focusDrawer() {
        if (drawerBand.getChildCount() == 0) {
            focusSection(S_CONTINUE, 1);
            return;
        }
        drawerBand.getChildAt(Math.max(0, Math.min(lastDrawer, drawerBand.getChildCount() - 1))).requestFocus();
        // A tile that already had focus fires no focus change, so the page would stay where it was.
        scroll.smoothScrollTo(0, 0);
    }

    // ---- sizes -------------------------------------------------------------------------------------------

    private static final int TILE_W = 232;
    private static final int TILE_H = 130;

    private void layoutSizes() {
        int h = container.getHeight();
        if (h <= 0) return;
        int drawerH = drawerBand.getHeight() > 0 ? drawerBand.getHeight() : dp(124);
        // Leave room under the drawer for the next row's name to peek in, as a hint that there is more below.
        int heroH = Math.max(dp(160), h - drawerH - dp(44));
        ViewGroup.LayoutParams lp = heroBlock.getLayoutParams();
        if (lp.height != heroH) {
            lp.height = heroH;
            heroBlock.setLayoutParams(lp);
        }
        buildDrawer();
        buildApps();
    }

    // ---- apps and the drawer ------------------------------------------------------------------------------

    private void loadApps() {
        apps.clear();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(leanback, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(act.getPackageName()) || hidden.contains(pkg) || apps.containsKey(pkg)) continue;
            Drawable banner = r.activityInfo.loadBanner(pm);
            if (banner == null) banner = r.activityInfo.applicationInfo.loadBanner(pm);
            apps.put(pkg, new App(pkg, String.valueOf(r.loadLabel(pm)), banner, r.loadIcon(pm)));
        }
        String saved = prefs.getString(DRAWER, null);
        List<String> want = saved == null ? defaultDrawer : Arrays.asList(TextUtils.split(saved, ","));
        drawer = new ArrayList<>();
        for (String p : want) if (apps.containsKey(p) && !drawer.contains(p)) drawer.add(p);
    }

    private void saveDrawer() {
        prefs.edit().putString(DRAWER, TextUtils.join(",", drawer)).apply();
    }

    /** Every drawer app on one line: tiles up to 176 dp wide, smaller when more apps have to fit. */
    private void buildDrawer() {
        boolean hadFocus = drawerBand.hasFocus();
        drawerBand.removeAllViews();
        int n = drawer.size();
        if (n == 0) return;
        int width = drawerBand.getWidth() > 0 ? drawerBand.getWidth() : act.getResources().getDisplayMetrics().widthPixels - dp(120);
        int gap = dp(14);
        int inner = width - drawerBand.getPaddingLeft() - drawerBand.getPaddingRight();
        int w = Math.min(dp(176), (inner - gap * (n - 1)) / n);
        int h = w * 9 / 16;
        for (int i = 0; i < n; i++) {
            App a = apps.get(drawer.get(i));
            View tile = appTile(a, w, h);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i > 0) lp.leftMargin = gap;
            final int index = i;
            tile.setOnFocusChangeListener((v, has) -> {
                lift(v, has, movingIndex == index ? 1.16f : 1.12f);
                if (has) lastDrawer = index;
            });
            tile.setOnClickListener(v -> {
                if (movingIndex >= 0) endMove(true);
                else launch(a.pkg);
            });
            tile.setOnLongClickListener(v -> {
                drawerMenu(index);
                return true;
            });
            tile.setOnKeyListener((v, key, e) -> drawerKey(index, key, e));
            drawerBand.addView(tile, lp);
        }
        if (hadFocus) focusDrawer();
    }

    private boolean drawerKey(int index, int key, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (movingIndex >= 0) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int to = index + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (to >= 0 && to < drawer.size()) {
                    Collections.swap(drawer, index, to);
                    movingIndex = to;
                    lastDrawer = to;
                    buildDrawer();
                    drawerBand.getChildAt(to).requestFocus();
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                endMove(true);
                return true;
            }
            return false;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                host.onUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focusSection(S_CONTINUE, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return index == 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return index == drawer.size() - 1;
            default:
                return false;
        }
    }

    private void drawerMenu(int index) {
        String pkg = drawer.get(index);
        App a = apps.get(pkg);
        menu(a == null ? pkg : a.label, new String[] {
                act.getString(R.string.feed_open), act.getString(R.string.feed_move),
                act.getString(R.string.feed_remove), act.getString(R.string.feed_app_info)}, new Runnable[] {
                () -> launch(pkg),
                () -> {
                    movingIndex = index;
                    buildDrawer();
                    drawerBand.getChildAt(index).requestFocus();
                    Toast.makeText(act, R.string.feed_move_hint, Toast.LENGTH_SHORT).show();
                },
                () -> {
                    drawer.remove(pkg);
                    saveDrawer();
                    lastDrawer = Math.max(0, Math.min(index, drawer.size() - 1));
                    buildDrawer();
                    buildApps();
                    update();
                    focusDrawer();
                },
                () -> appInfo(pkg)});
    }

    private void endMove(boolean save) {
        if (movingIndex < 0) return;
        movingIndex = -1;
        if (save) saveDrawer();
        buildDrawer();
        update();
        focusDrawer();
    }

    /** Every installed TV app that is not in the drawer. */
    private void buildApps() {
        List<App> rest = new ArrayList<>();
        for (App a : apps.values()) if (!drawer.contains(a.pkg)) rest.add(a);
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        int width = appsGrid.getWidth() > 0 ? appsGrid.getWidth() : act.getResources().getDisplayMetrics().widthPixels - dp(120);
        int w = (width - appsGrid.getPaddingLeft() - appsGrid.getPaddingRight()) / GRID_COLUMNS - dp(14);
        appsGrid.setAdapter(new AppsAdapter(rest, w, w * 9 / 16));
    }

    private final class AppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<App> list;
        private final int w, h;

        AppsAdapter(List<App> list, int w, int h) {
            this.list = list;
            this.w = w;
            this.h = h;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout cell = new FrameLayout(act);
            cell.setClipChildren(false);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h + dp(14)));
            return new RecyclerView.ViewHolder(cell) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            FrameLayout cell = (FrameLayout) vh.itemView;
            cell.removeAllViews();
            App a = list.get(position);
            View tile = appTile(a, w, h);
            cell.addView(tile, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
            tile.setOnFocusChangeListener((v, has) -> lift(v, has, 1.1f));
            tile.setOnClickListener(v -> launch(a.pkg));
            tile.setOnLongClickListener(v -> {
                menu(a.label, new String[] {act.getString(R.string.feed_open), act.getString(R.string.feed_add),
                        act.getString(R.string.feed_app_info)}, new Runnable[] {
                        () -> launch(a.pkg),
                        () -> {
                            drawer.add(a.pkg);
                            saveDrawer();
                            lastDrawer = drawer.size() - 1;
                            buildDrawer();
                            buildApps();
                            update();
                            focusDrawer();
                        },
                        () -> appInfo(a.pkg)});
                return true;
            });
            tile.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                int col = position % GRID_COLUMNS;
                if (key == KeyEvent.KEYCODE_DPAD_UP && position < GRID_COLUMNS) {
                    focusSection(S_APPS - 1, -1);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && position + GRID_COLUMNS >= list.size()) {
                    int below = position + GRID_COLUMNS - col;   // the next row's first cell
                    return below >= list.size() || focusGridCell(list.size() - 1);
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) return col == 0;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return col == GRID_COLUMNS - 1 || position == list.size() - 1;
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    private boolean focusGridCell(int position) {
        RecyclerView.ViewHolder vh = appsGrid.findViewHolderForAdapterPosition(position);
        if (vh == null) return false;
        ((ViewGroup) vh.itemView).getChildAt(0).requestFocus();
        return true;
    }

    /** An app's banner, or its icon and name on a dark card when it has none. */
    private View appTile(App a, int w, int h) {
        FrameLayout tile = new FrameLayout(act);
        tile.setFocusable(true);
        tile.setLongClickable(true);
        tile.setClipToOutline(true);
        tile.setBackground(rounded(0xFF1C2130, dp(12)));
        tile.setForeground(ring(dp(12)));
        tile.setContentDescription(a == null ? "" : a.label);
        if (a == null) return tile;
        if (a.banner != null) {
            ImageView img = new ImageView(act);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(a.banner);
            tile.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout col = column();
            col.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(act);
            icon.setImageDrawable(a.icon);
            int s = Math.max(dp(20), h / 3);
            col.addView(icon, new LinearLayout.LayoutParams(s, s));
            TextView name = text(h > dp(70) ? 13 : 11, 0xE6FFFFFF, medium);
            name.setText(a.label);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(6), dp(4), dp(6), 0);
            col.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return tile;
    }

    // ---- top 10 tabs -------------------------------------------------------------------------------------

    private void buildTabs() {
        tabPkgs.clear();
        Map<String, List<Feed.Entry>> tabs = feed.tabs();
        for (String pkg : drawer) if (tabs.containsKey(pkg)) tabPkgs.add(pkg);
        boolean any = !tabPkgs.isEmpty();
        tabsLabel.setVisibility(any ? View.VISIBLE : View.GONE);
        tabStrip.setVisibility(any ? View.VISIBLE : View.GONE);
        tabRow.setVisibility(any ? View.VISIBLE : View.GONE);
        boolean hadFocus = tabStrip.hasFocus();
        tabStrip.removeAllViews();
        if (!any) return;
        if (!tabPkgs.contains(selectedTab)) selectedTab = tabPkgs.get(0);
        for (int i = 0; i < tabPkgs.size(); i++) {
            String pkg = tabPkgs.get(i);
            App a = apps.get(pkg);
            LinearLayout pill = row();
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setFocusable(true);
            pill.setPadding(dp(12), dp(7), dp(16), dp(7));
            pill.setForeground(ring(dp(20)));
            ImageView icon = new ImageView(act);
            icon.setImageDrawable(a == null ? null : a.icon);
            icon.setClipToOutline(true);
            icon.setBackground(rounded(0x00000000, dp(5)));
            pill.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView name = text(14, Color.WHITE, medium);
            name.setText(a == null ? pkg : a.label);
            LinearLayout.LayoutParams nlp = wrap();
            nlp.leftMargin = dp(8);
            pill.addView(name, nlp);
            styleTab(pill, pkg.equals(selectedTab));
            final int index = i;
            pill.setOnFocusChangeListener((v, has) -> {
                lift(v, has, 1.06f);
                if (has && !pkg.equals(selectedTab)) {
                    selectedTab = pkg;
                    lastTabRow = 0;
                    for (int k = 0; k < tabStrip.getChildCount(); k++) styleTab((LinearLayout) tabStrip.getChildAt(k), k == index);
                    bindTabRow();
                }
            });
            pill.setOnClickListener(v -> focusSection(S_TAB_ROW, 1));
            pill.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return focusSection(S_TABS - 1, -1);
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusSection(S_TAB_ROW, 1);
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) return index == 0;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return index == tabPkgs.size() - 1;
                return false;
            });
            LinearLayout.LayoutParams lp = wrap();
            if (i > 0) lp.leftMargin = dp(10);
            tabStrip.addView(pill, lp);
        }
        bindTabRow();
        if (hadFocus) focusSection(S_TABS, 1);
    }

    private void styleTab(LinearLayout pill, boolean selected) {
        pill.setBackground(rounded(selected ? 0xF2FFFFFF : 0x1FFFFFFF, dp(20)));
        ((TextView) pill.getChildAt(1)).setTextColor(selected ? 0xFF0B0D12 : 0xD9FFFFFF);
    }

    private void bindTabRow() {
        List<Feed.Entry> list = feed.tabs().get(selectedTab);
        tabRow.setAdapter(new Tiles(list == null ? Collections.emptyList() : list, S_TAB_ROW, true));
    }

    // ---- poster rows --------------------------------------------------------------------------------------

    private RecyclerView row(int section) {
        RecyclerView rv = new RecyclerView(act);
        rv.setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false));
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setPadding(dp(8), dp(10), dp(8), dp(10));
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setItemAnimator(null);
        rv.setTag(section);
        return rv;
    }

    private final class Tiles extends RecyclerView.Adapter<Tiles.Holder> {
        private final List<Feed.Entry> list;
        private final int section;
        private final boolean ranked;

        Tiles(List<Feed.Entry> list, int section, boolean ranked) {
            this.list = list;
            this.section = section;
            this.ranked = ranked;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView art = new ImageView(act);
            final View shade = new View(act);
            final TextView title = text(15, Color.WHITE, medium);
            final TextView rank = text(30, Color.WHITE, medium);
            final LinearLayout tag = row();
            final ImageView badge = new ImageView(act);
            final View progress = new View(act);
            final View track = new View(act);
            String source = "";

            Holder(FrameLayout card) {
                super(card);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout card = new FrameLayout(act);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(TILE_W), dp(TILE_H));
            lp.rightMargin = dp(18);
            card.setLayoutParams(lp);
            card.setBackground(rounded(0xFF1C2130, dp(14)));
            card.setForeground(ring(dp(14)));
            card.setClipToOutline(true);
            card.setFocusable(true);
            Holder h = new Holder(card);
            h.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(h.art, match());
            h.shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0x00000000, 0x00000000, 0xE6000000}));
            card.addView(h.shade, match());
            h.title.setMaxLines(2);
            h.title.setEllipsize(TextUtils.TruncateAt.END);
            h.title.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            h.title.setPadding(dp(12), 0, dp(12), dp(12));
            card.addView(h.title, tl);
            h.rank.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
            h.rank.setIncludeFontPadding(false);
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            rl.leftMargin = dp(12);
            rl.topMargin = dp(8);
            card.addView(h.rank, rl);
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            gl.topMargin = dp(8);
            gl.rightMargin = dp(8);
            card.addView(h.tag, gl);
            h.badge.setClipToOutline(true);
            h.badge.setBackground(rounded(0x00000000, dp(4)));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM | Gravity.END);
            bl.rightMargin = dp(10);
            bl.bottomMargin = dp(12);
            card.addView(h.badge, bl);
            h.track.setBackgroundColor(0x40FFFFFF);
            card.addView(h.track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM));
            h.progress.setBackgroundColor(0xFFE50914);
            card.addView(h.progress, new FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM | Gravity.START));

            card.setOnFocusChangeListener((v, has) -> {
                lift(v, has, 1.08f);
                int pos = h.getBindingAdapterPosition();
                if (has && pos != RecyclerView.NO_POSITION) {
                    if (section == S_CONTINUE) lastContinue = pos;
                    else lastTabRow = pos;
                }
            });
            card.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                Feed.Entry e = list.get(pos);
                if (!Feed.open(act, e)) Toast.makeText(act, act.getString(R.string.cant_open, e.title), Toast.LENGTH_SHORT).show();
            });
            card.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = h.getBindingAdapterPosition();
                if (key == KeyEvent.KEYCODE_DPAD_UP) return focusSection(section - 1, -1);
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusSection(section + 1, 1);
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) return pos == 0;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return pos == list.size() - 1;
                return false;
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Feed.Entry e = list.get(position);
            h.itemView.setContentDescription(e.title);
            h.art.setImageDrawable(null);
            h.title.setText(e.title);
            h.title.setVisibility(View.VISIBLE);
            h.shade.setVisibility(View.VISIBLE);
            h.rank.setVisibility(ranked ? View.VISIBLE : View.GONE);
            h.rank.setText(String.valueOf(position + 1));
            h.tag.removeAllViews();
            if (!e.rating.isEmpty()) imdbTag(h.tag, e.rating, true);
            App a = apps.get(e.pkg);
            h.badge.setImageDrawable(a == null ? null : a.icon);
            h.badge.setVisibility(section == S_CONTINUE && a != null ? View.VISIBLE : View.GONE);
            boolean showProgress = e.progress > 0;
            h.track.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams plp = h.progress.getLayoutParams();
            plp.width = showProgress ? dp(TILE_W) * e.progress / 100 : 0;
            h.progress.setLayoutParams(plp);
            h.source = e.poster;
            String want = e.poster;
            feed.loadArt(want, dp(TILE_W), b -> {
                if (b == null || !want.equals(h.source)) return;
                h.art.setImageBitmap(b);
                // Apps' posters carry their own title; TMDB backdrops do not.
                if (!e.fromTmdb()) {
                    h.title.setVisibility(View.GONE);
                    h.shade.setVisibility(ranked ? View.VISIBLE : View.GONE);
                }
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    // ---- navigation ---------------------------------------------------------------------------------------

    /** Focus the nearest shown section from {@code section} in direction {@code dir} (+1 down, -1 up). */
    private boolean focusSection(int section, int dir) {
        for (int s = section; s >= S_DRAWER && s <= S_APPS; s += dir) {
            View v = sections[s];
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            switch (s) {
                case S_DRAWER:
                    if (drawerBand.getChildCount() == 0) continue;
                    focusDrawer();
                    return true;
                case S_CONTINUE:
                    if (focusRowAt(continueRow, lastContinue)) return true;
                    continue;
                case S_TABS:
                    int i = tabPkgs.indexOf(selectedTab);
                    if (i >= 0 && i < tabStrip.getChildCount()) {
                        tabStrip.getChildAt(i).requestFocus();
                        return true;
                    }
                    continue;
                case S_TAB_ROW:
                    if (focusRowAt(tabRow, lastTabRow)) return true;
                    continue;
                case S_APPS:
                    if (appsGrid.getAdapter() != null && appsGrid.getAdapter().getItemCount() > 0 && focusGridCell(0)) return true;
                    continue;
                default:
                    break;
            }
        }
        return true;   // nothing that way: stay
    }

    private boolean focusRowAt(RecyclerView rv, int pos) {
        if (rv.getAdapter() == null || rv.getAdapter().getItemCount() == 0) return false;
        int p = Math.max(0, Math.min(pos, rv.getAdapter().getItemCount() - 1));
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(p);
        if (vh != null) {
            vh.itemView.requestFocus();
            return true;
        }
        rv.scrollToPosition(p);
        rv.post(() -> {
            RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(p);
            if (h != null) h.itemView.requestFocus();
        });
        return true;
    }

    /** Where the page scrolls for a focused view: the top for the drawer (the header in full), else its section's name. */
    private int scrollTarget(View focused) {
        rect.set(0, 0, focused.getWidth(), focused.getHeight());
        try {
            scroll.offsetDescendantRectToMyCoords(focused, rect);
        } catch (IllegalArgumentException notOurs) {
            return scroll.getScrollY();
        }
        int y = rect.top + scroll.getScrollY();
        if (y < continueLabel.getTop() || isIn(focused, drawerBand)) return 0;
        if (isIn(focused, appsGrid)) {
            return Math.max(appsLabel.getTop() - dp(12), y - dp(150));
        }
        View label = isIn(focused, continueRow) ? continueLabel : tabsLabel;
        return Math.max(0, label.getTop() - dp(12));
    }

    private static boolean isIn(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == parent) return true;
        }
        return false;
    }

    /** The page: it scrolls by section, smoothly, instead of just far enough to show the focused tile. */
    private final class Scroll extends ScrollView {
        Scroll(Context ctx, AttributeSet attrs) {
            super(ctx, attrs);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            return 0;
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            smoothScrollTo(0, scrollTarget(focused));
        }
    }

    // ---- header -------------------------------------------------------------------------------------------

    private void nextHero() {
        handler.removeCallbacks(turn);
        if (heroes.isEmpty()) {
            heroBlock.setAlpha(0f);
            return;
        }
        heroTurn = (heroTurn + 1) % heroes.size();
        Feed.Entry e = heroes.get(heroTurn);
        heroBlock.animate().cancel();
        heroBlock.animate().alpha(0f).setDuration(heroBlock.getAlpha() > 0 ? 180 : 0).withEndAction(() -> {
            fillHero(e);
            heroBlock.animate().alpha(1f).setDuration(420).start();
        }).start();
        String source = !e.backdrop.isEmpty() ? e.backdrop : e.poster;
        if (!source.equals(backdropSource)) {
            backdropSource = source;
            feed.loadArt(source, act.getResources().getDisplayMetrics().widthPixels, b -> showBackdrop(source, b));
        }
        for (int i = 0; i < heroDots.getChildCount(); i++) {
            heroDots.getChildAt(i).setBackground(rounded(i == heroTurn ? 0xF2FFFFFF : 0x4DFFFFFF, dp(3)));
            ViewGroup.LayoutParams lp = heroDots.getChildAt(i).getLayoutParams();
            lp.width = i == heroTurn ? dp(18) : dp(6);
            heroDots.getChildAt(i).setLayoutParams(lp);
        }
        if (heroes.size() > 1) handler.postDelayed(turn, TURN_MS);
    }

    private void fillHero(Feed.Entry e) {
        App a = apps.get(e.pkg);
        heroLogo.setImageDrawable(a == null ? null : a.icon);
        heroLogo.setVisibility(a == null ? View.GONE : View.VISIBLE);
        String app = a == null ? "" : a.label;
        heroKicker.setText(app.isEmpty() ? act.getString(R.string.feed_new) : act.getString(R.string.feed_new_on, app));
        heroTitle.setText(e.title);
        heroTag.removeAllViews();
        if (!e.rating.isEmpty()) imdbTag(heroTag, e.rating, false);
        List<String> meta = new ArrayList<>();
        if ("tv".equals(e.kind)) meta.add(act.getString(R.string.feed_series));
        else if ("movie".equals(e.kind)) meta.add(act.getString(R.string.feed_movie));
        if (!e.year.isEmpty()) meta.add(e.year);
        heroMeta.setText(TextUtils.join("  ·  ", meta));
        heroPlot.setText(e.overview);
        heroPlot.setVisibility(e.overview.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showBackdrop(String source, Bitmap b) {
        if (!source.equals(backdropSource)) return;
        float target = scroll.getScrollY() > dp(40) ? BACKDROP_SCROLLED : BACKDROP_TOP;
        backdrop.animate().cancel();
        if (b == null) {
            backdrop.animate().alpha(0f).setDuration(300).start();
            return;
        }
        backdrop.animate().alpha(0f).setDuration(backdrop.getAlpha() > 0 ? 260 : 0).withEndAction(() -> {
            backdrop.setImageBitmap(b);
            backdrop.animate().alpha(target).setDuration(700).start();
        }).start();
    }

    private void buildDots() {
        heroDots.removeAllViews();
        if (heroes.size() < 2) return;
        for (int i = 0; i < heroes.size(); i++) {
            View dot = new View(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            if (i > 0) lp.leftMargin = dp(6);
            heroDots.addView(dot, lp);
        }
    }

    // ---- pieces -------------------------------------------------------------------------------------------

    /** The IMDb tag: the yellow IMDb mark and the rating on dark glass, in one pill. */
    private void imdbTag(LinearLayout into, String rating, boolean small) {
        int h = dp(small ? 20 : 24);
        int r = dp(small ? 5 : 6);
        TextView mark = text(small ? 10 : 12, 0xFF000000, Typeface.create(medium, Typeface.BOLD));
        mark.setText(R.string.feed_imdb);
        mark.setGravity(Gravity.CENTER);
        mark.setPadding(dp(small ? 5 : 7), 0, dp(small ? 5 : 7), 0);
        GradientDrawable left = new GradientDrawable();
        left.setColor(IMDB_YELLOW);
        left.setCornerRadii(new float[] {r, r, 0, 0, 0, 0, r, r});
        mark.setBackground(left);
        into.addView(mark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h));
        TextView value = text(small ? 11 : 13, Color.WHITE, medium);
        value.setText(rating);
        value.setGravity(Gravity.CENTER);
        value.setPadding(dp(small ? 6 : 8), 0, dp(small ? 6 : 8), 0);
        GradientDrawable right = new GradientDrawable();
        right.setColor(0xB3101218);
        right.setStroke(Math.max(1, dp(1) / 2), 0x66F5C518);
        right.setCornerRadii(new float[] {0, 0, r, r, r, r, 0, 0});
        value.setBackground(right);
        into.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h));
    }

    private void menu(String title, String[] labels, Runnable[] actions) {
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = column();
        box.setPadding(dp(18), dp(18), dp(18), dp(14));
        GradientDrawable bg = rounded(0xF2171A22, dp(20));
        bg.setStroke(Math.max(1, dp(1) / 2), 0x26FFFFFF);
        box.setBackground(bg);
        TextView head = text(13, 0x99FFFFFF, medium);
        head.setText(title);
        head.setAllCaps(true);
        head.setLetterSpacing(0.12f);
        head.setPadding(dp(10), 0, dp(10), dp(10));
        box.addView(head, wrap());
        for (int i = 0; i < labels.length; i++) {
            TextView item = text(17, Color.WHITE, regular);
            item.setText(labels[i]);
            item.setFocusable(true);
            item.setPadding(dp(14), dp(11), dp(14), dp(11));
            item.setBackground(rounded(0x00000000, dp(12)));
            item.setOnFocusChangeListener((v, has) -> {
                v.setBackground(rounded(has ? 0xF2FFFFFF : 0x00000000, dp(12)));
                ((TextView) v).setTextColor(has ? 0xFF0B0D12 : Color.WHITE);
            });
            Runnable run = actions[i];
            item.setOnClickListener(v -> {
                dialog.dismiss();
                run.run();
            });
            box.addView(item, new LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        dialog.setContentView(box);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        if (box.getChildCount() > 1) box.getChildAt(1).requestFocus();
    }

    private void launch(String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(act, act.getString(R.string.cant_open, pkg), Toast.LENGTH_SHORT).show();
        }
    }

    private void appInfo(String pkg) {
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // No app info screen on this TV.
        }
    }

    /** The focused tile grows and gets a white ring; the others sit flat. */
    private void lift(View v, boolean has, float scale) {
        float s = has ? scale : 1f;
        v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(10) : 0).setDuration(150).start();
        Drawable fg = v.getForeground();
        if (fg instanceof GradientDrawable) ((GradientDrawable) fg).setStroke(has ? dp(3) : 0, Color.WHITE);
    }

    private GradientDrawable ring(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(radius);
        return g;
    }

    private static GradientDrawable rounded(int colour, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView label(String s) {
        TextView t = text(15, 0xCCFFFFFF, medium);
        t.setText(s);
        t.setLetterSpacing(0.04f);
        return t;
    }

    private LinearLayout.LayoutParams labelParams() {
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(26);
        lp.leftMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams rowParams(int heightDp) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
    }

    private TextView text(int sp, int colour, Typeface face) {
        TextView t = new TextView(act);
        t.setTextSize(sp);
        t.setTextColor(colour);
        if (face != null) t.setTypeface(face);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private Typeface font(int id) {
        try {
            return act.getResources().getFont(id);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(int v) {
        return Math.round(v * d);
    }
}
