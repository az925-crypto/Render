package com.catur.modmenu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModMenuService extends Service implements
        EngineController.Listener,
        BoardWatchService.PositionListener {

    private WindowManager wm;
    private View panel;
    private View arrowWrap;
    private ArrowView arrow;
    private EngineController engine;
    private BoardDetector detector;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private ImageReader reader;
    private Display display;
    private String lastFen;
    private int winWidth, winHeight;
    private int depth = 12;
    private boolean whitePlayer = true;
    private boolean running;
    public static ModMenuService instance;

    public static void analyzeFen(String fen) {
        if (instance != null) instance.onPosition(fen, "manual");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        winWidth = dm.widthPixels;
        winHeight = dm.heightPixels;
        display = wm.getDefaultDisplay();
        engine = new EngineController(this, this);
        detector = new BoardDetector(this);
        startForeground(1, notification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            depth = intent.getIntExtra("depth", depth);
            whitePlayer = intent.getBooleanExtra("whitePlayer", true);
            int skill = intent.getIntExtra("skill", 10);
            engine.setSkill(skill);
            engine.setThreads(2);
        }
        if (!running) {
            running = true;
            addPanel();
            addArrow();
            if (BoardWatchService.instance != null)
                BoardWatchService.instance.setPositionListener(this);
        }
        return START_STICKY;
    }

    private void addPanel() {
        panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 16;
        lp.y = 140;
        wm.addView(panel, lp);

        final TextView best = panel.findViewById(R.id.best);
        final TextView eval = panel.findViewById(R.id.eval);
        final TextView depthView = panel.findViewById(R.id.depth);
        final TextView pv = panel.findViewById(R.id.pv);
        statusView = panel.findViewById(R.id.status);

        best.setText("—");
        ((Button) panel.findViewById(R.id.btn_refresh)).setOnClickListener(v -> {
            if (lastFen != null) engine.analyze(lastFen, depth);
            else toast("No position yet — play a move first");
        });
        ((Button) panel.findViewById(R.id.btn_scan)).setOnClickListener(v -> scanScreen());
        panel.findViewById(R.id.btn_close).setOnClickListener(v -> stopSelf());

        View handle = panel.findViewById(R.id.drag_handle);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) panel.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = p.x; oy = p.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        p.x = ox + (int) (e.getRawX() - sx);
                        p.y = oy + (int) (e.getRawY() - sy);
                        wm.updateViewLayout(panel, p);
                        return true;
                    default: return false;
                }
            }
        });

        engineListener = new EngineController.Listener() {
            @Override public void onResult(EngineController.Result result) {
                best.setText(result.bestSan);
                String e = result.eval.startsWith("mate") ? result.eval : "eval " + result.eval;
                eval.setText(e);
                depthView.setText("d" + result.depth);
                pv.setText(result.pv);
                if (lastFen != null) drawArrow(lastFen, result.bestUci);
            }
            @Override public void onStatus(String message) { setStatus(message); }
        };
    }

    private EngineController.Listener engineListener;
    private TextView statusView;

    private void addArrow() {
        arrow = new ArrowView(this);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                winWidth, winHeight, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0; lp.y = 0;
        arrowWrap = arrow;
        wm.addView(arrow, lp);
    }

    private void drawArrow(String fen, String uci) {
        if (uci == null || uci.length() < 4) return;
        BoardDetector.Result board = lastBoard;
        if (board == null) { scanScreen(); return; }
        try {
            int from = sq(uci.substring(0, 2), board.whiteOnBottom);
            int to = sq(uci.substring(2, 4), board.whiteOnBottom);
            main.post(() -> arrow.setArrow(from, to, board.left, board.top,
                    Math.min(board.right - board.left, board.bottom - board.top)));
        } catch (Exception ignored) { }
    }

    private static int sq(String name, boolean whiteBottom) {
        int f = name.charAt(0) - 'a';
        int r = name.charAt(1) - '1';
        return whiteBottom ? r * 8 + f : (7 - r) * 8 + (7 - f);
    }

    private volatile BoardDetector.Result lastBoard;

    private void scanScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        jobs.execute(() -> {
            try {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                ImageReader r = ImageReader.newInstance(dm.widthPixels, dm.heightPixels,
                        android.graphics.PixelFormat.RGBA_8888, 2);
                reader = r;
                android.media.projection.MediaProjection mp = MediaProjectionHolder.get();
                if (mp == null) { toast("Media projection not active"); return; }
                android.hardware.display.VirtualDisplay vd = mp.createVirtualDisplay(
                        "catur-scan", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                        0, r.getSurface(), null, null);
                Thread.sleep(450);
                Image img = r.acquireLatestImage();
                if (img == null) { vd.release(); r.close(); return; }
                Image.Plane[] planes = img.getPlanes();
                int pw = planes[0].getRowStride() / planes[0].getPixelStride();
                Bitmap bmp = Bitmap.createBitmap(pw, img.getHeight(), Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(planes[0].getBuffer());
                BoardDetector.Result res = detector.detect(bmp);
                img.close();
                bmp.recycle();
                vd.release();
                r.close();
                if (res != null && res.usable()) {
                    lastBoard = res;
                    main.post(() -> {
                        setStatus("board read from screen");
                        if (lastFen == null) onPosition(res.fen, "screen");
                    });
                } else {
                    main.post(() -> toast("Board not found — check move list"));
                }
            } catch (Exception e) {
                main.post(() -> toast("scan: " + e.getMessage()));
            }
        });
    }

    // -------------------------------------------------- EngineController.Listener

    @Override
    public void onResult(final EngineController.Result result) {
        if (engineListener != null) engineListener.onResult(result);
    }

    @Override
    public void onStatus(final String message) {
        main.post(() -> setStatus(message));
    }

    private void setStatus(String s) {
        if (statusView != null) statusView.setText(s);
    }

    // ---------------------------------------------- BoardWatchService.Listener

    @Override
    public void onPosition(String fen, String source) {
        lastFen = fen;
        main.post(() -> setStatus("position updated (" + source + ")"));
        engine.analyze(fen, depth);
    }

    // ---------------------------------------------------------------- lifecycle

    private Notification notification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("catur", getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "catur")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Catur Mod Menu")
                .setContentText("Engine overlay running")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    public void onDestroy() {
        running = false;
        if (BoardWatchService.instance != null)
            BoardWatchService.instance.setPositionListener(null);
        main.post(() -> {
            try { if (panel != null) wm.removeView(panel); } catch (Exception ignored) {}
            try { if (arrowWrap != null) wm.removeView(arrowWrap); } catch (Exception ignored) {}
        });
        engine.destroy();
        jobs.shutdownNow();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
