"""python3 -m unittest tests.test_packaging  - the Windows / Linux / macOS packaging pieces that can be checked without those systems."""
import os, re, sys, struct, subprocess, tempfile, unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
_HOME = tempfile.mkdtemp(prefix="pockettk-pkg-home-")
os.environ["POCKETTK_HOME"] = _HOME
sys.path.insert(0, os.path.join(HERE, "packaging", "windows"))


def read(*p):
    with open(os.path.join(HERE, *p), encoding="utf-8") as f:
        return f.read()


class Packaging(unittest.TestCase):
    def test_ico_is_valid(self):
        import make_ico
        d = make_ico.build()
        reserved, kind, n = struct.unpack("<HHH", d[:6])
        self.assertEqual((reserved, kind), (0, 1))
        self.assertEqual(n, len(make_ico.SIZES))
        for i in range(n):
            w, h, _, _, _, _, size, off = struct.unpack("<BBBBHHII", d[6 + 16 * i:22 + 16 * i])
            self.assertEqual(d[off:off + 8], b"\x89PNG\r\n\x1a\n")
            self.assertEqual(off + size <= len(d), True)

    def test_icon_png_draws(self):
        sys.path.insert(0, os.path.join(HERE, "packaging", "macos"))
        import make_icon
        rows = make_icon.render(64)
        self.assertEqual(len(rows), 64)
        self.assertEqual(rows[0][3], 0)                                   # the corner is transparent
        self.assertEqual(tuple(rows[32][4 * 32:4 * 32 + 3]) != (0, 0, 0), True)
        self.assertTrue(make_icon.png(64, rows).startswith(b"\x89PNG"))
        self.assertTrue(os.path.isfile(os.path.join(HERE, "packaging", "logo.svg")))

    def test_version_is_readable_by_all_build_scripts(self):
        v = re.search(r'^VERSION = "PocketTk v(.*)"', read("pockettk.py"), re.M)
        self.assertTrue(v and re.fullmatch(r"\d+\.\d+\.\d+", v.group(1)), "build scripts need a plain x.y.z version")
        for name in ("README.md", "customize.sh", "apkapple.sh"):
            self.assertIn("PocketTk v" + v.group(1), read(name))
        self.assertIn("version=v" + v.group(1), read("module.prop"))

    def test_bundle_id_is_ssdes(self):
        self.assertIn("com.ssdes.pockettk", read("packaging", "macos", "build_app.sh"))
        for f in ("packaging/macos/build_app.sh", "packaging/macos/Info.plist.in", ".github/workflows/apps.yml"):
            self.assertNotIn("palalgon", read(*f.split("/")))

    @unittest.skipIf(os.name == "nt", "these are Linux/macOS scripts: the Linux and macOS jobs parse them (Windows' bash is WSL's launcher or needs LF files)")
    def test_shell_scripts_parse(self):
        for f in ("packaging/linux/build_app.sh", "packaging/linux/install.sh", "packaging/linux/AppRun", "packaging/macos/build_app.sh"):
            r = subprocess.run(["bash", "-n", os.path.join(HERE, f)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, f + ": " + r.stderr)

    def test_flash_logo_is_plain_and_aligned(self):
        r = subprocess.run(["sh", "-c", ". ./logo.sh; pockettk_logo echo"], cwd=HERE, capture_output=True, text=True)
        plain = r.stdout
        self.assertTrue(plain.isascii(), "the flash screen's fallback font does not align box-drawing or block characters")
        rows = [l for l in plain.split("\n") if l.strip()]
        pocket = [l for l in rows if l.startswith("   ") and not l.startswith("      ")]
        phone = [l for l in rows if l.startswith("      ")]
        self.assertEqual(len({len(l) for l in pocket}), 1, pocket)
        self.assertEqual(len({len(l) for l in phone}), 1, phone)
        self.assertTrue(all(len(l) <= 36 for l in rows))
        self.assertEqual(len(pocket[0]), len(pocket[-1]))                 # fits a phone's flash screen
        self.assertIn("P o c k e t T k", plain)
        c = read("customize.sh")
        self.assertIn('. "$MODPATH/logo.sh"', c)
        self.assertLess(c.index("pockettk_logo ui_print"), c.index("tkinter -> APK"))

    def test_appimage_pieces(self):
        desk = read("packaging", "linux", "pockettk.desktop")
        for k in ("Name=PocketTk", "Exec=AppRun", "Icon=pockettk", "Type=Application"):
            self.assertIn(k, desk)
        self.assertTrue(os.access(os.path.join(HERE, "packaging", "linux", "AppRun"), os.X_OK))
        self.assertIn("AppImage", read(".github", "workflows", "apps.yml"))

    def test_ci_uses_runner_labels_that_exist(self):
        yml = read(".github", "workflows", "apps.yml")
        self.assertNotIn("runner: macos-13", yml, "macos-13 was retired by GitHub")
        self.assertNotIn("runner: macos-14", yml)
        self.assertIn("macos-15-intel", yml)
        self.assertIn("{ runner: macos-15, arch: arm64 }", yml)

    def test_arm_linux_cannot_block_the_release(self):
        yml = read(".github", "workflows", "apps.yml")
        self.assertIn("continue-on-error: ${{ matrix.experimental }}", yml)
        self.assertIn("ubuntu-24.04-arm, arch: aarch64, experimental: true", yml)
        self.assertIn("ubuntu-latest, arch: x86_64, experimental: false", yml)

    def test_linux_build_falls_back_to_pyside6_and_says_why(self):
        sh = read("packaging", "linux", "build_app.sh")
        for k in ("--only-binary=:all:", "PySide6", "qt-api", "qt_diagnose", "QT_BINDING"):
            self.assertIn(k, sh)
        self.assertLess(sh.index("PyQt6 PyQt6-WebEngine"), sh.index("pipi --only-binary=:all: PySide6"))
        self.assertIn("qt-api", read("packaging", "linux", "AppRun"))

    def test_every_script_the_apps_copy_exists(self):
        for f in ("windows/build_app.ps1", "linux/build_app.sh", "macos/build_app.sh"):
            text = read("packaging", *f.split("/"))
            m = re.search(r"pockettk\.py[ ,\"]+pockettk_gui\.py[^\n]*", text)
            self.assertTrue(m, f)
            for name in re.findall(r"[a-z_]+\.py", m.group(0)):
                self.assertTrue(os.path.isfile(os.path.join(HERE, name)), f + " copies missing " + name)
            for d in re.search(r"\bd in ([^;)]*\brt\b[^;)]*)", text).group(1).replace('"', "").replace(",", " ").split():
                self.assertTrue(os.path.isdir(os.path.join(HERE, d)), f + " copies missing folder " + d)

    def test_no_stdout_does_not_crash_the_window(self):
        code = ("import sys, os; sys.stdout = None; sys.stderr = None; os.environ['HOME'] = %r; os.environ['USERPROFILE'] = %r\n"
                "sys.path.insert(0, %r)\nimport pockettk_gui as g\ng._ensure_streams()\nprint('hello'); sys.stderr.write('x')\n"
                "print('ok', file=sys.__stdout__)" % (_HOME, _HOME, HERE))
        r = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, env=dict(os.environ, POCKETTK_HOME=_HOME))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(os.path.isfile(os.path.join(_HOME, ".pockettk", "gui.log")))


class CiFailuresStayFixed(unittest.TestCase):
    """The two failures of the Desktop apps workflow: Windows "self-test failed" and Linux arm64 "libopus.so.0 not found"."""

    def test_the_window_starts_under_python_dash_I(self):
        # `python -I` leaves the script's folder off sys.path; the Windows build used it for the self-test, and
        # `import pockettk` crashed before anything ran. The window must get past its imports either way.
        env = dict(os.environ, POCKETTK_GUI_SELFTEST="1", POCKETTK_HOME=_HOME, HOME=_HOME, USERPROFILE=_HOME)
        r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "pockettk_gui.py")], cwd=tempfile.gettempdir(),
                           capture_output=True, text=True, env=env)
        self.assertNotIn("ModuleNotFoundError", r.stderr, r.stderr)
        self.assertIn("selftest:", r.stdout, r.stdout + r.stderr)          # it ran (ok, or a PROBLEM line such as no pywebview)

    def test_windows_selftest_starts_the_app_the_way_users_do(self):
        ps1 = read("packaging", "windows", "build_app.ps1")
        a = ps1.index("# ---- 5.")
        step = ps1[a:ps1.index("# ---- 6.")]
        launch = [l for l in step.splitlines() if "pockettk_gui.py" in l and not l.lstrip().startswith("#")]
        self.assertEqual(len(launch), 1, launch)
        self.assertNotRegex(launch[0], r"\s-I\b", "the window is never started with -I, so the self-test must not be")
        for env in ("POCKETTK_GUI_SELFTEST", "PYTHONNOUSERSITE"):          # the launcher (PocketTk.cmd) sets PYTHONNOUSERSITE too
            self.assertIn(env, step)
        self.assertIn("PYTHONNOUSERSITE", ps1[:a])
        self.assertIn("exit code", step, "the failure message should say how it failed")

    def test_system_libraries_are_named_in_one_place(self):
        lst = read("packaging", "linux", "system-libs.txt")
        pk = [l.strip() for l in lst.splitlines() if l.strip() and not l.startswith("#")]
        self.assertEqual(len(pk), len(set(pk)), "duplicates in system-libs.txt")
        for name in pk:
            self.assertRegex(name, r"^[a-z0-9][a-z0-9.+-]+$")
        for must in ("libopus0", "libminizip1t64", "libnss3", "libasound2t64", "libxcb-cursor0"):   # each one failed a real CI run or user
            self.assertIn(must, pk)
        for f in (".github/workflows/apps.yml", ".github/workflows/desktop-test.yml"):
            y = read(*f.split("/"))
            self.assertIn("system-libs.txt", y, f + " must install the list, not a copy of it")
            self.assertNotIn("libasound2t64", y, f + " still has its own copy of the library list")
        readme = read("packaging", "linux", "READ ME FIRST.txt")
        self.assertIn("@LIBS@", readme)
        sh = read("packaging", "linux", "build_app.sh")
        self.assertIn("system-libs.txt", sh)
        self.assertIn("@LIBS@", sh)
        self.assertNotIn("libasound2t64 libxkbcommon-x11-0 libxcb-cursor0 (", sh, "the hint must come from the list")

    @unittest.skipIf(os.name == "nt", "runs a Linux script that uses ldd: the Linux and macOS jobs run it")
    def test_linux_hint_names_the_missing_library_from_ldd(self):
        sh = read("packaging", "linux", "build_app.sh")
        funcs = "\n".join(re.search(r"^%s\(\) \{.*?^\}" % n, sh, re.S | re.M).group(0) for n in ("deb_for", "qt_diagnose"))
        d = tempfile.mkdtemp(prefix="pk-ldd-")
        os.makedirs(os.path.join(d, "bin"))
        os.makedirs(os.path.join(d, "APP", "python"))
        open(os.path.join(d, "APP", "python", "libQt6WebEngineCore.so.6"), "w").close()
        ldd = os.path.join(d, "bin", "ldd")
        with open(ldd, "w") as f:                  # what the failing CI run printed
            f.write("#!/bin/sh\nprintf '\\tlibopus.so.0 => not found\\n\\tlibc.so.6 => /lib/libc.so.6 (0x1)\\n'\n")
        os.chmod(ldd, 0o755)
        script = "ARCH=aarch64; APP=%s; HERE=%s\n%s\nqt_diagnose\nfor l in libminizip.so.1 libasound.so.2 libnope.so.1; do echo \"$l=$(deb_for $l)\"; done\n" % (
            os.path.join(d, "APP"), os.path.join(HERE, "packaging", "linux"), funcs)
        r = subprocess.run(["bash", "-c", script], capture_output=True, text=True, env=dict(os.environ, PATH=os.path.join(d, "bin") + os.pathsep + os.environ["PATH"]))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("sudo apt install libopus0", r.stderr)                  # exactly the missing one, spelled out
        self.assertIn("libopus0", r.stderr.split("Everything Qt WebEngine usually needs")[1])    # and the whole list includes it
        self.assertIn("libminizip.so.1=libminizip1t64", r.stdout)
        self.assertIn("libasound.so.2=libasound2t64", r.stdout)
        self.assertIn("libnope.so.1=\n", r.stdout + "\n")                    # unknown libraries map to nothing, no error


class AndroidZipInRelease(unittest.TestCase):
    def test_workflow_attaches_android_zip(self):
        wf = read(".github/workflows/apps.yml")
        self.assertIn("-android.zip", wf)
        self.assertRegex(wf, r"needs: \[[^\]]*android[^\]]*\]")


class ReleaseNotes(unittest.TestCase):
    def test_notes_match_the_version_and_are_attached(self):
        v = re.search(r"^version=v(\S+)", read("module.prop"), re.M).group(1)
        self.assertTrue(read("RELEASE_NOTES.md").startswith("# PocketTk v" + v), "update RELEASE_NOTES.md for the new version")
        self.assertIn("body_path: RELEASE_NOTES.md", read(".github/workflows/apps.yml"))


if __name__ == "__main__":
    unittest.main()
