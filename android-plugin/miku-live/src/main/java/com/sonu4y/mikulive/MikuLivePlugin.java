package com.sonu4y.mikulive;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.SignalInfo;
import org.godotengine.godot.plugin.UsedByGodot;

import java.util.HashSet;
import java.util.Set;

/**
 * Bridge between the Godot app and the live-mode service.
 * In GDScript: var live = Engine.get_singleton("MikuLive")
 */
public class MikuLivePlugin extends GodotPlugin {
    static final int REQ_SCREEN = 7301;
    static final int REQ_PERMS = 7302;

    private static volatile MikuLivePlugin instance;
    static volatile boolean appVisible = true;

    public MikuLivePlugin(Godot godot) {
        super(godot);
        instance = this;
        installCrashRecorder();
        try {
            Activity a = getActivity();
            if (a != null) LocalVoice.prepareAsync(a, "Miku");
        } catch (Throwable ignored) {
        }
    }

    /** Saves the reason for a crash so the app can show it in Mic check next time. */
    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                Activity a = getActivity();
                if (a != null) {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    error.printStackTrace(new java.io.PrintWriter(sw));
                    String text = sw.toString();
                    if (text.length() > 1500) text = text.substring(0, 1500);
                    java.io.File f = new java.io.File(a.getFilesDir(), "miku_crash.txt");
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(text.getBytes("UTF-8")); }
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** The last crash report (empty if none); clears it. */
    @UsedByGodot
    public String takeLastCrash() {
        Activity a = getActivity();
        if (a == null) return "";
        java.io.File f = new java.io.File(a.getFilesDir(), "miku_crash.txt");
        if (!f.exists()) return "";
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            f.delete();
            return out.toString("UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    // ----------------------------------------------------- on-device voice --
    private static volatile int localWant = -1;   // the on-phone speech that should be playing
    private final java.util.concurrent.ExecutorService voiceThread = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Starts loading her on-device voice so the first reply is quick. */
    @UsedByGodot
    public void prepareVoice(String voice) {
        Context c = ctx();
        if (c != null) LocalVoice.prepareAsync(c, voice);
    }

    /** "ready", "loading" or the reason it can't work. */
    @UsedByGodot
    public String voiceStatus(String voice) {
        return LocalVoice.status(voice);
    }

    /** Makes her voice on the phone; audio comes back piece by piece through local_audio. */
    @UsedByGodot
    public boolean speakLocal(int id, String text, String voice) {
        final LocalVoice.Style st = LocalVoice.style(voice);
        final Context c = ctx();
        if (st == null || c == null || st.engine.status().contains("failed")) return false;
        localWant = id;
        voiceThread.execute(() -> {
            final boolean[] started = {false};
            if (localWant != id) return;   // replaced by a newer one before it started
            try {
                LocalVoice.speak(c, text, st, pcm -> {
                    if (localWant != id) return false;
                    if (!started[0]) {
                        started[0] = true;
                        emit("local_start", id, LocalVoice.playbackRate(st));
                    }
                    emit("local_audio", id, pcm);
                    return true;
                });
                emit("local_done", id);
            } catch (Throwable e) {
                emit("local_failed", id, String.valueOf(e.getMessage()));
            }
        });
        return true;
    }

    @UsedByGodot
    public void stopLocal(int id) {
        if (localWant == id) localWant = -1;
    }

    /** For automatic tests: the "miku_selftest" value the app was started with, or "". */
    @UsedByGodot
    public String getLaunchExtra() {
        Activity a = getActivity();
        if (a == null || a.getIntent() == null) return "";
        String v = a.getIntent().getStringExtra("miku_selftest");
        return v == null ? "" : v;
    }

    /** For automatic tests: makes Live mode say something as if it were her reply. */
    @UsedByGodot
    public void liveTestSay(String text) {
        LiveService s = LiveService.instance;
        if (s != null) s.testSay(text);
    }

    @UsedByGodot
    public String getLog() {
        return LiveService.getLog();
    }

    @Override
    public String getPluginName() {
        return "MikuLive";
    }

    @Override
    public Set<SignalInfo> getPluginSignals() {
        Set<SignalInfo> s = new HashSet<>();
        s.add(new SignalInfo("live_state", String.class));          // listening, hearing, thinking, speaking, stopped
        s.add(new SignalInfo("live_level", Float.class));           // 0..1 mic level
        s.add(new SignalInfo("live_heard", String.class));          // what the user said
        s.add(new SignalInfo("live_reply", String.class));          // her raw reply (with tags)
        s.add(new SignalInfo("live_speak_start", String.class));    // text being spoken
        s.add(new SignalInfo("live_speak_progress", Integer.class));// character position
        s.add(new SignalInfo("live_speak_done"));
        s.add(new SignalInfo("live_error", String.class));
        s.add(new SignalInfo("screen_share", Boolean.class));
        s.add(new SignalInfo("permissions_done", Boolean.class));
        s.add(new SignalInfo("local_start", Integer.class, Integer.class));     // id, sample rate
        s.add(new SignalInfo("local_audio", Integer.class, byte[].class));      // id, 16-bit mono PCM
        s.add(new SignalInfo("local_done", Integer.class));
        s.add(new SignalInfo("local_failed", Integer.class, String.class));
        return s;
    }

    /** Lets the service send events to the app (dropped if the app is gone). */
    static void emit(String name, Object... args) {
        MikuLivePlugin p = instance;
        if (p == null) return;
        if (!appVisible && name.equals("live_level")) return; // don't queue meter updates in the background
        try {
            p.emitSignal(name, args);
        } catch (Throwable ignored) {
        }
    }

    private Context ctx() {
        Activity a = getActivity();
        return a != null ? a.getApplicationContext() : null;
    }

    // ------------------------------------------------------------ permissions --
    @UsedByGodot
    public boolean hasMicPermission() {
        Context c = ctx();
        return c != null && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @UsedByGodot
    public void requestPermissions() {
        Activity a = getActivity();
        if (a == null) return;
        if (Build.VERSION.SDK_INT >= 33) {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
        } else {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        }
    }

    @Override
    public void onMainRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERMS) emit("permissions_done", hasMicPermission());
    }

    @UsedByGodot
    public boolean canDrawOverlays() {
        Context c = ctx();
        return c != null && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c));
    }

    @UsedByGodot
    public void openOverlaySettings() {
        Activity a = getActivity();
        if (a == null) return;
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName()));
        a.startActivity(i);
    }

    // -------------------------------------------------------------- live mode --
    @UsedByGodot
    public void startLive(String configJson) {
        Context c = ctx();
        if (c == null) return;
        Intent i = new Intent(c, LiveService.class).setAction(LiveService.ACTION_START).putExtra("config", configJson);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable e) {
            LiveService.log("Couldn't start live: " + e);
            emit("live_error", "service_failed");
        }
    }

    @UsedByGodot
    public void stopLive() {
        Context c = ctx();
        if (c == null) return;
        c.startService(new Intent(c, LiveService.class).setAction(LiveService.ACTION_STOP));
    }

    @UsedByGodot
    public void updateContext(String configJson) {
        LiveService s = LiveService.instance;
        if (s != null) s.updateConfig(configJson);
    }

    @UsedByGodot
    public void setVoiceOn(boolean on) {
        LiveService s = LiveService.instance;
        if (s != null) s.setVoiceOn(on);
    }

    @UsedByGodot
    public void stopSpeaking() {
        LiveService s = LiveService.instance;
        if (s != null) s.interrupt();
    }

    @UsedByGodot
    public boolean isLive() {
        return LiveService.instance != null && LiveService.instance.isRunning();
    }

    @UsedByGodot
    public boolean isSharingScreen() {
        return LiveService.instance != null && LiveService.instance.isSharing();
    }

    /** Turns that happened while the app was in the background, as a JSON array. */
    @UsedByGodot
    public String takeBackgroundTurns() {
        return LiveService.takePendingTurns();
    }

    // ---------------------------------------------------------- screen share --
    @UsedByGodot
    public void requestScreenShare() {
        Activity a = getActivity();
        if (a == null) return;
        MediaProjectionManager m = (MediaProjectionManager) a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        a.startActivityForResult(m.createScreenCaptureIntent(), REQ_SCREEN);
    }

    @UsedByGodot
    public void stopScreenShare() {
        LiveService s = LiveService.instance;
        if (s != null) s.stopScreen();
    }

    @Override
    public void onMainActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_SCREEN) return;
        Context c = ctx();
        if (resultCode != Activity.RESULT_OK || data == null || c == null || LiveService.instance == null) {
            emit("screen_share", false);
            return;
        }
        Intent i = new Intent(c, LiveService.class).setAction(LiveService.ACTION_SCREEN)
                .putExtra("code", resultCode).putExtra("data", data);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    // ------------------------------------------------------- app visibility --
    @Override
    public void onMainPause() {
        appVisible = false;
        LiveService s = LiveService.instance;
        if (s != null) s.onAppVisibility(false);
    }

    @Override
    public void onMainResume() {
        appVisible = true;
        LiveService s = LiveService.instance;
        if (s != null) s.onAppVisibility(true);
    }

    @Override
    public void onMainDestroy() {
        instance = null;
    }
}
