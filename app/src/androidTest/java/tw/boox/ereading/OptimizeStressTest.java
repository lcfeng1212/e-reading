package tw.boox.ereading;

import static org.junit.Assert.*;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OptimizeStressTest {
    private final android.app.Instrumentation ins = InstrumentationRegistry.getInstrumentation();
    private final android.content.Context app = ins.getTargetContext();
    private File temp(String extension) throws IOException {
        return File.createTempFile("boox-qa-", extension, app.getCacheDir());
    }
    private void log(String message) {
        android.util.Log.i("BooxOptimizeStress", message);
    }

    @Test public void convertBothLargeUtf8Fixtures() throws Exception {
        for (int megabytes : new int[]{5, 10}) {
            File src = temp(".txt"), dest = temp(".epub");
            try {
                String phrase = "這一段用來檢查小說正文的順序與長篇分段。";
                String block = "第1章 測試\n" + phrase.repeat(360) + "\n\n";
                byte[] row = block.getBytes(StandardCharsets.UTF_8);
                int repetitions = (megabytes * 1_048_576 + row.length - 1) / row.length;
                try (var out = new BufferedOutputStream(new FileOutputStream(src))) {
                    for (int i=0;i<repetitions;i++) out.write(row);
                }
                var progress = new ArrayList<Integer>();
                long start = SystemClock.elapsedRealtime();
                Books.txt(src,dest,"QA",Books.charset(src),progress::add);
                long took = SystemClock.elapsedRealtime()-start;
                try (var epub = new ZipFile(dest)) {
                    var description = Books.epub(epub);
                    assertFalse(description.chapters().isEmpty());
                    int occurrences = 0;
                    int max = 0;
                    for (var chapter : description.chapters()) {
                        var entry = epub.getEntry(chapter.path());
                        max = Math.max(max, (int)entry.getSize());
                        try (var in = epub.getInputStream(entry)) {
                            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            for (int at = xml.indexOf("這一段"); at >= 0; at = xml.indexOf("這一段",at+4)) occurrences++;
                        }
                    }
                    assertEquals(repetitions * 360, occurrences);
                    assertTrue(max <= 200_000);
                    log("TXT bytes=" + src.length()+" ms=" + took + " spine="+description.chapters().size()+" maxXhtml="+max+" progressEvents="+progress.size()+" last="+progress.get(progress.size()-1));
                }
                assertTrue(progress.get(progress.size()-1) >= 90);
                for (int j=1;j<progress.size();j++) assertTrue(progress.get(j) >= progress.get(j-1));
            } finally { src.delete(); dest.delete(); }
        }
    }

    @Test public void reopeningAChangedOriginalMustNotSilentlyUseStaleCache() throws Exception {
        var file = temp(".txt");
        var library = new Library(app);
        org.json.JSONObject book = null;
        try {
            Files.write(file.toPath(), "original current".getBytes(StandardCharsets.UTF_8));
            book = library.add(Uri.fromFile(file), true, true);
            var copy = library.source(book);
            library.clearCachedSource(book);
            assertTrue(copy.isFile());
            Files.write(file.toPath(), "rewritten changed".getBytes(StandardCharsets.UTF_8));
            try {
                var actual = new String(Files.readAllBytes(library.source(book).toPath()), StandardCharsets.UTF_8);
                assertEquals("The book was changed on disk; reopening must expose the new bytes or report a mismatch",
                    "rewritten changed", actual);
            } catch (IOException detected) {
                assertTrue(detected.getMessage().contains("變更"));
            }
        } finally { if (book != null) library.remove(book); file.delete(); }
    }

    @Test public void fiftyPageTurnsAndResume() throws Exception {
        var values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "boox-qa-50-" + UUID.randomUUID() + ".txt");
        values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        var resolver = app.getContentResolver();
        Uri uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        ReaderActivity activity = null;
        try {
            try (var output = resolver.openOutputStream(uri)) {
                var body = new StringBuilder("第1章 測試\n");
                for (int i=0;i<1000;i++)
                    body.append(i).append("　第一行，測試繁簡切換與續讀。繁體小說使用全形中文標點；也包含 English words。\n");
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            activity = open(uri);
            var web = ready(activity);
            int startChapter = chapterIndex(activity);
            int lastPage = 0;
            int lastChapter = startChapter;
            long start = SystemClock.elapsedRealtime();
            for (int i=0;i<50;i++) {
                sendNext(activity);
                long end = SystemClock.elapsedRealtime()+2000;
                boolean advanced = false;
                while (SystemClock.elapsedRealtime() < end) {
                    String result = state(web);
                    if (!result.equals("null")) {
                        int now = new org.json.JSONObject(result).getInt("page");
                        int ch = chapterIndex(activity);
                        if ((ch == lastChapter && now == lastPage+1) || (ch == lastChapter+1 && now == 0)) {
                            lastPage=now;
                            lastChapter=ch;
                            advanced=true;
                            break;
                        }
                    }
                    Thread.sleep(35);
                }
                assertTrue("page press " + (i+1) + " did not advance exactly once; last chapter="+lastChapter+" page="+lastPage,advanced);
            }
            log("50 key events ms="+(SystemClock.elapsedRealtime()-start)+" chapter="+lastChapter+" page="+lastPage);
            ins.runOnMainSync(activity::finish);
            ins.waitForIdleSync();
            activity = open(uri);
            web = ready(activity);
            var result = new org.json.JSONObject(state(web));
            assertEquals(lastChapter, chapterIndex(activity));
            assertEquals(lastPage, result.getInt("page"));
        } finally {
            if (activity != null) ins.runOnMainSync(activity::finish);
            resolver.delete(uri,null,null);
        }
    }

    private ReaderActivity open(Uri uri) {
        return (ReaderActivity) ins.startActivitySync(new Intent(Intent.ACTION_VIEW,uri)
            .setClass(app,ReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }
    private int chapterIndex(ReaderActivity activity) throws Exception {
        var field=ReaderActivity.class.getDeclaredField("chapter"); field.setAccessible(true);
        var answer=new java.util.concurrent.atomic.AtomicInteger();
        ins.runOnMainSync(() -> { try { answer.set(field.getInt(activity)); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } });
        return answer.get();
    }
    private void sendNext(ReaderActivity activity) {
        ins.runOnMainSync(() -> activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN)));
    }
    private android.webkit.WebView ready(ReaderActivity activity) throws Exception {
        long end = SystemClock.elapsedRealtime()+30_000;
        while (SystemClock.elapsedRealtime() < end) {
            var out = new java.util.concurrent.atomic.AtomicReference<android.webkit.WebView>();
            ins.runOnMainSync(() -> out.set(web(activity.findViewById(android.R.id.content))));
            if (out.get()!=null) {
                String value=state(out.get());
                if (!value.equals("null") && new org.json.JSONObject(value).optInt("total")>1) {
                    Thread.sleep(150);
                    return out.get();
                }
            }
            Thread.sleep(70);
        }
        throw new AssertionError("reader not ready");
    }
    private String state(android.webkit.WebView web) throws Exception {
        var answer = new CompletableFuture<String>();
        ins.runOnMainSync(() -> web.evaluateJavascript("window.reader ? window.reader.state() : null",answer::complete));
        return answer.get(5,TimeUnit.SECONDS);
    }
    private android.webkit.WebView web(android.view.View view) {
        if (view instanceof android.webkit.WebView w) return w;
        if (view instanceof android.view.ViewGroup group)
            for (int i=0;i<group.getChildCount();i++) {
                var result=web(group.getChildAt(i));
                if(result!=null)return result;
            }
        return null;
    }
}
