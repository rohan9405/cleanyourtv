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
Leave the stock launcher enabled (`STOCK_LAUNCHER=none`) so the Original Home card can open it.

```bash
TV_HOST=<tv ip> STOCK_LAUNCHER=none launcher/build-and-install.sh
TV_HOST=<tv ip> tools/venv/bin/python -I tools/manage.py install tv-feed
TV_HOST=<tv ip> tools/venv/bin/python -I tools/manage.py install original-home
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm grant io.github.rohan9405.feed android.permission.READ_TV_LISTINGS'
TV_HOST=<tv ip> tools/theme.sh my-tv/theme
```

IMDb ratings need a free OMDb key (omdbapi.com/apikey.aspx); type it on the Feed screen, or:

```bash
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'am start -n io.github.rohan9405.feed/.FeedActivity --es omdb_key <key>'
```

## Switching back

| What | How |
|---|---|
| Look at the TV's own home once | The **Original Home** card. Home on the remote comes back here. |
| Make the TV's own home the default | `TV_HOST=<tv ip> tools/switch-home.sh stock` (`clean` switches back) |
| Undo everything, debloat included | `TV_HOST=<tv ip> tools/restore.sh examples/sony-bravia-bf1/debloat.txt com.google.android.apps.tv.launcherx` |
