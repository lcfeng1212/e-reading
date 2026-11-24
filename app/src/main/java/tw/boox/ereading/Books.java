package tw.boox.ereading;

import com.ibm.icu.text.CharsetDetector;
import android.text.TextUtils;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.URI;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

final class Books {
    private static final int CHUNK = 48_000;
    private static final Pattern HEADING = Pattern.compile(
        "(?iu)^(?:第[0-9０-９零〇一二三四五六七八九十百千万萬兩两]+[章回節节卷集部篇].*|(?:序章|楔子|引子|前言|尾聲|尾声|後記|后记|番外|終章|终章).*|Chapter\\s+[0-9０-９]+.*)$");

    record Chapter(String path, String title) {}
    record Epub(List<Chapter> chapters, boolean fixed, boolean rtl) {}

    static Charset charset(File file) throws IOException {
        try (var in = new FileInputStream(file)) {
            byte[] prefix = in.readNBytes(64 * 1024);
            if (prefix.length >= 2 && (prefix[0] == (byte) 255 && prefix[1] == (byte) 254
                || prefix[0] == (byte) 254 && prefix[1] == (byte) 255)) return StandardCharsets.UTF_16;
            var input = java.nio.ByteBuffer.wrap(prefix);
            var decoder = StandardCharsets.UTF_8.newDecoder();
            var check = decoder.decode(input, java.nio.CharBuffer.allocate(prefix.length), false);
            if (!check.isError()) return StandardCharsets.UTF_8;
            var match = new CharsetDetector().setText(prefix).detect();
            if (match != null && match.getConfidence() >= 40
                && (match.getName().equalsIgnoreCase("Big5") || match.getName().equalsIgnoreCase("GB18030"))) {
                return named(match.getName());
            }
            throw new CharacterCodingException();
        }
    }

    /**
     * Taiwanese TXT files labelled Big5 are usually Windows code page 950, which adds common
     * characters such as 碁、銹、裏、墻、恒 and the euro sign. Strict Big5 rejects the whole file.
     */
    static Charset named(String name) {
        if (name.equalsIgnoreCase("Big5")) {
            for (String candidate : new String[]{"x-windows-950", "windows-950"}) {
                try { if (Charset.isSupported(candidate)) return Charset.forName(candidate); }
                catch (IllegalCharsetNameException ignored) {}
            }
        }
        return Charset.forName(name);
    }

    /** A chapter title is short and does not end like a sentence, e.g. 「第一回合比賽開始了。」. */
    static boolean heading(String line) {
        if (line.isEmpty() || line.length() > 40) return false;
        char last = line.charAt(line.length() - 1);
        if ("。，、；,;".indexOf(last) >= 0) return false;
        return HEADING.matcher(line).matches();
    }

    static void txt(File raw, File dest, String title, Charset encoding) throws IOException {
        txt(raw, dest, title, encoding, null);
    }

    /** Converts a TXT file, reporting progress from 0 to 100 as the file is read. */
    static void txt(File raw, File dest, String title, Charset encoding,
                    java.util.function.IntConsumer progress) throws IOException {
        var decoder = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        long length = Math.max(1, raw.length());
        var source = new FilterInputStream(new FileInputStream(raw)) {
            long read; int reported = -1;
            private void count(long n) {
                if (n <= 0 || progress == null) return;
                read += n;
                int percent = (int) Math.min(99, read * 100 / length);
                if (percent != reported) { reported = percent; progress.accept(percent); }
            }
            @Override public int read() throws IOException { int b = super.read(); if (b >= 0) count(1); return b; }
            @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); count(n); return n; }
        };
        try (var text = new BufferedReader(new InputStreamReader(source, decoder), 64 * 1024);
             var epub = new EpubOutput(dest, title)) {
            var html = new StringBuilder();
            String heading = "正文";
            boolean toc = true;
            int value;
            var line = new StringBuilder();
            boolean lineSplit = false;
            boolean afterCr = false;
            boolean first = true;
            while ((value = text.read()) != -1) {
                char c = (char) value;
                if (first && c == '\uFEFF') { first = false; continue; }
                first = false;
                if (afterCr && c == '\n') { afterCr = false; continue; }
                afterCr = c == '\r';
                if (c == '\r' || c == '\n') {
                    String str = line.toString();
                    if (!lineSplit && heading(str.strip())) {
                        if (html.length() > 0) epub.chapter(html.toString(), heading, toc);
                        html.setLength(0);
                        heading = str.strip();
                        toc = true;
                    }
                    String part = "<p>" + escape(str) + "</p>\n";
                    if (html.length() + part.length() > CHUNK && html.length() > 0) {
                        epub.chapter(html.toString(), heading, toc);
                        html.setLength(0);
                        toc = false;
                    }
                    html.append(part);
                    line.setLength(0);
                    lineSplit = false;
                } else {
                    line.append(c);
                    if (line.length() >= 6_000 && !Character.isHighSurrogate(c)) {
                        String part = "<p>" + escape(line.toString()) + "</p>\n";
                        if (html.length() + part.length() > CHUNK && html.length() > 0) {
                            epub.chapter(html.toString(), heading, toc);
                            html.setLength(0);
                            toc = false;
                        }
                        html.append(part);
                        line.setLength(0);
                        lineSplit = true;
                    }
                }
            }
            if (line.length() > 0) {
                String part = "<p>" + escape(line.toString()) + "</p>\n";
                if (html.length() + part.length() > CHUNK && html.length() > 0) {
                    epub.chapter(html.toString(), heading, toc);
                    html.setLength(0);
                    toc = false;
                }
                html.append(part);
            }
            if (html.length() > 0) epub.chapter(html.toString(), heading, toc);
            epub.finish();
        }
    }

    static Epub epub(ZipFile zip) throws Exception {
        String root = null;
        var container = xml(zip, "META-INF/container.xml");
        for (int type = container.getEventType(); type != XmlPullParser.END_DOCUMENT; type = container.next())
            if (type == XmlPullParser.START_TAG && container.getName().equals("rootfile")) {
                root = container.getAttributeValue(null, "full-path");
                break;
            }
        if (root == null) throw new IOException("EPUB 找不到書籍資訊");
        var manifest = new HashMap<String, String>();
        var spine = new ArrayList<String>();
        var opf = xml(zip, root);
        String nav = null, ncx = null;
        boolean fixed = false, rtl = false;
        for (int type = opf.getEventType(); type != XmlPullParser.END_DOCUMENT; type = opf.next()) {
            if (type != XmlPullParser.START_TAG) continue;
            switch (opf.getName()) {
                case "item" -> {
                    String href = opf.getAttributeValue(null, "href");
                    String properties = opf.getAttributeValue(null, "properties");
                    if (href != null) {
                        String path = resolve(root, href);
                        manifest.put(opf.getAttributeValue(null, "id"), path);
                        if ("application/x-dtbncx+xml".equals(opf.getAttributeValue(null, "media-type"))) ncx = path;
                        if (properties != null && Arrays.asList(properties.split("\\s+")).contains("nav"))
                            nav = path;
                    }
                }
                case "spine" -> rtl = "rtl".equals(opf.getAttributeValue(null, "page-progression-direction"));
                case "itemref" -> spine.add(opf.getAttributeValue(null, "idref"));
                case "meta" -> {
                    if ("rendition:layout".equals(opf.getAttributeValue(null, "property")))
                        fixed = "pre-paginated".equals(opf.nextText().strip());
                }
            }
        }
        var titles = new HashMap<String, String>();
        if (nav != null) {
            try {
                var menu = xml(zip, nav);
                for (int type = menu.getEventType(); type != XmlPullParser.END_DOCUMENT; type = menu.next()) {
                    if (type != XmlPullParser.START_TAG || !menu.getName().equals("a")) continue;
                    String href = menu.getAttributeValue(null, "href");
                    if (href == null) continue;
                    int level = menu.getDepth();
                    var label = new StringBuilder();
                    while (menu.next() != XmlPullParser.END_DOCUMENT
                        && !(menu.getEventType() == XmlPullParser.END_TAG && menu.getDepth() == level))
                        if (menu.getEventType() == XmlPullParser.TEXT) label.append(menu.getText());
                    if (!label.toString().isBlank()) titles.putIfAbsent(resolve(nav, href), label.toString().strip());
                }
            } catch (Exception ignored) { /* A broken optional navigation file does not block reading. */ }
        }
        if (ncx != null && titles.isEmpty()) {
            try {
                var menu = xml(zip, ncx);
                String label = "";
                for (int type = menu.getEventType(); type != XmlPullParser.END_DOCUMENT; type = menu.next()) {
                    if (type != XmlPullParser.START_TAG) continue;
                    if (menu.getName().equals("text")) label = menu.nextText().strip();
                    if (menu.getName().equals("content")) {
                        String href = menu.getAttributeValue(null, "src");
                        if (href != null && !label.isEmpty()) titles.putIfAbsent(resolve(ncx, href), label);
                    }
                }
            } catch (Exception ignored) { /* Navigation is optional. */ }
        }
        var paths = new ArrayList<Chapter>();
        for (String id : spine) {
            String path = manifest.get(id);
            if (path != null && zip.getEntry(path) != null) {
                String label = titles.get(path);
                if (label == null && !path.matches("OEBPS/part-[0-9]+\\.xhtml")) label = "第 " + (paths.size() + 1) + " 節";
                paths.add(new Chapter(path, label));
            }
        }
        if (paths.isEmpty()) throw new IOException("EPUB 沒有可讀章節");
        return new Epub(paths, fixed, rtl);
    }

    private static XmlPullParser xml(ZipFile zip, String path) throws Exception {
        var e = zip.getEntry(path);
        if (e == null) throw new IOException("EPUB 缺少 " + path);
        byte[] data;
        try (var in = zip.getInputStream(e)) { data = in.readNBytes(4 * 1024 * 1024 + 1); }
        if (data.length > 4 * 1024 * 1024) throw new IOException("EPUB 索引過大");
        var parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(data), null);
        return parser;
    }

    private static String resolve(String base, String href) {
        try {
            var uri = new URI(null, null, "/" + base, null).resolve(href.replace(" ", "%20"));
            String path = uri.normalize().getPath();
            return !uri.isAbsolute() && uri.getAuthority() == null && path != null && path.startsWith("/")
                && !path.startsWith("/../") ? path.substring(1) : "";
        } catch (Exception e) { return ""; }
    }

    static String escape(String value) { return TextUtils.htmlEncode(value); }

    private static final class EpubOutput implements Closeable {
        final ZipOutputStream zip;
        final String title;
        final ArrayList<String> paths = new ArrayList<>();
        final ArrayList<String> contents = new ArrayList<>();

        EpubOutput(File file, String title) throws IOException {
            this.title = title;
            zip = new ZipOutputStream(new FileOutputStream(file));
            byte[] type = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            var header = new ZipEntry("mimetype");
            var crc = new CRC32(); crc.update(type);
            header.setMethod(ZipEntry.STORED); header.setSize(type.length); header.setCrc(crc.getValue());
            zip.putNextEntry(header); zip.write(type); zip.closeEntry();
            entry("META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
                """);
        }

        void chapter(String text, String name, boolean toc) throws IOException {
            String path = "part-" + paths.size() + ".xhtml";
            paths.add(path);
            if (toc) contents.add("<li><a href=\"" + path + "\">" + escape(name) + "</a></li>");
            entry("OEBPS/" + path, page(name, text));
        }

        void finish() throws IOException {
            if (paths.isEmpty()) throw new IOException("文字檔沒有內容");
            var items = new StringBuilder();
            var spine = new StringBuilder();
            for (int i = 0; i < paths.size(); i++) {
                items.append("<item id=\"p").append(i).append("\" href=\"").append(paths.get(i))
                    .append("\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"p").append(i).append("\"/>");
            }
            entry("OEBPS/nav.xhtml", page("目錄", "<nav epub:type=\"toc\"><ol>"
                + String.join("", contents) + "</ol></nav>"));
            entry("OEBPS/book.opf", String.format(Locale.ROOT, """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:%s</dc:identifier><dc:title>%s</dc:title><dc:language>zh</dc:language><meta property="dcterms:modified">%s</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>%s</manifest><spine>%s</spine></package>
                """, UUID.randomUUID(), escape(title), java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), items, spine));
        }

        private String page(String title, String body) {
            return "<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><meta charset=\"utf-8\"/><title>"
                + escape(title) + "</title></head><body>" + body + "</body></html>";
        }

        private void entry(String path, String data) throws IOException {
            zip.putNextEntry(new ZipEntry(path)); zip.write(data.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }

        @Override public void close() throws IOException { zip.close(); }
    }
}
