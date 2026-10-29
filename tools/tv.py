#!/usr/bin/env python3
"""
tv.py - one tool for everything this repo does to the TV, with the safety checks built in.

Needs only Python 3.8+ and `adb` on the PATH (Android platform-tools).
Works the same on Windows, macOS and Linux.

  python tools/tv.py connect 192.168.1.50      # once; remembers the TV
  python tools/tv.py pair <ip> <port> <code>    # only if the TV uses Wireless debugging
  python tools/tv.py info                       # model, Android version, Home app, network
  python tools/tv.py backup                     # ALWAYS before changing anything
  python tools/tv.py list                       # every package, sorted into categories
  python tools/tv.py describe <pkg>             # what a package contains, without starting it
  python tools/tv.py disable <pkg> [<pkg>...]   # at most 5 per batch, refuses dangerous ones
  python tools/tv.py restore [--all | <pkg>...] # re-enable what we disabled
  python tools/tv.py reboot-check               # wait, sync, reboot, compare disabled list
  python tools/tv.py install <apk>              # install the launcher
  python tools/tv.py set-home                   # make our launcher Home and verify it
  python tools/tv.py screenshot <out.png> [KEY...]  # press keys, then capture the screen
  python tools/tv.py key KEY [KEY...]           # e.g. DPAD_RIGHT HOME BACK
  python tools/tv.py push-config <config.json>  # change dock without a rebuild
  python tools/tv.py push-wallpaper <image>     # wallpaper.jpg or .png
  python tools/tv.py dns status|on|off          # optional ad-blocking Private DNS
  python tools/tv.py closed <ip> [port]         # prove network ADB is really closed
  python tools/tv.py shell "<one command>"

Environment: TV=<ip[:port]> overrides the remembered TV, ADB=<path to adb>.
"""

import argparse
import datetime as dt
import errno
import fnmatch
import json
import os
import socket
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")
STATE = os.path.join(ROOT, "state")
BACKUPS = os.path.join(ROOT, "backups")
SHOTS = os.path.join(ROOT, "shots")
REPORTS = os.path.join(ROOT, "reports")
DISABLED_LOG = os.path.join(STATE, "disabled.txt")
TV_FILE = os.path.join(STATE, "tv.txt")

OUR_PKG = "com.kiranpatil.tvlauncher"
OUR_HOME = OUR_PKG + "/.MainActivity"
OUR_FILES = "/sdcard/Android/data/%s/files" % OUR_PKG
MIN_SDK = 26
MAX_BATCH = 5
HOME_QUERY = "-a android.intent.action.MAIN -c android.intent.category.HOME"

# Opening one of these, even "just to look", can flip a factory-mode flag on some boards.
FACTORY_WORDS = ("factory", "fac.menu", "facmenu", ".fac.", "production", "com.at.")


# --------------------------------------------------------------------------- adb plumbing

def adb_bin():
    return os.environ.get("ADB", "adb")


def serial():
    tv = os.environ.get("TV")
    if not tv and os.path.isfile(TV_FILE):
        with open(TV_FILE) as f:
            tv = f.read().strip()
    if not tv:
        die("No TV set. Run:  python tools/tv.py connect <tv-ip>")
    if ":" not in tv:
        tv += ":" + os.environ.get("TV_PORT", "5555")
    return tv


def adb(*args, timeout=60, check=False, s=None):
    cmd = [adb_bin()]
    if s is not False:
        cmd += ["-s", s or serial()]
    cmd += list(args)
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    except FileNotFoundError:
        die("adb not found. Install Android platform-tools and put adb on the PATH (or set ADB=...).")
    except subprocess.TimeoutExpired:
        if check:
            die("adb timed out: %s" % " ".join(args))
        return 124, ""
    out = p.stdout.decode("utf-8", "replace")
    if check and p.returncode != 0:
        die("adb failed (%s): %s" % (" ".join(args), out.strip()))
    return p.returncode, out


def sh(command, timeout=60):
    """One shell command per adb call: chained commands can silently stop after the first."""
    _, out = adb("shell", command, timeout=timeout)
    return out.replace("\r\n", "\n").strip()


def die(msg, code=1):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(code)


def ts():
    return dt.datetime.now().strftime("%Y%m%d-%H%M%S")


def ensure(*dirs):
    for d in dirs:
        os.makedirs(d, exist_ok=True)


# --------------------------------------------------------------------------- package rules

def load_rules():
    with open(os.path.join(TOOLS, "packages.json")) as f:
        return json.load(f)


def match_group(pkg, groups):
    """Returns the group name whose pattern matches pkg, or None."""
    for name, patterns in groups.items():
        for pat in patterns:
            if fnmatch.fnmatchcase(pkg.lower(), pat.lower()):
                return name
    return None


def packages(flag=""):
    out = sh("pm list packages %s" % flag)
    return sorted(l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:"))


def current_home():
    out = sh("cmd package resolve-activity --brief %s" % HOME_QUERY)
    lines = [l.strip() for l in out.splitlines() if "/" in l]
    return lines[-1] if lines else ""


def home_candidates():
    out = sh("cmd package query-activities --brief %s" % HOME_QUERY)
    return [l.strip() for l in out.splitlines() if "/" in l and not l.strip().startswith("priority")]


def sdk_level():
    try:
        return int(sh("getprop ro.build.version.sdk"))
    except ValueError:
        return 0


def read_disabled_log():
    if not os.path.isfile(DISABLED_LOG):
        return []
    with open(DISABLED_LOG) as f:
        return [l.split("\t")[0].strip() for l in f if l.strip() and not l.startswith("#")]


def write_disabled_log(pkgs):
    ensure(STATE)
    with open(DISABLED_LOG, "w") as f:
        f.write("# packages disabled by tv.py; 'python tools/tv.py restore --all' re-enables them\n")
        for p in pkgs:
            f.write(p + "\n")


# --------------------------------------------------------------------------- commands

def cmd_connect(a):
    host = a.ip if ":" in a.ip else "%s:%s" % (a.ip, a.port)
    print("Connecting to %s ..." % host)
    print(">> Watch the TV now: an 'Allow USB debugging?' prompt may appear.")
    print(">> Tick 'Always allow from this computer' and press OK.")
    _, out = adb("connect", host, s=False, timeout=30)
    print(out.strip())
    for _ in range(30):
        _, state = adb("-s", host, "get-state", s=False, timeout=10)
        if state.strip() == "device":
            ensure(STATE)
            with open(TV_FILE, "w") as f:
                f.write(host)
            print("Connected. Remembered %s for the next commands." % host)
            return
        time.sleep(2)
    die("The TV did not authorise this computer. Check the prompt on the TV and run connect again.\n"
        "If port 5555 is closed, turn on Settings > Device Preferences > About > Build (7 taps), then\n"
        "Developer options > USB debugging / ADB debugging, or Wireless debugging (see README).")


def cmd_pair(a):
    """Wireless debugging: Developer options > Wireless debugging > Pair device with pairing code."""
    _, out = adb("pair", "%s:%d" % (a.ip, a.port), a.code, s=False, timeout=30)
    print(out.strip())
    if "Successfully paired" not in out:
        die("Pairing failed. The code and pairing port change every time the dialog opens.")
    print("Paired. Now connect to the port shown under Wireless debugging > IP address & Port:\n"
          "  python tools/tv.py connect %s --port <that port>" % a.ip)


def cmd_info(a):
    props = {k: sh("getprop " + k) for k in (
        "ro.product.model", "ro.product.manufacturer", "ro.build.version.release",
        "ro.build.version.sdk", "ro.build.fingerprint", "ro.adb.secure", "ro.debuggable")}
    for k, v in props.items():
        print("%-28s %s" % (k, v))
    print("%-28s %s" % ("home (current)", current_home()))
    print("%-28s %s" % ("home candidates", ", ".join(home_candidates())))
    print("%-28s %s" % ("wm size", " | ".join(sh("wm size").splitlines())))
    print("%-28s %s" % ("wm density", " | ".join(sh("wm density").splitlines())))
    transports = sorted(set(w for w in sh("dumpsys connectivity").split() if w.startswith("Transports:") or w in ("WIFI", "ETHERNET")))
    print("%-28s %s" % ("network", " ".join(transports) or "?"))
    if props["ro.adb.secure"] in ("0", ""):
        print("\nWARNING: ro.adb.secure is %r. Anyone on your Wi-Fi can open a shell while network ADB is on."
              % props["ro.adb.secure"])
    sdk = int(props["ro.build.version.sdk"] or 0)
    print("\nLauncher: " + ("OK, Android SDK %d >= %d" % (sdk, MIN_SDK) if sdk >= MIN_SDK
                            else "NOT possible, SDK %d < %d (debloat only)" % (sdk, MIN_SDK)))


def cmd_backup(a):
    d = os.path.join(BACKUPS, ts())
    ensure(d, STATE)
    jobs = {
        "packages-all.txt": "pm list packages -f -u",
        "packages-enabled.txt": "pm list packages -e",
        "packages-disabled.txt": "pm list packages -d",
        "packages-system.txt": "pm list packages -s",
        "packages-thirdparty.txt": "pm list packages -3",
        "getprop.txt": "getprop",
        "settings-system.txt": "settings list system",
        "settings-secure.txt": "settings list secure",
        "settings-global.txt": "settings list global",
        "home.txt": "cmd package resolve-activity --brief " + HOME_QUERY,
        "home-candidates.txt": "cmd package query-activities --brief " + HOME_QUERY,
        "wm-size.txt": "wm size",
        "wm-density.txt": "wm density",
        "display-density-forced.txt": "settings get secure display_density_forced",
        "private-dns.txt": "settings get global private_dns_mode",
        "private-dns-host.txt": "settings get global private_dns_specifier",
    }
    for name, c in jobs.items():
        out = sh(c, timeout=120)
        with open(os.path.join(d, name), "w", encoding="utf-8") as f:
            f.write(out + "\n")
        print("saved %-28s (%d lines)" % (name, len(out.splitlines())))
    # A restore script that works even without this repo's state file.
    pre_disabled = set(packages("-d"))
    with open(os.path.join(d, "README.txt"), "w") as f:
        f.write("Backup taken %s before changes.\n"
                "Packages already disabled at this point (by the firmware) are in packages-disabled.txt;\n"
                "'tv.py restore --all' never touches those.\n" % d)
    with open(os.path.join(STATE, "baseline-disabled.txt"), "w") as f:
        f.write("\n".join(sorted(pre_disabled)) + "\n")
    print("\nBackup folder: %s" % d)


def classify(pkg, rules):
    g = match_group(pkg, rules["protected"])
    if g:
        return "PROTECTED", g
    g = match_group(pkg, rules["keep"])
    if g:
        return "KEEP", g
    g = match_group(pkg, rules["ask"])
    if g:
        return "ASK", g
    g = match_group(pkg, rules["categories"])
    if g:
        return "CANDIDATE", g
    return "UNSORTED", ""


def cmd_list(a):
    rules = load_rules()
    enabled = set(packages("-e"))
    disabled = set(packages("-d"))
    system = set(packages("-s"))
    rows = []
    for p in sorted(enabled | disabled):
        status, group = classify(p, rules)
        rows.append((status, group, p, "disabled" if p in disabled else "enabled",
                     "system" if p in system else "user"))
    order = {"CANDIDATE": 0, "ASK": 1, "UNSORTED": 2, "KEEP": 3, "PROTECTED": 4}
    rows.sort(key=lambda r: (order[r[0]], r[1], r[2]))
    ensure(REPORTS)
    path = os.path.join(REPORTS, "packages-%s.md" % ts())
    with open(path, "w") as f:
        f.write("| rule | group | package | state | type |\n|---|---|---|---|---|\n")
        for r in rows:
            f.write("| %s |\n" % " | ".join(r))
    for r in rows:
        print("%-10s %-38s %-55s %-8s %s" % r)
    print("\n%d packages. Report: %s" % (len(rows), path))
    print("Patterns are only a first sort. Run 'describe <pkg>' on anything you are unsure about.")


def cmd_describe(a):
    pkg = a.pkg
    if any(w in pkg.lower() for w in FACTORY_WORDS):
        print("NOTE: factory-style package. Describing it is safe; NEVER start it.")
    out = sh("dumpsys package %s" % pkg, timeout=60)
    keep = []
    section = None
    for line in out.splitlines():
        s = line.strip()
        if s.startswith(("Activity Resolver Table", "Receiver Resolver Table", "Service Resolver Table",
                         "Provider Resolver Table")):
            section = s.split()[0]
            keep.append("[%s]" % section)
            continue
        if section and ("/" in s and pkg in s):
            keep.append("  " + s.split(" filter")[0])
        for key in ("versionName=", "codePath=", "enabled=", "flags=[", "installerPackageName=",
                    "android.intent.category.HOME", "LEANBACK_LAUNCHER", "BOOT_COMPLETED"):
            if key in s:
                keep.append("  " + s)
                break
    seen = set()
    for l in keep:
        if l not in seen:
            print(l)
            seen.add(l)
    status, group = classify(pkg, load_rules())
    print("\nRule: %s %s" % (status, group))


def cmd_disable(a):
    rules = load_rules()
    pkgs = list(dict.fromkeys(a.pkgs))
    if len(pkgs) > MAX_BATCH:
        die("At most %d packages per batch (got %d). Small batches let us tell which one broke something."
            % (MAX_BATCH, len(pkgs)))
    if not os.path.isfile(os.path.join(STATE, "baseline-disabled.txt")):
        die("No backup yet. Run 'python tools/tv.py backup' first.")

    installed = set(packages())
    home = current_home()
    refused = []
    todo = []
    for p in pkgs:
        status, group = classify(p, rules)
        if p not in installed:
            refused.append((p, "not installed"))
        elif status == "PROTECTED":
            refused.append((p, "protected: %s (the TV may stop booting)" % group))
        elif status == "KEEP":
            refused.append((p, "on the keep list: %s" % group))
        elif status == "ASK" and not a.yes_asked:
            refused.append((p, "'%s' needs your decision; re-run with --yes-asked if you really want it off" % group))
        elif home.startswith(p + "/"):
            refused.append((p, "it is the current Home app (%s). Install our launcher and run set-home first." % home))
        elif p == "com.google.android.tungsten.setupwraith" and not a.wifi_checked:
            refused.append((p, "setup wizard: run  shell \"dumpsys wifi | grep -o 'cname=[^ ]*'\"  first; "
                               "if it owns the saved Wi-Fi, disabling it drops the TV off Wi-Fi. Then --wifi-checked."))
        else:
            todo.append(p)

    for p, why in refused:
        print("REFUSED %-50s %s" % (p, why))
    if not todo:
        die("Nothing to disable in this batch.", 2)

    for p in todo:
        out = sh("pm disable-user --user 0 %s" % p)
        print("pm disable-user %-45s -> %s" % (p, out.splitlines()[-1] if out else "(no output)"))
    time.sleep(1)
    now_disabled = set(packages("-d"))
    ok = [p for p in todo if p in now_disabled]
    failed = [p for p in todo if p not in now_disabled]
    log = read_disabled_log()
    write_disabled_log(log + [p for p in ok if p not in log])
    print("\nVerified disabled: %d   Not disabled: %d" % (len(ok), len(failed)))
    for p in failed:
        print("  NOT disabled: %s" % p)
    if todo and not ok:
        print("\nThe TV refused every package. Some firmware blocks pm disable-user entirely; do not keep\n"
              "retrying. Fall back to the TV's own privacy settings and the optional DNS blocking (README).")
    shot = os.path.join(SHOTS, "%s-after-disable.png" % ts())
    print("\nLook at the TV (or this screenshot) before the next batch:")
    take_screenshot(shot, ["HOME"], quiet=False)


def cmd_restore(a):
    log = read_disabled_log()
    targets = log if a.all else a.pkgs
    if not targets:
        die("Nothing to restore. Use --all or name packages.")
    still = list(log)
    for p in targets:
        out = sh("pm enable --user 0 %s" % p)
        print("pm enable %-50s -> %s" % (p, out.splitlines()[-1] if out else "(no output)"))
        if p in still:
            still.remove(p)
    write_disabled_log(still)
    if a.home:
        bdirs = sorted(os.listdir(BACKUPS)) if os.path.isdir(BACKUPS) else []
        if not bdirs:
            die("No backup found to read the original Home app from.")
        with open(os.path.join(BACKUPS, bdirs[0], "home.txt")) as f:
            lines = [l.strip() for l in f if "/" in l]
        if lines:
            print("Setting Home back to the original: %s" % lines[-1])
            print(sh("cmd package set-home-activity %s" % lines[-1]))
    print("Done. Reboot the TV if anything still looks off.")


def cmd_reboot_check(a):
    print("Waiting 15 s so Android writes package state to disk before the reboot ...")
    sh("sync")
    time.sleep(15)
    adb("reboot", timeout=20)
    print("Rebooting. Waiting for the TV to come back (up to 3 min) ...")
    time.sleep(20)
    host = serial()
    for _ in range(40):
        adb("connect", host, s=False, timeout=10)
        _, st = adb("get-state", timeout=10)
        if st.strip() == "device" and sh("getprop sys.boot_completed") == "1":
            break
        time.sleep(4)
    else:
        die("TV did not come back over ADB. If the screen is up, re-run connect. If it is stuck, see README 'Recovery'.")
    time.sleep(5)
    disabled = set(packages("-d"))
    back = [p for p in read_disabled_log() if p not in disabled]
    print("Home after boot: %s" % current_home())
    if back:
        print("Re-enabled by the firmware at boot (do not fight it, note it):")
        for p in back:
            print("  " + p)
    else:
        print("All packages we disabled are still disabled.")
    take_screenshot(os.path.join(SHOTS, "%s-after-reboot.png" % ts()), [], quiet=False)


def cmd_install(a):
    apk = a.apk
    if not os.path.isfile(apk):
        die("APK not found: " + apk)
    sdk = sdk_level()
    if sdk and sdk < MIN_SDK:
        die("This TV is Android SDK %d; the launcher needs %d+. Only the debloat half applies." % (sdk, MIN_SDK))
    print("Installing %s ..." % apk)
    code, out = adb("install", "-r", "-g", apk, timeout=120)
    if code == 124:
        print("adb install is waiting (probably an on-screen confirmation). Falling back to pm install ...")
        remote = "/data/local/tmp/tvlauncher.apk"
        adb("push", apk, remote, timeout=120, check=True)
        out = sh("pm install -r -g %s" % remote, timeout=120)
        sh("rm %s" % remote)
    print(out.strip())
    if "INSTALL_FAILED_VERIFICATION_FAILURE" in out:
        die("This TV refuses sideloaded apps. The launcher half is not possible here; do the debloat only.")
    if "INSTALL_FAILED_OLDER_SDK" in out:
        die("TV's Android is too old for the launcher.")
    if "Success" not in out:
        die("Install failed (see above).")
    for perm in ("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"):
        sh("pm grant %s %s" % (OUR_PKG, perm))
    print("Installed. Location permission granted so the Wi-Fi pill can show the network name.")
    print("Next: python tools/tv.py set-home")


def cmd_set_home(a):
    if OUR_PKG not in packages():
        die("Launcher not installed. Run install first.")
    cands = home_candidates()
    print("Apps that claim Home:")
    for c in cands:
        print("  " + c)
    print(sh("cmd package set-home-activity %s" % OUR_HOME))
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)
    focus = sh("dumpsys activity activities | grep -E 'mFocusedApp|ResumedActivity'")
    print(focus)
    if OUR_PKG in focus:
        print("\nVERIFIED: Home opens our launcher.")
    else:
        print("\nNOT verified: Home still opens something else, even if set-home-activity said Success.\n"
              "Do NOT disable the stock launcher yet. Send me the lines above.")
    take_screenshot(os.path.join(SHOTS, "%s-home.png" % ts()), [], quiet=False)


def press(keys):
    for k in keys:
        k = k.upper()
        if not k.startswith("KEYCODE_"):
            k = "KEYCODE_" + k
        sh("input keyevent %s" % k)
        time.sleep(0.45)


def take_screenshot(out, keys, quiet=True):  # quiet kept for call-site readability
    ensure(os.path.dirname(os.path.abspath(out)))
    if keys:
        press(keys)
        time.sleep(0.8)
    remote = "/sdcard/__tvshot.png"
    sh("screencap -p %s" % remote, timeout=30)
    adb("pull", remote, out, timeout=60)
    sh("rm %s" % remote)
    size = os.path.getsize(out) if os.path.isfile(out) else 0
    if size == 0:
        resumed = sh("dumpsys activity activities | grep -i ResumedActivity")
        print("Empty screenshot: Live TV, HDMI and some apps (Netflix) block capture. That is the TV, not a fault.\n"
              "On screen now:\n" + resumed)
        return False
    print("Screenshot: %s (%d KB)" % (os.path.abspath(out), size // 1024))
    return True


def cmd_screenshot(a):
    ok = take_screenshot(a.out, a.keys)
    sys.exit(0 if ok else 1)


def cmd_key(a):
    press(a.keys)


def restart_launcher():
    sh("am force-stop %s" % OUR_PKG)
    sh("input keyevent KEYCODE_HOME")


def cmd_push_config(a):
    with open(a.file, encoding="utf-8") as f:
        try:
            json.load(f)
        except ValueError as e:
            die("config is not valid JSON: %s" % e)
    sh("mkdir -p %s" % OUR_FILES)
    adb("push", a.file, OUR_FILES + "/config.json", check=True)
    restart_launcher()
    print("Pushed. The launcher restarted with the new config.")


def cmd_push_wallpaper(a):
    ext = os.path.splitext(a.file)[1].lower()
    if ext not in (".jpg", ".jpeg", ".png"):
        die("Use a .jpg or .png image.")
    name = "wallpaper.png" if ext == ".png" else "wallpaper.jpg"
    sh("mkdir -p %s" % OUR_FILES)
    sh("rm -f %s/wallpaper.jpg" % OUR_FILES)
    sh("rm -f %s/wallpaper.png" % OUR_FILES)
    adb("push", a.file, OUR_FILES + "/" + name, check=True)
    restart_launcher()
    print("Wallpaper set. Remove it with:  python tools/tv.py shell \"rm %s/%s\"" % (OUR_FILES, name))


def cmd_dns(a):
    if a.action == "status":
        print("mode: %s" % sh("settings get global private_dns_mode"))
        print("host: %s" % sh("settings get global private_dns_specifier"))
    elif a.action == "on":
        sh("settings put global private_dns_specifier dns.adguard-dns.com")
        sh("settings put global private_dns_mode hostname")
        print("Ad-blocking Private DNS on (AdGuard). It does NOT block YouTube ads.\n"
              "Open a streaming app now and check it still plays. Undo: python tools/tv.py dns off")
    elif a.action == "off":
        sh("settings put global private_dns_mode opportunistic")
        print("Private DNS back to automatic.")


def cmd_closed(a):
    """Only 'Connection refused' proves the port is closed. A timeout proves nothing."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5)
    try:
        s.connect((a.ip, a.port))
        print("OPEN: %s:%d accepts connections. Network ADB is still on." % (a.ip, a.port))
        sys.exit(2)
    except ConnectionRefusedError:
        print("CLOSED: %s:%d refused the connection." % (a.ip, a.port))
        sys.exit(0)
    except OSError as e:
        why = errno.errorcode.get(e.errno, str(e)) if getattr(e, "errno", None) else str(e)
        print("UNKNOWN: %s:%d gave %s. That is not proof it is closed (TV asleep, wrong IP, or blocked)."
              % (a.ip, a.port, why))
        sys.exit(3)
    finally:
        s.close()


def cmd_shell(a):
    print(sh(a.command, timeout=a.timeout))


# --------------------------------------------------------------------------- main

def main():
    p = argparse.ArgumentParser(description="Safe ADB tool for Kiran's TV", formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("connect"); c.add_argument("ip"); c.add_argument("--port", type=int, default=5555); c.set_defaults(f=cmd_connect)
    c = sub.add_parser("pair"); c.add_argument("ip"); c.add_argument("port", type=int); c.add_argument("code"); c.set_defaults(f=cmd_pair)
    sub.add_parser("info").set_defaults(f=cmd_info)
    sub.add_parser("backup").set_defaults(f=cmd_backup)
    sub.add_parser("list").set_defaults(f=cmd_list)
    c = sub.add_parser("describe"); c.add_argument("pkg"); c.set_defaults(f=cmd_describe)
    c = sub.add_parser("disable"); c.add_argument("pkgs", nargs="+")
    c.add_argument("--yes-asked", action="store_true", help="also allow packages from the 'ask' list")
    c.add_argument("--wifi-checked", action="store_true"); c.set_defaults(f=cmd_disable)
    c = sub.add_parser("restore"); c.add_argument("pkgs", nargs="*"); c.add_argument("--all", action="store_true")
    c.add_argument("--home", action="store_true", help="also set the original Home app back"); c.set_defaults(f=cmd_restore)
    sub.add_parser("reboot-check").set_defaults(f=cmd_reboot_check)
    c = sub.add_parser("install"); c.add_argument("apk"); c.set_defaults(f=cmd_install)
    sub.add_parser("set-home").set_defaults(f=cmd_set_home)
    c = sub.add_parser("screenshot"); c.add_argument("out"); c.add_argument("keys", nargs="*"); c.set_defaults(f=cmd_screenshot)
    c = sub.add_parser("key"); c.add_argument("keys", nargs="+"); c.set_defaults(f=cmd_key)
    c = sub.add_parser("push-config"); c.add_argument("file"); c.set_defaults(f=cmd_push_config)
    c = sub.add_parser("push-wallpaper"); c.add_argument("file"); c.set_defaults(f=cmd_push_wallpaper)
    c = sub.add_parser("dns"); c.add_argument("action", choices=["status", "on", "off"]); c.set_defaults(f=cmd_dns)
    c = sub.add_parser("closed"); c.add_argument("ip"); c.add_argument("port", nargs="?", type=int, default=5555); c.set_defaults(f=cmd_closed)
    c = sub.add_parser("shell"); c.add_argument("command"); c.add_argument("--timeout", type=int, default=60); c.set_defaults(f=cmd_shell)

    a = p.parse_args()
    a.f(a)


if __name__ == "__main__":
    main()
