package apple.rt;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/** Geometry and drawing helpers for Canvas items: bounding boxes, hit tests, dashes, arrows, smooth lines, text blocks. */
final class CanvasKit {
    private CanvasKit() {}

    /** Offsets of a box of size w x h placed at (x, y) with a Tk anchor. Returns the top-left corner. */
    static float[] anchored(String an, float x, float y, float w, float h) {
        String a = an.equals("center") ? "" : an;               // "center" itself contains n and e
        float l = a.contains("w") ? x : a.contains("e") ? x - w : x - w / 2;
        float t = a.contains("n") ? y : a.contains("s") ? y - h : y - h / 2;
        return new float[]{l, t};
    }

    static List<String> lines(Ui.CItem it, Paint p) {
        List<String> out = new ArrayList<String>();
        for (String raw : it.text.split("\n", -1)) {
            if (it.wrap <= 0) { out.add(raw); continue; }
            StringBuilder cur = new StringBuilder();
            for (String word : raw.split(" ", -1)) {
                String t = cur.length() == 0 ? word : cur + " " + word;
                if (cur.length() > 0 && p.measureText(t) > it.wrap) { out.add(cur.toString()); cur = new StringBuilder(word); }
                else { cur = new StringBuilder(t); }
            }
            out.add(cur.toString());
        }
        return out;
    }

    static void setFont(Paint p, Ui.CItem it) {
        p.setTextSize(it.size);
        p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                it.bold && it.italic ? android.graphics.Typeface.BOLD_ITALIC : it.bold ? android.graphics.Typeface.BOLD : it.italic ? android.graphics.Typeface.ITALIC : android.graphics.Typeface.NORMAL));
    }

    /** Where a text item's block lands: {left, top, width, height, lineHeight}. */
    static float[] textBox(Ui.CItem it, Paint p) {
        setFont(p, it);
        List<String> ls = lines(it, p);
        float w = 0;
        for (String l : ls) w = Math.max(w, p.measureText(l));
        float lh = p.descent() - p.ascent();
        float h = lh * ls.size();
        float[] tl = anchored(it.anchor, (float) it.c[0], (float) it.c[1], w, h);
        return new float[]{tl[0], tl[1], w, h, lh};
    }

    static void drawText(Canvas cv, Ui.CItem it, Paint p, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        float[] b = textBox(it, p);
        List<String> ls = lines(it, p);
        for (int i = 0; i < ls.size(); i++) {
            float lw = p.measureText(ls.get(i));
            float x = b[0] + (it.justify.equals("center") ? (b[2] - lw) / 2 : it.justify.equals("right") ? b[2] - lw : 0);
            cv.drawText(ls.get(i), x, b[1] + i * b[4] - p.ascent(), p);
        }
    }

    /** A dashed straight segment. dash is on, off, on, off... in canvas pixels. */
    static void dashed(Canvas cv, float x1, float y1, float x2, float y2, float[] dash, Paint p) {
        double len = Math.hypot(x2 - x1, y2 - y1);
        if (len == 0 || dash == null || dash.length == 0) { cv.drawLine(x1, y1, x2, y2, p); return; }
        double pos = 0;
        int i = 0;
        while (pos < len) {
            float seg = Math.max(0.5f, dash[i % dash.length]);
            double end = Math.min(len, pos + seg);
            if (i % 2 == 0) cv.drawLine((float) (x1 + (x2 - x1) * pos / len), (float) (y1 + (y2 - y1) * pos / len), (float) (x1 + (x2 - x1) * end / len), (float) (y1 + (y2 - y1) * end / len), p);
            pos = end;
            i++;
        }
    }

    static void arrowHead(Canvas cv, float tipX, float tipY, float fromX, float fromY, float w, Paint p) {
        double ang = Math.atan2(tipY - fromY, tipX - fromX);
        float len = Math.max(8f, w * 3.5f), half = Math.max(3.5f, w * 1.6f);
        float bx = (float) (tipX - len * Math.cos(ang)), by = (float) (tipY - len * Math.sin(ang));
        Path path = new Path();
        path.moveTo(tipX, tipY);
        path.lineTo((float) (bx + half * Math.sin(ang)), (float) (by - half * Math.cos(ang)));
        path.lineTo((float) (bx - half * Math.sin(ang)), (float) (by + half * Math.cos(ang)));
        path.close();
        p.setStyle(Paint.Style.FILL);
        cv.drawPath(path, p);
    }

    /** Tk's "smooth": a curve through the middles of the segments, with the points as controls. */
    static Path smoothPath(double[] c, boolean closed) {
        Path path = new Path();
        int n = c.length / 2;
        if (n < 3) {
            for (int i = 0; i < n; i++) { if (i == 0) path.moveTo((float) c[0], (float) c[1]); else path.lineTo((float) c[2 * i], (float) c[2 * i + 1]); }
            return path;
        }
        float mx = (float) (c[0] + c[2]) / 2, my = (float) (c[1] + c[3]) / 2;
        path.moveTo(closed ? mx : (float) c[0], closed ? my : (float) c[1]);
        if (!closed) path.lineTo(mx, my);
        for (int i = 1; i < (closed ? n + 1 : n - 1); i++) {
            int a = i % n, b = (i + 1) % n;
            float cx = (float) c[2 * a], cy = (float) c[2 * a + 1];
            float ex = (float) (c[2 * a] + c[2 * b]) / 2, ey = (float) (c[2 * a + 1] + c[2 * b + 1]) / 2;
            path.quadTo(cx, cy, ex, ey);
        }
        if (!closed) path.lineTo((float) c[2 * n - 2], (float) c[2 * n - 1]);
        else path.close();
        return path;
    }

    // ------------------------------------------------------------ boxes and hits

    /** {x1, y1, x2, y2}, or null for an item with no size yet. */
    static float[] bbox(Ui.CItem it, Paint p) {
        double[] c = it.c;
        if (c == null || c.length < 2) return null;
        if (it.type.equals("text")) { float[] b = textBox(it, p); return new float[]{b[0], b[1], b[0] + b[2], b[1] + b[3]}; }
        if (it.type.equals("image")) {
            if (it.img == null) return null;
            float[] tl = anchored(it.anchor, (float) c[0], (float) c[1], it.img.wd(), it.img.ht());
            return new float[]{tl[0], tl[1], tl[0] + it.img.wd(), tl[1] + it.img.ht()};
        }
        float x1 = Float.MAX_VALUE, y1 = Float.MAX_VALUE, x2 = -Float.MAX_VALUE, y2 = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < c.length; i += 2) {
            x1 = (float) Math.min(x1, c[i]); x2 = (float) Math.max(x2, c[i]);
            y1 = (float) Math.min(y1, c[i + 1]); y2 = (float) Math.max(y2, c[i + 1]);
        }
        float pad = (it.outline != null || it.type.equals("line")) ? it.width / 2f : 0f;
        return new float[]{x1 - pad, y1 - pad, x2 + pad, y2 + pad};
    }

    static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** True when the point (x, y) is on the item, within halo pixels. */
    static boolean hit(Ui.CItem it, double x, double y, double halo, Paint p) {
        if (it.hidden) return false;
        float[] b = bbox(it, p);
        if (b == null) return false;
        if (it.type.equals("line")) {
            double[] c = it.c;
            for (int i = 0; i + 3 < c.length; i += 2) if (segDist(x, y, c[i], c[i + 1], c[i + 2], c[i + 3]) <= Math.max(halo, it.width / 2.0)) return true;
            return false;
        }
        if (it.type.equals("oval") && it.fill != null) {
            double rx = (b[2] - b[0]) / 2 + halo, ry = (b[3] - b[1]) / 2 + halo;
            if (rx <= 0 || ry <= 0) return false;
            double dx = (x - (b[0] + b[2]) / 2) / rx, dy = (y - (b[1] + b[3]) / 2) / ry;
            return dx * dx + dy * dy <= 1;
        }
        return x >= b[0] - halo && x <= b[2] + halo && y >= b[1] - halo && y <= b[3] + halo;
    }

    static boolean overlaps(float[] b, double x1, double y1, double x2, double y2) {
        return b != null && b[2] >= Math.min(x1, x2) && b[0] <= Math.max(x1, x2) && b[3] >= Math.min(y1, y2) && b[1] <= Math.max(y1, y2);
    }

    static boolean enclosed(float[] b, double x1, double y1, double x2, double y2) {
        return b != null && b[0] >= Math.min(x1, x2) && b[2] <= Math.max(x1, x2) && b[1] >= Math.min(y1, y2) && b[3] <= Math.max(y1, y2);
    }

    static RectF rect(double[] c) {
        return new RectF((float) Math.min(c[0], c[2]), (float) Math.min(c[1], c[3]), (float) Math.max(c[0], c[2]), (float) Math.max(c[1], c[3]));
    }
}
