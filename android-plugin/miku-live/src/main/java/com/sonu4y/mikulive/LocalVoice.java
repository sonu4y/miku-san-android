package com.sonu4y.mikulive;

import android.content.Context;
import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Her on-phone voices (run by sherpa-onnx). Work offline with no usage limit.
 *
 * Two engines:
 *  - FAST: a small Piper (VITS) model. Makes speech many times faster than real time,
 *    so she starts talking almost at once on any phone.
 *  - HD: Kokoro. More natural and the cutest voices, but needs a strong phone.
 *
 * The models ship inside the APK (assets/voice-fast, assets/kokoro) and are
 * unpacked to app storage once, then loaded from files.
 */
final class LocalVoice {
    /** A voice: which engine, its speaker id, and how much higher/cuter it's played. */
    static final class Style {
        final Engine engine;
        final int sid;
        final float pitch;
        Style(Engine engine, int sid, float pitch) { this.engine = engine; this.sid = sid; this.pitch = pitch; }
    }

    static Style style(String name) {
        if (name == null) return null;
        switch (name) {
            // fast voices (default)
            case "Miku":    return new Style(FAST, FAST_MIKU_SID, FAST_MIKU_PITCH);
            case "Sunny":   return new Style(FAST, FAST_SUNNY_SID, FAST_SUNNY_PITCH);
            // HD voices (Kokoro)
            case "MikuHD":  return new Style(HD, 40, 1.00f);  // jf_tebukuro: high, anime-girl voice
            case "Sweet":   return new Style(HD, 38, 1.15f);  // jf_gongitsune
            case "Bubbly":  return new Style(HD, 39, 1.15f);  // jf_nezumi
            case "Kawaii":  return new Style(HD, 37, 1.25f);  // jf_alpha, extra high
            case "Heart":   return new Style(HD, 3, 1.10f);   // af_heart: natural English
            default: return null;                            // not an on-phone voice (a Gemini voice)
        }
    }

    // Filled in from a scan of all the fast model's speakers (highest, clearest young voices).
    static final int FAST_MIKU_SID = 453;      // clearest of the highest young voices
    static final float FAST_MIKU_PITCH = 1.15f;
    static final int FAST_SUNNY_SID = 267;     // very expressive, cheerful
    static final float FAST_SUNNY_PITCH = 1.15f;

    /** One speech engine: loaded on first use, then kept. */
    static final class Engine {
        final String name, assetDir, storeDir;
        final int rate;
        final String[] files;
        private OfflineTts tts;
        private volatile String failure;
        private volatile boolean loading;
        private final Object lock = new Object();

        Engine(String name, String assetDir, String storeDir, int rate, String... files) {
            this.name = name; this.assetDir = assetDir; this.storeDir = storeDir; this.rate = rate; this.files = files;
        }

        String status() {
            if (failure != null) return failure;
            if (tts != null) return "ready";
            return "loading";
        }

        void prepareAsync(Context ctx) {
            if (tts != null || loading || failure != null) return;
            final Context app = ctx.getApplicationContext();
            loading = true;
            new Thread(() -> {
                try { get(app); } catch (Throwable ignored) { }
            }, "miku-voice-" + name).start();
        }

        OfflineTts get(Context ctx) throws Exception {
            synchronized (lock) {
                if (tts != null) return tts;
                if (failure != null) throw new Exception(failure);
                loading = true;
                long t0 = System.currentTimeMillis();
                try {
                    File dir = unpack(ctx, this);
                    long t1 = System.currentTimeMillis();
                    OfflineTtsModelConfig m = new OfflineTtsModelConfig();
                    if (this == HD) {
                        OfflineTtsKokoroModelConfig k = new OfflineTtsKokoroModelConfig();
                        k.setModel(new File(dir, files[0]).getAbsolutePath());
                        k.setVoices(new File(dir, "voices.bin").getAbsolutePath());
                        k.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
                        k.setLexicon(new File(dir, "lexicon-us-en.txt").getAbsolutePath());
                        k.setDataDir(new File(dir, "espeak-ng-data").getAbsolutePath());
                        k.setLang("en-us");
                        k.setLengthScale(1.0f);
                        m.setKokoro(k);
                    } else {
                        OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
                        v.setModel(new File(dir, files[0]).getAbsolutePath());
                        v.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
                        v.setDataDir(new File(dir, "espeak-ng-data").getAbsolutePath());
                        v.setLengthScale(1.0f);
                        m.setVits(v);
                    }
                    int cores = Runtime.getRuntime().availableProcessors();
                    m.setNumThreads(cores >= 6 ? 4 : Math.max(1, Math.min(2, cores)));
                    m.setDebug(false);
                    m.setProvider("cpu");
                    OfflineTtsConfig c = new OfflineTtsConfig();
                    c.setModel(m);
                    c.setMaxNumSentences(1);
                    c.setSilenceScale(0.2f);
                    OfflineTts t = new OfflineTts(null, c);
                    long t2 = System.currentTimeMillis();
                    t.generate("Hi.", 0, 1.0f);   // warm up so the first real reply is quick
                    LiveService.log(name + " voice ready: unpack " + (t1 - t0) + " ms, load " + (t2 - t1) + " ms, warm-up "
                            + (System.currentTimeMillis() - t2) + " ms, " + m.getNumThreads() + " threads of " + cores);
                    tts = t;
                    return t;
                } catch (Throwable e) {
                    failure = name + " voice failed to load: " + e;
                    LiveService.log(failure);
                    throw new Exception(failure);
                } finally {
                    loading = false;
                }
            }
        }

        GeneratedAudio generate(Context ctx, String text, int sid, float speed) throws Exception {
            OfflineTts t = get(ctx);
            synchronized (lock) { return t.generate(text, sid, speed); }
        }
    }

    static final Engine FAST = new Engine("Fast", "voice-fast", "voice-fast-v1", 22050,
            "model.onnx", "tokens.txt");
    static final Engine HD = new Engine("HD", "kokoro", "kokoro-v2", 24000,
            "model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt");

    /** Loads the engine for this voice in the background (fast engine always). */
    static void prepareAsync(Context ctx, String voice) {
        FAST.prepareAsync(ctx);
        Style st = style(voice);
        if (st != null && st.engine == HD) HD.prepareAsync(ctx);
    }

    /** "ready", "loading" or why it can't work, for this voice. */
    static String status(String voice) {
        Style st = style(voice);
        return st == null ? "not an on-phone voice" : st.engine.status();
    }

    private static File unpack(Context ctx, Engine e) throws Exception {
        File root = new File(ctx.getFilesDir(), e.storeDir);
        File done = new File(root, ".done");
        if (done.exists()) return root;
        AssetManager am = ctx.getAssets();
        long free = ctx.getFilesDir().getUsableSpace();
        if (free < 250L * 1024 * 1024) throw new Exception("not enough free storage (" + (free >> 20) + " MB free)");
        for (String old : new String[]{"kokoro-v1"}) {
            File o = new File(ctx.getFilesDir(), old);
            if (o.exists()) deleteAll(o);
        }
        root.mkdirs();
        for (String f : e.files) copyAsset(am, e.assetDir + "/" + f, new File(root, f));
        copyAssetDir(am, e.assetDir + "/espeak-ng-data", new File(root, "espeak-ng-data"));
        new FileOutputStream(done).close();
        return root;
    }

    private static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }

    private static void copyAsset(AssetManager am, String path, File out) throws Exception {
        out.getParentFile().mkdirs();
        File tmp = new File(out.getPath() + ".part");
        try (InputStream in = am.open(path); OutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        if (!tmp.renameTo(out)) throw new Exception("couldn't save " + out.getName());
    }

    private static void copyAssetDir(AssetManager am, String path, File out) throws Exception {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            copyAsset(am, path, out);
            return;
        }
        out.mkdirs();
        for (String k : kids) copyAssetDir(am, path + "/" + k, new File(out, k));
    }

    /** Splits a reply into short pieces: the first one is kept small so she starts fast. */
    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[^.!?…\\n]+[.!?…]*").matcher(text);
        while (m.find()) {
            String s = m.group().trim();
            if (s.isEmpty() || !s.matches(".*[\\p{L}\\p{N}].*")) continue;
            // long sentences: break at commas, then at spaces
            while (s.length() > 160) {
                int cut = s.lastIndexOf(", ", 150);
                if (cut < 40) cut = s.lastIndexOf(' ', 150);
                if (cut < 20) cut = 150;
                out.add(s.substring(0, cut + 1).trim());
                s = s.substring(cut + 1).trim();
            }
            if (!s.isEmpty()) out.add(s);
        }
        // a long first piece delays her first word: split it at its first comma
        if (!out.isEmpty() && out.get(0).length() > 60) {
            String f = out.get(0);
            int c = f.indexOf(", ");
            if (c >= 6 && c < f.length() - 12) {
                out.set(0, f.substring(c + 2).trim());
                out.add(0, f.substring(0, c + 1).trim());
            }
        }
        // join very short pieces after the first so the voice flows naturally
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (i > 0 && merged.size() > 1 && merged.get(merged.size() - 1).length() < 25) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + " " + s);
            } else {
                merged.add(s);
            }
        }
        return merged;
    }

    /** Receives her voice piece by piece (16-bit mono PCM). Return false to stop. */
    interface Sink {
        boolean piece(byte[] pcm);
    }

    /**
     * Makes speech for the whole text, handing each piece to the sink as soon as it's ready.
     * Blocks until done.
     */
    static void speak(Context ctx, String text, Style st, Sink sink) throws Exception {
        float speed = 1.0f / st.pitch;   // played back faster/higher, so speak slower to keep a normal pace
        for (String piece : chunks(text)) {
            long g0 = System.currentTimeMillis();
            GeneratedAudio a = st.engine.generate(ctx, piece, st.sid, speed);
            float[] f = a.getSamples();
            LiveService.log(String.format(Locale.US, "%s voice: %d chars -> %.1f s of audio in %d ms",
                    st.engine.name, piece.length(), f.length / (float) st.engine.rate, System.currentTimeMillis() - g0));
            byte[] b = new byte[f.length * 2];
            for (int i = 0; i < f.length; i++) {
                int x = Math.round(Math.max(-1f, Math.min(1f, f[i])) * 32767f);
                b[2 * i] = (byte) x;
                b[2 * i + 1] = (byte) (x >> 8);
            }
            if (!sink.piece(b)) return;
        }
    }

    static int playbackRate(Style st) {
        return Math.round(st.engine.rate * st.pitch);
    }
}
