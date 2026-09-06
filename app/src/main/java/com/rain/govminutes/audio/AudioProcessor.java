package com.rain.govminutes.audio;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

public final class AudioProcessor {
    public interface Progress { void onProgress(int percent, String stage); }

    public static void process(File input, File output, int sampleRate, Progress cb) throws IOException {
        short[] in = WavIO.readPcm16(input);
        float[] x = new float[in.length];
        for (int i = 0; i < in.length; i++) x[i] = in[i] / 32768f;
        if (cb != null) cb.onProgress(10, "Removing rumble");
        float rc = 1f / (2f * (float)Math.PI * 80f); float dt = 1f / sampleRate; float a = rc / (rc + dt); float prevX = 0, prevY = 0;
        for (int i = 0; i < x.length; i++) { float y = a * (prevY + x[i] - prevX); prevX = x[i]; prevY = y; x[i] = y; }
        if (cb != null) cb.onProgress(25, "Estimating room noise");
        int win = Math.max(1, sampleRate / 10); int nwin = (x.length + win - 1) / win; float[] rms = new float[nwin];
        for (int w = 0; w < nwin; w++) { int s = w * win, e = Math.min(x.length, s + win); double sum = 0; for (int i = s; i < e; i++) sum += x[i] * x[i]; rms[w] = (float)Math.sqrt(sum / Math.max(1, e - s) + 1e-12); }
        float[] sorted = rms.clone(); Arrays.sort(sorted); float noise = sorted[Math.min(sorted.length - 1, Math.max(0, sorted.length / 5))]; float gate = Math.max(0.004f, noise * 1.35f);
        if (cb != null) cb.onProgress(40, "Reducing steady noise");
        float env = 1f; float attack = (float)Math.exp(-1.0 / (0.025 * sampleRate)); float release = (float)Math.exp(-1.0 / (0.150 * sampleRate));
        for (int i = 0; i < x.length; i++) { float mag = Math.abs(x[i]); float target = mag < gate ? 0.30f : 1.0f; float k = target < env ? attack : release; env = k * env + (1-k) * target; x[i] *= env; }
        if (cb != null) cb.onProgress(55, "Balancing near and far voices");
        int agcWin = Math.max(1, sampleRate / 2); float targetRms = 0.105f; float lastGain = 1f;
        for (int s = 0; s < x.length; s += agcWin) { int e = Math.min(x.length, s + agcWin); double sum = 0; for (int i = s; i < e; i++) sum += x[i] * x[i]; float r = (float)Math.sqrt(sum / Math.max(1, e-s) + 1e-12); boolean speechLike = r > gate * 1.15f; float desired = speechLike ? targetRms / Math.max(r, 0.008f) : 1f; desired = clamp(desired, 0.55f, 4.2f); desired = 0.65f * lastGain + 0.35f * desired; lastGain = desired; for (int i = s; i < e; i++) x[i] *= desired; }
        if (cb != null) cb.onProgress(72, "Smoothing speech level");
        for (int i = 0; i < x.length; i++) { float v = x[i]; float av = Math.abs(v); if (av > 0.22f) { float over = av - 0.22f; av = 0.22f + over * 0.42f; v = Math.copySign(av, v); } x[i] = (float)Math.tanh(v * 1.18f) / (float)Math.tanh(1.18f); }
        if (cb != null) cb.onProgress(85, "Normalizing output");
        float peak = 1e-6f; for (float v : x) peak = Math.max(peak, Math.abs(v)); float master = Math.min(1f, 0.94f / peak); short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) out[i] = (short)Math.round(clamp(x[i] * master, -1f, 1f) * 32767f);
        WavIO.writePcm16(output, sampleRate, out); if (cb != null) cb.onProgress(100, "Processed audio ready");
    }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
