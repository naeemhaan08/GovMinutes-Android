# GovMinutes Android V1

Local-first Android meeting recorder optimized for Samsung Galaxy and Google Pixel devices.

## V1 outputs
Every meeting session creates:
- `Original.wav` — untouched 48 kHz mono PCM recording
- `Processed.wav` — local cleanup + far/near voice leveling
- `Bangla_Transcript.txt` — speaker-labeled Bangla transcript
- `Speaker_Timeline.csv` — internal diarization timeline

## How it works
1. **Record:** foreground `AudioRecord` service, 48 kHz PCM. It prefers `UNPROCESSED` input when Android reports support; otherwise it uses `VOICE_RECOGNITION`.
2. **Process:** 80 Hz rumble removal, conservative noise gate, slow speech AGC for near/far leveling, gentle compression, limiter.
3. **Speaker diarization:** fully local conservative clustering. Short utterances do not create new speakers, and a whole-meeting merge pass collapses similar clusters.
4. **Bangla transcription:** on Android 13+ the app injects each detected speaker-turn into Android's **on-device** `SpeechRecognizer` with locale `bn-BD`. No external transcription API is used. If the phone has no compatible on-device Bangla recognizer/model, the app reports that instead of silently using cloud recognition.

## Important V1 note
The included diarizer is a **model-free fallback**, built to avoid the previous “speaker explosion” failure mode. For production-grade diarization in very reverberant rooms, replace the `Diarizer` implementation with a local neural speaker-embedding/segmentation model while keeping the same `Segment` interface. The rest of the app does not need to change.

## Recommended devices
- Samsung Galaxy S / Z / recent A series
- Google Pixel
- Android 13+ required for transcribing recorded PCM via `EXTRA_AUDIO_SOURCE`
- Android 14+ can expose richer recognition timing/confidence when supported by the recognizer

## Build APK in Android Studio
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. **Build > Build APK(s)**.
4. Install `app-debug.apk` on the phone.

## Build APK with GitHub Actions
The repository includes `.github/workflows/build-apk.yml`. Push the project to a GitHub repository and run **Build GovMinutes APK** from Actions. The APK is uploaded as the `GovMinutes-debug-apk` artifact.

## Privacy
No Internet permission is declared. Meeting audio stays in the app's external files directory and local Android speech recognition is explicitly requested.
