package com.catur.modmenu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Transparent, touch-through layer that draws the suggested move on the board. */
final class ArrowView extends View {

    private int from = -1, to = -1;
    private int left, top, size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ArrowView(Context ctx) {
        super(ctx);
        paint.setColor(Color.parseColor("#FFC24A"));
        paint.setStrokeWidth(10f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setArrow(int fromSq, int toSq, int boardLeft, int boardTop, int boardSize) {
        from = fromSq;
        to = toSq;
        left = boardLeft;
        top = boardTop;
        size = boardSize;
        invalidate();
    }

    void clear() {
        from = -1;
        to = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (from < 0 || to < 0 || size < 8) return;
        int cs = size / 8;
        float x1 = left + (from % 8) * cs + cs / 2f;
        float y1 = top + (from / 8) * cs + cs / 2f;
        float x2 = left + (to % 8) * cs + cs / 2f;
        float y2 = top + (to / 8) * cs + cs / 2f;

        double ang = Math.atan2(y2 - y1, x2 - x1);
        float head = cs * 0.42f;
        float bx = x2 - (float) Math.cos(ang) * head;
        float by = y2 - (float) Math.sin(ang) * head;

        c.drawLine(x1, y1, bx, by, paint);
        float wing = 0.48f;
        c.drawLine(bx, by, (float) (bx - head * Math.cos(ang - wing)),
                (float) (by - head * Math.sin(ang - wing)), paint);
        c.drawLine(bx, by, (float) (bx - head * Math.cos(ang + wing)),
                (float) (by - head * Math.sin(ang + wing)), paint);
    }
}
