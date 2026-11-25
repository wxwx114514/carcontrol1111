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
 * 方向盘控件：
 *   · 拖动手感：绕圆心转，转到哪儿停在哪儿（不自动回正）
 *   · 分级灵敏度（不粘）：硬区 ±8° 用 0.8，软区 ±30° 用 0.9，外面 1.0
 *   · 进/出硬区时给一次轻微振动（"咔哒"感）
 *   · 松手时若落在 ±8° 内，轻吸回 0°
 *   · 输出：getW() 返回 -1000~+1000（正 = 右转），带 3° 输出死区
 */
public class SteeringWheelView extends View {

    /** 满舵角度 */
    private static final float MAX_ANGLE = 135f;
    /** 硬区：进/出给振动，松手在此范围内吸回 0 */
    private static final float SNAP_ZONE = 8f;
    /** 软区 */
    private static final float SOFT_ZONE = 30f;
    /** 灵敏度系数：硬区 0.8、软区 0.9、外面 1.0 */
    private static final float SENS_HARD = 0.8f;
    private static final float SENS_SOFT = 0.9f;
    /** 输出死区 */
    private static final float DEAD_ZONE = 3f;

    private float angle = 0f;
    private float lastTouchAngle = 0f;
    private boolean dragging = false;
    private boolean inSnap = true;
    private boolean animating = false;

    private final Paint paintRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintZone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintKnob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    /** 松手后的轻吸动画 */
    private final Runnable snapAnim = new Runnable() {
        @Override
        public void run() {
            if (Math.abs(angle) < 0.4f) {
                angle = 0f;
                inSnap = true;
                animating = false;
                invalidate();
                return;
            }
            angle *= 0.6f;
            invalidate();
            postDelayed(snapAnim, 16);
        }
    };

    public SteeringWheelView(Context context) {
        super(context);
        setFocusable(true);

        paintRing.setStyle(Paint.Style.STROKE);
        paintRing.setStrokeWidth(10f);
        paintRing.setColor(0xFF37474F);

        paintTick.setStyle(Paint.Style.STROKE);
        paintTick.setStrokeWidth(3f);
        paintTick.setColor(0xFF90A4AE);

        paintZone.setStyle(Paint.Style.STROKE);
        paintZone.setStrokeWidth(14f);
        paintZone.setColor(0xFF2E7D32);

        paintKnob.setStyle(Paint.Style.FILL);
        paintKnob.setColor(0xFF00897B);

        paintText.setTextAlign(Paint.Align.CENTER);
        paintText.setColor(Color.WHITE);
    }

    public float getAngle() {
        return angle;
    }

    /** 转向量 -1000~+1000（正 = 右转） */
    public int getW() {
        float a = angle;
        if (Math.abs(a) < DEAD_ZONE) return 0;
        a = (a > 0) ? (a - DEAD_ZONE) : (a + DEAD_ZONE);
        float span = MAX_ANGLE - DEAD_ZONE;
        int w = Math.round(a / span * 1000f);
        if (w > 1000) w = 1000;
        if (w < -1000) w = -1000;
        return w;
    }

    public void recenter() {
        removeCallbacks(snapAnim);
        animating = false;
        angle = 0f;
        inSnap = true;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int size = Math.min(w, h);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 26f;
        if (r < 40f) r = 40f;

        c.drawCircle(cx, cy, r, paintRing);

        for (int deg = -135; deg <= 135; deg += 15) {
            double rad = Math.toRadians(deg);
            float len = (deg % 45 == 0) ? 18f : 9f;
            float x1 = (float) (cx + (r - len) * Math.sin(rad));
            float y1 = (float) (cy - (r - len) * Math.cos(rad));
            float x2 = (float) (cx + r * Math.sin(rad));
            float y2 = (float) (cy - r * Math.cos(rad));
            c.drawLine(x1, y1, x2, y2, paintTick);
        }

        /* 硬区(±8°)和软区(±30°)的范围提示 */
        arcRect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(arcRect, 270f - SNAP_ZONE, SNAP_ZONE * 2f, false, paintZone);
        c.drawArc(arcRect, 270f - SOFT_ZONE, SOFT_ZONE * 2f, false, paintTick);

        paintTick.setColor(0xFF4DB6AC);
        paintTick.setStrokeWidth(6f);
        c.drawLine(cx, cy - r, cx, cy - r + 30f, paintTick);
        paintTick.setColor(0xFF90A4AE);
        paintTick.setStrokeWidth(3f);

        double rad = Math.toRadians(angle);
        float kx = (float) (cx + r * 0.72f * Math.sin(rad));
        float ky = (float) (cy - r * 0.72f * Math.cos(rad));
        c.drawCircle(cx, cy, r * 0.55f, paintRing);
        c.drawCircle(kx, ky, 26f, paintKnob);

        paintText.setTextSize(r * 0.22f);
        String s = (angle >= 0 ? "+" : "") + Math.round(angle) + "°";
        c.drawText(s, cx, cy + r * 0.08f, paintText);
        paintText.setTextSize(r * 0.11f);
        c.drawText("W = " + getW(), cx, cy + r * 0.28f, paintText);
        paintText.setTextSize(r * 0.09f);
        paintText.setColor(0xFFB0BEC5);
        c.drawText("左转", cx - r * 0.62f, cy + r + 18f, paintText);
        c.drawText("右转", cx + r * 0.62f, cy + r + 18f, paintText);
        paintText.setColor(Color.WHITE);
    }

    private float touchAngle(float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        return (float) Math.toDegrees(Math.atan2(x - cx, cy - y));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float dx = e.getX() - cx;
                float dy = e.getY() - cy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float r = Math.min(cx, cy) - 26f;
                if (dist > r + 60f) return false;

                removeCallbacks(snapAnim);
                animating = false;
                dragging = true;
                lastTouchAngle = touchAngle(e.getX(), e.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;

                float a = touchAngle(e.getX(), e.getY());
                float delta = a - lastTouchAngle;
                while (delta > 180f) delta -= 360f;
                while (delta < -180f) delta += 360f;

                /* 分级灵敏度：硬区 0.8、软区 0.9、外面 1.0 */
                float absA = Math.abs(angle);
                float sens = 1f;
                if (absA < SNAP_ZONE)      sens = SENS_HARD;
                else if (absA < SOFT_ZONE) sens = SENS_SOFT;

                angle += delta * sens;
                if (angle > MAX_ANGLE) angle = MAX_ANGLE;
                if (angle < -MAX_ANGLE) angle = -MAX_ANGLE;

                /* 进/出硬区给一次"咔哒"，用手感提示中心位置 */
                boolean nowIn = (Math.abs(angle) < SNAP_ZONE);
                if (nowIn != inSnap) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    inSnap = nowIn;
                }

                lastTouchAngle = a;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                /* 松手时在硬区内就轻吸回 0° */
                if (Math.abs(angle) > 0.1f && Math.abs(angle) < SNAP_ZONE) {
                    if (!animating) {
                        animating = true;
                        post(snapAnim);
                    }
                }
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }
}
