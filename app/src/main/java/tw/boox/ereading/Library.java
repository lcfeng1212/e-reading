package tw.boox.ereading;

import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
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

    /** Size and modification time of a document, or null values when the provider omits them. */
    private long[] stamp(Uri uri) {
        long size = -1, modified = -1;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int a = cursor.getColumnIndex(OpenableColumns.SIZE);
                int b = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (a >= 0 && !cursor.isNull(a)) size = cursor.getLong(a);
                if (b >= 0 && !cursor.isNull(b)) modified = cursor.getLong(b);
            }
        } catch (Exception ignored) {}
        return new long[]{size, modified};
    }

    JSONObject add(Uri uri, boolean retain, boolean durable) throws Exception {
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
            // Downloading and hashing can take minutes for a cloud file. Do it outside the lock
            // so the shelf and reading position can still be saved meanwhile.
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = context.getContentResolver().openInputStream(uri);
                 var out = temp == null ? OutputStream.nullOutputStream() : new FileOutputStream(temp)) {
                if (in == null) throw new IOException("無法讀取這個檔案");
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) != -1;) { digest.update(buffer, 0, n); out.write(buffer, 0, n); }
            }
            String id = hex(digest.digest()) + "." + type;
            long[] stamp = temp == null ? stamp(uri) : new long[]{-1, -1};
            if (temp != null) commit(temp, new File(folder, id));
            synchronized (this) {
                JSONObject existing = null;
                for (var item : entries) if (item.optString("id").equals(id)) existing = item;
                if (existing != null) {
                    if (!existing.optBoolean("local") || temp != null) {
                        existing.put("uri", uri.toString());
                        existing.put("local", temp != null);
                        existing.put("bytes", stamp[0]); existing.put("modified", stamp[1]);
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
                book.put("bytes", stamp[0]); book.put("modified", stamp[1]);
                book.put("added", System.currentTimeMillis());
                if (!durable && temp == null) throw new IOException("無法保留原檔權限");
                entries.add(book); save();
                return book;
            }
        } finally { if (temp != null) temp.delete(); }
    }

    /**
     * Opens a PDF kept at its original location without copying it, when the file is unchanged
     * since import. Returns null when a verified copy is needed instead.
     */
    ParcelFileDescriptor unchangedPdf(JSONObject book) {
        if (book.optBoolean("local") || !book.optString("format").equals("pdf") || !unchanged(book)) return null;
        try { return context.getContentResolver().openFileDescriptor(Uri.parse(book.optString("uri")), "r"); }
        catch (Exception e) { return null; }
    }

    /**
     * Whether a book kept at its original location still has the size and modification time
     * recorded at import. Unknown values count as changed, so the content is verified again.
     */
    private boolean unchanged(JSONObject book) {
        if (book.optBoolean("local")) return true;
        long size = book.optLong("bytes", -1), modified = book.optLong("modified", -1);
        if (size < 0 || modified < 0) return false;
        long[] now = stamp(Uri.parse(book.optString("uri")));
        return now[0] == size && now[1] == modified;
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
        if (book.optBoolean("local")) {
            if (target.isFile()) return target;
            throw new IOException("本機副本不存在，請重新匯入");
        }
        // A kept copy is reused only while the original is untouched. Otherwise it is copied
        // and hashed again, which reports an edited original instead of showing the old text.
        if (target.isFile() && unchanged(book)) return target;
        target.delete();
        Uri uri = Uri.parse(book.optString("uri"));
        File temp = File.createTempFile("reader-", ".tmp", folder);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = context.getContentResolver().openInputStream(uri);
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
            // Same content with a new timestamp, e.g. copied again: remember the new stamp.
            long[] stamp = stamp(uri);
            synchronized (this) {
                try { book.put("bytes", stamp[0]); book.put("modified", stamp[1]); } catch (JSONException ignored) {}
                save();
            }
            return target;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        } finally { temp.delete(); }
    }

    File epub(JSONObject book, String manualEncoding) throws IOException { return epub(book, manualEncoding, null); }

    File epub(JSONObject book, String manualEncoding, java.util.function.IntConsumer progress) throws IOException {
        String type = book.optString("format");
        if (type.equals("epub")) return source(book);
        String selected = manualEncoding == null ? book.optString("encoding", "auto") : manualEncoding;
        File result = new File(folder, book.optString("id") + "-v1-" + selected + ".epub");
        if (result.isFile() && unchanged(book)) return result;
        // Verifies the original. It throws when the content has changed since import.
        File original = source(book);
        if (result.isFile()) return result;
        File temp = File.createTempFile("conversion-", ".tmp", folder);
        try {
            if (type.equals("mobi")) Mobi.convert(original, temp);
            else Books.txt(original, temp, book.optString("title"),
                selected.equals("auto") ? Books.charset(original) : Books.named(selected), progress);
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

    /**
     * Copies of books kept at their original location are temporary. Keep the copy of the book
     * just closed so reopening it is instant, and delete the others.
     */
    synchronized void clearCachedSource(JSONObject book) {
        for (var item : entries) {
            if (item == book || item.optBoolean("local")) continue;
            new File(folder, item.optString("id")).delete();
        }
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
