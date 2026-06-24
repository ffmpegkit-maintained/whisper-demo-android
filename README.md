# ffmpegkit-test-81 — WhisperKit integration DEMO

Test app for FFmpegKit 8.1 Full — validates the Whisper JNI bridge end-to-end.

## Setup (once CI build is done)

### 1. Drop the AAR into `app/libs/`

Retrieve the AAR from `ffmpegkit-maintained/ci-cache-private`, branch `81-full`:

```powershell
git clone --branch 81-full https://github.com/ffmpegkit-maintained/ci-cache-private --depth 1 D:\tmp\ci-cache-full
copy D:\tmp\ci-cache-full\*.aar D:\Projects\ffmpegkit-test-81\app\libs\
```

Remove `app/libs/PLACEHOLDER.txt` after dropping in the AAR.

### 2. Push the test video to the Pixel 7 Pro

Connect the Pixel 7 Pro via USB, enable USB debugging (Settings → Developer Options), then:

```powershell
adb push "D:\Videos\Vol_election.mp4" /sdcard/Movies/
```

### 3. Open in Android Studio

Open `D:\Projects\ffmpegkit-test-81\` in Android Studio. Let Gradle sync.

### 4. Run on device

Select the Pixel 7 Pro as target and click Run (Shift+F10).

---

## What the test covers

| Step | Method | Expected result |
|---|---|---|
| 1 | `FFmpegKit` PCM extraction | 16 kHz mono f32 PCM without error |
| 2 | `wk.transcribe(pcm)` | French transcription text |
| 3 | `wk.transcribe(pcm, "fr")` | Same, slightly faster with language hint |
| 4 | `wk.transcribeToSrt(pcm)` | SRT with timestamps |
| 5 | `wk.translate(pcm)` | English translation (Whisper built-in) |
| 6 | `wk.translateToSrt(pcm)` | English SRT |

**The test is considered passing** if all 6 steps produce non-empty output without exceptions.

### After passing: optional translation provider test

To also test `DeepLTranslationProvider`, add a snippet to `runWhisper()` after the existing tests:

```java
TranslationProvider deepl = new DeepLTranslationProvider("YOUR_DEEPL_KEY");
String frSrt = wk.transcribeToSrtAndTranslate(pcm, deepl, "EN-US");
sb.append("── transcribeToSrtAndTranslate(DeepL → EN) ──\n").append(truncate(frSrt, 400));
```

---

## Only publish Gumroad products after this test passes
