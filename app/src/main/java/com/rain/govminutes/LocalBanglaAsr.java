package com.rain.govminutes;

import android.content.Context;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Fully local Bengali ASR. No Android SpeechRecognizer and no network/API. */
public final class LocalBanglaAsr implements AutoCloseable {
    private static final String MODEL_DIR = "sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09";
    private final OnlineRecognizer recognizer;

    public LocalBanglaAsr(Context context) {
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(MODEL_DIR + "/encoder.onnx");
        transducer.setDecoder(MODEL_DIR + "/decoder.onnx");
        transducer.setJoiner(MODEL_DIR + "/joiner.onnx");

        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(MODEL_DIR + "/tokens.txt");
        model.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        model.setProvider("cpu");
        model.setModelType("zipformer2");
        model.setModelingUnit("cjkchar");

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setModelConfig(model);
        config.setEnableEndpoint(false);
        config.setDecodingMethod("greedy_search");
        config.setMaxActivePaths(4);

        recognizer = new OnlineRecognizer(context.getAssets(), config);
    }

    public String transcribeSegment(File wav, int sampleRate, long startMs, long endMs) throws IOException {
        long paddedStart = Math.max(0, startMs - 120);
        long paddedEnd = Math.max(paddedStart + 250, endMs + 160);
        float[] audio = readSegmentAsFloat(wav, sampleRate, paddedStart, paddedEnd);
        if (audio.length == 0) return "";

        int pad = Math.max(1, sampleRate / 5); // 200 ms silence on both sides
        float[] left = new float[pad];
        float[] right = new float[pad];
        OnlineStream stream = recognizer.createStream("");
        try {
            stream.acceptWaveform(left, sampleRate);
            stream.acceptWaveform(audio, sampleRate);
            stream.acceptWaveform(right, sampleRate);
            stream.inputFinished();
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            OnlineRecognizerResult result = recognizer.getResult(stream);
            String text = result == null ? "" : result.getText();
            return text == null ? "" : text.trim();
        } finally {
            stream.release();
        }
    }

    private static float[] readSegmentAsFloat(File wav, int sampleRate, long startMs, long endMs) throws IOException {
        long startSample = startMs * sampleRate / 1000L;
        long endSample = endMs * sampleRate / 1000L;
        if (endSample <= startSample) return new float[0];
        try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
            long dataStart = 44L;
            long totalSamples = Math.max(0, (raf.length() - dataStart) / 2L);
            startSample = Math.min(startSample, totalSamples);
            endSample = Math.min(endSample, totalSamples);
            int count = (int)Math.min(Integer.MAX_VALUE, Math.max(0, endSample - startSample));
            if (count <= 0) return new float[0];
            raf.seek(dataStart + startSample * 2L);
            byte[] bytes = new byte[count * 2];
            raf.readFully(bytes);
            float[] out = new float[count];
            for (int i = 0, j = 0; i < count; i++, j += 2) {
                int lo = bytes[j] & 0xff;
                int hi = bytes[j + 1];
                short s = (short)((hi << 8) | lo);
                out[i] = s / 32768f;
            }
            return out;
        }
    }

    @Override public void close() {
        recognizer.release();
    }
}
