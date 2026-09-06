package com.rain.govminutes;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.rain.govminutes.audio.Diarizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public final class OnDeviceTranscriber {
    public interface Callback {
        void onProgress(int done, int total, String message);
        void onComplete(String transcript);
        void onError(String message);
    }

    private static final int ASR_RATE = 16000;
    private static final long MAX_CHUNK_MS = 20000;
    private static final long MIN_CHUNK_MS = 700;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean finishedOne = false;
    private int attemptToken = 0;
    private int successfulChunks = 0;
    private int failedChunks = 0;
    private int lastError = 0;

    public OnDeviceTranscriber(Context c) {
        context = c.getApplicationContext();
    }

    public void transcribe(File processedWav, int sampleRate, List<Diarizer.Segment> segments, Callback cb) {
        if (Build.VERSION.SDK_INT < 33) {
            cb.onError("Android 13 or newer is required for recorded-file transcription.");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            cb.onError("No Android speech recognition service is available on this phone.");
            return;
        }

        List<Diarizer.Segment> usable = splitUsableSegments(segments);
        if (usable.isEmpty()) {
            cb.onError("No usable speech segments detected in this recording.");
            return;
        }

        successfulChunks = 0;
        failedChunks = 0;
        lastError = 0;
        transcribeNext(processedWav, sampleRate, usable, 0, new StringBuilder(), cb);
    }

    private void transcribeNext(File wav, int inputRate, List<Diarizer.Segment> segs, int index,
                                StringBuilder out, Callback cb) {
        if (index >= segs.size()) {
            String text = compact(out.toString());
            if (text.isEmpty()) {
                cb.onError("Bangla recognition returned no text. Last recognizer error: " + errorName(lastError) +
                        ". This phone may have a speech engine but not usable Bangla recorded-audio support.");
            } else {
                cb.onComplete(text);
            }
            return;
        }

        Diarizer.Segment seg = segs.get(index);
        cb.onProgress(index, segs.size(), "Bangla ASR • Speaker " + seg.speaker + " • on-device first");

        main.post(() -> attemptSegment(wav, inputRate, seg, true, new Result() {
            @Override public void done(String text, String provider) {
                if (text != null && !text.trim().isEmpty()) {
                    successfulChunks++;
                    out.append("Speaker ").append(seg.speaker).append(":\n")
                            .append(text.trim()).append("\n\n");
                    cb.onProgress(index + 1, segs.size(), "Recognized with " + provider);
                    main.postDelayed(() -> transcribeNext(wav, inputRate, segs, index + 1, out, cb), 180);
                } else {
                    fallbackOrFinish(wav, inputRate, seg, segs, index, out, cb, true);
                }
            }

            @Override public void fail(int error, String provider) {
                lastError = error;
                fallbackOrFinish(wav, inputRate, seg, segs, index, out, cb, true);
            }
        }));
    }

    private void fallbackOrFinish(File wav, int inputRate, Diarizer.Segment seg,
                                  List<Diarizer.Segment> segs, int index, StringBuilder out,
                                  Callback cb, boolean trySystemFallback) {
        if (trySystemFallback) {
            cb.onProgress(index, segs.size(), "On-device result unavailable • trying built-in system recognizer (offline preferred)");
            main.postDelayed(() -> attemptSegment(wav, inputRate, seg, false, new Result() {
                @Override public void done(String text, String provider) {
                    if (text != null && !text.trim().isEmpty()) {
                        successfulChunks++;
                        out.append("Speaker ").append(seg.speaker).append(":\n")
                                .append(text.trim()).append("\n\n");
                        cb.onProgress(index + 1, segs.size(), "Recognized with " + provider);
                    } else {
                        failedChunks++;
                    }
                    main.postDelayed(() -> transcribeNext(wav, inputRate, segs, index + 1, out, cb), 220);
                }

                @Override public void fail(int error, String provider) {
                    lastError = error;
                    failedChunks++;
                    main.postDelayed(() -> transcribeNext(wav, inputRate, segs, index + 1, out, cb), 260);
                }
            }), 220);
        } else {
            failedChunks++;
            main.postDelayed(() -> transcribeNext(wav, inputRate, segs, index + 1, out, cb), 220);
        }
    }

    private interface Result {
        void done(String text, String provider);
        void fail(int error, String provider);
    }

    private void attemptSegment(File wav, int inputRate, Diarizer.Segment seg, boolean onDevice, Result result) {
        String provider = onDevice ? "on-device engine" : "Android system engine";
        try {
            if (onDevice) {
                if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    result.fail(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, provider);
                    return;
                }
            }

            destroyRecognizer();
            recognizer = onDevice
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    : SpeechRecognizer.createSpeechRecognizer(context);

            finishedOne = false;
            final int token = ++attemptToken;
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, ASR_RATE);
            if (Build.VERSION.SDK_INT >= 33) {
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
            }
            if (Build.VERSION.SDK_INT >= 34) {
                intent.putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true);
                intent.putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_CONFIDENCE, true);
            }

            StringBuilder collected = new StringBuilder();
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }

                @Override public void onError(int error) {
                    if (!finishedOne && token == attemptToken) {
                        finishedOne = true;
                        cleanupPipe(pipe);
                        destroyRecognizer();
                        result.fail(error, provider);
                    }
                }

                @Override public void onResults(Bundle results) {
                    append(results, collected);
                    finish();
                }

                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override public void onSegmentResults(Bundle segmentResults) {
                    append(segmentResults, collected);
                }

                @Override public void onEndOfSegmentedSession() {
                    finish();
                }

                private void finish() {
                    if (!finishedOne && token == attemptToken) {
                        finishedOne = true;
                        String text = collected.toString().trim();
                        cleanupPipe(pipe);
                        destroyRecognizer();
                        result.done(text, provider);
                    }
                }
            });

            recognizer.startListening(intent);
            new Thread(() -> feedSegment16k(wav, seg, inputRate, pipe[1]), "GM-ASRFeed").start();

            long duration = Math.max(1000, seg.endMs - seg.startMs);
            long timeoutMs = Math.max(15000, Math.min(50000, duration * 2 + 10000));
            main.postDelayed(() -> {
                if (!finishedOne && token == attemptToken) {
                    finishedOne = true;
                    cleanupPipe(pipe);
                    destroyRecognizer();
                    result.fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, provider);
                }
            }, timeoutMs);
        } catch (Exception e) {
            destroyRecognizer();
            result.fail(SpeechRecognizer.ERROR_CLIENT, provider);
        }
    }

    private static List<Diarizer.Segment> splitUsableSegments(List<Diarizer.Segment> segments) {
        List<Diarizer.Segment> out = new ArrayList<>();
        if (segments == null) return out;
        for (Diarizer.Segment s : segments) {
            long duration = s.endMs - s.startMs;
            if (duration < MIN_CHUNK_MS) continue;
            if (duration <= MAX_CHUNK_MS) {
                out.add(s);
                continue;
            }
            long p = s.startMs;
            while (p < s.endMs) {
                long e = Math.min(s.endMs, p + MAX_CHUNK_MS);
                if (e - p >= MIN_CHUNK_MS) {
                    out.add(new Diarizer.Segment(p, e, s.speaker, s.confidence));
                }
                p = e;
            }
        }
        return out;
    }

    private static void append(Bundle bundle, StringBuilder sb) {
        if (bundle == null) return;
        ArrayList<String> r = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r != null && !r.isEmpty()) {
            String text = r.get(0) == null ? "" : r.get(0).trim();
            if (!text.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(text);
            }
        }
    }

    private static void feedSegment16k(File wav, Diarizer.Segment seg, int inputRate,
                                       ParcelFileDescriptor writeEnd) {
        long startSample = Math.max(0, seg.startMs * inputRate / 1000L);
        long sampleCount = Math.max(0, (seg.endMs - seg.startMs) * inputRate / 1000L);
        long startBytes = 44 + startSample * 2L;
        int leadingPad = ASR_RATE / 5;  // 200 ms silence helps keep the first word
        int trailingPad = ASR_RATE / 4; // 250 ms silence helps the recognizer finish cleanly

        try (RandomAccessFile raf = new RandomAccessFile(wav, "r");
             FileOutputStream os = new FileOutputStream(writeEnd.getFileDescriptor())) {

            if (startBytes >= raf.length() || sampleCount <= 0) return;
            long availableSamples = Math.max(0, (raf.length() - startBytes) / 2L);
            int srcCount = (int) Math.min(sampleCount, Math.min(availableSamples, Integer.MAX_VALUE / 2L));
            if (srcCount <= 0) return;

            byte[] srcBytes = new byte[srcCount * 2];
            raf.seek(startBytes);
            raf.readFully(srcBytes);
            short[] src = new short[srcCount];
            for (int i = 0, p = 0; i < srcCount; i++, p += 2) {
                src[i] = (short) ((srcBytes[p] & 0xff) | (srcBytes[p + 1] << 8));
            }

            int outCount = Math.max(1, (int) ((long) srcCount * ASR_RATE / inputRate));
            byte[] out = new byte[(leadingPad + outCount + trailingPad) * 2];
            int pos = leadingPad * 2;

            for (int i = 0; i < outCount; i++) {
                double srcPos = i * (double) inputRate / ASR_RATE;
                int a = Math.min(srcCount - 1, (int) srcPos);
                int b = Math.min(srcCount - 1, a + 1);
                double frac = srcPos - a;
                int v = (int) Math.round(src[a] * (1.0 - frac) + src[b] * frac);
                out[pos++] = (byte) (v & 0xff);
                out[pos++] = (byte) ((v >> 8) & 0xff);
            }

            os.write(out);
            os.flush();
        } catch (Exception ignored) {
        } finally {
            try { writeEnd.close(); } catch (Exception ignored) { }
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private static void cleanupPipe(ParcelFileDescriptor[] pipe) {
        if (pipe == null) return;
        try { pipe[0].close(); } catch (Exception ignored) { }
        try { pipe[1].close(); } catch (Exception ignored) { }
    }

    private static String compact(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) return "";
        String[] blocks = trimmed.split("\\n\\n+");
        StringBuilder out = new StringBuilder();
        int last = -1;

        for (String block : blocks) {
            int c = block.indexOf(':');
            if (c < 0) continue;
            String head = block.substring(0, c).trim();
            String text = block.substring(c + 1).trim();
            if (text.isEmpty()) continue;

            int sp = -1;
            try { sp = Integer.parseInt(head.replace("Speaker", "").trim()); }
            catch (Exception ignored) { }

            if (sp == last && out.length() > 0) {
                out.append(' ').append(text);
            } else {
                if (out.length() > 0) out.append("\n\n");
                out.append("Speaker ").append(sp).append(":\n").append(text);
                last = sp;
            }
        }
        return out.toString().trim();
    }

    private static String errorName(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_AUDIO: return "audio input error";
            case SpeechRecognizer.ERROR_CLIENT: return "recognizer client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "microphone permission error";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "Bangla not supported by this engine";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "Bangla model unavailable/not downloaded";
            case SpeechRecognizer.ERROR_NETWORK: return "network unavailable to system recognizer";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "recognizer network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "speech heard but no Bangla match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "recognizer busy";
            case SpeechRecognizer.ERROR_SERVER: return "recognizer service error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no usable speech detected";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "too many recognition requests";
            default: return e == 0 ? "no detailed error returned" : "error code " + e;
        }
    }
}
