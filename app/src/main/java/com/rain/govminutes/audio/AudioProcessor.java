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

        if (cb != null) cb.onProgress(8, "Cleaning low rumble");
        highPass(x, sampleRate, 68f);

        if (cb != null) cb.onProgress(24, "Measuring room and speech level");
        int frame = Math.max(1, sampleRate / 4); // 250 ms: slow enough to avoid pumping
        int frames = (x.length + frame - 1) / frame;
        float[] rms = new float[frames];
        for (int f = 0; f < frames; f++) {
            int s = f * frame;
            int e = Math.min(x.length, s + frame);
            rms[f] = rms(x, s, e);
        }

        float[] sorted = rms.clone();
        Arrays.sort(sorted);
        float noise = sorted[Math.min(sorted.length - 1, Math.max(0, sorted.length / 5))];
        float speechFloor = Math.max(0.0045f, noise * 1.55f);

        if (cb != null) cb.onProgress(40, "Gently reducing steady background noise");
        float[] gain = new float[frames];
        final float targetSpeechRms = 0.135f; // roughly -17.4 dBFS RMS
        for (int f = 0; f < frames; f++) {
            float r = rms[f];

            // Gentle expander only. Never crush quiet ambience to silence.
            float low = Math.max(0.0025f, noise * 1.05f);
            float high = Math.max(low + 0.001f, noise * 2.50f);
            float speechness = clamp((r - low) / (high - low), 0f, 1f);
            float denoiseGain = 0.78f + 0.22f * speechness;

            // Upward speech leveling: bring distant/quiet speech closer without making loud speech harsh.
            float levelGain = 1f;
            if (r > speechFloor) {
                levelGain = targetSpeechRms / Math.max(r, 0.008f);
                levelGain = clamp(levelGain, 0.90f, 2.85f);
            }
            gain[f] = denoiseGain * levelGain;
        }

        if (cb != null) cb.onProgress(58, "Balancing near and far voices smoothly");
        // Slow two-way smoothing prevents audible level jumps and pumping.
        float g = 1f;
        for (int f = 0; f < frames; f++) {
            g = 0.68f * g + 0.32f * gain[f];
            gain[f] = g;
        }
        g = gain[frames - 1];
        for (int f = frames - 2; f >= 0; f--) {
            g = 0.82f * g + 0.18f * gain[f];
            gain[f] = 0.55f * gain[f] + 0.45f * g;
        }

        // Interpolate frame gains sample-by-sample so there are no block boundary clicks.
        for (int f = 0; f < frames; f++) {
            int s = f * frame;
            int e = Math.min(x.length, s + frame);
            float g0 = gain[f];
            float g1 = gain[Math.min(frames - 1, f + 1)];
            int len = Math.max(1, e - s);
            for (int i = s; i < e; i++) {
                float t = (i - s) / (float) len;
                x[i] *= g0 + (g1 - g0) * t;
            }
        }

        if (cb != null) cb.onProgress(74, "Applying transparent peak protection");
        softLimit(x);

        if (cb != null) cb.onProgress(86, "Gently lifting the final meeting level");
        // Global speech loudness lift. Unlike the previous processor, this is allowed to boost.
        float global = finalSpeechLift(x, sampleRate, noise, speechFloor);
        float peak = peak(x);
        if (peak > 1e-6f) global = Math.min(global, 0.975f / peak);
        global = Math.max(0.80f, global);
        for (int i = 0; i < x.length; i++) x[i] *= global;
        softLimit(x);

        if (cb != null) cb.onProgress(96, "Writing clean processed audio");
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = (short) Math.round(clamp(x[i], -0.985f, 0.985f) * 32767f);
        }
        WavIO.writePcm16(output, sampleRate, out);
        if (cb != null) cb.onProgress(100, "Processed audio ready");
    }

    private static void highPass(float[] x, int sr, float hz) {
        float rc = 1f / (2f * (float) Math.PI * hz);
        float dt = 1f / sr;
        float a = rc / (rc + dt);
        float prevX = 0f, prevY = 0f;
        for (int i = 0; i < x.length; i++) {
            float y = a * (prevY + x[i] - prevX);
            prevX = x[i];
            prevY = y;
            x[i] = y;
        }
    }

    private static float finalSpeechLift(float[] x, int sr, float originalNoise, float originalSpeechFloor) {
        int frame = Math.max(1, sr / 4);
        List<Float> speech = new ArrayList<>();
        // Threshold is intentionally conservative after processing; it only measures likely speech frames.
        float threshold = Math.max(0.008f, Math.max(originalNoise * 1.35f, originalSpeechFloor * 0.85f));
        for (int s = 0; s < x.length; s += frame) {
            int e = Math.min(x.length, s + frame);
            float r = rms(x, s, e);
            if (r > threshold) speech.add(r);
        }
        if (speech.isEmpty()) return 1.12f;
        float[] a = new float[speech.size()];
        for (int i = 0; i < a.length; i++) a[i] = speech.get(i);
        Arrays.sort(a);
        float representative = a[a.length / 2];
        float desired = 0.155f / Math.max(0.02f, representative);
        return clamp(desired, 1.03f, 1.60f);
    }

    private static void softLimit(float[] x) {
        final float knee = 0.88f;
        final float headroom = 0.105f;
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            float av = Math.abs(v);
            if (av > knee) {
                float over = av - knee;
                float limited = knee + headroom * (1f - (float) Math.exp(-over / headroom));
                x[i] = Math.copySign(Math.min(0.985f, limited), v);
            }
        }
    }

    private static float rms(float[] x, int s, int e) {
        double q = 0;
        for (int i = s; i < e; i++) q += x[i] * x[i];
        return (float) Math.sqrt(q / Math.max(1, e - s) + 1e-12);
    }

    private static float peak(float[] x) {
        float p = 1e-6f;
        for (float v : x) p = Math.max(p, Math.abs(v));
        return p;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
