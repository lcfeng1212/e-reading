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
            Books.txt(original, converted, "小說", Books.charset(original));
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
}
