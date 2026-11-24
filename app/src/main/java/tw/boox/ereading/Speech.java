package tw.boox.ereading;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.*;
import java.util.function.Consumer;

/**
 * Reads the book aloud with the device's own text-to-speech engine, so the app carries no voice
 * data of its own. The engine and its Chinese voice are installed by the system when needed.
 */
final class Speech {
    interface Listener {
        /** The character now being read: paragraph index and offset in the chapter's text nodes. */
        void at(int paragraph, int offset);
        /** Everything handed over has been read. */
        void finished();
        void failed();
    }

    /** A piece of a text node, read as one utterance. */
    record Part(int paragraph, int offset, String text) {}

    static final String NO_ENGINE = "engine", NO_VOICE = "voice";
    // Utterances queued ahead of the one being read, so the engine never waits for the next one.
    private static final int AHEAD = 3;
    private final Context context;
    private final Listener listener;
    private TextToSpeech engine;
    private boolean ready;
    private final List<Part> parts = new ArrayList<>();
    private int next, generation;
    private float rate = 1f;

    Speech(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * Starts the engine once and picks a Chinese voice. Calls back on an engine thread with null
     * when ready, or with NO_ENGINE / NO_VOICE.
     */
    void prepare(String chinese, Consumer<String> done) {
        if (engine != null && ready) { done.accept(choose(chinese) ? null : NO_VOICE); return; }
        if (engine != null) engine.shutdown();
        ready = false;
        // Without any engine the constructor reports failure before it returns.
        final TextToSpeech[] created = new TextToSpeech[1];
        final int[] early = {Integer.MIN_VALUE};
        created[0] = new TextToSpeech(context, status -> {
            if (created[0] == null) early[0] = status;
            else started(created[0], status, chinese, done);
        });
        engine = created[0];
        if (early[0] != Integer.MIN_VALUE) started(engine, early[0], chinese, done);
    }

    private void started(TextToSpeech created, int status, String chinese, Consumer<String> done) {
        if (created != engine) return;
        if (status != TextToSpeech.SUCCESS || engine.getEngines().isEmpty()) { done.accept(NO_ENGINE); return; }
        ready = true;
        engine.setOnUtteranceProgressListener(progress);
        done.accept(choose(chinese) ? null : NO_VOICE);
    }

    /** Traditional Chinese first for books shown in Traditional characters, then any Chinese voice. */
    private boolean choose(String chinese) {
        var order = "cn".equals(chinese)
            ? List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.CHINESE)
            : List.of(Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE, Locale.CHINESE);
        for (Locale locale : order) {
            int result = engine.setLanguage(locale);
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setSpeechRate(rate);
                return true;
            }
        }
        return false;
    }

    void rate(float value) { rate = value; if (engine != null && ready) engine.setSpeechRate(value); }

    /** Replaces whatever is being read with these parts. */
    synchronized void speak(List<Part> list) {
        if (engine == null || !ready) return;
        generation++;
        engine.stop();
        parts.clear();
        int limit = Math.max(200, Math.min(1000, TextToSpeech.getMaxSpeechInputLength() - 1));
        for (Part part : list) split(part, limit);
        next = 0;
        if (parts.isEmpty()) { listener.finished(); return; }
        feed();
    }

    /** Engines refuse text past a length limit, so long paragraphs are cut after punctuation. */
    private void split(Part part, int limit) {
        String text = part.text();
        int from = 0;
        while (from < text.length()) {
            int end = Math.min(text.length(), from + limit);
            if (end < text.length()) {
                int cut = -1;
                for (int i = end - 1; i > from + limit / 2; i--) if ("。！？；，、.!?;,\n".indexOf(text.charAt(i)) >= 0) { cut = i + 1; break; }
                if (cut > 0) end = cut;
            }
            String piece = text.substring(from, end);
            if (!piece.isBlank()) parts.add(new Part(part.paragraph(), part.offset() + from, piece));
            from = end;
        }
    }

    private synchronized void feed() {
        while (next < parts.size() && next < current + AHEAD + 1) {
            engine.speak(parts.get(next).text(), TextToSpeech.QUEUE_ADD, null, generation + ":" + next);
            next++;
        }
    }

    private int current;

    private synchronized Part part(String id, boolean advance) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        try {
            if (Integer.parseInt(id.substring(0, colon)) != generation) return null;
            int index = Integer.parseInt(id.substring(colon + 1));
            if (index < 0 || index >= parts.size()) return null;
            if (advance) current = index;
            return parts.get(index);
        } catch (RuntimeException e) { return null; }
    }

    private final UtteranceProgressListener progress = new UtteranceProgressListener() {
        @Override public void onStart(String id) {
            Part part = part(id, true);
            if (part != null) { listener.at(part.paragraph(), part.offset()); feed(); }
        }
        @Override public void onRangeStart(String id, int start, int end, int frame) {
            Part part = part(id, false);
            if (part != null) listener.at(part.paragraph(), part.offset() + start);
        }
        @Override public void onDone(String id) {
            boolean last;
            synchronized (Speech.this) {
                Part part = part(id, false);
                last = part != null && part == parts.get(parts.size() - 1);
            }
            if (last) listener.finished();
            else synchronized (Speech.this) { if (part(id, false) != null) feed(); }
        }
        @Override public void onError(String id) { if (part(id, false) != null) listener.failed(); }
        @Override public void onError(String id, int code) { onError(id); }
    };

    synchronized void stop() {
        generation++;
        parts.clear(); next = 0; current = 0;
        if (engine != null && ready) engine.stop();
    }

    void close() {
        stop();
        if (engine != null) engine.shutdown();
        engine = null; ready = false;
    }
}
