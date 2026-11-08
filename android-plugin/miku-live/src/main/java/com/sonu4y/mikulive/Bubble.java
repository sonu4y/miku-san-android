package com.sonu4y.mikulive;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Small floating Miku bubble shown over other apps while live mode runs.
 * The ring colour shows what she is doing; tap to open the app, drag to move,
 * long-press to stop live mode.
 */
class Bubble {
    private final LiveService service;
    private final WindowManager wm;
    private FrameLayout view;
    private GradientDrawable ring;
    private WindowManager.LayoutParams lp;
    private boolean shown = false;

    Bubble(LiveService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    private int dp(float v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }

    private void build() {
        int size = dp(64);
        view = new FrameLayout(service);
        ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.parseColor("#E6101820"));
        ring.setStroke(dp(4), Color.parseColor("#6070D6"));
        view.setBackground(ring);
        ImageView face = new ImageView(service);
        face.setImageResource(service.getApplicationInfo().icon);
        face.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int pad = dp(8);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(size - 2 * pad, size - 2 * pad, Gravity.CENTER);
        view.addView(face, flp);
        view.setElevation(dp(6));

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = service.getResources().getDisplayMetrics().widthPixels - size - dp(12);
        lp.y = dp(160);

        view.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; long down; boolean moved;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y;
                        down = System.currentTimeMillis(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        lp.x = ox + (int) dx; lp.y = oy + (int) dy;
                        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (System.currentTimeMillis() - down > 700) {
                                service.startService(new Intent(service, LiveService.class).setAction(LiveService.ACTION_STOP));
                            } else {
                                Intent open = service.getPackageManager().getLaunchIntentForPackage(service.getPackageName());
                                if (open != null) {
                                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                                    service.startActivity(open);
                                }
                            }
                        }
                        return true;
                }
                return false;
            }
        });
    }

    void show() {
        if (shown) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(service)) return;
        if (view == null) build();
        try {
            wm.addView(view, lp);
            shown = true;
        } catch (Exception ignored) {
        }
    }

    void hide() {
        if (!shown) return;
        try { wm.removeView(view); } catch (Exception ignored) { }
        shown = false;
    }

    void setState(String state) {
        if (ring == null) return;
        String c;
        switch (state) {
            case "HEARING": c = "#F2B56B"; break;   // candle: hearing you
            case "THINKING": c = "#9DB0BF"; break;  // mist: thinking
            case "SPEAKING": c = "#7FD3A0"; break;  // green: talking
            default: c = "#6070D6"; break;          // her blue: listening
        }
        ring.setStroke(dp(4), Color.parseColor(c));
    }
}
