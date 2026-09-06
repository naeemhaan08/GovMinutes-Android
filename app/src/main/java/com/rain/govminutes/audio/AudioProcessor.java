package com.rain.govminutes.audio;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AudioProcessor {
    public interface Progress { void onProgress(int percent, String stage); }

    public static void process(File input, File output, int sampleRate, Progress cb) throws IOException {
        short[] in = WavIO.readPcm16(input);
        if (in.length == 0) throw new IOException("Empty recording");

        float[] x = new float[in.length];
        for (int i = 0; i < in.length; i++) x[i] = in[i] / 32768f;

        if (cb != null) cb.onProgress(7, "Removing room rumble");
        highPass(x, sampleRate, 72f);
        notch(x, sampleRate, 100f, 14f); // common 50 Hz mains harmonic, away from most speech energy
        lowPass(x, sampleRate, 10500f);  // tame phone hiss without dulling speech

        if (cb != null) cb.onProgress(22, "Measuring room noise and speaker distance");
        final int frame = Math.max(1, sampleRate / 10); // 100 ms
        final int frames = (x.length + frame - 1) / frame;
        float[] rms = new float[frames];
        for (int f = 0; f < frames; f++) {
            int s = f * frame, e = Math.min(x.length, s + frame);
            rms[f] = rms(x, s, e);
        }
        float[] sorted = rms.clone();
        Arrays.sort(sorted);
        float noise = sorted[Math.min(sorted.length - 1, Math.max(0, (int)(sorted.length * 0.15)))];
        float speechFloor = Math.max(0.0038f, noise * 1.38f);

        if (cb != null) cb.onProgress(38, "Cleaning steady background sound");
        float[] frameGain = new float[frames];
        final float target = 0.155f; // target before final master lift
        for (int f = 0; f < frames; f++) {
            float r = rms[f];
            float speechness = clamp((r - noise * 1.05f) / Math.max(0.002f, noise * 2.0f), 0f, 1f);

            // Non-speech is lowered gently. Speech is never gated or chopped.
            float ambienceGain = 0.64f + 0.36f * speechness;
            float level = 1f;
            if (r > speechFloor) {
                // Upward compression. Quiet/distant speech receives more help than near speech,
                // but the exponent keeps the transition natural instead of forcing all voices identical.
                float ratio = target / Math.max(r, 0.006f);
                level = (float)Math.pow(Math.max(0.05f, ratio), 0.72);
                level = clamp(level, 0.82f, 5.6f);
            }
            frameGain[f] = ambienceGain * level;
        }

        if (cb != null) cb.onProgress(55, "Gently balancing near and far voices");
        // Slow attack when raising distant speech, faster release when a close speaker starts.
        float g = 1f;
        for (int f = 0; f < frames; f++) {
            float wanted = frameGain[f];
            float k = wanted > g ? 0.13f : 0.34f;
            g += (wanted - g) * k;
            frameGain[f] = g;
        }
        // Light backward smoothing so the gain starts moving just before each change.
        g = frameGain[frames - 1];
        for (int f = frames - 2; f >= 0; f--) {
            g += (frameGain[f] - g) * 0.10f;
            frameGain[f] = frameGain[f] * 0.78f + g * 0.22f;
        }

        for (int f = 0; f < frames; f++) {
            int s = f * frame, e = Math.min(x.length, s + frame);
            float g0 = frameGain[f];
            float g1 = frameGain[Math.min(frames - 1, f + 1)];
            int len = Math.max(1, e - s);
            for (int i = s; i < e; i++) {
                float t = (i - s) / (float)len;
                x[i] *= g0 + (g1 - g0) * t;
            }
        }

        if (cb != null) cb.onProgress(72, "Lifting meeting volume smoothly");
        // Important: do not let one loud peak prevent the rest of the meeting from being lifted.
        // Loud peaks are handled by the transparent compressor afterwards.
        float master = speechMasterGain(x, sampleRate, speechFloor);
        for (int i = 0; i < x.length; i++) x[i] *= master;

        if (cb != null) cb.onProgress(84, "Protecting close-speaker peaks");
        transparentCompressor(x, 0.30f, 3.0f);
        softLimiter(x, 0.94f);

        if (cb != null) cb.onProgress(95, "Writing processed audio");
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = (short)Math.round(clamp(x[i], -0.985f, 0.985f) * 32767f);
        }
        WavIO.writePcm16(output, sampleRate, out);
        if (cb != null) cb.onProgress(100, "Processed audio ready • voices gently lifted and balanced");
    }

    private static float speechMasterGain(float[] x, int sr, float floor) {
        int frame = Math.max(1, sr / 4);
        List<Float> speech = new ArrayList<>();
        for (int s = 0; s < x.length; s += frame) {
            int e = Math.min(x.length, s + frame);
            float r = rms(x, s, e);
            if (r > Math.max(0.008f, floor * 0.90f)) speech.add(r);
        }
        if (speech.isEmpty()) return 1.18f;
        float[] a = new float[speech.size()];
        for (int i = 0; i < a.length; i++) a[i] = speech.get(i);
        Arrays.sort(a);
        float representative = a[Math.min(a.length - 1, (int)(a.length * 0.55f))];
        float wanted = 0.185f / Math.max(0.025f, representative);
        return clamp(wanted, 1.08f, 2.05f);
    }

    private static void transparentCompressor(float[] x, float threshold, float ratio) {
        float knee = 0.08f;
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            float a = Math.abs(v);
            if (a <= threshold - knee) continue;
            float compressed;
            if (a >= threshold + knee) {
                compressed = threshold + (a - threshold) / ratio;
            } else {
                float t = (a - (threshold - knee)) / (2f * knee);
                float hard = threshold + (a - threshold) / ratio;
                compressed = a * (1f - t * t) + hard * (t * t);
            }
            x[i] = Math.copySign(compressed, v);
        }
    }

    private static void softLimiter(float[] x, float ceiling) {
        for (int i = 0; i < x.length; i++) {
            float v = x[i], a = Math.abs(v);
            if (a > ceiling) {
                float over = a - ceiling;
                a = ceiling + 0.045f * (1f - (float)Math.exp(-over / 0.045f));
                x[i] = Math.copySign(Math.min(0.985f, a), v);
            }
        }
    }

    private static void highPass(float[] x, int sr, float hz) {
        float rc = 1f / (2f * (float)Math.PI * hz), dt = 1f / sr;
        float a = rc / (rc + dt), px = 0f, py = 0f;
        for (int i = 0; i < x.length; i++) {
            float y = a * (py + x[i] - px); px = x[i]; py = y; x[i] = y;
        }
    }

    private static void lowPass(float[] x, int sr, float hz) {
        float rc = 1f / (2f * (float)Math.PI * hz), dt = 1f / sr;
        float a = dt / (rc + dt), y = 0f;
        for (int i = 0; i < x.length; i++) { y += a * (x[i] - y); x[i] = y; }
    }

    private static void notch(float[] x, int sr, float hz, float q) {
        double w0 = 2.0 * Math.PI * hz / sr;
        double alpha = Math.sin(w0) / (2.0 * q);
        double b0 = 1, b1 = -2 * Math.cos(w0), b2 = 1;
        double a0 = 1 + alpha, a1 = -2 * Math.cos(w0), a2 = 1 - alpha;
        b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double in = x[i];
            double y = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = in; y2 = y1; y1 = y;
            x[i] = (float)y;
        }
    }

    private static float rms(float[] x, int s, int e) {
        double q = 0;
        for (int i = s; i < e; i++) q += x[i] * x[i];
        return (float)Math.sqrt(q / Math.max(1, e - s) + 1e-12);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
