"""python3 -m unittest tests.test_check
`pockettk check` (warnings only; -v for the generated Java) on the desktop command line and the phone script, and the header
comment of the generated Java. Needs no Android tools."""
import os, sys, shutil, tempfile, subprocess, unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

GOOD = "import tkinter as tk\nroot = tk.Tk()\nroot.mainloop()\n"
NO_TK = "print('hello')\n"
BAD = "def (:\n"


def read(*p):
    with open(os.path.join(HERE, *p), encoding="utf-8") as f:
        return f.read()


class Check(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.home = tempfile.mkdtemp(prefix="pockettk-check-")
        cls.env = dict(os.environ, POCKETTK_HOME=cls.home, PYTHONIOENCODING="utf-8")
        for name, src in (("good.py", GOOD), ("notk.py", NO_TK), ("bad.py", BAD)):
            with open(os.path.join(cls.home, name), "w") as f:
                f.write(src)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.home, ignore_errors=True)

    def cli(self, *args):
        p = subprocess.run([sys.executable, os.path.join(HERE, "pockettk.py")] + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           universal_newlines=True, encoding="utf-8", errors="replace", env=self.env, timeout=120)
        return p.returncode, p.stdout

    def test_default_shows_warnings_only_not_the_java(self):
        code, out = self.cli("check", os.path.join(self.home, "notk.py"))
        self.assertEqual(code, 0, out)
        self.assertIn("warning: this script does not import tkinter", out)
        self.assertIn("1 warning", out)
        self.assertNotIn("public final class Script", out)
        self.assertNotIn("package apple.gen", out)
        self.assertLess(len(out.splitlines()), 6)

    def test_clean_script_says_so(self):
        code, out = self.cli("check", os.path.join(self.home, "good.py"))
        self.assertEqual(code, 0, out)
        self.assertIn("no warnings", out)
        self.assertNotIn("class Script", out)

    def test_dash_v_adds_the_generated_java_after_the_warnings(self):
        for flag in ("-v", "--java"):
            code, out = self.cli("check", os.path.join(self.home, "good.py"), flag)
            self.assertEqual(code, 0, out)
            self.assertIn("public final class Script", out)
            self.assertLess(out.index("no warnings"), out.index("public final class Script"))

    def test_a_script_that_cannot_be_transpiled_fails(self):
        code, out = self.cli("check", os.path.join(self.home, "bad.py"))
        self.assertNotEqual(code, 0)
        self.assertIn("transpile failed", out)

    def test_help_matches_what_it_does(self):
        code, out = self.cli("check", "--help")
        self.assertIn("warnings", out)
        self.assertIn("--java", out)

    @unittest.skipIf(os.name == "nt", "the phone script is a shell script")
    def test_the_phone_command_does_the_same(self):
        home = tempfile.mkdtemp(prefix="pockettk-phone-")
        try:
            prefix = os.path.dirname(os.path.dirname(sys.executable))
            termux = prefix if os.path.isfile(os.path.join(prefix, "bin", "python3")) else "/usr"
            env = dict(os.environ, HOME=home, APKAPPLE_HOME=home, APKAPPLE_TERMUX=termux)

            def run(*a):
                return subprocess.run(["sh", os.path.join(HERE, "apkapple.sh"), "check"] + list(a), stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, universal_newlines=True, env=env, timeout=120)
            p = run(os.path.join(self.home, "notk.py"))
            self.assertEqual(p.returncode, 0, p.stdout)
            self.assertIn("warning: this script does not import tkinter", p.stdout)
            self.assertIn("1 warning", p.stdout)
            self.assertNotIn("public final class Script", p.stdout)
            p = run(os.path.join(self.home, "good.py"))
            self.assertIn("no warnings", p.stdout)
            self.assertNotIn("class Script", p.stdout)
            p = run(os.path.join(self.home, "good.py"), "-v")
            self.assertIn("public final class Script", p.stdout)
            p = run(os.path.join(self.home, "bad.py"))
            self.assertNotEqual(p.returncode, 0)
            self.assertIn("transpile failed", p.stdout)
            p = run("/no/such/file.py")
            self.assertNotEqual(p.returncode, 0)
            self.assertIn("no such file", p.stdout)
        finally:
            shutil.rmtree(home, ignore_errors=True)
        self.assertIn("pockettk check app.py [-v]", read("apkapple.sh"))
        self.assertIn("pockettk check app.py [-v]", read("README.md"))


class GeneratedJava(unittest.TestCase):
    def test_no_leftover_old_name_in_the_generated_java(self):
        out = tempfile.mkdtemp(prefix="pockettk-gen-")
        try:
            src = os.path.join(out, "a.py")
            with open(src, "w") as f:
                f.write(GOOD)
            gen = os.path.join(out, "gen")
            p = subprocess.run([sys.executable, "-I", os.path.join(HERE, "transpile.py"), "gen", src, gen, "--no-assets"],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
            self.assertEqual(p.returncode, 0, p.stdout)
            javas = [os.path.join(b, n) for b, d, ns in os.walk(gen) for n in ns if n.endswith(".java")]
            self.assertTrue(javas)
            for j in javas:
                with open(j, encoding="utf-8") as f:
                    text = f.read()
                self.assertNotIn("ApkApple", text, j)
                self.assertNotIn("TkDroid", text, j)
            with open(os.path.join(gen, "src", "apple", "gen", "Script.java"), encoding="utf-8") as f:
                self.assertIn("/** Generated by PocketTk from your Python script. Do not edit. */", f.read())
        finally:
            shutil.rmtree(out, ignore_errors=True)


class Version(unittest.TestCase):
    def test_every_place_says_the_same_version(self):
        import re
        v = re.search(r'^VERSION = "PocketTk v(.*)"', read("pockettk.py"), re.M).group(1)
        self.assertEqual(v, "4.1.2")
        self.assertIn('VERSION="PocketTk v%s"' % v, read("apkapple.sh"))
        self.assertIn("version=v%s\n" % v, read("module.prop"))
        self.assertIn("PocketTk v%s" % v, read("customize.sh"))
        self.assertTrue(read("README.md").startswith("# PocketTk v%s" % v))
        self.assertIn("## v%s" % v, read("CHANGELOG.md"))
        self.assertTrue(read("RELEASE_NOTES.md").startswith("# PocketTk v%s" % v))


if __name__ == "__main__":
    unittest.main()
