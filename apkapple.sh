#!/system/bin/sh
# PocketTk - tkinter -> APK. KernelSU module CLI (command: pockettk; the old names tkdroid, apkapple and apple still work but are deprecated).
IS_ROOT=0; [ "$(id -u)" = 0 ] && IS_ROOT=1
# No su at all = an unrooted phone: apps are installed through the system installer (or adb), see unrooted_setup.
UNROOTED=0; [ "$IS_ROOT" = 1 ] || command -v su >/dev/null 2>&1 || UNROOTED=1
if [ "$IS_ROOT" = 1 ]; then
  # Root shells from Termux (tsu) export LD_LIBRARY_PATH/LD_PRELOAD, which breaks Android's own tools.
  unset LD_LIBRARY_PATH LD_PRELOAD
  export PATH=/system/bin:/system/xbin:/product/bin:/apex/com.android.runtime/bin:/vendor/bin:$PATH
fi
# Files live next to this script (the module copies them into Termux so a normal user can read them).
MODDIR=$(cd "$(dirname "$0")" && pwd)

TERMUX=${APKAPPLE_TERMUX:-/data/data/com.termux/files/usr}
TXHOME=${APKAPPLE_HOME:-/data/data/com.termux/files/home}
if [ "$IS_ROOT" = 1 ]; then
  WORK=/data/local/tmp/pockettk; KS_DIR=/data/adb/pockettk
else
  WORK="$HOME/.pockettk/work"; KS_DIR="$HOME/.pockettk"
fi
OUT_DIR=/sdcard/Download/PocketTk
VERSION="PocketTk v4.1.2"
EDITION=full          # make_demo.py sets this to "demo" (PocketTk Demo), make_consumer.py to "consumer" (PocketTk Consumer)
EDITION_TAG=""; [ "$EDITION" = demo ] && EDITION_TAG=" Demo"; [ "$EDITION" = consumer ] && EDITION_TAG=" Consumer"
PKG_PREFIX="pockettk.ssdes."
LEGACY_PKG_PREFIXES="tkdroid.ssdes. tkdroid.palalgon. tkdroid.molt. apple.app."      # packages of apps built before the name changed

# legacy_suffix PKG -> the app name after an old prefix; fails when PKG is not on an old package
legacy_suffix() {
  for _lp in $LEGACY_PKG_PREFIXES; do
    case "$1" in "$_lp"*) echo "${1#"$_lp"}"; return 0;; esac
  done
  return 1
}
LAST="$KS_DIR/last-package"
if [ "$IS_ROOT" = 0 ] && { [ -d "$HOME/.apple" ] || [ -d "$HOME/.tkdroid" ]; } && [ ! -e "$HOME/.pockettk" ] && [ -f "$MODDIR/migrate.sh" ]; then
  . "$MODDIR/migrate.sh"; pockettk_migrate_home "$HOME"      # old ApkApple / TkDroid folder -> ~/.pockettk
fi

case "$POCKETTK_CALLED" in
  tkdroid|apkapple|apple) echo "pockettk: the name '$POCKETTK_CALLED' is deprecated and will be removed in a future version - use: pockettk $*" >&2;;
esac

die() { echo "pockettk: $*" >&2; exit 1; }
say() { echo "- $*"; }

# The demo edition is cut down: anything that needs a bigger edition stops here.
demo_block() { # demo_block WHAT
  [ "$EDITION" = demo ] || return 0
  die "$1 is not in the PocketTk Demo. The full PocketTk is open source: github.com/iamssdes/PocketTk"
}


# Run a Termux tool. No LD_LIBRARY_PATH: Termux binaries find their libraries themselves.
tx() {
  env PATH="$TERMUX/bin:$PATH" TMPDIR="$WORK/tmp" JAVA_HOME="$TERMUX/lib/jvm/java-21-openjdk" "$@"
}

newest() { # newest <glob...> : last match by numeric version
  for p in "$@"; do [ -e "$p" ] && echo "$p"; done | sort -t- -k2 -n | tail -n 1
}

find_sdk() {
  for d in "$APPLE_SDK" "$TXHOME/android-sdk" "$TXHOME/Android/Sdk" "$TERMUX/share/android-sdk" \
           /sdcard/PocketTk/sdk /sdcard/ApkApple/sdk /sdcard/Apple/sdk "$MODDIR/tools/sdk"; do
    [ -n "$d" ] && [ -d "$d" ] && { echo "$d"; return; }
  done
}

find_android_jar() {
  for f in "$APPLE_ANDROID_JAR" "$MODDIR/tools/android.jar" "$HOME/.pockettk/android.jar" /sdcard/PocketTk/android.jar /sdcard/ApkApple/android.jar /sdcard/Apple/android.jar; do
    [ -n "$f" ] && [ -f "$f" ] && { echo "$f"; return; }
  done
  sdk=$(find_sdk)
  [ -n "$sdk" ] && newest "$sdk"/platforms/android-*/android.jar
}

find_tool() { # find_tool name
  for f in "$TERMUX/bin/$1" "$MODDIR/tools/$1"; do [ -x "$f" ] && { echo "$f"; return; }; done
  sdk=$(find_sdk)
  if [ -n "$sdk" ]; then
    for f in "$sdk"/build-tools/*/"$1"; do [ -x "$f" ] && { echo "$f"; return; }; done
  fi
}

find_lib() { # find_lib name.jar (inside build-tools/*/lib)
  sdk=$(find_sdk)
  [ -n "$sdk" ] && for f in "$sdk"/build-tools/*/lib/"$1"; do [ -f "$f" ] && { echo "$f"; return; }; done
}

mark() { if [ -n "$2" ]; then echo "  [ok]  $1: $2"; else echo "  [--]  $1: NOT FOUND"; MISSING=1; fi; }

cmd_doctor() {
  MISSING=0
  echo "$VERSION toolchain check"
  if [ "$UNROOTED" = 1 ]; then
    if [ "$ADB" = 1 ]; then echo "  [ok]  mode: no root, adb connected (silent install, launch, logs)"
    elif command -v termux-open >/dev/null 2>&1; then echo "  [ok]  mode: no root, installs through the Android installer (add adb for silent install and logs)"
    else echo "  [--]  mode: no root and no termux-open: pkg install termux-tools"; MISSING=1; fi
  fi
  mark "python3   " "$(find_tool python3)"
  mark "javac/ecj " "$(find_tool javac)$( [ -z "$(find_tool javac)" ] && find_tool ecj)"
  mark "java      " "$(find_tool java)"
  mark "keytool   " "$(find_tool keytool)"
  mark "aapt2     " "$(find_tool aapt2)"
  d8=$(find_tool d8); [ -z "$d8" ] && d8=$(find_lib d8.jar); [ -z "$d8" ] && d8=$(find_tool dx)
  mark "d8 or dx  " "$d8"
  sg=$(find_tool apksigner); [ -z "$sg" ] && sg=$(find_lib apksigner.jar)
  mark "apksigner " "$sg"
  mark "android.jar" "$(find_android_jar)"
  # optional: only needed for  pockettk build app.py --aab  (Google Play upload)
  bt=$(find_bundletool); js=$(find_tool jarsigner)
  if [ -n "$bt" ] && [ -n "$js" ]; then echo "  [ok]  bundletool+jarsigner: $bt (--aab)"
  else echo "  [..]  bundletool+jarsigner: optional, for --aab  ->  download bundletool-all-VERSION.jar from github.com/google/bundletool/releases to ~/bundletool.jar"; fi
  # optional: only needed for YouTube / page links in #audio: and #playlist:
  yd=$(find_tool yt-dlp); nd=$(find_tool node)
  if [ -n "$yd" ] && [ -n "$nd" ]; then echo "  [ok]  yt-dlp+node: $yd (YouTube #audio:)"
  else echo "  [..]  yt-dlp+node: optional, for YouTube #audio:  ->  pkg install yt-dlp nodejs"; fi
  if [ "$MISSING" = 1 ]; then
    cat <<'T'

Missing pieces. In Termux (as the normal Termux user, NOT as root) run:
  pkg update && pkg install python openjdk-21 aapt2 apksigner dx
and get android.jar (SDK platform) by either:
  * installing the Android SDK in Termux (build-tools + platforms;android-34), or
  * copying any android.jar (API 26+) to /sdcard/PocketTk/android.jar
Package names vary by Termux mirror/date; run `pockettk doctor` again after each step.
T
    return 1
  fi
  echo "All set."
}

# What the demo edition cannot do (shown by help; __MAXLINES__ is filled in by make_demo.py)
demo_notes() {
  cat <<'N'

PocketTk DEMO - a cut-down edition. What works: run, build, check, install, logs, list, uninstall, demo, doctor.
  Scripts: plain tkinter, up to __MAXLINES__ lines; only  #use: fppe  is available (other #use: lines are ignored).
  Not in the demo: update / update-all / revert / ids / delete (--id), --icon, --dispose, -p, --aab, update kinds;
  no Canvas, Toplevel, Notebook, Menu, Scale, Spinbox; no network, files (open, os, csv), threads or sqlite3.
  Apps built with the demo always show the "Demo" version label.
  The full PocketTk (no limits) is open source: github.com/iamssdes/PocketTk
N
}

# What the consumer edition cannot do (shown by help)
consumer_notes() {
  cat <<'N'

PocketTk CONSUMER - not the full version (CONSUMER DOES NOT MEAN FULL). Everything works except one thing:
  #use: builtin-editing and the #builtins: lines are not available (they are ignored with a warning), so apps
  built with the consumer version always show the version label, which is the link to About PocketTk.
N
}

usage() {
  cat <<'U'
PocketTk - turn a tkinter script into an Android APK   (command: pockettk; the old names tkdroid, apkapple and apple are deprecated)

  pockettk run app.py [--dispose] [--logs] [--icon IMG] [--id NAME]   build + install + launch on this phone
  pockettk build app.py [-n NAME] [-p PACKAGE] [-o OUT.apk] [--icon IMG] [--id NAME] [-i] [-k]
  pockettk build app.py --aab [--id NAME] [--target-sdk N] [--keystore FILE [--ks-alias A]]   Android App Bundle for Google Play upload
  pockettk logs [PACKAGE|app.py] [-f] [-e] [-c]   errors/prints with Python line numbers
  pockettk check app.py [-v]   transpile only; show warnings (-v: also the generated Java)
  pockettk install file.apk    install an APK (pm install -r)
  pockettk update --id NAME [code.py] [--patch|--small|--medium|--large] [--debug]   rebuild + install over the old app (keeps its data)
  pockettk update --id NAME --new-pkg   move an app from an old package (tkdroid.ssdes.* and older) to pockettk.ssdes.* (update does this by itself; --keep-pkg stays on the old package)
  pockettk revert --id NAME    restore the version saved by update --debug (normally automatic)
  pockettk update-all [--force] [-j N] [--patch|--small|--medium|--large]   update every app with an id to this PocketTk version (N at a time)
                               (apps still on an old package, tkdroid.ssdes.* and older, are moved to pockettk.ssdes.*: data copied, old app removed)
  pockettk ids                 app ids you created
  pockettk delete --id NAME [-y]   uninstall the app (its data goes too) and remove the id
  pockettk delete-uninstalled      remove the ids of apps that are no longer installed
  pockettk list                apps built by PocketTk that are installed
  pockettk uninstall [PACKAGE|app.py|--all]
  pockettk demo                write sample scripts to /sdcard/Download/PocketTk/
  pockettk set-email [ADDRESS] set (or show) the email that About > Request sends to; kept across re-flashing
  pockettk doctor              verify the build toolchain
  pockettk help [--brief] | version     (help --brief: what this is and how to use it, in a few lines)

update kinds (apps with --id; shown in the app's version label):
  --patch   raise the patch counter only:   1.2.3 Patch 1 -> Patch 2   (default)
  --small   raise the last number, reset patch:     1.2.3 -> 1.2.4
  --medium  raise the middle number, reset last + patch:   1.2.4 -> 1.3.0
  --large   raise the first number, reset the rest:        1.3.0 -> 2.0.0

run:
  --dispose   uninstall and delete the app the moment it leaves the foreground
              (apps using `#use: dpooa`: only when the overlay is fully closed)
  --logs      follow the log after launching (Ctrl+C to stop)
  --debug     show a log panel (prints, errors, warnings) under the app
update --debug:
  builds with the log panel, installs it over the old app and launches it. When you close the app,
  the previous version is installed back over it automatically (data kept, no uninstall).
build:
  -n NAME     app label (default: file name)
  -p PACKAGE  Android package id (default: pockettk.ssdes.<name>)
  -o OUT      output path (default: /sdcard/Download/PocketTk/<name>.apk)
  -i          install after building
  -k          keep the work directory
  --debug     add the log panel

Script header (top comments):
  #use: fppe, dpooa, filepick, datasaving, audio   (any mix; "native filepicker" = filepick)
  #use: builtin-editing                         (allows the #builtins: lines below)
  #builtins: version-label: disable             (switch a built-in off: version-label, datareset-confirm, error-toast,
                                                 or a feature name; "enable" turns it back on)
  #dpooa: draggable                             (overlay window can be moved)
  #datasaving: datareset                        (inside a function: ask, then wipe saved data, reset variables)
  #datasaving: datareset: noconfirm             (same, without the confirmation)
  #audio: dQw4w9WgXcQ                           (music: YouTube id/URL, any URL, or a file; needs #use: audio)
  #playlist: id1 id2 /sdcard/a.mp3              (several tracks, played in order; add "noloop" to play once)
U
  if [ "$EDITION" = demo ]; then demo_notes; fi
  if [ "$EDITION" = consumer ]; then consumer_notes; fi
}

brief() {
  cat <<'B'
PocketTk - brief

What it is
  A KernelSU module plus a Termux command. It reads your Python tkinter script, translates it to Java
  (transpile.py), compiles and signs an Android APK (apkapple.sh runs the build tools) and installs it.
  The files in rt/ are the runtime that turns tkinter widgets into real Android views. Your script becomes
  native code; there is no Python on the phone while the app runs.

How to use (in Termux, as a normal user, not tsu)
  pockettk doctor                  check the build tools (first time only)
  pockettk demo                    write example scripts to /sdcard/Download/PocketTk/
  pockettk run app.py              build, install and launch
  pockettk run app.py --id notes   same, and remember the app as "notes"
  pockettk update --id notes       rebuild and install over it (data is kept)
  pockettk update --id notes --debug   try a change with a log panel; the old version returns when you close it
  pockettk logs -e                 show errors with your Python line numbers

Options go in comments at the top of the script, for example:
  #use: dpooa, datasaving, audio       floating window, saved variables, music
Everything: pockettk help        Details and limits: README.md
B
  if [ "$EDITION" = demo ]; then demo_notes; fi
  if [ "$EDITION" = consumer ]; then consumer_notes; fi
}

q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

safe_name() { echo "$1" | tr 'A-Z' 'a-z' | sed 's/[^a-z0-9]/_/g; s/^_*//; s/_*$//'; }

cmd_check() {
  _java=0; _src=""
  for _a in "$@"; do
    case "$_a" in -v|--java) _java=1;; *) [ -n "$_src" ] || _src=$_a;; esac
  done
  [ -f "$_src" ] || die "no such file: $_src"
  py=$(find_tool python3); [ -n "$py" ] || die "python3 not found (run: pockettk doctor)"
  rm -rf "$WORK/check"; mkdir -p "$WORK/tmp" "$WORK/check"
  _out=$(tx "$py" -I "$MODDIR/transpile.py" gen "$_src" "$WORK/check" --no-assets 2>&1) || { printf '%s\n' "$_out"; die "transpile failed"; }
  [ -n "$_out" ] && printf '%s\n' "$_out"
  _n=$(printf '%s\n' "$_out" | grep -c '^warning:' || true)
  if [ "$_n" -eq 0 ]; then echo "no warnings"; elif [ "$_n" -eq 1 ]; then echo "1 warning"; else echo "$_n warnings"; fi
  if [ "$_java" = 1 ]; then
    echo "---- generated Script.java ----"
    cat "$WORK/check/src/apple/gen/Script.java"
  fi
}

cmd_demo() {
  mkdir -p "$OUT_DIR"
  if [ "$EDITION" = demo ]; then
    cat > "$OUT_DIR/hello.py" <<'D'
#use: fppe
import tkinter as tk

root = tk.Tk()
root.title("PocketTk demo")

name = tk.StringVar()
greeting = tk.StringVar(value="Type your name")
count = 0
total = 0.0


def greet():
    global count, total
    if not name.get().strip():
        greeting.set("Please type a name first")
        return
    count += 1
    total += 0.1
    greeting.set(f"Hello, {name.get()}! ({count}x, total {total})")


tk.Label(root, text="Hello from Python", font=("Arial", 22, "bold")).pack(pady=12)
tk.Entry(root, textvariable=name).pack(fill=tk.X, padx=16)
tk.Button(root, text="Greet", command=greet).pack(pady=8)
tk.Label(root, textvariable=greeting, fg="blue").pack()
root.mainloop()
D
    say "wrote $OUT_DIR/hello.py"
    say "try:  pockettk run $OUT_DIR/hello.py"
    return 0
  fi
  cat > "$OUT_DIR/demo.py" <<'D'
#use: fppe, datasaving, filepick
import json
import tkinter as tk
from tkinter import filedialog, messagebox

root = tk.Tk()
root.title("PocketTk demo")

name = tk.StringVar()
greeting = tk.StringVar(value="Type your name")
count = 0
total = 0.0


def greet():
    global count, total
    if not name.get().strip():
        messagebox.showinfo("Hmm", "Please type a name first")
        return
    count += 1
    total += 0.1
    greeting.set(f"Hello, {name.get()}! ({count}x, total {total})")


def export():
    path = filedialog.asksaveasfilename(defaultextension=".json", initialfile="demo.json")
    if not path:
        return
    with open(path, "w") as f:
        json.dump({"name": name.get(), "count": count, "total": total}, f, indent=2)
    messagebox.showinfo("Saved", "Wrote " + path.split("/")[-1])


tk.Label(root, text="Hello from Python", font=("Arial", 22, "bold")).pack(pady=12)
tk.Entry(root, textvariable=name).pack(fill=tk.X, padx=16)
tk.Button(root, text="Greet", command=greet).pack(pady=8)
tk.Label(root, textvariable=greeting, fg="blue").pack()
tk.Button(root, text="Export JSON...", command=export).pack(pady=8)
root.mainloop()
D
  cat > "$OUT_DIR/float.py" <<'D'
#use: dpooa, datasaving
#dpooa: draggable, resizable, snap, size 300x260, pos topright, alpha 0.92
#dpooa: title "Floating notes", bar #1B5E20
import tkinter as tk

root = tk.Tk()
root.title("Floating notes")
notes = tk.Text(root, height=5)
notes.pack(fill=tk.X)
hits = 0
label = tk.Label(root, text="taps: 0")
label.pack()


def tap():
    global hits
    hits += 1
    label.config(text=f"taps: {hits}")


tk.Button(root, text="Tap", command=tap).pack()
root.mainloop()
D
  cat > "$OUT_DIR/audio.py" <<'D'
#use: audio
#audio: dQw4w9WgXcQ
import tkinter as tk

root = tk.Tk()
root.title("Audio demo")
tk.Label(root, text="Music is playing", font=("Arial", 22, "bold")).pack(pady=40)
root.mainloop()
D
  say "wrote $OUT_DIR/demo.py, $OUT_DIR/float.py and $OUT_DIR/audio.py"
  say "try:  pockettk run $OUT_DIR/demo.py        or:  pockettk run $OUT_DIR/float.py --dispose"
}

# Grant "display over other apps" (needed for #use: dpooa); harmless for other apps.
grant_overlay() {
  [ -n "$1" ] || return 0
  appops set "$1" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  cmd appops set "$1" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  pm grant "$1" android.permission.SYSTEM_ALERT_WINDOW >/dev/null 2>&1
}

# ---- unrooted phones -------------------------------------------------------------------------------
# With adb (wireless debugging on this phone, or a USB cable) everything root does can be done through adb.
# Without adb the APK is handed to Android's own installer: you tap Install, and Open afterwards.
adb_ready() {
  command -v adb >/dev/null 2>&1 || return 1
  adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { f = 1 } END { exit !f }'
}
unrooted_setup() {
  [ "$UNROOTED" = 1 ] || return 0
  ADB=0
  if adb_ready; then
    ADB=1
    # the helpers below call pm/am/appops/logcat by name: point them at the device
    pm() { adb shell pm "$@"; }
    am() { adb shell am "$@"; }
    cmd() { adb shell cmd "$@"; }
    appops() { adb shell appops "$@"; }
    monkey() { adb shell monkey "$@"; }
    logcat() { adb logcat "$@"; }
    dumpsys() { adb shell dumpsys "$@"; }
  fi
}
unrooted_setup
UNROOT_NOTE=0
unrooted_note() {
  [ "$UNROOT_NOTE" = 0 ] || return 0
  UNROOT_NOTE=1
  say "no root: using the Android installer (for silent install, launch and logs: pkg install android-tools, turn on Wireless debugging, adb pair, adb connect)"
}
# what root did for the internal helpers, for a phone without su
as_user() {
  _c=$1; shift
  if [ "$ADB" = 1 ]; then
    case "$_c" in
      _grant|_launch|_has|_pkgs|_list|_uninstall|_logs) "cmd_$_c" "$@"; return;;
    esac
    echo "pockettk: this needs root, it is not available on an unrooted phone" >&2; return 1
  fi
  case "$_c" in
    _grant) return 0;;
    _launch) say "tap Open in the installer to start it"; return 0;;
    _has|_pkgs|_list) return 1;;
    _uninstall) for _p in "$@"; do am start -a android.intent.action.DELETE -d "package:$_p" >/dev/null 2>&1 && say "confirm the removal of $_p on the screen"; done; return 0;;
    _logs) echo "pockettk: logs need adb on an unrooted phone: pkg install android-tools, turn on Wireless debugging, then adb pair and adb connect" >&2; return 1;;
  esac
  echo "pockettk: this needs root, it is not available on an unrooted phone" >&2; return 1
}

# Run a root-only helper: directly when already root, else through su.
as_root() {
  if [ "$IS_ROOT" = 1 ]; then sh "$MODDIR/apkapple.sh" "$@"
  elif [ "$UNROOTED" = 1 ]; then as_user "$@"
  else
    command -v su >/dev/null 2>&1 || die "no su found: launch root in DFRoot first, then retry"
    su -c "sh '$MODDIR/apkapple.sh' $*"
  fi
}

cmd_install() {
  [ -f "$1" ] || die "no such file: $1"
  pkg="$2"
  if [ -z "$pkg" ] && [ "$IS_ROOT" = 0 ]; then
    aapt=$(find_tool aapt2)
    [ -n "$aapt" ] && pkg=$(tx "$aapt" dump packagename "$1" 2>/dev/null | head -n 1)
  fi
  # The package installer service cannot read /sdcard (FUSE), so stage the APK in /data/local/tmp first.
  tmp=/data/local/tmp/apkapple-install${pkg:+-$pkg}.apk     # per package: update-all installs several at once
  inst="cp '$1' $tmp && chmod 644 $tmp && pm install -r $tmp; r=\$?; rm -f $tmp; exit \$r"
  if [ "$UNROOTED" = 1 ]; then
    if [ "$ADB" = 1 ]; then
      adb install -r -d "$1" || die "adb install failed (see message above)"
    else
      unrooted_note
      command -v termux-open >/dev/null 2>&1 || die "termux-open not found: pkg install termux-tools   (or use adb)"
      KEEP_APK=1
      termux-open "$1" || die "could not open the installer"
      say "tap Install on the screen (if Android blocks it: Settings > allow Termux to install unknown apps, then run again)"
    fi
    [ -n "$pkg" ] && as_root _grant "$pkg"
    [ "$ADB" = 1 ] && say "installed${pkg:+: $pkg}"
    return 0
  fi
  if [ "$IS_ROOT" = 1 ]; then
    sh -c "$inst" || die "install failed (see message above)"
  else
    command -v su >/dev/null 2>&1 || die "no su found: launch root in DFRoot first, then retry:  pockettk install $1"
    su -c "$inst" || die "install failed (is root launched? then:  pockettk install $1)"
  fi
  [ -n "$pkg" ] && as_root _grant "$pkg"
  say "installed${pkg:+: $pkg}"
}

# internal (root): pockettk _grant PKG
cmd__grant() {
  grant_overlay "$1"
  pm grant "$1" com.termux.permission.RUN_COMMAND >/dev/null 2>&1      # apps made with #use: termux-connect
}

# internal (root): pockettk _launch PKG [fg|closed APK BUILDDIR]
cmd__launch() {
  pkg="$1"
  grant_overlay "$pkg"
  am start -n "$pkg/apple.gen.MainActivity" >/dev/null 2>&1 || monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
    || { echo "pockettk: could not launch $pkg" >&2; return 1; }
}

# internal (root): pockettk _watch PKG MODE APK BUILDDIR  -> uninstall when the app is gone
cmd__watch() {
  pkg="$1" mode="$2" apk="$3" bdir="$4"
  seen=0 gone=0 t0=$(date +%s)
  while :; do
    sleep 0.5
    now=$(date +%s)
    if [ "$mode" = closed ]; then
      pid=$(pidof "$pkg" 2>/dev/null)
      if [ -n "$pid" ]; then seen=1; gone=0
      elif [ "$seen" = 1 ]; then gone=$((gone + 1)); [ "$gone" -ge 2 ] && break
      fi
    else
      fg=0
      if [ -e "/data/data/$pkg/files/.busy" ]; then fg=1   # file picker open: still "in use"
      else
        top=$(dumpsys activity activities 2>/dev/null | grep -E 'ResumedActivity|topResumedActivity')
        case "$top" in *"$pkg/"*) fg=1;; esac
      fi
      if [ "$fg" = 1 ]; then seen=1; gone=0
      elif [ "$seen" = 1 ]; then gone=$((gone + 1)); [ "$gone" -ge 2 ] && break
      fi
    fi
    if [ "$seen" = 0 ] && [ $((now - t0)) -gt 40 ]; then break; fi   # never started
  done
  pm uninstall "$pkg" >/dev/null 2>&1
  rm -f "$apk" "$apk.idsig"
  [ -n "$bdir" ] && rm -rf "$bdir"
  echo "disposed $pkg" >> /data/local/tmp/apkapple-dispose.log
}

# internal (root): pockettk _backup PKG DEST -> copy the installed APK of PKG to DEST (owned like DEST's folder)
cmd__backup() {
  _src=$(pm path "$1" 2>/dev/null | sed -n 's/^package://p' | head -n 1)
  [ -n "$_src" ] && [ -f "$_src" ] || return 1
  cp "$_src" "$2" || return 1
  _d=$(dirname "$2")
  chown "$(stat -c %u:%g "$_d")" "$2" 2>/dev/null
  chcon --reference="$_d" "$2" 2>/dev/null
  chmod 644 "$2"
}

# internal (root): pockettk _revert PKG APK -> install APK over PKG (keeps data), then delete APK
cmd__revert() {
  _pkg="$1" _apk="$2" _tmp=/data/local/tmp/apkapple-revert.apk _log=/data/local/tmp/apkapple-debug.log
  [ -f "$_apk" ] || { echo "pockettk: no saved version to restore for $_pkg" >&2; return 1; }
  cp "$_apk" "$_tmp" && chmod 644 "$_tmp"
  if _out=$(pm install -r "$_tmp" 2>&1); then
    rm -f "$_tmp" "$_apk"
    grant_overlay "$_pkg"
    echo "restored previous version of $_pkg" >> "$_log"
    echo "restored the previous version of $_pkg"
  else
    rm -f "$_tmp"
    echo "revert of $_pkg FAILED: $_out" >> "$_log"
    echo "pockettk: restoring the previous version failed: $_out" >&2
    return 1
  fi
}

# internal (root): pockettk _debugwatch PKG APK -> wait until the app is closed, then _revert
cmd__debugwatch() {
  _p="$1" _a="$2"; seen=0 gone=0 t0=$(date +%s)
  while :; do
    sleep 0.5
    if [ -n "$(pidof "$_p" 2>/dev/null)" ]; then seen=1; gone=0
    elif [ "$seen" = 1 ]; then gone=$((gone + 1)); [ "$gone" -ge 2 ] && break
    elif [ $(( $(date +%s) - t0 )) -gt 40 ]; then break      # never started
    fi
  done
  cmd__revert "$_p" "$_a"
}

# sign_apk IN OUT with the PocketTk key (same key as every build, so Android accepts it as an update)
sign_apk() {
  _ks="$KS_DIR/apple.keystore"
  [ -f "$_ks" ] || { echo "pockettk: signing key not found ($_ks)" >&2; return 1; }
  _sg=$(find_tool apksigner)
  if [ -n "$_sg" ]; then
    tx "$_sg" sign --ks "$_ks" --ks-pass pass:android --key-pass pass:android --out "$2" "$1" || return 1
  else
    _sj=$(find_lib apksigner.jar); _jv=$(find_tool java)
    [ -n "$_sj" ] && [ -n "$_jv" ] || { echo "pockettk: apksigner not found - run: pockettk doctor" >&2; return 1; }
    tx "$_jv" -jar "$_sj" sign --ks "$_ks" --ks-pass pass:android --key-pass pass:android --out "$2" "$1" || return 1
  fi
  rm -f "$2.idsig"
}

# compile_java OUTDIR CLASSPATH LISTFILE SRCDIR LOG : javac takes the list file, ecj the folder
compile_java() {
  if [ -n "$JAVAC" ]; then
    tx "$JAVAC" -nowarn -Xlint:none --release 8 -encoding UTF-8 -cp "$2" -d "$1" @"$3" >"$5" 2>&1 || { cat "$5"; return 1; }
  else
    tx "$ECJ" -8 -nowarn -encoding UTF-8 -cp "$2" -d "$1" "$4" >"$5" 2>&1 || { cat "$5"; return 1; }
  fi
}

# dex_classes CLASSDIR OUTDIR [RUNTIME_CLASSES] : writes OUTDIR/classes.dex
dex_classes() {
  _out="$2"; _cl="${2%/}.classes.txt"
  find "$1" -name '*.class' > "$_cl"
  [ -s "$_cl" ] || return 1
  if [ -n "$D8" ] || [ -n "$D8JAR" ]; then
    # first with the runtime as class path, then without (in case this d8 does not take a folder there)
    _tries="$3 -"; [ -n "$3" ] || _tries="-"
    for _c in $_tries; do
      if [ "$_c" = "-" ]; then _ca=""; else _ca="--classpath $_c"; fi
      if [ -n "$D8" ]; then
        tx "$D8" --release --min-api 26 --lib "$AJAR" $_ca --output "$_out" $(cat "$_cl") && return 0
      else
        tx "$JAVA" -cp "$D8JAR" com.android.tools.r8.D8 --release --min-api 26 --lib "$AJAR" $_ca --output "$_out" $(cat "$_cl") && return 0
      fi
    done
    return 1
  fi
  # dx.jar is a dex file run by Android's dalvikvm, which aborts if Termux's LD_LIBRARY_PATH leaks in.
  tx "$DX" --dex --min-sdk-version=26 --output="$_out/classes.dex" "$1" \
    || { echo "pockettk: dx failed (try manually: unset LD_LIBRARY_PATH LD_PRELOAD; dx --dex --min-sdk-version=26 --output=/data/local/tmp/t.dex $1)" >&2; return 1; }
}

# ensure_rt : compile + dex the runtime (rt/*.java, ~270 KB of Java) ONCE and keep it in $KS_DIR/rt/<key>.
# Every app build then only compiles its own small generated code, and the runtime goes into the APK as a
# second dex. The key changes when the runtime, android.jar or the compiler/dexer change, so it rebuilds itself.
# Sets RT_CLASSES and XDEX (the extra argument for `transpile.py pack`).
ensure_rt() {
  _tc=ecj; [ -z "$JAVAC" ] || _tc=javac
  _dc=dx; [ -z "$D8$D8JAR" ] || _dc=d8
  _key="$(cat "$MODDIR"/rt/*.java | cksum | tr ' ' '-')-$_tc-$_dc-$(stat -c %s "$AJAR" 2>/dev/null)"
  RTC="$KS_DIR/rt/$_key"; RT_CLASSES="$RTC/classes"; XDEX="--extra-dex $RTC/rt.dex"
  [ -f "$RTC/rt.dex" ] && [ -d "$RT_CLASSES" ] && return 0
  say "preparing the runtime (one time, every app reuses it)"
  mkdir -p "$KS_DIR/rt" "$WORK/tmp" || return 1
  _t=$(mktemp -d "$KS_DIR/rt/tmp.XXXXXX") || return 1
  mkdir -p "$_t/classes" "$_t/dex" || return 1
  find "$MODDIR/rt" -name '*.java' > "$_t/sources.txt"
  compile_java "$_t/classes" "$AJAR" "$_t/sources.txt" "$MODDIR/rt" "$_t/javac.log" || { rm -rf "$_t"; return 1; }
  dex_classes "$_t/classes" "$_t/dex" || { rm -rf "$_t"; return 1; }
  [ -f "$_t/dex/classes.dex" ] || { rm -rf "$_t"; return 1; }
  mv "$_t/dex/classes.dex" "$_t/rt.dex"; rm -rf "$_t/dex" "$_t/dex.classes.txt" "$_t/sources.txt" "$_t/javac.log"
  if [ -f "$RTC/rt.dex" ]; then rm -rf "$_t"          # another build finished it first
  else rm -rf "$RTC"; mv "$_t" "$RTC" || { rm -rf "$_t"; return 1; }; fi
  for _d in "$KS_DIR"/rt/*; do                          # runtimes of older versions
    case "$_d" in "$RTC"|"$KS_DIR"/rt/tmp.*) ;; *) rm -rf "$_d";; esac
  done
  return 0
}

cmd_run() {
  src="" dispose=0 follow=0 extra=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --dispose|-d) demo_block "--dispose"; dispose=1; shift;;
      --logs|-l)    follow=1; shift;;
      --debug)      extra="$extra --debug"; shift;;
      -n|-p)        [ "$1" = -p ] && demo_block "-p (own package name)"; [ $# -ge 2 ] || die "$1 needs a value"; extra="$extra $1 $(q "$2")"; shift 2;;
      --icon)       demo_block "--icon"; [ $# -ge 2 ] || die "--icon needs an image file"; [ -f "$2" ] || die "no such icon file: $2"; ic=$(cd "$(dirname "$2")" && pwd)/$(basename "$2"); extra="$extra --icon $(q "$ic")"; shift 2;;
      --id)         demo_block "--id"; [ $# -ge 2 ] || die "--id needs a name"; extra="$extra --id $(q "$2")"; shift 2;;
      --patch|--small|--medium|--large) demo_block "$1"; extra="$extra $1"; shift;;
      -h|--help)    usage; return 0;;
      --ipa|--sign|--profile|--simulator|--emulate) die "--ipa (iOS) builds only work on a Mac for now: run pockettk on macOS";;
      -*)           die "unknown option $1";;
      *)            src=$1; shift;;
    esac
  done
  [ -n "$src" ] || { usage; return 1; }
  [ -f "$src" ] || die "no such file: $src"
  [ "$IS_ROOT" = 0 ] || die "run this as your normal Termux user (type 'exit' to leave tsu)."
  base=$(basename "$src" .py); sn=$(safe_name "$base"); [ -n "$sn" ] || sn=app
  case "$sn" in [0-9]*) sn="a$sn";; esac
  apk="$WORK/run/$sn.apk"
  mkdir -p "$WORK/run"
  eval "cmd_build \"\$src\" -o \"\$apk\" $extra" || return 1
  pkg="$BUILT_PKG"
  [ -n "$pkg" ] || pkg=$(cat "$LAST")
  cmd_install "$apk" "$pkg" || return 1
  if [ "$UNROOTED" = 1 ] && [ "$ADB" = 0 ] && grep -qiE '^[[:space:]]*#.*dpooa' "$src"; then
    say "this app floats over other apps: after installing, allow it under Settings > Apps > $(basename "$src" .py) > Display over other apps"
  fi
  say "launching"
  as_root _launch "$pkg" || return 1
  if [ "$dispose" = 1 ] && [ "$UNROOTED" = 1 ]; then
    say "note: --dispose needs root, the app stays installed (remove it with: pockettk uninstall $pkg)"; dispose=0
  fi
  if [ "$dispose" = 1 ]; then
    mode=fg
    grep -qiE '^[[:space:]]*#.*dpooa' "$src" && mode=closed
    grep -qiE '^[[:space:]]*#.*(use:.*datasaving|datasaving)' "$src" && say "note: datasaving data is deleted together with the app when it is disposed"
    command -v su >/dev/null 2>&1 || die "no su found"
    su -c "nohup sh '$MODDIR/apkapple.sh' _watch '$pkg' $mode '$apk' '' </dev/null >/dev/null 2>&1 &"
    if [ "$mode" = closed ]; then say "will be removed when the overlay is fully closed (x button)"
    else say "will be removed the moment it leaves the foreground"; fi
  else
    [ "$KEEP_APK" = 1 ] || rm -f "$apk"      # the system installer reads it a moment later
  fi
  if [ "$follow" = 1 ]; then cmd_logs "$pkg" -f; else say "errors/prints:  pockettk logs"; fi
}

# internal (root): pockettk _logs PKG FLAGS
cmd__logs() {
  pkg="$1"; shift
  follow=0 errs=0 clear=0
  for f in "$@"; do case "$f" in -f) follow=1;; -e) errs=1;; -c) clear=1;; esac; done
  if [ "$clear" = 1 ]; then logcat -c; rm -f "/data/data/$pkg/files/error.log"; echo "logs cleared"; return 0; fi
  if [ "$errs" = 1 ]; then
    if [ -f "/data/data/$pkg/files/error.log" ]; then cat "/data/data/$pkg/files/error.log"; else echo "no errors recorded for $pkg"; fi
    return 0
  fi
  filt='s/^[A-Z]\/[^(]*( *[0-9]*): //'
  if [ "$follow" = 1 ]; then
    echo "-- following $pkg (Ctrl+C to stop) --"
    logcat -v brief -s ApkApple:V System.out:I AndroidRuntime:E | sed "$filt"
  else
    out=$(logcat -d -v brief -s ApkApple:V System.out:I AndroidRuntime:E | sed "$filt")
    if [ -n "$out" ]; then echo "$out"
    elif [ -f "/data/data/$pkg/files/error.log" ]; then echo "(logcat empty; saved errors:)"; cat "/data/data/$pkg/files/error.log"
    else echo "(no output yet for $pkg)"; fi
  fi
}

# pkg_for_base NAME -> package of the app built from NAME.py: pockettk.ssdes.NAME, or an older package of that name when only that is installed
pkg_for_base() {
  _pn="${PKG_PREFIX}$1"
  as_root _has "$_pn" && { echo "$_pn"; return; }
  for _lp in $LEGACY_PKG_PREFIXES; do
    as_root _has "${_lp}$1" && { echo "${_lp}$1"; return; }
  done
  echo "$_pn"
}

cmd_logs() {
  pkg="" flags=""
  while [ $# -gt 0 ]; do
    case "$1" in
      -f|-e|-c) flags="$flags $1"; shift;;
      *.py) b=$(safe_name "$(basename "$1" .py)"); case "$b" in [0-9]*) b="a$b";; esac; pkg=$(pkg_for_base "$b"); shift;;
      *) pkg="$1"; shift;;
    esac
  done
  [ -n "$pkg" ] || { [ -f "$LAST" ] && pkg=$(cat "$LAST"); }
  [ -n "$pkg" ] || die "no app yet - run one first: pockettk run app.py"
  as_root _logs "$pkg" $flags
}

# internal (root): list / remove
cmd__list() {
  pm list packages 2>/dev/null | sed 's/^package://' \
    | awk -v p="$PKG_PREFIX $LEGACY_PKG_PREFIXES" 'BEGIN { n = split(p, a, " ") } { for (i = 1; i <= n; i++) if (index($0, a[i]) == 1) { print; break } }'
}
cmd__uninstall() { for p in "$@"; do pm uninstall "$p" 2>&1 | sed "s/^/$p: /"; done; }

cmd_list() {
  out=$(as_root _list)
  [ -n "$out" ] && echo "$out" || echo "(no PocketTk apps installed)"
}

cmd_uninstall() {
  case "$1" in
    "") [ -f "$LAST" ] || die "usage: pockettk uninstall PACKAGE|app.py|--all"; pkgs=$(cat "$LAST");;
    --all|-a) pkgs=$(as_root _list);;
    *.py) b=$(safe_name "$(basename "$1" .py)"); case "$b" in [0-9]*) b="a$b";; esac; pkgs=$(pkg_for_base "$b");;
    *) pkgs="$1";;
  esac
  [ -n "$pkgs" ] || { echo "nothing to remove"; return 0; }
  as_root _uninstall $pkgs
}

# find_bundletool: path of bundletool-all-*.jar (BUNDLETOOL env, ~/bundletool.jar, ~/bundletool*.jar, Download folder)
find_bundletool() {
  [ -n "$BUNDLETOOL" ] && [ -f "$BUNDLETOOL" ] && { echo "$BUNDLETOOL"; return; }
  for f in "$HOME/bundletool.jar" "$HOME"/bundletool*.jar "$HOME"/bundletool/*.jar /sdcard/Download/bundletool*.jar "$MODDIR/tools/bundletool.jar"; do
    [ -f "$f" ] && { echo "$f"; return; }
  done
}

# sign_aab FILE [KEYSTORE] [ALIAS]: jarsigner with the upload key. Without KEYSTORE the key in $KS_DIR/upload.keystore
# is used (made on first use, random password kept in $KS_DIR/upload.pass). Google Play re-signs the app with its own key.
sign_aab() {
  _f=$1; _ks=$2; _al=$3
  if [ -z "$_ks" ]; then
    _ks="$KS_DIR/upload.keystore"; _al=upload; _pf="$KS_DIR/upload.pass"
    if [ ! -f "$_ks" ]; then
      say "creating your upload key (one time): $_ks"
      mkdir -p "$KS_DIR"
      _pw=$(tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24)
      [ -n "$_pw" ] || return 1
      tx "$KEYTOOL" -genkeypair -keystore "$_ks" -storepass "$_pw" -keypass "$_pw" -alias upload \
          -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=PocketTk upload key" >/dev/null 2>&1 || { echo "pockettk: keytool failed" >&2; return 1; }
      ( umask 077; printf '%s' "$_pw" > "$_pf" )
      say "BACK UP both files, you need the same key for every future upload:"
      say "  $_ks"
      say "  $_pf"
    fi
    [ -f "$_pf" ] || { echo "pockettk: $_pf is missing (the password of $_ks)" >&2; return 1; }
    _pw=$(cat "$_pf")
  else
    [ -f "$_ks" ] || { echo "pockettk: no such keystore: $_ks" >&2; return 1; }
    _pw=$POCKETTK_KS_PASS
    if [ -z "$_pw" ]; then printf 'password of %s: ' "$_ks" >&2; stty -echo 2>/dev/null; read -r _pw; stty echo 2>/dev/null; echo >&2; fi
    if [ -z "$_al" ]; then
      _al=$(tx "$KEYTOOL" -list -keystore "$_ks" -storepass "$_pw" 2>/dev/null | sed -n 's/^\([^,]*\), .*PrivateKeyEntry.*/\1/p' | head -1)
      [ -n "$_al" ] || { echo "pockettk: could not find a key alias in $_ks (use --ks-alias NAME)" >&2; return 1; }
    fi
  fi
  tx "$JARSIGNER" -keystore "$_ks" -storepass "$_pw" -keypass "$_pw" -sigalg SHA256withRSA -digestalg SHA-256 "$_f" "$_al" >/dev/null || return 1
  _fp=$(tx "$KEYTOOL" -list -keystore "$_ks" -storepass "$_pw" -alias "$_al" 2>/dev/null | sed -n 's/.*SHA-\?256[^:]*: *//p; s/.*SHA256: *//p' | head -1)
  [ -n "$_fp" ] && say "upload key SHA-256: $_fp"
  return 0
}

cmd_build() {
  src="" name="" pkg="" out="" inst=0 keep=0 icon="" id="" vc=1 bump=patch dbg="" aab=0 tsdk="" upks="" upalias=""
  verA=1 verB=0 verC=0 verP=0     # app version A.B.C plus a separate patch counter
  BUILT_PKG=""
  while [ $# -gt 0 ]; do
    case "$1" in
      -n|--name)    [ $# -ge 2 ] || die "-n needs a value"; name=$2; shift 2;;
      -p|--package) demo_block "-p (own package name)"; [ $# -ge 2 ] || die "-p needs a value"; pkg=$2; shift 2;;
      -o|--output)  [ $# -ge 2 ] || die "-o needs a value"; out=$2; shift 2;;
      --icon)       demo_block "--icon"; [ $# -ge 2 ] || die "--icon needs an image file"; icon=$2; shift 2;;
      --id)         demo_block "--id"; [ $# -ge 2 ] || die "--id needs a name"; id=$(safe_name "$2"); [ -n "$id" ] || die "bad --id"; shift 2;;
      --patch|--small|--medium|--large) demo_block "$1"; bump=${1#--}; shift;;
      -i|--install) inst=1; shift;;
      -k|--keep)    keep=1; shift;;
      --debug)      dbg="--debug"; shift;;
      --aab)        demo_block "--aab (Google Play bundle)"; aab=1; shift;;
      --target-sdk) demo_block "--target-sdk"; [ $# -ge 2 ] || die "--target-sdk needs a number"; tsdk=$2; shift 2;;
      --keystore)   demo_block "--keystore"; [ $# -ge 2 ] || die "--keystore needs a file"; upks=$2; shift 2;;
      --ks-alias)   [ $# -ge 2 ] || die "--ks-alias needs a name"; upalias=$2; shift 2;;
      -h|--help)    usage; return 0;;
      --ipa|--sign|--profile|--simulator|--emulate) die "--ipa (iOS) builds only work on a Mac for now: run pockettk on macOS";;
      -*)           die "unknown option $1";;
      *)            src=$1; shift;;
    esac
  done
  [ -n "$src" ] || { usage; return 1; }
  [ -f "$src" ] || die "no such file: $src"
  case "$tsdk" in ""|[0-9][0-9]) ;; *) die "--target-sdk must be a number like 36";; esac
  if [ "$aab" = 1 ]; then
    [ "$inst" = 0 ] || die "an .aab cannot be installed on the phone (it is for Google Play upload): use  pockettk build app.py --aab  without -i / run"
    [ -z "$dbg" ] || die "--debug builds cannot be .aab files"
    BUNDLETOOL=$(find_bundletool); [ -n "$BUNDLETOOL" ] || die "bundletool.jar not found (see: pockettk doctor). Download it and put it at ~/bundletool.jar"
    JARSIGNER=$(find_tool jarsigner); [ -n "$JARSIGNER" ] || die "jarsigner not found (it comes with openjdk-21) - run: pockettk doctor"
    [ -n "$tsdk" ] || tsdk=36
  else
    [ -n "$tsdk" ] || tsdk=34
  fi
  if [ -z "$icon" ] && [ -n "$id" ] && [ -f "$KS_DIR/ids/$id" ]; then
    icon=$(sed -n 's/^icon=//p' "$KS_DIR/ids/$id"); [ -f "$icon" ] || icon=""
  fi
  if [ -n "$icon" ]; then
    [ -f "$icon" ] || die "no such icon file: $icon"
    case "$icon" in *.png|*.PNG) iext=png;; *.webp|*.WEBP) iext=webp;; *.jpg|*.JPG|*.jpeg|*.JPEG) iext=jpg;; *) die "icon must be .png, .webp or .jpg";; esac
  fi
  [ "$IS_ROOT" = 0 ] || die "build as your normal Termux user (type 'exit' to leave tsu). Only installing/launching uses root."

  base=$(basename "$src" .py)
  [ -n "$name" ] || name=$base
  sn=$(safe_name "$base"); [ -n "$sn" ] || sn=app
  case "$sn" in [0-9]*) sn="a$sn";; esac
  [ -n "$pkg" ] || pkg="${PKG_PREFIX}$sn"
  if [ -n "$id" ]; then
    # An id pins the package name, so building again with the same id updates the installed app in place.
    IDF="$KS_DIR/ids/$id"
    if [ -f "$IDF" ]; then
      oldpkg=$(sed -n 's/^pkg=//p' "$IDF"); oldvc=$(sed -n 's/^vc=//p' "$IDF"); oldname=$(sed -n 's/^name=//p' "$IDF")
      pkg=${PKG_OVERRIDE:-$oldpkg}; vc=$((oldvc + 1))
      oldver=$(sed -n 's/^ver=//p' "$IDF"); oldpatch=$(sed -n 's/^patch=//p' "$IDF")
      [ -n "$oldver" ] || oldver="1.0.$((oldvc - 1))"          # id made before v2.3.1
      verA=${oldver%%.*}; _r=${oldver#*.}; verB=${_r%%.*}; verC=${_r#*.}; verP=${oldpatch:-0}
      case "$bump" in
        large)  verA=$((verA + 1)); verB=0; verC=0; verP=0;;
        medium) verB=$((verB + 1)); verC=0; verP=0;;
        small)  verC=$((verC + 1)); verP=0;;
        *)      verP=$((verP + 1));;
      esac
      [ -n "$name" ] && [ "$name" != "$base" ] || name=$oldname
    else
      case "$pkg" in "${PKG_PREFIX}$sn") pkg="${PKG_PREFIX}$id";; esac
    fi
  fi
  if [ "$aab" = 1 ]; then [ -n "$out" ] || out="$OUT_DIR/$sn.aab"; else [ -n "$out" ] || out="$OUT_DIR/$sn.apk"; fi
  vname="$verA.$verB.$verC"; [ "$verP" -gt 0 ] && vname="$vname-p$verP"

  PY=$(find_tool python3);  [ -n "$PY" ] || die "python3 not found - run: pockettk doctor"
  JAVAC=$(find_tool javac); ECJ=""
  [ -n "$JAVAC" ] || { ECJ=$(find_tool ecj); [ -n "$ECJ" ] || die "javac/ecj not found - run: pockettk doctor"; }
  AAPT2=$(find_tool aapt2); [ -n "$AAPT2" ] || die "aapt2 not found - run: pockettk doctor"
  AJAR=$(find_android_jar); [ -n "$AJAR" ] || die "android.jar not found - run: pockettk doctor"
  D8=$(find_tool d8); D8JAR=""; DX=""
  if [ -z "$D8" ]; then D8JAR=$(find_lib d8.jar); [ -n "$D8JAR" ] || { DX=$(find_tool dx); [ -n "$DX" ] || die "d8/dx not found - run: pockettk doctor"; }; fi
  SIGNER=$(find_tool apksigner); SIGJAR=""
  [ -n "$SIGNER" ] || { SIGJAR=$(find_lib apksigner.jar); [ -n "$SIGJAR" ] || die "apksigner not found - run: pockettk doctor"; }
  JAVA=$(find_tool java); KEYTOOL=$(find_tool keytool)
  [ -n "$JAVA" ] && [ -n "$KEYTOOL" ] || die "java/keytool not found - run: pockettk doctor"

  B="$WORK/build/$sn"
  rm -rf "$B"; mkdir -p "$B/classes" "$B/dex" "$WORK/tmp" "$(dirname "$out")"

  say "transpiling $src"
  tx "$PY" -I "$MODDIR/transpile.py" gen "$src" "$B" --pkg "$pkg" --label "$name" --audio-cache "$KS_DIR/audio-cache" --apple "v${VERSION#PocketTk v}${EDITION_TAG}" $dbg || die "transpile failed"
  mkdir -p "$KS_DIR"; echo "$pkg" > "$LAST"
  # The PocketTk Demo module is bundled inside every app (assets/TkDroid_Demo.zip, in the .apk and in the .aab): the
  # About page's "Download the demo" copies it out of the app, so no link is needed. The demo edition cannot contain
  # itself, so it bundles nothing.
  if [ "$EDITION" != demo ] && [ -f "$MODDIR/demo/TkDroid_Demo.zip" ]; then
    mkdir -p "$B/assets" && cp "$MODDIR/demo/TkDroid_Demo.zip" "$B/assets/TkDroid_Demo.zip" || die "could not bundle the demo module"
  fi
  # APKAPPLE_NO_RTCACHE=1 : old behaviour (compile the runtime into every app, single dex)
  XDEX=""; RT_CLASSES=""; CPATH="$AJAR"
  if [ -z "$APKAPPLE_NO_RTCACHE" ]; then
    ensure_rt || die "could not build the PocketTk runtime"
    CPATH="$AJAR:$RT_CLASSES"
  else
    mkdir -p "$B/src/apple/rt"; cp "$MODDIR"/rt/*.java "$B/src/apple/rt/"
  fi

  say "compiling Java"
  find "$B/src" -name '*.java' > "$B/sources.txt"
  # javac chatter (deprecation "Note:" lines etc.) is hidden unless the compile actually fails
  compile_java "$B/classes" "$CPATH" "$B/sources.txt" "$B/src" "$B/javac.log" || die "Java compile failed"

  say "dexing"
  dex_classes "$B/classes" "$B/dex" "$RT_CLASSES" || die "dexing failed"
  [ -f "$B/dex/classes.dex" ] || die "no classes.dex produced"

  RESARG=""
  if [ -n "$icon" ]; then
    say "adding icon"
    mkdir -p "$B/res/mipmap-xxxhdpi"
    cp "$icon" "$B/res/mipmap-xxxhdpi/ic_launcher.$iext" || die "cannot read icon"
    tx "$AAPT2" compile --dir "$B/res" -o "$B/res.zip" || die "icon rejected by aapt2 (use a valid PNG)"
    sed 's|android:allowBackup="false"|android:icon="@mipmap/ic_launcher" android:allowBackup="false"|' "$B/AndroidManifest.xml" > "$B/m.xml" && mv "$B/m.xml" "$B/AndroidManifest.xml"
    RESARG="$B/res.zip"
  fi

  # aapt2 only injects --version-code/--version-name when the manifest has none, and the generated manifest
  # has placeholders, so write the real values into it (otherwise every build stays at code 1 / "1.0").
  sed -e 's|android:versionCode="[^"]*"|android:versionCode="'"$vc"'"|' \
      -e 's|android:versionName="[^"]*"|android:versionName="'"$vname"'"|' "$B/AndroidManifest.xml" > "$B/m.xml" \
      && mv "$B/m.xml" "$B/AndroidManifest.xml" || die "could not set the version in the manifest"
  grep -q "android:versionCode=\"$vc\"" "$B/AndroidManifest.xml" || die "version code was not applied to the manifest"

  ASSETARG=""
  [ -d "$B/assets" ] && ASSETARG="-A $B/assets"
  say "linking manifest"
  PROTO=""; [ "$aab" = 1 ] && PROTO="--proto-format"
  tx "$AAPT2" link $PROTO -o "$B/base.apk" -I "$AJAR" --manifest "$B/AndroidManifest.xml" $RESARG $ASSETARG \
      --min-sdk-version 26 --target-sdk-version "$tsdk" --version-code "$vc" --version-name "$vname" || die "aapt2 link failed"

  if [ "$aab" = 1 ]; then
    say "packaging the base module"
    tx "$PY" -I "$MODDIR/transpile.py" aabpack "$B/base.apk" "$B/dex/classes.dex" "$B/base.zip" $XDEX || die "packaging failed"
    say "building the bundle"
    rm -f "$out"
    tx "$JAVA" -jar "$BUNDLETOOL" build-bundle --modules="$B/base.zip" --output="$out" || die "bundletool build-bundle failed"
    say "signing with the upload key"
    sign_aab "$out" "$upks" "$upalias" || die "signing the bundle failed"
  else
  say "packaging"
  tx "$PY" -I "$MODDIR/transpile.py" pack "$B/base.apk" "$B/dex/classes.dex" "$B/unsigned.apk" $XDEX || die "packaging failed"

  KS="$KS_DIR/apple.keystore"
  if [ ! -f "$KS" ]; then
    say "creating signing key (one time)"
    mkdir -p "$KS_DIR"
    tx "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android -alias apple \
        -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=PocketTk" >/dev/null 2>&1 || die "keytool failed"
  fi

  say "signing"
  if [ -n "$SIGNER" ]; then
    tx "$SIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$out" "$B/unsigned.apk" || die "apksigner failed"
  else
    tx "$JAVA" -jar "$SIGJAR" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$out" "$B/unsigned.apk" || die "apksigner failed"
  fi
  rm -f "$out.idsig"
  fi
  if [ -n "$id" ]; then
    mkdir -p "$KS_DIR/ids"
    absrc=$(cd "$(dirname "$src")" && pwd)/$(basename "$src")
    absic=""; [ -n "$icon" ] && absic=$(cd "$(dirname "$icon")" && pwd)/$(basename "$icon")
    [ -n "$absic" ] || absic=$(sed -n 's/^icon=//p' "$IDF" 2>/dev/null)
    printf 'pkg=%s\nvc=%s\nname=%s\nsrc=%s\nicon=%s\nver=%s\npatch=%s\napple=%s\n' "$pkg" "$vc" "$name" "$absrc" "$absic" "$verA.$verB.$verC" "$verP" "${VERSION#PocketTk v}" > "$IDF"
    say "app id '$id' -> $pkg (version $verA.$verB.$verC$([ "$verP" -gt 0 ] && echo " Patch $verP"), code $vc)"
  fi
  [ "$keep" = 1 ] || rm -rf "$B"
  say "built: $out"
  [ "$aab" = 1 ] && say "versionCode $vc, versionName $vname, target SDK $tsdk - upload this file in Play Console (every upload needs a higher versionCode: build with --id so it counts up)"
  BUILT_PKG="$pkg"
  if [ "$inst" = 1 ]; then cmd_install "$out" "$pkg"; fi
}

# pockettk update --id ID [code.py] [--icon IMG] [-n NAME] [-k]
# Rebuilds the app registered under ID and installs it over the old one (data is kept).
cmd_update() {
  id="" src="" extra="" nolaunch=0 debug=0 newpkg=1
  while [ $# -gt 0 ]; do
    case "$1" in
      --id)   [ $# -ge 2 ] || die "--id needs a name"; id=$(safe_name "$2"); shift 2;;
      --no-launch) nolaunch=1; shift;;
      --debug) debug=1; shift;;
      --new-pkg) newpkg=1; shift;;
      --keep-pkg) newpkg=0; shift;;
      -i|--install) shift;;
      -n|-o|--icon) [ $# -ge 2 ] || die "$1 needs a value"; extra="$extra $1 $(q "$2")"; shift 2;;
      -k)     extra="$extra -k"; shift;;
      --patch|--small|--medium|--large) extra="$extra $1"; shift;;
      -h|--help) usage; return 0;;
      --ipa|--sign|--profile|--simulator|--emulate) die "--ipa (iOS) builds only work on a Mac for now: run pockettk on macOS";;
      -*)     die "unknown option $1";;
      *)      src=$1; shift;;
    esac
  done
  [ -n "$id" ] || die "usage: pockettk update --id ID [code.py] [-n NAME] [--icon IMG]"
  IDF="$KS_DIR/ids/$id"
  [ -f "$IDF" ] || die "unknown app id '$id' - create it first:  pockettk run app.py --id $id   (see: pockettk ids)"
  [ -n "$src" ] || src=$(sed -n 's/^src=//p' "$IDF")
  [ -f "$src" ] || die "source not found: $src - pass the file:  pockettk update --id $id code.py"
  say "updating '$id' ($(sed -n 's/^pkg=//p' "$IDF"))"
  # an app still on an old package (tkdroid.ssdes.* and older) is built and installed as pockettk.ssdes.* instead: data copied, old app removed (--keep-pkg: stay)
  mig_old="" mig_new=""
  if [ "$newpkg" = 1 ] && [ "$debug" = 0 ]; then
    _op=$(sed -n 's/^pkg=//p' "$IDF")
    if _sfx=$(legacy_suffix "$_op"); then
      mig_old=$_op; mig_new="${PKG_PREFIX}${_sfx}"
      say "package: $mig_old -> $mig_new"
    fi
  fi
  if [ "$debug" = 1 ]; then
    [ "$IS_ROOT" = 0 ] || die "run this as your normal Termux user (type 'exit' to leave tsu)."
    [ "$UNROOTED" = 0 ] || die "update --debug needs root (it saves and restores the installed version)"
    DDIR="$KS_DIR/debug"; mkdir -p "$DDIR"
    rv="$DDIR/$id-revert.apk"; prev="$DDIR/$id-prev.apk"
    [ ! -f "$rv" ] || die "an earlier debug session of '$id' has not been restored yet. Run:  pockettk revert --id $id"
    oldpkg=$(sed -n 's/^pkg=//p' "$IDF")
    prevver=$(sed -n 's/^ver=//p' "$IDF"); _pp=$(sed -n 's/^patch=//p' "$IDF"); [ "${_pp:-0}" -gt 0 ] && prevver="$prevver Patch $_pp"
    canrevert=0
    # keep the installed APK and the id record: they are what the app goes back to
    if as_root _backup "$oldpkg" "$prev" && [ -s "$prev" ]; then canrevert=1; else rm -f "$prev"; fi
    cp "$IDF" "$DDIR/$id.ids" || die "cannot save the id record"
    extra="$extra --debug"
  fi
  PKG_OVERRIDE=$mig_new
  eval "cmd_build \"\$src\" --id \"\$id\" -i $extra" || { PKG_OVERRIDE=""; [ "$debug" = 1 ] && rm -f "$prev" "$DDIR/$id.ids"; return 1; }
  PKG_OVERRIDE=""
  if [ -n "$mig_new" ]; then
    # the new package is a different app to Android: copy the old app's saved data over, then remove the old app
    if as_root _migrate_data "$mig_old" "$mig_new"; then
      say "moved '$id': data copied, old app $mig_old removed"
    else
      say "warning: '$id' is installed as $mig_new, but its data could not be copied - the old app $mig_old was kept"
    fi
    [ -f "$LAST" ] && [ "$(cat "$LAST")" = "$mig_old" ] && echo "$mig_new" > "$LAST"
  fi
  if [ "$debug" = 1 ]; then
    if [ "$canrevert" = 1 ]; then
      # The old version needs a HIGHER version code than the debug build to install over it (Android refuses
      # downgrades), so make a copy of it with the code raised by one and sign it with our key.
      newvc=$(sed -n 's/^vc=//p' "$IDF"); revvc=$((newvc + 1))
      PYX=$(find_tool python3)
      if tx "$PYX" -I "$MODDIR/transpile.py" setvc "$prev" "$DDIR/$id-unsigned.apk" --code "$revvc" \
         && sign_apk "$DDIR/$id-unsigned.apk" "$rv"; then
        # the id record goes back to what it was, but the version code stays ahead of everything installed
        sed "s/^vc=.*/vc=$revvc/" "$DDIR/$id.ids" > "$IDF"
      else
        canrevert=0; rm -f "$rv"
        say "could not prepare the old version; the debug build will stay installed"
      fi
    else
      say "no installed copy of '$id' to go back to; the debug build will stay installed"
    fi
    rm -f "$prev" "$DDIR/$id-unsigned.apk" "$DDIR/$id.ids"
  fi
  [ "$nolaunch" = 1 ] || as_root _launch "$BUILT_PKG"
  if [ "$debug" = 1 ] && [ "$canrevert" = 1 ]; then
    command -v su >/dev/null 2>&1 || die "no su found"
    su -c "nohup sh '$MODDIR/apkapple.sh' _debugwatch '$BUILT_PKG' '$rv' </dev/null >/dev/null 2>&1 &"
    say "debug build running with a log panel; when you close the app, v$prevver comes back (data kept)"
  else
    [ -n "$mig_new" ] || say "updated; data of the installed app was kept"
  fi
}

# pockettk revert --id ID : install the version saved by `update --debug` over the app (if it was not restored)
cmd_revert() {
  id=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --id) [ $# -ge 2 ] || die "--id needs a name"; id=$(safe_name "$2"); shift 2;;
      *) die "usage: pockettk revert --id ID";;
    esac
  done
  [ -n "$id" ] || die "usage: pockettk revert --id ID"
  [ -f "$KS_DIR/ids/$id" ] || die "unknown app id '$id' (see: pockettk ids)"
  [ -f "$KS_DIR/debug/$id-revert.apk" ] || die "nothing to restore for '$id'"
  as_root _revert "$(sed -n 's/^pkg=//p' "$KS_DIR/ids/$id")" "$KS_DIR/debug/$id-revert.apk"
}

# pockettk update-all [--force] [-j N] [--patch|--small|--medium|--large]
# Rebuilds every app that has an id with THIS version of PocketTk (new runtime, new built-ins) and installs
# it over the old one; data is kept. Apps already built with this version are skipped unless --force.
# The runtime is compiled once (first app) and reused; several apps are then built at the same time (-j).
cmd_update_all() {
  force=0 flags="" jobs=""
  while [ $# -gt 0 ]; do
    case "$1" in
      -f|--force) force=1; shift;;
      -j|--jobs) [ $# -ge 2 ] || die "$1 needs a number"; jobs=$2; shift 2;;
      -j[0-9]*) jobs=${1#-j}; shift;;
      --patch|--small|--medium|--large) flags="$flags $1"; shift;;
      -h|--help) usage; return 0;;
      *) die "unknown option $1  (usage: pockettk update-all [--force] [-j N] [--patch|--small|--medium|--large])";;
    esac
  done
  case "$jobs" in *[!0-9]*|0) die "-j needs a number of 1 or more";; esac
  [ "$IS_ROOT" = 0 ] || die "run this as your normal Termux user (type 'exit' to leave tsu)."
  [ -d "$KS_DIR/ids" ] && ls "$KS_DIR/ids" 2>/dev/null | grep -q . || { echo "no app ids yet (use:  pockettk run app.py --id NAME)"; return 0; }
  if [ -z "$jobs" ]; then      # half the cores, 1 to 3
    jobs=$(nproc 2>/dev/null); case "$jobs" in ''|*[!0-9]*) jobs=2;; esac
    jobs=$((jobs / 2)); [ "$jobs" -ge 1 ] || jobs=1; [ "$jobs" -le 3 ] || jobs=3
  fi
  cur=${VERSION#PocketTk v}
  installed=$(as_root _list)
  n_ok=0 n_skip=0 n_fail=0 failed="" todo="" sns="" dup=0
  for f in "$KS_DIR"/ids/*; do
    [ -f "$f" ] || continue
    uid=$(basename "$f")
    built=$(sed -n 's/^apple=//p' "$f")
    upkg=$(sed -n 's/^pkg=//p' "$f")
    legacy=0; _lsfx=$(legacy_suffix "$upkg") && legacy=1     # still on an old package (tkdroid.ssdes.* and older)
    if [ "$force" = 0 ] && [ "$built" = "$cur" ] && [ "$legacy" = 0 ]; then
      say "$uid: already built with PocketTk $cur - skipped"; n_skip=$((n_skip + 1)); continue
    fi
    if [ -n "$installed" ] && ! printf '%s\n' "$installed" | grep -qxF "$upkg"; then
      if [ "$legacy" = 1 ]; then     # nothing installed to move: just rename the package in the record
        sed "s/^pkg=.*/pkg=${PKG_PREFIX}${_lsfx}/" "$f" > "$f.tmp.$$" && cat "$f.tmp.$$" > "$f"; rm -f "$f.tmp.$$"
        say "$uid: not installed - package name changed to ${PKG_PREFIX}${_lsfx}"
      fi
      say "$uid: not installed - skipped (install it again with: pockettk update --id $uid)"; n_skip=$((n_skip + 1)); continue
    fi
    todo="$todo $uid"
    # two apps whose source files have the same name share a build folder and output file: never build those side by side
    _sn=$(safe_name "$(basename "$(sed -n 's/^src=//p' "$f")" .py)"); [ -n "$_sn" ] || _sn=app
    case "$_sn" in [0-9]*) _sn="a$_sn";; esac
    case " $sns " in *" $_sn "*) dup=1;; esac
    sns="$sns $_sn"
  done
  set -- $todo; total=$#
  if [ "$total" -gt 1 ] && [ "$jobs" -gt 1 ] && [ "$dup" = 1 ]; then
    say "some apps have source files with the same name - building one at a time"; jobs=1
  fi
  [ "$total" -ge "$jobs" ] || jobs=$total

  if [ "$jobs" -le 1 ]; then
    i=0
    for uid in $todo; do
      i=$((i + 1))
      built=$(sed -n 's/^apple=//p' "$KS_DIR/ids/$uid")
      say "== [$i/$total] $uid (built with ${built:-an older version}) -> $cur"
      if ( cmd_update --id "$uid" --no-launch $(ua_newpkg "$uid") $flags ); then n_ok=$((n_ok + 1))
      else n_fail=$((n_fail + 1)); failed="$failed $uid"; fi
    done
  else
    say "updating $total apps, $jobs at a time"
    RUNDIR="$WORK/update-all"; rm -rf "$RUNDIR"; mkdir -p "$RUNDIR" "$WORK/tmp"
    pending="" bgpids="" started=0 finished=0
    trap 'for _p in $bgpids; do kill $_p 2>/dev/null; done; echo "pockettk: stopped" >&2; exit 130' INT TERM
    for uid in $todo; do
      started=$((started + 1))
      # the first app goes alone: it compiles the runtime that all the others reuse
      slots=$jobs; [ "$started" -gt 2 ] || slots=1
      while :; do
        _reap_n=0; for _u in $pending; do _reap_n=$((_reap_n + 1)); done
        [ "$_reap_n" -lt "$slots" ] && break
        sleep 0.5; ua_reap
      done
      ( ( cmd_update --id "$uid" --no-launch $(ua_newpkg "$uid") $flags ) >"$RUNDIR/$uid.log" 2>&1
        echo $? >"$RUNDIR/$uid.rc.t"; mv "$RUNDIR/$uid.rc.t" "$RUNDIR/$uid.rc" ) &
      bgpids="$bgpids $!"; pending="$pending $uid"
    done
    while [ -n "$(echo $pending)" ]; do sleep 0.5; ua_reap; done
    trap - INT TERM
    rm -rf "$RUNDIR"
  fi
  say "update-all: $n_ok updated, $n_skip skipped, $n_fail failed"
  [ "$n_ok" -gt 0 ] && say "open the apps again to see the new version"
  [ "$n_fail" = 0 ] || { say "failed:$failed  (retry one with: pockettk update --id NAME)"; return 1; }
}

# used by cmd_update_all: --new-pkg for an id that is still on an old package
ua_newpkg() {
  legacy_suffix "$(sed -n 's/^pkg=//p' "$KS_DIR/ids/$1")" >/dev/null && echo --new-pkg
}

# used by cmd_update_all: report the apps whose background build has finished (uses its variables)
ua_reap() {
  _np=""
  for _u in $pending; do
    if [ -f "$RUNDIR/$_u.rc" ]; then
      finished=$((finished + 1))
      if [ "$(cat "$RUNDIR/$_u.rc")" = 0 ]; then
        n_ok=$((n_ok + 1)); say "[$finished/$total] $_u updated"
        grep -e '^warning' -e '^- warning' "$RUNDIR/$_u.log" 2>/dev/null
      else
        n_fail=$((n_fail + 1)); failed="$failed $_u"; say "[$finished/$total] $_u FAILED:"; cat "$RUNDIR/$_u.log"
      fi
    else _np="$_np $_u"; fi
  done
  pending="$_np"
}

# internal (root): pockettk _migrate_data OLDPKG NEWPKG -> copy the old app's saved data into the new app, then uninstall the old one
cmd__migrate_data() {
  _od="/data/data/$1" _nd="/data/data/$2"
  if [ -d "$_od" ] && [ -d "$_nd" ]; then
    am force-stop "$1" >/dev/null 2>&1
    _u=$(stat -c %u "$_nd") || return 1
    for _s in files shared_prefs databases; do
      [ -d "$_od/$_s" ] || continue
      rm -rf "$_nd/$_s"
      cp -a "$_od/$_s" "$_nd/$_s" || return 1
      chown -R "$_u:$_u" "$_nd/$_s" || return 1
    done
    restorecon -R "$_nd" >/dev/null 2>&1
  fi
  grant_overlay "$2"
  pm uninstall "$1" >/dev/null 2>&1
}

# internal (root): pockettk _has PKG  -> success when PKG is installed
cmd__has() { pm path "$1" >/dev/null 2>&1; }
# internal (root): pockettk _pkgs  -> every installed package name
cmd__pkgs() { pm list packages 2>/dev/null | sed 's/^package://'; }

# forget an id: its record plus anything left over from update --debug
drop_id() {
  rm -f "$KS_DIR/ids/$1" "$KS_DIR/debug/$1-revert.apk" "$KS_DIR/debug/$1-prev.apk" "$KS_DIR/debug/$1-unsigned.apk" "$KS_DIR/debug/$1.ids"
}

# pockettk delete --id ID [-y] : uninstall the app (data included) and remove the id
cmd_delete() {
  id="" yes=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --id)     [ $# -ge 2 ] || die "--id needs a name"; id=$(safe_name "$2"); shift 2;;
      -y|--yes) yes=1; shift;;
      -h|--help) usage; return 0;;
      *) die "usage: pockettk delete --id ID [-y]";;
    esac
  done
  [ -n "$id" ] || die "usage: pockettk delete --id ID [-y]"
  [ "$IS_ROOT" = 0 ] || die "run this as your normal Termux user (type 'exit' to leave tsu)."
  IDF="$KS_DIR/ids/$id"
  [ -f "$IDF" ] || die "unknown app id '$id' (see: pockettk ids)"
  pkg=$(sed -n 's/^pkg=//p' "$IDF")
  if [ "$yes" = 0 ] && [ -t 0 ]; then
    printf "Delete '%s' (%s)? The app and its saved data are removed. Your script is kept. [y/N] " "$id" "$pkg"
    read -r ans
    case "$ans" in y|Y|yes|YES) ;; *) echo "cancelled"; return 1;; esac
  fi
  if as_root _has "$pkg"; then
    as_root _uninstall "$pkg"
    as_root _has "$pkg" && die "could not uninstall $pkg - the id was kept"
  fi
  dsrc=$(sed -n 's/^src=//p' "$IDF")
  drop_id "$id"
  say "deleted '$id': app uninstalled, id removed${dsrc:+ (your script $dsrc is untouched)}"
}

# pockettk delete-uninstalled : remove the ids whose app is no longer installed
cmd_delete_uninstalled() {
  [ "$IS_ROOT" = 0 ] || die "run this as your normal Termux user (type 'exit' to leave tsu)."
  [ -d "$KS_DIR/ids" ] && ls "$KS_DIR/ids" 2>/dev/null | grep -q . || { echo "no app ids yet"; return 0; }
  all=$(as_root _pkgs)
  [ -n "$all" ] || die "could not read the list of installed apps (is root available?) - nothing deleted"
  n=0 names=""
  for f in "$KS_DIR"/ids/*; do
    [ -f "$f" ] || continue
    uid=$(basename "$f"); upkg=$(sed -n 's/^pkg=//p' "$f")
    [ -n "$upkg" ] || continue
    if ! printf '%s\n' "$all" | grep -qxF "$upkg"; then
      drop_id "$uid"; n=$((n + 1)); names="$names $uid"
    fi
  done
  if [ "$n" = 0 ]; then say "every id still has its app installed - nothing to delete"
  else say "deleted $n id(s) of uninstalled apps:$names"; fi
}

cmd_ids() {
  [ -d "$KS_DIR/ids" ] && ls "$KS_DIR/ids" 2>/dev/null | grep -q . || { echo "no app ids yet (use:  pockettk run app.py --id NAME)"; return 0; }
  for f in "$KS_DIR"/ids/*; do
    _v=$(sed -n 's/^ver=//p' "$f"); [ -n "$_v" ] || _v="1.0.$(( $(sed -n 's/^vc=//p' "$f") - 1 ))"
    _p=$(sed -n 's/^patch=//p' "$f"); [ "${_p:-0}" -gt 0 ] && _v="$_v Patch $_p"
    _a=$(sed -n 's/^apple=//p' "$f"); _o=""; [ "$_a" = "${VERSION#PocketTk v}" ] || _o="  (built with PocketTk ${_a:-<2.3.1}; pockettk update-all)"
    printf '%-14s %s  v%s  %s%s\n' "$(basename "$f")" "$(sed -n 's/^pkg=//p' "$f")" "$_v" "$(sed -n 's/^src=//p' "$f")" "$_o"
  done
}

case "$EDITION:$1" in
  demo:update|demo:update-all|demo:updateall|demo:revert|demo:ids|demo:delete|demo:delete-uninstalled|demo:deleteuninstalled)
    demo_block "pockettk $1";;
esac

# pockettk set-email [ADDRESS]: the address the About > Request page sends requests to. It is saved in
# ~/.pockettk/request_email and put back by the module every time it is flashed, so it only has to be set once.
cmd_set_email() {
  [ "$IS_ROOT" = 0 ] || die "run this as your normal Termux user (type 'exit' to leave tsu)."
  if [ -z "$1" ]; then
    _cur=$(sed -n 's/.*REQUEST_EMAIL = "\([^"]*\)".*/\1/p' "$MODDIR/rt/Contact.java" | head -n 1)
    echo "request email: ${_cur:-<not set>}"; return 0
  fi
  case "$1" in
    *[!A-Za-z0-9._%+@-]*|@*|*@|*@*@*) die "that does not look like an email address";;
    *@*.*) ;;
    *) die "that does not look like an email address";;
  esac
  ( umask 077; mkdir -p "$KS_DIR"; printf '%s\n' "$1" > "$KS_DIR/request_email" ) || die "could not save $KS_DIR/request_email"
  [ -w "$MODDIR/rt/Contact.java" ] || die "saved, but $MODDIR/rt/Contact.java cannot be written: flash the module again to apply it"
  sed -i "s|REQUEST_EMAIL = \"[^\"]*\"|REQUEST_EMAIL = \"$1\"|" "$MODDIR/rt/Contact.java" || die "could not update Contact.java"
  say "request email set to $1 (apps built from now on use it; it survives re-flashing)"
}

case "$1" in
  gui|make-gui|makegui) die "PocketTk has no window on Android: use the commands (pockettk help). The window apps are for macOS, Windows and Linux.";;
  set-email|setemail) shift; cmd_set_email "$@";;
  update)  shift; cmd_update "$@";;
  update-all|updateall) shift; cmd_update_all "$@";;
  revert)  shift; cmd_revert "$@";;
  ids)     cmd_ids;;
  delete)  shift; cmd_delete "$@";;
  delete-uninstalled|deleteuninstalled) cmd_delete_uninstalled;;
  run)     shift; cmd_run "$@";;
  build)   shift; cmd_build "$@";;
  logs|log) shift; cmd_logs "$@";;
  check)   shift; cmd_check "$@";;
  install) shift; cmd_install "$@";;
  list)    cmd_list;;
  uninstall|remove) shift; cmd_uninstall "$@";;
  demo)    cmd_demo;;
  doctor)  cmd_doctor;;
  _grant|_launch|_watch|_logs|_list|_uninstall|_backup|_revert|_debugwatch|_has|_pkgs|_migrate_data) c=$1; shift; "cmd_$c" "$@";;
  version|-v|--version) echo "$VERSION$EDITION_TAG";;
  help|-h|--help|"") case "$2" in --brief|-b|brief) brief;; *) usage;; esac;;
  *) usage; exit 1;;
esac
