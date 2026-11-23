package com.example.carcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * 前后滑块（油门/倒车）：
 *   · 上 = 前进(+1000)，下 = 后退(-1000)，中间是 0
 *   · 松手保持当前位置（不自动回零），要靠"急停"或手动拖回 0
 *   · 0 附近有吸附：|v| < 40‰ 直接归 0，并给一次轻微振动
 *   · 带刻度：每 10% 一小格，每 20% 长格 + 数字标注
 */
public class TickSliderView extends View {

    private static final int MIN_V = -1000;
    private static final int MAX_V = 1000;
    /** 0 附近吸附范围（‰） */
    private static final int SNAP_V = 40;

    private int value = 0;
    private boolean dragging = false;
    private boolean inSnap = true;

    private final Paint paintTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintMajor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    public TickSliderView(Context context) {
        super(context);
        setFocusable(true);

        paintTrack.setStyle(Paint.Style.STROKE);
        paintTrack.setStrokeWidth(10f);
        paintTrack.setColor(0xFF455A64);

        paintFill.setStyle(Paint.Style.FILL);
        paintFill.setColor(0x3300BFA5);

        paintTick.setStrokeWidth(2f);
        paintTick.setColor(0xFF78909C);

        paintMajor.setStrokeWidth(3f);
        paintMajor.setColor(0xFFB0BEC5);

        paintThumb.setStyle(Paint.Style.FILL);
        paintThumb.setColor(0xFF00897B);

        paintText.setColor(Color.WHITE);
        paintText.setTextAlign(Paint.Align.RIGHT);
    }

    /** -1000 ~ +1000 */
    public int getValue() {
        return value;
    }

    public void setValue(int v) {
        value = clamp(v);
        invalidate();
    }

    private int clamp(int v) {
        if (v > MAX_V) return MAX_V;
        if (v < MIN_V) return MIN_V;
        return v;
    }

    private int topY() {
        return (int) (getPaddingTop() + 34f);
    }

    private int bottomY() {
        return getHeight() - 34;
    }

    private float trackX() {
        return getWidth() * 0.66f;
    }

    private float valueToY(int v) {
        float t = (float) (MAX_V - v) / (MAX_V - MIN_V);       // 0 在顶，1 在底
        return topY() + t * (bottomY() - topY());
    }

    private int yToValue(float y) {
        float span = bottomY() - topY();
        if (span <= 0) return 0;
        float t = (y - topY()) / span;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return Math.round(MAX_V - t * (MAX_V - MIN_V));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        float x = trackX();
        int yTop = topY();
        int yBot = bottomY();

        // 轨道
        c.drawLine(x, yTop, x, yBot, paintTrack);

        // 0 位置的中线（加粗提示）
        float y0 = valueToY(0);
        paintMajor.setColor(0xFF4DB6AC);
        paintMajor.setStrokeWidth(6f);
        c.drawLine(x - 26f, y0, x + 26f, y0, paintMajor);
        paintMajor.setColor(0xFFB0BEC5);
        paintMajor.setStrokeWidth(3f);

        // 刻度：每 100‰（10%）一格，每 200‰ 加长并标数字
        paintText.setTextSize(getWidth() * 0.10f);
        for (int v = MIN_V; v <= MAX_V; v += 100) {
            float y = valueToY(v);
            boolean major = (v % 200 == 0);
            float len = major ? 26f : 14f;

            if (major) {
                c.drawLine(x - len, y, x, y, paintMajor);
            } else {
                c.drawLine(x - len, y, x, y, paintTick);
            }

            if (major) {
                String s = (v > 0 ? "+" : "") + (v / 10) + "%";
                c.drawText(s, x - 34f, y + paintText.getTextSize() * 0.35f, paintText);
            }
        }

        // 当前值填充（从 0 到当前值）
        float yv = valueToY(value);
        tmp.set(x - 12f, Math.min(y0, yv), x + 12f, Math.max(y0, yv));
        c.drawRoundRect(tmp, 12f, 12f, paintFill);

        // 拇指块
        tmp.set(x - 48f, yv - 20f, x + 48f, yv + 20f);
        c.drawRoundRect(tmp, 12f, 12f, paintThumb);

        // 当前值标签
        paintText.setTextAlign(Paint.Align.CENTER);
        paintText.setTextSize(getWidth() * 0.13f);
        String s = "V = " + value;
        c.drawText(s, getWidth() / 2f, yTop - 10f, paintText);
        paintText.setTextAlign(Paint.Align.RIGHT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                dragging = true;
                int v = yToValue(e.getY());

                // 0 附近吸附
                if (Math.abs(v) < SNAP_V) {
                    if (!inSnap) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        inSnap = true;
                    }
                    v = 0;
                } else {
                    inSnap = false;
                }

                value = v;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }
}
