package tw.boox.ereading;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.util.*;
import java.util.zip.*;

final class Pages implements Closeable {
    private File file;
    private final java.util.concurrent.Callable<File> textSource;
    private final PdfRenderer pdf;
    private final ZipFile zip;
    private final List<String> images = new ArrayList<>();
    private PDDocument document;

    /** A PDF read in place. Text extraction for translation copies it on first use. */
    Pages(ParcelFileDescriptor descriptor, java.util.concurrent.Callable<File> textSource) throws IOException {
        this.file = null;
        this.textSource = textSource;
        try { pdf = new PdfRenderer(descriptor); }
        catch (IOException | RuntimeException e) { descriptor.close(); throw e; }
        zip = null;
    }

    Pages(File file, boolean isPdf) throws IOException {
        this.file = file;
        this.textSource = null;
        pdf = isPdf ? new PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) : null;
        zip = isPdf ? null : new ZipFile(file);
        if (zip != null) {
            zip.stream().filter(e -> !e.isDirectory() && e.getName().matches("(?i).+\\.(png|jpe?g|gif|bmp|webp|avif)$")
                && !e.getName().startsWith("__MACOSX/")).forEach(e -> images.add(e.getName()));
            images.sort(Pages::natural);
            if (images.isEmpty()) { zip.close(); throw new IOException("漫畫檔沒有可讀圖片"); }
        }
    }

    synchronized int count() { return pdf == null ? images.size() : pdf.getPageCount(); }

    synchronized Bitmap render(int index, boolean crop) throws IOException {
        if (pdf != null) {
            try (var page = pdf.openPage(index)) {
                float scale = Math.min(3f, 2200f / Math.max(page.getWidth(), page.getHeight()));
                Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                    Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return crop ? crop(bitmap) : bitmap;
            }
        }
        var entry = zip.getEntry(images.get(index));
        var options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (var in = zip.getInputStream(entry)) { BitmapFactory.decodeStream(in, null, options); }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outHeight, options.outWidth) / options.inSampleSize > 3000)
            options.inSampleSize *= 2;
        try (var in = zip.getInputStream(entry)) {
            var image = BitmapFactory.decodeStream(in, null, options);
            if (image == null) throw new IOException("這一頁的圖片無法解碼");
            return image;
        }
    }

    synchronized String text(int page) throws IOException {
        if (pdf == null) return "";
        if (file == null) {
            try { file = textSource.call(); }
            catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException(e.getMessage(), e); }
        }
        if (document == null) document = PDDocument.load(file, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly());
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(page + 1); stripper.setEndPage(page + 1);
        return stripper.getText(document).strip();
    }

    private static Bitmap crop(Bitmap image) {
        int left = image.getWidth(), top = image.getHeight(), right = 0, bottom = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int p = image.getPixel(x, y);
                if (Color.red(p) > 225 && Color.green(p) > 225 && Color.blue(p) > 225) continue;
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
        }
        if (left >= right || top >= bottom) return image;
        left = Math.max(0, left - 12); top = Math.max(0, top - 12);
        right = Math.min(image.getWidth(), right + 13); bottom = Math.min(image.getHeight(), bottom + 13);
        var result = Bitmap.createBitmap(image, left, top, right - left, bottom - top);
        if (result != image) image.recycle();
        return result;
    }

    private static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (x >= '0' && x <= '9' && y >= '0' && y <= '9') {
                int ai = i, bj = j;
                while (i < a.length() && a.charAt(i) >= '0' && a.charAt(i) <= '9') i++;
                while (j < b.length() && b.charAt(j) >= '0' && b.charAt(j) <= '9') j++;
                String ax = a.substring(ai, i).replaceFirst("^0+(?!$)", "");
                String by = b.substring(bj, j).replaceFirst("^0+(?!$)", "");
                int n = Integer.compare(ax.length(), by.length());
                if (n != 0) return n;
                n = ax.compareTo(by);
                if (n != 0) return n;
            } else {
                int n = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (n != 0) return n;
                i++; j++;
            }
        }
        int remaining = Integer.compare(a.length() - i, b.length() - j);
        return remaining != 0 ? remaining : a.compareTo(b);
    }

    @Override public synchronized void close() throws IOException {
        if (document != null) document.close();
        if (pdf != null) pdf.close();
        if (zip != null) zip.close();
    }
}
