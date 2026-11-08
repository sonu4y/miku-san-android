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
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
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
