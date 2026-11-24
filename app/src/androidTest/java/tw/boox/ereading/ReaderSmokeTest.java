package tw.boox.ereading;

import static org.junit.Assert.*;
import android.content.Intent;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ReaderSmokeTest {
    private File temp(String extension) throws IOException {
        return File.createTempFile("reader-test-", extension,
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
    }

    @Test public void convertChineseTxtAndKeepTextInOrder() throws Exception {
        var original = temp(".txt");
        var converted = temp(".epub");
        try {
            String input = "第１章 開始\n天晴。<&>\n第两百节 續章\n" + "山水".repeat(27_001);
            Files.write(original.toPath(), input.getBytes(StandardCharsets.UTF_8));
            var progress = new java.util.ArrayList<Integer>();
            Books.txt(original, converted, "小說", Books.charset(original), progress::add);
            assertFalse(progress.isEmpty());
            assertTrue(progress.get(progress.size() - 1) >= 90);
            try (var zip = new ZipFile(converted)) {
                var book = Books.epub(zip);
                assertTrue(book.chapters().size() > 2);
                assertEquals("第１章 開始", book.chapters().get(0).title());
                assertEquals("第两百节 續章", book.chapters().get(1).title());
                var result = new StringBuilder();
                for (var part : book.chapters()) try (var in = zip.getInputStream(zip.getEntry(part.path()))) {
                    byte[] content = in.readAllBytes();
                    assertTrue(content.length < 200_000);
                    String html = new String(content, StandardCharsets.UTF_8);
                    var paragraphs = java.util.regex.Pattern.compile("(?s)<p>(.*?)</p>").matcher(html);
                    while (paragraphs.find()) {
                        result.append(android.text.Html.fromHtml(paragraphs.group(1), 0).toString());
                    }
                }
                assertEquals(input.replace("\n", ""), result.toString());
            }
        } finally { original.delete(); converted.delete(); }
    }

    @Test public void convertBig5WithCodePage950Characters() throws Exception {
        var original = temp(".txt");
        var converted = temp(".epub");
        try {
            String input = "第1章 測試\n碁銹裏墻恒粧嫺，一般文字顯示正常。\n";
            Files.write(original.toPath(), input.getBytes("x-windows-950"));
            Books.txt(original, converted, "Big5", Books.named("Big5"));
            try (var zip = new ZipFile(converted)) {
                var book = Books.epub(zip);
                String html = new String(zip.getInputStream(zip.getEntry(book.chapters().get(0).path())).readAllBytes(),
                    StandardCharsets.UTF_8);
                assertTrue(html.contains("碁銹裏墻恒粧嫺"));
            }
        } finally { original.delete(); converted.delete(); }
    }

    @Test public void sentencesAreNotChapterTitles() {
        assertTrue(Books.heading("第十章 真正的章名"));
        assertTrue(Books.heading("第一回 風起"));
        assertFalse(Books.heading("第一回合比賽開始了。"));
        assertFalse(Books.heading("第三部分的內容如下。"));
        assertFalse(Books.heading("序章後面接著很長的一句敘述文字，這不是章名而是正文的一部分，長度超過四十字唷。"));
    }

    @Test public void convertPhrasesStartingWithLatinLetters() throws Exception {
        var app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("隨身碟", new Chinese(app).convert("U盘", "tw"));
    }

    private byte[] modelArchive(String... entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String name : entries) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) zip.write(name.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test public void installDownloadedVoiceModelSafely() throws Exception {
        var app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VoiceModel.delete(VoiceModel.folder(app));
        assertFalse(VoiceModel.ready(app));
        // A crafted archive that writes outside the model folder is refused and installs nothing.
        try {
            VoiceModel.install(app, new ByteArrayInputStream(modelArchive("m/am/final.mdl", "m/../../escaped.txt")));
            fail("An entry leaving the model folder must be refused");
        } catch (IOException expected) {}
        assertFalse(VoiceModel.ready(app));
        assertFalse(new File(app.getFilesDir(), "escaped.txt").exists());
        // An archive without the acoustic model is incomplete.
        try {
            VoiceModel.install(app, new ByteArrayInputStream(modelArchive("m/README")));
            fail("An incomplete model must be refused");
        } catch (IOException expected) {}
        assertFalse(VoiceModel.ready(app));
        VoiceModel.install(app, new ByteArrayInputStream(modelArchive("m/", "m/am/final.mdl", "m/conf/model.conf")));
        assertTrue(VoiceModel.ready(app));
        assertTrue(new File(VoiceModel.folder(app), "conf/model.conf").isFile());
        VoiceModel.delete(VoiceModel.folder(app));
    }

    @Test public void readPdfAndComicPages() throws Exception {
        var pdf = temp(".pdf");
        var cbz = temp(".cbz");
        try {
            var ink = new Paint();
            ink.setTextSize(22);
            var document = new PdfDocument();
            try (var out = new FileOutputStream(pdf)) {
                var page = document.startPage(new PdfDocument.PageInfo.Builder(360, 400, 1).create());
                page.getCanvas().drawText("Offline reader", 16, 40, ink);
                document.finishPage(page);
                document.writeTo(out);
            } finally { document.close(); }
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
            try (var pages = new Pages(pdf, true)) {
                assertEquals(1, pages.count());
                assertTrue(pages.render(0, false).getWidth() > 0);
                assertTrue(pages.text(0).contains("Offline reader"));
            }
            // A PDF kept at its original location renders without a copy; text copies on demand.
            var copied = new java.util.concurrent.atomic.AtomicBoolean();
            try (var pages = new Pages(android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY),
                () -> { copied.set(true); return pdf; })) {
                assertTrue(pages.render(0, false).getWidth() > 0);
                assertFalse(copied.get());
                assertTrue(pages.text(0).contains("Offline reader"));
                assertTrue(copied.get());
            }
            // A provider may hand over a descriptor that cannot seek. Opening in place must fail
            // cleanly so the reader falls back to a copy.
            var pipe = android.os.ParcelFileDescriptor.createPipe();
            try {
                new Pages(pipe[0], () -> pdf).close();
                fail("A pipe cannot be read as a PDF in place");
            } catch (IOException | RuntimeException expected) {
                // Expected: PdfRenderer rejects a descriptor it cannot seek.
            } finally { pipe[1].close(); }
            try (var zip = new ZipOutputStream(new FileOutputStream(cbz))) {
                putImage(zip, "10.png", Color.BLACK);
                putImage(zip, "2.png", Color.WHITE);
            }
            try (var pages = new Pages(cbz, false)) {
                assertEquals(2, pages.count());
                assertEquals(Color.WHITE, pages.render(0, false).getPixel(0, 0));
                assertEquals(Color.BLACK, pages.render(1, false).getPixel(0, 0));
            }
        } finally { pdf.delete(); cbz.delete(); }
    }

    private void putImage(ZipOutputStream zip, String name, int color) throws IOException {
        var picture = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        picture.eraseColor(color);
        zip.putNextEntry(new ZipEntry(name)); picture.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry();
        picture.recycle();
    }

    @Test public void convertUncompressedAndHuffdicMobi() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getContext();
        for (String name : new String[]{"sample-unicode-uncompressed.mobi", "sample-unicode-huffdic.mobi"}) {
            var source = temp(".mobi");
            var target = temp(".epub");
            try {
                try (var in = context.getAssets().open(name); var out = new FileOutputStream(source)) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
                }
                Mobi.convert(source, target);
                try (var zip = new ZipFile(target)) { assertFalse(Books.epub(zip).chapters().isEmpty()); }
            } finally { source.delete(); target.delete(); }
        }
    }

    @Test public void launchReaderAndConvertChineseDisplay() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var app = instrumentation.getTargetContext();
        var intent = new Intent(app, ReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        var activity = instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        assertNotNull(activity.findViewById(android.R.id.content));
        assertEquals("繁體中文", new Chinese(app).convert("繁体中文", "tw"));
        assertEquals("简体中文", new Chinese(app).convert("簡體中文", "cn"));
        instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void openImportedTxtAndContinueToNextPage() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var app = instrumentation.getTargetContext();
        var resolver = app.getContentResolver();
        var values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            "boox-reader-smoke-" + java.util.UUID.randomUUID() + ".txt");
        values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        var uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        ReaderActivity activity = null;
        try {
            try (var output = resolver.openOutputStream(uri)) {
                assertNotNull(output);
                output.write(("第1章 測試\n" + "測試文字依照順序顯示。".repeat(120) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            var launch = new Intent(Intent.ACTION_VIEW, uri).setClass(app, ReaderActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity = (ReaderActivity) instrumentation.startActivitySync(launch);
            ReaderActivity reader = activity;
            android.webkit.WebView web = null;
            long deadline = android.os.SystemClock.elapsedRealtime() + 25_000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                var candidate = new java.util.concurrent.atomic.AtomicReference<android.webkit.WebView>();
                instrumentation.runOnMainSync(() -> candidate.set(findWeb(reader.findViewById(android.R.id.content))));
                web = candidate.get();
                if (web != null && !readState(instrumentation, web).equals("null")) break;
                Thread.sleep(200);
            }
            assertNotNull("Opening TXT should display a reader", web);
            String before = readState(instrumentation, web);
            assertNotEquals("The EPUB renderer should initialize", "null", before);
            assertEquals(0, new org.json.JSONObject(before).getInt("page"));
            assertTrue("The test book needs more than one page", new org.json.JSONObject(before).getInt("total") > 1);
            instrumentation.runOnMainSync(() -> reader.dispatchKeyEvent(new android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN)));
            int current = 0;
            for (int i = 0; i < 15 && current == 0; i++) {
                current = new org.json.JSONObject(readState(instrumentation, web)).getInt("page");
                if (current == 0) Thread.sleep(100);
            }
            assertEquals("One key press should go forward one page", 1, current);
        } finally {
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
            resolver.delete(uri, null, null);
        }
    }

    private android.webkit.WebView findWeb(android.view.View view) {
        if (view instanceof android.webkit.WebView web) return web;
        if (view instanceof android.view.ViewGroup parent) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                var found = findWeb(parent.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private String readState(android.app.Instrumentation target, android.webkit.WebView web) throws Exception {
        var result = new java.util.concurrent.CompletableFuture<String>();
        target.runOnMainSync(() -> web.evaluateJavascript(
            "window.reader ? window.reader.state() : null", result::complete));
        return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
