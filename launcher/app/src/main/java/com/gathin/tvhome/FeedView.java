package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The poster rows above the dock (feed_package): a title line for the focused or featured tile, then Continue
 * watching and the apps' rows, and a poster backdrop behind the clock that cycles through the featured tiles while
 * the rows are not in use.
 *
 * <p>Three states, set by MainActivity from where the focus is: {@link #focusRow} (focus in the rows: two rows
 * shown), {@link #peek} (focus on the dock: the title line and one row) and {@link #collapse} (focus in the grid
 * below the dock: rows gone, so the apps have the room).
 */
final class FeedView {
    interface Host {
        /** A tile took focus: hide the grid's glass highlight. */
        void onFeedFocused();

        /** Up from the first row: to the top bar. */
        void onFeedUp();

        /** Down from the last row: to the dock. */
        void onFeedDown();
    }

    private static final long TURN_MS = 9000;
    private static final float BACKDROP_ALPHA = 0.6f;
    private static final int IMDB_YELLOW = 0xFFF5C518;

    private final Activity act;
    private final Feed feed;
    private final Host host;
    private final Handler handler;
    private final float d;
    private final LinearLayout box;
    private final TextView title;
    private final TextView meta;
    private final LinearLayout rowsBox;
    private final ImageView backdrop;
    private final View scrim;
    private final List<RecyclerView> rowViews = new ArrayList<>();
    private final List<Integer> rowPositions = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();
    private List<Feed.Row> rows = new ArrayList<>();
    private int current;
    private boolean inRows;
    private boolean collapsed;
    private int heroTurn;
    private String backdropSource = "";
    private final Runnable turn = this::nextHero;

    FeedView(Activity act, Feed feed, Host host, Handler handler, LinearLayout box, ImageView backdrop, View scrim) {
        this.act = act;
        this.feed = feed;
        this.host = host;
        this.handler = handler;
        this.box = box;
        this.backdrop = backdrop;
        this.scrim = scrim;
        d = act.getResources().getDisplayMetrics().density;

        title = new TextView(act);
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        meta = new TextView(act);
        meta.setTextColor(0xCCFFFFFF);
        meta.setTextSize(14);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(4);
        box.addView(meta, mlp);

        rowsBox = new LinearLayout(act);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setClipChildren(false);
        rowsBox.setClipToPadding(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        box.addView(rowsBox, rlp);

        // Darker at the left and the bottom, where the text and the dock sit over the poster.
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF0000000, 0x99000000, 0x33000000}));
    }

    boolean hasRows() {
        return !rows.isEmpty();
    }

    boolean inRows() {
        return inRows;
    }

    /** The add-on published new rows. */
    void update() {
        String focusedRow = inRows && current < rows.size() ? rows.get(current).title : null;
        rows = feed.rows();
        rowsBox.removeAllViews();
        rowViews.clear();
        rowPositions.clear();
        for (Feed.Row r : rows) {
            rowsBox.addView(buildRow(r, rowViews.size()));
            rowPositions.add(0);
        }
        current = 0;
        for (int i = 0; focusedRow != null && i < rows.size(); i++) if (rows.get(i).title.equals(focusedRow)) current = i;
        box.setVisibility(rows.isEmpty() && feed.hero().isEmpty() ? View.GONE : box.getVisibility());
        if (rows.isEmpty() && feed.hero().isEmpty()) {
            backdrop.setVisibility(View.GONE);
            scrim.setVisibility(View.GONE);
            return;
        }
        backdrop.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        showRows();
        if (!inRows) nextHero();
    }

    void start() {
        handler.removeCallbacks(turn);
        if (!inRows) handler.postDelayed(turn, TURN_MS);
    }

    void stop() {
        handler.removeCallbacks(turn);
    }

    /** Focus on the dock: the title line and the current row, the featured posters turning behind. True when the layout changed. */
    boolean peek() {
        int want = hasRows() || !feed.hero().isEmpty() ? View.VISIBLE : View.GONE;
        boolean changed = inRows || box.getVisibility() != want;
        inRows = false;
        collapsed = false;
        box.setVisibility(want);
        showRows();
        handler.removeCallbacks(turn);
        handler.postDelayed(turn, TURN_MS);
        return changed;
    }

    /** Focus in the grid below the dock: the apps get the room. True when the layout changed. */
    boolean collapse() {
        if (collapsed) return false;
        collapsed = true;
        inRows = false;
        boolean changed = box.getVisibility() != View.GONE;
        box.setVisibility(View.GONE);
        return changed;
    }

    /** Focus into row {@code index} (clamped), at the tile it was last on. */
    void focusRow(int index) {
        if (rows.isEmpty()) return;
        current = Math.max(0, Math.min(index, rows.size() - 1));
        inRows = true;
        collapsed = false;
        box.setVisibility(View.VISIBLE);
        handler.removeCallbacks(turn);
        showRows();
        RecyclerView rv = rowViews.get(current);
        int pos = Math.min(rowPositions.get(current), Math.max(0, rows.get(current).entries.size() - 1));
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.requestFocus();
        } else {
            rv.scrollToPosition(pos);
            rv.post(() -> {
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.requestFocus();
            });
        }
    }

    int currentRow() {
        return current;
    }

    /** Two rows while the focus is in them, one while it is on the dock: the dock keeps its room on a 540 dp screen. */
    private void showRows() {
        int shown = inRows ? 2 : 1;
        for (int i = 0; i < rowsBox.getChildCount(); i++) {
            rowsBox.getChildAt(i).setVisibility(i >= current && i < current + shown ? View.VISIBLE : View.GONE);
        }
    }

    private View buildRow(Feed.Row r, int index) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        TextView label = new TextView(act);
        label.setText(r.title);
        label.setAllCaps(true);
        label.setLetterSpacing(0.14f);
        label.setTextSize(12);
        label.setTextColor(0xB3FFFFFF);
        label.setSingleLine(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        llp.bottomMargin = dp(6);
        row.addView(label, llp);

        RecyclerView rv = new RecyclerView(act);
        rv.setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false));
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setPadding(dp(4), dp(6), dp(4), dp(6));
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setItemAnimator(null);
        rv.setAdapter(new Tiles(r.entries, index));
        row.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TILE_H + 12)));
        rowViews.add(rv);
        return row;
    }

    private static final int TILE_W = 200;
    private static final int TILE_H = 112;

    private final class Tiles extends RecyclerView.Adapter<Tiles.Holder> {
        private final List<Feed.Entry> entries;
        private final int rowIndex;

        Tiles(List<Feed.Entry> entries, int rowIndex) {
            this.entries = entries;
            this.rowIndex = rowIndex;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView art;
            final TextView name;
            final TextView rating;
            final View progress;
            String source = "";

            Holder(FrameLayout v, ImageView art, TextView name, TextView rating, View progress) {
                super(v);
                this.art = art;
                this.name = name;
                this.rating = rating;
                this.progress = progress;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout card = new FrameLayout(act);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(TILE_W), dp(TILE_H));
            lp.rightMargin = dp(14);
            card.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1E2230);
            bg.setCornerRadius(dp(10));
            card.setBackground(bg);
            card.setClipToOutline(true);
            // The outline is drawn over the poster: on the background it would sit under the art.
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(Color.TRANSPARENT);
            ring.setCornerRadius(dp(10));
            card.setForeground(ring);
            card.setFocusable(true);

            ImageView art = new ImageView(act);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView name = new TextView(act);
            name.setTextColor(Color.WHITE);
            name.setTextSize(13);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(10), dp(10), dp(10), dp(10));
            name.setGravity(Gravity.BOTTOM | Gravity.START);
            card.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView rating = new TextView(act);
            rating.setTextColor(Color.BLACK);
            rating.setTextSize(11);
            rating.setTypeface(Typeface.DEFAULT_BOLD);
            rating.setPadding(dp(6), dp(1), dp(6), dp(1));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(IMDB_YELLOW);
            pill.setCornerRadius(dp(4));
            rating.setBackground(pill);
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            rp.topMargin = dp(6);
            rp.rightMargin = dp(6);
            card.addView(rating, rp);

            View progress = new View(act);
            progress.setBackgroundColor(0xFFE50914);
            card.addView(progress, new FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM | Gravity.START));

            Holder h = new Holder(card, art, name, rating, progress);
            card.setOnFocusChangeListener((v, has) -> {
                float s = has ? 1.1f : 1f;
                v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(8) : 0).setDuration(140).start();
                ((GradientDrawable) v.getForeground()).setStroke(has ? dp(3) : 0, Color.WHITE);
                int pos = h.getBindingAdapterPosition();
                if (has && pos != RecyclerView.NO_POSITION) {
                    current = rowIndex;
                    rowPositions.set(rowIndex, pos);
                    inRows = true;
                    showEntry(entries.get(pos), true);
                    host.onFeedFocused();
                }
            });
            card.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                Feed.Entry e = entries.get(pos);
                if (!Feed.open(act, e)) {
                    Toast.makeText(act, act.getString(R.string.cant_open, e.title), Toast.LENGTH_SHORT).show();
                }
            });
            card.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = h.getBindingAdapterPosition();
                switch (key) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (rowIndex == 0) host.onFeedUp();
                        else focusRow(rowIndex - 1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (rowIndex >= rows.size() - 1) host.onFeedDown();
                        else focusRow(rowIndex + 1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return pos == 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos == entries.size() - 1;
                    default:
                        return false;
                }
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Feed.Entry e = entries.get(position);
            h.name.setText(e.title);
            h.name.setVisibility(View.VISIBLE);
            h.art.setImageDrawable(null);
            h.rating.setVisibility(e.rating == null || e.rating.isEmpty() ? View.GONE : View.VISIBLE);
            h.rating.setText(act.getString(R.string.feed_rating, e.rating));
            ViewGroup.LayoutParams plp = h.progress.getLayoutParams();
            plp.width = e.progress > 0 ? dp(TILE_W) * e.progress / 100 : 0;
            h.progress.setLayoutParams(plp);
            h.source = e.poster == null ? "" : e.poster;
            h.itemView.setContentDescription(e.title);
            String want = h.source;
            feed.loadArt(want, dp(TILE_W), b -> {
                if (b == null || !want.equals(h.source)) return;
                h.art.setImageBitmap(b);
                // The poster carries its own title; the text stays for tiles with no art.
                h.name.setVisibility(View.GONE);
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }

    /** The title line and, when asked, the backdrop, for one tile. */
    private void showEntry(Feed.Entry e, boolean withBackdrop) {
        title.setText(e.title);
        List<String> parts = new ArrayList<>();
        parts.add(appLabel(e.pkg));
        parts.add(e.subtitle);
        if (e.rating != null && !e.rating.isEmpty()) parts.add(act.getString(R.string.feed_rating_long, e.rating));
        if (e.progress > 0) parts.add(act.getString(R.string.feed_progress, e.progress));
        StringBuilder m = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (m.length() > 0) m.append("  ·  ");
            m.append(p);
        }
        meta.setText(m);
        if (!withBackdrop) return;
        String source = e.backdrop != null && !e.backdrop.isEmpty() ? e.backdrop : e.poster;
        if (source == null || source.equals(backdropSource)) return;
        backdropSource = source;
        feed.loadArt(source, act.getResources().getDisplayMetrics().widthPixels / 2, b -> {
            if (b == null || !source.equals(backdropSource)) return;
            backdrop.animate().cancel();
            backdrop.animate().alpha(0f).setDuration(backdrop.getAlpha() > 0 ? 220 : 0).withEndAction(() -> {
                backdrop.setImageBitmap(b);
                backdrop.animate().alpha(BACKDROP_ALPHA).setDuration(500).start();
            }).start();
        });
    }

    /** The next featured tile, every {@value #TURN_MS} ms while the rows are not in use. */
    private void nextHero() {
        handler.removeCallbacks(turn);
        List<Feed.Entry> hero = feed.hero();
        if (hero.isEmpty() || inRows) return;
        heroTurn = (heroTurn + 1) % hero.size();
        showEntry(hero.get(heroTurn), true);
        if (hero.size() > 1) handler.postDelayed(turn, TURN_MS);
    }

    private String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String l = labels.get(pkg);
        if (l != null) return l;
        PackageManager pm = act.getPackageManager();
        try {
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            l = "";
        }
        labels.put(pkg, l);
        return l;
    }

    private int dp(int v) {
        return Math.round(v * d);
    }
}
