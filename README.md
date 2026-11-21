# PocketTk v4.1.2

Turn a Python **tkinter** script into an installable Android APK (or, on a Mac, an iOS .ipa). Runs in Termux (rooted or not, as a KernelSU module or on its own), or on Windows, Linux and macOS.
(Python is transpiled to Java, or to Swift for iOS; there is no embedded CPython.)

**PocketTk was called TkDroid** (and ApkApple before that). Old apps, folders and the signing key are moved over automatically, see "Folders and the move".

## About this project
Made by SSDES. It started as a fun idea.

## Commands (run in Termux as a normal user, not tsu)
These still work for anyone who likes a terminal; the window runs the same commands.
    pockettk run app.py [--dispose] [--logs] [--debug] [--icon IMG] [--id NAME]   build + install + launch
    pockettk build app.py [-n NAME] [-p PKG] [-o OUT.apk] [--icon IMG] [--id NAME] [--debug] [-i] [-k]
    pockettk build app.py --aab [--id NAME] [--target-sdk N] [--keystore FILE [--ks-alias A]]   Android App Bundle for Google Play
    pockettk update --id NAME [code.py] [--patch|--small|--medium|--large] [--debug]
    pockettk revert --id NAME                  restore the version saved by update --debug (normally automatic)
    pockettk update-all [--force] [-j N] [--patch|--small|--medium|--large]   (also moves apps still on tkdroid.ssdes.*, tkdroid.molt.* or apple.app.* to pockettk.ssdes.*)
    pockettk ids
    pockettk delete --id NAME [-y]            uninstall the app (its data goes too) and remove the id
    pockettk delete-uninstalled                remove the ids of apps that are no longer installed
    pockettk logs [PKG|app.py] [-f] [-e] [-c]  errors/prints with Python line numbers
    pockettk check app.py [-v]                 transpile only, show warnings (-v: the generated Java too)
    pockettk install FILE.apk [PKG]
    pockettk list | uninstall [PKG|app.py|--all] | demo | doctor
    pockettk gui                               the same thing as a window on the phone (see "The window on Android")
    pockettk help [--brief]                    --brief: what PocketTk is and how to use it, in a few lines
PocketTk was called **TkDroid** before (and ApkApple before that). The old names `tkdroid`, `apkapple` and `apple` are **deprecated**: they still work for now but print a warning and will be removed in a future version. Use `pockettk`. First run: `pockettk doctor`. `pockettk demo` writes example scripts.

### Unrooted phone (Termux, no root)
    sh termux_setup.sh        once, from the unzipped folder, as the normal Termux user
    pockettk version | pockettk doctor | pockettk run app.py
Building works exactly as on a rooted phone. Without root, `run`/`update` hand the APK to Android's installer: tap **Install**, then **Open**. With adb (`pkg install android-tools`, Wireless debugging, `adb pair`, `adb connect`) installs are silent and launch/logs/uninstall work. Not available without root: `--dispose`, `update --debug`, `revert`, moving old-package apps, and logs without adb. Apps that float over other apps need "Display over other apps" allowed once in Settings.

## Windows / Linux / macOS / no Android phone (Patch 5 and later)
The KernelSU module above works as before. Patch 5 added `pockettk.py`, a stdlib-only Python 3.8+ version of the same tool for a PC. It needs Java (JDK 8+).

    python pockettk.py setup                  one time: downloads the Android SDK tools (or set POCKETTK_SDK)
    python pockettk.py doctor
    python pockettk.py build app.py [--id NAME] [--icon IMG]
    python pockettk.py build app.py --aab [--id NAME] [--target-sdk N] [--keystore FILE [--ks-alias A]]   Google Play bundle
    python pockettk.py run app.py --preview   PC phone window instead of a phone
    python pockettk.py update --id NAME [code.py] --preview
    python pockettk.py preview app.py [--width W] [--height H] [--dpi D] [--cpu 5-100]
    python pockettk.py ids | logs | check | version
Linux and macOS: `./pockettk ...`   Windows: `pockettk.cmd ...`. On a Mac install a JDK first (`brew install openjdk`; PocketTk also finds `/usr/libexec/java_home` and the Homebrew folders). The Mac port is untested on real hardware. With a phone attached over adb, `run`/`update` install and launch it; without one they open the preview.

### The window app (no terminal): macOS, Windows, Linux
The same window on all three systems, and it is a terminal: a prompt with history and Tab completion, and our own commands.

    ~ $ pick                                  choose your script (system file chooser), or drop a .py on the window
    ~ $ build notes.py --id notes --small     transpile and export an APK
    ~ $ build notes.py --aab --id notes       a Google Play bundle (--keystore FILE to sign with your key)
    ~ $ check | preview | install | devices | update notes | apps | doctor | setup | cd | ls | reveal | outdir | theme | help

`help` lists the commands, `help build` explains the options. Tab completes commands, options, app ids and paths (a second Tab lists the choices); Up/Down is the history; Ctrl+C stops a build; Ctrl+L clears. Paths with spaces go in quotes. `theme` switches colours (dusk, green, paper, mono). Builds go to `~/PocketTk` (`outdir` changes it) and the path in the output is clickable (or type `reveal`).

| System | File | Install |
|---|---|---|
| macOS (Apple Silicon, Intel) | `PocketTk-<version>-<arch>.dmg` | open it, drag **PocketTk** to Applications |
| Windows 10/11 (x64) | `PocketTk-<version>-windows-x64-setup.exe` or `...-windows-x64.zip` | run the installer (no admin rights), or unzip and open `PocketTk.cmd` |
| Linux (x86_64, aarch64) | `PocketTk-<version>-linux-<arch>.AppImage` | one file: make it executable (file manager: Properties > Permissions > Allow executing) and double-click. No terminal |
| Linux, as an archive | `PocketTk-<version>-linux-<arch>.tar.gz` | unpack, run `./install.sh` (menu entry) or `./pockettk-gui` |

- **Everything it needs is installed by the app itself.** Each package carries its own Python and pywebview. On the first start the window downloads a Java JDK (Eclipse Temurin) and Google's Android build tools into `~/.pockettk` (`%USERPROFILE%\.pockettk` on Windows), after you accept Google's Android SDK license in the window. No administrator password, nothing outside that folder. `doctor` shows what is installed and `setup` repeats the install. (The SDK cannot be bundled: its license has to be accepted by you.)
- **No terminal, no network port.** The page is shown in a native window (pywebview) and talks to the app through its built-in bridge; there is no local web server. Builds run as a child process of `pockettk.py build ...`, so the log is what the terminal would show, and Ctrl+C (or `cancel`) stops the whole build.
- **Open the file chooser straight away:** `pockettk gui --pick`, or `pockettk gui app.py` to start with a file chosen. (The packages also contain a command line tool: `pockettk.cmd` on Windows, `./pockettk` on Linux.)
- **macOS first open:** signed ad hoc, not notarized. macOS 14 or older: right-click the app > Open. macOS 15 or newer: open it once, then System Settings > Privacy & Security > **Open Anyway**. Apple Silicon: if Google's `aapt2` does not start, type `rosetta` to install it.
- **Windows:** not code-signed, so SmartScreen says "Windows protected your PC": More info > Run anyway. The window uses Microsoft Edge WebView2 (in Windows 11 and current Windows 10; otherwise install the Evergreen Runtime from Microsoft). Errors go to `%USERPROFILE%\.pockettk\gui.log`.
- **Linux:** the window uses Qt WebEngine from inside the package (about 250 MB, works on any common distribution). A very bare system may need a few desktop libraries; `READ ME FIRST.txt` lists them for Debian/Ubuntu. With your own Python you can also run `pip install pywebview` (GTK/WebKit2GTK via `python3-gi gir1.2-webkit2-4.1`, or `pywebview[qt]`) and `pockettk gui`.
- **Building the packages:** `packaging/macos/build_app.sh`, `packaging\windows\build_app.ps1` (zip, plus the installer when Inno Setup is installed), `packaging/linux/build_app.sh`, each on its own system, or the GitHub Actions workflow `.github/workflows/apps.yml` (builds all of them and attaches them to a `v*` release). Tests: `python -m unittest tests.test_deps tests.test_gui tests.test_packaging tests.test_termux_connect`, `node tests/test_shell.js` and `node tests/test_core.js`.
- **Not tested on real systems:** the windows and the packages were never run on a real Mac, Windows or Linux desktop. The installer, the build scripts and the Python side are tested here with stubs only. Android (Termux) has no window yet.

### Testing the desktop apps on GitHub (no computer needed)
Open the repository on github.com > **Actions** > **Desktop test** > **Run workflow** (the workflow file must be on the default branch to be listed). Three jobs run on Windows, macOS and Linux: **tests** (the full suite), **window** (opens the real PocketTk window, checks that the page and the Python bridge answer, attaches a screenshot) and **build an APK** (installs the JDK and Android SDK the way the window does on first run, then builds an APK and a Google Play bundle and checks what came out). A green tick means it worked on that system; open a red one to read the log. The summary page has a table, and the screenshots, APK and bundle can be downloaded from **Artifacts** at the bottom of the run. The two switches skip the window or the build when you only want the tests. The release workflow (**Desktop apps**) is separate and still runs when you push a `v*` tag.

### Google Play bundle (`--aab`)
Works like the phone version: the `.aab` is signed with an upload key kept in `~/.pockettk/upload.keystore` (password in `upload.pass`; made on first use, **back both up**), or with your own `--keystore` (password from `POCKETTK_KS_PASS` or a prompt). Target SDK defaults to 36 for bundles. Every Play upload needs a higher version code, so build with `--id` so it counts up. It needs `bundletool` (`pockettk setup` downloads it, or put `bundletool-all-VERSION.jar` at `~/.pockettk/bundletool.jar`, or set `BUNDLETOOL`) and the JDK's `jarsigner`. An `.aab` can't be installed on a phone. Tested against a stand-in bundletool only, not Google's.

### Preview (approximate)
Runs your app's real generated Java on the desktop JVM inside a phone-shaped window (bezel, status bar, nav bar). The side panel sets device preset, Width, Height, density, CPU strength and zoom, plus Rotate, Reload code and Back/Home. Settings persist in ~/.pockettk/preview.properties.
It is a **Preview**, not Android: fonts, spacing and speed differ; CPU strength is a simulated slowdown; SQLite, audio and real intents are not available. Always test on a phone before release.

## iOS: `--ipa` (macOS only, experimental)
    pockettk build app.py --ipa                          unsigned .ipa
    pockettk build app.py --ipa --sign "Apple Development: Name (TEAMID)" --profile app.mobileprovision
    pockettk run app.py --ipa --simulator                runs a real iOS build in the iOS Simulator on your Mac
    pockettk update --id NAME --ipa [--simulator]        same ids and version bumps as Android
`--ipa` translates your Python into **Swift** (`ipa/pyswift.py`) and compiles it with Xcode's `swiftc` against a small UIKit runtime (`ipa/runtime/*.swift`). It needs a Mac with the full Xcode app (for the iOS SDK). On Windows, Linux and Termux it stops with a message. `--emulate` is a synonym for `--simulator`.

Signing needs an Apple Developer account: a free Apple ID gives a profile that expires after 7 days, a paid account gives longer-lived ones. Without `--sign` you get an unsigned `.ipa` that tools like Sideloadly or AltStore can sign. To install on a plugged-in iPhone PocketTk uses `ideviceinstaller` (`brew install ideviceinstaller`) when it is there.

**What it translates (version 1):** functions (defaults, `*args`, `**kwargs`, nested, `lambda`), classes (methods, attributes, `super()`, inheritance between your own classes), `if`/`while`/`for`, comprehensions, f-strings and `%`/`.format`, the usual `str`/`list`/`dict` methods, `math`, `random`, and tkinter's `Tk`, `Frame`, `Label`, `Button`, `Entry`, `Checkbutton`, `StringVar`/`IntVar`/`DoubleVar`/`BooleanVar`, `pack`/`grid`, `after`, and `messagebox.showinfo/showwarning/showerror`. Anything else (try/except, with, imports, Canvas, Text, Listbox, classes that extend tkinter widgets, `askyesno`...) stops with the Python line number.
**Differences from Android builds:** no `#use:` directives, no About page or version label, no icon yet. A runtime error shows an alert and carries on with `None` instead of crashing. Layout is approximate (`pack` stacks top to bottom, `grid` makes rows).
**Status:** written without access to a Mac, so it has never been compiled. The first run on a Mac may hit Swift compile errors in the runtime: if so, send the `swiftc` output.

## Directives (comments at the top of the script)
    #use: fppe, dpooa, filepick, datasaving, audio, termux-connect, builtin-editing     (any number, comma separated)
    #builtins: version-label: disable
    #dpooa: draggable
    #audio: dQw4w9WgXcQ
    #playlist: id1 id2 id3
- **fppe** – floating-point fix: 0.1+0.2 prints 0.3.
- **dpooa** – shows the app as a window over other apps (needs "display over other apps"; the install grants it, otherwise a settings page opens). With `run --dispose` it is removed only when fully closed. **New windows:** with dpooa active, `Toplevel(root)` opens a floating window of its own (title bar with minimize and close, drag by the title bar, `#dpooa: resizable` adds the resize grip, `#dpooa: alpha`, `bg` and `bar` apply too) instead of a panel inside the main window; each new one is offset a little so it does not cover the one that opened it. On a Toplevel `title()`, `protocol("WM_DELETE_WINDOW", fn)`, `geometry()`, `attributes("-alpha", x)`, `iconify()`, `withdraw()`, `deiconify()`, `lift()` and `destroy()` act on that window; dialogs (`messagebox`, `simpledialog`) open in the window you last touched. **Keyboard:** dpooa windows never hold on to the keyboard. A window only takes it while you type in one of its fields (it opens when you tap a field, or when the script calls `focus_set()` or shows an input dialog) and hands it back the moment you touch anything outside that window (another app, another dpooa window), press back, minimize or close it. So typing in another app or in a different window of the same app works while a dpooa window is open, and the back key goes to the app behind when you are not typing. Without dpooa (or when the permission is missing) a Toplevel stays a panel inside the page as before. Control the window with `#dpooa:` comments (header only; several lines allowed; commas or spaces):

  | option | effect |
  |---|---|
  | `draggable` | drag the window by its title bar |
  | `resizable` | corner grip (bottom-right) to resize |
  | `snap` | after a drag, snaps to the nearest left/right screen edge |
  | `size 320x400` | width x height in dp; also `90%x50%`, `300xauto`, `fullx300` (or `width N`, `height N`) |
  | `pos 40 120` | x y in dp from top-left; or `pos center/top/bottom/left/right/topleft/topright/bottomleft/bottomright` |
  | `alpha 0.8` | window transparency, 0.1-1 |
  | `title "My HUD"` | fixed title-bar text (script `root.title()` won't change it) |
  | `bar #RRGGBB` / `bg #RRGGBB` | title bar / window background color |
  | `minimize off` / `close off` | hide the – / × button (then close it from code with `root.destroy()`) |
  | `startminimized` | start collapsed to the title bar |
  | `clickthrough` | window ignores touches (display-only HUD; close it from code) |

  Example: `#dpooa: draggable, resizable, size 320x50%, pos topright, alpha 0.9, title "Notes"`

  From your code, inside a dpooa window: `root.geometry("300x200+20+40")` (dp; negative offsets count from the right/bottom), `root.geometry()` (read), `root.attributes("-alpha", 0.6)`, `root.iconify()` / `root.deiconify()` (collapse / restore).
- **filepick** (aliases `native filepicker`, `native`) – real Android file picker behind `filedialog` (`askopenfilename`, `asksaveasfilename`, ...). Works as `f = askopenfilename()` or with `callback=`/`command=`.
- **datasaving** – all module-level variables survive leaving and relaunching. (Don't combine with `--dispose`: dispose uninstalls the app and its data.)
  - **Windows remember their place (with `dpooa`):** with both `#use: dpooa` and `#use: datasaving`, the main window and every `Toplevel` save where they are (position, size if you resized it, collapsed or not) whenever you move, resize or collapse them, and come back there the next time they are created. The saved place wins over `#dpooa: pos/size`, which only decide the very first start. A `Toplevel` is matched by its name: write `tk.Toplevel(root, name="note_3")` (a normal tkinter argument; give each window its own name, and the same name again later to get the same place). Without `name=`, windows are matched by creation order (the 1st, 2nd, ... Toplevel made in a run). A closed window keeps its remembered place. PocketTk cannot rebuild a window's contents by itself, so keep a module-level list of the windows that are open (it is saved like any variable) and create them again at startup. The place of a window you did not open this time is kept. `#datasaving: datareset` also forgets all window places. Switch it off with `#builtins: window-memory: disable`.
  - **Reset the data:** put the comment `#datasaving: datareset` inside a function. Whenever that function runs, the saved data is wiped and the module-level variables go back to the values your code first gave them (an `IntVar`/`StringVar` is reset in place, so widgets bound to it update). Needs `#use: datasaving`; ignored with a warning outside a function. The comment can be the whole body:
    ```python
    def resetdata():
        #datasaving: datareset
    ```
    **Confirmation:** the reset asks "Erase all saved data and start over?" first. If the user taps Cancel, nothing is wiped and the function ends right there (the lines after the comment don't run). Write `#datasaving: datareset: noconfirm` to reset immediately, with no question.
    ```python
    def resetdata():
        #datasaving: datareset: noconfirm
    ```
    Labels you set by hand (`label.config(text=...)`) are not refreshed automatically - update them after calling the function. `reset`, `resetdata` also work as the word after the colon, and `noconfirm` can be written `no-confirm` / `no_confirm`.

- **audio** (aliases `music`, `sound`) – plays music in the app. It is comment-only: nothing to import or call, and it does nothing in an IDE; it plays in the built app.

  | line | effect |
  |---|---|
  | `#audio: dQw4w9WgXcQ` | one track: a YouTube video id (the `v=` part), a YouTube URL, any other URL, or a file |
  | `#playlist: id1 id2 id3` | several tracks, played in order (spaces or commas; several lines add up) |
  | `noloop` / `loop` | in either line: play once / repeat (default: repeat the track or the whole list) |

  Sources can be mixed: `#playlist: dQw4w9WgXcQ https://example.com/a.mp3 /sdcard/Music/b.m4a "my songs/c.ogg"`. Relative paths are looked up next to the script. `#audio:` alone also switches audio on, so `#use: audio` is optional.

  **How it works:** tracks are fetched when you build and bundled inside the APK, so the app plays them offline and needs no permissions. Downloads are cached in `~/.pockettk/audio-cache`, so later builds/updates are fast. Direct audio links are downloaded with Python; YouTube and other page links need `yt-dlp` plus a JavaScript runtime in Termux (YouTube requires one): `pkg install yt-dlp nodejs` (or `pip install -U "yt-dlp[default]"`). `pockettk doctor` shows whether they are found. If a track cannot be fetched the build still finishes, with a warning that says why. Music pauses when the app goes to the background and resumes when you come back (dpooa overlays keep playing until closed). Large playlists make a large APK (about 4 MB per song).

- **Run commands in Termux (`#use: termux-connect`):** the app may `import termux` and run commands inside Termux, then get their output back. There is no server and no open port: each call is one `RUN_COMMAND` intent to Termux and Termux answers with a result.

        #use: termux-connect
        import termux
        def done(code, out, err):
            print(code, out)
        if termux.state() == "ok":
            termux.run(["pockettk", "ids"], done)

  `termux.state()` is `"ok"`, `"missing"` (Termux is not installed) or `"permission"` (not allowed yet: `termux.ask()` shows Android's question). `termux.run(args, callback)` runs `args[0]` (a command name from Termux's bin folder, or an absolute path) with the rest as arguments, in Termux's home folder (`workdir="..."` changes that); `callback(exit_code, stdout, stderr)` runs when it ends, and `exit_code` is `-1` with the reason in `stderr` when it could not be started. Output is cut at about 100 000 characters by Termux. It needs the Termux app, `allow-external-apps = true` in `~/.termux/termux.properties` (add the line yourself, then run `termux-reload-settings`) and the permission to run commands in Termux (granted at install on a rooted phone or with adb, otherwise asked once). The directive adds that permission, a package query and a small result receiver to the app. Without `#use: termux-connect`, `import termux` is an error.

## Built-ins (on by default) and how to switch them off
| name | what it does |
|---|---|
| `version-label` | small app version text plus the PocketTk version it was built with and an underlined **About PocketTk** link (e.g. `v1.2.3 Patch 2, Built With PocketTk 3.1.2`) at the bottom right corner, drawn over your UI; tapping the link opens the About page, everywhere else touches pass through it (with a dpooa resize grip it sits just left of the grip) |
| `datareset-confirm` | the question asked before `#datasaving: datareset` wipes the data |
| `error-toast` | the toast shown when a callback raises (the traceback still goes to `error.log` / logcat) |
| `window-memory` | with `#use: dpooa` **and** `datasaving`: the dpooa windows remember their position, size and collapsed state (see below) |

To turn one off, enable builtin editing and name it:

    #use: builtin-editing
    #builtins: version-label: disable

Several names can share a line (`#builtins: version-label, error-toast: disable`) and the line can be repeated. `enable` turns one back on. Without `#use: builtin-editing` the `#builtins:` lines are ignored with a warning, and an unknown name gives a warning. The same line also works for the opt-in features (`fppe`, `dpooa`, `filepick`, `datasaving`, `audio`): `#builtins: audio: disable` switches audio off even if `#audio:` lines are present. A per-line `#datasaving: datareset: confirm` / `noconfirm` always wins over `datareset-confirm`.

## Google Play upload (`--aab`)
`pockettk build app.py --id notes --aab` makes `notes.aab` (default folder `/sdcard/Download/PocketTk/`) instead of an APK. An `.aab` cannot be installed on the phone; it is only for uploading in Play Console. `run`, `-i` and `--debug` do not work with it.
- **One-time setup:** `pkg install openjdk-21 aapt2 dx`, and download `bundletool-all-VERSION.jar` from github.com/google/bundletool/releases to `~/bundletool.jar` (`pockettk doctor` shows whether it was found; `BUNDLETOOL=/path/to.jar` also works).
- **Upload key:** the first time, an upload key is created in the module's data folder (`apple.keystore` is the APK key and is not used for this; the new one is `upload.keystore` with its password in `upload.pass`). **Back both files up**: Play needs the same upload key for every upload. Or use your own with `--keystore FILE` (password from the `POCKETTK_KS_PASS` variable or a prompt; `--ks-alias NAME` if it has several keys). The SHA-256 fingerprint is printed after signing. Play signs the installed app with its own key (Play App Signing).
- **Version code:** Play refuses an upload whose versionCode is not higher than the last one. Build with `--id NAME` so it counts up on every build (`--small/--medium/--large/--patch` as usual).
- **Target SDK:** `--aab` targets API 36 by default (Play requires a recent target API; change with `--target-sdk N`). Plain APK builds stay at 34. Android 15+ draws apps edge to edge when the target is 35 or higher, so check how your layout looks.
- **Not done by PocketTk:** Play Console account, app listing, icon/screenshots, privacy policy and the testing tracks.
Untested on a real device and with the real aapt2 and bundletool; only the packing and signing steps were run on a desktop.

## Icon
`--icon logo.png` (png, webp or jpg; square, 512x512 or larger is best) sets the launcher icon for `build` and `run`.

## Updating an app (like a store update)
    pockettk run app.py --id notes        # first time: registers id "notes" (fixed package name)
    pockettk update --id notes code.py -i # later: rebuild and install over the old app
    pockettk update --id notes            # reuse the saved source path
    pockettk ids                          # list your ids
The id pins the package name (new apps get `pockettk.ssdes.NAME`; apps built before that change keep their older `tkdroid.ssdes.NAME` (or `tkdroid.molt.NAME`, `apple.app.NAME`) package until `update` or `update-all` moves them) and the signing key stays the same, and every build bumps the version code, so Android treats it as an update: the app's data (including `datasaving` state) is kept. Name and icon are remembered unless you pass new ones. Don't use `--dispose` with ids you want to keep updating.

## Updating all apps to a new PocketTk version
An app contains the runtime it was built with, so installing a newer PocketTk does not change apps you already built. `pockettk update-all` rebuilds every app that has an id from its saved source and installs it over the old one (data is kept, apps are not launched). Apps already built with the current PocketTk are skipped, as are apps that are no longer installed; `--force` rebuilds those anyway. The update kind flags work as in `update` (default `--patch`) and apply to all apps. One failing app does not stop the others; they are listed at the end. It is fast on purpose: the PocketTk runtime is compiled once (cached in `~/.pockettk/rt`, rebuilt automatically when PocketTk or android.jar changes) and every app only compiles its own code; the first app builds alone, the rest are built `-j N` at a time (default: half the CPU cores, 1 to 3; `-j 1` builds one by one with live output). Apps whose source files share a file name are always built one at a time. The runtime sits in each APK as a second dex (`classes2.dex`). If the cache ever misbehaves, `APKAPPLE_NO_RTCACHE=1 apkapple update-all` builds the old way. If an app's source file moved, fix it with `pockettk update --id NAME code.py`. Apps built without `--id` are not tracked and are not touched. `pockettk ids` marks apps built with an older PocketTk.

## Version numbers (what the version label shows)
An app built with an id has a version `A.B.C` plus a separate patch counter. You say what kind of update it is when you rebuild:

| flag | effect | example |
|---|---|---|
| `--patch` (default) | raise the patch counter only | `v1.2.3` -> `v1.2.3 Patch 1` -> `Patch 2` |
| `--small` | raise the last number, reset patch | `v1.2.3 Patch 2` -> `v1.2.4` |
| `--medium` | raise the middle number, reset the last number and patch | `v1.2.4` -> `v1.3.0` |
| `--large` | raise the first number, reset the others and patch | `v1.3.0` -> `v2.0.0` |

    pockettk update --id notes code.py --small
    pockettk run app.py --id notes --medium

The first build of an id is `v1.0.0`. Ids made before v2.3.1 continue from `1.0.<old number>`. Android's internal version code still goes up by one on every build, whatever the flag. Without `--id` the version stays `v1.0.0`. `pockettk ids` lists each app's current version.

## Debug builds (`--debug`)
`--debug` (on `build`, `run` and `update`) adds a **log panel under the app**: everything the program prints (`print`, `sys.stdout/stderr`), uncaught errors with Python line numbers, and runtime warnings (unsupported widget/option/method). The panel has COPY, CLEAR and a collapse button; it keeps the last 500 lines. It works in dpooa windows too.

    pockettk update --id notes code.py --debug

`update --debug` is for trying a change safely:
1. It saves the installed version of the app, builds the new code with the log panel, installs it over the old one (data kept) and launches it.
2. When you **close the app** (back button, or swipe it away; going to the home screen does not count), the previous version is installed back over it. Again nothing is uninstalled, so the app's data stays.
3. The id record (version numbers, source path) goes back to what it was, so the debug build does not count as a release.

How the restore works: Android refuses to install a lower version code over a higher one, so PocketTk makes a copy of the old APK with its version code raised past the debug build and signs it with your PocketTk key. The old app's code and version label are unchanged.
If the restore does not happen (for example the phone rebooted first), run `pockettk revert --id NAME`; a second `update --debug` of the same id is refused until then. If the id has no installed app, the debug build simply stays installed. The result of each restore is appended to `/data/local/tmp/apkapple-debug.log`.

## Folders and the move from TkDroid / ApkApple
PocketTk keeps its files here (they used to be named TkDroid / `.tkdroid`, and ApkApple / `.apple`):

| What | Where |
|---|---|
| Signing key, app ids, audio cache, work files | `~/.pockettk` in Termux (`/data/adb/pockettk` for root) |
| Demo scripts and built APKs | `/sdcard/Download/PocketTk/` |
| Your own `android.jar` / `sdk` (optional) | `/sdcard/PocketTk/` |
| The tool itself, copied into Termux | `$PREFIX/share/pockettk` |

**Flashing this module moves everything over:** the old `~/.tkdroid`, `~/.apple`, `/data/adb/tkdroid`, `/data/adb/apple`, `/sdcard/Download/TkDroid`, `/sdcard/TkDroid` (and the ApkApple ones) become the new folders (the signing key included, so your apps keep updating), and the `src=` / `icon=` paths inside the app ids are rewritten to match. If a new folder already exists, only the files it lacks are copied. If Termux was not ready at flash time, the first `pockettk` command moves `~/.tkdroid` / `~/.apple` by itself. The PC tool moves `~/.tkdroid` the same way. The old `/sdcard/ApkApple/...` locations are still searched for `android.jar` and `sdk`.

**Package names:** new apps use `pockettk.ssdes.NAME` (earlier versions used `tkdroid.ssdes.NAME`, then `tkdroid.molt.NAME`, and before that `apple.app.NAME`). `pockettk update --id NAME` and `pockettk update-all` move every app that is still on one of the older names to `pockettk.ssdes.NAME` (even if it was already built with this version): it builds the app under the new package, copies the old app's saved data (`files`, `shared_prefs`, `databases`, so `datasaving` state survives) into it as root, then uninstalls the old one. If the data copy fails, the old app is kept and you are told. An id whose app is not installed just gets its package name changed in the record. Apps with a custom `-p` package are left alone. `pockettk update --id NAME` does this by itself for an app on an old package; `--keep-pkg` stays on the old one. Without root the data cannot be copied: the app is installed under the new package and the old one is kept (the PC tool tells you the `adb uninstall` line).

## About PocketTk page
Every app has a small underlined **About PocketTk** link at the end of its version label (switch the label off with `#builtins: version-label: disable`). It opens a plain page inside the app that explains what PocketTk is, why it exists, how apps are made with it, who makes it, and the "Can I use it?" answer (yes, it is open source; the **Open the repo on GitHub** button opens https://github.com/iamssdes/PocketTk, set as `REPO_URL` in `rt/About.java`). Close it with the Close button or the back button. The page is `rt/About.java`, so its text is easy to edit.

## Deleting apps
`pockettk delete --id NAME` **uninstalls the app, including its saved data**, and removes the id (version record, leftover `update --debug` files). It asks first when run in a terminal; `-y` skips the question. Your `.py` script is never touched. The id's package name is freed, so building the same id again starts over at `v1.0.0`.

`pockettk delete-uninstalled` removes the ids of apps that are no longer installed (for example ones you uninstalled by hand). It reads the phone's package list first and does nothing if it cannot, so it never deletes an id by mistake.

## Dispose
`run --dispose` uninstalls and deletes the app the moment it leaves the foreground (dpooa apps: when fully closed).

## Errors
Uncaught errors show a Python-style traceback with your line numbers, in logcat (tag `ApkApple`), `filesDir/error.log`, and a toast.

## Supported
Widgets (Label, Button, Entry, Text, Checkbutton, Radiobutton, Scale, Listbox, Combobox, Spinbox, Progressbar, Separator, Canvas, Frame, **Notebook**, **Menu**...), pack/grid/place, StringVar etc., messagebox/simpledialog/**colorchooser**, input(), after(); `with`, comprehensions, `*args/**kwargs`, nonlocal, del, custom exceptions.

**Notebook** has real tabs: a black tab bar, equal-width tabs, the selected one white and bold with a crimson underline, idle ones gray. `add/insert/forget/hide/select/index/tabs/tab`, `<<NotebookTabChanged>>`, `enable_traversal`. Colors: `nb.config(bg=, fg=, accent=, idle=, tabbg=)`.

**Menu**: `root.config(menu=bar)` shows a menu bar under the title; tapping a name opens a popup list. `add_command/add_cascade/add_separator/add_checkbutton/add_radiobutton`, `insert_*`, `delete`, `entryconfig`, `entrycget`, `index`, `invoke`, `accelerator=` (shown as text), `state="disabled"`, nested cascades (with a Back row), and `menu.tk_popup(x, y)` for context menus. Checks and radios use your `BooleanVar`/`StringVar`.

**colorchooser.askcolor(color=None, title=None)** shows a dialog (palette + R/G/B sliders) and returns `((r, g, b), "#rrggbb")`, or `(None, None)` on Cancel.

**Treeview** (`ttk.Treeview`): `columns=`, `show="headings"` or `"tree headings"`, `height=`, `selectmode=`; `heading()`, `column()` (width, anchor), `insert(parent, index, iid=, text=, values=, open=, tags=)`, `delete`, `detach`, `move`, `item`, `set`, `get_children`, `parent`, `index`, `next`, `prev`, `exists`, `selection` / `selection_set/add/remove/toggle`, `focus`, `see`, `tag_configure(background=, foreground=)`, and the events `<<TreeviewSelect>>`, `<<TreeviewOpen>>`, `<<TreeviewClose>>` and double tap. A tap on a row selects it; with the tree column shown it also opens or closes a parent row. **LabeledScale**: a number label above (or `compound="bottom"` below) a scale, both on `variable=`.

**Canvas**: `create_line` (`smooth`, `arrow=first|last|both`, `dash=`, `width`), `create_rectangle`, `create_oval`, `create_arc` (`start`, `extent`, `style=pieslice|chord|arc`), `create_polygon` (filled, `outline`, `smooth`), `create_text` (`anchor`, `font=("Arial", 14, "bold")`, `justify`, `width=` to wrap, several lines), `create_image`; `state="hidden"`. Methods: `coords`, `move`, `moveto`, `scale`, `delete` (several tags), `itemconfig`, `itemcget`, `type`, `gettags`, `bbox`, `find_all`, `find_withtag`, `find_closest`, `find_overlapping`, `find_enclosed`, `find_above`, `find_below`, `addtag_*`, `dtag`, `tag_raise` / `lift`, `tag_lower` / `lower`, `tag_bind(tag, "<Button-1>", fn)` (also `<ButtonRelease-1>`, `<B1-Motion>`; `"current"` is the tapped item). Not yet: `create_window`, `create_bitmap`, `postscript`.

**Images**: `tk.PhotoImage(file="logo.png")` (PNG, JPEG, GIF, WebP, BMP; the file next to your script is copied into the app), `PhotoImage(data=base64_text)`, `PhotoImage(width=, height=)`, with `width() height() zoom() subsample() copy() put() get() blank() configure()`. Use it with `image=` on Label and Button (`compound="left|right|top|bottom"` shows text too; without `compound` only the picture shows) and `canvas.create_image(x, y, image=img, anchor="nw")`. Keep a reference to the image, as in tkinter. No PIL / ImageTk.

**Classes**: inheritance and multiple inheritance (C3 order), `super()`, several instances of one class, subclassing `tk.Frame`/`tk.Tk`/`threading.Thread`, `@staticmethod`, `@classmethod`, `@property` (+ setter), `@dataclass`, `Enum`, and dunder methods (`__str__ __repr__ __eq__ __lt__ __add__ __getitem__ __len__ __iter__ __call__ __contains__ __enter__ __exit__`...).
**Generators** (`yield`, `yield from`, `.send`, `.close`), **decorators** (functions and classes), **bytes/bytearray** and binary file modes (`"rb"`, `"wb"`).

**Libraries**: json, os, sys, re, datetime, threading, collections, math, random, time, string, plus itertools, functools (reduce, partial, wraps, lru_cache, cache, cmp_to_key), copy, operator, statistics, hashlib (md5/sha1/sha2), base64, uuid, csv (reader, writer, DictReader, DictWriter), socket (TCP/UDP), urllib.request / urllib.parse, a small `requests` (get/post/put/delete, params, json, data, headers, timeout) and sqlite3 (connect, cursor, execute, executemany, fetch*, commit; `?` parameters). Every app has the INTERNET permission (the About page downloads the demo and the Request page sends requests); importing socket, urllib or requests additionally allows plain-http (cleartext) traffic for that app.

## Not supported / limits
Audio: no live streams or YouTube playlist links (list the video ids with `#playlist:`), no playback control from Python, no playback after the app is closed.
Treeview; askdirectory returns the app's files dir. Subclassing built-in types (dict, list...) is not supported. Exception classes with their own methods/attributes are only partly supported. sqlite3 named parameters (`:name`) and row factories are not supported; `csv` dialects other than delimiter/quotechar/quoting are ignored. `requests` is a minimal built-in; **pip packages cannot be used** (there is no embedded CPython).

## Tested on a real device
`#use: termux-connect` (`import termux`) has not been tried on a phone yet: the transpiler output is tested on a desktop (`tests/test_termux_connect.py`), but the `RUN_COMMAND` call itself, the result broadcast and the permission question are written from Termux's documented interface and are the part to watch on a real phone.
All features (version label and builtin switches, datareset and its confirmation dialog, audio, overlay permission, native file picker, dispose watcher and the runtime's dexing) were tested on a real phone, the author's own. Results may vary depending on your device's age, Android version and root status. Report anything odd with `pockettk logs -e`.
The log panel and `update --debug` / `revert` / `help --brief` were added after that testing: the generated code, the shell flow (with stubs) and the version-code patching were checked on a desktop, not yet on a phone.

v3.1.0 (Notebook tabs, Menu, colorchooser, classes, generators, decorators, bytes, new libraries) was checked on a desktop with stubs and compared with CPython; none of it has been tried on a phone yet. sqlite3 and the Menu/Notebook/colorchooser look in particular are untested on a device.
The small version label now reads `<app version>, Built With PocketTk <version>`.
v3.1.2 (the About PocketTk link and page) has not been tried on a phone yet, including opening it from a dpooa floating window; the folder move on flash and the `update-all` package move are also untested on a device.
