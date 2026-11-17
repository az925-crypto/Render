package com.catur.modmenu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.InputStream;
import java.util.Arrays;

/**
 * Best-effort board reader for screenshots. Used as a fallback when the
 * accessibility move list is unavailable. Template matching runs on 32x32
 * silhouettes so it stays cheap on a phone.
 */
final class BoardDetector {

    static final class Result {
        final String fen;
        final int left, top, right, bottom;
        final boolean whiteOnBottom;
        final float confidence;

        Result(String fen, int left, int top, int right, int bottom,
               boolean whiteOnBottom, float confidence) {
            this.fen = fen;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.whiteOnBottom = whiteOnBottom;
            this.confidence = confidence;
        }

        boolean usable() { return confidence >= 0.45f; }
    }

    private static final int N = 32;
    private static final int[][] PALETTES = {
            {0xEBECD0, 0x779556},   // Chess.com classic
            {0xEEEED2, 0x769656},   // green board
            {0xD6D6D6, 0x8B8B8B}    // greyscale
    };

    private final boolean[][][] templates = new boolean[12][][];
    private final boolean[] templateIsWhite = new boolean[12];
    private final char[] templateChar = new char[12];
    private static final char[] ORDER = {'K', 'Q', 'B', 'N', 'R', 'P'};

    BoardDetector(Context ctx) {
        int i = 0;
        for (char side : new char[]{'w', 'b'}) {
            for (char piece : ORDER) {
                templates[i] = loadMask(ctx, "" + side + piece);
                templateIsWhite[i] = side == 'w';
                templateChar[i] = side == 'w' ? piece : Character.toLowerCase(piece);
                i++;
            }
        }
    }

    Result detect(Bitmap src) {
        if (src == null || src.getWidth() < 80 || src.getHeight() < 80) return null;

        // work on a small copy for the board search
        int scale = Math.max(1, src.getWidth() / 360);
        int w = src.getWidth() / scale, h = src.getHeight() / scale;
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);

        Rect board = findBoard(px, w, h);
        small.recycle();
        if (board == null) return null;

        int left = board.left * scale, top = board.top * scale;
        int right = Math.min(src.getWidth(), board.right * scale + scale);
        int bottom = Math.min(src.getHeight(), board.bottom * scale + scale);
        int size = Math.min(right - left, bottom - top);
        right = left + size;
        bottom = top + size;
        if (right - left < 80) return null;

        int[] colors = pickPalette(px, w, h, board);
        int light = colors[0];
        int dark = colors[1];

        boolean whiteBottom = orientation(src, left, top, size, light);
        char[] placement = new char[64];
        Arrays.fill(placement, '.');
        float totalScore = 0;
        int filled = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int f = whiteBottom ? col : 7 - col;
                int r = whiteBottom ? 7 - row : row;
                int idx = r * 8 + f;
                int cs = size / 8;
                int cx = left + col * cs + cs / 2;
                int cy = top + row * cs + cs / 2;
                // a1 is a dark square; with white at the bottom, screen row 7 / col 0 is a1
                boolean isLightSquare;
                if (whiteBottom) isLightSquare = (row + col) % 2 == 1;
                else isLightSquare = (row + col) % 2 == 0;
                int bg = isLightSquare ? light : dark;

                Classified c = classify(src, cx, cy, cs, bg);
                placement[idx] = c.piece;
                if (c.piece != '.') { totalScore += c.score; filled++; }
            }
        }

        String fen = placementToFen(placement, whiteBottom ? 'w' : 'b');
        float confidence = filled == 0 ? 0 : totalScore / filled;
        return new Result(fen, left, top, right, bottom, whiteBottom, confidence);
    }

    private Classified classify(Bitmap src, int cx, int cy, int cs, int bg) {
        int crop = (int) (cs * 0.78f);
        int x0 = Math.max(0, cx - crop / 2);
        int y0 = Math.max(0, cy - crop / 2);
        if (x0 + crop > src.getWidth()) crop = src.getWidth() - x0;
        if (y0 + crop > src.getHeight()) crop = src.getHeight() - y0;
        if (crop < 8) return new Classified('.', 0);

        Bitmap patch = Bitmap.createBitmap(src, x0, y0, crop, crop);
        Bitmap small = Bitmap.createScaledBitmap(patch, N, N, true);
        if (patch != small) patch.recycle();
        int[] p = new int[N * N];
        small.getPixels(p, 0, N, 0, 0, N, N);
        small.recycle();

        boolean[][] mask = new boolean[N][N];
        long lumSum = 0;
        int masked = 0;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int c = p[y * N + x];
                int d = Math.abs(Color.red(c) - Color.red(bg))
                        + Math.abs(Color.green(c) - Color.green(bg))
                        + Math.abs(Color.blue(c) - Color.blue(bg));
                if (d > 90) {
                    mask[y][x] = true;
                    lumSum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3;
                    masked++;
                }
            }
        }
        if (masked < N * N * 0.05) return new Classified('.', 0);
        float avgLum = lumSum / (float) masked;

        char best = '.';
        float bestScore = 0;
        for (int t = 0; t < 12; t++) {
            boolean[][] tm = templates[t];
            if (tm == null) continue;
            int inter = 0, union = 0;
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    boolean a = mask[y][x], b = tm[y][x];
                    if (a && b) inter++;
                    if (a || b) union++;
                }
            }
            if (union == 0) continue;
            float iou = inter / (float) union;
            // brightness gate: white pieces are light, black pieces are dark
            boolean looksWhite = avgLum > 145;
            float colorGate = looksWhite == templateIsWhite[t] ? 1f : 0.35f;
            float score = iou * colorGate;
            if (score > bestScore) { bestScore = score; best = templateChar[t]; }
        }
        return new Classified(best, bestScore);
    }

    private static final class Classified {
        final char piece; final float score;
        Classified(char piece, float score) { this.piece = piece; this.score = score; }
    }

    // ------------------------------------------------------------- board search

    private static final class Rect {
        final int left, top, right, bottom;
        Rect(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    }

    /** Find the largest region that looks like a checkerboard of two flat colours. */
    private static Rect findBoard(int[] px, int w, int h) {
        boolean[] isBoard = new boolean[px.length];
        int count = 0;
        for (int i = 0; i < px.length; i++) {
            for (int[] pal : PALETTES) {
                if (near(px[i], pal[0], 42) || near(px[i], pal[1], 42)) {
                    isBoard[i] = true; count++;
                    break;
                }
            }
        }
        if (count < w * h * 0.04f) return null;

        int minX = w, minY = h, maxX = 0, maxY = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!isBoard[y * w + x]) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        if (bw < w * 0.25f || bh < h * 0.25f) return null;
        int size = Math.min(bw, bh);
        // keep the square, anchored to the detected corner
        return new Rect(minX, minY, minX + size, minY + size);
    }

    private static boolean near(int c, int rgb, int tol) {
        return Math.abs(Color.red(c) - Color.red(rgb))
                + Math.abs(Color.green(c) - Color.green(rgb))
                + Math.abs(Color.blue(c) - Color.blue(rgb)) < tol;
    }

    private static int[] pickPalette(int[] px, int w, int h, Rect r) {
        int[] bestPal = PALETTES[0];
        int best = -1;
        for (int[] pal : PALETTES) {
            int hits = 0;
            int cs = Math.max(1, (r.right - r.left) / 8);
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    int want = ((row + col) % 2 == 0) ? pal[0] : pal[1];
                    int x = Math.min(w - 1, r.left + col * cs + cs / 2);
                    int y = Math.min(h - 1, r.top + row * cs + cs / 2);
                    if (near(px[y * w + x], want, 55)) hits++;
                }
            }
            if (hits > best) { best = hits; bestPal = pal; }
        }
        return bestPal;
    }

    private static boolean orientation(Bitmap src, int left, int top, int size, int light) {
        int cs = size / 8;
        int p = sample(src, left + 7 * cs + cs / 2, top + 7 * cs + cs / 2);
        return near(p, light, 60); // light h1 => white on bottom
    }

    private static int sample(Bitmap b, int x, int y) {
        x = Math.max(0, Math.min(b.getWidth() - 1, x));
        y = Math.max(0, Math.min(b.getHeight() - 1, y));
        return b.getPixel(x, y);
    }

    private static String placementToFen(char[] b, char side) {
        StringBuilder sb = new StringBuilder(80);
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                char c = b[rank * 8 + file];
                if (c == '.') empty++;
                else { if (empty > 0) { sb.append(empty); empty = 0; } sb.append(c); }
            }
            if (empty > 0) sb.append(empty);
            if (rank > 0) sb.append('/');
        }
        sb.append(' ').append(side).append(" - - 0 1");
        return sb.toString();
    }

    private boolean[][] loadMask(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open("pieces/" + name + ".png")) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            Bitmap small = Bitmap.createScaledBitmap(bmp, N, N, true);
            boolean[][] mask = new boolean[N][N];
            int[] p = new int[N * N];
            small.getPixels(p, 0, N, 0, 0, N, N);
            for (int y = 0; y < N; y++)
                for (int x = 0; x < N; x++)
                    mask[y][x] = Color.alpha(p[y * N + x]) > 110;
            if (small != bmp) bmp.recycle();
            small.recycle();
            return mask;
        } catch (Exception e) {
            return null;
        }
    }
}
