package tw.boox.ereading;

import android.content.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.function.IntConsumer;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The offline Chinese speech model (about 42 MB) is downloaded the first time voice page
 * turning is switched on, instead of making every install carry it.
 */
final class VoiceModel {
    static final String NAME = "vosk-model-small-cn-0.22";
    /** A copy kept with this project's releases first, then the publisher's own server. */
    static final String[] SOURCES = {
        "https://github.com/lcfeng1212/e-reading/releases/download/voice-model-cn-0.22/" + NAME + ".zip",
        "https://alphacephei.com/vosk/models/" + NAME + ".zip",
    };
    static final long ABOUT_BYTES = 42L * 1024 * 1024;

    private VoiceModel() {}

    static File folder(Context context) { return new File(context.getFilesDir(), "voice-cn"); }

    static boolean ready(Context context) {
        File folder = folder(context);
        return new File(folder, ".ready").isFile() && new File(folder, "am/final.mdl").isFile();
    }

    /**
     * Downloads, verifies and installs the model. Progress runs from 0 to 100. Throws on failure,
     * leaving no partial model behind.
     */
    static void download(Context context, String sha256, IntConsumer progress, BooleanSupplier cancelled)
        throws IOException {
        IOException last = null;
        for (String source : SOURCES) {
            File zip = File.createTempFile("voice-", ".tmp", context.getCacheDir());
            try {
                fetch(source, zip, sha256, progress, cancelled);
                try (var in = new FileInputStream(zip)) { install(context, in); }
                return;
            } catch (InterruptedIOException stop) {
                throw stop;
            } catch (IOException failure) {
                last = failure;
            } finally { zip.delete(); }
        }
        throw last != null ? last : new IOException("無法下載語音模型");
    }

    private static void fetch(String source, File target, String sha256, IntConsumer progress,
                              BooleanSupplier cancelled) throws IOException {
        var connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("下載來源回應 " + connection.getResponseCode());
            long length = connection.getContentLengthLong();
            if (length <= 0) length = ABOUT_BYTES;
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
            long read = 0;
            int reported = -1;
            try (var in = connection.getInputStream(); var out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException("已取消下載");
                    out.write(buffer, 0, n); digest.update(buffer, 0, n);
                    read += n;
                    if (read > 200L * 1024 * 1024) throw new IOException("下載檔案大小異常");
                    int percent = (int) Math.min(99, read * 100 / length);
                    if (percent != reported) { reported = percent; progress.accept(percent); }
                }
            }
            if (sha256 != null && !sha256.isEmpty() && !sha256.equalsIgnoreCase(hex(digest.digest())))
                throw new IOException("語音模型檢查碼不符");
        } finally { connection.disconnect(); }
    }

    /** Unpacks the model archive, dropping its top folder, and switches it in at once. */
    static void install(Context context, InputStream archive) throws IOException {
        File folder = folder(context);
        File staging = new File(context.getFilesDir(), "voice-cn.tmp");
        delete(staging);
        if (!staging.mkdirs()) throw new IOException("無法建立模型資料夾");
        String root = staging.getCanonicalPath() + File.separator;
        try {
            unpack(archive, staging, root);
        } catch (IOException | RuntimeException e) {
            delete(staging);
            throw e;
        }
        if (!new File(staging, "am/final.mdl").isFile()) {
            delete(staging);
            throw new IOException("語音模型內容不完整");
        }
        Files.write(new File(staging, ".ready").toPath(), NAME.getBytes(StandardCharsets.US_ASCII));
        delete(folder);
        if (!staging.renameTo(folder)) { delete(staging); throw new IOException("無法安裝語音模型"); }
    }

    private static void unpack(InputStream archive, File staging, String root) throws IOException {
        try (var zip = new ZipInputStream(new BufferedInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                String inner = slash < 0 ? "" : name.substring(slash + 1);
                if (inner.isEmpty()) continue;
                File out = new File(staging, inner);
                // Refuse entries such as "../x" that would write outside the model folder.
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("語音模型壓縮檔內容異常");
                if (entry.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (var write = new FileOutputStream(out)) { zip.transferTo(write); }
            }
        }
    }

    static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static String hex(byte[] bytes) {
        var out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
}
