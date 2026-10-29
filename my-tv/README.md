# My TV: Sony BRAVIA BF1 (Google TV 12)

The setup on this fork's `custom-home` branch: the CleanYourTV launcher with poster rows, the Feed add-on and the
Original Home card, on top of the repo's tested list for this model, `examples/sony-bravia-bf1/debloat.txt`.

## Preview on the Mac first

The emulator `bravia_preview` (Google TV 12) runs the same builds with sample rows:

```bash
~/.cleantv-toolchain/sdk/emulator/emulator -avd bravia_preview -no-boot-anim
```

Demo rows on or off (off reads the apps' real rows):

```bash
~/.cleantv-toolchain/sdk/platform-tools/adb shell am start -n io.github.rohan9405.feed/.FeedActivity --ez demo true
```

## On the TV

Build tools: `export JAVA_HOME=~/.cleantv-toolchain/jdk/Contents/Home ANDROID_HOME=~/.cleantv-toolchain/sdk`.
On this TV Google TV keeps the Home button while its launcher is enabled, even with the setup wizard off, so the
launcher is disabled after the hand-over (the examples list's steps) and the Original Home card can't open it;
`tools/switch-home.sh stock` brings it back.

```bash
TV_HOST=<tv ip> STOCK_LAUNCHER=none launcher/build-and-install.sh
TV_HOST=<tv ip> tools/venv/bin/python -I tools/manage.py install tv-feed
TV_HOST=<tv ip> tools/venv/bin/python -I tools/manage.py install original-home
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm grant io.github.rohan9405.feed android.permission.READ_TV_LISTINGS'
TV_HOST=<tv ip> tools/theme.sh my-tv/theme
```

IMDb ratings need a free OMDb key (omdbapi.com/apikey.aspx). It is kept on this Mac in
`~/.cleantv-private/omdb_key`, outside the repo, and sent to the TV over ADB; nothing is typed with the remote:

```bash
my-tv/api-keys.sh save omdb                   # once each: paste the key (hidden), it is checked
my-tv/api-keys.sh save tmdb
TV_HOST=<tv ip> my-tv/api-keys.sh send       # after installing the Feed add-on (--emulator for the preview)
```

## Home, top to bottom

| Part | What it is |
|---|---|
| Header | New in the last 10 days on the drawer's services (TMDB), best IMDb rating first: 5 per app, up to 20, turning every 8 s. YouTube is left out. |
| Drawer | Your favourite apps on one line, shrinking to fit. Hold OK on one: Open, Move, Remove from favourites, App info. Hold OK on any app under Apps: Add to favourites. |
| Continue watching | From the apps that publish it (Prime Video, JioHotstar, Netflix, Airtel, Apple TV). |
| Top 10 | A tab per drawer app; Left and Right change the row. Netflix and SonyLIV publish nothing, so theirs come from TMDB's most popular in India. |
| Apps | Everything else. |

Ratings come through the StreamRatings shared cache (the browser extension's), refreshed 3 to 30 days by age.
The Bluetooth button next to Wi-Fi opens the Bluetooth settings.

## What was applied (29 Oct 2026)

- `my-tv/debloat.txt` with `tools/disable.sh`: the BF1 list, all four preloaded apps off, voice search kept.
  Google's ambient screensaver was turned off at first, then back on: on this TV it still shows its photos, weather
  and clock with the Google TV launcher disabled, so it stays the screensaver.
- By hand, after the launcher was installed: setup wizard and Google TV launcher disabled, Home set to CleanYourTV.
- Private DNS `dns.adguard-dns.com` (it was automatic). It does not block YouTube ads.

## Switching back

| What | How |
|---|---|
| Make the TV's own home the default | `TV_HOST=<tv ip> tools/switch-home.sh stock` (`clean` switches back) |
| Undo everything: apps, Google TV home, screensaver, DNS | `TV_HOST=<tv ip> my-tv/restore.sh` |
