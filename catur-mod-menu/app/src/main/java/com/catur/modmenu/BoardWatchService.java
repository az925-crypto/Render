package com.catur.modmenu;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Follows the move list shown by the chess app. The list is plain text in most
 * chess UIs, so rebuilding the position from SAN is far more reliable than
 * reading pixels. Falls back to whatever the caller can supply otherwise.
 */
public class BoardWatchService extends AccessibilityService {

    public interface PositionListener {
        void onPosition(String fen, String source);
    }

    public static BoardWatchService instance;
    private PositionListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile String lastFen;
    private static final long POLL_MS = 900;
    private static final Pattern MOVE_NUMBER = Pattern.compile("^\\d+\\.{1,3}$");
    private static final Pattern RESULT = Pattern.compile("^(1-0|0-1|1/2-1/2|\\d+/\\d+)$");

    public void setPositionListener(PositionListener l) { this.listener = l; }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        handler.post(this::tick);
    }

    private void tick() {
        try { scan(); } catch (Exception ignored) {}
        handler.postDelayed(this::tick, POLL_MS);
    }

    private void scan() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        List<String> texts = new ArrayList<>();
        collect(root, texts, 0);
        String fen = reconstruct(texts);
        if (fen != null && !fen.equals(lastFen)) {
            lastFen = fen;
            if (listener != null) listener.onPosition(fen, "moves");
        }
    }

    private static void collect(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 24) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0 && t.length() < 80) out.add(t.toString());
        int n = node.getChildCount();
        for (int i = 0; i < n && i < 200; i++) collect(node.getChild(i), out, depth + 1);
    }

    /** Try a few offsets into the token stream; keep the longest valid SAN run. */
    static String reconstruct(List<String> texts) {
        List<String> tokens = new ArrayList<>();
        for (String text : texts) {
            String[] parts = text.split("[\\s\\u00a0,;:|\\u2022]+");
            for (String part : parts) {
                String s = clean(part);
                if (s.isEmpty()) continue;
                if (MOVE_NUMBER.matcher(s).matches() || RESULT.matcher(s).matches()) continue;
                tokens.add(s);
            }
        }
        if (tokens.size() < 2) return null;
        if (tokens.size() > 120) tokens = tokens.subList(tokens.size() - 120, tokens.size());

        String bestFen = null;
        int bestLen = 0;
        int maxStart = Math.min(8, tokens.size() - 2);
        for (int start = 0; start <= maxStart; start++) {
            Chess.Position p = Chess.Position.start();
            int n = 0;
            for (int i = start; i < tokens.size(); i++) {
                if (!Chess.applySan(p, tokens.get(i))) break;
                n++;
            }
            if (n > bestLen) { bestLen = n; bestFen = p.toFen(); }
        }
        return bestLen >= 2 ? bestFen : null;
    }

    private static String clean(String s) {
        String t = s.trim();
        if (t.length() > 12) return "";
        t = t.replace("0-0-0", "O-O-O").replace("0-0", "O-O");
        // keep only characters that can appear in SAN
        StringBuilder b = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            if ((c >= 'a' && c <= 'h') || (c >= '1' && c <= '8')
                    || c == 'K' || c == 'Q' || c == 'R' || c == 'B' || c == 'N'
                    || c == 'O' || c == 'x' || c == '=' || c == '+' || c == '#'
                    || c == '!' || c == '?' || c == '-') b.append(c);
            else break;
        }
        return b.toString();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The polling loop above is the source of truth; events only nudge it.
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
