package com.touchpad.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collections;
import java.util.Locale;

/**
 * Full-screen touch surface that recognises Mac trackpad gestures:
 * 1 finger move / tap = click / double-tap-and-hold = drag,
 * 2 fingers scroll / pinch / rotate / tap = right click,
 * 3 fingers tap = look up, 3–4 finger swipes = Spaces / Mission Control / App Exposé,
 * 4–5 finger pinch = Apps / Show Desktop.
 */
public class TrackpadView extends View {

    public static class Settings {
        public float pointerSpeed = 1f;
        public float scrollSpeed = 1f;
        public boolean naturalScroll = true;
        public boolean tapToClick = true;
    }

    private enum Mode { NONE, POINTER, DRAG, SCROLL, PINCH, ROTATE, DONE }

    private static final int MAX_ID = 32;
    private static final long TAP_TIMEOUT = 230;
    private static final long MULTI_TAP_TIMEOUT = 300;
    private static final long DOUBLE_TAP_WINDOW = 320;

    private final Connection conn;
    private Settings settings = new Settings();
    private final float dp;

    // Per-pointer state, indexed by pointer id.
    private final float[] startX = new float[MAX_ID], startY = new float[MAX_ID];
    private final float[] lastX = new float[MAX_ID], lastY = new float[MAX_ID];
    private final boolean[] down = new boolean[MAX_ID];

    private Mode mode = Mode.NONE;
    private long gestureStart;
    private int maxPointers;
    private float maxMove;
    private int primaryId;
    private boolean tapDragCandidate;
    private long lastTapUp;
    private float pendingDx, pendingDy;
    private long lastMoveTime;
    private float smoothSpeed;

    // Multi-finger geometry
    private float gx, gy, gSpan, gAngle;           // scratch output of computeGeometry
    private float baseX, baseY, baseSpan, baseAngle; // where the current multi-finger gesture started
    private float prevX, prevY, prevSpan, prevAngle; // last frame

    // Recent scroll samples for fling velocity
    private final long[] sampleT = new long[8];
    private final float[] sampleX = new float[8], sampleY = new float[8];
    private int sampleN, sampleHead;

    // Drawing
    private final Paint padPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF padRect = new RectF();
    private String hint = "";

    public TrackpadView(Context context, Connection conn) {
        super(context);
        this.conn = conn;
        dp = getResources().getDisplayMetrics().density;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f * dp);
        borderPaint.setColor(0xFF3A3A3C);
        touchPaint.setColor(0x330A84FF);
        hintPaint.setColor(0xFF636366);
        hintPaint.setTextSize(15 * dp);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setSettings(Settings s) { settings = s; }

    public void setHint(String h) {
        hint = h == null ? "" : h;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float m = 12 * dp;
        padRect.set(m, m, w - m, h - m);
        padPaint.setShader(new LinearGradient(0, 0, 0, h, 0xFF2C2C2E, 0xFF1C1C1E, Shader.TileMode.CLAMP));
        if (Build.VERSION.SDK_INT >= 29) {
            // Keep edge swipes for ourselves instead of the system back gesture where allowed.
            setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, w, h)));
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        float r = 18 * dp;
        c.drawRoundRect(padRect, r, r, padPaint);
        c.drawRoundRect(padRect, r, r, borderPaint);
        if (!hint.isEmpty()) c.drawText(hint, padRect.centerX(), padRect.bottom - 24 * dp, hintPaint);
        for (int id = 0; id < MAX_ID; id++) {
            if (down[id]) c.drawCircle(lastX[id], lastY[id], 28 * dp, touchPaint);
        }
    }

    // ---------------------------------------------------------------- touch handling

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        long t = ev.getEventTime();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: onFirstDown(ev, t); break;
            case MotionEvent.ACTION_POINTER_DOWN: onPointerDown(ev); break;
            case MotionEvent.ACTION_MOVE: onMove(ev, t); break;
            case MotionEvent.ACTION_POINTER_UP: onPointerUp(ev, t); break;
            case MotionEvent.ACTION_UP: onLastUp(ev, t, false); break;
            case MotionEvent.ACTION_CANCEL: onLastUp(ev, t, true); break;
        }
        invalidate();
        return true;
    }

    private void onFirstDown(MotionEvent ev, long t) {
        conn.send("T"); // stops any scroll momentum, like touching a real trackpad
        for (int i = 0; i < MAX_ID; i++) down[i] = false;
        mode = Mode.NONE;
        gestureStart = t;
        maxPointers = 1;
        maxMove = 0;
        pendingDx = pendingDy = 0;
        lastMoveTime = t;
        smoothSpeed = 0;
        primaryId = ev.getPointerId(0);
        tapDragCandidate = settings.tapToClick && t - lastTapUp < DOUBLE_TAP_WINDOW;
        track(ev, 0);
    }

    private void onPointerDown(MotionEvent ev) {
        track(ev, ev.getActionIndex());
        maxPointers = Math.max(maxPointers, ev.getPointerCount());
        if (mode == Mode.POINTER) {
            // Second finger joined a cursor move: re-decide as a multi-finger gesture.
            mode = Mode.NONE;
            maxMove = 0;
        }
        if (mode == Mode.NONE) {
            resetBase(ev, -1);
        } else if (mode == Mode.SCROLL || mode == Mode.PINCH || mode == Mode.ROTATE) {
            computeGeometry(ev, -1);
            setPrev();
        }
    }

    private void onMove(MotionEvent ev, long t) {
        int n = ev.getPointerCount();
        for (int i = 0; i < n; i++) {
            int id = ev.getPointerId(i);
            if (id >= MAX_ID) continue;
            float x = ev.getX(i), y = ev.getY(i);
            maxMove = Math.max(maxMove, (float) Math.hypot(x - startX[id], y - startY[id]));
        }

        switch (mode) {
            case NONE: decide(ev, t, n); break;
            case POINTER:
            case DRAG: movePointer(ev, t); break;
            case SCROLL: {
                computeGeometry(ev, -1);
                float dx = gx - prevX, dy = gy - prevY;
                setPrev();
                addSample(t, gx, gy);
                sendScroll("C", dx, dy);
                break;
            }
            case PINCH: {
                computeGeometry(ev, -1);
                if (prevSpan > 0 && gSpan > 0) send("Z C %.4f", gSpan / prevSpan - 1f);
                setPrev();
                break;
            }
            case ROTATE: {
                computeGeometry(ev, -1);
                send("R C %.3f", -angleDelta(prevAngle, gAngle));
                setPrev();
                break;
            }
            default:
                break;
        }
        updateLast(ev);
    }

    private void decide(MotionEvent ev, long t, int n) {
        if (n == 1 && maxPointers == 1) {
            int i = ev.findPointerIndex(primaryId);
            if (i < 0) return;
            pendingDx += ev.getX(i) - lastX[primaryId];
            pendingDy += ev.getY(i) - lastY[primaryId];
            float threshold = tapDragCandidate ? 10 * dp : 2.5f * dp;
            if (maxMove > threshold) {
                if (tapDragCandidate) {
                    mode = Mode.DRAG;
                    conn.send("D 0 1");
                } else {
                    mode = Mode.POINTER;
                }
                sendMove(pendingDx, pendingDy, t);
            }
            return;
        }

        computeGeometry(ev, -1);
        float trans = (float) Math.hypot(gx - baseX, gy - baseY);
        float spanD = Math.abs(gSpan - baseSpan);

        if (n == 2) {
            float rot = Math.abs(angleDelta(baseAngle, gAngle));
            float rotArc = (float) Math.toRadians(rot) * gSpan / 2f;
            float scrollT = 7 * dp, pinchT = 22 * dp, rotT = 26 * dp;
            if (trans > scrollT && trans / scrollT >= spanD / pinchT && trans / scrollT >= rotArc / rotT) {
                mode = Mode.SCROLL;
                sampleN = 0;
                addSample(ev.getEventTime(), gx, gy);
                sendScroll("B", gx - baseX, gy - baseY);
                setPrev();
            } else if (spanD > pinchT && spanD / pinchT >= rotArc / rotT) {
                mode = Mode.PINCH;
                send("Z B %.4f", gSpan / baseSpan - 1f);
                setPrev();
            } else if (rotArc > rotT) {
                mode = Mode.ROTATE;
                send("R B %.3f", -angleDelta(baseAngle, gAngle));
                setPrev();
            }
            return;
        }

        if (n >= 3) {
            float swipeT = 55 * dp, spreadT = 60 * dp;
            if (n >= 4 && spanD > spreadT && spanD / spreadT > trans / swipeT) {
                conn.send(gSpan < baseSpan ? "K apps" : "K desktop");
                mode = Mode.DONE;
            } else if (trans > swipeT) {
                float dx = gx - baseX, dy = gy - baseY;
                String cmd;
                if (Math.abs(dx) > Math.abs(dy)) cmd = dx < 0 ? "K spaceR" : "K spaceL";
                else cmd = dy < 0 ? "K mission" : "K expose";
                conn.send(cmd);
                mode = Mode.DONE;
            }
        }
    }

    private void movePointer(MotionEvent ev, long t) {
        int i = ev.findPointerIndex(primaryId);
        if (i < 0) return;
        sendMove(ev.getX(i) - lastX[primaryId], ev.getY(i) - lastY[primaryId], t);
    }

    private void onPointerUp(MotionEvent ev, long t) {
        int idx = ev.getActionIndex();
        int id = ev.getPointerId(idx);
        int remaining = ev.getPointerCount() - 1;
        switch (mode) {
            case SCROLL:
                if (remaining < 2) {
                    endScroll(t);
                    mode = Mode.DONE;
                } else {
                    computeGeometry(ev, idx);
                    setPrev();
                }
                break;
            case PINCH:
                send("Z E %.4f", 0f);
                mode = Mode.DONE;
                break;
            case ROTATE:
                send("R E %.3f", 0f);
                mode = Mode.DONE;
                break;
            case POINTER:
            case DRAG:
                if (id == primaryId && mode == Mode.POINTER) mode = Mode.DONE;
                break;
            case NONE:
                resetBase(ev, idx);
                break;
            default:
                break;
        }
        if (id < MAX_ID) down[id] = false;
    }

    private void onLastUp(MotionEvent ev, long t, boolean cancelled) {
        long duration = t - gestureStart;
        float tapSlop = 10 * dp;
        switch (mode) {
            case DRAG: conn.send("U 0"); break;
            case SCROLL: endScroll(t); break;
            case PINCH: send("Z E %.4f", 0f); break;
            case ROTATE: send("R E %.3f", 0f); break;
            default: break;
        }

        boolean tapCandidate = !cancelled && settings.tapToClick && maxMove < tapSlop
                && (mode == Mode.NONE || mode == Mode.POINTER);
        if (tapCandidate && maxPointers == 1 && duration < TAP_TIMEOUT) {
            conn.send("C 0");
            lastTapUp = t;
        } else if (tapCandidate && maxPointers == 2 && duration < MULTI_TAP_TIMEOUT) {
            conn.send("C 1");
            lastTapUp = 0;
        } else if (tapCandidate && maxPointers == 3 && duration < MULTI_TAP_TIMEOUT) {
            conn.send("K lookup");
            lastTapUp = 0;
        } else {
            lastTapUp = 0;
        }

        mode = Mode.NONE;
        for (int i = 0; i < MAX_ID; i++) down[i] = false;
    }

    // ---------------------------------------------------------------- helpers

    private void track(MotionEvent ev, int i) {
        int id = ev.getPointerId(i);
        if (id >= MAX_ID) return;
        startX[id] = lastX[id] = ev.getX(i);
        startY[id] = lastY[id] = ev.getY(i);
        down[id] = true;
    }

    private void updateLast(MotionEvent ev) {
        for (int i = 0; i < ev.getPointerCount(); i++) {
            int id = ev.getPointerId(i);
            if (id >= MAX_ID) continue;
            lastX[id] = ev.getX(i);
            lastY[id] = ev.getY(i);
        }
    }

    private void resetBase(MotionEvent ev, int skip) {
        computeGeometry(ev, skip);
        baseX = gx; baseY = gy; baseSpan = gSpan; baseAngle = gAngle;
        setPrev();
    }

    private void setPrev() {
        prevX = gx; prevY = gy; prevSpan = gSpan; prevAngle = gAngle;
    }

    /** Centroid, average finger spread and (for the first two fingers) angle, optionally skipping one pointer. */
    private void computeGeometry(MotionEvent ev, int skip) {
        int n = ev.getPointerCount();
        float sx = 0, sy = 0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (i == skip) continue;
            sx += ev.getX(i);
            sy += ev.getY(i);
            count++;
        }
        if (count == 0) return;
        gx = sx / count;
        gy = sy / count;
        float span = 0;
        int a = -1, b = -1;
        for (int i = 0; i < n; i++) {
            if (i == skip) continue;
            span += (float) Math.hypot(ev.getX(i) - gx, ev.getY(i) - gy);
            if (a < 0) a = i; else if (b < 0) b = i;
        }
        gSpan = 2 * span / count;
        gAngle = b >= 0 ? (float) Math.toDegrees(Math.atan2(ev.getY(b) - ev.getY(a), ev.getX(b) - ev.getX(a))) : 0;
    }

    private static float angleDelta(float from, float to) {
        float d = to - from;
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        return d;
    }

    private void sendMove(float dx, float dy, long t) {
        long dt = Math.max(1, t - lastMoveTime);
        lastMoveTime = t;
        float dist = (float) Math.hypot(dx, dy) / dp;  // in dp
        float speed = Math.min(dist / dt, 6f);         // dp per ms
        smoothSpeed = smoothSpeed * 0.5f + speed * 0.5f;
        // Gentle at low speed for precision, faster when flicking — similar to macOS acceleration.
        float gain = 0.55f + 1.6f * Math.min(smoothSpeed, 2.2f);
        float k = gain * settings.pointerSpeed / dp;
        if (dx == 0 && dy == 0) return;
        send("M %.2f %.2f", dx * k, dy * k);
    }

    private float scrollFactor() {
        float k = 1.15f * settings.scrollSpeed / dp;
        return settings.naturalScroll ? k : -k;
    }

    private void sendScroll(String phase, float dx, float dy) {
        float k = scrollFactor();
        send("S " + phase + " %.2f %.2f", dx * k, dy * k);
    }

    private void endScroll(long t) {
        float vx = 0, vy = 0;
        // Velocity over the last ~80 ms; if the fingers stopped before lifting there is no fling.
        if (sampleN >= 2) {
            int newest = (sampleHead - 1 + sampleT.length) % sampleT.length;
            int oldest = newest;
            for (int k = 1; k < sampleN; k++) {
                int i = (newest - k + sampleT.length) % sampleT.length;
                if (sampleT[newest] - sampleT[i] > 80) break;
                oldest = i;
            }
            long dt = sampleT[newest] - sampleT[oldest];
            if (dt > 0 && t - sampleT[newest] < 60) {
                vx = (sampleX[newest] - sampleX[oldest]) * 1000f / dt;
                vy = (sampleY[newest] - sampleY[oldest]) * 1000f / dt;
            }
        }
        float k = scrollFactor();
        send("S E %.1f %.1f", vx * k, vy * k);
    }

    private void addSample(long t, float x, float y) {
        sampleT[sampleHead] = t;
        sampleX[sampleHead] = x;
        sampleY[sampleHead] = y;
        sampleHead = (sampleHead + 1) % sampleT.length;
        sampleN = Math.min(sampleN + 1, sampleT.length);
    }

    private void send(String fmt, float a) {
        conn.send(String.format(Locale.US, fmt, a));
    }

    private void send(String fmt, float a, float b) {
        conn.send(String.format(Locale.US, fmt, a, b));
    }
}
