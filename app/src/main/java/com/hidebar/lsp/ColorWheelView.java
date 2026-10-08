package com.hidebar.lsp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * HSV 色轮：角度 = 色相，半径 = 饱和度（和 MIUI「色彩风格」里那个色温盘一个手感）。
 * <p>
 * 三个通道里只有色相/饱和度由这个控件决定；「深浅」是外层的不透明度滑块，
 * 这里把它做成一层黑色蒙版画上去，让色轮看上去就是最终效果的深浅，
 * 免得用户在色轮上挑了个颜色、实际出来是半透明的，觉得"对不上"。
 * <p>
 * 实现上把「色相 × 饱和度」预渲染成一张位图缓存起来（只在尺寸变化时重建），
 * 拖动深浅时只改蒙版 alpha，不会每帧重算 26 万像素。
 */
public class ColorWheelView extends View {

    public interface OnPickListener {
        void onPick(float hue, float sat, boolean fromUser);
    }

    /** 色相 0..360，0 = 正右方，顺时针增大 */
    private float hue = 210f;
    /** 饱和度 0..1，0 = 圆心（白），1 = 边缘（最艳） */
    private float sat = 0f;
    /**
     * 明度（HSV 的 V）0..1。v2.1.3 新增：只有它才能表达「纯黑」——
     * 老版本把色轮当成纯 H/S 盘，V 恒为 1，于是"纯黑"按下去算出来的是白色，
     * 在白底界面上肉眼完全看不出变化（用户报的"纯黑没生效"就是这么来的）。
     */
    private float val = 1f;
    /** 深浅 0..1，只影响蒙版，不影响取色 */
    private float alpha = 1f;

    private Bitmap disc;
    private int discSize = 0;

    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** v2.1.3：明度蒙版（V<1 压黑）与不透明度蒙版（alpha<1 提白）分开画 */
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    private OnPickListener listener;
    private boolean tracking = false;

    private final float density;

    public ColorWheelView(Context c) {
        this(c, null);
    }

    public ColorWheelView(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3f));
        ringPaint.setColor(0xFFFFFFFF);
        ringShadow.setStyle(Paint.Style.STROKE);
        ringShadow.setStrokeWidth(dp(5f));
        ringShadow.setColor(0x66000000);
        scrimPaint.setStyle(Paint.Style.FILL);
        scrimPaint.setColor(0xFF000000);
        whitePaint.setStyle(Paint.Style.FILL);
        whitePaint.setColor(0xFFFFFFFF);
    }

    public void setOnPickListener(OnPickListener l) {
        listener = l;
    }

    /** 外部（预设按钮 / 读取当前配置）驱动，不回调 listener。 */
    public void setHsv(float h, float s) {
        setHsv(h, s, 1f);
    }

    /** v2.1.3：带明度 V 的版本，纯黑靠 v=0 表达。 */
    public void setHsv(float h, float s, float v) {
        hue = ((h % 360f) + 360f) % 360f;
        sat = clamp01(s);
        val = clamp01(v);
        invalidate();
    }

    public void setDepth(float a) {
        alpha = clamp01(a);
        invalidate();
    }

    public float hue() {
        return hue;
    }

    public float sat() {
        return sat;
    }

    public float value() {
        return val;
    }

    /** 当前选中的颜色（含 Alpha），供预览与写配置用。 */
    public int color(int alpha255) {
        return Color.HSVToColor(alpha255, new float[]{hue, sat, val});
    }

    private float dp(float v) {
        return v * density;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    protected void onDraw(Canvas cv) {
        final int w = getWidth();
        final int h = getHeight();
        final int size = Math.min(w, h);
        if (size <= 0) return;

        // 色轮外接正方形居中
        dst.set((w - size) / 2f, (h - size) / 2f, (w + size) / 2f, (h + size) / 2f);
        final float cx = dst.centerX();
        final float cy = dst.centerY();
        final float r = size / 2f;

        ensureDisc(size);
        if (disc != null) cv.drawBitmap(disc, null, dst, bmpPaint);

        // 明度：V<1 压黑 —— 纯黑（V=0）时整盘就是黑的，和用户按下的预设对得上
        if (val < 0.999f) {
            scrimPaint.setAlpha((int) ((1f - val) * 255f));
            cv.drawCircle(cx, cy, r, scrimPaint);
        }
        // 不透明度：alpha<1 提白 —— 关闭时整盘发白，表示"这层没了"
        if (alpha < 0.999f) {
            whitePaint.setAlpha((int) ((1f - alpha) * 255f));
            cv.drawCircle(cx, cy, r, whitePaint);
        }

        // 当前取色点
        final double rad = Math.toRadians(hue);
        final float px = cx + (float) (Math.cos(rad) * r * sat);
        final float py = cy + (float) (Math.sin(rad) * r * sat);
        cv.drawCircle(px, py, dp(7f), ringShadow);
        cv.drawCircle(px, py, dp(7f), ringPaint);
        // 圆点内部填当前颜色，浅色时也能看见
        Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        inner.setStyle(Paint.Style.FILL);
        inner.setColor(Color.HSVToColor(new float[]{hue, sat, val}));
        cv.drawCircle(px, py, dp(4.5f), inner);
    }

    /** 预渲染「色相 × 饱和度」盘面，值固定 1，深浅靠蒙版。 */
    private void ensureDisc(int size) {
        if (disc != null && discSize == size) return;
        final int n = size;
        final int[] px = new int[n * n];
        final float r = n / 2f;
        final float r2 = r * r;
        final float[] hsv = new float[3];
        for (int y = 0; y < n; y++) {
            final float dy = y - r + 0.5f;
            for (int x = 0; x < n; x++) {
                final float dx = x - r + 0.5f;
                final float d2 = dx * dx + dy * dy;
                final int i = y * n + x;
                if (d2 > r2) {
                    px[i] = 0;
                    continue;
                }
                final float d = (float) Math.sqrt(d2);
                hsv[0] = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (hsv[0] < 0f) hsv[0] += 360f;
                hsv[1] = Math.min(1f, d / r);
                hsv[2] = 1f;
                // 最外 1px 做点羽化，圆边不会出锯齿
                final float edge = Math.max(0f, Math.min(1f, r - d));
                int c = Color.HSVToColor(hsv);
                px[i] = (c & 0x00FFFFFF) | (((int) (edge * 255f)) << 24);
            }
        }
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, n, 0, 0, n, n);
        disc = b;
        discSize = n;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                pick(e.getX(), e.getY(), true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracking) pick(e.getX(), e.getY(), true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void pick(float x, float y, boolean fromUser) {
        final float cx = dst.centerX();
        final float cy = dst.centerY();
        final float r = dst.width() / 2f;
        if (r <= 0f) return;
        final float dx = x - cx;
        final float dy = y - cy;
        final float d = (float) Math.sqrt(dx * dx + dy * dy);
        float h = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (h < 0f) h += 360f;
        hue = h;
        sat = Math.min(1f, d / r);
        invalidate();
        if (fromUser && listener != null) listener.onPick(hue, sat, true);
    }
}
