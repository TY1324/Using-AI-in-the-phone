package com.example.aichat;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/**
 * UI 主题：配色、圆角、卡片背景（纯程序化，无第三方依赖）
 */
public class UiTheme {

    public static class Palette {
        public int bg;
        public int surface;
        public int surfaceAlt;
        public int text;
        public int textSecondary;
        public int accent;
        public int accentSoft;
        public int bubbleUser;
        public int bubbleAi;
        public int divider;
        public int danger;
    }

    public static Palette light() {
        Palette p = new Palette();
        p.bg = 0xFFF6F7FB;
        p.surface = 0xFFFFFFFF;
        p.surfaceAlt = 0xFFEEF0F6;
        p.text = 0xFF1A1D26;
        p.textSecondary = 0xFF6B7280;
        p.accent = 0xFF4F46E5;
        p.accentSoft = 0xFFEEF0FF;
        p.bubbleUser = 0xFF4F46E5;
        p.bubbleAi = 0xFFFFFFFF;
        p.divider = 0xFFE5E7EF;
        p.danger = 0xFFDC2626;
        return p;
    }

    public static Palette dark() {
        Palette p = new Palette();
        p.bg = 0xFF0F1117;
        p.surface = 0xFF1A1D26;
        p.surfaceAlt = 0xFF242836;
        p.text = 0xFFE8EAF0;
        p.textSecondary = 0xFF9AA1B5;
        p.accent = 0xFF818CF8;
        p.accentSoft = 0xFF232A45;
        p.bubbleUser = 0xFF4F46E5;
        p.bubbleAi = 0xFF1F2330;
        p.divider = 0xFF2A2F3D;
        p.danger = 0xFFF87171;
        return p;
    }

    /** 圆角矩形背景 */
    public static GradientDrawable roundRect(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radius);
        d.setColor(color);
        return d;
    }

    /** 左侧大圆角气泡（聊天气泡样式） */
    public static GradientDrawable bubble(int color, float r, boolean isUser) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        float[] radii;
        if (isUser) {
            radii = new float[]{r, r, r * 0.35f, r, r, r, r * 0.35f, r};
        } else {
            radii = new float[]{r * 0.35f, r, r, r, r * 0.35f, r, r, r};
        }
        d.setCornerRadii(radii);
        d.setColor(color);
        return d;
    }

    /** 描边背景（用于次要按钮） */
    public static GradientDrawable stroke(int color, int strokeColor, float radius, int width) {
        GradientDrawable d = roundRect(color, radius);
        d.setStroke(width, strokeColor);
        return d;
    }

    /** 把颜色调亮/调暗（amount > 0 变亮，< 0 变暗） */
    public static int shade(int color, float amount) {
        int a = Color.alpha(color);
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        if (amount >= 0) {
            r = (int) (r + (255 - r) * amount);
            g = (int) (g + (255 - g) * amount);
            b = (int) (b + (255 - b) * amount);
        } else {
            float k = 1 + amount;
            r = (int) (r * k);
            g = (int) (g * k);
            b = (int) (b * k);
        }
        return Color.argb(a, clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
