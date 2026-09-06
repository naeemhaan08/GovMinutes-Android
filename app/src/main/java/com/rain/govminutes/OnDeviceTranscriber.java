package com.rain.govminutes;

import android.content.Context;
import com.rain.govminutes.audio.Diarizer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Speaker-aware transcription using the Bengali model bundled inside GovMinutes. */
public final class OnDeviceTranscriber {
    public interface Callback {
        void onProgress(int done, int total, String message);
        void onComplete(String transcript);
        void onError(String message);
    }

    private static final long MAX_CHUNK_MS = 18000;
    private static final long MIN_CHUNK_MS = 650;
    private final Context context;

    public OnDeviceTranscriber(Context c) { context = c.getApplicationContext(); }

    public void transcribe(File processedWav, int sampleRate, List<Diarizer.Segment> segments, Callback cb) {
        List<Diarizer.Segment> usable = splitUsableSegments(segments);
        if (usable.isEmpty()) {
            cb.onError("No usable speech segments were detected.");
            return;
        }

        new Thread(() -> {
            StringBuilder raw = new StringBuilder();
            int recognized = 0;
            try (LocalBanglaAsr engine = new LocalBanglaAsr(context)) {
                cb.onProgress(0, usable.size(), "Loading embedded Bangla model…");
                for (int i = 0; i < usable.size(); i++) {
                    Diarizer.Segment s = usable.get(i);
                    cb.onProgress(i, usable.size(), "Offline Bangla ASR • Speaker " + s.speaker);
                    String text = engine.transcribeSegment(processedWav, sampleRate, s.startMs, s.endMs);
                    if (text != null && !text.trim().isEmpty()) {
                        recognized++;
                        raw.append("Speaker ").append(s.speaker).append(":\n")
                                .append(text.trim()).append("\n\n");
                    }
                }
                String finalText = compact(raw.toString());
                if (recognized == 0 || finalText.isEmpty()) {
                    cb.onError("The embedded Bangla model could not recover usable words from this recording. Try the processed audio again after placing the phone nearer the centre of the room.");
                } else {
                    cb.onProgress(usable.size(), usable.size(), "Offline Bangla transcript ready");
                    cb.onComplete(finalText);
                }
            } catch (Throwable e) {
                String m = e.getMessage();
                cb.onError("Embedded Bangla transcription failed" + (m == null || m.trim().isEmpty() ? "." : ": " + m));
            }
        }, "GM-LocalBanglaASR").start();
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
                if (e - p >= MIN_CHUNK_MS) out.add(new Diarizer.Segment(p, e, s.speaker, s.confidence));
                p = e;
            }
        }
        return out;
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
            try { sp = Integer.parseInt(head.replace("Speaker", "").trim()); } catch (Exception ignored) { }
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
}
