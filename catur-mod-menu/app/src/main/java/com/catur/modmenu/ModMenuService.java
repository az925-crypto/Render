package com.catur.modmenu;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
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
    private VirtualDisplay virtualDisplay;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private String lastFen;
    private volatile EngineController.Result lastResult;
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
        engine = new EngineController(this, this);
        detector = new BoardDetector(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent == null ? Activity.RESULT_CANCELED
                : intent.getIntExtra("projectionResult", Activity.RESULT_CANCELED);
        Intent token = intent == null ? null : intent.getParcelableExtra("projectionData");
        boolean hasCapture = resultCode == Activity.RESULT_OK && token != null;

        // startForeground must happen before getMediaProjection() on Android 14+.
        int typeFlags = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        if (hasCapture && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            typeFlags |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        startForegroundCompat(typeFlags);

        if (intent != null) {
            depth = intent.getIntExtra("depth", depth);
            whitePlayer = intent.getBooleanExtra("whitePlayer", true);
            engine.setSkill(intent.getIntExtra("skill", 10));
            engine.setThreads(2);
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("overlay permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (hasCapture) initProjection(resultCode, token);

        if (!running) {
            running = true;
            try {
                addPanel();
                addArrow();
            } catch (Exception e) {
                toast("overlay failed: " + e.getMessage());
                stopSelf();
                return START_NOT_STICKY;
            }
            if (BoardWatchService.instance != null)
                BoardWatchService.instance.setPositionListener(this);
        }
        // The MediaProjection token is single-use: never let the system
        // restart us with a null intent and a dead capture session.
        return START_NOT_STICKY;
    }

    private void startForegroundCompat(int typeFlags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notification(), typeFlags);
        else
            startForeground(1, notification());
    }

    private void initProjection(int resultCode, Intent token) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, token);

        // Android 14+ requires a callback before createVirtualDisplay().
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                main.post(() -> {
                    setStatus("screen capture stopped");
                    releaseCapture();
                    MediaProjectionHolder.clear();
                });
            }
        };
        projection.registerCallback(projectionCallback, main);

        MediaProjectionHolder.set(projection);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels,
                android.graphics.PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay("catur-scan",
                dm.widthPixels, dm.heightPixels, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, main);
    }

    private void releaseCapture() {
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
        virtualDisplay = null;
        reader = null;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0; lp.y = 0;
        arrowWrap = arrow;
        wm.addView(arrow, lp);
    }

    private void drawArrow(String fen, String uci) {
        if (uci == null || uci.length() < 4 || arrow == null) return;
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
        if (reader == null || virtualDisplay == null) {
            toast("screen capture not active");
            return;
        }
        jobs.execute(() -> {
            Image img = null;
            Bitmap bmp = null;
            try {
                // Let the persistent VirtualDisplay produce a fresh frame.
                Thread.sleep(300);
                img = reader.acquireLatestImage();
                if (img == null) { toast("no frame yet — tap again"); return; }
                Image.Plane[] planes = img.getPlanes();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int width = Math.min(img.getWidth(), rowStride / pixelStride);
                int height = img.getHeight();
                Bitmap raw = Bitmap.createBitmap(rowStride / pixelStride, height,
                        Bitmap.Config.ARGB_8888);
                raw.copyPixelsFromBuffer(planes[0].getBuffer());
                bmp = width == raw.getWidth() ? raw
                        : Bitmap.createBitmap(raw, 0, 0, width, height);
                if (bmp != raw) raw.recycle();

                BoardDetector.Result res = detector.detect(bmp);
                final BoardDetector.Result found = res;
                if (res != null && res.usable()) {
                    lastBoard = res;
                    main.post(() -> {
                        setStatus("board read from screen");
                        EngineController.Result last = lastResult;
                        if (last != null) drawArrow(last.fen, last.bestUci);
                        if (lastFen == null) onPosition(found.fen, "screen");
                    });
                } else {
                    main.post(() -> toast("Board not found — check move list"));
                }
            } catch (Exception e) {
                main.post(() -> toast("scan: " + e.getMessage()));
            } finally {
                if (img != null) { try { img.close(); } catch (Exception ignored) {} }
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            }
        });
    }

    // -------------------------------------------------- EngineController.Listener

    @Override
    public void onResult(final EngineController.Result result) {
        lastResult = result;
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
        if (!"manual".equals(source)) {
            int sp = fen.indexOf(' ');
            char side = sp >= 0 && sp + 1 < fen.length() ? fen.charAt(sp + 1) : '?';
            if ((side == 'w') != whitePlayer) {
                main.post(() -> setStatus("opponent's turn"));
                return;
            }
        }
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

    private void toast(final String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        running = false;
        if (BoardWatchService.instance != null)
            BoardWatchService.instance.setPositionListener(null);

        // onDestroy runs on the main thread: remove synchronously, no post.
        try { if (panel != null) wm.removeViewImmediate(panel); } catch (Exception ignored) {}
        try { if (arrowWrap != null) wm.removeViewImmediate(arrowWrap); } catch (Exception ignored) {}
        panel = null;
        arrowWrap = null;
        arrow = null;

        releaseCapture();
        if (projection != null) {
            if (projectionCallback != null) {
                try { projection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
            }
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        MediaProjectionHolder.clear();

        engine.destroy();
        jobs.shutdownNow();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
