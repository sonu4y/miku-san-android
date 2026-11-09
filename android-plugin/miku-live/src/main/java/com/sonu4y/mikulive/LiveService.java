package com.sonu4y.mikulive;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live voice mode that keeps running when the app is in the background.
 * Listens with the phone's call-quality microphone (echo cancellation + noise
 * suppression), detects when you start and stop talking, sends the turn (plus a
 * screenshot when screen sharing is on) to the Miku-san server, and speaks the
 * reply. You can interrupt her by talking while she speaks.
 */
public class LiveService extends Service {
    static final String TAG = "MikuLive";
    static final String ACTION_START = "com.sonu4y.mikulive.START";
    static final String ACTION_STOP = "com.sonu4y.mikulive.STOP";
    static final String ACTION_SCREEN = "com.sonu4y.mikulive.SCREEN";
    static final String CHANNEL = "miku_live";
    static final int NOTIF_ID = 4711;

    static volatile LiveService instance;
    private static final JSONArray pendingTurns = new JSONArray();

    // a short log of what live mode did, shown in the app's Mic check
    private static final ArrayDeque<String> log = new ArrayDeque<>();
    static void log(String msg) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()) + "  " + msg;
        Log.i(TAG, msg);
        synchronized (log) {
            log.addLast(line);
            while (log.size() > 14) log.removeFirst();
        }
    }
    static String getLog() {
        synchronized (log) { return android.text.TextUtils.join("\n", log); }
    }

    // audio
    static final int RATE = 16000;
    static final int FRAME = 320;                // 20 ms
    static final int PREROLL_FRAMES = 18;        // 360 ms kept before speech starts
    static final int START_FRAMES = 3;           // 60 ms above threshold to start
    static final int END_SILENCE_FRAMES = 35;    // 700 ms of quiet ends the turn
    static final int MIN_VOICED_FRAMES = 12;     // ignore blips shorter than ~240 ms
    static final int MAX_FRAMES = 1000;          // 20 s max per turn
    static final int BARGE_FRAMES = 8;           // 160 ms of loud speech interrupts her

    enum State { LISTENING, HEARING, THINKING, SPEAKING }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private volatile State state = State.LISTENING;
    private Thread audioThread;
    private AudioRecord recorder;

    private volatile String server = "";
    private volatile String system = "";
    private final JSONArray history = new JSONArray();
    private volatile boolean voiceOn = true;
    private volatile String voiceName = "Leda";
    private volatile AudioTrack track;

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;

    private Bubble bubble;
    private ScreenGrabber screen;
    private volatile boolean appVisible = true;

    // ----------------------------------------------------------------- service --
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        bubble = new Bubble(this);
        screen = new ScreenGrabber(this);
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            Locale in = new Locale("en", "IN");
            int r = tts.setLanguage(in);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
            tts.setPitch(1.12f);
            tts.setSpeechRate(1.02f);
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { finishedSpeaking(); }
                @Override public void onError(String id) { finishedSpeaking(); }
                @Override public void onStop(String id, boolean interrupted) { finishedSpeaking(); }
                @Override public void onRangeStart(String id, int start, int end, int frame) {
                    MikuLivePlugin.emit("live_speak_progress", start);
                }
            });
            ttsReady = true;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_SCREEN.equals(action)) {
            goForeground(true);
            int code = intent.getIntExtra("code", 0);
            Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("data", Intent.class) : intent.getParcelableExtra("data");
            boolean ok = data != null && screen.start(code, data);
            MikuLivePlugin.emit("screen_share", ok);
            if (!ok) goForeground(false);
            updateNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            log("Live starting");
            updateConfig(intent.getStringExtra("config"));
            try {
                goForeground(screen.isActive());
            } catch (Throwable e) {
                log("Couldn't start in the background: " + e);
                MikuLivePlugin.emit("live_error", "service_failed");
            }
            if (!running) startListening();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopEverything();
        if (tts != null) tts.shutdown();
        net.shutdownNow();
        instance = null;
        super.onDestroy();
    }

    boolean isRunning() { return running; }
    boolean isSharing() { return screen != null && screen.isActive(); }

    void updateConfig(String json) {
        if (json == null) return;
        try {
            JSONObject c = new JSONObject(json);
            if (c.has("server")) server = c.getString("server");
            if (c.has("system")) system = c.getString("system");
            if (c.has("voice_on")) voiceOn = c.getBoolean("voice_on");
            if (c.has("voice")) voiceName = c.getString("voice");
            if (c.has("messages")) {
                JSONArray m = c.getJSONArray("messages");
                synchronized (history) {
                    while (history.length() > 0) history.remove(0);
                    for (int i = 0; i < m.length(); i++) history.put(m.get(i));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "bad config", e);
        }
    }

    void setVoiceOn(boolean on) {
        voiceOn = on;
        if (!on) interrupt();
    }

    static String takePendingTurns() {
        synchronized (pendingTurns) {
            String s = pendingTurns.toString();
            while (pendingTurns.length() > 0) pendingTurns.remove(0);
            return s;
        }
    }

    void onAppVisibility(boolean visible) {
        appVisible = visible;
        main.post(() -> {
            if (!visible && running) bubble.show(); else bubble.hide();
            bubble.setState(state.name());
        });
    }

    private void stopEverything() {
        if (running) log("Live stopped");
        running = false;
        stopTrack();
        if (audioThread != null) audioThread.interrupt();
        if (tts != null) tts.stop();
        if (screen != null) screen.stop();
        main.post(() -> bubble.hide());
        setState(State.LISTENING);
        MikuLivePlugin.emit("live_state", "stopped");
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    void stopScreen() {
        screen.stop();
        MikuLivePlugin.emit("screen_share", false);
        goForeground(false);
        updateNotification();
    }

    // ------------------------------------------------------------ notification --
    private void goForeground(boolean withScreen) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Live talk", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while Miku-san is listening");
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (withScreen) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open, piFlags);
        PendingIntent stopPi = PendingIntent.getService(this, 2, new Intent(this, LiveService.class).setAction(ACTION_STOP), piFlags);
        String text;
        switch (state) {
            case HEARING: text = "Hearing you..."; break;
            case THINKING: text = "Thinking..."; break;
            case SPEAKING: text = "Talking. Speak to interrupt."; break;
            default: text = "Listening. Just talk."; break;
        }
        if (isSharing()) text += " Sharing your screen.";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Miku-san is live")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build());
        return b.build();
    }

    private void updateNotification() {
        if (!running) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            nm.notify(NOTIF_ID, buildNotification());
        } catch (SecurityException ignored) {
        }
    }

    private void setState(State s) {
        state = s;
        MikuLivePlugin.emit("live_state", s.name().toLowerCase(Locale.US));
        main.post(() -> {
            bubble.setState(s.name());
            updateNotification();
        });
    }

    // ------------------------------------------------------------------ audio --
    @SuppressLint("MissingPermission")
    private void startListening() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 20));
        } catch (Exception e) {
            log("Mic failed: " + e);
            MikuLivePlugin.emit("live_error", "mic_unavailable");
            stopEverything();
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            log("Mic failed: not initialized (another app using it?)");
            MikuLivePlugin.emit("live_error", "mic_unavailable");
            stopEverything();
            return;
        }
        int session = recorder.getAudioSessionId();
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session).setEnabled(true); } catch (Exception ignored) { }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session).setEnabled(true); } catch (Exception ignored) { }
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session).setEnabled(true); } catch (Exception ignored) { }
        log("Mic open (echo cancel " + (AcousticEchoCanceler.isAvailable() ? "on" : "n/a") + ", noise filter " + (NoiseSuppressor.isAvailable() ? "on" : "n/a") + ")");

        running = true;
        setState(State.LISTENING);
        audioThread = new Thread(this::audioLoop, "miku-mic");
        audioThread.start();
    }

    private void audioLoop() {
        short[] frame = new short[FRAME];
        ArrayDeque<short[]> preroll = new ArrayDeque<>();
        ByteArrayOutputStream turn = new ByteArrayOutputStream();
        double noise = -55;         // adaptive background level (dBFS)
        double echo = -60;          // her own voice leaking into the mic while she talks
        int above = 0, silence = 0, voiced = 0, frames = 0, barge = 0, meter = 0;
        boolean capturing = false;
        try {
            recorder.startRecording();
        } catch (Exception e) {
            MikuLivePlugin.emit("live_error", "mic_unavailable");
            main.post(this::stopEverything);
            return;
        }
        while (running && !Thread.currentThread().isInterrupted()) {
            int n = recorder.read(frame, 0, FRAME);
            if (n <= 0) continue;
            double sum = 0;
            for (int i = 0; i < n; i++) sum += (double) frame[i] * frame[i];
            double db = 20 * Math.log10(Math.sqrt(sum / n) / 32768.0 + 1e-9);

            if (++meter >= 5) {
                meter = 0;
                MikuLivePlugin.emit("live_level", (float) Math.max(0, Math.min(1, (db + 60) / 45)));
            }

            State s = state;
            if (s == State.THINKING) { above = 0; continue; }

            if (s == State.SPEAKING) {
                // interrupt her only if you're clearly louder than her echo
                echo = echo * 0.95 + db * 0.05;
                double gate = Math.max(noise + 20, echo + 9);
                if (db > gate) barge++; else barge = Math.max(0, barge - 1);
                preroll.addLast(frame.clone());
                if (preroll.size() > PREROLL_FRAMES) preroll.removeFirst();
                if (barge >= BARGE_FRAMES) {
                    barge = 0;
                    interrupt();
                    capturing = true;
                    turn.reset();
                    for (short[] f : preroll) writeFrame(turn, f);
                    preroll.clear();
                    silence = 0; voiced = BARGE_FRAMES; frames = 0;
                    setState(State.HEARING);
                }
                continue;
            }

            if (!capturing) {
                // track the room's background noise
                noise = db < noise ? noise * 0.9 + db * 0.1 : noise * 0.995 + db * 0.005;
                preroll.addLast(frame.clone());
                if (preroll.size() > PREROLL_FRAMES) preroll.removeFirst();
                if (db > noise + 10 && db > -52) above++; else above = 0;
                if (above >= START_FRAMES) {
                    capturing = true;
                    turn.reset();
                    for (short[] f : preroll) writeFrame(turn, f);
                    preroll.clear();
                    silence = 0; voiced = above; frames = 0; above = 0;
                    setState(State.HEARING);
                }
            } else {
                writeFrame(turn, frame);
                frames++;
                boolean loud = db > noise + 6 && db > -55;
                if (loud) { voiced++; silence = 0; } else silence++;
                if (silence >= END_SILENCE_FRAMES || frames >= MAX_FRAMES) {
                    capturing = false;
                    if (voiced >= MIN_VOICED_FRAMES) {
                        byte[] pcm = turn.toByteArray();
                        log(String.format(Locale.US, "Heard %.1f s of speech, sending", pcm.length / 2.0 / RATE));
                        setState(State.THINKING);
                        net.execute(() -> sendTurn(pcm));
                    } else {
                        log("Ignored a short noise");
                        setState(State.LISTENING);
                    }
                    turn.reset();
                    echo = -60;
                }
            }
        }
        try { recorder.stop(); } catch (Exception ignored) { }
        recorder.release();
    }

    private static void writeFrame(ByteArrayOutputStream out, short[] f) {
        for (short v : f) {
            out.write(v & 0xff);
            out.write((v >> 8) & 0xff);
        }
    }

    private static byte[] wav(byte[] pcm) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(pcm.length + 44);
        int byteRate = RATE * 2;
        writeAscii(o, "RIFF"); writeInt(o, 36 + pcm.length); writeAscii(o, "WAVE");
        writeAscii(o, "fmt "); writeInt(o, 16); writeShort(o, 1); writeShort(o, 1);
        writeInt(o, RATE); writeInt(o, byteRate); writeShort(o, 2); writeShort(o, 16);
        writeAscii(o, "data"); writeInt(o, pcm.length);
        o.write(pcm, 0, pcm.length);
        return o.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream o, String s) { byte[] b = s.getBytes(StandardCharsets.US_ASCII); o.write(b, 0, b.length); }
    private static void writeInt(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    private static void writeShort(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }

    // ------------------------------------------------------------------- turns --
    private void sendTurn(byte[] pcm) {
        try {
            JSONObject body = new JSONObject();
            body.put("system", system);
            synchronized (history) { body.put("messages", new JSONArray(history.toString())); }
            body.put("audio", Base64.encodeToString(wav(pcm), Base64.NO_WRAP));
            String shot = screen.isActive() ? screen.grabJpegBase64() : null;
            if (shot != null) body.put("image", shot);
            if (voiceOn && !"phone".equals(voiceName)) body.put("voice", voiceName);
            long t0 = System.currentTimeMillis();

            HttpURLConnection c = (HttpURLConnection) new URL(server + "/api/talk").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Origin", server);
            c.setRequestProperty("Referer", server + "/");
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            log("Server answered " + code + " in " + (System.currentTimeMillis() - t0) + " ms");
            if (!running) return;
            if (code != 200) {
                String err = code == 404 ? "server_old" : "server";
                try { err = new JSONObject(resp).optString("error", err); } catch (Exception ignored) { }
                log("Problem: " + err);
                MikuLivePlugin.emit("live_error", err);
                pauseThenListen(1500);
                return;
            }
            JSONObject r = new JSONObject(resp);
            String heard = r.optString("heard", "").trim();
            String reply = r.optString("reply", "").trim();
            if (heard.isEmpty() || reply.isEmpty()) {
                log("No clear words in that clip");
                setState(State.LISTENING);
                return;
            }
            synchronized (history) {
                history.put(new JSONObject().put("role", "user").put("content", heard));
                history.put(new JSONObject().put("role", "assistant").put("content", reply));
                while (history.length() > 24) history.remove(0);
            }
            if (!appVisible) {
                // the app picks these up when it comes back to the front
                synchronized (pendingTurns) { pendingTurns.put(new JSONObject().put("heard", heard).put("reply", reply)); }
            } else {
                MikuLivePlugin.emit("live_heard", heard);
                MikuLivePlugin.emit("live_reply", reply);
            }
            String audio = r.optString("audio", "");
            if (!audio.isEmpty()) {
                log("Speaking with natural voice (" + r.optString("voice", voiceName) + ")");
                playPcm(Base64.decode(audio, Base64.DEFAULT), r.optInt("rate", 24000), spokenText(reply));
            } else {
                if (voiceOn && !"phone".equals(voiceName)) log("Natural voice unavailable, using phone voice");
                speak(spokenText(reply));
            }
        } catch (Exception e) {
            Log.w(TAG, "turn failed", e);
            log("Turn failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            if (running) {
                MikuLivePlugin.emit("live_error", "offline");
                pauseThenListen(2000);
            }
        }
    }

    private void pauseThenListen(long ms) {
        main.postDelayed(() -> { if (running) setState(State.LISTENING); }, ms);
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    static String spokenText(String raw) {
        String s = raw.replaceFirst("^\\s*\\[\\w+\\]\\s*", "");
        s = s.replaceAll("(?is)<(remember|name)>.*?(</(remember|name)>|$)", "");
        s = s.replaceAll("<[^>]*>", "");
        return s.trim();
    }

    // ---------------------------------------------------------------- speaking --
    private void speak(String text) {
        if (text.isEmpty() || !voiceOn || !ttsReady) {
            MikuLivePlugin.emit("live_speak_start", text);
            // no voice: give the app time to animate her mouth, then listen again
            main.postDelayed(this::finishedSpeaking, Math.min(8000, 300 + text.length() * 70L));
            setState(State.SPEAKING);
            return;
        }
        setState(State.SPEAKING);
        MikuLivePlugin.emit("live_speak_start", text);
        Bundle p = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "miku-" + System.currentTimeMillis());
    }

    /** Plays her natural (server-made) voice and reports progress for lip-sync. */
    private void playPcm(byte[] pcm, int rate, String text) {
        stopTrack();
        if (pcm.length < 4) { speak(text); return; }
        int len = pcm.length & ~1;
        AudioTrack t;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(len)
                    .build();
            t.write(pcm, 0, len);
        } catch (Exception e) {
            log("Couldn't play natural voice: " + e.getMessage());
            speak(text);
            return;
        }
        final int frames = len / 2;
        track = t;
        setState(State.SPEAKING);
        MikuLivePlugin.emit("live_speak_start", text);
        t.play();
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (track != t || state != State.SPEAKING) return;
                int pos;
                try { pos = t.getPlaybackHeadPosition(); } catch (Exception e) { pos = frames; }
                MikuLivePlugin.emit("live_speak_progress", (int) ((long) text.length() * Math.min(pos, frames) / Math.max(1, frames)));
                if (pos >= frames - rate / 50) finishedSpeaking(); else main.postDelayed(this, 80);
            }
        };
        main.postDelayed(tick, 80);
    }

    private void stopTrack() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) { }
            t.release();
        }
    }

    void interrupt() {
        if (state != State.SPEAKING) return;
        if (tts != null) tts.stop();
        stopTrack();
        log("Interrupted by you");
        finishedSpeaking();
    }

    private void finishedSpeaking() {
        if (state != State.SPEAKING) return;
        stopTrack();
        MikuLivePlugin.emit("live_speak_done");
        if (running) setState(State.LISTENING);
    }
}
