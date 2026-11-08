package com.sonu4y.mikulive;

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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Screen sharing via Android's MediaProjection (the user approves it each time
 * with the system dialog). Keeps the latest frame and hands out a small JPEG
 * when a voice turn is sent.
 */
class ScreenGrabber {
    private static final int MAX_SIDE = 960;

    private final LiveService service;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private volatile String lastJpeg;
    private int width, height;

    ScreenGrabber(LiveService service) {
        this.service = service;
    }

    boolean isActive() {
        return projection != null;
    }

    boolean start(int resultCode, Intent data) {
        stop();
        try {
            MediaProjectionManager m = (MediaProjectionManager) service.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);
            if (projection == null) return false;
            thread = new HandlerThread("miku-screen");
            thread.start();
            Handler h = new Handler(thread.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    release();
                    MikuLivePlugin.emit("screen_share", false);
                }
            }, h);
            DisplayMetrics dm = service.getResources().getDisplayMetrics();
            float scale = Math.min(1f, MAX_SIDE / (float) Math.max(dm.widthPixels, dm.heightPixels));
            width = Math.max(2, Math.round(dm.widthPixels * scale)) & ~1;
            height = Math.max(2, Math.round(dm.heightPixels * scale)) & ~1;
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img != null) lastJpeg = toJpeg(img);
                } catch (Exception ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, h);
            display = projection.createVirtualDisplay("miku-screen", width, height, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
            return true;
        } catch (Exception e) {
            stop();
            return false;
        }
    }

    private long lastEncode = 0;

    private String toJpeg(Image img) {
        long now = System.currentTimeMillis();
        if (now - lastEncode < 700 && lastJpeg != null) return lastJpeg; // the screen can update 60x a second; we don't need that
        lastEncode = now;
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap bmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buf);
        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, width, height);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, 70, out);
        if (cropped != bmp) cropped.recycle();
        bmp.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    String grabJpegBase64() {
        return lastJpeg;
    }

    private void release() {
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (thread != null) { thread.quitSafely(); thread = null; }
        projection = null;
        lastJpeg = null;
    }

    void stop() {
        MediaProjection p = projection;
        release();
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) { }
        }
    }
}
