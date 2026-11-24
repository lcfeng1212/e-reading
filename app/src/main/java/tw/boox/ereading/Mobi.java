package tw.boox.ereading;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

final class Mobi {
    private static native int writeBook(String original, Mobi output);

    private final ZipOutputStream zip;
    private Mobi(ZipOutputStream zip) { this.zip = zip; }

    static void convert(File original, File target) throws IOException {
        // Conversion reconstructs an EPUB from an unprotected MOBI publication.
        // Limit source size as the native parser needs random access and holds records in memory.
        if (original.length() > 80L * 1024 * 1024) throw new IOException("MOBI 檔案超過 80 MB");
        try (var output = new ZipOutputStream(new FileOutputStream(target))) {
            byte[] type = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            var mimetype = new ZipEntry("mimetype");
            var crc = new CRC32(); crc.update(type);
            mimetype.setMethod(ZipEntry.STORED);
            mimetype.setSize(type.length);
            mimetype.setCrc(crc.getValue());
            output.putNextEntry(mimetype);
            output.write(type);
            output.closeEntry();
            var writer = new Mobi(output);
            writer.write("META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
                """.getBytes(StandardCharsets.UTF_8));
            System.loadLibrary("reader_mobi");
            int result = writeBook(original.getAbsolutePath(), writer);
            if (result == 2) throw new IOException("這個 MOBI 有數位版權保護");
            if (result != 0) throw new IOException("無法開啟這個 MOBI 版本或檔案損毀");
        } catch (LinkageError e) {
            throw new IOException("這台裝置無法載入 MOBI 解析工具", e);
        }
    }

    @SuppressWarnings("unused") // Called by the native parser.
    private void write(String path, byte[] payload) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(payload);
        zip.closeEntry();
    }
}
