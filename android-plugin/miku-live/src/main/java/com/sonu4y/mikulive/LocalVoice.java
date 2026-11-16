package com.sonu4y.mikulive;

import android.content.Context;
import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
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
 *  - FISH: her own voice, copied from a short clip made with Fish Audio (ZipVoice
 *    voice cloning). Slower than FAST, so it needs a decent phone.
 *
 * The models ship inside the APK (assets/voice-fast, assets/voice-fish) and are
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
            // her own voice, built with Fish Audio
            case "Fish":    return new Style(FISH, 0, 1.00f);
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
                    if (this == FISH) {
                        OfflineTtsZipVoiceModelConfig z = new OfflineTtsZipVoiceModelConfig();
                        z.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
                        z.setEncoder(new File(dir, "encoder.int8.onnx").getAbsolutePath());
                        z.setDecoder(new File(dir, "decoder.int8.onnx").getAbsolutePath());
                        z.setVocoder(new File(dir, "vocos_24khz.onnx").getAbsolutePath());
                        z.setLexicon(new File(dir, "lexicon.txt").getAbsolutePath());
                        z.setDataDir(new File(dir, "espeak-ng-data").getAbsolutePath());
                        z.setFeatScale(0.1f);
                        z.setTShift(0.5f);
                        z.setTargetRms(0.1f);
                        z.setGuidanceScale(1.0f);
                        m.setZipvoice(z);
                        refAudio = readWav(new File(dir, "ref.wav"));
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
                    tts = t;
                    long t2 = System.currentTimeMillis();
                    // warm up so the first real reply is quick (and see how fast this phone is)
                    GeneratedAudio w = raw(t, "Hello there, how are you today?", 0, 1.0f);
                    long warm = System.currentTimeMillis() - t2;
                    float secs = w.getSamples().length / (float) rate;
                    if (this == FISH && secs > 0 && warm / 1000f / secs > 0.9f) steps = 2;   // slower phone: fewer steps
                    LiveService.log(name + " voice ready: unpack " + (t1 - t0) + " ms, load " + (t2 - t1) + " ms, warm-up "
                            + warm + " ms for " + String.format(Locale.US, "%.1f", secs) + " s, " + m.getNumThreads()
                            + " threads of " + cores + (this == FISH ? ", steps " + steps : ""));
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
            synchronized (lock) { return raw(t, text, sid, speed); }
        }

        float[] refAudio;
        int steps = 3;

        private GeneratedAudio raw(OfflineTts t, String text, int sid, float speed) {
            if (this != FISH) return t.generate(text, sid, speed);
            GenerationConfig g = new GenerationConfig();
            g.setSpeed(speed);
            g.setSid(0);
            g.setSilenceScale(0.2f);
            g.setReferenceAudio(refAudio);
            g.setReferenceSampleRate(REF_RATE);
            g.setReferenceText(REF_TEXT);
            g.setNumSteps(steps);
            return t.generateWithConfig(text, g);
        }
    }

    /** What the reference clip says (made with Fish Audio). */
    static final String REF_TEXT = "Hello, I'm Miku-san. Alley vortex qema tick.";
    static final int REF_RATE = 24000;

    /** Reads a 16-bit mono WAV into floats. */
    static float[] readWav(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        }
        int p = 12;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, "US-ASCII");
            int size = (b[p + 4] & 255) | (b[p + 5] & 255) << 8 | (b[p + 6] & 255) << 16 | (b[p + 7] & 255) << 24;
            if (id.equals("data")) {
                int count = Math.min(size, b.length - p - 8) / 2;
                float[] out = new float[count];
                for (int i = 0; i < count; i++) {
                    short v = (short) ((b[p + 8 + 2 * i] & 255) | (b[p + 9 + 2 * i] << 8));
                    out[i] = v / 32768f;
                }
                return out;
            }
            p += 8 + size + (size & 1);
        }
        throw new Exception("reference clip has no audio");
    }

    static final Engine FAST = new Engine("Fast", "voice-fast", "voice-fast-v1", 22050,
            "model.onnx", "tokens.txt");
    static final Engine FISH = new Engine("Fish", "voice-fish", "voice-fish-v1", 24000,
            "encoder.int8.onnx", "decoder.int8.onnx", "vocos_24khz.onnx", "tokens.txt", "lexicon.txt", "ref.wav");

    /** Loads the engine for this voice in the background (fast engine always). */
    private static volatile boolean cleaned = false;

    static void prepareAsync(Context ctx, String voice) {
        if (!cleaned) {
            cleaned = true;
            final File files = ctx.getFilesDir();
            new Thread(() -> {   // free the space the old HD voices used
                for (String old : new String[]{"kokoro-v1", "kokoro-v2"}) {
                    File o = new File(files, old);
                    if (o.exists()) deleteAll(o);
                }
            }, "miku-voice-cleanup").start();
        }
        FAST.prepareAsync(ctx);
        Style st = style(voice);
        if (st != null && st.engine == FISH) FISH.prepareAsync(ctx);
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
        if (free < 200L * 1024 * 1024) throw new Exception("not enough free storage (" + (free >> 20) + " MB free)");
        for (String old : new String[]{"kokoro-v1", "kokoro-v2"}) {   // the old HD voices
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
