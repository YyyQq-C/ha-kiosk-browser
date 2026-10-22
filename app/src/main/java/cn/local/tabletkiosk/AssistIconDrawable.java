package cn.local.tabletkiosk;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** 保留原麦克风透明轮廓，使用与语音面板声波一致的青紫渐变绘制。 */
final class AssistIconDrawable extends Drawable {
    private final Bitmap shape;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /** 读取现有图标作为透明蒙版，不修改原图片资源。 */
    AssistIconDrawable(Resources resources) {
        shape = BitmapFactory.decodeResource(resources, R.drawable.ic_assist_microphone);
    }

    /** 图标缩放时同步蒙版和渐变坐标，避免边缘出现底色。 */
    @Override
    protected void onBoundsChange(Rect bounds) {
        if (bounds.isEmpty()) return;
        BitmapShader mask = new BitmapShader(shape, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        matrix.setScale(bounds.width() / (float) shape.getWidth(), bounds.height() / (float) shape.getHeight());
        matrix.postTranslate(bounds.left, bounds.top);
        mask.setLocalMatrix(matrix);
        LinearGradient colors = new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.top,
                AssistWaveView.CYAN, AssistWaveView.VIOLET, Shader.TileMode.CLAMP);
        paint.setShader(new ComposeShader(colors, mask, PorterDuff.Mode.DST_IN));
    }

    /** 只在麦克风原有透明轮廓内显示渐变。 */
    @Override
    public void draw(Canvas canvas) {
        canvas.drawRect(getBounds(), paint);
    }

    /** 支持系统对图标设置透明度。 */
    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    /** 保留标准 Drawable 滤镜接口。 */
    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    /** 图标具有透明背景。 */
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** 保持原图宽高比，供 ImageView 的 FIT_CENTER 使用。 */
    @Override
    public int getIntrinsicWidth() {
        return shape.getWidth();
    }

    /** 保持原图宽高比，供 ImageView 的 FIT_CENTER 使用。 */
    @Override
    public int getIntrinsicHeight() {
        return shape.getHeight();
    }
}
