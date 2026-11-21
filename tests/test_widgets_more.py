"""Treeview and LabeledScale are real widgets now (they used to be placeholder labels)."""
import os
import unittest
from tests.test_images import gen, HERE


class MoreWidgets(unittest.TestCase):
    def test_treeview(self):
        p, java, out = gen('import tkinter as tk\nfrom tkinter import ttk\nroot = tk.Tk()\n'
                           'tv = ttk.Treeview(root, columns=("a", "b"), show="headings")\n'
                           'tv.heading("a", text="A")\ntv.insert("", "end", values=(1, 2))\n'
                           'tv.bind("<<TreeviewSelect>>", lambda e: print(tv.selection()))\nroot.mainloop()\n')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn('Ui.w("Treeview"', java)
        self.assertNotIn("not supported yet", p.stdout + p.stderr)

    def test_labeled_scale(self):
        p, java, out = gen('import tkinter as tk\nfrom tkinter import ttk\nroot = tk.Tk()\n'
                           'v = tk.IntVar()\nttk.LabeledScale(root, variable=v, from_=0, to=9).pack()\nroot.mainloop()\n')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("Ui.labeledScale(", java)


class CanvasMore(unittest.TestCase):
    def test_new_canvas_calls_transpile(self):
        p, java, out = gen('import tkinter as tk\nroot = tk.Tk()\nc = tk.Canvas(root)\nc.pack()\n'
                           'a = c.create_arc(0, 0, 50, 50, start=10, extent=90, style="pieslice")\n'
                           'c.create_line(0, 0, 9, 9, arrow="last", dash=(4, 2), smooth=True)\n'
                           'c.tag_bind(a, "<Button-1>", lambda e: print(c.bbox(a), c.find_closest(1, 1)))\n'
                           'c.tag_raise(a)\nroot.mainloop()\n')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertNotIn("not supported", p.stdout + p.stderr)
        self.assertIn("create_arc", java)

    def test_canvas_scale_uses_exact_density(self):
        src = open(os.path.join(HERE, "rt", "Ui.java"), encoding="utf-8").read()
        self.assertNotIn("float d = dp(1);", src)      # dp(1) is rounded to whole pixels: drawings were 14% too big on a 2.625 phone


if __name__ == "__main__":
    unittest.main()
