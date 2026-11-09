package com.sonu4y.mikulive;

import android.content.Context;
import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Her on-device voice (Kokoro, run by sherpa-onnx). Works offline, has no
 * usage limit, and speaks sentence by sentence so she starts talking fast.
 *
 * The model files ship inside the APK under assets/kokoro/. Only the small
 * espeak-ng-data folder is copied to app storage (espeak can't read assets).
 */
final class LocalVoice {
    /** A voice: Kokoro speaker id and how much higher/cuter it's played. */
    static final class Style {
        final int sid;
        final float pitch;
        Style(int sid, float pitch) { this.sid = sid; this.pitch = pitch; }
    }

    static Style style(String name) {
        if (name == null) return null;
        switch (name) {
            case "Miku":   return new Style(40, 1.00f);  // jf_tebukuro: high, anime-girl voice
            case "Sweet":  return new Style(38, 1.15f);  // jf_gongitsune
            case "Bubbly": return new Style(39, 1.15f);  // jf_nezumi
            case "Kawaii": return new Style(37, 1.25f);  // jf_alpha, extra high
            case "Heart":  return new Style(3, 1.10f);   // af_heart: natural English
            default: return null;                       // not a local voice (a Gemini voice)
        }
    }

    static final int MODEL_RATE = 24000;
    private static final String DIR = "kokoro";
    private static final Object lock = new Object();
    private static OfflineTts tts;
    private static volatile String failure = null;
    private static volatile boolean loading = false;

    /** Loads the model in the background (call early so the first reply is fast). */
    static void prepareAsync(Context ctx) {
        if (tts != null || loading || failure != null) return;
        final Context app = ctx.getApplicationContext();
        loading = true;
        new Thread(() -> {
            try { get(app); } catch (Throwable ignored) { }
        }, "miku-voice-load").start();
    }

    static String failure() { return failure; }
    static boolean ready() { return tts != null; }

    static OfflineTts get(Context ctx) throws Exception {
        synchronized (lock) {
            if (tts != null) return tts;
            if (failure != null) throw new Exception(failure);
            long t0 = System.currentTimeMillis();
            try {
                // the model is unpacked from the APK to app storage once; after that it loads straight from files
                File dir = unpack(ctx);
                long t1 = System.currentTimeMillis();
                OfflineTtsKokoroModelConfig k = new OfflineTtsKokoroModelConfig();
                k.setModel(new File(dir, "model.int8.onnx").getAbsolutePath());
                k.setVoices(new File(dir, "voices.bin").getAbsolutePath());
                k.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
                k.setLexicon(new File(dir, "lexicon-us-en.txt").getAbsolutePath());
                k.setDataDir(new File(dir, "espeak-ng-data").getAbsolutePath());
                k.setLang("en-us");
                k.setLengthScale(1.0f);
                OfflineTtsModelConfig m = new OfflineTtsModelConfig();
                m.setKokoro(k);
                int cores = Runtime.getRuntime().availableProcessors();
                m.setNumThreads(cores >= 8 ? 4 : Math.max(1, Math.min(2, cores)));
                m.setDebug(false);
                m.setProvider("cpu");
                OfflineTtsConfig c = new OfflineTtsConfig();
                c.setModel(m);
                c.setMaxNumSentences(1);
                c.setSilenceScale(0.2f);
                OfflineTts t = new OfflineTts(null, c);
                long t2 = System.currentTimeMillis();
                t.generate("Hi.", 40, 1.0f);   // warm up so the first real reply is quick
                LiveService.log("On-phone voice ready: unpack " + (t1 - t0) + " ms, load " + (t2 - t1) + " ms, warm-up "
                        + (System.currentTimeMillis() - t2) + " ms, " + m.getNumThreads() + " threads of " + cores);
                tts = t;
                return t;
            } catch (Throwable e) {
                failure = "on-phone voice failed to load: " + e;
                LiveService.log(failure);
                throw new Exception(failure);
            } finally {
                loading = false;
            }
        }
    }

    private static final String[] FILES = {"model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt"};

    private static File unpack(Context ctx) throws Exception {
        File root = new File(ctx.getFilesDir(), "kokoro-v2");
        File done = new File(root, ".done");
        if (done.exists()) return root;
        AssetManager am = ctx.getAssets();
        long free = ctx.getFilesDir().getUsableSpace();
        if (free < 200L * 1024 * 1024) throw new Exception("not enough free storage (" + (free >> 20) + " MB free, needs 160 MB)");
        // remove older copies
        File old = new File(ctx.getFilesDir(), "kokoro-v1");
        if (old.exists()) deleteAll(old);
        root.mkdirs();
        for (String f : FILES) copyAsset(am, DIR + "/" + f, new File(root, f));
        copyAssetDir(am, DIR + "/espeak-ng-data", new File(root, "espeak-ng-data"));
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
     * The next piece is made while the previous one plays. Blocks until done.
     */
    static void speak(Context ctx, String text, Style st, Sink sink) throws Exception {
        OfflineTts t = get(ctx);
        float speed = 1.0f / st.pitch;   // played back faster/higher, so speak slower to keep a normal pace
        for (String piece : chunks(text)) {
            GeneratedAudio a;
            long g0 = System.currentTimeMillis();
            synchronized (lock) { a = t.generate(piece, st.sid, speed); }
            float[] f = a.getSamples();
            LiveService.log(String.format(java.util.Locale.US, "Voice piece: %d chars -> %.1f s of audio in %d ms",
                    piece.length(), f.length / (float) MODEL_RATE, System.currentTimeMillis() - g0));
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
        return Math.round(MODEL_RATE * st.pitch);
    }
}
