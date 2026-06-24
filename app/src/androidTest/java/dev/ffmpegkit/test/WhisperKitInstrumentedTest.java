package dev.ffmpegkit.test;

import android.Manifest;
import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.LibreTranslateProvider;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.TranslationProvider;
import com.arthenica.ffmpegkit.WhisperKit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Instrumented test: generates FR/EN/ES subtitle files from Vol_election.mp4
 * using FFmpegKit 8.1.7+ (all_channel_counts fix required).
 *
 * Prerequisites (handled by run_whisper_test.ps1):
 *   - Model at  getFilesDir()/ggml-tiny.bin       (pushed via adb + run-as)
 *   - Video at  /sdcard/Movies/Vol_election.mp4   (pushed via adb push)
 *
 * Output SRT files:
 *   /sdcard/Android/data/dev.ffmpegkit.test/files/Vol_election_fr.srt
 *   /sdcard/Android/data/dev.ffmpegkit.test/files/Vol_election_en.srt
 *   /sdcard/Android/data/dev.ffmpegkit.test/files/Vol_election_es.srt
 */
@RunWith(AndroidJUnit4.class)
public class WhisperKitInstrumentedTest {

    private static final String TAG = "WhisperTest";
    private static final String VIDEO_PATH = "/sdcard/Movies/Vol_election.mp4";

    private static final String[] LIBRE_TRANSLATE_INSTANCES = {
        "https://translate.cutie.dating",
        "https://translate.fedilab.app",
        "https://translate.terraprint.co",
        "https://translate.argosopentech.com",
        "https://libretranslate.de"
    };

    private static float[]    sPcm;
    private static WhisperKit sWhisperKit;
    private static File       sSrtDir;
    private static long       sGlobalStart;

    // ──────────────────────────────────────────────────── setup ──

    @BeforeClass
    public static void setupWhisper() throws Exception {
        sGlobalStart = System.currentTimeMillis();
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(ctx.getPackageName(),
                        Manifest.permission.READ_MEDIA_VIDEO);
        Log.i(TAG, "READ_MEDIA_VIDEO granted");

        sSrtDir = ctx.getExternalFilesDir(null);
        if (sSrtDir != null) sSrtDir.mkdirs();

        String modelPath = ctx.getFilesDir().getAbsolutePath() + "/ggml-base.bin";
        if (!new File(modelPath).exists()) {
            throw new IllegalStateException("Model not found at " + modelPath);
        }

        // Extract mono 16 kHz PCM WAV via FFmpegKit.
        // Requires 8.1.7+ which fixes the all_channel_counts crash in
        // fftools_ffmpeg_filter.c (abuffersink initialization).
        String wavPath = ctx.getCacheDir().getAbsolutePath() + "/whisper_audio.wav";
        Log.i(TAG, "Extracting PCM: " + VIDEO_PATH + " → " + wavPath);
        FFmpegSession session = FFmpegKit.execute(
                "-i " + VIDEO_PATH + " -vn -ar 16000 -ac 1 -c:a pcm_s16le -y " + wavPath);
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            throw new IOException(
                    "FFmpegKit extraction failed (rc=" + session.getReturnCode() + "):\n"
                    + session.getLogsAsString());
        }

        // Skip 44-byte WAV header; read s16le samples and normalize to float [-1,1]
        byte[] bytes = Files.readAllBytes(Paths.get(wavPath));
        ShortBuffer sb = ByteBuffer.wrap(bytes, 44, bytes.length - 44)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        sPcm = new float[sb.remaining()];
        for (int i = 0; i < sPcm.length; i++) {
            sPcm[i] = sb.get(i) / 32768.0f;
        }
        Log.i(TAG, "PCM loaded: " + sPcm.length + " samples (~"
                + (sPcm.length / 16000) + "s at 16 kHz)");

        Log.i(TAG, "Loading WhisperKit model: " + modelPath);
        sWhisperKit = WhisperKit.createFromFile(modelPath);
        Log.i(TAG, "WhisperKit ready. System: " + sWhisperKit.getSystemInfo());
    }

    @AfterClass
    public static void teardown() {
        if (sWhisperKit != null) {
            try { sWhisperKit.close(); } catch (Exception e) {
                Log.w(TAG, "close() failed", e);
            }
        }
        Log.i(TAG, "=== Total test time: "
                + ((System.currentTimeMillis() - sGlobalStart) / 1000) + "s ===");
    }

    // ──────────────────────────────────────────────────── tests ──

    @Test(timeout = 600_000)
    public void test1_frenchSubtitles() throws Exception {
        Log.i(TAG, "--- test1: French SRT ---");
        long t0 = System.currentTimeMillis();
        String srt = sWhisperKit.transcribeToSrt(sPcm);
        Log.i(TAG, "FR SRT [" + (System.currentTimeMillis() - t0) + "ms] — first 300 chars:\n"
                + srt.substring(0, Math.min(300, srt.length())));
        assertNotNull("transcribeToSrt() returned null", srt);
        assertFalse("transcribeToSrt() returned empty", srt.trim().isEmpty());
        writeSrt("Vol_election_fr.srt", srt);
        Log.i(TAG, "Vol_election_fr.srt saved (" + srt.length() + " chars)");
    }

    @Test(timeout = 600_000)
    public void test2_englishSubtitles() throws Exception {
        Log.i(TAG, "--- test2: English SRT (Whisper translate) ---");
        long t0 = System.currentTimeMillis();
        String srt = sWhisperKit.translateToSrt(sPcm);
        Log.i(TAG, "EN SRT [" + (System.currentTimeMillis() - t0) + "ms] — first 300 chars:\n"
                + srt.substring(0, Math.min(300, srt.length())));
        assertNotNull("translateToSrt() returned null", srt);
        assertFalse("translateToSrt() returned empty", srt.trim().isEmpty());
        writeSrt("Vol_election_en.srt", srt);
        Log.i(TAG, "Vol_election_en.srt saved (" + srt.length() + " chars)");
    }

    @Test(timeout = 600_000)
    public void test3_spanishSubtitles() throws Exception {
        Log.i(TAG, "--- test3: Spanish SRT (Whisper FR transcription → LibreTranslate FR→ES) ---");
        String esSrt = null;
        String usedInstance = null;
        for (String instance : LIBRE_TRANSLATE_INSTANCES) {
            try {
                Log.i(TAG, "Trying LibreTranslate FR→ES: " + instance);
                TranslationProvider lt = new LibreTranslateProvider(instance);
                long t0 = System.currentTimeMillis();
                esSrt = sWhisperKit.transcribeToSrtAndTranslate(sPcm, lt, "es");
                Log.i(TAG, "ES SRT via " + instance + " [" + (System.currentTimeMillis() - t0) + "ms]");
                usedInstance = instance;
                break;
            } catch (Exception e) {
                Log.w(TAG, "Instance " + instance + " failed: " + e.getMessage());
            }
        }
        if (esSrt != null && !esSrt.trim().isEmpty()) {
            writeSrt("Vol_election_es.srt", esSrt);
            Log.i(TAG, "Vol_election_es.srt saved via " + usedInstance);
        } else {
            Log.w(TAG, "Spanish subtitles NOT generated — all LibreTranslate instances failed.");
            writeSrt("Vol_election_es.srt",
                    "1\n00:00:00,000 --> 00:00:03,000\n"
                    + "[Subtítulos en español no disponibles — se requiere conexión de red (LibreTranslate)]\n");
        }
        // Best-effort — don't fail the suite if LibreTranslate is down
    }

    // ──────────────────────────────────────────────────── helpers ──

    private static void writeSrt(String filename, String content) throws IOException {
        File file = new File(sSrtDir, filename);
        Files.write(file.toPath(),
                content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }
}
