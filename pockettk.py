#!/usr/bin/env python3
"""PocketTk for Windows, Linux and Termux (no root needed).  Python 3.8+, standard library only.

    python pockettk.py doctor
    python pockettk.py build app.py [--id NAME] [-n "App name"] [--icon pic.png] [-i]
    python pockettk.py run app.py [--id NAME] [--preview]
    python pockettk.py update --id NAME [app.py] [--preview]
    python pockettk.py preview app.py [--width 360 --height 800 --dpi 420 --cpu 100]

It uses the same transpile.py and rt/ folder as the rooted phone version, so apps behave the same.
Installing on a phone goes through adb (USB debugging or Wireless debugging); no root is involved.
"""
import os, re, sys, shutil, subprocess, tempfile, zlib, glob, time, json, platform, argparse

HERE = os.path.dirname(os.path.abspath(__file__))
VERSION = "PocketTk v4.1.2"
PKG_PREFIX = "pockettk.ssdes."
LEGACY_PREFIXES = ("tkdroid.ssdes.", "tkdroid.palalgon.", "tkdroid.molt.", "apple.app.")
WIN = os.name == "nt"
HOME = os.path.expanduser("~")
for _k, _v in list(os.environ.items()):          # the old TKDROID_* variables still work
    if _k.startswith("TKDROID_") and "POCKETTK_" + _k[8:] not in os.environ:
        os.environ["POCKETTK_" + _k[8:]] = _v
KS = os.environ.get("POCKETTK_HOME") or os.path.join(HOME, ".pockettk")
if not os.environ.get("POCKETTK_HOME"):          # TkDroid kept its data in ~/.tkdroid (signing key, ids): move it
    for _old in (os.path.join(HOME, ".tkdroid"), os.path.join(HOME, ".apple")):
        if os.path.isdir(_old) and not os.path.exists(KS):
            try:
                shutil.move(_old, KS)
                print("- moved %s -> %s" % (_old, KS))
            except OSError:
                pass
WORK = os.path.join(KS, "work")
EXE = ".exe" if WIN else ""
BAT = ".bat" if WIN else ""
CPSEP = os.pathsep


class Fail(Exception):
    pass


def die(msg):
    raise Fail(msg)


def say(msg):
    print("- " + msg, flush=True)


def safe_name(s):
    s = re.sub(r"[^a-z0-9]", "_", s.lower())
    return s.strip("_")


# ---------------------------------------------------------------- output folder
def out_dir():
    env = os.environ.get("POCKETTK_OUT")
    if env:
        return env
    if os.path.isdir("/data/data/com.termux") or os.environ.get("PREFIX", "").startswith("/data/data/com.termux"):
        d = "/sdcard/Download/PocketTk"
        try:
            os.makedirs(d, exist_ok=True)
            if os.access(d, os.W_OK):
                return d
        except OSError:
            pass
        return os.path.join(HOME, "pockettk")
    return os.path.join(HOME, "PocketTk")


# ---------------------------------------------------------------- tool discovery
def _newest(paths):
    def key(p):
        nums = re.findall(r"\d+", os.path.basename(os.path.dirname(p)) if os.path.basename(p).startswith(("aapt", "d8", "apksigner", "zipalign")) else os.path.basename(p))
        return [int(n) for n in nums] or [0]
    return sorted(paths, key=key)[-1] if paths else ""


def sdk_roots():
    r = []
    for v in ("POCKETTK_SDK", "APPLE_SDK", "ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(v):
            r.append(os.environ[v])
    r += [os.path.join(KS, "sdk"), os.path.join(HOME, "android-sdk"), os.path.join(HOME, "Android", "Sdk"),
          os.path.join(HOME, "Library", "Android", "sdk")]
    if WIN and os.environ.get("LOCALAPPDATA"):
        r.append(os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk"))
    pre = os.environ.get("PREFIX")
    if pre:
        r.append(os.path.join(pre, "share", "android-sdk"))
    return [d for d in r if os.path.isdir(d)]


def local_jdks():
    """JDKs PocketTk downloaded itself (the window app does that on its first run) into ~/.pockettk/jdk."""
    out = []
    for d in sorted(glob.glob(os.path.join(KS, "jdk", "*")), reverse=True):
        for cand in (os.path.join(d, "Contents", "Home"), d):
            if os.path.isfile(os.path.join(cand, "bin", "javac" + EXE)):
                out.append(cand)
                break
    return out


def export_java_home():
    """The SDK's d8 and apksigner scripts find Java through JAVA_HOME or PATH. Point both at the JDK we found,
    so a JDK that PocketTk downloaded itself (and that is on no PATH) works too. Returns the JDK home or ""."""
    javac = find_tool("javac")
    if not javac:
        return ""
    home = os.path.dirname(os.path.dirname(os.path.realpath(javac)))
    cur = os.environ.get("JAVA_HOME", "")
    if not (cur and os.path.isfile(os.path.join(cur, "bin", "javac" + EXE))):
        os.environ["JAVA_HOME"] = home
    jb = os.path.join(os.environ["JAVA_HOME"], "bin")
    if jb not in os.environ.get("PATH", "").split(os.pathsep):
        os.environ["PATH"] = jb + os.pathsep + os.environ.get("PATH", "")
    return os.environ["JAVA_HOME"]


def java_homes():
    """Where a JDK may live: JAVA_HOME, and on a Mac the places Homebrew and the system put it (a Mac's /usr/bin/java
    is only a stub that fails when no JDK is installed, so it must not be picked blindly)."""
    r = []
    if os.environ.get("JAVA_HOME"):
        r.append(os.environ["JAVA_HOME"])
    r += local_jdks()
    if platform.system() == "Darwin":
        try:
            o = subprocess.run(["/usr/libexec/java_home"], capture_output=True, text=True, timeout=10)
            if o.returncode == 0 and o.stdout.strip():
                r.append(o.stdout.strip())
        except (OSError, subprocess.SubprocessError):
            pass
        r += ["/opt/homebrew/opt/openjdk", "/usr/local/opt/openjdk", "/opt/homebrew/opt/openjdk@21", "/opt/homebrew/opt/openjdk@17"]
        r += sorted(glob.glob("/Library/Java/JavaVirtualMachines/*/Contents/Home"), reverse=True)
    return [d for d in r if os.path.isdir(d)]


def find_tool(name, ext=None):
    """name = aapt2 | d8 | apksigner | zipalign | adb | java | javac | keytool | jarsigner"""
    ext = EXE if ext is None else ext
    for root in sdk_roots():
        for pat in ("build-tools/*/%s" % (name + (BAT if name in ("d8", "apksigner") else ext)),
                    "build-tools/*/%s" % (name + ext),
                    "platform-tools/%s" % (name + ext)):
            hit = _newest(glob.glob(os.path.join(root, pat)))
            if hit:
                return hit
    if name in ("java", "javac", "keytool", "jarsigner"):
        for jh in java_homes():
            if os.path.isfile(os.path.join(jh, "bin", name + EXE)):
                return os.path.join(jh, "bin", name + EXE)
    for n in (name + EXE, name + BAT, name):
        p = shutil.which(n)
        if p:
            return p
    return ""


def find_lib(name):
    for root in sdk_roots():
        hit = _newest(glob.glob(os.path.join(root, "build-tools", "*", "lib", name)))
        if hit:
            return hit
    return ""


def find_android_jar():
    for p in (os.environ.get("POCKETTK_ANDROID_JAR"), os.path.join(KS, "android.jar"), os.path.join(HOME, "android.jar"),
              os.path.join(os.getcwd(), "android.jar")):
        if p and os.path.isfile(p):
            return p
    for root in sdk_roots():
        hit = _newest(glob.glob(os.path.join(root, "platforms", "android-*", "android.jar")))
        if hit:
            return hit
    return ""


def python_exe():
    return sys.executable or shutil.which("python3") or shutil.which("python")


def run(cmd, log=None, check=True, **kw):
    """Run a command (list). A .bat is run through cmd on Windows. Raises Fail with the exact command on error."""
    cmd = [str(c) for c in cmd]
    if WIN and cmd[0].lower().endswith((".bat", ".cmd")):
        cmd = ["cmd", "/c"] + cmd
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True,
                           encoding="utf-8", errors="replace", **kw)
    except OSError as e:
        die("could not run %s (%s)" % (cmd[0], e))
    if log:
        with open(log, "w", encoding="utf-8") as f:
            f.write(p.stdout)
    if check and p.returncode != 0:
        sys.stdout.write(p.stdout)
        die("command failed (exit %d): %s" % (p.returncode, " ".join(cmd)))
    return p


# ---------------------------------------------------------------- toolchain bundle
class Tools:
    def __init__(self, need_sign=True):
        self.py = python_exe() or die("python not found")
        self.javac = find_tool("javac") or die("javac not found: install a JDK 17+ (run: pockettk doctor)")
        self.java = find_tool("java") or die("java not found: install a JDK 17+ (run: pockettk doctor)")
        self.keytool = find_tool("keytool") or die("keytool not found: install a JDK 17+ (run: pockettk doctor)")
        self.aapt2 = find_tool("aapt2") or die("aapt2 not found: run: pockettk setup  (or: pockettk doctor)")
        self.ajar = find_android_jar() or die("android.jar not found: run: pockettk setup  (or: pockettk doctor)")
        self.d8 = find_tool("d8")
        self.d8jar = "" if self.d8 else find_lib("d8.jar")
        if not (self.d8 or self.d8jar):
            die("d8 not found: run: pockettk setup  (or: pockettk doctor)")
        self.signer = find_tool("apksigner")
        self.signjar = "" if self.signer else find_lib("apksigner.jar")
        if need_sign and not (self.signer or self.signjar):
            die("apksigner not found: run: pockettk setup  (or: pockettk doctor)")
        export_java_home()


# ---------------------------------------------------------------- runtime (rt/*.java) compiled once
def rt_sources(rt_dir=None):
    return sorted(glob.glob(os.path.join(rt_dir or os.path.join(HERE, "rt"), "*.java")))


def ensure_rt(t):
    srcs = rt_sources()
    crc = 0
    for s in srcs:
        with open(s, "rb") as f:
            crc = zlib.crc32(f.read(), crc)
    key = "%08x-%d-%s" % (crc, os.path.getsize(t.ajar), "d8")
    base = os.path.join(KS, "rt")
    rtc = os.path.join(base, key)
    classes, dex = os.path.join(rtc, "classes"), os.path.join(rtc, "rt.dex")
    if os.path.isfile(dex) and os.path.isdir(classes):
        return classes, dex
    say("preparing the runtime (one time, every app reuses it)")
    os.makedirs(base, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix="tmp.", dir=base)
    try:
        cl = os.path.join(tmp, "classes"); os.makedirs(cl)
        compile_java(t, cl, t.ajar, srcs, os.path.join(tmp, "javac.log"))
        dexd = os.path.join(tmp, "dex"); os.makedirs(dexd)
        dex_classes(t, cl, dexd, None)
        shutil.move(os.path.join(dexd, "classes.dex"), os.path.join(tmp, "rt.dex"))
        shutil.rmtree(dexd, ignore_errors=True)
        if os.path.isdir(rtc):
            shutil.rmtree(rtc, ignore_errors=True)
        os.rename(tmp, rtc)
    except BaseException:
        shutil.rmtree(tmp, ignore_errors=True)
        raise
    for d in glob.glob(os.path.join(base, "*")):          # runtimes of older versions
        if os.path.abspath(d) != os.path.abspath(rtc) and not os.path.basename(d).startswith("tmp."):
            shutil.rmtree(d, ignore_errors=True)
    return classes, dex


def compile_java(t, outdir, classpath, sources, log):
    lst = log + ".list"
    with open(lst, "w", encoding="utf-8") as f:
        for s in sources:
            f.write('"%s"\n' % s.replace("\\", "/"))
    run([t.javac, "-nowarn", "-Xlint:none", "--release", "8", "-encoding", "UTF-8", "-cp", classpath,
         "-d", outdir, "@" + lst], log=log)


def class_files(d):
    out = []
    for r, _, fs in os.walk(d):
        out += [os.path.join(r, f) for f in fs if f.endswith(".class")]
    return out


def dex_classes(t, classdir, outdir, rt_classes):
    files = class_files(classdir)
    if not files:
        die("no classes were produced")
    base = ["--release", "--min-api", "26", "--lib", t.ajar]
    tries = [["--classpath", rt_classes], []] if rt_classes else [[]]
    inputs, jar = files, None
    if sum(len(f) + 3 for f in files) > 4000:
        # d8 is a .bat file on Windows and cmd.exe stops at about 8000 characters ("The command line is too long."): the
        # runtime alone is hundreds of classes, so hand d8 one jar (a zip of the same class files) instead of every file
        import zipfile
        jar = os.path.normpath(classdir) + ".dex-input.jar"
        root = os.path.normpath(classdir)
        with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
            for f in files:
                z.write(f, os.path.relpath(f, root).replace("\\", "/"))
        inputs = [jar]
    last = None
    try:
        for extra in tries:
            if t.d8:
                cmd = [t.d8] + base + extra + ["--output", outdir] + inputs
            else:
                cmd = [t.java, "-cp", t.d8jar, "com.android.tools.r8.D8"] + base + extra + ["--output", outdir] + inputs
            p = run(cmd, check=False)
            if p.returncode == 0:
                return
            last = (cmd, p.stdout)
    finally:
        if jar and os.path.exists(jar):
            os.remove(jar)
    sys.stdout.write(last[1])
    die("dexing failed: " + " ".join(last[0][:3]) + " ...")


# ---------------------------------------------------------------- signing
def ensure_key(t):
    ks = os.path.join(KS, "apple.keystore")        # same file name as the phone version: keys can be copied between them
    if not os.path.isfile(ks):
        say("creating signing key (one time)")
        os.makedirs(KS, exist_ok=True)
        run([t.keytool, "-genkeypair", "-keystore", ks, "-storepass", "android", "-keypass", "android", "-alias", "apple",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "36500", "-dname", "CN=PocketTk"])
    return ks


def sign_apk(t, src, dst):
    ks = ensure_key(t)
    args = ["sign", "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", dst, src]
    if t.signer:
        run([t.signer] + args)
    else:
        run([t.java, "-jar", t.signjar] + args)
    if os.path.exists(dst + ".idsig"):
        os.remove(dst + ".idsig")


# ---------------------------------------------------------------- app ids (same file format as the phone version)
def read_id(i):
    p = os.path.join(KS, "ids", i)
    if not os.path.isfile(p):
        return None
    d = {}
    for line in open(p, encoding="utf-8"):
        if "=" in line:
            k, v = line.rstrip("\n").split("=", 1)
            d[k] = v
    return d


def write_id(i, d):
    os.makedirs(os.path.join(KS, "ids"), exist_ok=True)
    order = ["pkg", "vc", "name", "src", "icon", "ver", "patch", "apple"]
    with open(os.path.join(KS, "ids", i), "w", encoding="utf-8", newline="\n") as f:
        for k in order:
            f.write("%s=%s\n" % (k, d.get(k, "")))


def legacy_suffix(pkg):
    for p in LEGACY_PREFIXES:
        if pkg.startswith(p):
            return pkg[len(p):]
    return None


# ---------------------------------------------------------------- build
def sub_manifest(path, vc, vname, icon):
    s = open(path, encoding="utf-8").read()
    if icon:
        s = s.replace('android:allowBackup="false"', 'android:icon="@mipmap/ic_launcher" android:allowBackup="false"')
    s = re.sub(r'android:versionCode="[^"]*"', 'android:versionCode="%s"' % vc, s)
    s = re.sub(r'android:versionName="[^"]*"', 'android:versionName="%s"' % vname, s)
    if 'android:versionCode="%s"' % vc not in s:
        die("version code was not applied to the manifest")
    open(path, "w", encoding="utf-8", newline="\n").write(s)


def find_bundletool():
    for p in (os.environ.get("BUNDLETOOL"), os.path.join(KS, "bundletool.jar"), os.path.join(HOME, "bundletool.jar")):
        if p and os.path.isfile(p):
            return p
    for pat in (os.path.join(HOME, "bundletool*.jar"), os.path.join(HOME, "Downloads", "bundletool*.jar"),
                os.path.join(HOME, "bundletool", "*.jar")):
        hit = _newest(glob.glob(pat))
        if hit:
            return hit
    return ""


def sign_aab(t, aab, keystore=None, alias=None):
    """jarsigner with the upload key (Google Play re-signs the app with its own key). Without a keystore the key
    in ~/.pockettk/upload.keystore is used: made on first use, random password kept in upload.pass."""
    js = find_tool("jarsigner") or die("jarsigner not found: it comes with the JDK (run: pockettk doctor)")
    if not keystore:
        ks, alias, pf = os.path.join(KS, "upload.keystore"), "upload", os.path.join(KS, "upload.pass")
        if not os.path.isfile(ks):
            say("creating your upload key (one time): " + ks)
            os.makedirs(KS, exist_ok=True)
            import secrets, string
            pw = "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(24))
            run([t.keytool, "-genkeypair", "-keystore", ks, "-storepass", pw, "-keypass", pw, "-alias", "upload",
                 "-keyalg", "RSA", "-keysize", "2048", "-validity", "36500", "-dname", "CN=PocketTk upload key"])
            fd = os.open(pf, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write(pw)
            say("BACK UP both files, you need the same key for every future upload:")
            say("  " + ks)
            say("  " + pf)
        if not os.path.isfile(pf):
            die("%s is missing (the password of %s)" % (pf, ks))
        pw = open(pf).read().strip()
    else:
        ks = keystore
        if not os.path.isfile(ks):
            die("no such keystore: %s" % ks)
        pw = os.environ.get("POCKETTK_KS_PASS")
        if not pw:
            import getpass
            pw = getpass.getpass("password of %s: " % ks)
        if not alias:
            p = run([t.keytool, "-list", "-keystore", ks, "-storepass", pw], check=False)
            m = re.search(r"^([^,\n]+), .*PrivateKeyEntry", p.stdout, re.M)
            if not m:
                die("could not find a key alias in %s (use --ks-alias NAME)" % ks)
            alias = m.group(1).strip()
    run([js, "-keystore", ks, "-storepass", pw, "-keypass", pw, "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", aab, alias])
    p = run([t.keytool, "-list", "-keystore", ks, "-storepass", pw, "-alias", alias], check=False)
    m = re.search(r"SHA-?256[^:]*:\s*([0-9A-F:]+)", p.stdout)
    if m:
        say("upload key SHA-256: " + m.group(1))


def build(src, name=None, pkg=None, out=None, icon=None, ident=None, bump="patch", debug=False, keep=False,
          target_sdk=None, pkg_override=None, aab=False, ks=None, ks_alias=None):
    if not os.path.isfile(src):
        die("no such file: %s" % src)
    if aab and debug:
        die("--debug builds cannot be .aab files")
    target_sdk = target_sdk or ("36" if aab else "34")
    if icon:
        if not os.path.isfile(icon):
            die("no such icon file: %s" % icon)
        iext = os.path.splitext(icon)[1].lower().lstrip(".")
        if iext == "jpeg":
            iext = "jpg"
        if iext not in ("png", "webp", "jpg"):
            die("icon must be .png, .webp or .jpg")
    base = os.path.splitext(os.path.basename(src))[0]
    sn = safe_name(base) or "app"
    if sn[0].isdigit():
        sn = "a" + sn
    name = name or base
    vc, A, B, C, P = 1, 1, 0, 0, 0
    pkg = pkg or PKG_PREFIX + sn
    rec = read_id(ident) if ident else None
    if ident and rec:
        pkg = pkg_override or rec["pkg"]
        vc = int(rec.get("vc") or 1) + 1
        oldver = rec.get("ver") or "1.0.%d" % (vc - 2)
        A, B, C = [int(x) for x in oldver.split(".")[:3]]
        P = int(rec.get("patch") or 0)
        if bump == "large":
            A, B, C, P = A + 1, 0, 0, 0
        elif bump == "medium":
            B, C, P = B + 1, 0, 0
        elif bump == "small":
            C, P = C + 1, 0
        else:
            P += 1
        if (not name or name == base):
            name = rec.get("name") or name
        if icon is None and rec.get("icon") and os.path.isfile(rec["icon"]):
            icon = rec["icon"]
            iext = os.path.splitext(icon)[1].lower().lstrip(".").replace("jpeg", "jpg")
    elif ident and pkg == PKG_PREFIX + sn:
        pkg = PKG_PREFIX + ident
    vname = "%d.%d.%d" % (A, B, C) + ("-p%d" % P if P > 0 else "")
    od = out_dir()
    out = out or os.path.join(od, sn + (".aab" if aab else ".apk"))
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)

    t = Tools(need_sign=not aab)
    bundletool = ""
    if aab:
        bundletool = find_bundletool() or die("bundletool.jar not found: run  pockettk setup  (or download bundletool-all-VERSION.jar from github.com/google/bundletool/releases to %s)" % os.path.join(KS, "bundletool.jar"))
        find_tool("jarsigner") or die("jarsigner not found: it comes with the JDK (run: pockettk doctor)")
    bd = os.path.join(WORK, "build", sn)
    shutil.rmtree(bd, ignore_errors=True)
    for d in ("classes", "dex"):
        os.makedirs(os.path.join(bd, d))
    say("transpiling %s" % src)
    gen = [t.py, "-I", os.path.join(HERE, "transpile.py"), "gen", src, bd, "--pkg", pkg, "--label", name,
           "--audio-cache", os.path.join(KS, "audio-cache"), "--apple", VERSION.replace("PocketTk v", "")]
    if debug:
        gen.append("--debug")
    p = run(gen, check=False)
    if p.returncode != 0:
        sys.stdout.write(p.stdout)
        die("transpile failed")
    for line in p.stdout.splitlines():
        if line.startswith(("note:", "audio:")):
            print(line)
    os.makedirs(KS, exist_ok=True)
    open(os.path.join(KS, "last-package"), "w").write(pkg + "\n")
    demo = os.path.join(HERE, "demo", "TkDroid_Demo.zip")
    if os.path.isfile(demo):
        os.makedirs(os.path.join(bd, "assets"), exist_ok=True)
        shutil.copyfile(demo, os.path.join(bd, "assets", "TkDroid_Demo.zip"))

    rt_classes, rt_dex = ensure_rt(t)
    say("compiling Java")
    srcs = []
    for r, _, fs in os.walk(os.path.join(bd, "src")):
        srcs += [os.path.join(r, f) for f in fs if f.endswith(".java")]
    compile_java(t, os.path.join(bd, "classes"), t.ajar + CPSEP + rt_classes, srcs, os.path.join(bd, "javac.log"))
    say("dexing")
    dex_classes(t, os.path.join(bd, "classes"), os.path.join(bd, "dex"), rt_classes)
    if not os.path.isfile(os.path.join(bd, "dex", "classes.dex")):
        die("no classes.dex produced")

    manifest = os.path.join(bd, "AndroidManifest.xml")
    resarg = []
    if icon:
        say("adding icon")
        rd = os.path.join(bd, "res", "mipmap-xxxhdpi")
        os.makedirs(rd)
        shutil.copyfile(icon, os.path.join(rd, "ic_launcher." + iext))
        p = run([t.aapt2, "compile", "--dir", os.path.join(bd, "res"), "-o", os.path.join(bd, "res.zip")], check=False)
        if p.returncode != 0:
            sys.stdout.write(p.stdout)
            die("icon rejected by aapt2 (use a valid PNG)")
        resarg = [os.path.join(bd, "res.zip")]
    sub_manifest(manifest, vc, vname, icon)
    say("linking manifest")
    link = [t.aapt2, "link"] + (["--proto-format"] if aab else []) + ["-o", os.path.join(bd, "base.apk"), "-I", t.ajar, "--manifest", manifest] + resarg
    if os.path.isdir(os.path.join(bd, "assets")):
        link += ["-A", os.path.join(bd, "assets")]
    link += ["--min-sdk-version", "26", "--target-sdk-version", str(target_sdk), "--version-code", str(vc),
             "--version-name", vname]
    run(link)
    if aab:
        say("packaging the base module")
        run([t.py, "-I", os.path.join(HERE, "transpile.py"), "aabpack", os.path.join(bd, "base.apk"),
             os.path.join(bd, "dex", "classes.dex"), os.path.join(bd, "base.zip"), "--extra-dex", rt_dex])
        say("building the bundle")
        if os.path.exists(out):
            os.remove(out)
        run([t.java, "-jar", bundletool, "build-bundle", "--modules=" + os.path.join(bd, "base.zip"), "--output=" + out])
        say("signing with the upload key")
        sign_aab(t, out, ks, ks_alias)
    else:
        say("packaging")
        run([t.py, "-I", os.path.join(HERE, "transpile.py"), "pack", os.path.join(bd, "base.apk"),
             os.path.join(bd, "dex", "classes.dex"), os.path.join(bd, "unsigned.apk"), "--extra-dex", rt_dex])
        say("signing")
        sign_apk(t, os.path.join(bd, "unsigned.apk"), out)
    if ident:
        ab = lambda p_: os.path.abspath(p_) if p_ else ""
        write_id(ident, {"pkg": pkg, "vc": vc, "name": name, "src": ab(src), "icon": ab(icon), "ver": "%d.%d.%d" % (A, B, C),
                         "patch": P, "apple": VERSION.replace("PocketTk v", "")})
        say("app id '%s' -> %s (version %d.%d.%d%s, code %d)" % (ident, pkg, A, B, C, " Patch %d" % P if P else "", vc))
    if not keep:
        shutil.rmtree(bd, ignore_errors=True)
    say("built: %s" % out)
    if aab:
        say("versionCode %d, versionName %s, target SDK %s - upload this file in Play Console (every upload needs a higher versionCode: build with --id so it counts up)" % (vc, vname, target_sdk))
    return out, pkg


# ---------------------------------------------------------------- adb (installing on a phone, no root)
def adb():
    a = find_tool("adb")
    if not a:
        die("adb not found: run: pockettk setup  (platform-tools), or install Android platform-tools")
    return a


def adb_devices():
    p = run([adb(), "devices"], check=False)
    return [l.split()[0] for l in p.stdout.splitlines()[1:] if l.strip().endswith("device")]


def adb_install(apk, pkg, launch=True):
    devs = adb_devices()
    if not devs:
        die("no phone found by adb. Turn on USB debugging (or Wireless debugging) and run: adb devices")
    if len(devs) > 1 and not os.environ.get("ANDROID_SERIAL"):
        die("more than one device: set ANDROID_SERIAL to one of: " + ", ".join(devs))
    say("installing on the phone")
    p = run([adb(), "install", "-r", "-d", apk], check=False)
    if "Success" not in p.stdout:
        sys.stdout.write(p.stdout)
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in p.stdout or "signatures do not match" in p.stdout:
            die("a copy of this app signed with a different key is installed: uninstall it first (adb uninstall %s)" % pkg)
        die("install failed")
    say("installed: " + pkg)
    run([adb(), "shell", "appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"], check=False)   # dpooa overlay, harmless otherwise
    if launch:
        run([adb(), "shell", "am", "start", "-n", pkg + "/apple.gen.MainActivity"], check=False)


def adb_logs(pkg):
    p = run([adb(), "shell", "pidof", pkg], check=False)
    pid = p.stdout.strip().split()
    cmd = [adb(), "logcat", "-v", "time"] + (["--pid=" + pid[0]] if pid else ["-s", "AndroidRuntime:E", "System.err:W"])
    print("logs (Ctrl+C to stop)")
    try:
        subprocess.call(cmd)
    except KeyboardInterrupt:
        pass


# ---------------------------------------------------------------- preview (interactive phone window on the PC)
def preview(src, width=None, height=None, dpi=None, cpu=None, name=None, keep=False):
    import pockettk_preview as pv
    return pv.run_preview(sys.modules[__name__], src, width, height, dpi, cpu, name, keep)


# ---------------------------------------------------------------- doctor / setup
def doctor():
    print(VERSION + "  (cross-platform)  " + platform.system() + " " + platform.machine())
    ok = True

    def line(good, label, detail):
        nonlocal ok
        print("  [%s]  %s%s" % ("ok" if good else "..", label, ("  " + detail) if detail else ""))
        ok = ok and good

    line(True, "python", "%s (%s)" % (python_exe(), platform.python_version()))
    for n in ("javac", "java", "keytool"):
        p = find_tool(n)
        line(bool(p), n, p or "missing: install a JDK 17 or newer (https://adoptium.net%s)" % ("  or: brew install openjdk" if platform.system() == "Darwin" else ""))
    for n, hint in (("aapt2", "SDK build-tools"), ("d8", "SDK build-tools"), ("apksigner", "SDK build-tools")):
        p = find_tool(n) or (find_lib(n + ".jar"))
        line(bool(p), n, p or "missing: run  pockettk setup  (%s)" % hint)
    aj = find_android_jar()
    line(bool(aj), "android.jar", aj or "missing: run  pockettk setup  (or put android.jar in %s)" % KS)
    a = find_tool("adb")
    print("  [%s]  adb (only to install on a phone)  %s" % ("ok" if a else "--", a or "optional: pockettk setup installs platform-tools"))
    if a:
        d = adb_devices()
        print("  [%s]  phone  %s" % ("ok" if d else "--", ", ".join(d) if d else "none connected (USB debugging or Wireless debugging)"))
    bt = find_bundletool()
    print("  [%s]  bundletool (only for --aab)  %s" % ("ok" if bt else "--", bt or "optional: pockettk setup downloads it"))
    if platform.system() == "Darwin":
        xr = shutil.which("xcrun")
        sdk = None
        if xr:
            q = subprocess.run(["xcrun", "--sdk", "iphoneos", "--show-sdk-path"], capture_output=True, text=True)
            sdk = q.stdout.strip() if q.returncode == 0 else None
        print("  [%s]  iOS builds (--ipa)  %s" % ("ok" if sdk else "--", sdk or "optional: install Xcode (the full app) to build .ipa files"))
    elif True:
        print("  [--]  iOS builds (--ipa)  macOS only for now")
    print("  [ok]  data folder  " + KS)
    print("  [ok]  output folder  " + out_dir())
    print("Everything needed is present." if ok else "Missing parts are listed above.")
    return 0 if ok else 1


def setup():
    """Download the Android command-line tools from Google, then the build-tools, platform and platform-tools."""
    import urllib.request, zipfile, xml.etree.ElementTree as ET
    sdk = os.environ.get("POCKETTK_SDK") or os.path.join(KS, "sdk")
    os.makedirs(sdk, exist_ok=True)
    sm = os.path.join(sdk, "cmdline-tools", "latest", "bin", "sdkmanager" + BAT)
    if not os.path.isfile(sm):
        say("looking up the Android command-line tools")
        url = "https://dl.google.com/android/repository/repository2-3.xml"
        try:
            xml = urllib.request.urlopen(url, timeout=60).read()
        except Exception as e:
            die("could not reach dl.google.com (%s). Install Android command-line tools by hand, or put android.jar and build-tools in %s" % (e, sdk))
        root = ET.fromstring(xml)
        osn = {"Windows": "windows", "Darwin": "macosx"}.get(platform.system(), "linux")
        best = None
        for rp in root.iter("remotePackage"):
            if rp.get("path") == "cmdline-tools;latest":
                for ar in rp.iter("archive"):
                    if ar.findtext("host-os") == osn:
                        best = ar.findtext("complete/url")
        if not best:
            die("no command-line tools listed for this system")
        z = os.path.join(sdk, "cmdline.zip")
        say("downloading " + best)
        urllib.request.urlretrieve("https://dl.google.com/android/repository/" + best, z)
        tmp = os.path.join(sdk, "_x")
        shutil.rmtree(tmp, ignore_errors=True)
        zipfile.ZipFile(z).extractall(tmp)
        os.makedirs(os.path.join(sdk, "cmdline-tools"), exist_ok=True)
        shutil.rmtree(os.path.join(sdk, "cmdline-tools", "latest"), ignore_errors=True)
        shutil.move(os.path.join(tmp, "cmdline-tools"), os.path.join(sdk, "cmdline-tools", "latest"))
        shutil.rmtree(tmp, ignore_errors=True); os.remove(z)
        if not WIN:
            for f in glob.glob(os.path.join(sdk, "cmdline-tools", "latest", "bin", "*")):
                os.chmod(f, 0o755)
    print("Google's Android SDK license must be accepted to continue (read it, answer y).")
    env = dict(os.environ, JAVA_HOME=os.environ.get("JAVA_HOME", ""))
    if subprocess.call([sm, "--sdk_root=" + sdk, "--licenses"] if not WIN else ["cmd", "/c", sm, "--sdk_root=" + sdk, "--licenses"]) != 0:
        die("licenses were not accepted")
    cmd = [sm, "--sdk_root=" + sdk, "build-tools;34.0.0", "platforms;android-34", "platform-tools"]
    if subprocess.call(cmd if not WIN else ["cmd", "/c"] + cmd) != 0:
        die("sdkmanager failed")
    if not find_bundletool():
        say("looking up bundletool (for --aab, Google Play bundles)")
        os.makedirs(KS, exist_ok=True)
        try:
            rel = json.loads(urllib.request.urlopen("https://api.github.com/repos/google/bundletool/releases/latest", timeout=60).read())
            asset = next(x for x in rel["assets"] if re.match(r"bundletool-all-.*\.jar$", x["name"]))
            urllib.request.urlretrieve(asset["browser_download_url"], os.path.join(KS, "bundletool.jar"))
            say("saved " + os.path.join(KS, "bundletool.jar"))
        except Exception as e:
            say("could not download bundletool (%s). Only --aab needs it: put bundletool-all-VERSION.jar at %s" % (e, os.path.join(KS, "bundletool.jar")))
    say("done. Run: pockettk doctor")
    return 0


# ---------------------------------------------------------------- CLI
def cmd_ids():
    d = os.path.join(KS, "ids")
    if not os.path.isdir(d) or not os.listdir(d):
        print("no app ids yet (create one with:  pockettk run app.py --id NAME)")
        return 0
    for i in sorted(os.listdir(d)):
        r = read_id(i)
        print("%-14s %-34s v%s%s  code %s  %s" % (i, r["pkg"], r.get("ver", "?"), " Patch " + r["patch"] if r.get("patch") not in (None, "", "0") else "", r.get("vc"), r.get("src")))
    return 0


def ipa_main(a, bump):
    """build / run / update with --ipa (macOS only)."""
    import pockettk_ipa as ipa
    ipa.need_mac(sys.modules[__name__])
    tk = sys.modules[__name__]
    ident = safe_name(a.id) if a.id else None
    src, pkg = a.script, a.package
    if a.cmd == "update":
        if not ident: die("usage: pockettk update --id NAME [app.py] --ipa")
        rec = read_id(ident) or die("unknown app id '%s' (see: pockettk ids)" % ident)
        src = src or rec.get("src")
        if not src or not os.path.isfile(src): die("source not found: %s - pass the file: pockettk update --id %s app.py --ipa" % (src, ident))
        sfx = legacy_suffix(rec["pkg"])
        if sfx is not None and not pkg:
            pkg = PKG_PREFIX + sfx
        say("updating '%s' for iOS" % ident)
    result, bid = ipa.build_ipa(tk, src, a.name, pkg, a.output if a.cmd == "build" else None, ident, bump,
                                a.sign, a.profile, a.simulator)
    if a.cmd in ("run", "update"):
        if a.simulator:
            ipa.run_in_simulator(tk, result, bid)
        else:
            ipa.install_on_device(tk, result)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(prog="pockettk", description=VERSION)
    sp = ap.add_subparsers(dest="cmd")

    def common(p):
        p.add_argument("-n", "--name"); p.add_argument("-p", "--package"); p.add_argument("-o", "--output")
        p.add_argument("--icon"); p.add_argument("--id"); p.add_argument("-k", "--keep", action="store_true")
        p.add_argument("--debug", action="store_true"); p.add_argument("--target-sdk", default=None)
        p.add_argument("--ipa", action="store_true", help="build an iOS .ipa instead of an APK (macOS only)")
        p.add_argument("--sign", help="--ipa: signing identity, e.g. \"Apple Development: Name (TEAMID)\"")
        p.add_argument("--profile", help="--ipa: .mobileprovision file for --sign")
        p.add_argument("--simulator", "--emulate", dest="simulator", action="store_true",
                       help="--ipa: run it in the iOS Simulator on this Mac (no signing needed)")
        g = p.add_mutually_exclusive_group()
        for b in ("patch", "small", "medium", "large"):
            g.add_argument("--" + b, dest="bump", action="store_const", const=b)

    def pv_args(p):
        p.add_argument("--preview", action="store_true", help="show it in a phone window on this PC instead of a phone")
        p.add_argument("--width", type=int, default=None, help="screen width in dp (default 360)")
        p.add_argument("--height", type=int, default=None, help="screen height in dp (default 800)")
        p.add_argument("--dpi", type=int, default=None, help="density (default 420)")
        p.add_argument("--cpu", type=int, default=None, help="CPU strength in percent, 5-100 (simulated slowdown)")

    b = sp.add_parser("build"); b.add_argument("script"); common(b); b.add_argument("-i", "--install", action="store_true")
    b.add_argument("--aab", action="store_true", help="Android App Bundle for a Google Play upload (needs bundletool)")
    b.add_argument("--keystore", help="--aab: your own upload keystore (default: a key made in ~/.pockettk)")
    b.add_argument("--ks-alias", help="--aab: key alias inside --keystore")
    r = sp.add_parser("run"); r.add_argument("script"); common(r); pv_args(r); r.add_argument("--logs", action="store_true")
    u = sp.add_parser("update"); u.add_argument("script", nargs="?"); common(u); pv_args(u); u.add_argument("--logs", action="store_true")
    pr = sp.add_parser("preview"); pr.add_argument("script"); pr.add_argument("-n", "--name"); pr.add_argument("-k", "--keep", action="store_true"); pv_args(pr)
    ck = sp.add_parser("check", help="transpile only: show warnings (nothing is built)",
                       description="Transpile the script and show the warnings and notes. Nothing is built. -v also prints the generated Java.")
    ck.add_argument("script")
    ck.add_argument("-v", "--java", action="store_true", help="also print the generated Java")
    sp.add_parser("ids"); sp.add_parser("doctor"); sp.add_parser("setup"); sp.add_parser("version")
    gu = sp.add_parser("gui", help="open the PocketTk window (file picker, build, setup) - no terminal needed")
    gu.add_argument("script", nargs="?", help="a .py file to preselect")
    gu.add_argument("--pick", action="store_true", help="open the file picker right away")
    lg = sp.add_parser("logs"); lg.add_argument("--id")
    a = ap.parse_args(argv)
    try:
        if a.cmd in (None,):
            ap.print_help(); return 0
        if a.cmd == "version":
            print(VERSION); return 0
        if a.cmd == "gui":
            if os.environ.get("TERMUX_VERSION") or os.path.isdir("/data/data/com.termux/files/usr"):
                die("PocketTk has no window on Android: use the commands (pockettk help). The window apps are for macOS, Windows and Linux")
            import pockettk_gui
            return pockettk_gui.main(a.script, a.pick)
        if a.cmd == "doctor":
            return doctor()
        if a.cmd == "setup":
            return setup()
        if a.cmd == "ids":
            return cmd_ids()
        if a.cmd == "check":
            tmp = os.path.join(WORK, "check"); shutil.rmtree(tmp, ignore_errors=True); os.makedirs(tmp)
            p = run([python_exe(), "-I", os.path.join(HERE, "transpile.py"), "gen", a.script, tmp, "--no-assets"], check=False)
            if p.returncode: sys.stdout.write(p.stdout); die("transpile failed")
            lines = [l for l in p.stdout.splitlines() if l.strip()]
            for l in lines:
                print(l)                                    # the transpiler's own "warning: ..." and "note: ..." lines
            n = sum(1 for l in lines if l.startswith("warning:"))
            print("no warnings" if not n else "%d warning%s" % (n, "" if n == 1 else "s"))
            if a.java:
                print("---- generated Script.java ----")
                print(open(os.path.join(tmp, "src", "apple", "gen", "Script.java"), encoding="utf-8").read())
            return 0
        if a.cmd == "preview":
            preview(a.script, a.width, a.height, a.dpi, a.cpu, a.name, a.keep); return 0
        if a.cmd == "logs":
            rec = read_id(a.id) if a.id else None
            pkg = rec["pkg"] if rec else (open(os.path.join(KS, "last-package")).read().strip() if os.path.isfile(os.path.join(KS, "last-package")) else "")
            if not pkg: die("give --id NAME")
            adb_logs(pkg); return 0
        bump = getattr(a, "bump", None) or "patch"
        if a.cmd in ("build", "run", "update") and (a.ipa or a.sign or a.profile or a.simulator) and not a.ipa:
            die("--sign, --profile and --simulator go with --ipa")
        if a.cmd in ("build", "run", "update") and a.ipa:
            return ipa_main(a, bump)
        if a.cmd == "build":
            if a.aab and a.install: die("an .aab cannot be installed on a phone (it is for Google Play upload): build with --aab without -i")
            if (a.keystore or a.ks_alias) and not a.aab: die("--keystore and --ks-alias go with --aab")
            apk, pkg = build(a.script, a.name, a.package, a.output, a.icon, safe_name(a.id) if a.id else None, bump, a.debug, a.keep, a.target_sdk,
                             None, a.aab, a.keystore, a.ks_alias)
            if a.install: adb_install(apk, pkg)
            return 0
        if a.cmd in ("run", "update"):
            ident = safe_name(a.id) if a.id else None
            src = a.script
            if a.cmd == "update":
                if not ident: die("usage: pockettk update --id NAME [app.py]")
                rec = read_id(ident) or die("unknown app id '%s' (see: pockettk ids)" % ident)
                src = src or rec.get("src")
                if not src or not os.path.isfile(src): die("source not found: %s - pass the file: pockettk update --id %s app.py" % (src, ident))
                say("updating '%s' (%s)" % (ident, rec["pkg"]))
            if a.preview:
                preview(src, a.width, a.height, a.dpi, a.cpu, a.name, a.keep); return 0
            override, oldpkg = None, None
            if a.cmd == "update" and not a.debug:
                sfx = legacy_suffix(rec["pkg"])
                if sfx is not None:                       # still on an old package: build it as pockettk.ssdes.*
                    override, oldpkg = PKG_PREFIX + sfx, rec["pkg"]
                    say("package: %s -> %s" % (oldpkg, override))
            apk, pkg = build(src, a.name, a.package, a.output, a.icon, ident, bump, a.debug, a.keep, a.target_sdk, override)
            adb_install(apk, pkg)
            if oldpkg:
                say("%s is now a new app. The old app %s is still on the phone with its data (a PC cannot copy it over without root)." % (pkg, oldpkg))
                say("remove it with:  adb uninstall %s     (on a rooted phone, `pockettk update` there moves the data too)" % oldpkg)
            if a.logs: adb_logs(pkg)
            return 0
        ap.print_help(); return 1
    except Fail as e:
        print("pockettk: %s" % e, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
