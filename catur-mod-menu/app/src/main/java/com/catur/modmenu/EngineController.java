package com.catur.modmenu;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Drives a bundled Stockfish UCI binary living in app-private storage. */
final class EngineController {

    interface Listener {
        void onResult(Result result);
        void onStatus(String message);
    }

    static final class Result {
        final String fen;
        final String bestUci;
        final String bestSan;
        final String eval;
        final String pv;
        final int depth;

        Result(String fen, String bestUci, String bestSan, String eval, String pv, int depth) {
            this.fen = fen;
            this.bestUci = bestUci;
            this.bestSan = bestSan;
            this.eval = eval;
            this.pv = pv;
            this.depth = depth;
        }

        boolean valid() { return bestUci != null && !bestUci.isEmpty(); }
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final Object lock = new Object();

    private File binary;
    private Process process;
    private OutputStream stdin;
    private Thread reader;
    private volatile boolean ready;
    private volatile boolean destroyed;
    private int skill = 10;
    private int threads = 2;

    EngineController(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    void setSkill(int value) { this.skill = Math.max(0, Math.min(20, value)); }
    void setThreads(int value) { this.threads = Math.max(1, Math.min(8, value)); }

    void analyze(final String fen, final int depth) {
        jobs.execute(() -> {
            try {
                ensureStarted();
                drain();
                send("stop");
                send("setoption name Skill Level value " + skill);
                send("setoption name Threads value " + threads);
                send("position fen " + fen);
                send("go depth " + depth);

                String best = null;
                String eval = null;
                String pv = null;
                int gotDepth = 0;
                long deadline = System.currentTimeMillis() + 25000L;

                while (System.currentTimeMillis() < deadline) {
                    String line = lines.poll(500, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        if (process != null && !process.isAlive()) throw new IOException("engine exited");
                        continue;
                    }
                    if (line.startsWith("info ")) {
                        int d = intAfter(line, " depth ");
                        if (d > gotDepth) {
                            gotDepth = d;
                            String score = scoreAfter(line);
                            if (score != null) eval = score;
                            int pvIdx = line.indexOf(" pv ");
                            if (pvIdx >= 0) pv = line.substring(pvIdx + 4).trim();
                        }
                    } else if (line.startsWith("bestmove")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length > 1) best = parts[1];
                        break;
                    }
                }

                if (best == null) {
                    send("stop");
                    throw new IOException("engine timeout");
                }

                Chess.Position p = Chess.Position.fromFen(fen);
                Chess.Move m = Chess.fromUci(p, best);
                String san = m == null ? best : Chess.san(p, m);
                String pvSan = sanFromPv(fen, pv);
                Result r = new Result(fen, best, san, eval == null ? "—" : eval,
                        pvSan == null ? "" : pvSan, gotDepth);
                main.post(() -> listener.onResult(r));
            } catch (Exception e) {
                ready = false;
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> listener.onStatus("engine: " + msg));
            }
        });
    }

    private static String sanFromPv(String fen, String pv) {
        if (pv == null || pv.trim().isEmpty()) return null;
        Chess.Position p = Chess.Position.fromFen(fen);
        StringBuilder out = new StringBuilder();
        for (String uci : pv.trim().split("\\s+")) {
            Chess.Move m = Chess.fromUci(p, uci);
            if (m == null) break;
            if (out.length() > 0) out.append(' ');
            out.append(Chess.san(p, m));
            Chess.applySan(p, Chess.sanBase(p, m)); // keep the same position in sync
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static int intAfter(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return 0;
        i += key.length();
        int j = i;
        while (j < line.length() && Character.isDigit(line.charAt(j))) j++;
        try { return Integer.parseInt(line.substring(i, j)); } catch (Exception e) { return 0; }
    }

    private static String scoreAfter(String line) {
        int i = line.indexOf(" score ");
        if (i < 0) return null;
        String rest = line.substring(i + 7);
        String[] p = rest.split("\\s+");
        if (p.length < 2) return null;
        if (p[0].equals("mate")) return "mate " + p[1];
        if (p[0].equals("cp")) {
            int cp;
            try { cp = Integer.parseInt(p[1]); } catch (Exception e) { return null; }
            return String.format(Locale.US, "%+.2f", cp / 100.0);
        }
        return null;
    }

    private void ensureStarted() throws IOException {
        synchronized (lock) {
            if (ready && process != null && process.isAlive()) return;
            shutdownProcess();
            binary = prepareBinary();
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
            pb.redirectErrorStream(true);
            process = pb.start();
            stdin = process.getOutputStream();

            reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) lines.offer(line);
                } catch (IOException ignored) {
                } finally {
                    lines.offer("__engine_dead__");
                }
            }, "stockfish-reader");
            reader.setDaemon(true);
            reader.start();

            send("uci");
            waitFor("uciok", 6000);
            send("isready");
            waitFor("readyok", 6000);
            send("setoption name Skill Level value " + skill);
            send("setoption name Threads value " + threads);
            send("ucinewgame");
            ready = true;
            main.post(() -> listener.onStatus("engine ready"));
        }
    }

    private static final String ENGINE_URL =
            "https://github.com/official-stockfish/Stockfish/releases/download/sf_19/stockfish-android-arm64-universal.tar.gz";
    private static final long ENGINE_MIN = 90L * 1024 * 1024; // extracted sf_19 arm64 is ~97 MB

    private File prepareBinary() throws IOException {
        File out = new File(ctx.getFilesDir(), "stockfish");
        if (out.exists() && out.length() > ENGINE_MIN) return out;

        // The engine is not bundled in the APK (keeps the download small).
        // Prefer the cached copy delivered by the build; otherwise fetch it.
        File cached = new File(ctx.getCacheDir(), "engine.tar.gz");
        if (!cached.exists() || cached.length() < 50L * 1024 * 1024) {
            main.post(() -> listener.onStatus("downloading engine (~80 MB), first run only"));
            download(ENGINE_URL, cached);
        }

        File stage = new File(ctx.getFilesDir(), "engine_extract");
        stage.mkdirs();
        untarGz(cached, stage, "stockfish/stockfish-android-arm64-universal", out);
        cached.delete();
        deleteTree(stage);

        if (!out.setExecutable(true, true) && !out.setExecutable(true, false))
            throw new IOException("chmod failed");
        return out;
    }

    private static void download(String url, File dst) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "CaturModMenu/1.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
        try (InputStream in = c.getInputStream(); FileOutputStream f = new FileOutputStream(dst)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) f.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (dst.length() < 50L * 1024 * 1024) throw new IOException("engine download incomplete");
    }

    /** Extract one named entry from a tar.gz without pulling in extra dependencies. */
    private static void untarGz(File archive, File dest, String entry, File out) throws IOException {
        try (InputStream fi = new java.io.FileInputStream(archive);
             java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(fi)) {
            byte[] hdr = new byte[512];
            while (readFully(gz, hdr, 512)) {
                int len = octal(hdr, 124, 12);
                String name = new String(hdr, 0, 100, StandardCharsets.UTF_8).trim();
                if (name.startsWith("./")) name = name.substring(2);
                if (name.equals(entry)) {
                    try (FileOutputStream f = new FileOutputStream(out)) {
                        byte[] buf = new byte[64 * 1024];
                        int left = len;
                        while (left > 0) {
                            int n = gz.read(buf, 0, Math.min(buf.length, left));
                            if (n <= 0) throw new IOException("truncated engine archive");
                            f.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    return;
                }
                long skip = ((len + 511) / 512) * 512L;
                while (skip > 0) skip -= gz.skip(skip);
            }
        }
        throw new IOException("engine entry not found in archive");
    }

    private static boolean readFully(InputStream in, byte[] b, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) return off > 0;
            off += r;
        }
        return true;
    }

    private static int octal(byte[] b, int off, int len) {
        int v = 0;
        for (int i = off; i < off + len && b[i] >= '0' && b[i] <= '7'; i++)
            v = v * 8 + (b[i] - '0');
        return v;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void send(String cmd) throws IOException {
        if (stdin == null) throw new IOException("engine not started");
        stdin.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private void waitFor(String token, long timeout) throws IOException {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            String line;
            try { line = lines.poll(300, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            if (line == null) continue;
            if (line.equals("__engine_dead__")) throw new IOException("engine exited during handshake");
            if (line.equals(token) || line.startsWith(token)) return;
        }
        throw new IOException("handshake timeout: " + token);
    }

    private void drain() { lines.clear(); }

    private void shutdownProcess() {
        try { if (stdin != null) { stdin.write("quit\n".getBytes(StandardCharsets.UTF_8)); stdin.flush(); } }
        catch (Exception ignored) {}
        if (process != null) {
            process.destroy();
            try { if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        process = null;
        stdin = null;
        lines.clear();
    }

    void destroy() {
        destroyed = true;
        jobs.shutdownNow();
        shutdownProcess();
    }

    boolean isDestroyed() { return destroyed; }
}
