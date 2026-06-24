package dev.ffmpegkit.test;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.MediaController;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.LibreTranslateProvider;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.TranslationProvider;
import com.arthenica.ffmpegkit.WhisperKit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Demo app for the ffmpegkit-maintained Full 8.1 LTS AAR.
 *
 * Pipeline:
 *   Video → FFmpegKit (audio extract) → WAV s16le
 *        → WhisperKit (transcribe/translate) → FR / EN / ES SRT
 *        → VideoView with synchronized subtitle overlay
 *        → FFmpegKit (burn subtitles into MP4)
 *
 * 100% on-device — only the optional ES LibreTranslate step requires internet.
 */
public class MainActivity extends Activity {

    private static final String TAG          = "WhisperDemo";
    private static final String TINY_URL     = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin";
    private static final String BASE_URL     = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin";
    private static final int    REQ_PERM     = 1;
    private static final int    REQ_PICK     = 2;

    // ── views ──────────────────────────────────────────────────────────────────
    private TextView     tvModelStatus, tvProgress;
    private Button       btnDownloadTiny, btnDownloadBase;
    private Button       btnBrowse, btnAnalyze, btnExport;
    private Button       btnFr, btnEn, btnEs;
    private EditText     etVideoPath;
    private VideoView    videoView;
    private TextView     tvSubtitle;
    private LinearLayout playerCard, langCard, statsCard;
    private TextView     tvStatTime, tvStatModel, tvStatFfmpeg, tvStatInfo;

    // ── state ──────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private final List<SubtitleEntry>[] parsedSrt = new List[3]; // [0]=FR [1]=EN [2]=ES
    private final String[]              srtData   = new String[3];
    private int     currentLang = 0;
    private Uri     pickedUri;             // set when user uses the file picker
    private boolean analysisOk  = false;

    // ── threading ──────────────────────────────────────────────────────────────
    private final Handler         uiHandler       = new Handler(Looper.getMainLooper());
    private final Handler         subtitleHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor        = Executors.newSingleThreadExecutor();
    private Runnable              subtitleLoop;

    // ══════════════════════════════════════════════════════════════ lifecycle ══

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvModelStatus   = findViewById(R.id.tvModelStatus);
        tvProgress      = findViewById(R.id.tvProgress);
        btnDownloadTiny = findViewById(R.id.btnDownloadTiny);
        btnDownloadBase = findViewById(R.id.btnDownloadBase);
        btnBrowse       = findViewById(R.id.btnBrowse);
        btnAnalyze      = findViewById(R.id.btnAnalyze);
        btnExport       = findViewById(R.id.btnExport);
        btnFr           = findViewById(R.id.btnFr);
        btnEn           = findViewById(R.id.btnEn);
        btnEs           = findViewById(R.id.btnEs);
        etVideoPath     = findViewById(R.id.etVideoPath);
        videoView       = findViewById(R.id.videoView);
        tvSubtitle      = findViewById(R.id.tvSubtitle);
        playerCard      = findViewById(R.id.playerCard);
        langCard        = findViewById(R.id.langCard);
        statsCard       = findViewById(R.id.statsCard);
        tvStatTime      = findViewById(R.id.tvStatTime);
        tvStatModel     = findViewById(R.id.tvStatModel);
        tvStatFfmpeg    = findViewById(R.id.tvStatFfmpeg);
        tvStatInfo      = findViewById(R.id.tvStatInfo);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(videoView);
        videoView.setMediaController(mc);

        updateModelStatus();

        btnDownloadTiny.setOnClickListener(v -> downloadModel("ggml-tiny.bin", TINY_URL));
        btnDownloadBase.setOnClickListener(v -> downloadModel("ggml-base.bin", BASE_URL));
        btnBrowse.setOnClickListener(v -> openFilePicker());
        btnAnalyze.setOnClickListener(v -> requestPermissionAndAnalyze());
        btnExport.setOnClickListener(v -> exportWithSubtitles());
        btnFr.setOnClickListener(v -> selectLanguage(0));
        btnEn.setOnClickListener(v -> selectLanguage(1));
        btnEs.setOnClickListener(v -> selectLanguage(2));
    }

    @Override
    protected void onPause()   { super.onPause();   stopSubtitleLoop(); }
    @Override
    protected void onDestroy() { super.onDestroy(); stopSubtitleLoop(); executor.shutdownNow(); }

    // ══════════════════════════════════════════════════════════════════ model ══

    private File modelFile() {
        File base = new File(getFilesDir(), "ggml-base.bin");
        File tiny = new File(getFilesDir(), "ggml-tiny.bin");
        if (base.exists()) return base;
        if (tiny.exists()) return tiny;
        return base;
    }

    private void updateModelStatus() {
        File base = new File(getFilesDir(), "ggml-base.bin");
        File tiny = new File(getFilesDir(), "ggml-tiny.bin");
        if (base.exists()) {
            tvModelStatus.setText("ggml-base.bin  (" + (base.length() >> 20) + " MB)");
        } else if (tiny.exists()) {
            tvModelStatus.setText("ggml-tiny.bin  (" + (tiny.length() >> 20) + " MB)"
                    + "  — download base for better accuracy");
        } else {
            tvModelStatus.setText("No model — download one below");
        }
    }

    private void downloadModel(String filename, String url) {
        btnDownloadTiny.setEnabled(false);
        btnDownloadBase.setEnabled(false);
        setProgress("Downloading " + filename + "…");

        executor.execute(() -> {
            File dest = new File(getFilesDir(), filename);
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(120_000);
                conn.connect();
                int total = conn.getContentLength();
                try (InputStream in  = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int read, done = 0;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        done += read;
                        if (total > 0) setProgress("Downloading " + filename + "… " + done * 100 / total + "%");
                    }
                }
                uiHandler.post(() -> {
                    updateModelStatus();
                    setProgress(filename + " ready.");
                    btnDownloadTiny.setEnabled(true);
                    btnDownloadBase.setEnabled(true);
                });
            } catch (Exception e) {
                if (dest.exists()) dest.delete();
                uiHandler.post(() -> {
                    tvModelStatus.setText("Download failed: " + e.getMessage());
                    btnDownloadTiny.setEnabled(true);
                    btnDownloadBase.setEnabled(true);
                });
            }
        });
    }

    // ════════════════════════════════════════════════════════════ file picker ══

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // Persist read permission across reboots
                int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if ((data.getFlags() & flags) != 0) {
                    getContentResolver().takePersistableUriPermission(uri, flags);
                }
                pickedUri = uri;
                etVideoPath.setText(uri.toString());
                setProgress("Video selected via file picker.");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════ permissions ══

    private void requestPermissionAndAnalyze() {
        // File picker URIs don't need READ_MEDIA_VIDEO; only direct paths do.
        if (pickedUri == null) {
            String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? Manifest.permission.READ_MEDIA_VIDEO
                    : Manifest.permission.READ_EXTERNAL_STORAGE;
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{perm}, REQ_PERM);
                return;
            }
        }
        runAnalysis();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        if (req == REQ_PERM && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            runAnalysis();
        } else {
            setProgress("Storage permission denied — use the file picker (…) instead.");
        }
    }

    // ═══════════════════════════════════════════════════════════════ analysis ══

    private void runAnalysis() {
        File model = modelFile();
        if (!model.exists()) {
            setProgress("No Whisper model — download one first.");
            return;
        }

        // Validate video source
        final Uri  videoUri;
        final String videoPathStr;
        if (pickedUri != null) {
            videoUri     = pickedUri;
            videoPathStr = null;
        } else {
            videoUri     = null;
            videoPathStr = etVideoPath.getText().toString().trim();
            if (!new File(videoPathStr).exists()) {
                setProgress("Video not found: " + videoPathStr
                        + "\nUse the … button to pick a file, or push via:\n"
                        + "  adb push <video> /sdcard/Movies/");
                return;
            }
        }

        btnAnalyze.setEnabled(false);
        btnExport.setEnabled(false);
        analysisOk = false;

        long globalStart = System.currentTimeMillis();
        String wavPath   = getCacheDir() + "/whisper_audio.wav";

        executor.execute(() -> {
            // ── Step 1: audio extraction ────────────────────────────────────────
            setProgress("1/4  Extracting audio (FFmpegKit 8.1)…");

            String ffmpegInput;
            ParcelFileDescriptor pfd = null;
            if (videoUri != null) {
                // Content URI: open a file descriptor and pass via /proc/self/fd/<n>
                // so FFmpegKit (running in-process) can read it without a copy.
                try {
                    pfd = getContentResolver().openFileDescriptor(videoUri, "r");
                    ffmpegInput = "/proc/self/fd/" + pfd.getFd();
                } catch (IOException e) {
                    setProgress("Cannot open video URI: " + e.getMessage());
                    uiHandler.post(() -> btnAnalyze.setEnabled(true));
                    return;
                }
            } else {
                ffmpegInput = videoPathStr;
            }

            FFmpegSession session = FFmpegKit.execute(
                    "-i " + ffmpegInput + " -vn -ar 16000 -ac 1 -c:a pcm_s16le -y " + wavPath);

            if (pfd != null) { try { pfd.close(); } catch (IOException ignored) {} }

            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                setProgress("FFmpegKit error (rc=" + session.getReturnCode() + "):\n"
                        + session.getLogsAsString());
                uiHandler.post(() -> btnAnalyze.setEnabled(true));
                return;
            }

            // ── Step 2: load PCM ─────────────────────────────────────────────────
            float[] pcm;
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(wavPath));
                ShortBuffer sb = ByteBuffer.wrap(bytes, 44, bytes.length - 44)
                        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                pcm = new float[sb.remaining()];
                for (int i = 0; i < pcm.length; i++) pcm[i] = sb.get(i) / 32768.0f;
                Log.d(TAG, "PCM: " + pcm.length + " samples (~" + pcm.length / 16000 + "s)");
            } catch (IOException e) {
                setProgress("PCM load error: " + e.getMessage());
                uiHandler.post(() -> btnAnalyze.setEnabled(true));
                return;
            }

            // ── Step 3 & 4: WhisperKit ──────────────────────────────────────────
            try (WhisperKit wk = WhisperKit.createFromFile(model.getAbsolutePath())) {
                String sysInfo = wk.getSystemInfo();

                setProgress("2/4  Transcribing French subtitles…");
                long t0 = System.currentTimeMillis();
                srtData[0] = wk.transcribeToSrt(pcm);
                long tFr = System.currentTimeMillis() - t0;

                setProgress("3/4  Translating to English…");
                t0 = System.currentTimeMillis();
                srtData[1] = wk.translateToSrt(pcm);
                long tEn = System.currentTimeMillis() - t0;

                setProgress("4/4  Translating to Spanish (LibreTranslate, best-effort)…");
                srtData[2] = bestEffortSpanish(wk, pcm);

                long total = System.currentTimeMillis() - globalStart;
                for (int i = 0; i < 3; i++) parsedSrt[i] = parseSrt(srtData[i]);

                final Uri   playUri  = videoUri;
                final String playPath = videoPathStr;
                final String statTime = "Processing: " + (total / 1000) + "s total"
                        + "  (FR " + (tFr / 1000) + "s · EN " + (tEn / 1000) + "s)";
                final String statMod  = "Model: " + model.getName()
                        + "  (" + (model.length() >> 20) + " MB)";
                final String statFf   = "FFmpegKit 8.1 LTS · FFmpeg n8.1.2 · NDK r27c";
                final String statInfo = "CPU: " + sysInfo;

                uiHandler.post(() -> {
                    tvStatTime.setText(statTime);
                    tvStatModel.setText(statMod);
                    tvStatFfmpeg.setText(statFf);
                    tvStatInfo.setText(statInfo);
                    statsCard.setVisibility(View.VISIBLE);
                    playerCard.setVisibility(View.VISIBLE);
                    langCard.setVisibility(View.VISIBLE);
                    selectLanguage(0);
                    if (playUri != null) {
                        videoView.setVideoURI(playUri);
                    } else {
                        videoView.setVideoPath(playPath);
                    }
                    videoView.requestFocus();
                    videoView.start();
                    startSubtitleLoop();
                    analysisOk = true;
                    btnAnalyze.setEnabled(true);
                    btnExport.setEnabled(true);
                    setProgress("Done. Tap FR / EN / ES to switch subtitle language.");
                });

            } catch (IOException e) {
                setProgress("WhisperKit error: " + e.getMessage());
                Log.e(TAG, "WhisperKit failed", e);
                uiHandler.post(() -> btnAnalyze.setEnabled(true));
            }
        });
    }

    private String bestEffortSpanish(WhisperKit wk, float[] pcm) {
        String[] instances = {
            "https://translate.terraprint.co",
            "https://translate.argosopentech.com",
            "https://libretranslate.de"
        };
        for (String url : instances) {
            try {
                TranslationProvider lt = new LibreTranslateProvider(url);
                String result = wk.transcribeToSrtAndTranslate(pcm, lt, "es");
                if (result != null && !result.trim().isEmpty()) return result;
            } catch (Exception e) {
                Log.w(TAG, "LibreTranslate " + url + " — " + e.getMessage());
            }
        }
        return "1\n00:00:00,000 --> 00:00:03,000\n"
                + "[Traducción no disponible — LibreTranslate inaccesible]\n";
    }

    // ══════════════════════════════════════════════════════════════ playback ══

    private void startSubtitleLoop() {
        stopSubtitleLoop();
        subtitleLoop = new Runnable() {
            @Override public void run() {
                updateSubtitle(videoView.getCurrentPosition());
                subtitleHandler.postDelayed(this, 80);
            }
        };
        subtitleHandler.post(subtitleLoop);
    }

    private void stopSubtitleLoop() {
        if (subtitleLoop != null) {
            subtitleHandler.removeCallbacks(subtitleLoop);
            subtitleLoop = null;
        }
    }

    private void updateSubtitle(long posMs) {
        List<SubtitleEntry> subs = parsedSrt[currentLang];
        if (subs == null) { tvSubtitle.setVisibility(View.INVISIBLE); return; }
        String text = "";
        for (SubtitleEntry e : subs) {
            if (posMs >= e.startMs && posMs <= e.endMs) { text = e.text; break; }
        }
        if (text.isEmpty()) {
            tvSubtitle.setVisibility(View.INVISIBLE);
        } else {
            tvSubtitle.setText(text);
            tvSubtitle.setVisibility(View.VISIBLE);
        }
    }

    private void selectLanguage(int lang) {
        currentLang = lang;
        // Disable the active language button so it looks "selected"
        btnFr.setEnabled(lang != 0);
        btnEn.setEnabled(lang != 1);
        btnEs.setEnabled(lang != 2);
    }

    // ═══════════════════════════════════════════════════════════════ export ══

    private void exportWithSubtitles() {
        if (!analysisOk || srtData[currentLang] == null) return;
        btnExport.setEnabled(false);
        setProgress("Exporting with " + langName(currentLang) + " subtitles…");

        executor.execute(() -> {
            try {
                // Write SRT to a path with no special chars (required by lavfi subtitles filter)
                File srtFile = new File(getCacheDir(), "export_subs.srt");
                Files.write(srtFile.toPath(),
                        srtData[currentLang].getBytes(StandardCharsets.UTF_8));

                File outFile = new File(getExternalFilesDir(null),
                        "demo_" + langCode(currentLang) + "_subtitled.mp4");

                // Determine input path for FFmpeg (same logic as runAnalysis)
                String ffmpegInput;
                ParcelFileDescriptor pfd = null;
                if (pickedUri != null) {
                    pfd          = getContentResolver().openFileDescriptor(pickedUri, "r");
                    ffmpegInput  = "/proc/self/fd/" + pfd.getFd();
                } else {
                    ffmpegInput = etVideoPath.getText().toString().trim();
                }

                // Escape colons in the SRT path (lavfi filter string syntax)
                String srtEscaped = srtFile.getAbsolutePath().replace(":", "\\:");

                String cmd = "-i " + ffmpegInput
                        + " -vf subtitles=" + srtEscaped
                        + ":force_style='FontSize=24,PrimaryColour=&Hffffff&,"
                        +   "OutlineColour=&H000000&,BorderStyle=3'"
                        + " -c:v libx264 -crf 23 -preset fast -c:a copy"
                        + " -y " + outFile.getAbsolutePath();

                FFmpegSession session = FFmpegKit.execute(cmd);
                if (pfd != null) { try { pfd.close(); } catch (IOException ignored) {} }

                uiHandler.post(() -> {
                    btnExport.setEnabled(true);
                    if (ReturnCode.isSuccess(session.getReturnCode())) {
                        setProgress("Exported: " + outFile.getAbsolutePath()
                                + "\n\nadb pull " + outFile.getAbsolutePath());
                    } else {
                        setProgress("Export failed (rc=" + session.getReturnCode() + ")\n"
                                + session.getLogsAsString());
                    }
                });
            } catch (IOException e) {
                uiHandler.post(() -> {
                    btnExport.setEnabled(true);
                    setProgress("Export error: " + e.getMessage());
                });
            }
        });
    }

    private String langCode(int i) { return i == 0 ? "fr" : i == 1 ? "en" : "es"; }
    private String langName(int i) { return i == 0 ? "French" : i == 1 ? "English" : "Spanish"; }

    // ═════════════════════════════════════════════════════════════ SRT ══

    private static class SubtitleEntry {
        final long   startMs, endMs;
        final String text;
        SubtitleEntry(long s, long e, String t) { startMs = s; endMs = e; text = t; }
    }

    private static List<SubtitleEntry> parseSrt(String srt) {
        List<SubtitleEntry> list = new ArrayList<>();
        if (srt == null || srt.trim().isEmpty()) return list;
        for (String block : srt.trim().split("\n\n+")) {
            String[] lines = block.trim().split("\n");
            if (lines.length < 3) continue;
            String[] arrow = lines[1].split("-->");
            if (arrow.length < 2) continue;
            StringBuilder text = new StringBuilder();
            for (int i = 2; i < lines.length; i++) {
                if (i > 2) text.append("\n");
                text.append(lines[i].trim());
            }
            list.add(new SubtitleEntry(
                    parseSrtTime(arrow[0]),
                    parseSrtTime(arrow[1]),
                    text.toString()));
        }
        return list;
    }

    private static long parseSrtTime(String t) {
        // "HH:MM:SS,mmm"
        t = t.trim();
        String[] p = t.split("[,:]");
        if (p.length < 4) return 0;
        try {
            return Long.parseLong(p[0]) * 3_600_000L
                 + Long.parseLong(p[1]) *    60_000L
                 + Long.parseLong(p[2]) *     1_000L
                 + Long.parseLong(p[3]);
        } catch (NumberFormatException e) { return 0; }
    }

    // ═══════════════════════════════════════════════════════════ helpers ══

    private void setProgress(String msg) {
        uiHandler.post(() -> tvProgress.setText(msg));
    }
}
