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
                    // The model is downloaded on first use; ReaderActivity offers it before starting.
                    if (!VoiceModel.ready(context)) { listener.state("尚未下載語音模型"); return; }
                    model = new Model(VoiceModel.folder(context).getAbsolutePath());
                }
                if (run != generation) return;
                recognizer = new Recognizer(model, 16000,
                    "[\"下一页\", \"上 一页\", \"下\", \"上\", \"[unk]\"]");
                recognizer.setWords(true);
                speech = new SpeechService(recognizer, 16000);
                boolean started = speech.startListening(new RecognitionListener() {
                    @Override public void onPartialResult(String data) {}
                    @Override public void onFinalResult(String data) {}
                    @Override public void onError(Exception error) {
                        if (run == generation) listener.state("收音中斷，請重新啟用");
                    }
                    @Override public void onTimeout() {}
                    @Override public void onResult(String data) {
                        if (run != generation) return;
                        try {
                            var result = new JSONObject(data);
                            var words = result.optJSONArray("result");
                            if (words == null || words.length() == 0) return;
                            for (int i = 0; i < words.length(); i++)
                                if (words.getJSONObject(i).optDouble("conf", 0) < .60) return;
                            String phrase = result.optString("text").replaceAll("\\s+", "")
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
                if (!started) throw new IOException("無法啟動收音");
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
}
