package tw.boox.ereading;

import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

final class Library {
    static final Set<String> FORMATS = Set.of("txt", "epub", "mobi", "pdf", "cbz");
    private final Context context;
    private final File folder;
    private final List<JSONObject> entries = new ArrayList<>();
    Library(Context context) {
        this.context = context;
        folder = new File(context.getFilesDir(), "books");
        folder.mkdirs();
        try {
            var saved = new JSONArray(context.getSharedPreferences("library", 0).getString("books", "[]"));
            for (int i = 0; i < saved.length(); i++) entries.add(saved.getJSONObject(i));
        } catch (JSONException ignored) {}
    }

    synchronized List<JSONObject> books() { return new ArrayList<>(entries); }

    synchronized void save() {
        context.getSharedPreferences("library", 0).edit().putString("books", new JSONArray(entries).toString()).apply();
    }

    synchronized JSONObject add(Uri uri, boolean retain, boolean durable) throws Exception {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        if (name == null) name = uri.getLastPathSegment();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String type = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(type)) throw new IOException("請選擇 TXT、EPUB、MOBI、PDF 或 CBZ 書檔");
        File temp = retain ? null : File.createTempFile("import-", ".tmp", folder);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = context.getContentResolver().openInputStream(uri);
                 var out = temp == null ? OutputStream.nullOutputStream() : new FileOutputStream(temp)) {
                if (in == null) throw new IOException("無法讀取這個檔案");
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) != -1;) { digest.update(buffer, 0, n); out.write(buffer, 0, n); }
            }
            String id = hex(digest.digest()) + "." + type;
            JSONObject existing = null;
            for (var item : entries) if (item.optString("id").equals(id)) existing = item;
            if (temp != null) commit(temp, new File(folder, id));
            if (existing != null) {
                if (!existing.optBoolean("local") || temp != null) {
                    existing.put("uri", uri.toString());
                    existing.put("local", temp != null);
                    save();
                }
                return existing;
            }
            var book = new JSONObject();
            book.put("id", id);
            book.put("title", name.substring(0, dot));
            book.put("format", type);
            book.put("uri", uri.toString());
            book.put("local", temp != null);
            book.put("added", System.currentTimeMillis());
            if (!durable && temp == null) throw new IOException("無法保留原檔權限");
            entries.add(book); save();
            return book;
        } finally { if (temp != null) temp.delete(); }
    }

    synchronized JSONObject replace(JSONObject old, Uri uri, boolean continueReading) throws Exception {
        var previous = new JSONObject(old.toString());
        var existing = new ArrayList<>(entries);
        var next = add(uri, false, false);
        try {
            String format = next.optString("format");
            if (format.equals("pdf") || format.equals("cbz")) {
                try (var pages = new Pages(source(next), format.equals("pdf"))) {
                    if (pages.count() < 1) throw new IOException("檔案沒有可讀頁面");
                }
            } else try (var archive = new java.util.zip.ZipFile(epub(next, null))) {
                Books.epub(archive);
            }
        } catch (Exception failure) {
            if (!existing.contains(next)) remove(next);
            throw failure;
        }
        for (String key : List.of("size", "chinese", "night", "hand", "rtl", "language", "font",
            "spacing", "fit", "rotation")) {
            if (previous.has(key)) next.put(key, previous.get(key));
        }
        for (String key : List.of("chapter", "position", "paragraph", "offset", "sample", "anchorPath")) {
            if (continueReading && previous.has(key)) next.put(key, previous.get(key));
            else next.remove(key);
        }
        // A new edition can move bookmarks. Keep them only when the file identity still matches.
        if (!old.optString("id").equals(next.optString("id"))) remove(old);
        save();
        return next;
    }

    File source(JSONObject book) throws IOException {
        File target = new File(folder, book.optString("id"));
        if (target.isFile()) return target;
        if (book.optBoolean("local")) throw new IOException("本機副本不存在，請重新匯入");
        File temp = File.createTempFile("reader-", ".tmp", folder);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = context.getContentResolver().openInputStream(Uri.parse(book.optString("uri")));
                 var out = new FileOutputStream(temp)) {
                if (in == null) throw new IOException("找不到原檔，請重新選檔");
                byte[] buffer = new byte[64 * 1024];
                for (int size; (size = in.read(buffer)) != -1;) {
                    digest.update(buffer, 0, size); out.write(buffer, 0, size);
                }
            }
            if (!book.optString("id").startsWith(hex(digest.digest()) + "."))
                throw new IOException("原檔內容已變更，請重新匯入");
            commit(temp, target);
            return target;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        } finally { temp.delete(); }
    }

    File epub(JSONObject book, String manualEncoding) throws IOException {
        String type = book.optString("format");
        if (type.equals("epub")) return source(book);
        String selected = manualEncoding == null ? book.optString("encoding", "auto") : manualEncoding;
        File result = new File(folder, book.optString("id") + "-v1-" + selected + ".epub");
        if (result.isFile()) return result;
        File original = source(book);
        File temp = File.createTempFile("conversion-", ".tmp", folder);
        try {
            if (type.equals("mobi")) Books.mobi(original, temp, book.optString("title"));
            else Books.txt(original, temp, book.optString("title"),
                selected.equals("auto") ? Books.charset(original) : java.nio.charset.Charset.forName(selected));
            commit(temp, result);
            return result;
        } finally { temp.delete(); }
    }

    synchronized String backup() throws JSONException {
        var all = new JSONArray();
        for (var book : entries) {
            var copy = new JSONObject(book.toString());
            copy.remove("uri"); copy.remove("local");
            all.put(copy);
        }
        return new JSONObject().put("version", 1).put("books", all).toString(2);
    }

    synchronized int restore(String text) throws JSONException {
        var root = new JSONObject(text);
        if (root.getInt("version") != 1) throw new JSONException("備份版本不支援");
        var saved = root.getJSONArray("books");
        int matches = 0;
        for (int i = 0; i < saved.length(); i++) {
            var copy = saved.getJSONObject(i);
            for (var book : entries) {
                if (!book.optString("id").equals(copy.optString("id"))) continue;
                for (String key : List.of("title", "chapter", "position", "paragraph", "offset",
                    "sample", "anchorPath", "markers", "size", "chinese", "night", "hand", "rtl",
                    "language", "font", "spacing", "fit", "rotation", "opened")) {
                    if (copy.has(key)) book.put(key, copy.get(key));
                }
                matches++;
                break;
            }
        }
        save();
        return matches;
    }

    synchronized void remove(JSONObject book) {
        entries.remove(book); save();
        String id = book.optString("id");
        File[] matches = folder.listFiles((dir, name) -> name.equals(id) || name.startsWith(id + "-"));
        if (matches != null) for (File file : matches) file.delete();
    }

    synchronized void clearCachedSource(JSONObject book) {
        if (!book.optBoolean("local")) new File(folder, book.optString("id")).delete();
    }

    private static String hex(byte[] bytes) {
        var hex = new StringBuilder(2 * bytes.length);
        for (byte element : bytes) {
            int b = element & 255;
            hex.append(Character.forDigit(b >>> 4, 16)).append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    private static void commit(File temp, File dest) throws IOException {
        Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
