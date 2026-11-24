package tw.boox.ereading;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Heavier scenarios: batch import, rapid reopening, rotation while loading, renderer loss. */
@RunWith(AndroidJUnit4.class)
public final class LoadStressTest {
    private final Instrumentation ins = InstrumentationRegistry.getInstrumentation();
    private final android.content.Context app = ins.getTargetContext();

    private File book(String name, String text) throws Exception {
        var file = new File(app.getCacheDir(), name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test public void importFortyBooksWithBrokenOnesMixedIn() throws Exception {
        var library = new Library(app);
        var files = new ArrayList<File>();
        var added = new ArrayList<JSONObject>();
        int failures = 0;
        long start = SystemClock.elapsedRealtime();
        try {
            for (int i = 0; i < 40; i++) {
                File file = i % 8 == 7
                    ? book("stress-" + i + ".doc", "不是書檔")                        // rejected by type
                    : book("stress-" + i + ".txt", "第1章 第" + i + "本\n" + "內容。".repeat(2000 + i));
                files.add(file);
                try { added.add(library.add(Uri.fromFile(file), false, false)); }
                catch (Exception expected) { failures++; }
            }
            for (var entry : added) assertTrue(library.epub(entry, null).isFile());
            long took = SystemClock.elapsedRealtime() - start;
            android.util.Log.i("LoadStress", "40 imports and 35 conversions took " + took + " ms");
            assertEquals(5, failures);
            assertEquals(35, added.size());
            File[] leftovers = new File(app.getFilesDir(), "books").listFiles((d, n) -> n.endsWith(".tmp"));
            assertEquals("No temporary files remain", 0, leftovers == null ? 0 : leftovers.length);
            // A new Library instance sees the same shelf: the records were saved.
            var reloaded = new Library(app);
            int found = 0;
            for (var entry : added) for (var item : reloaded.books())
                if (item.optString("id").equals(entry.optString("id"))) { found++; break; }
            assertEquals(35, found);
        } finally {
            for (var entry : added) library.remove(entry);
            for (var file : files) file.delete();
        }
    }

    @Test public void reopenTheSameBookTenTimesThenTurnAPage() throws Exception {
        try (var session = new Session("第1章 連續開關\n" + "重複開關書本時仍能翻頁。".repeat(400) + "\n")) {
            for (int i = 0; i < 10; i++) {
                ins.runOnMainSync(() -> session.call("shelf", ""));
                ins.runOnMainSync(() -> session.call("open", session.entry(), null));
                SystemClock.sleep(i % 2 == 0 ? 30 : 250);
            }
            session.awaitReader();
            session.pressNextAndExpectPage(1);
        }
    }

    @Test public void rotateWhileTheChapterLoads() throws Exception {
        try (var session = new Session("第1章 旋轉\n" + "載入中旋轉畫面後仍能翻頁。".repeat(400) + "\n")) {
            ins.runOnMainSync(() -> session.call("shelf", ""));
            ins.runOnMainSync(() -> {
                session.call("open", session.entry(), null);
                var config = new android.content.res.Configuration(session.activity.getResources().getConfiguration());
                config.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                session.activity.onConfigurationChanged(config);
            });
            session.awaitReader();
            session.pressNextAndExpectPage(1);
        }
    }

    @Test public void recoverWhenThePageRendererDies() throws Exception {
        try (var session = new Session("第1章 重新載入\n" + "閱讀頁程序結束後自動恢復。".repeat(400) + "\n")) {
            WebView before = session.awaitReader();
            ins.runOnMainSync(() -> before.loadUrl("chrome://crash"));
            long deadline = SystemClock.elapsedRealtime() + 25_000;
            WebView after = null;
            while (SystemClock.elapsedRealtime() < deadline) {
                after = session.web();
                if (after != null && after != before && !session.state(after).equals("null")) break;
                SystemClock.sleep(200);
            }
            assertNotNull(after);
            assertNotSame("A new page replaces the crashed one", before, after);
            assertFalse(session.activity.isFinishing());
            session.pressNextAndExpectPage(1);
        }
    }

    /** Opens a TXT book in the reader and drives it. */
    private final class Session implements AutoCloseable {
        final ReaderActivity activity;
        final Uri uri;

        Session(String text) throws Exception {
            var resolver = app.getContentResolver();
            var values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "load-stress-" + UUID.randomUUID() + ".txt");
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            assertNotNull(uri);
            try (var out = resolver.openOutputStream(uri)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            activity = (ReaderActivity) ins.startActivitySync(new Intent(Intent.ACTION_VIEW, uri)
                .setClass(app, ReaderActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
            awaitReader();
        }

        Object field(String name) {
            try {
                Field f = ReaderActivity.class.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(activity);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }

        JSONObject entry() {
            var library = (Library) field("library");
            var books = library.books();
            books.sort(Comparator.comparingLong((JSONObject b) -> b.optLong("opened")).reversed());
            return books.get(0);
        }

        void call(String name, Object... args) {
            try {
                for (Method m : ReaderActivity.class.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                    m.setAccessible(true);
                    m.invoke(activity, args);
                    return;
                }
                throw new AssertionError("No method " + name);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }

        WebView web() {
            var found = new AtomicReference<WebView>();
            ins.runOnMainSync(() -> found.set((WebView) field("web")));
            return found.get();
        }

        String state(WebView web) {
            var result = new CompletableFuture<String>();
            ins.runOnMainSync(() -> web.evaluateJavascript("window.reader ? window.reader.state() : null", result::complete));
            try { return result.get(5, TimeUnit.SECONDS); } catch (Exception e) { return "null"; }
        }

        boolean busy() {
            var value = new AtomicReference<Boolean>();
            ins.runOnMainSync(() -> value.set((Boolean) field("busy")));
            return value.get();
        }

        WebView awaitReader() {
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                WebView web = web();
                if (web != null && !busy() && !state(web).equals("null")) return web;
                SystemClock.sleep(200);
            }
            throw new AssertionError("The reader did not finish loading");
        }

        void pressNextAndExpectPage(int expected) throws Exception {
            WebView web = awaitReader();
            int before = new JSONObject(state(web)).getInt("page");
            ins.runOnMainSync(() -> activity.dispatchKeyEvent(new android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN)));
            int now = before;
            for (int i = 0; i < 30 && now == before; i++) {
                SystemClock.sleep(100);
                now = new JSONObject(state(web)).getInt("page");
            }
            assertEquals(before + expected, now);
        }

        @Override public void close() {
            ins.runOnMainSync(activity::finish);
            app.getContentResolver().delete(uri, null, null);
        }
    }
}
