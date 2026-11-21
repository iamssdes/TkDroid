#!/usr/bin/env python3
"""
PocketTk transpiler: a tkinter program (a practical subset of Python) -> Java source
for an Android Activity, plus the helpers that package a signed-ready APK.

    transpile.py gen  SCRIPT.py OUTDIR --pkg com.x.y --label "Name"
    transpile.py pack BASE.apk CLASSES.dex OUT.apk

Python 3.8+, standard library only.
"""
import argparse
import ast
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import urllib.parse
import urllib.request
import zipfile
from xml.sax.saxutils import quoteattr

# --------------------------------------------------------------------------- tables

WIDGETS = {
    "Frame", "LabelFrame", "Labelframe", "Toplevel", "PanedWindow", "Label", "Message", "Button",
    "Entry", "Text", "Checkbutton", "Radiobutton", "Scale", "Listbox", "Combobox", "Spinbox",
    "Progressbar", "Separator", "Canvas", "Scrollbar", "Notebook", "Menu", "Menubutton", "OptionMenu",
    "Sizegrip", "Treeview",
}
EXTRA_TK_NAMES = {"LabeledScale"}
UNSUPPORTED_WIDGETS = {"PhotoImage", "LabeledScale"}   # handled specially in tk_call; the rest of this set would be a placeholder
# PhotoImage(file="x.png") with a literal name: the file next to the script is copied into the app (assets/img/). Filled by tk_call.
SCRIPT_DIR = "."
IMAGE_FILES = {}
TYPE_NAMES = {"int", "float", "str", "bool", "list", "dict", "tuple", "set", "frozenset", "object", "callable"}
FILEPICK = {"askopenfilename", "asksaveasfilename", "askopenfilenames", "askopenfile", "asksaveasfile",
            "askdirectory"}
FEATURES = {"fppe", "dpooa", "filepick", "datasaving", "audio", "termux-connect"}

# ---- edition: make_demo.py flips DEMO to True when it builds the PocketTk Demo module. The demo only builds
# small, plain scripts: see demo_check() and the feature filter in cmd_gen().
DEMO = False
DEMO_MAX_LINES = 100                                    # longest script (lines) the demo builds
DEMO_FEATURES = {"fppe"}                                # the only #use: items that work in the demo
DEMO_BLOCKED_MODULES = {"socket", "urllib", "requests", "sqlite3", "threading", "os", "csv", "termux"}
DEMO_BLOCKED_WIDGETS = {"Canvas", "Toplevel", "Notebook", "Menu", "Menubutton", "OptionMenu", "PanedWindow",
                        "Scale", "Spinbox"}
# ---- edition: make_consumer.py flips CONSUMER to True when it builds the PocketTk Consumer module. Its only limit:
# no `#use: builtin-editing` (and so no `#builtins:` lines), which means the version label (the link to the
# About PocketTk page) always shows. See parse_directives().
CONSUMER = False
FEATURE_ALIASES = {
    "native": "filepick", "filepicker": "filepick", "nativefilepicker": "filepick", "filepick": "filepick",
    "native_filepicker": "filepick", "fppe": "fppe", "floatfix": "fppe", "dpooa": "dpooa", "overlay": "dpooa",
    "datasaving": "datasaving", "datasave": "datasaving", "persist": "datasaving", "datasaver": "datasaving",
    "audio": "audio", "music": "audio", "sound": "audio",
    "termux-connect": "termux-connect", "termux": "termux-connect", "termuxconnect": "termux-connect",
}
FEATURE_ALIASES.update({"builtin-editing": "builtin-editing", "builtins": "builtin-editing", "builtinediting": "builtin-editing"})
# Built-in behaviours that are on by default and can be switched off with `#builtins: NAME: disable`
# (needs `#use: builtin-editing`). The opt-in features (fppe, dpooa, ...) can be disabled the same way.
TOGGLES = {
    "version-label": "small app version text at the bottom right",
    "datareset-confirm": "confirmation before '#datasaving: datareset' wipes the data",
    "error-toast": "toast shown when a callback raises an error (the traceback is still logged)",
    "window-memory": "dpooa windows remember their position, size and collapsed state (needs #use: dpooa and datasaving)",
}
TOGGLE_ALIASES = {"versionlabel": "version-label", "version": "version-label", "datareset-confirmation": "datareset-confirm",
                  "dataresetconfirm": "datareset-confirm", "dataresetconfirmation": "datareset-confirm",
                  "errortoast": "error-toast", "errors": "error-toast",
                  "windowmemory": "window-memory", "window-position": "window-memory", "windowpositions": "window-memory",
                  "window-positions": "window-memory"}
FEATURE_FILLER = {"handling", "handle", "error", "errors", "support", "mode", "and", "the"}
VARS = {"StringVar", "IntVar", "DoubleVar", "BooleanVar"}
BUILTINS = {
    "len", "str", "int", "float", "bool", "abs", "min", "max", "sum", "round", "print", "sorted",
    "reversed", "list", "tuple", "set", "dict", "range", "enumerate", "zip", "map", "filter", "any",
    "all", "chr", "ord", "pow", "divmod", "repr", "format", "input", "hex", "bin", "oct",
    "isinstance", "type", "callable", "open", "exit", "quit", "frozenset", "hasattr", "getattr", "iter",
    "next", "id", "hash", "setattr", "delattr", "issubclass", "vars", "bytes", "bytearray",
}
EXCEPTIONS = {
    "Exception", "BaseException", "ValueError", "TypeError", "ZeroDivisionError", "KeyError",
    "IndexError", "RuntimeError", "NotImplementedError", "AttributeError", "OSError", "StopIteration",
    "AssertionError", "NameError", "ArithmeticError", "OverflowError", "LookupError",
    "FileNotFoundError", "PermissionError", "FileExistsError", "IsADirectoryError", "KeyboardInterrupt",
    "SystemExit", "RecursionError", "UnicodeError", "JSONDecodeError", "EOFError", "TimeoutError",
}
MODULES = {"math", "random", "time", "json", "os", "sys", "re", "datetime", "threading", "string",
           "collections", "itertools", "functools", "copy", "operator", "statistics", "hashlib", "base64",
           "uuid", "csv", "socket", "urllib", "requests", "sqlite3", "termux"}
NET_MODULES = {"socket", "urllib", "requests"}
IGNORED_MODULES = {"typing", "__future__", "dataclasses", "abc", "enum"}
TK_CONST = {
    "END": "end", "LEFT": "left", "RIGHT": "right", "TOP": "top", "BOTTOM": "bottom", "BOTH": "both",
    "X": "x", "Y": "y", "N": "n", "S": "s", "E": "e", "W": "w", "NW": "nw", "NE": "ne", "SW": "sw",
    "SE": "se", "NS": "ns", "EW": "ew", "NSEW": "nsew", "CENTER": "center", "NORMAL": "normal",
    "DISABLED": "disabled", "ACTIVE": "active", "HORIZONTAL": "horizontal", "VERTICAL": "vertical",
    "WORD": "word", "CHAR": "char", "NONE": "none", "INSERT": "insert", "SEL": "sel",
    "RAISED": "raised", "SUNKEN": "sunken", "FLAT": "flat", "GROOVE": "groove", "RIDGE": "ridge",
    "SOLID": "solid", "READONLY": "readonly", "BROWSE": "browse", "SINGLE": "single",
    "MULTIPLE": "multiple", "EXTENDED": "extended", "ANCHOR": "anchor", "ALL": "all",
}
TK_BOOL = {"TRUE": "Boolean.TRUE", "FALSE": "Boolean.FALSE", "YES": "Boolean.TRUE", "NO": "Boolean.FALSE"}


def jstr(s):
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif 32 <= o < 127:
            out.append(ch)
        elif o > 0xFFFF:
            o -= 0x10000
            out.append("\\u%04x\\u%04x" % (0xD800 + (o >> 10), 0xDC00 + (o & 0x3FF)))
        else:
            out.append("\\u%04x" % o)
    out.append('"')
    return "".join(out)


def dotted(node):
    parts = []
    while isinstance(node, ast.Attribute):
        parts.append(node.attr)
        node = node.value
    if isinstance(node, ast.Name):
        parts.append(node.id)
        return ".".join(reversed(parts))
    return None


class Unsupported(Exception):
    pass


# ------------------------------------------------------------------ directives
DPOOA_FLAGS = {
    "draggable": "drag", "drag": "drag", "movable": "drag",
    "resizable": "resize", "resize": "resize",
    "snap": "snap", "snapedge": "snap",
    "clickthrough": "click", "click-through": "click", "passthrough": "click",
    "startminimized": "min0", "minimized": "min0", "collapsed": "min0",
    "nominimize": "nomin", "nomin": "nomin", "noclose": "noclose",
}
DPOOA_POS = {"center", "top", "bottom", "left", "right", "topleft", "topright", "bottomleft", "bottomright"}
DPOOA_KNOWN = ("draggable, resizable, snap, clickthrough, startminimized, size WxH, width N, height N, "
               "pos X Y | pos center/top/bottom/left/right/topleft/..., alpha 0.1-1, title \"text\", "
               "bar #RRGGBB, bg #RRGGBB, minimize off, close off")
_DIM = re.compile(r"^(?:[+-]?\d+(?:\.\d+)?%?|auto|wrap|full|match)$", re.I)
_COLOR = re.compile(r"^#?(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")


def parse_dpooa(text, no, opts, warns):
    """Options of a `#dpooa:` comment: flags and `key value` pairs, comma/space separated."""
    t = re.search(r"(?i)\btitle\s*[:=]?\s*(?:\"([^\"]*)\"|'([^']*)')", text)
    if t:
        opts["title"] = (t.group(1) if t.group(1) is not None else t.group(2)).replace(";", ",")
        text = text[:t.start()] + " " + text[t.end():]
    toks = [x for x in re.split(r"[,;\s]+", re.sub(r"\s*[=:]\s*", " ", text.strip())) if x]
    i = 0

    def take():
        nonlocal i
        i += 1
        return toks[i] if i < len(toks) else None

    while i < len(toks):
        k = toks[i].lower()
        if k in DPOOA_FLAGS:
            opts[DPOOA_FLAGS[k]] = "1"
        elif k in ("size", "dimensions"):
            v = take()
            m = re.match(r"^(.+?)[x×*](.+)$", v or "", re.I)
            if m and _DIM.match(m.group(1)) and _DIM.match(m.group(2)):
                opts["w"], opts["h"] = m.group(1).lower(), m.group(2).lower()
            elif v and _DIM.match(v) and i + 1 < len(toks) and _DIM.match(toks[i + 1]):
                opts["w"], opts["h"] = v.lower(), take().lower()
            else:
                warns.append("line %d: #dpooa: size needs WxH, e.g. size 320x400 (dp), 90%%x50%%, 300xauto" % no)
        elif k in ("width", "w", "height", "h"):
            v = take()
            if v and _DIM.match(v):
                opts["w" if k[0] == "w" else "h"] = v.lower()
            else:
                warns.append("line %d: #dpooa: %s needs a number, %%, auto or full" % (no, k))
        elif k in ("pos", "position", "at", "place"):
            v = take()
            if v and v.lower() in DPOOA_POS:
                opts["at"] = v.lower()
            elif v and _DIM.match(v):
                opts["x"] = v
                if i + 1 < len(toks) and _DIM.match(toks[i + 1]):
                    opts["y"] = take()
            else:
                warns.append("line %d: #dpooa: pos needs X Y (dp) or one of %s" % (no, "/".join(sorted(DPOOA_POS))))
        elif k in ("alpha", "opacity"):
            v = take() or ""
            try:
                f = float(v[:-1]) / 100 if v.endswith("%") else float(v)
                opts["alpha"] = repr(min(1.0, max(0.1, f)))
            except ValueError:
                warns.append("line %d: #dpooa: alpha needs a number from 0.1 to 1" % no)
        elif k in ("bar", "barcolor", "titlebar", "bg", "background", "color", "backdrop"):
            v = take()
            if v and _COLOR.match(v):
                opts["bar" if k in ("bar", "barcolor", "titlebar") else "bg"] = v.lstrip("#")
            else:
                warns.append("line %d: #dpooa: %s needs a color like #202020" % (no, k))
        elif k in ("minimize", "min", "minimise", "close"):
            v = (take() or "").lower()
            if v in ("off", "no", "false", "hide", "hidden", "none"):
                opts["noclose" if k == "close" else "nomin"] = "1"
            elif v not in ("on", "yes", "true", "show"):
                warns.append("line %d: #dpooa: %s takes on/off" % (no, k))
        else:
            warns.append("line %d: unknown #dpooa: option '%s' (known: %s)" % (no, toks[i], DPOOA_KNOWN))
        i += 1
    if opts.get("click"):
        warns.append("#dpooa: clickthrough makes the window ignore touches - close it from your code (root.destroy())")
    if opts.get("noclose") and not opts.get("click"):
        warns.append("#dpooa: close off - make sure your code closes the window (root.destroy())")


def dpooa_spec(opts):
    return ";".join("%s=%s" % (k, v) for k, v in sorted(opts.items()))


def parse_directives(source, audio=None, off=None):
    """
    `#use: fppe, dpooa, filepick, datasaving, audio` (also `native filepicker`), `#dpooa: draggable`,
    `#audio: SOURCE` and `#playlist: SOURCE SOURCE ...` in the leading comments.
    Returns (features, options, warnings). `#audio:`/`#playlist:` lines are appended to `audio`
    (a list) as (line number, directive, text). `#builtins: NAME: disable` lines (with `#use: builtin-editing`)
    switch built-in behaviours off: the names go into `off` (a set); disabled features leave `feats`.
    """
    feats, opts, warns = set(), {}, []
    edits = []
    for no, line in enumerate(source.splitlines(), 1):
        t = line.strip()
        if not t or t.startswith("#!") or (t.startswith("#") and "coding" in t[:20]):
            continue
        if not t.startswith("#"):
            if t.startswith(('"""', "'''")) or t.startswith(('r"""',)):
                continue
            break
        body = t.lstrip("#").strip()
        m = re.match(r"(?i)^use\s*:\s*(.*)$", body)
        if m:
            text = re.sub(r"(?i)native\s+file\s*picker", "filepick", m.group(1))
            text = re.sub(r"(?i)fppe\s+handling", "fppe", text)
            text = re.sub(r"(?i)termux[\s_]+connect", "termux-connect", text)
            text = re.sub(r"(?i)builtins?[\s_-]*editing", "builtin-editing", text)
            for tok in re.split(r"[,;\s]+", text.strip()):
                low = tok.lower().strip(".")
                if not low or low in FEATURE_FILLER:
                    continue
                f = FEATURE_ALIASES.get(low)
                if f == "builtin-editing" and CONSUMER:
                    warns.append("line %d: #use: builtin-editing is not in the PocketTk Consumer version - ignored" % no)
                    continue
                if f:
                    feats.add(f)
                else:
                    warns.append("line %d: unknown #use: item '%s' (known: fppe, dpooa, filepick, datasaving, audio, termux-connect, builtin-editing)" % (no, tok))
            continue
        m = re.match(r"(?i)^builtins?\s*:\s*(.+?)\s*:\s*([A-Za-z]+)\s*$", body)
        if m:
            edits.append((no, m.group(1), m.group(2).lower()))
            continue
        m = re.match(r"(?i)^(audio|playlist)\s*:\s*(.*)$", body)
        if m:
            feats.add("audio")
            if audio is not None:
                audio.append((no, m.group(1).lower(), m.group(2)))
            continue
        m = re.match(r"(?i)^dpooa\s*:\s*(.*)$", body)
        if m:
            feats.add("dpooa")
            parse_dpooa(m.group(1), no, opts, warns)
            continue
        m = re.match(r"(?i)^(filepick|fppe|datasaving)\s*:", body)
        if m:
            feats.add(m.group(1).lower())
    for no, names, word in edits:
        if CONSUMER:
            warns.append("line %d: #builtins: is not in the PocketTk Consumer version - ignored (the version label always shows)" % no)
            continue
        if "builtin-editing" not in feats:
            warns.append("line %d: #builtins: needs #use: builtin-editing at the top - ignored" % no)
            continue
        if word in ("disable", "disabled", "off", "no", "false"):
            on = False
        elif word in ("enable", "enabled", "on", "yes", "true"):
            on = True
        else:
            warns.append("line %d: #builtins: expects 'disable' or 'enable' after the name, not '%s' - ignored" % (no, word))
            continue
        for raw in re.split(r"[,;]+|\s+and\s+", names):
            nm = re.sub(r"[\s_]+", "-", raw.strip().lower())
            key = re.sub(r"[^a-z0-9]", "", nm)
            if not key:
                continue
            nm = TOGGLE_ALIASES.get(nm, TOGGLE_ALIASES.get(key, nm))
            if nm in TOGGLES:
                if off is not None:
                    (off.discard if on else off.add)(nm)
                continue
            f = FEATURE_ALIASES.get(key)
            if f and f != "builtin-editing":
                if on:
                    feats.add(f)
                else:
                    feats.discard(f)
                    if f == "dpooa":
                        opts.clear()
                continue
            warns.append("line %d: unknown builtin '%s' (known: %s)" % (no, raw.strip(), ", ".join(sorted(TOGGLES) + sorted(FEATURES))))
    return feats, opts, warns


# ------------------------------------------------------------------ audio (#use: audio)
# `#audio: SOURCE` / `#playlist: SOURCE SOURCE ...`. A SOURCE is a YouTube video id or URL, any other
# URL (a direct audio link, or a page yt-dlp understands) or a local file. Everything is fetched at
# build time and bundled in the APK (assets/audio/), so the app plays it offline.
AUDIO_EXTS = {".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac", ".mid", ".midi",
              ".webm", ".mka", ".3gp", ".amr", ".mp4"}
YT_ID_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")
YT_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
            "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com"}
YTDLP_HINT = ("YouTube/page links need yt-dlp and a JavaScript runtime in Termux:  pkg install yt-dlp nodejs   "
              "(or: pip install -U 'yt-dlp[default]'); then build again - downloaded tracks are cached")
_ytdlp_state = {}


def audio_note(msg):
    sys.stderr.write("audio: %s\n" % msg)


def audio_tokens(text):
    """Split a `#audio:` / `#playlist:` value on spaces/commas; "quoted paths" may contain spaces."""
    out = []
    for m in re.finditer(r'"([^"]*)"|\'([^\']*)\'|([^\s,]+)', text):
        t = m.group(1) if m.group(1) is not None else (m.group(2) if m.group(2) is not None else m.group(3))
        if t:
            out.append(t)
    return out


def youtube_id(url):
    try:
        u = urllib.parse.urlparse(url)
    except ValueError:
        return None
    host = (u.hostname or "").lower()
    if host not in YT_HOSTS:
        return None
    if host.endswith("youtu.be"):
        cand = u.path.strip("/").split("/")[0]
    else:
        parts = [x for x in u.path.split("/") if x]
        if len(parts) > 1 and parts[0] in ("shorts", "embed", "live", "v"):
            cand = parts[1]
        else:
            cand = (urllib.parse.parse_qs(u.query).get("v") or [""])[0]
    return cand if YT_ID_RE.match(cand or "") else None


def classify_audio(tok, script_dir):
    """-> (kind, value): file | yt | url | page | badyt | missing."""
    t = tok.strip()
    if t.lower().startswith("file://"):
        t = t[7:]
    if re.match(r"(?i)^https?://", t):
        yid = youtube_id(t)
        if yid:
            return "yt", yid
        if (urllib.parse.urlparse(t).hostname or "").lower() in YT_HOSTS:
            return "badyt", t
        ext = os.path.splitext(urllib.parse.urlparse(t).path)[1].lower()
        return ("url" if ext in AUDIO_EXTS else "page"), t
    p = os.path.expanduser(t)
    for cand in ([p] if os.path.isabs(p) else [os.path.join(script_dir, p), os.path.abspath(p)]):
        if os.path.isfile(cand):
            return "file", os.path.abspath(cand)
    if YT_ID_RE.match(t):
        return "yt", t
    return "missing", t


def cached_file(cache, key):
    best = None
    try:
        names = os.listdir(cache)
    except OSError:
        return None
    for n in names:
        if n.startswith(key + ".") and not n.endswith((".part", ".ytdl", ".tmp", ".json", ".temp")):
            f = os.path.join(cache, n)
            if os.path.isfile(f) and os.path.getsize(f) > 0 and (best is None or os.path.getsize(f) > os.path.getsize(best)):
                best = f
    return best


def find_ytdlp():
    if "cmd" in _ytdlp_state:
        return _ytdlp_state["cmd"]
    cmd = None
    exe = shutil.which("yt-dlp")
    if exe:
        cmd = [exe]
    else:
        try:
            r = subprocess.run([sys.executable, "-m", "yt_dlp", "--version"], stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, timeout=60)
            if r.returncode == 0:
                cmd = [sys.executable, "-m", "yt_dlp"]
        except (OSError, subprocess.SubprocessError):
            pass
    _ytdlp_state["cmd"] = cmd
    return cmd


def ytdlp_js_args(cmd):
    """YouTube needs an external JS runtime; yt-dlp only enables deno by default, so enable what is installed."""
    if "js" in _ytdlp_state:
        return _ytdlp_state["js"]
    args = []
    try:
        r = subprocess.run(cmd + ["--help"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           universal_newlines=True, timeout=60)
        if "--js-runtimes" in r.stdout:
            for name, exe in (("deno", "deno"), ("node", "node"), ("bun", "bun"), ("quickjs", "qjs")):
                if shutil.which(exe):
                    args += ["--js-runtimes", name]
    except (OSError, subprocess.SubprocessError):
        pass
    _ytdlp_state["js"] = args
    return args


def ytdlp_fetch(url, cache, key):
    cmd = find_ytdlp()
    if not cmd:
        return None, "yt-dlp is not installed"
    args = cmd + ["--no-playlist", "--no-warnings", "--no-progress", "--no-mtime",
                  "-f", "bestaudio[ext=m4a]/bestaudio/best", "-o", os.path.join(cache, key + ".%(ext)s")]
    args += ytdlp_js_args(cmd) + [url]
    try:
        r = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, timeout=900)
    except (OSError, subprocess.SubprocessError) as e:
        return None, str(e)
    f = cached_file(cache, key)
    if r.returncode == 0 and f:
        return f, ""
    tail = " | ".join(l.strip() for l in (r.stdout or "").strip().splitlines()[-3:])
    return None, tail or ("yt-dlp exited with code %d" % r.returncode)


def http_fetch(url, cache, key):
    ext = os.path.splitext(urllib.parse.urlparse(url).path)[1].lower()
    dest = os.path.join(cache, key + ext)
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Linux; Android) ApkApple"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(tmp, dest)
        return dest, ""
    except Exception as e:  # network, HTTP and disk errors all end up as one warning
        try:
            os.remove(tmp)
        except OSError:
            pass
        return None, str(e)


def prepare_audio(items, script_path, outdir, cache, fetch=True):
    """
    Resolve the `#audio:` / `#playlist:` lines, fetch the tracks and copy them to OUTDIR/assets/audio.
    Returns (asset names, loop flag, warnings).
    """
    script_dir = os.path.dirname(os.path.abspath(script_path))
    adir = os.path.join(outdir, "assets", "audio")
    loop, tracks, warns, total, hint = True, [], [], 0, False
    for no, directive, text in items:
        for tok in audio_tokens(text):
            low = tok.lower()
            if low in ("noloop", "no-loop", "once"):
                loop = False
                continue
            if low == "loop":
                loop = True
                continue
            kind, val = classify_audio(tok, script_dir)
            if kind == "missing":
                warns.append("line %d: #%s: '%s' is not a file, a URL or a YouTube video id" % (no, directive, tok))
                continue
            if kind == "badyt":
                warns.append("line %d: #%s: %s has no video id (YouTube playlist links are not supported - "
                             "list the video ids with #playlist:)" % (no, directive, val))
                continue
            if not fetch:
                tracks.append("t%d.m4a" % len(tracks))
                continue
            src, err = None, ""
            if kind == "file":
                src = val
            else:
                os.makedirs(cache, exist_ok=True)
                key = ("yt_" + val) if kind == "yt" else ("u_" + hashlib.sha1(val.encode("utf-8")).hexdigest()[:16])
                src = cached_file(cache, key)
                if src:
                    audio_note("using cached %s" % val)
                else:
                    audio_note("downloading %s" % val)
                    if kind == "url":
                        src, err = http_fetch(val, cache, key)
                    else:
                        src, err = ytdlp_fetch("https://www.youtube.com/watch?v=" + val if kind == "yt" else val, cache, key)
                        hint = hint or src is None
            if not src:
                warns.append("line %d: #%s: could not get %s: %s" % (no, directive, val, err))
                continue
            data_hash = hashlib.sha1()
            with open(src, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    data_hash.update(chunk)
            ext = os.path.splitext(src)[1].lower()
            if not re.match(r"^\.[a-z0-9]{1,5}$", ext):
                ext = ""
            name = "t%d_%s%s" % (len(tracks), data_hash.hexdigest()[:8], ext)
            os.makedirs(adir, exist_ok=True)
            shutil.copyfile(src, os.path.join(adir, name))
            total += os.path.getsize(src)
            tracks.append(name)
    if hint:
        warns.append(YTDLP_HINT)
    if total > 40 * 1024 * 1024:
        audio_note("%d MB of audio will be bundled in the APK" % (total // (1024 * 1024)))
    return tracks, loop, warns


def audio_cfg_java(tracks, loop):
    return ("package apple.gen;\n\n/** Tracks bundled from #audio: / #playlist: (assets/audio/). */\n"
            "public final class AudioCfg {\n    private AudioCfg() {}\n"
            "    static final String[] TRACKS = {%s};\n    static final boolean LOOP = %s;\n}\n"
            % (", ".join(jstr(t) for t in tracks), "true" if loop else "false"))


# ------------------------------------------------------------------ datareset (#datasaving: datareset)
# A comment line `#datasaving: datareset` inside a function makes that function wipe the saved data.
# The line is turned into a real statement before parsing (so a function holding only that comment is valid
# Python, and line numbers do not move), then checked by DataResetMarks.
RESET_MARK = "__datareset__"
DATARESET_RE = re.compile(r"^([ \t]*)#[ \t]*datasaving[ \t]*:[ \t]*(?:data)?reset(?:data)?"
                          r"(?:[ \t]*:[ \t]*(no[-_ ]?confirm(?:ation)?|confirm))?[ \t]*$", re.I)


def mark_datareset(source):
    out, n = [], 0
    for line in source.split("\n"):
        m = DATARESET_RE.match(line.rstrip("\r"))
        if m:
            w = (m.group(2) or "").lower()      # none: follow the datareset-confirm builtin (on by default)
            arg = "" if not w else ("False" if w.startswith("no") else "True")
            line = m.group(1) + RESET_MARK + "(" + arg + ")" + ("\r" if line.endswith("\r") else "")
            n += 1
        out.append(line)
    return "\n".join(out), n


class DataResetMarks(ast.NodeTransformer):
    """Keeps the marks that are inside a function (and datasaving is on); turns the others into `pass` with a warning."""

    def __init__(self, enabled):
        self.enabled, self.depth, self.warns, self.kept = enabled, 0, [], 0

    def visit_FunctionDef(self, node):
        self.depth += 1
        self.generic_visit(node)
        self.depth -= 1
        return node

    visit_AsyncFunctionDef = visit_FunctionDef

    def visit_Expr(self, node):
        v = node.value
        if isinstance(v, ast.Call) and isinstance(v.func, ast.Name) and v.func.id == RESET_MARK:
            if not self.enabled:
                self.warns.append("line %d: #datasaving: datareset needs #use: datasaving at the top - ignored" % node.lineno)
            elif self.depth == 0:
                self.warns.append("line %d: #datasaving: datareset must be inside a function (at the top level it "
                                  "would wipe the data at every start) - ignored" % node.lineno)
            else:
                self.kept += 1
                return node
            return ast.copy_location(ast.Pass(), node)
        return node


# ------------------------------------------------------------------ AST preparation

def collect_stores(stmts):
    """Names bound inside a function body, not descending into nested scopes."""
    names, glob = [], set()

    def add(n):
        if n not in names:
            names.append(n)

    def target(t):
        if isinstance(t, ast.Name):
            add(t.id)
        elif isinstance(t, (ast.Tuple, ast.List)):
            for e in t.elts:
                target(e)
        elif isinstance(t, ast.Starred):
            target(t.value)

    def visit(n):
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)):
            add(n.name)
            return
        if isinstance(n, ast.ClassDef):
            add(n.name)
            return
        if isinstance(n, (ast.Lambda, ast.ListComp, ast.GeneratorExp, ast.SetComp, ast.DictComp)):
            return
        if isinstance(n, (ast.Global, ast.Nonlocal)):
            glob.update(n.names)
        elif isinstance(n, ast.Assign):
            for t in n.targets:
                target(t)
        elif isinstance(n, (ast.AugAssign, ast.AnnAssign)):
            target(n.target)
        elif isinstance(n, ast.For):
            target(n.target)
        elif isinstance(n, ast.With):
            for it in n.items:
                if it.optional_vars is not None:
                    target(it.optional_vars)
        elif isinstance(n, ast.ExceptHandler) and n.name:
            add(n.name)
        for c in ast.iter_child_nodes(n):
            visit(c)

    for s in stmts:
        visit(s)
    return [n for n in names if n not in glob], glob


def collect_nonlocal(stmts):
    """Names declared `nonlocal` in a function body (not descending into nested scopes)."""
    out = set()

    def visit(n):
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.ClassDef)):
            return
        if isinstance(n, ast.Nonlocal):
            out.update(n.names)
        for c in ast.iter_child_nodes(n):
            visit(c)

    for s in stmts:
        visit(s)
    return out


class ClassFlattener(ast.NodeTransformer):
    """
    Turns `class App(tk.Tk): def __init__(self): self.x = ...` into plain functions and
    globals: methods become functions, `self.x` becomes a global `self_x`, `self` is the
    app object. One instance per class is assumed.
    """

    def __init__(self):
        self.methods = {}          # method name -> java-safe function name
        self.inits = {}            # class name -> function name of __init__
        self.attrs = set()         # attribute names assigned on self
        self.classes = set()
        self.bases = {}
        self.cur_methods = set()
        self.exc_classes = {}      # custom exception class -> parent exception name

    def exc_parent(self, node):
        for b in node.bases:
            d = (dotted(b) or "").split(".")[-1]
            if d in EXCEPTIONS or d in self.exc_classes:
                return d
        return None

    def scan(self, tree):
        for node in tree.body:
            if isinstance(node, ast.ClassDef) and self.exc_parent(node):
                self.exc_classes[node.name] = self.exc_parent(node)

    def flatten(self, tree):
        self.scan(tree)
        tree.body = [n for n in tree.body if not (isinstance(n, ast.ClassDef) and n.name in self.exc_classes)]
        return tree


# ------------------------------------------------------------------ decorator lowering

def has_yield(stmts):
    """True when a function body contains yield (not looking into nested scopes)."""
    def visit(n):
        if isinstance(n, (ast.Yield, ast.YieldFrom)):
            return True
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.ClassDef)):
            return False
        return any(visit(c) for c in ast.iter_child_nodes(n))
    return any(visit(x) for x in stmts)


_dec_counter = [0]


def lower_decorators(stmts, in_class=False):
    """@d def f  ->  def _decN_f ... ; f = d(_decN_f)   (methods are handled by the class compiler)."""
    out = []
    for st in stmts:
        for fld in ("body", "orelse", "finalbody"):
            if hasattr(st, fld) and isinstance(getattr(st, fld), list) and not isinstance(st, ast.ClassDef):
                setattr(st, fld, lower_decorators(getattr(st, fld)))
        if isinstance(st, ast.Try):
            for h in st.handlers:
                h.body = lower_decorators(h.body)
        if isinstance(st, ast.ClassDef):
            for item in st.body:
                if isinstance(item, ast.FunctionDef):
                    item.body = lower_decorators(item.body)
        if isinstance(st, ast.FunctionDef) and st.decorator_list:
            _dec_counter[0] += 1
            decs = st.decorator_list
            orig = "_dec%d_%s" % (_dec_counter[0], st.name)
            st.decorator_list = []
            final = st.name
            st.name = orig
            st._pyname = final
            val = ast.Name(id=orig, ctx=ast.Load())
            for d in reversed(decs):
                val = ast.Call(func=d, args=[val], keywords=[])
            asg = ast.Assign(targets=[ast.Name(id=final, ctx=ast.Store())], value=val)
            ast.copy_location(asg, st)
            ast.copy_location(val, st)
            out.append(st)
            out.append(asg)
            continue
        out.append(st)
    return out


# ------------------------------------------------------------------------- scopes

class Scope:
    def __init__(self, kind, frame=None, vars_=None, parent=None):
        self.kind = kind            # "module" | "func"
        self.frame = frame
        self.vars = vars_ or {}
        self.parent = parent
        self.globals = set()
        self.nonlocals = set()


class Gen:
    def __init__(self, tree, flat):
        self.tree = tree
        self.flat = flat
        self.warnings = []
        self.alias = {}             # local name -> dotted module path
        self.from_names = {}        # local name -> dotted path of imported attribute
        self.star = False
        self.funcs = {}             # top-level function name -> FunctionDef
        self.globals = []           # python names that become static fields
        self.global_set = set()
        self.app_vars = set()
        self.class_ctx = []
        self.counter = 0
        self.scope = None
        self.exc_stack = []
        self.loop_stack = []
        self.features = set()
        self.restore_ok = True
        self.uses_reset = False
        self.off = set()            # builtins switched off with #builtins: NAME: disable

    # ------------------------------------------------------------- bookkeeping

    def warn(self, node, msg):
        line = getattr(node, "lineno", "?")
        self.warnings.append("line %s: %s" % (line, msg))

    def uid(self):
        self.counter += 1
        return self.counter

    def add_global(self, name):
        if name not in self.global_set:
            self.global_set.add(name)
            self.globals.append(name)

    # ------------------------------------------------------------------ prepass

    def prepass(self):
        body = self.tree.body
        names, glob = collect_stores([s for s in body if not isinstance(s, ast.FunctionDef)])
        for n in names:
            if n not in self.funcs:
                self.add_global(n)
        for s in body:
            if isinstance(s, ast.FunctionDef):
                self.funcs[s.name] = s
        for s in ast.walk(self.tree):
            if isinstance(s, ast.Global):
                for n in s.names:
                    self.add_global(n)
            if isinstance(s, (ast.Import, ast.ImportFrom)):
                self.handle_import(s)
        # class-attribute globals created by flattening
        for a in self.flat.attrs:
            self.add_global("self_" + a)
        # variables that hold an app instance
        for s in ast.walk(self.tree):
            if isinstance(s, ast.Assign) and isinstance(s.value, ast.Call) \
                    and isinstance(s.value.func, ast.Name) and s.value.func.id in self.flat.classes:
                for t in s.targets:
                    if isinstance(t, ast.Name):
                        self.app_vars.add(t.id)

    def handle_import(self, node):
        if isinstance(node, ast.Import):
            for al in node.names:
                top = al.name.split(".")[0]
                if top == "tkinter" or top in MODULES:
                    self.alias[al.asname or top] = al.name if al.asname else top
                    if not al.asname and "." in al.name:
                        # `import tkinter.messagebox` binds `tkinter`
                        self.alias[top] = top
                elif top not in IGNORED_MODULES:
                    self.warn(node, "module '%s' is not available in Apple apps" % al.name)
        else:
            mod = node.module or ""
            top = mod.split(".")[0]
            if top == "tkinter":
                for al in node.names:
                    if al.name == "*":
                        self.star = True
                    else:
                        self.from_names[al.asname or al.name] = mod + "." + al.name
            elif top in MODULES:
                for al in node.names:
                    self.from_names[al.asname or al.name] = mod + "." + al.name
            elif top not in IGNORED_MODULES:
                self.warn(node, "module '%s' is not available in Apple apps" % mod)

    # ----------------------------------------------------------- name handling

    def canon(self, node):
        """Canonical tkinter/module name for a Name/Attribute, or None."""
        d = dotted(node)
        if not d:
            return None
        parts = d.split(".")
        head = parts[0]
        if self.lookup_local(head) is not None:
            return None
        if "filepick" in self.features and head not in self.global_set and head not in self.funcs:
            if head == "filepick" and len(parts) == 2:
                return "filedialog." + parts[1]
            if len(parts) == 1 and head in FILEPICK and head not in self.from_names:
                return "filedialog." + head
        if head in self.alias:
            parts = self.alias[head].split(".") + parts[1:]
        elif head in self.from_names:
            parts = self.from_names[head].split(".") + parts[1:]
        elif (len(parts) == 1 and head not in self.global_set and head not in self.funcs
              and (self.star or head in WIDGETS or head in VARS or head in UNSUPPORTED_WIDGETS or head == "Tk")):
            pass
        else:
            return None
        while parts and parts[0] in ("tkinter", "ttk"):
            parts = parts[1:]
        return ".".join(parts) if parts else None

    def lookup_local(self, name):
        sc = self.scope
        while sc is not None:
            if sc.kind == "func" and name in sc.vars:
                return "%s[%d]" % (sc.frame, sc.vars[name])
            sc = sc.parent
        return None

    def load_name(self, node):
        n = node.id
        loc = self.lookup_local(n)
        if loc:
            return loc
        if n in self.global_set:
            return "g_" + n
        if n in self.funcs:
            return "F_" + n
        if n == "__name__":
            return '"__main__"'
        if n in TK_BOOL and (self.star or n in self.from_names):
            return TK_BOOL[n]
        if n in TK_CONST and (self.star or n in self.from_names):
            return jstr(TK_CONST[n])
        if n in self.from_names:
            c = self.canon(node)
            if c in TK_CONST:
                return jstr(TK_CONST[c])
        if n in BUILTINS:
            return ('new Fn() { public Object call(Object... _x) { return Py.bi(%s, _x); } }' % jstr(n))
        if n in EXCEPTIONS or n in self.flat.exc_classes:
            return jstr(n)
        if n in TYPE_NAMES:
            return jstr(n)
        self.warn(node, "name '%s' is not defined" % n)
        return "null"

    def store_target(self, node):
        """Java lvalue for assigning to a Name."""
        n = node.id
        sc = self.scope
        if sc.kind == "func" and n in sc.nonlocals:
            o = sc.parent
            while o is not None:
                if o.kind == "func" and n in o.vars:
                    return "%s[%d]" % (o.frame, o.vars[n])
                o = o.parent
        if sc.kind == "func" and n in sc.vars:
            return "%s[%d]" % (sc.frame, sc.vars[n])
        self.add_global(n)
        return "g_" + n

    # -------------------------------------------------------------- expressions

    def expr(self, n):
        m = getattr(self, "e_" + type(n).__name__, None)
        if m is None:
            self.warn(n, "unsupported expression: %s" % type(n).__name__)
            return "null"
        return m(n)

    def cond(self, n):
        """Java boolean expression for Python truthiness."""
        if isinstance(n, ast.Compare):
            return self.compare(n)
        if isinstance(n, ast.BoolOp):
            op = " && " if isinstance(n.op, ast.And) else " || "
            return "(" + op.join(self.cond(v) for v in n.values) + ")"
        if isinstance(n, ast.UnaryOp) and isinstance(n.op, ast.Not):
            return "(!" + self.cond(n.operand) + ")"
        if isinstance(n, ast.Constant):
            return "Py.T" if n.value else "Py.F"
        return "Py.truth(%s)" % self.expr(n)

    def e_Constant(self, n):
        v = n.value
        if v is None:
            return "((Object) null)"
        if v is True:
            return "Boolean.TRUE"
        if v is False:
            return "Boolean.FALSE"
        if isinstance(v, str):
            return jstr(v)
        if isinstance(v, int):
            if -(2 ** 63) <= v < 2 ** 63:
                return "Long.valueOf(%dL)" % v
            self.warn(n, "integer too large, using float")
            return "Double.valueOf(%r)" % float(v)
        if isinstance(v, float):
            if v != v:
                return "Double.valueOf(Double.NaN)"
            if v in (float("inf"), float("-inf")):
                return "Double.valueOf(Double.%sINFINITY)" % ("POSITIVE_" if v > 0 else "NEGATIVE_")
            return "Double.valueOf(%r)" % v
        if isinstance(v, bytes):
            return "Oop.bytesLit(%s)" % jstr(v.decode("latin-1"))
        self.warn(n, "unsupported constant")
        return "null"

    def e_Name(self, n):
        return self.load_name(n)

    def e_Yield(self, n):
        return "Oop.yld(%s)" % (self.expr(n.value) if n.value is not None else "null")

    def e_YieldFrom(self, n):
        return "Oop.yldFrom(%s)" % self.expr(n.value)

    def e_Attribute(self, n):
        c = self.canon(n)
        if c:
            if c in TK_CONST:
                return jstr(TK_CONST[c])
            if c in TK_BOOL:
                return TK_BOOL[c]
            head = c.split(".")[0]
            if head in MODULES and "." in c:
                mod, rest = c.split(".", 1)
                return 'Py.modc(%s, %s)' % (jstr(mod), jstr(rest))
            if not isinstance(n.value, (ast.Call, ast.Subscript)) and dotted(n) and \
                    self.lookup_local(dotted(n).split(".")[0]) is None and dotted(n).split(".")[0] not in self.global_set:
                self.warn(n, "'%s' is not supported" % dotted(n))
                return "null"
        return "Py.attr(%s, %s)" % (self.expr(n.value), jstr(n.attr))

    def e_JoinedStr(self, n):
        parts = []
        for v in n.values:
            if isinstance(v, ast.Constant):
                parts.append(jstr(str(v.value)))
            elif isinstance(v, ast.FormattedValue):
                val = self.expr(v.value)
                if v.conversion == 114:
                    val = "Py.repr(%s)" % val
                if v.format_spec is not None:
                    spec = self.expr(v.format_spec)
                    parts.append("Py.fmt(%s, %s)" % (val, spec))
                else:
                    parts.append(val)
        if not parts:
            return '""'
        return "Py.cat(%s)" % ", ".join(parts)

    def e_BinOp(self, n):
        ops = {ast.Add: "add", ast.Sub: "sub", ast.Mult: "mul", ast.Div: "div", ast.FloorDiv: "floordiv",
               ast.Mod: "mod", ast.Pow: "pow"}
        bits = {ast.BitAnd: "&", ast.BitOr: "|", ast.BitXor: "^", ast.LShift: "<<", ast.RShift: ">>"}
        t = type(n.op)
        if t in ops:
            return "Py.%s(%s, %s)" % (ops[t], self.expr(n.left), self.expr(n.right))
        if t in bits:
            return 'Py.bit("%s", %s, %s)' % (bits[t], self.expr(n.left), self.expr(n.right))
        self.warn(n, "unsupported operator")
        return "null"

    def e_UnaryOp(self, n):
        if isinstance(n.op, ast.Not):
            return "Boolean.valueOf(!%s)" % self.cond(n.operand)
        if isinstance(n.op, ast.USub):
            return "Py.neg(%s)" % self.expr(n.operand)
        if isinstance(n.op, ast.UAdd):
            return self.expr(n.operand)
        return 'Py.bit("~", %s, null)' % self.expr(n.operand)

    def e_BoolOp(self, n):
        fn = "and" if isinstance(n.op, ast.And) else "or"
        out = self.expr(n.values[-1])
        for v in reversed(n.values[:-1]):
            out = "Py.%s(%s, %s)" % (fn, self.expr(v), self.lazy(out))
        return out

    def lazy(self, java_expr):
        return "new Fn() { public Object call(Object... _x) { return %s; } }" % java_expr

    def compare(self, n):
        parts = []
        left = n.left
        for op, right in zip(n.ops, n.comparators):
            parts.append(self.cmp1(left, op, right))
            left = right
        return parts[0] if len(parts) == 1 else "(" + " && ".join(parts) + ")"

    def cmp1(self, l, op, r):
        if isinstance(op, (ast.Is, ast.IsNot)):
            neg = isinstance(op, ast.IsNot)
            if isinstance(r, ast.Constant) and r.value is None:
                return "(%s %s null)" % (self.expr(l), "!=" if neg else "==")
            if isinstance(l, ast.Constant) and l.value is None:
                return "(%s %s null)" % (self.expr(r), "!=" if neg else "==")
            return "(%sPy.is(%s, %s))" % ("!" if neg else "", self.expr(l), self.expr(r))
        if isinstance(op, (ast.Eq, ast.NotEq, ast.Is, ast.IsNot)):
            for tl, tr in ((l, r), (r, l)):
                if isinstance(tl, ast.Call) and isinstance(tl.func, ast.Name) and tl.func.id == "type" \
                        and len(tl.args) == 1 and isinstance(tr, ast.Name) and tr.id in TYPE_NAMES:
                    base = "Ext.typeIs(%s, %s)" % (self.expr(tl.args[0]), jstr(tr.id))
                    return "(!%s)" % base if isinstance(op, (ast.NotEq, ast.IsNot)) else base
        a, b = self.expr(l), self.expr(r)
        if isinstance(op, ast.Eq):
            return "Py.eq(%s, %s)" % (a, b)
        if isinstance(op, ast.NotEq):
            return "(!Py.eq(%s, %s))" % (a, b)
        if isinstance(op, ast.In):
            return "Py.contains(%s, %s)" % (b, a)
        if isinstance(op, ast.NotIn):
            return "(!Py.contains(%s, %s))" % (b, a)
        sym = {ast.Lt: "<", ast.LtE: "<=", ast.Gt: ">", ast.GtE: ">="}[type(op)]
        return "(Py.cmp(%s, %s) %s 0)" % (a, b, sym)

    def e_Compare(self, n):
        return "Boolean.valueOf(%s)" % self.compare(n)

    def e_IfExp(self, n):
        return "(%s ? (Object) %s : (Object) %s)" % (self.cond(n.test), self.expr(n.body), self.expr(n.orelse))

    def elts(self, n):
        starred = any(isinstance(e, ast.Starred) for e in n.elts)
        parts = [("Ext.star(%s)" % self.expr(e.value)) if isinstance(e, ast.Starred) else self.expr(e) for e in n.elts]
        return ("Ext.args(%s)" % ", ".join(parts)) if starred else ", ".join(parts)

    def e_List(self, n):
        return "Py.listOf(%s)" % self.elts(n)

    def e_Tuple(self, n):
        return "Py.tupleOf(%s)" % self.elts(n)

    def e_Set(self, n):
        return "Py.setOf(%s)" % self.elts(n)

    def e_Dict(self, n):
        items = []
        star = False
        for k, v in zip(n.keys, n.values):
            if k is None:
                star = True
                items += ["Ext.dstar(%s)" % self.expr(v), "null"]
            else:
                items += [self.expr(k), self.expr(v)]
        return "%s(%s)" % ("Ext.mkDict" if star else "Py.dict", ", ".join(items))

    def e_Subscript(self, n):
        return "Py.idx(%s, %s)" % (self.expr(n.value), self.index(n.slice))

    def index(self, s):
        if isinstance(s, ast.Slice):
            f = lambda x: "null" if x is None else self.expr(x)
            return "Py.slice(%s, %s, %s)" % (f(s.lower), f(s.upper), f(s.step))
        if hasattr(ast, "Index") and type(s).__name__ == "Index":  # py<3.9
            return self.expr(s.value)
        return self.expr(s)

    def e_Lambda(self, n):
        return self.function_expr(n.args, [ast.Return(value=n.body)], n)

    def comp(self, n, kind):
        """List/set/dict comprehension or generator expression as inline Java loops."""
        if any(g.is_async for g in n.generators):
            self.warn(n, "async comprehensions are not supported")
            return "null"
        fid = self.uid()
        frame = "L%d" % fid
        names = []
        for g in n.generators:
            nm, _ = collect_stores([ast.Assign(targets=[g.target], value=ast.Constant(value=None))])
            for x in nm:
                if x not in names:
                    names.append(x)
        first_iter = self.expr(n.generators[0].iter)      # evaluated in the enclosing scope
        sc = Scope("func", frame, {nm: i for i, nm in enumerate(names)}, self.scope)
        saved, self.scope = self.scope, sc
        saved_ok, self.restore_ok = self.restore_ok, False
        try:
            lines = []
            close = []
            ind = "        "
            for gi, g in enumerate(n.generators):
                it = first_iter if gi == 0 else self.expr(g.iter)
                v = "_i%d" % self.uid()
                lines.append("%sfor (Object %s : Py.iter(%s)) {" % (ind, v, it))
                lines.append(self.assign_stmt(g.target, v, ind + "    "))
                close.append(ind + "}")
                ind += "    "
                for cnd in g.ifs:
                    lines.append("%sif (%s) {" % (ind, self.cond(cnd)))
                    close.append(ind + "}")
                    ind += "    "
            if kind == "dict":
                add = "%s_r.put(Py.key(%s), %s);" % (ind, self.expr(n.key), self.expr(n.value))
            elif kind == "set":
                add = "%s_r.add(Py.key(%s));" % (ind, self.expr(n.elt))
            else:
                add = "%s_r.add(%s);" % (ind, self.expr(n.elt))
            lines.append(add)
        finally:
            self.scope = saved
            self.restore_ok = saved_ok
        decl = {"dict": "java.util.Map<Object, Object> _r = new java.util.LinkedHashMap<Object, Object>();",
                "set": "java.util.Set<Object> _r = new java.util.LinkedHashSet<Object>();",
                "list": "java.util.List<Object> _r = new java.util.ArrayList<Object>();"}[kind]
        body = "\n".join(lines + list(reversed(close)))
        return ("new Fn() { public Object call(Object... _x%d) { final Object[] %s = new Object[%d]; %s\n%s\n        return _r; } }.call()"
                % (fid, frame, max(1, len(names)), decl, body))

    def e_ListComp(self, n):
        return self.comp(n, "list")

    e_GeneratorExp = e_ListComp

    def e_SetComp(self, n):
        return self.comp(n, "set")

    def e_DictComp(self, n):
        return self.comp(n, "dict")

    # ------------------------------------------------------------------- calls

    def e_Call(self, n):
        has_star = any(isinstance(a, ast.Starred) for a in n.args) or any(k.arg is None for k in n.keywords)
        kws = [k for k in n.keywords if k.arg is not None]
        args = [a for a in n.args if not isinstance(a, ast.Starred)]
        func = n.func

        c = self.canon(func) if isinstance(func, (ast.Name, ast.Attribute)) else None
        if c:
            r = self.tk_call(n, c, args, kws, has_star)
            if r is not None:
                return r

        if isinstance(func, ast.Name):
            name = func.id
            if name == "super":
                return "null"
            if self.lookup_local(name) is None and name not in self.global_set:
                if name in self.flat.classes:
                    init = self.flat.inits.get(name)
                    if not init:
                        return "Py.callApp(null)"
                    if has_star:
                        return "Py.callApp(F_%s, %s)" % (init, self.dyn_args(n))
                    slots = self.slots(n, init, args, kws)
                    return "Py.callApp(%s%s)" % ("F_" + init, "".join(", " + x for x in slots))
                if name in self.funcs:
                    if has_star:
                        return self.user_call_dyn(n, name)
                    return self.user_call(n, name, args, kws)
                if name in BUILTINS:
                    return self.builtin_call(n, name, args, kws, has_star)
                if name in EXCEPTIONS or name in self.flat.exc_classes:
                    return 'Py.exc("%s"%s)' % (name, "".join(", " + self.expr(x) for x in args))
            ref = self.load_name(func)
            return "Py.call(%s%s)" % (ref, self.call_tail(n, has_star))

        if isinstance(func, ast.Attribute):
            return self.method_call(n, func, args, kws, has_star)

        return "Py.call(%s%s)" % (self.expr(func), self.call_tail(n, has_star))

    def dyn_args(self, n):
        """`Ext.args(...)` expression: positional, *star, keyword and **dstar parts in source order."""
        parts = []
        for a in n.args:
            parts.append("Ext.star(%s)" % self.expr(a.value) if isinstance(a, ast.Starred) else self.expr(a))
        for k in n.keywords:
            parts.append("Ext.dstar(%s)" % self.expr(k.value) if k.arg is None else 'Py.kw("%s", %s)' % (k.arg, self.expr(k.value)))
        return "Ext.args(%s)" % ", ".join(parts)

    def call_tail(self, n, has_star):
        """Arguments of a generic call as `, a, b, Py.kw(..)` (or one Ext.args array)."""
        if has_star:
            return ", " + self.dyn_args(n)
        out = "".join(", " + self.expr(a) for a in n.args)
        for k in n.keywords:
            out += ', Py.kw("%s", %s)' % (k.arg, self.expr(k.value))
        return out

    def kwlist(self, kws):
        return [(k.arg, self.expr(k.value)) for k in kws]

    def tk_call(self, n, c, args, kws, has_star=False):
        kind = c.split(".")[-1]
        if c in ("Tk",):
            return "Ui.tk()"
        if c == "Style" or c.startswith("font.") or c == "Font":
            return "Ui.dummy()"
        if c in WIDGETS:
            if has_star:
                return 'Ui.wk("%s", %s)' % (c, self.dyn_args(n))
            parent = self.expr(args[0]) if args else "null"
            kv = []
            if c == "OptionMenu":
                vals = []
                for a in n.args[2:]:
                    vals.append("Ext.star(%s)" % self.expr(a.value) if isinstance(a, ast.Starred) else self.expr(a))
                if len(n.args) > 1:
                    kv += ['"variable"', self.expr(n.args[1])]
                kv += ['"values"', "Ext.dedupe(Ext.args(%s))" % ", ".join(vals)]
            for k, v in self.kwlist(kws):
                if k in ("master", "parent"):
                    parent = v
                else:
                    kv += [jstr(k), v]
            return 'Ui.w("%s", %s%s)' % (c, parent, "".join(", " + x for x in kv))
        if c == "LabeledScale":
            parent = self.expr(args[0]) if args else "null"
            kv = []
            for k in n.keywords:
                if k.arg in ("variable", "from_", "to", "compound"):
                    kv += [jstr(k.arg), self.expr(k.value)]
            if len(args) > 1 and not any(k.arg == "variable" for k in n.keywords):
                kv += ['"variable"', self.expr(args[1])]
            return "Ui.labeledScale(%s%s)" % (parent, "".join(", " + x for x in kv))
        if c == "PhotoImage":
            kv = []
            for k in n.keywords:
                if k.arg in ("file", "data", "format", "width", "height", "gamma", "palette"):
                    kv += [jstr(k.arg), self.expr(k.value)]
                    if k.arg == "file" and isinstance(k.value, ast.Constant) and isinstance(k.value.value, str):
                        lit = k.value.value
                        src = lit if os.path.isabs(lit) else os.path.join(SCRIPT_DIR, lit)
                        if os.path.isfile(src):
                            ext = os.path.splitext(lit)[1].lower() or ".png"
                            name = "img/" + hashlib.md5(lit.encode("utf-8")).hexdigest()[:12] + ext
                            IMAGE_FILES[name] = src
                            kv += ['"asset"', jstr(name)]
                        else:
                            self.warn(n, "image file '%s' was not found next to the script; the app will look for it on the phone" % lit)
                elif k.arg in ("master", "name", "cnf"):
                    pass
                else:
                    self.warn(n, "PhotoImage option '%s' is ignored" % k.arg)
            return "Ui.photo(%s)" % ", ".join(kv)
        if c in UNSUPPORTED_WIDGETS:
            self.warn(n, "%s is not supported yet; a placeholder label is shown instead" % c)
            parent = self.expr(args[0]) if args else "null"
            return 'Ui.w("%s", %s)' % (c, parent)
        if c in VARS:
            init = self.expr(args[0]) if args else "null"
            for k, v in self.kwlist(kws):
                if k == "value":
                    init = v
            return 'Py.var("%s", %s)' % (c, init)
        if c.startswith("messagebox."):
            fn = c.split(".", 1)[1]
            vals = [self.expr(a) for a in args]
            for k, v in self.kwlist(kws):
                if k == "title":
                    vals.insert(0, v)
                elif k == "message":
                    vals.append(v)
            while len(vals) < 2:
                vals.append('""')
            return 'Ui.msg("%s", %s, %s)' % (fn.replace("show", ""), vals[0], vals[1])
        if c.startswith("simpledialog."):
            fn = c.split(".", 1)[1]
            if fn not in ("askstring", "askinteger", "askfloat"):
                self.warn(n, "%s is not supported" % c)
                return "null"
            slot = {"title": None, "prompt": None, "initialvalue": None, "minvalue": None, "maxvalue": None, "show": None}
            order = ["title", "prompt"]
            for i, a in enumerate(args[:2]):
                slot[order[i]] = self.expr(a)
            for k, v in self.kwlist(kws):
                if k in slot:
                    slot[k] = v
            vals = ["null" if slot[k] is None else slot[k] for k in ("title", "prompt", "initialvalue", "minvalue", "maxvalue", "show")]
            return 'Ui.simple("%s", %s)' % (fn, ", ".join(vals))
        if c.startswith("filedialog."):
            fn = c.split(".", 1)[1]
            if fn not in FILEPICK:
                self.warn(n, "%s is not supported" % c)
                return "null"
            return 'Ui.filedialog("%s"%s)' % (fn, self.call_tail(n, has_star))
        if c.startswith("colorchooser."):
            if c != "colorchooser.askcolor":
                self.warn(n, "%s is not supported" % c)
                return "null"
            return 'Ui.askcolor(%s)' % self.call_tail(n, has_star).lstrip(", ")
        if c.split(".")[0] in MODULES and "." in c:
            mod, fn = c.split(".", 1)
            tail = self.call_tail(n, has_star)
            # a bare Py.mod("m", "f") would bind to the % operator overload, so pass an explicit empty array
            return 'Py.mod("%s", "%s"%s)' % (mod, fn, tail if tail.strip() else ", new Object[0]")
        return None

    def builtin_call(self, n, name, args, kws, has_star=False):
        if has_star:
            return 'Py.bi("%s", %s)' % (name, self.dyn_args(n))
        if name == "isinstance" and len(args) == 2:
            return 'Py.bi("isinstance", %s, %s)' % (self.expr(args[0]), self.type_expr(args[1]))
        if name == "input":
            return "Ui.input(%s)" % (self.expr(args[0]) if args else "null")
        parts = [self.expr(a) for a in args]
        for k, v in self.kwlist(kws):
            parts.append('Py.kw("%s", %s)' % (k, v))
        return 'Py.bi("%s"%s)' % (name, "".join(", " + p for p in parts))

    def type_expr(self, t):
        """Type names for isinstance(): Java strings (or a tuple of them)."""
        if isinstance(t, ast.Tuple):
            return "Py.tupleOf(%s)" % ", ".join(self.type_expr(e) for e in t.elts)
        d = dotted(t)
        if d:
            last = d.split(".")[-1]
            if last == "Tk":
                last = "Wid"
            return jstr("set" if last == "frozenset" else last)
        self.warn(t, "isinstance() needs a type name")
        return '"object"'

    def slots(self, n, name, args, kws):
        """Positional Java argument list for a call to a known top-level function."""
        fd = self.funcs[name]
        params = [a.arg for a in fd.args.args]
        kwonly = [a.arg for a in fd.args.kwonlyargs]
        slots = [None] * len(params)
        extra = []
        for i, a in enumerate(args):
            if i < len(params):
                slots[i] = self.expr(a)
            elif fd.args.vararg:
                extra.append(self.expr(a))
            else:
                self.warn(n, "too many arguments for %s()" % name)
        kwextra = []
        for k, v in self.kwlist(kws):
            if k in params:
                slots[params.index(k)] = v
            elif k in kwonly or fd.args.kwarg:
                kwextra.append('Py.kw("%s", %s)' % (k, v))
            else:
                self.warn(n, "%s() has no parameter '%s'" % (name, k))
        while slots and slots[-1] is None:
            slots.pop()
        out = ["Py.MISSING" if x is None else x for x in slots]
        if extra:
            out += ["Py.MISSING"] * (len(params) - len(out)) + extra
        return out + kwextra

    def user_call(self, n, name, args, kws):
        return "F_%s.call(%s)" % (name, ", ".join(self.slots(n, name, args, kws)))

    def needs_bind(self, fd):
        a = fd.args
        return bool(a.vararg or a.kwarg or a.kwonlyargs)

    def user_call_dyn(self, n, name):
        fd = self.funcs[name]
        if self.needs_bind(fd):
            return "F_%s.call(%s)" % (name, self.dyn_args(n))
        params = ", ".join(jstr(a.arg) for a in fd.args.args)
        return "Ext.callNamed(F_%s, new String[] { %s }, %s)" % (name, params, self.dyn_args(n))

    def method_call(self, n, func, args, kws, has_star=False):
        attr = func.attr
        recv_name = func.value.id if isinstance(func.value, ast.Name) else None
        # super().method(...) / super(Cls, self).method(...)
        if isinstance(func.value, ast.Call) and isinstance(func.value.func, ast.Name) and func.value.func.id == "super":
            if not self.class_ctx or self.class_ctx[-1][1] is None:
                self.warn(n, "super() used outside a method")
                return "null"
            jv, selfname = self.class_ctx[-1]
            return 'Oop.sup(%s, %s, %s%s)' % (self.load_name(ast.Name(id=selfname, ctx=ast.Load())), jv, jstr(attr), self.call_tail(n, has_star))
        # Frame.__init__(self, parent, ...) with a tkinter base class
        if attr == "__init__" and isinstance(func.value, (ast.Name, ast.Attribute)) and args:
            cb = self.canon(func.value)
            if cb and (cb in WIDGETS or cb == "Tk"):
                rest = "".join(", " + self.expr(x) for x in args[1:]) + "".join(", Py.kw(%s, %s)" % (jstr(k), v) for k, v in self.kwlist(kws))
                return 'Oop.baseInit(%s, %s%s)' % (self.expr(args[0]), jstr(cb), rest)
        # direct call of a flattened method on self / an app instance
        if attr in self.flat.methods and recv_name is not None and (recv_name == "self" or recv_name in self.app_vars):
            mname = self.flat.methods[attr]
            if has_star:
                return self.user_call_dyn(n, mname)
            return self.user_call(n, mname, args, kws)
        return 'Py.m(%s, "%s"%s)' % (self.expr(func.value), attr, self.call_tail(n, has_star))

    # -------------------------------------------------------------- functions

    def function_expr(self, args_node, body, node, name=None, force_bind=False):
        a = args_node
        params = [x.arg for x in (list(getattr(a, "posonlyargs", [])) + list(a.args))]
        kwonly = [x.arg for x in a.kwonlyargs]
        vararg = a.vararg.arg if a.vararg else None
        kwarg = a.kwarg.arg if a.kwarg else None
        use_bind = bool(vararg or kwarg or kwonly) or force_bind
        defaults = list(a.defaults)
        names, glob = collect_stores(body)
        nonloc = collect_nonlocal(body)
        names = [x for x in names if not (x.startswith("self_") and x[5:] in self.flat.attrs) and x not in nonloc]
        special = [x for x in (vararg, kwarg) if x]
        locals_ = list(params) + [x for x in kwonly if x not in params] + [x for x in special if x not in params]
        locals_ += [x for x in names if x not in locals_]
        fid = self.uid()
        frame = "L%d" % fid
        sc = Scope("func", frame, {nm: i for i, nm in enumerate(locals_)}, self.scope)
        sc.globals = glob - nonloc
        sc.nonlocals = nonloc
        for g in glob - nonloc:
            self.add_global(g)
        # defaults are evaluated now, in the enclosing scope
        if use_bind:
            allnames = params + kwonly
            first_default = len(params) - len(defaults)
            dexprs = []
            for i, nm in enumerate(params):
                dexprs.append(self.expr(defaults[i - first_default]) if i >= first_default else "Py.MISSING")
            for d in a.kw_defaults:
                dexprs.append("Py.MISSING" if d is None else self.expr(d))
            has_defaults = True
        else:
            dexprs = [self.expr(d) for d in defaults]
            has_defaults = bool(defaults)
        saved, self.scope = self.scope, sc
        saved_loops, self.loop_stack = self.loop_stack, []
        try:
            lines = []
            if use_bind:
                fname = name or "<lambda>"
                lines.append("Object[] _b%d = Ext.bind(_a%d, new String[] { %s }, %d, this.d, %s, %s, %s);"
                             % (fid, fid, ", ".join(jstr(x) for x in allnames), len(params),
                                "true" if vararg else "false", "true" if kwarg else "false", jstr(fname)))
                for i, nm in enumerate(allnames):
                    lines.append("%s[%d] = _b%d[%d];" % (frame, sc.vars[nm], fid, i))
                k = len(allnames)
                if vararg:
                    lines.append("%s[%d] = _b%d[%d];" % (frame, sc.vars[vararg], fid, k))
                    k += 1
                if kwarg:
                    lines.append("%s[%d] = _b%d[%d];" % (frame, sc.vars[kwarg], fid, k))
            else:
                lines.append("if (_a%d.length > 0 && _a%d[_a%d.length - 1] instanceof Py.Kw) _a%d = Ext.normKw(_a%d, new String[] { %s });"
                             % (fid, fid, fid, fid, fid, ", ".join(jstr(x) for x in params)))
                first_default = len(params) - len(defaults)
                for i, p in enumerate(params):
                    dv = "this.d[%d]" % (i - first_default) if i >= first_default else "null"
                    lines.append("%s[%d] = (_a%d.length > %d && _a%d[%d] != Py.MISSING) ? _a%d[%d] : %s;"
                                 % (frame, i, fid, i, fid, i, fid, i, dv))
            saved_exc, self.exc_stack = self.exc_stack, []
            try:
                body_lines = self.block(body, 2)
            finally:
                self.exc_stack = saved_exc
        finally:
            self.scope = saved
            self.loop_stack = saved_loops
        head = "new Py.DFn(new Object[] { %s })" % ", ".join(dexprs) if has_defaults else "new Fn()"
        text = "%s {\n      public Object call(Object... _a%d) {\n        final Object[] %s = new Object[%d];\n" % (
            head, fid, frame, max(1, len(locals_)))
        text += "".join("        %s\n" % l for l in lines)
        if has_yield(body):
            text += "        return Oop.generator(new Fn() { public Object call(Object... _g%d) {\n" % fid
            text += "\n".join(body_lines) + "\n        return null;\n      } });\n      }\n    }"
        else:
            text += "\n".join(body_lines) + "\n        return null;\n      }\n    }"
        return text

    # ------------------------------------------------------------- statements

    def block(self, stmts, ind):
        out = []
        for s in stmts:
            out += self.stmt(s, ind)
        return out

    def pad(self, ind):
        return "    " * (ind + 1)

    def stmt(self, s, ind):
        m = getattr(self, "s_" + type(s).__name__, None)
        if m is None:
            self.warn(s, "unsupported statement: %s" % type(s).__name__)
            return []
        out = m(s, ind)
        ln = getattr(s, "lineno", 0)
        if out and ln:
            out[0] = "/*@%d*/%s" % (ln, out[0])
        return out

    def s_Pass(self, s, ind):
        return []

    def s_Import(self, s, ind):
        return []

    s_ImportFrom = s_Import
    s_Global = s_Import
    s_Nonlocal = s_Import

    def s_Expr(self, s, ind):
        v = s.value
        if isinstance(v, ast.Call) and isinstance(v.func, ast.Name) and v.func.id == RESET_MARK:
            self.uses_reset = True
            out = []
            arg = v.args[0].value if v.args and isinstance(v.args[0], ast.Constant) else None
            if arg is True or (arg is None and "datareset-confirm" not in self.off):
                # built-in confirmation; declining leaves the data alone and ends the function
                out.append(self.pad(ind) + "if (!Ui.confirmReset()) { if (Py.T) return null; }")
            out.append(self.pad(ind) + "Ext.resetData(RESET);")
            return out
        if isinstance(s.value, ast.Constant):
            return []
        return [self.pad(ind) + "Py.ignore(%s);" % self.expr(s.value)]

    def s_Assign(self, s, ind):
        out = []
        # app instance created at module level
        val = self.expr(s.value)
        if len(s.targets) == 1:
            out.append(self.assign_stmt(s.targets[0], val, self.pad(ind)))
        else:
            tmp = "_v%d" % self.uid()
            out.append(self.pad(ind) + "{ Object %s = %s;" % (tmp, val))
            for t in s.targets:
                out.append(self.assign_stmt(t, tmp, self.pad(ind)))
            out.append(self.pad(ind) + "}")
        return out

    def s_AnnAssign(self, s, ind):
        if s.value is None:
            return []
        return [self.assign_stmt(s.target, self.expr(s.value), self.pad(ind))]

    def assign_stmt(self, t, val, pad):
        if isinstance(t, ast.Name):
            target = self.store_target(t)
            if "datasaving" in self.features and self.restore_ok and target.startswith("g_"):
                val = "Ext.restore(%s, %s)" % (jstr(t.id), val)
            return "%s%s = %s;" % (pad, target, val)
        if isinstance(t, (ast.Tuple, ast.List)):
            tmp = "_t%d" % self.uid()
            lines = ["%s{ Object[] %s = Py.unpack(%s, %d);" % (pad, tmp, val, len(t.elts))]
            for i, e in enumerate(t.elts):
                lines.append(self.assign_stmt(e, "%s[%d]" % (tmp, i), pad))
            lines.append(pad + "}")
            return "\n".join(lines)
        if isinstance(t, ast.Subscript):
            return "%sPy.setIdx(%s, %s, %s);" % (pad, self.expr(t.value), self.index(t.slice), val)
        if isinstance(t, ast.Attribute):
            return "%sOop.setattr(%s, %s, %s);" % (pad, self.expr(t.value), jstr(t.attr), val)
        if isinstance(t, ast.Starred):
            self.warn(t, "starred assignment targets are not supported")
        else:
            self.warn(t, "cannot assign to this kind of target (only names, items and self.attributes)")
        return pad + ";"

    def s_AugAssign(self, s, ind):
        ops = {ast.Add: "add", ast.Sub: "sub", ast.Mult: "mul", ast.Div: "div", ast.FloorDiv: "floordiv",
               ast.Mod: "mod", ast.Pow: "pow"}
        bits = {ast.BitAnd: "&", ast.BitOr: "|", ast.BitXor: "^", ast.LShift: "<<", ast.RShift: ">>"}
        t = type(s.op)
        if t not in ops and t not in bits:
            self.warn(s, "unsupported augmented assignment")
            return []
        cur = ast.copy_location(type(s.target)(**{f: getattr(s.target, f) for f in s.target._fields}), s.target)
        if isinstance(cur, ast.Name):
            cur.ctx = ast.Load()
        elif hasattr(cur, "ctx"):
            cur.ctx = ast.Load()
        if t in ops:
            val = "Py.%s(%s, %s)" % (ops[t], self.expr(cur), self.expr(s.value))
        else:
            val = 'Py.bit("%s", %s, %s)' % (bits[t], self.expr(cur), self.expr(s.value))
        return [self.assign_stmt(s.target, val, self.pad(ind))]

    def s_If(self, s, ind):
        p = self.pad(ind)
        out = [p + "if (%s) {" % self.cond(s.test)]
        out += self.block(s.body, ind + 1)
        orelse = s.orelse
        while len(orelse) == 1 and isinstance(orelse[0], ast.If):
            e = orelse[0]
            out.append(p + "} else if (%s) {" % ("/*@%d*/" % e.lineno + self.cond(e.test)))
            out += self.block(e.body, ind + 1)
            orelse = e.orelse
        if orelse:
            out.append(p + "} else {")
            out += self.block(orelse, ind + 1)
        out.append(p + "}")
        return out

    def loop(self, head, body, orelse, ind, pre=()):
        """Common loop emitter with Python's for/while-else semantics."""
        p = self.pad(ind)
        if not orelse:
            self.loop_stack.append(None)
            try:
                inner = self.block(body, ind + 1)
            finally:
                self.loop_stack.pop()
            return [p + head] + list(pre) + inner + [p + "}"]
        flag = "_b%d" % self.uid()
        self.loop_stack.append(flag)
        try:
            inner = self.block(body, ind + 1)
        finally:
            self.loop_stack.pop()
        out = [p + "{ boolean %s = false;" % flag, p + head] + list(pre) + inner + [p + "}"]
        out.append(p + "if (!%s) {" % flag)
        out += self.block(orelse, ind + 1)
        out.append(p + "} }")
        return out

    def s_While(self, s, ind):
        return self.loop("while (%s) {" % self.cond(s.test), s.body, s.orelse, ind)

    def s_For(self, s, ind):
        v = "_i%d" % self.uid()
        head = "for (Object %s : Py.iter(%s)) {" % (v, self.expr(s.iter))
        saved, self.restore_ok = self.restore_ok, False
        try:
            assign = self.assign_stmt(s.target, v, self.pad(ind + 1))
        finally:
            self.restore_ok = saved
        return self.loop(head, s.body, s.orelse, ind, pre=[assign])

    def s_Break(self, s, ind):
        flag = self.loop_stack[-1] if self.loop_stack else None
        if flag:
            return [self.pad(ind) + "if (Py.T) { %s = true; break; }" % flag]
        return [self.pad(ind) + "if (Py.T) break;"]

    def s_Continue(self, s, ind):
        return [self.pad(ind) + "if (Py.T) continue;"]

    def s_Return(self, s, ind):
        if self.scope.kind != "func":
            self.warn(s, "'return' outside a function")
            return []
        v = self.expr(s.value) if s.value is not None else "null"
        return [self.pad(ind) + "if (Py.T) return %s;" % v]

    def s_Raise(self, s, ind):
        p = self.pad(ind)
        if s.exc is None:
            if self.exc_stack:
                return [p + "if (Py.T) throw %s;" % self.exc_stack[-1]]
            return [p + 'if (Py.T) throw Py.exc("RuntimeError", "No active exception to re-raise");']
        return [p + "if (Py.T) throw Py.raise(%s);" % self.expr(s.exc)]

    def s_Assert(self, s, ind):
        msg = self.expr(s.msg) if s.msg is not None else "null"
        return [self.pad(ind) + "Py.assertTrue(%s, %s);" % (self.cond(s.test), msg)]

    def s_Try(self, s, ind):
        p = self.pad(ind)
        uid = self.uid()
        out = [p + "try {"]
        out += self.block(s.body + s.orelse, ind + 1)
        if s.handlers:
            ev, pv = "_e%d" % uid, "_p%d" % uid
            out.append(p + "} catch (RuntimeException %s) {" % ev)
            out.append(self.pad(ind + 1) + "Py.PyError %s = Py.wrap(%s);" % (pv, ev))
            first = True
            for h in s.handlers:
                names = self.handler_names(h)
                test = "true" if names is None else "%s.matches(%s)" % (pv, ", ".join(jstr(x) for x in names))
                kw = "if" if first else "else if"
                out.append(self.pad(ind + 1) + "%s (%s) {" % (kw, test))
                if h.name:
                    saved_ok, self.restore_ok = self.restore_ok, False
                    out.append(self.assign_stmt(ast.Name(id=h.name, ctx=ast.Store()), pv, self.pad(ind + 2)))
                    self.restore_ok = saved_ok
                self.exc_stack.append(pv)
                out += self.block(h.body, ind + 2)
                self.exc_stack.pop()
                out.append(self.pad(ind + 1) + "}")
                first = False
            out.append(self.pad(ind + 1) + "else { throw %s; }" % pv)
        if s.finalbody:
            out.append(p + "} finally {")
            out += self.block(s.finalbody, ind + 1)
        out.append(p + "}")
        return out

    def handler_names(self, h):
        if h.type is None:
            return None
        nodes = h.type.elts if isinstance(h.type, ast.Tuple) else [h.type]
        names = []
        for t in nodes:
            d = dotted(t) or "Exception"
            names.append(d.split(".")[-1])
        return names

    def deco_info(self, d):
        """(kind, name) of a decorator expression: kind is None for ones that are applied normally."""
        base = d.func if isinstance(d, ast.Call) else d
        dn = dotted(base) or ""
        last = dn.split(".")[-1]
        return last, dn

    def s_ClassDef(self, s, ind):
        p = self.pad(ind)
        jv = "_c%d" % self.uid()
        bases = []
        for b in s.bases:
            bb = b.value if isinstance(b, ast.Subscript) else b
            d = dotted(bb) or ""
            last = d.split(".")[-1]
            c = self.canon(bb) if isinstance(bb, (ast.Name, ast.Attribute)) else None
            if isinstance(b, ast.Subscript) and last in ("Generic", "Protocol"):
                continue
            if c and (c in WIDGETS or c == "Tk"):
                bases.append(jstr("tk:" + c))
            elif c == "threading.Thread":
                bases.append(jstr("tk:thread"))
            elif last in ("object", "ABC", "ABCMeta", "Generic", "Protocol"):
                continue
            elif last in ("Enum", "IntEnum", "StrEnum", "Flag", "IntFlag"):
                bases.append(jstr("enum:" + last))
            elif last in ("dict", "list", "set", "tuple", "str", "int", "float"):
                self.warn(s, "subclassing the built-in type '%s' is not supported" % last)
            else:
                bases.append(self.expr(b))
        out = [p + "{ final Oop.Cls %s = Oop.cls(%s%s);" % (jv, jstr(s.name), "".join(", " + x for x in bases))]
        fields, fdefs = [], []
        dc = None
        extra_decos = []
        for d in s.decorator_list:
            last, dn = self.deco_info(d)
            if last == "dataclass":
                dc = d
            elif last not in ("total_ordering", "final", "runtime_checkable"):
                extra_decos.append(d)
        for item in s.body:
            if isinstance(item, ast.FunctionDef):
                kind, extra, selfname = 0, [], None
                if item.args.args:
                    selfname = item.args.args[0].arg
                for d in item.decorator_list:
                    last, dn = self.deco_info(d)
                    if last == "staticmethod":
                        kind, selfname = 1, None
                    elif last == "classmethod":
                        kind = 2
                    elif last == "property":
                        kind = 3
                    elif last == "setter":
                        kind = 4
                    elif last in ("abstractmethod", "overload", "override", "getter", "deleter", "final", "cached_property"):
                        if last == "cached_property":
                            kind = 3
                    else:
                        extra.append(d)
                self.class_ctx.append((jv, selfname))
                try:
                    fexpr = "Oop.fname(%s, %s)" % (self.function_expr(item.args, item.body, item, item.name, force_bind=True), jstr(item.name))
                finally:
                    self.class_ctx.pop()
                for d in reversed(extra):
                    fexpr = "Py.call(%s, %s)" % (self.expr(d), fexpr)
                out.append("%s  %s.def(%s, %s, %d);" % (p, jv, jstr(item.name), fexpr, kind))
            elif isinstance(item, ast.Assign):
                for t in item.targets:
                    if isinstance(t, ast.Name):
                        out.append("%s  %s.set(%s, %s);" % (p, jv, jstr(t.id), self.class_value(item.value)))
                    else:
                        self.warn(item, "only simple names can be assigned in a class body")
            elif isinstance(item, ast.AnnAssign) and isinstance(item.target, ast.Name):
                if dc is not None:
                    fields.append(jstr(item.target.id))
                    fdefs.append("Py.MISSING" if item.value is None else self.class_value(item.value))
                elif item.value is not None:
                    out.append("%s  %s.set(%s, %s);" % (p, jv, jstr(item.target.id), self.class_value(item.value)))
            elif isinstance(item, ast.ClassDef):
                inner = self.s_ClassDef(item, ind + 1)
                out += inner
                tgt = self.store_target(ast.Name(id=item.name, ctx=ast.Store()))
                out.append("%s  %s.set(%s, %s);" % (p, jv, jstr(item.name), tgt))
            elif isinstance(item, (ast.Pass,)) or (isinstance(item, ast.Expr) and isinstance(item.value, ast.Constant)):
                pass
            else:
                self.warn(item, "unsupported statement in a class body")
        if dc is not None:
            order = frozen = "false"
            if isinstance(dc, ast.Call):
                for k in dc.keywords:
                    if k.arg == "order" and isinstance(k.value, ast.Constant):
                        order = "true" if k.value.value else "false"
                    if k.arg == "frozen" and isinstance(k.value, ast.Constant):
                        frozen = "true" if k.value.value else "false"
            out.append("%s  Oop.dataclass(%s, new String[] { %s }, new Object[] { %s }, %s, %s);" % (
                p, jv, ", ".join(fields), ", ".join(fdefs), order, frozen))
        if any(b.startswith('"enum:') for b in bases):
            out.append("%s  Oop.finishEnum(%s);" % (p, jv))
        val = jv
        for d in reversed(extra_decos):
            val = "Py.call(%s, %s)" % (self.expr(d), val)
        out.append("%s  %s = %s; }" % (p, self.store_target(ast.Name(id=s.name, ctx=ast.Store())), val))
        return out

    def class_value(self, v):
        """Expression for a class-body value (handles dataclass field(...) and enum auto())."""
        if isinstance(v, ast.Call):
            last = (dotted(v.func) or "").split(".")[-1]
            if last == "field":
                args = ["Py.kw(%s, %s)" % (jstr(k), x) for k, x in self.kwlist(v.keywords)]
                return "Oop.field(%s)" % ", ".join(args)
            if last == "auto" and not v.args:
                return "Oop.AUTO"
        return self.expr(v)

    def s_FunctionDef(self, s, ind):
        expr = "Oop.fname(%s, %s)" % (self.function_expr(s.args, s.body, s, s.name), jstr(getattr(s, "_pyname", s.name)))
        p = self.pad(ind)
        if self.scope.kind == "module":
            self.funcs[s.name] = s
            return ["%sF_%s = %s;" % (p, s.name, expr)]
        return ["%s%s[%d] = %s;" % (p, self.scope.frame, self.scope.vars[s.name], expr)]

    def s_With(self, s, ind):
        """with a as x, b as y: body  ->  enter both, run body, exit in reverse order (always)."""
        p = self.pad(ind)
        uid = self.uid()
        out = [p + "{"]
        n = len(s.items)
        for k, it in enumerate(s.items):
            w = "_w%d_%d" % (uid, k)
            out.append(p + "  Object %s = %s;" % (w, self.expr(it.context_expr)))
            if it.optional_vars is not None:
                saved, self.restore_ok = self.restore_ok, False
                out.append(self.assign_stmt(it.optional_vars, "Ext.enter(%s)" % w, p + "  "))
                self.restore_ok = saved
            else:
                out.append(p + "  Ext.enter(%s);" % w)
            out.append(p + "  try {")
        out += self.block(s.body, ind + 1 + n)
        for k in reversed(range(n)):
            out.append(p + "  } finally { Ext.exit(_w%d_%d); }" % (uid, k))
        out.append(p + "}")
        return out

    def s_Delete(self, s, ind):
        out = []
        for t in s.targets:
            if isinstance(t, ast.Subscript):
                out.append("%sExt.delIdx(%s, %s);" % (self.pad(ind), self.expr(t.value), self.index(t.slice)))
            elif isinstance(t, ast.Attribute):
                out.append("%sOop.delattr(%s, %s);" % (self.pad(ind), self.expr(t.value), jstr(t.attr)))
            elif isinstance(t, ast.Name):
                out.append("%s%s = null;" % (self.pad(ind), self.store_target(t)))
            else:
                self.warn(s, "'del' of this kind of target is not supported")
        return out

    # ------------------------------------------------------------------ output

    def generate(self):
        self.prepass()
        self.scope = Scope("module")
        body = self.tree.body
        lines = self.block(body, 1)
        fields = ["    static Object g_%s;" % g for g in self.globals]
        fields += ["    static Fn F_%s;" % f for f in self.func_names()]
        src = [
            "package apple.gen;",
            "",
            "import apple.rt.*;",
            "",
            "/** Generated by PocketTk from your Python script. Do not edit. */",
            "public final class Script {",
            "    private Script() {}",
            "",
        ]
        src += fields
        if "datasaving" in self.features:
            src.append("    static final Fn SNAP = new Fn() { public Object call(Object... _x) {")
            src.append("        java.util.Map<Object, Object> m = new java.util.LinkedHashMap<Object, Object>();")
            for g in self.globals:
                src.append("        m.put(%s, g_%s);" % (jstr(g), g))
            src.append("        return m; } };")
        if self.uses_reset and "datasaving" in self.features:
            src.append("    static final Fn RESET = new Fn() { public Object call(Object... _x) {")
            for g in self.globals:
                src.append("        g_%s = Ext.initialValue(%s, g_%s);" % (g, jstr(g), g))
            src.append("        return null; } };")
        src += ["", "    public static void main() {"]
        for name, parent in sorted(self.flat.exc_classes.items()):
            src.append("        Py.defExc(%s, %s);" % (jstr(name), jstr(parent)))
        src += lines
        src += ["    }", "}", ""]
        text = "\n".join(src)
        self.linemap = self.build_linemap(text)
        return re.sub(r"/\*@\d+\*/", "", text)

    def build_linemap(self, text):
        """Java line number -> Python line number (index = Java line, 1-based)."""
        cur = 0
        out = [0]
        # line numbers must be computed on the text with markers removed, one marker per source line
        for ln in text.split("\n"):
            ms = re.findall(r"/\*@(\d+)\*/", ln)
            if ms:
                out.append(int(ms[0]))
                cur = int(ms[-1])
            else:
                out.append(cur)
        return out

    def func_names(self):
        """Names of every def that executes at module level (including inside if/for/try)."""
        names = []

        def walk(stmts):
            for st in stmts:
                if isinstance(st, ast.FunctionDef):
                    if st.name not in names:
                        names.append(st.name)
                elif isinstance(st, (ast.If, ast.For, ast.While)):
                    walk(st.body)
                    walk(st.orelse)
                elif isinstance(st, ast.Try):
                    walk(st.body)
                    for h in st.handlers:
                        walk(h.body)
                    walk(st.orelse)
                    walk(st.finalbody)
                elif isinstance(st, ast.With):
                    walk(st.body)
        walk(self.tree.body)
        return names


MAIN_ACTIVITY = """package apple.gen;

import android.app.Activity;
import android.os.Bundle;
import apple.rt.*;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean ov = false;
        try {
            Py.FPPE = @FPPE@;
            Ext.scriptName = Meta.NAME;
            Ext.source = Meta.SRC;
            Ext.setLineMap(Meta.lm());
            Ui.versionLabel = @VLABEL@;
            Ui.builtWith = @BUILT@;
            Ui.errorToast = @ETOAST@;
            Ui.windowMemory = @WMEM@;
            Debug.on = @DEBUG@;
            Ui.ovSpec(@DPSPEC@);
            @SAVING@
            ov = Ui.start(this, @DPOOA@, @DRAG@);
            @AUDIO@
            Ui.run(new Fn() {
                public Object call(Object... x) { Script.main(); return null; }
            });
        } catch (Throwable t) {
            Ui.fatal(t);
        }
        if (ov) finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Ui.onPause();
        if (!Ui.isOverlay()) Audio.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Audio.resume();
    }

    @Override
    public void onBackPressed() {
        if (!Ui.back()) super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && !Ui.isOverlay()) {
            Audio.stop();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
"""

PICK_ACTIVITY = """package apple.gen;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import apple.rt.Ui;

/** Hosts the system file picker (kept separate so it also works for #use: dpooa apps). */
public class PickActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { finish(); return; }
        try {
            startActivityForResult(Ui.pickIntent(), 7001);
        } catch (Throwable t) {
            Ui.pickResult(0, null);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        Ui.pickResult(res, data);
        finish();
    }
}
"""


def meta_java(name, source_lines, linemap):
    csv = ",".join("" if v == 0 else str(v) for v in linemap)
    chunks = [csv[k:k + 20000] for k in range(0, len(csv), 20000)] or [""]
    out = ["package apple.gen;", "", "/** Python line numbers and source for tracebacks. */", "public final class Meta {",
           "    private Meta() {}", "    static final String NAME = %s;" % jstr(name), "    static final String[] LMC = {"]
    out += ["        %s," % jstr(c) for c in chunks]
    out += ["    };", "    static String lm() { StringBuilder sb = new StringBuilder(); for (String c : LMC) sb.append(c); return sb.toString(); }"]
    if len(source_lines) <= 4000:
        out.append("    static final String[] SRC = {")
        out += ["        %s," % jstr(l[:300]) for l in source_lines]
        out.append("    };")
    else:
        out.append("    static final String[] SRC = {};")
    out += ["}", ""]
    return "\n".join(out)


def manifest(pkg, label, features=()):
    dp = "dpooa" in features
    # every app has INTERNET: the Request page sends requests (the demo is bundled in the app, not downloaded)
    perms = '    <uses-permission android:name="android.permission.INTERNET" />\n'
    perms += '    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n' if dp else ""
    tc = "termux-connect" in features     # the termux module: run commands in Termux (RUN_COMMAND) and get the result back
    if tc:
        perms += '    <uses-permission android:name="com.termux.permission.RUN_COMMAND" />\n'
        perms += '    <queries>\n        <package android:name="com.termux" />\n    </queries>\n'
    receiver = '        <receiver\n            android:name="apple.rt.TermuxResult"\n            android:exported="false" />\n' if tc else ""
    if "net" in features:
        app_attrs = '\n        android:usesCleartextTraffic="true"'
    else:
        app_attrs = ""
    main_attrs = ""
    if dp:
        main_attrs = ('\n            android:theme="@android:style/Theme.Translucent.NoTitleBar"'
                      '\n            android:excludeFromRecents="true"')
    return """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package=%s
    android:versionCode="1"
    android:versionName="1.0">
%s    <application
        android:label=%s
        android:allowBackup="false"%s
        android:supportsRtl="true">
        <activity
            android:name="apple.gen.MainActivity"
            android:exported="true"%s
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|keyboard|smallestScreenSize|density|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity
            android:name="apple.gen.PickActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true" />
        <activity
            android:name="apple.rt.About"
            android:exported="false"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|keyboard|smallestScreenSize|density|uiMode"
            android:theme="@android:style/Theme.DeviceDefault.NoActionBar" />
        <activity
            android:name="apple.rt.Request"
            android:exported="false"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|keyboard|smallestScreenSize|density|uiMode"
            android:windowSoftInputMode="adjustResize"
            android:theme="@android:style/Theme.DeviceDefault.NoActionBar" />
%s    </application>
</manifest>
""" % (quoteattr(pkg), perms, quoteattr(label), app_attrs, main_attrs, receiver)


# ------------------------------------------------------------------------- demo edition

def demo_check(source, tree):
    """The PocketTk Demo only builds small, plain scripts. Returns the list of reasons this one is refused."""
    errs = []
    n = len(source.splitlines())
    if n > DEMO_MAX_LINES:
        errs.append("the script has %d lines; the demo builds scripts of up to %d lines" % (n, DEMO_MAX_LINES))
    for node in ast.walk(tree):
        line = getattr(node, "lineno", 0)
        mods = []
        if isinstance(node, ast.Import):
            mods = [al.name.split(".")[0] for al in node.names]
        elif isinstance(node, ast.ImportFrom) and node.module and not node.level:
            mods = [node.module.split(".")[0]]
        for m in mods:
            if m in DEMO_BLOCKED_MODULES:
                errs.append("line %d: the module '%s' (network, files, threads, databases) is not in the demo" % (line, m))
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "open":
            errs.append("line %d: open() (reading and writing files) is not in the demo" % line)
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load) and node.id in DEMO_BLOCKED_WIDGETS:
            errs.append("line %d: the widget %s is not in the demo" % (line, node.id))
        if isinstance(node, ast.Attribute) and isinstance(node.ctx, ast.Load) and node.attr in DEMO_BLOCKED_WIDGETS:
            errs.append("line %d: the widget %s is not in the demo" % (line, node.attr))
    seen, out = set(), []
    for e in errs:                                     # each reason once
        if e not in seen:
            seen.add(e)
            out.append(e)
    return out


# ------------------------------------------------------------------------- commands

def cmd_gen(a):
    try:
        with open(a.script, "r", encoding="utf-8") as f:
            source = f.read()
        parse_text, n_marks = mark_datareset(source)
        tree = ast.parse(parse_text, a.script)
    except SyntaxError as e:
        sys.stderr.write("Syntax error in %s, line %s: %s\n" % (a.script, e.lineno, e.msg))
        return 2
    except OSError as e:
        sys.stderr.write("Cannot read %s: %s\n" % (a.script, e))
        return 2
    global SCRIPT_DIR
    SCRIPT_DIR = os.path.dirname(os.path.abspath(a.script))
    IMAGE_FILES.clear()
    audio_items = []
    off = set()
    feats, opts, dwarn = parse_directives(source, audio_items, off)
    if DEMO:
        problems = demo_check(source, tree)
        if problems:
            for e in problems:
                sys.stderr.write("demo limit: %s\n" % e)
            sys.stderr.write("The full PocketTk has no such limits and is open source: github.com/iamssdes/PocketTk\n")
            return 2
        for f in sorted(feats - DEMO_FEATURES):
            if f != "builtin-editing":
                dwarn.append("#use: %s is not in the demo - ignored" % f)
        if off or "builtin-editing" in feats:
            dwarn.append("#builtins: is not in the demo - ignored (the version label always shows)")
        feats = feats & DEMO_FEATURES
        off.clear()
        opts.clear()
    marks = DataResetMarks("datasaving" in feats)
    if n_marks:
        tree = marks.visit(tree)
        dwarn += marks.warns
    tree.body = lower_decorators(tree.body)
    flat = ClassFlattener()
    tree = flat.flatten(tree)
    ast.fix_missing_locations(tree)
    g = Gen(tree, flat)
    g.features = feats
    g.off = off
    java = g.generate()
    if any(x.split(".")[0] == "termux" for x in list(g.alias.values()) + list(g.from_names.values())) and "termux-connect" not in feats:
        sys.stderr.write("error: the script imports termux, which needs  #use: termux-connect  in the comments at the top of the script\n")
        return 2
    atracks, aloop = [], True
    if "audio" in feats:
        atracks, aloop, awarn = prepare_audio(audio_items, a.script, a.outdir, a.audio_cache, fetch=not a.no_assets)
        dwarn += awarn
        if not audio_items:
            dwarn.append("#use: audio but no #audio: or #playlist: line (e.g.  #audio: dQw4w9WgXcQ )")
        elif not atracks:
            dwarn.append("no audio could be added, the app will be silent")
    if IMAGE_FILES and not a.no_assets:
        idir = os.path.join(a.outdir, "assets", "img")
        os.makedirs(idir, exist_ok=True)
        for name, src in IMAGE_FILES.items():
            shutil.copyfile(src, os.path.join(a.outdir, "assets", name))
    gen_dir = os.path.join(a.outdir, "src", "apple", "gen")
    os.makedirs(gen_dir, exist_ok=True)
    with open(os.path.join(gen_dir, "Script.java"), "w", encoding="utf-8") as f:
        f.write(java)
    act = (MAIN_ACTIVITY.replace("@FPPE@", "true" if "fppe" in feats else "false")
           .replace("@VLABEL@", "false" if "version-label" in off else "true")
           .replace("@BUILT@", jstr(a.apple))
           .replace("@ETOAST@", "false" if "error-toast" in off else "true")
           .replace("@WMEM@", "true" if ("dpooa" in feats and "datasaving" in feats and "window-memory" not in off) else "false")
           .replace("@DEBUG@", "true" if a.debug else "false")
           .replace("@DPOOA@", "true" if "dpooa" in feats else "false")
           .replace("@DRAG@", "true" if "drag" in opts else "false")
           .replace("@DPSPEC@", json.dumps(dpooa_spec(opts)))
           .replace("@SAVING@", "Ext.enableDataSaving(getFilesDir(), Script.SNAP);" if "datasaving" in feats else "")
           .replace("@AUDIO@", "Audio.start(this, AudioCfg.TRACKS, AudioCfg.LOOP);" if atracks else ""))
    with open(os.path.join(gen_dir, "MainActivity.java"), "w", encoding="utf-8") as f:
        f.write(act)
    with open(os.path.join(gen_dir, "PickActivity.java"), "w", encoding="utf-8") as f:
        f.write(PICK_ACTIVITY)
    if atracks:
        with open(os.path.join(gen_dir, "AudioCfg.java"), "w", encoding="utf-8") as f:
            f.write(audio_cfg_java(atracks, aloop))
    with open(os.path.join(gen_dir, "Meta.java"), "w", encoding="utf-8") as f:
        f.write(meta_java(os.path.basename(a.script), source.splitlines(), g.linemap))
    with open(os.path.join(a.outdir, "AndroidManifest.xml"), "w", encoding="utf-8") as f:
        used = set(g.alias.values()) | set(g.from_names.values())
        net = any(x.split(".")[0] in NET_MODULES for x in used)
        f.write(manifest(a.pkg, a.label, set(feats) | ({"net"} if net else set())))
    for w in dwarn + g.warnings:
        sys.stderr.write("warning: %s\n" % w)
    if feats:
        sys.stderr.write("note: using %s%s\n" % (", ".join(sorted(feats - {"builtin-editing"})), (" (%s)" % dpooa_spec(opts)) if opts and "dpooa" in feats else ""))
    uses_tk = g.star or any(v.startswith("tkinter") for v in list(g.alias.values()) + list(g.from_names.values()))
    if not uses_tk:
        sys.stderr.write("warning: this script does not import tkinter; building it anyway\n")
    return 0


def cmd_aabpack(a):
    """Turn an `aapt2 link --proto-format` APK into the base module zip bundletool expects:
    AndroidManifest.xml -> manifest/AndroidManifest.xml, resources.pb and res/, assets/ kept,
    dex files under dex/ (classes.dex, classes2.dex ...)."""
    zin = zipfile.ZipFile(a.base)
    names = zin.namelist()
    if "resources.pb" not in names or "AndroidManifest.xml" not in names:
        sys.stderr.write("aabpack: %s is not a proto-format APK (no resources.pb); was aapt2 run with --proto-format?\n" % a.base)
        return 2
    entries = []
    for info in zin.infolist():
        fn = info.filename
        if fn.endswith("/") or fn.startswith("META-INF/"):
            continue
        if fn.startswith("classes") and fn.endswith(".dex") and "/" not in fn:
            continue
        data = zin.read(fn)
        if fn == "AndroidManifest.xml":
            entries.append(("manifest/AndroidManifest.xml", data))
        elif fn == "resources.pb" or fn.startswith("res/") or fn.startswith("assets/") or fn.startswith("lib/") or fn.startswith("root/"):
            entries.append((fn, data))
        else:
            entries.append(("root/" + fn, data))
    with open(a.dex, "rb") as f:
        entries.append(("dex/classes.dex", f.read()))
    for i, path in enumerate(a.extra_dex, start=2):
        with open(path, "rb") as f:
            entries.append(("dex/classes%d.dex" % i, f.read()))
    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries:
            zi = zipfile.ZipInfo(name, date_time=(2009, 1, 1, 0, 0, 0))
            zi.external_attr = 0o644 << 16
            zi.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(zi, data)
    return 0


def cmd_pack(a):
    """Add classes.dex (plus classes2.dex ... for each --extra-dex) to the aapt2 output and write a
    4-byte-aligned zip (ready for apksigner)."""
    zin = zipfile.ZipFile(a.base)
    entries = []
    for info in zin.infolist():
        fn = info.filename
        if fn.endswith("/") or (fn.startswith("classes") and fn.endswith(".dex") and "/" not in fn):
            continue
        entries.append((fn, zin.read(fn)))
    with open(a.dex, "rb") as f:
        entries.append(("classes.dex", f.read()))
    for i, path in enumerate(a.extra_dex, start=2):
        with open(path, "rb") as f:
            entries.append(("classes%d.dex" % i, f.read()))
    with open(a.out, "wb") as fp:
        zf = zipfile.ZipFile(fp, "w")
        for name, data in entries:
            zi = zipfile.ZipInfo(name, date_time=(2009, 1, 1, 0, 0, 0))
            zi.external_attr = 0o644 << 16
            if name == "resources.arsc" or name.endswith(".so"):
                zi.compress_type = zipfile.ZIP_STORED
                off = fp.tell() + 30 + len(name.encode("utf-8"))
                pad = (-off) % 4
                if pad:
                    n = pad + 8
                    zi.extra = struct.pack("<HHH", 0xD935, n - 4, 4) + b"\0" * (n - 6)
            else:
                zi.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(zi, data)
        zf.close()
    return 0


def patch_version_code(axml, code):
    """Set versionCode in a binary AndroidManifest.xml (aapt2 output). Returns the new bytes."""
    b = bytearray(axml)
    if len(b) < 8 or struct.unpack_from("<H", b, 0)[0] != 0x0003:
        raise ValueError("not a binary AndroidManifest.xml")
    VERSION_CODE_RES = 0x0101021B
    pos = struct.unpack_from("<H", b, 2)[0]            # first chunk after the file header
    end = min(len(b), struct.unpack_from("<I", b, 4)[0])
    name_idx = None
    while pos + 8 <= end:
        ctype, hsize, csize = struct.unpack_from("<HHI", b, pos)
        if csize < 8:
            break
        if ctype == 0x0180:                              # resource map: string index -> attribute resource id
            ids = struct.unpack_from("<%dI" % ((csize - hsize) // 4), b, pos + hsize)
            if VERSION_CODE_RES in ids:
                name_idx = ids.index(VERSION_CODE_RES)
        elif ctype == 0x0102:                            # first start tag = <manifest>
            if name_idx is None:
                break
            ext = pos + hsize
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", b, ext + 8)
            for i in range(attr_count):
                a = ext + attr_start + i * attr_size
                _ns, name, _raw, _vsize, _res0, dtype, _data = struct.unpack_from("<IIIHBBI", b, a)
                if name == name_idx:
                    if dtype != 0x10:
                        raise ValueError("versionCode is not a plain integer")
                    struct.pack_into("<I", b, a + 16, code)
                    return bytes(b)
            break
        pos += csize
    raise ValueError("versionCode not found in the manifest")


def write_aligned_zip(entries, out):
    """Same zip layout as cmd_pack: resources.arsc / .so stored and 4-byte aligned."""
    with open(out, "wb") as fp:
        zf = zipfile.ZipFile(fp, "w")
        for name, data in entries:
            zi = zipfile.ZipInfo(name, date_time=(2009, 1, 1, 0, 0, 0))
            zi.external_attr = 0o644 << 16
            if name == "resources.arsc" or name.endswith(".so"):
                zi.compress_type = zipfile.ZIP_STORED
                off = fp.tell() + 30 + len(name.encode("utf-8"))
                pad = (-off) % 4
                if pad:
                    n = pad + 8
                    zi.extra = struct.pack("<HHH", 0xD935, n - 4, 4) + b"\0" * (n - 6)
            else:
                zi.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(zi, data)
        zf.close()


def cmd_setvc(a):
    """Copy an installed APK with a higher versionCode (old signature dropped; sign the result afterwards).
    Used by `apkapple update --debug` so the previous version can be installed over the debug build."""
    try:
        zin = zipfile.ZipFile(a.apk)
        entries = []
        for info in zin.infolist():
            n = info.filename
            if n.endswith("/") or n.startswith("META-INF/"):
                continue
            data = zin.read(n)
            if n == "AndroidManifest.xml":
                data = patch_version_code(data, a.code)
            entries.append((n, data))
        if not any(n == "AndroidManifest.xml" for n, _ in entries):
            raise ValueError("no AndroidManifest.xml in the APK")
        write_aligned_zip(entries, a.out)
    except (OSError, ValueError, zipfile.BadZipFile) as e:
        sys.stderr.write("setvc: %s\n" % e)
        return 2
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(prog="transpile.py")
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("gen")
    g.add_argument("script")
    g.add_argument("outdir")
    g.add_argument("--pkg", default="pockettk.ssdes")
    g.add_argument("--label", default="Apple App")
    g.add_argument("--audio-cache", default=os.path.join(os.path.expanduser("~"), ".pockettk", "audio-cache"))
    g.add_argument("--no-assets", action="store_true", help="do not fetch/bundle #audio: tracks (apkapple check)")
    g.add_argument("--debug", action="store_true", help="show a log panel next to the app")
    g.add_argument("--apple", default="", help="PocketTk version shown in the version label (Built With ...)")
    g.set_defaults(fn=cmd_gen)
    v = sub.add_parser("setvc")
    v.add_argument("apk")
    v.add_argument("out")
    v.add_argument("--code", type=int, required=True)
    v.set_defaults(fn=cmd_setvc)
    p = sub.add_parser("pack")
    p.add_argument("base")
    p.add_argument("dex")
    p.add_argument("out")
    p.add_argument("--extra-dex", action="append", default=[], help="more dex files, stored as classes2.dex, classes3.dex ...")
    p.set_defaults(fn=cmd_pack)
    q = sub.add_parser("aabpack")
    q.add_argument("base")
    q.add_argument("dex")
    q.add_argument("out")
    q.add_argument("--extra-dex", action="append", default=[])
    q.set_defaults(fn=cmd_aabpack)
    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
