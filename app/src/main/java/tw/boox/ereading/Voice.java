package tw.boox.ereading;

import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

final class Voice {
    interface Listener { void page(int delta); void state(String text); }
    private final Context context;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Model model;
    private Recognizer recognizer;
    private SpeechService speech;
    private volatile int generation;
    private long lastCommand;

    Voice(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        int run = ++generation;
        worker.execute(() -> {
            end();
            try {
                if (model == null) {
                    listener.state("正在載入離線語音…");
                    File directory = new File(context.getFilesDir(), "voice-cn");
                    File marker = new File(directory, ".ready");
                    if (!marker.isFile()) {
                        copy("model-cn", directory);
                        Files.write(marker.toPath(), "vosk-model-small-cn-0.22".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    }
                    model = new Model(directory.getAbsolutePath());
                }
                if (run != generation) return;
                recognizer = new Recognizer(model, 16000,
                    "[\"下一页\", \"上一页\", \"[unk]\"]");
                speech = new SpeechService(recognizer, 16000);
                speech.startListening(new RecognitionListener() {
                    @Override public void onPartialResult(String data) {}
                    @Override public void onFinalResult(String data) {}
                    @Override public void onError(Exception error) {
                        if (run == generation) listener.state("收音中斷，請重新啟用");
                    }
                    @Override public void onTimeout() {}
                    @Override public void onResult(String data) {
                        if (run != generation) return;
                        try {
                            String phrase = new JSONObject(data).optString("text").replaceAll("\\s+", "")
                                .replace('頁', '页');
                            int delta = phrase.equals("下一页") ? 1 : phrase.equals("上一页") ? -1 : 0;
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (delta != 0 && now - lastCommand >= 550) {
                                lastCommand = now;
                                listener.page(delta);
                            }
                        } catch (Exception ignored) {}
                    }
                });
                if (run == generation) listener.state("聆聽中");
            } catch (Exception e) {
                end();
                if (run == generation) listener.state("無法啟用離線語音");
            }
        });
    }

    void stop() {
        generation++;
        worker.execute(this::end);
    }

    void close() {
        generation++;
        worker.execute(() -> { end(); if (model != null) model.close(); });
        worker.shutdown();
    }

    private void end() {
        if (speech != null) { speech.stop(); speech.shutdown(); speech = null; }
        if (recognizer != null) { recognizer.close(); recognizer = null; }
    }

    private void copy(String asset, File to) throws IOException {
        String[] children = context.getAssets().list(asset);
        if (children != null && children.length > 0) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("無法建立模型資料夾");
            for (String child : children) copy(asset + "/" + child, new File(to, child));
        } else {
            try (var in = context.getAssets().open(asset)) {
                Files.copy(in, to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
