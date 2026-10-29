# Kiran's TV Launcher

A clean, ad-free Home screen for the Xiaomi TV (`MiTV-MOOR2`, Android TV 11), plus a
safe debloat. Everything here is written from scratch and lives only in this repo.

- **Launcher**: Apple TV-style. Pitch black, a 5-app dock in a rounded band, every other
  app in a grid below it, one glass highlight that springs between tiles, a big thin
  clock, and Inputs / Wi-Fi / Settings pills. No ads, no recommendation rows, no network
  polling, no blur or video: built for a TV with about 1 GB of RAM.
- **Debloat**: Xiaomi telemetry, PatchWall and ad services are *disabled*, never
  uninstalled. Everything is reversible with one command.

```
launcher/                 Android app (Java, no libraries)
  app/src/main/assets/config.json   dock apps, columns, greeting, Live TV app
tools/tv.py               the only tool you run; every safety check is inside it
tools/packages.json       blocklist (never disable) and keep-list (your apps)
.github/workflows/        builds the APK on GitHub on every push
```

## 1. One-time setup on your computer

1. Install **Android platform-tools** (it contains `adb`) and **Python 3**.
   - Windows: unzip platform-tools to `C:\platform-tools` and add it to PATH.
   - Mac: `brew install android-platform-tools`
2. Clone this repo and open a terminal in it.

## 2. One-time setup on the TV

1. Settings → Device Preferences → About → click **Build** 7 times ("You are now a developer").
2. Settings → Device Preferences → **Developer options** → turn on **USB debugging**
   If there is a separate **ADB debugging / Network debugging** switch, turn that on too.
   If `connect` still fails, use **Wireless debugging** → *Pair device with pairing code*
   and send the code and port; `connect` then works on the port it shows.
3. Note the TV's IP: Settings → Network & Internet → your Wi-Fi.

> Some TVs cut power to their USB ports while USB debugging is on. Turn it **off** again when done (step 7).

## 3. Connect and back up (always first)

```bash
python tools/tv.py connect <tv-ip>     # accept "Allow USB debugging?" on the TV
python tools/tv.py info
python tools/tv.py backup
```

## 4. Install the launcher

Download `kiran-tv-launcher.apk` from this repo's **Releases → latest** (GitHub builds it
on every push), then:

```bash
python tools/tv.py install kiran-tv-launcher.apk
python tools/tv.py set-home        # must print VERIFIED before the stock launcher is touched
python tools/tv.py reboot-check    # Home and ADB must come back after a reboot
```

## 5. Debloat, in small batches

```bash
python tools/tv.py list                        # sorted into categories, saved to reports/
python tools/tv.py describe <package>          # what it is, without ever starting it
python tools/tv.py disable <pkg1> <pkg2> ...   # max 5 at a time; screenshot after each batch
python tools/tv.py reboot-check                # once at the end: waits 15 s, reboots, compares
```

`disable` refuses, whatever you type: Android core packages, TV picture/HDMI/tuner
middleware, remote/Bluetooth/keyboard, Google Play, Google Assistant, your streaming
apps, and the current Home app until our launcher is verified. Chromecast, screen
mirroring and firmware-update packages need `--yes-asked`.

## 6. Optional: ad-blocking DNS

```bash
python tools/tv.py dns on      # AdGuard Private DNS
python tools/tv.py dns off     # undo (back to automatic)
```

It does **not** block YouTube ads. Skip it if your router already filters DNS (Pi-hole,
AdGuard Home, a VPN with domain rules): Private DNS bypasses the router.

## 7. Close network ADB when finished

Port 5555 is unencrypted; leaving it open lets anyone on your Wi-Fi control the TV.

1. Developer options → turn off **USB debugging**, **ADB/Network debugging** and
   **Wireless debugging** (whichever exist), and **Revoke USB debugging authorisations**.
2. Restart the TV, then: `python tools/tv.py closed <tv-ip>` must print **CLOSED**.
   A timeout ("UNKNOWN") is not proof; only "refused" is.

---

## Re-enable an app

```bash
python tools/tv.py restore <package>        # one app
python tools/tv.py restore --all            # everything tv.py disabled
python tools/tv.py restore --all --home     # and give Home back to the stock launcher
```

On the TV itself: long-press OK on any tile → the app's system info page.
`state/disabled.txt` lists everything disabled.

## Change the dock order or apps

Edit the dock in `config.json` (each slot lists package names; the first installed wins),
then push it. No rebuild needed:

```bash
python tools/tv.py push-config launcher/app/src/main/assets/config.json
```

Settings in `config.json`:

| key | meaning |
|---|---|
| `columns` | tiles per row (3–7); also the dock size |
| `dock_apps` | dock slots, left to right |
| `hidden_apps` | packages to leave out of the grid |
| `greeting_name` | e.g. `"Kiran"` → "Good evening, Kiran" |
| `live_tv_package` | app the Inputs → Live TV item opens, if the default doesn't work |
| `input_labels` | rename inputs, e.g. `{"<input id>": "Set-top box"}` |
| `show_phone_apps` | also list phone-only apps that have no TV banner |
| `wallpaper_dim` | 0–1, how much a wallpaper is darkened |

Commit the edited file too, so the next APK build carries it.

## Change the wallpaper

Default is pitch black.

```bash
python tools/tv.py push-wallpaper photo.jpg
python tools/tv.py shell "rm /sdcard/Android/data/com.kiranpatil.tvlauncher/files/wallpaper.jpg"   # back to black
```

## Building the APK yourself (optional)

GitHub Actions builds it, so you never need to. Locally with JDK 17 + Android SDK:
`gradle -p launcher assembleDebug`. The signing key in `launcher/keystore/` is a
personal key for this app, so every build can update the installed one.

## Recovery

- **Home is black / launcher missing**: run `python tools/tv.py restore --all --home`
  (ADB keeps working without a Home screen).
- **A batch broke something**: `restore` the last batch's packages and reboot.
- **TV won't boot**: a factory reset from the TV's recovery menu re-enables every
  disabled package (the button combination varies by model; check Xiaomi support).
  `tools/packages.json` exists so this never has to happen.
