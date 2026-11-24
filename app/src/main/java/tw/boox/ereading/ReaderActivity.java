package tw.boox.ereading;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

public final class ReaderActivity extends Activity {
    private static final int PICK = 1, EXPORT = 2, RESTORE = 3, MIC = 4, REPLACE = 5;
    private JSONObject replacement;
    private static final String HOST = "reader.invalid";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Library library;
    private Chinese chinese;
    private Voice voice;
    private LinearLayout layout, header, bar;
    private TextView status, mic;
    private FrameLayout content;
    private JSONObject book;
    private ZipFile zip;
    private Books.Epub epub;
    private Pages pages;
    private WebView web;
    private ImageView image;
    private String readerScript;
    private int chapter, page, paragraph = -1, epoch;
    // The latest turn requested while a page is loading. Only one is kept, so presses do not pile up.
    private int queuedTurn;
    private long lastSave;
    // Not a view: posts on a view replaced by setContentView never run.
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    // Cumulative chapter sizes, so progress reflects how much of the book has been read.
    private long[] weights;
    private boolean saveScheduled;
    private double offset;
    private String sample = "";
    private boolean busy, toolbar, active, voiceOn, voiceRunning, operation, pendingRotate;
    private String voiceState = "";
    private float imageScale = 1, imageX, imageY, downX, downY;
    private String linkFragment, pendingSearch;
    private final LinkedHashMap<String, String> translations = new LinkedHashMap<>(32, .75f, true);
    private Translator translator;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.view.Window window = getWindow();
        window.setStatusBarColor(Color.WHITE); window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        library = new Library(this);
        chinese = new Chinese(this);
        PDFBoxResourceLoader.init(getApplicationContext());
        // Voice page turning starts off every time the app starts. It stays on through a lock
        // screen or a trip to the shelf until the app is closed.
        voiceOn = false;
        getPreferences(0).edit().remove("voice").apply();
        voice = new Voice(this, new Voice.Listener() {
            @Override public void page(int delta) { runOnUiThread(() -> { if (voiceRunning && active) turn(delta); }); }
            @Override public void state(String text) { runOnUiThread(() -> { if (voiceRunning) { voiceState = text; refreshStatus(); } }); }
        });
        try (var in = getAssets().open("reader.js")) { readerScript = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException e) { readerScript = ""; }
        try {
            var disk = new JSONObject(getPreferences(0).getString("translations", "{}"));
            for (Iterator<String> it = disk.keys(); it.hasNext();) { String key = it.next(); translations.put(key, disk.getString(key)); }
        } catch (JSONException ignored) {}
        shelf("");
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); }
    private void handle(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null)
            importFiles(List.of(intent.getData()), intent.getFlags());
    }
    @Override protected void onResume() { super.onResume(); active = true; syncVoice(); }
    @Override protected void onPause() { active = false; syncVoice(); snapshot(true); super.onPause(); }
    @Override protected void onDestroy() {
        closeBook(); voice.close();
        if (translator != null) translator.close();
        io.shutdown();
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (web != null && !busy) web.postDelayed(this::relayout, 120);
        else if (web != null) pendingRotate = true;
        if (image != null) resetImage();
    }
    @Override public void onBackPressed() { if (book != null) shelf(""); else super.onBackPressed(); }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean next = code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_VOLUME_DOWN
            || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean prior = code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_VOLUME_UP
            || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP;
        if (book != null && (next || prior)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                int direction = next ? 1 : -1;
                turn("left".equals(book.optString("hand")) ? -direction : direction);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String value, int size) {
        var view = new TextView(this);
        view.setText(value); view.setTextColor(Color.BLACK); view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private TextView action(String value, Runnable task) {
        var view = label(value, 15);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(46)); view.setPadding(dp(8), 0, dp(8), 0);
        view.setBackgroundResource(android.R.drawable.list_selector_background);
        view.setOnClickListener(v -> task.run());
        return view;
    }
    private LinearLayout column() { var v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { return new LinearLayout(this); }
    private void weighted(LinearLayout row, View child) { row.addView(child, new LinearLayout.LayoutParams(0, -2, 1)); }
    private void divider(LinearLayout parent) { var rule = new View(this); rule.setBackgroundColor(0xffd5d5d5); parent.addView(rule, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void base() { layout = column(); layout.setBackgroundColor(Color.WHITE); setContentView(layout); }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void error(Exception e) { message(e.getMessage() == null ? "操作失敗" : e.getMessage()); }
    // Book records are serialized by Library on the I/O thread too. Edit them under its lock.
    private void put(JSONObject target, String key, Object value) {
        synchronized (library) { try { target.put(key, value); } catch (JSONException ignored) {} }
    }

    private void shelf(String query) {
        closeBook();
        base();
        var top = row(); top.setPadding(dp(20), dp(12), dp(14), 0); layout.addView(top);
        var title = label("書架", 32); title.setTypeface(null, Typeface.BOLD);
        weighted(top, title);
        top.addView(action("＋", this::pick));
        top.addView(action("⋯", this::shelfOptions));
        var scroll = new ScrollView(this); layout.addView(scroll);
        var list = column(); list.setPadding(dp(20), 0, dp(20), dp(20)); scroll.addView(list);
        var search = new EditText(this); search.setSingleLine(true); search.setHint("搜尋書名"); search.setText(query);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, a, e) -> { shelf(search.getText().toString().strip()); return true; });
        list.addView(search);
        list.setFocusableInTouchMode(true); list.requestFocus();
        var all = library.books();
        var recent = new ArrayList<>(all);
        recent.sort(Comparator.comparingLong((JSONObject b) -> b.optLong("opened")).reversed());
        if (query.isBlank() && !recent.isEmpty() && recent.get(0).has("opened")) {
            var latest = recent.get(0);
            list.addView(label("繼續閱讀", 16));
            addBookRow(list, latest);
            if (recent.size() > 1 && recent.get(1).has("opened")) {
                list.addView(label("最近開啟", 16));
                for (int i = 1; i < Math.min(recent.size(), 4); i++)
                    if (recent.get(i).has("opened")) addBookRow(list, recent.get(i));
            }
        }
        list.addView(label("全部書籍", 16));
        int sort = getPreferences(0).getInt("sort", 0);
        if (sort == 0) all = recent;
        else if (sort == 1) all.sort(Comparator.comparing(b -> b.optString("title"), java.text.Collator.getInstance(Locale.TAIWAN)));
        else all.sort(Comparator.comparingLong((JSONObject b) -> b.optLong("added")).reversed());
        for (JSONObject entry : all)
            if (entry.optString("title").toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) addBookRow(list, entry);
        if (all.isEmpty()) list.addView(label("按「＋」開啟第一本書", 17));
    }

    private void addBookRow(LinearLayout list, JSONObject entry) {
        var item = action("", () -> open(entry, null));
        item.setGravity(Gravity.CENTER_VERTICAL); item.setTextSize(17);
        item.setText(entry.optString("title") + "\n" + entry.optString("format").toUpperCase(Locale.ROOT)
            + (entry.has("opened") ? "  ·  " + entry.optString("progress") : ""));
        item.setMinHeight(dp(60));
        item.setOnLongClickListener(view -> { bookOptions(entry); return true; });
        list.addView(item); divider(list);
    }

    private void shelfOptions() {
        new AlertDialog.Builder(this).setItems(new String[]{"排序", "匯出閱讀備份", "匯入閱讀備份"}, (d, i) -> {
            if (i == 0) {
                new AlertDialog.Builder(this).setTitle("排序").setSingleChoiceItems(
                    new String[]{"最近閱讀", "書名", "加入日期"}, getPreferences(0).getInt("sort", 0), (dialog, choice) -> {
                        getPreferences(0).edit().putInt("sort", choice).apply(); dialog.dismiss(); shelf("");
                    }).show();
            } else if (i == 1) {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "e-reading-backup.json"), EXPORT);
            } else {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE), RESTORE);
            }
        }).show();
    }

    private void bookOptions(JSONObject entry) {
        new AlertDialog.Builder(this).setTitle(entry.optString("title")).setItems(new String[]{"改名", "檔案資訊", "替換書檔", "從書架移除"}, (d, i) -> {
            if (i == 0) {
                var field = new EditText(this); field.setText(entry.optString("title"));
                new AlertDialog.Builder(this).setTitle("改名").setView(field).setNegativeButton("取消", null)
                    .setPositiveButton("儲存", (a, b) -> {
                        String text = field.getText().toString().strip();
                        if (!text.isEmpty()) { put(entry, "title", text); library.save(); shelf(""); }
                    }).show();
            } else if (i == 1) {
                new AlertDialog.Builder(this).setTitle("檔案資訊")
                    .setMessage(entry.optString("format").toUpperCase(Locale.ROOT) + "\n" + (entry.optBoolean("local") ? "本機副本" : "本機原檔"))
                    .setPositiveButton("關閉", null).show();
            } else if (i == 2) {
                replacement = entry;
                var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REPLACE);
            } else {
                new AlertDialog.Builder(this).setTitle("從書架移除？").setMessage("原始檔仍會保留。")
                    .setNegativeButton("取消", null).setPositiveButton("移除", (a, b) -> { library.remove(entry); shelf(""); }).show();
            }
        }).show();
    }

    private void pick() {
        if (operation) return;
        var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REPLACE) {
            JSONObject previous = replacement;
            replacement = null;
            if (previous != null && result == RESULT_OK && data != null && data.getData() != null)
                replaceBook(previous, data.getData());
            return;
        }
        if (result != RESULT_OK || data == null) return;
        if (request == PICK) {
            var all = new ArrayList<Uri>();
            var clip = data.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) all.add(clip.getItemAt(i).getUri());
            else if (data.getData() != null) all.add(data.getData());
            importFiles(all, data.getFlags());
        } else if (data.getData() != null && (request == EXPORT || request == RESTORE)) {
            Uri uri = data.getData();
            io.execute(() -> {
                try {
                    if (request == EXPORT) {
                        try (var out = getContentResolver().openOutputStream(uri, "wt")) {
                            if (out == null) throw new IOException("無法寫入備份");
                            out.write(library.backup().getBytes(StandardCharsets.UTF_8));
                        }
                        runOnUiThread(() -> message("已匯出閱讀備份"));
                    } else {
                        try (var in = getContentResolver().openInputStream(uri)) {
                            if (in == null) throw new IOException("無法讀取備份");
                            byte[] bytes = in.readNBytes(4 * 1024 * 1024 + 1);
                            if (bytes.length > 4 * 1024 * 1024) throw new IOException("備份檔過大");
                            int matches = library.restore(new String(bytes, StandardCharsets.UTF_8));
                            runOnUiThread(() -> { shelf(""); message("已還原 " + matches + " 本書的進度"); });
                        }
                    }
                } catch (Exception e) { runOnUiThread(() -> error(e)); }
            });
        }
    }

    private void importFiles(List<Uri> all, int flags) {
        if (all.isEmpty() || operation) return;
        operation = true;
        var wait = progress("正在匯入／下載檔案…");
        io.execute(() -> {
            var imported = new ArrayList<JSONObject>();
            var failures = new ArrayList<String>();
            for (Uri uri : all) {
                try {
                    boolean persistent = false;
                    if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            persistent = true;
                        } catch (SecurityException ignored) {}
                    }
                    String provider = uri.getAuthority();
                    boolean local = "com.android.externalstorage.documents".equals(provider) ||
                        "com.android.providers.downloads.documents".equals(provider) ||
                        "com.android.providers.media.documents".equals(provider);
                    imported.add(library.add(uri, local && persistent, persistent));
                } catch (Exception e) { failures.add(e.getMessage() == null ? "匯入失敗" : e.getMessage()); }
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                operation = false; wait.dismiss();
                if (all.size() == 1 && imported.size() == 1) open(imported.get(0), null);
                else shelf("");
                if (!failures.isEmpty()) new AlertDialog.Builder(this).setTitle("有檔案未匯入")
                    .setMessage(String.join("\n", failures)).setNegativeButton("關閉", null)
                    .setPositiveButton("重試", (a, b) -> importFiles(all, flags)).show();
            });
        });
    }

    private void replaceBook(JSONObject previous, Uri uri) {
        new AlertDialog.Builder(this).setTitle("替換書檔")
            .setItems(new String[]{"替換並保留閱讀位置", "替換並從頭閱讀"}, (dialog, choice) -> {
                if (operation) return;
                operation = true;
                var wait = progress("正在讀取新版…");
                io.execute(() -> {
                    try {
                        var next = library.replace(previous, uri, choice == 0);
                        runOnUiThread(() -> {
                            if (isDestroyed()) return;
                            operation = false; wait.dismiss(); shelf(""); open(next, null);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            if (isDestroyed()) return;
                            operation = false; wait.dismiss(); error(e);
                        });
                    }
                });
            }).setNegativeButton("取消", null).show();
    }

    private AlertDialog progress(String text) {
        return new AlertDialog.Builder(this).setMessage(text).setCancelable(false).show();
    }

    private void open(JSONObject entry, String encoding) {
        if (operation) return;
        closeBook();
        operation = true;
        String type = entry.optString("format");
        var wait = progress(type.equals("txt") ? "正在轉成 EPUB…" : "正在開書…");
        io.execute(() -> {
            ZipFile z = null; Pages p = null;
            try {
                Books.Epub description = null;
                if (type.equals("pdf")) {
                    var inPlace = library.unchangedPdf(entry);
                    // Some providers return a descriptor PdfRenderer cannot seek. Fall back to a copy.
                    if (inPlace != null) try { p = new Pages(inPlace, () -> library.source(entry)); }
                        catch (Exception unreadable) { p = null; }
                    if (p == null) p = new Pages(library.source(entry), true);
                } else if (type.equals("cbz")) p = new Pages(library.source(entry), false);
                else {
                    java.util.function.IntConsumer progress = type.equals("txt") ? percent ->
                        runOnUiThread(() -> { if (wait.isShowing()) wait.setMessage("正在轉成 EPUB… " + percent + "%"); }) : null;
                    z = new ZipFile(library.epub(entry, encoding, progress)); description = Books.epub(z);
                }
                var finalZip = z; var finalPages = p; var finalDescription = description;
                runOnUiThread(() -> {
                    if (isDestroyed()) { close(finalZip); close(finalPages); return; }
                    operation = false; wait.dismiss();
                    book = entry; zip = finalZip; epub = finalDescription; pages = finalPages;
                    weights = null;
                    if (epub != null) {
                        weights = new long[epub.chapters().size() + 1];
                        for (int i = 0; i < epub.chapters().size(); i++) {
                            var item = zip.getEntry(epub.chapters().get(i).path());
                            weights[i + 1] = weights[i] + Math.max(1, item == null ? 1 : item.getSize());
                        }
                    }
                    page = Math.max(-1, entry.optInt("position"));
                    chapter = epub == null ? 0 : Math.min(epub.chapters().size() - 1, Math.max(0, entry.optInt("chapter")));
                    if (epub != null && entry.has("anchorPath")) {
                        String anchorPath = entry.optString("anchorPath");
                        for (int i = 0; i < epub.chapters().size(); i++) {
                            if (epub.chapters().get(i).path().equals(anchorPath)) { chapter = i; break; }
                        }
                    }
                    paragraph = entry.optInt("paragraph", -1); offset = entry.optDouble("offset", 0); sample = entry.optString("sample");
                    if (encoding != null) put(entry, "encoding", encoding);
                    put(entry, "opened", System.currentTimeMillis());
                    library.save();
                    reading();
                });
            } catch (Exception e) {
                close(z); close(p);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    operation = false; wait.dismiss();
                    if (type.equals("txt") && e instanceof java.nio.charset.CharacterCodingException) encodingPicker(entry);
                    else error(e);
                });
            }
        });
    }

    private void encodingPicker(JSONObject entry) {
        var modes = new String[]{"UTF-8", "Big5", "GB18030", "UTF-16"};
        io.execute(() -> {
            try {
                byte[] bytes;
                try (var in = new FileInputStream(library.source(entry))) { bytes = in.readNBytes(680); }
                var previews = new String[modes.length];
                for (int i = 0; i < modes.length; i++) {
                    String s = new String(bytes, Books.named(modes[i])).strip();
                    previews[i] = modes[i] + "\n" + s.substring(0, Math.min(s.length(), 100));
                }
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("請選擇顯示正常的文字")
                    .setItems(previews, (d, i) -> open(entry, modes[i])).setNegativeButton("取消", null).show());
            } catch (Exception e) { runOnUiThread(() -> error(e)); }
        });
    }

    private void reading() {
        toolbar = false;
        base();
        header = row(); layout.addView(header);
        header.addView(action("返回", () -> shelf("")));
        var title = label(book.optString("title"), 17); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        weighted(header, title); header.addView(action("書籤", this::bookmark));
        content = new FrameLayout(this); layout.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        var footer = row(); layout.addView(footer);
        status = label("", 12); status.setGravity(Gravity.CENTER); weighted(footer, status);
        status.setOnClickListener(v -> toggleToolbar());
        mic = label("", 12); mic.setPadding(dp(5), 0, dp(10), 0); footer.addView(mic);
        mic.setOnClickListener(v -> toggleVoice());
        bar = row(); layout.addView(bar);
        weighted(bar, action("目錄／跳頁", this::navigation));
        weighted(bar, action(pages != null || epub.fixed() ? "版面" : "Aa", this::appearance));
        if (epub != null || book.optString("format").equals("pdf")) weighted(bar, action("翻譯", () -> translate(false)));
        weighted(bar, action("語音", this::toggleVoice));
        header.setVisibility(View.GONE); bar.setVisibility(View.GONE);
        if (epub != null) createWeb();
        else createImage();
        refreshStatus(); syncVoice();
    }

    private void toggleToolbar() {
        toolbar = !toolbar;
        header.setVisibility(toolbar ? View.VISIBLE : View.GONE);
        bar.setVisibility(toolbar ? View.VISIBLE : View.GONE);
        if (web != null && !busy) web.postDelayed(this::relayout, 80);
        if (image != null) resetImage();
    }

    private void createWeb() {
        final ZipFile archive = zip;
        web = new WebView(this);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!HOST.equals(url.getHost()) || !"https".equals(url.getScheme())) return empty();
                try {
                    String name = url.getPath();
                    if (name == null || name.length() <= 1) return empty();
                    name = name.substring(1);
                    var entry = archive.getEntry(name);
                    if (entry == null || entry.isDirectory() || entry.getSize() > 32L * 1024 * 1024) return empty();
                    String kind = mime(name);
                    // Forbid book scripts, embedded frames, remote requests and form submissions.
                    Map<String, String> headers = Map.of("Content-Security-Policy",
                        "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; media-src 'self'; script-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'",
                        "X-Content-Type-Options", "nosniff");
                    return new WebResourceResponse(kind, kind.startsWith("text/") ? "UTF-8" : null, 200, "OK", headers, archive.getInputStream(entry));
                } catch (Exception ignored) { return empty(); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (view != web || !request.isForMainFrame()) return true;
                Uri uri = request.getUrl();
                if (!HOST.equals(uri.getHost()) || !"https".equals(uri.getScheme())) return true;
                String path = uri.getPath();
                if (path == null) return true;
                for (int i = 0; i < epub.chapters().size(); i++) {
                    if (!("/" + epub.chapters().get(i).path()).equals(path)) continue;
                    if (i == chapter && uri.getFragment() != null) js("reader.chapterLink(" + JSONObject.quote(uri.getFragment()) + ")", ReaderActivity.this::apply);
                    else {
                        linkFragment = uri.getFragment();
                        loadChapter(i, 0, -1, 0, "");
                    }
                    break;
                }
                return true;
            }
            // Without this, Android kills the whole app when the page renderer dies, e.g. under
            // memory pressure. Rebuild the page at the last known position instead.
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != web || book == null) { view.destroy(); return true; }
                content.removeView(web);
                web.destroy(); web = null;
                epoch++; busy = false; queuedTurn = 0;
                message("閱讀頁已重新載入");
                createWeb();
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                // Compare decoded paths: WebView may report the address with different escaping.
                Uri loaded = Uri.parse(url);
                if (view != web || !HOST.equals(loaded.getHost())
                    || !("/" + epub.chapters().get(chapter).path()).equals(loaded.getPath())) return;
                int run = epoch;
                js("window.bookFixed=" + epub.fixed() + ";\n" + readerScript, result -> {
                    if (run != epoch) return;
                    transform(true, () -> restoreWeb(page, paragraph, offset, sample));
                });
            }
        });
        web.setVerticalScrollBarEnabled(false); web.setHorizontalScrollBarEnabled(false);
        var settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(false); settings.setSupportMultipleWindows(false);
        settings.setTextZoom(100);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        web.setOnTouchListener(this::readerTouch);
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        loadChapter(chapter, page, paragraph, offset, sample);
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Map.of(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "html", "htm", "xhtml" -> "text/html";
            case "css" -> "text/css";
            case "svg" -> "image/svg+xml";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            default -> Optional.ofNullable(MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)).orElse("application/octet-stream");
        };
    }

    private String chapterUrl(int index) {
        return "https://" + HOST + "/" + Uri.encode(epub.chapters().get(index).path(), "/");
    }

    private void loadChapter(int index, int nextPage, int anchor, double ratio, String excerpt) {
        epoch++;
        busy = true; chapter = index; page = nextPage; paragraph = anchor; offset = ratio; sample = excerpt;
        web.loadUrl(chapterUrl(index));
    }

    private void js(String code, Consumer<String> callback) {
        if (web == null) return;
        WebView original = web;
        int run = epoch;
        original.evaluateJavascript(code, value -> {
            if (web == original && run == epoch && callback != null) callback.accept(value);
        });
    }

    private void transform(boolean freshlyLoaded, Runnable done) {
        if (epub.fixed()) { done.run(); return; }
        int run = epoch;
        String mode = book.optString("chinese", "original");
        if (mode.equals("original")) {
            if (freshlyLoaded) done.run();
            else js("reader.replace([])", ignored -> done.run());
            return;
        }
        js("reader.originals()", value -> io.execute(() -> {
            try {
                var input = new JSONArray(value);
                var output = new JSONArray();
                for (int i = 0; i < input.length(); i++) output.put(chinese.convert(input.getString(i), mode));
                runOnUiThread(() -> {
                    if (run != epoch || web == null) return;
                    js("reader.replace(" + output + ")", ignored -> done.run());
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (run == epoch && web != null) { error(e); done.run(); } });
            }
        }));
    }

    private void restoreWeb(int position, int anchor, double ratio, String excerpt) {
        String settings = "{\"font\":" + Math.max(14, Math.min(44, book.optInt("size", 23))) +
            ",\"night\":" + book.optBoolean("night") + ",\"serif\":" + book.optString("font").equals("serif") +
            ",\"line\":" + Math.max(1.2, Math.min(2.1, book.optDouble("spacing", 1.55))) + "}";
        js("reader.layout(" + settings + ")", unused ->
            js("reader.locate(" + position + "," + anchor + "," + ratio + "," + JSONObject.quote(excerpt) + ")", value -> {
                apply(value);
                if (linkFragment != null) {
                    String next = linkFragment; linkFragment = null;
                    js("reader.chapterLink(" + JSONObject.quote(next) + ")", this::apply);
                }
                if (pendingSearch != null) {
                    String query = pendingSearch; pendingSearch = null;
                    js("reader.search(" + JSONObject.quote(query) + ")", this::apply);
                }
                if (pendingRotate) { pendingRotate = false; relayout(); }
            }));
    }

    private void relayout() { if (web != null && !busy) restoreWeb(page, paragraph, offset, sample); }

    private void apply(String json) {
        settle();
        try {
            JSONObject state = new JSONObject(json);
            page = state.getInt("page"); paragraph = state.optInt("paragraph", -1);
            offset = state.optDouble("offset", 0); sample = state.optString("sample");
            int total = Math.max(1, state.optInt("total", 1));
            int percent = weights == null
                ? (int) Math.round(100d * (chapter + (page + 1d) / total) / epub.chapters().size())
                : (int) Math.round(100d * (weights[chapter] + (weights[chapter + 1] - weights[chapter]) * (page + 1d) / total)
                    / weights[weights.length - 1]);
            put(book, "progress", Math.max(1, Math.min(100, percent)) + "%");
            snapshot();
        } catch (Exception ignored) {}
        refreshStatus();
    }

    private void createImage() {
        image = new ImageView(this); image.setScaleType(ImageView.ScaleType.MATRIX);
        var pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                imageScale = Math.max(1, Math.min(6, imageScale * detector.getScaleFactor()));
                updateImageMatrix(); return true;
            }
        });
        image.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            return pinch.isInProgress() || readerTouch(v, e);
        });
        content.addView(image, new FrameLayout.LayoutParams(-1, -1));
        page = Math.max(0, Math.min(page, pages.count() - 1));
        renderImage();
    }

    private void renderImage() {
        Pages renderer = pages;
        int run = ++epoch;
        int index = page;
        boolean crop = book.optString("fit").equals("crop");
        busy = true;
        io.execute(() -> {
            try {
                Bitmap result = renderer.render(index, crop);
                runOnUiThread(() -> {
                    if (run != epoch || image == null) { result.recycle(); return; }
                    image.setImageBitmap(result); resetImage(); settle();
                    put(book, "progress", (page + 1) + " / " + pages.count());
                    snapshot(); refreshStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (run == epoch) { busy = false; queuedTurn = 0; error(e); } });
            }
        });
    }

    private void resetImage() {
        if (image == null) return;
        imageScale = 1; imageX = 0; imageY = 0;
        image.post(this::updateImageMatrix);
    }

    private void updateImageMatrix() {
        if (image == null || image.getDrawable() == null || image.getWidth() <= 0 || image.getHeight() <= 0) return;
        float width = image.getDrawable().getIntrinsicWidth(), height = image.getDrawable().getIntrinsicHeight();
        int rotation = book.optInt("rotation");
        RectF rect = new RectF(0, 0, width, height);
        var matrix = new Matrix();
        matrix.setRotate(rotation, width / 2, height / 2); matrix.mapRect(rect);
        float scale = image.getWidth() / rect.width();
        if (!book.optString("fit").equals("width")) scale = Math.min(scale, image.getHeight() / rect.height());
        scale *= imageScale;
        float spillX = Math.max(0, (rect.width() * scale - image.getWidth()) / 2);
        float spillY = Math.max(0, (rect.height() * scale - image.getHeight()) / 2);
        imageX = Math.max(-spillX, Math.min(spillX, imageX));
        imageY = Math.max(-spillY, Math.min(spillY, imageY));
        matrix.postTranslate(-rect.left, -rect.top); matrix.postScale(scale, scale);
        matrix.postTranslate((image.getWidth() - rect.width() * scale) / 2 + imageX,
            (image.getHeight() - rect.height() * scale) / 2 + imageY);
        image.setImageMatrix(matrix);
    }

    private boolean readerTouch(View view, MotionEvent event) {
        if (event.getPointerCount() > 1) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); return view == image;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE && image != null && (imageScale > 1 || book.optString("fit").equals("width"))) {
            imageX += event.getX() - downX; imageY += event.getY() - downY;
            downX = event.getX(); downY = event.getY(); updateImageMatrix(); return true;
        }
        if (event.getAction() != MotionEvent.ACTION_UP) return view == image;
        float delta = event.getX() - downX;
        if (event.getEventTime() - event.getDownTime() > 420) return view == image;
        boolean rtl = book.optBoolean("rtl", epub != null && epub.rtl());
        if (Math.abs(delta) > dp(48)) { turn((delta < 0 ? 1 : -1) * (rtl ? -1 : 1)); return true; }
        if (Math.abs(delta) > dp(18) || Math.abs(event.getY() - downY) > dp(18)) return view == image;
        float x = event.getX() / Math.max(1, view.getWidth());
        if (x > .27f && x < .73f) toggleToolbar();
        else {
            String hand = book.optString("hand");
            int next = (x > .5 ? 1 : -1) * (rtl ? -1 : 1);
            if (hand.equals("left")) next = x < .5 ? 1 : -1;
            if (hand.equals("right")) next = x > .5 ? 1 : -1;
            turn(next);
        }
        if (web != null) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            web.onTouchEvent(cancel);
            cancel.recycle();
        }
        return true;
    }

    private void settle() {
        busy = false;
        if (queuedTurn == 0 || book == null) return;
        int delta = queuedTurn;
        queuedTurn = 0;
        main.post(() -> turn(delta));
    }

    private void turn(int delta) {
        if (book == null) return;
        if (busy) { queuedTurn = delta; return; }
        if (pages != null) {
            int next = page + delta;
            if (next >= 0 && next < pages.count()) { page = next; renderImage(); }
            return;
        }
        busy = true;
        js("reader.turn(" + delta + ")", value -> {
            if ("null".equals(value)) {
                int next = chapter + delta;
                if (next >= 0 && next < epub.chapters().size()) loadChapter(next, delta > 0 ? 0 : -1, -1, 0, "");
                else settle();
            } else apply(value);
        });
    }

    private void snapshot() { snapshot(false); }

    /**
     * Records the reading position. Writing the whole shelf on every page turn is wasteful on an
     * e-reader, so routine saves are batched; leaving the page or the app saves at once.
     */
    private void snapshot(boolean now) {
        if (book == null) {
            if (now && saveScheduled) { saveScheduled = false; library.save(); }
            return;
        }
        // While a chapter loads, the fields already name its target (page -1 is its last page),
        // so leaving the book at that moment still records where the reader was heading.
        if (busy && !now) return;
        put(book, "position", page); put(book, "chapter", chapter);
        put(book, "paragraph", paragraph); put(book, "offset", offset); put(book, "sample", sample);
        if (epub != null) put(book, "anchorPath", epub.chapters().get(chapter).path());
        long elapsed = android.os.SystemClock.elapsedRealtime() - lastSave;
        if (now || elapsed >= 3000) {
            saveScheduled = false; lastSave = android.os.SystemClock.elapsedRealtime(); library.save();
        } else if (!saveScheduled) {
            saveScheduled = true;
            main.postDelayed(() -> { if (saveScheduled) snapshot(true); }, 3000 - elapsed);
        }
    }

    private void refreshStatus() {
        if (book == null || status == null) return;
        status.setText(book.optString("progress", " "));
        mic.setText(voiceOn ? (voiceState.isBlank() ? "語音開啟" : voiceState) : "");
    }

    private void navigation() {
        if (busy) return;
        String[] items = epub != null ? new String[]{"目錄", "書籤", "跳頁", "搜尋本書"}
            : new String[]{"書籤", "跳頁"};
        new AlertDialog.Builder(this).setTitle("目錄／跳頁").setItems(items, (d, i) -> {
            switch (items[i]) {
                case "目錄" -> contents();
                case "書籤" -> markers();
                case "跳頁" -> jump();
                default -> search();
            }
        }).show();
    }

    private void contents() {
        if (epub == null) { jump(); return; }
        var titles = new ArrayList<String>();
        var positions = new ArrayList<Integer>();
        for (int i = 0; i < epub.chapters().size(); i++) {
            var part = epub.chapters().get(i);
            if (part.title() != null) { titles.add(part.title()); positions.add(i); }
        }
        if (titles.isEmpty()) { jump(); return; }
        new AlertDialog.Builder(this).setTitle("目錄").setItems(titles.toArray(new String[0]),
            (d, i) -> loadChapter(positions.get(i), 0, -1, 0, "")).show();
    }

    private void jump() {
        var field = new EditText(this);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int max = pages != null ? pages.count() : 100;
        field.setHint("1–" + max);
        new AlertDialog.Builder(this).setTitle(pages != null ? "跳到頁碼" : "跳到進度 %").setView(field)
            .setNegativeButton("取消", null).setPositiveButton("前往", (d, which) -> {
                try {
                    int target = Integer.parseInt(field.getText().toString());
                    target = Math.max(1, Math.min(target, max));
                    if (pages != null) { page = target - 1; renderImage(); }
                    else {
                        int part = Math.min(epub.chapters().size() - 1, (int)(target / 100d * epub.chapters().size()));
                        loadChapter(part, target == 100 ? -1 : 0, -1, 0, "");
                    }
                } catch (NumberFormatException ignored) {}
            }).show();
    }

    private record SearchHit(int section, String label) {}

    private void search() {
        if (web == null) return;
        var field = new EditText(this); field.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("搜尋本書").setView(field).setNegativeButton("取消", null)
            .setPositiveButton("搜尋", (d, i) -> {
                String text = field.getText().toString().strip();
                if (!text.isEmpty()) searchBook(text);
            }).show();
    }

    private void searchBook(String term) {
        String needle = term.toLowerCase(Locale.ROOT);
        String mode = book.optString("chinese", "original");
        ZipFile archive = zip;
        var chapters = epub.chapters();
        int run = epoch;
        var wait = progress("正在搜尋本書…");
        io.execute(() -> {
            try {
                var hits = new ArrayList<SearchHit>();
                for (int i = 0; i < chapters.size() && hits.size() < 100; i++) {
                    var part = chapters.get(i);
                    var entry = archive.getEntry(part.path());
                    if (entry == null || entry.getSize() > 8 * 1024 * 1024) continue;
                    String source;
                    try (var in = archive.getInputStream(entry)) {
                        byte[] bytes = in.readNBytes(8 * 1024 * 1024 + 1);
                        if (bytes.length > 8 * 1024 * 1024) continue;
                        source = new String(bytes, StandardCharsets.UTF_8);
                    }
                    source = source.replaceAll("(?is)<(?:head|script|style)\\b[^>]*>.*?</(?:head|script|style)>", "");
                    String plain = android.text.Html.fromHtml(source, android.text.Html.FROM_HTML_MODE_LEGACY).toString();
                    if (!mode.equals("original")) plain = chinese.convert(plain, mode);
                    int found = plain.toLowerCase(Locale.ROOT).indexOf(needle);
                    if (found < 0) continue;
                    int start = Math.min(plain.length(), Math.max(0, found - 13));
                    int end = Math.min(plain.length(), found + needle.length() + 24);
                    String name = part.title() == null ? "第 " + (i + 1) + " 節" : part.title();
                    hits.add(new SearchHit(i, name + "\n" + plain.substring(start, end).replaceAll("\\s+", " ")));
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    wait.dismiss();
                    if (run != epoch) return;
                    if (hits.isEmpty()) { message("本書找不到「" + term + "」"); return; }
                    new AlertDialog.Builder(this).setTitle("搜尋結果")
                        .setItems(hits.stream().map(SearchHit::label).toArray(String[]::new), (d, i) -> {
                            pendingSearch = term;
                            loadChapter(hits.get(i).section(), 0, -1, 0, "");
                        }).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!isDestroyed()) { wait.dismiss(); error(e); } });
            }
        });
    }

    private JSONArray bookmarkList() {
        var result = book.optJSONArray("markers");
        if (result == null) { result = new JSONArray(); put(book, "markers", result); }
        return result;
    }

    private void bookmark() {
        if (busy) return;
        var markers = bookmarkList();
        if (pages != null) {
            for (int i = 0; i < markers.length(); i++) {
                var marker = markers.optJSONObject(i);
                if (marker != null && marker.optInt("page") == page) { removeBookmark(markers, i); return; }
            }
            addBookmark(markers);
            return;
        }
        // Page numbers move when the font size changes. Ask the page which saved anchors it holds.
        var indexes = new ArrayList<Integer>();
        var anchors = new JSONArray();
        for (int i = 0; i < markers.length(); i++) {
            var marker = markers.optJSONObject(i);
            if (marker == null || marker.optInt("chapter") != chapter) continue;
            try {
                anchors.put(new JSONArray().put(marker.optInt("paragraph", -1)).put(marker.optDouble("offset", 0))
                    .put(marker.optString("sample")));
            } catch (JSONException invalid) { continue; }
            indexes.add(i);
        }
        if (indexes.isEmpty()) { addBookmark(markers); return; }
        js("reader.onPage(" + anchors + ")", value -> {
            int found;
            try { found = Integer.parseInt(value); } catch (NumberFormatException e) { found = -1; }
            if (found >= 0 && found < indexes.size()) removeBookmark(markers, indexes.get(found));
            else addBookmark(markers);
        });
    }

    private void removeBookmark(JSONArray markers, int index) {
        synchronized (library) { markers.remove(index); }
        library.save(); message("已移除本頁書籤");
    }

    private void addBookmark(JSONArray markers) {
        var marker = new JSONObject();
        put(marker, "chapter", chapter); put(marker, "page", page); put(marker, "paragraph", paragraph);
        put(marker, "offset", offset); put(marker, "sample", sample); put(marker, "label", book.optString("progress"));
        synchronized (library) { markers.put(marker); }
        library.save(); message("已加書籤");
    }

    private void markers() {
        var markers = bookmarkList();
        if (markers.length() == 0) { message("還沒有書籤"); return; }
        var labels = new String[markers.length()];
        for (int i = 0; i < labels.length; i++) {
            var entry = markers.optJSONObject(i);
            labels[i] = entry == null ? "書籤" : entry.optString("label") + "  " + entry.optString("sample");
        }
        new AlertDialog.Builder(this).setTitle("書籤").setItems(labels, (d, i) -> {
            var entry = markers.optJSONObject(i);
            if (entry == null) return;
            int next = entry.optInt("page");
            if (pages != null) { page = Math.max(0, Math.min(next, pages.count() - 1)); renderImage(); }
            else loadChapter(Math.max(0, Math.min(entry.optInt("chapter"), epub.chapters().size() - 1)),
                next, entry.optInt("paragraph", -1), entry.optDouble("offset"), entry.optString("sample"));
        }).show();
    }

    private void appearance() {
        if (busy) return;
        if (pages != null || epub.fixed()) {
            var options = pages == null ? new String[]{"翻頁設定"} :
                book.optString("format").equals("pdf") ? new String[]{"整頁", "適寬", "裁白邊", "旋轉", "翻頁設定"} :
                new String[]{"左至右", "右至左", "旋轉", "翻頁設定"};
            new AlertDialog.Builder(this).setTitle("版面").setItems(options, (d, i) -> {
                String choice = options[i];
                switch (choice) {
                    case "整頁" -> { put(book, "fit", "page"); renderImage(); }
                    case "適寬" -> { put(book, "fit", "width"); renderImage(); }
                    case "裁白邊" -> { put(book, "fit", "crop"); renderImage(); }
                    case "旋轉" -> { put(book, "rotation", (book.optInt("rotation") + 90) % 360); resetImage(); }
                    case "左至右", "右至左" -> put(book, "rtl", choice.equals("右至左"));
                    default -> hand();
                }
                library.save();
            }).show();
            return;
        }
        var pane = column(); pane.setPadding(dp(16), dp(10), dp(16), dp(8));
        var text = row(); pane.addView(text);
        var current = label("", 16);
        current.setGravity(Gravity.CENTER);
        current.setText("字級 " + book.optInt("size", 23));
        weighted(text, action("－", () -> font(-2, current)));
        weighted(text, current);
        weighted(text, action("＋", () -> font(2, current)));
        var language = row(); pane.addView(language);
        var modes = new String[]{"original", "tw", "cn"};
        var names = new String[]{"原文", "繁體", "簡體"};
        var buttons = new TextView[3];
        for (int i = 0; i < modes.length; i++) {
            int index = i;
            buttons[i] = action(names[i], () -> {
                if (busy) return;
                put(book, "chinese", modes[index]); library.save();
                for (int a = 0; a < modes.length; a++) buttons[a].setText((a == index ? "✓ " : "") + names[a]);
                busy = true;
                transform(false, () -> restoreWeb(page, paragraph, offset, sample));
            });
            if (modes[i].equals(book.optString("chinese", "original"))) buttons[i].setText("✓ " + names[i]);
            weighted(language, buttons[i]);
        }
        pane.addView(action("字型與間距", this::typeOptions));
        pane.addView(action("黑白主題", () -> { put(book, "night", !book.optBoolean("night")); library.save(); relayout(); }));
        pane.addView(action("翻頁設定", this::hand));
        bottom("閱讀設定", pane);
    }

    private void typeOptions() {
        var values = new String[]{"系統字型", "襯線字型", "緊湊行距", "標準行距", "寬鬆行距"};
        new AlertDialog.Builder(this).setTitle("字型與間距").setItems(values, (d, i) -> {
            if (i < 2) put(book, "font", i == 0 ? "default" : "serif");
            else put(book, "spacing", new double[]{1.35, 1.55, 1.8}[i - 2]);
            library.save(); relayout();
        }).show();
    }

    private void hand() {
        String[] values = {"default", "left", "right"};
        new AlertDialog.Builder(this).setTitle("翻頁設定")
            .setSingleChoiceItems(new String[]{"標準", "左手", "右手"}, Arrays.asList(values).indexOf(book.optString("hand", "default")),
                (d, i) -> { put(book, "hand", values[i]); library.save(); d.dismiss(); }).show();
    }

    private void font(int delta, TextView value) {
        if (busy) return;
        int size = Math.max(14, Math.min(44, book.optInt("size", 23) + delta));
        put(book, "size", size); library.save();
        value.setText("字級 " + size); relayout();
    }

    private void bottom(String title, View pane) {
        var dialog = new AlertDialog.Builder(this).setTitle(title).setView(pane).setPositiveButton("關閉", null).create();
        dialog.show();
        var window = dialog.getWindow();
        if (window != null) { window.setGravity(Gravity.BOTTOM); window.setLayout(-1, -2); }
    }

    private void toggleVoice() {
        if (!voiceOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
            return;
        }
        voiceOn = !voiceOn; updateVoice();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MIC) {
            voiceOn = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!voiceOn) message("允許使用麥克風後才能語音翻頁");
            updateVoice();
        }
    }

    private void updateVoice() { syncVoice(); }

    private void syncVoice() {
        boolean wanted = book != null && active && voiceOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            && getSystemService(android.os.PowerManager.class).isInteractive();
        if (wanted != voiceRunning) {
            voiceRunning = wanted; voiceState = "";
            if (wanted) voice.start(); else voice.stop();
        }
        refreshStatus();
    }

    private void translate(boolean selected) {
        if (book == null || busy) return;
        int run = epoch;
        if (web != null) js(selected ? "reader.selected()" : "reader.visibleText()", value -> {
            try { languagePrompt(new JSONArray("[" + value + "]").optString(0, ""), run); }
            catch (Exception ignored) { message("無法讀取這一頁的文字"); }
        });
        else if (pages != null && !selected) {
            Pages renderer = pages; int index = page;
            io.execute(() -> {
                try { String text = renderer.text(index); runOnUiThread(() -> pdfTranslationOptions(text, run)); }
                catch (Exception e) { runOnUiThread(() -> { if (run == epoch) error(e); }); }
            });
        }
    }

    private void pdfTranslationOptions(String text, int run) {
        if (run != epoch || book == null) return;
        if (text.isBlank()) { message("找不到可翻譯的文字"); return; }
        var field = new EditText(this);
        field.setText(text);
        field.setShowSoftInputOnFocus(false);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMaxLines(10);
        field.setGravity(Gravity.TOP);
        field.setTextIsSelectable(true);
        field.setKeyListener(null);
        new AlertDialog.Builder(this).setTitle("翻譯本頁或選取文字").setView(field)
            .setNegativeButton("取消", null).setNeutralButton("翻譯選取", (dialog, which) -> {
                int start = Math.min(field.getSelectionStart(), field.getSelectionEnd());
                int end = Math.max(field.getSelectionStart(), field.getSelectionEnd());
                if (start >= 0 && end > start) languagePrompt(text.substring(start, end), run);
                else message("請先選取要翻譯的文字");
            }).setPositiveButton("翻譯本頁", (dialog, which) -> languagePrompt(text, run)).show();
    }

    private void languagePrompt(String text, int run) {
        if (run != epoch || book == null) return;
        if (text.isBlank()) { message("找不到可翻譯的文字"); return; }
        int prior = book.optString("language").equals(TranslateLanguage.JAPANESE) ? 1 : 0;
        new AlertDialog.Builder(this).setTitle("翻譯成繁體中文")
            .setSingleChoiceItems(new String[]{"英文 → 中文", "日文 → 中文"}, prior, (d, i) -> {
            d.dismiss();
            String language = i == 0 ? TranslateLanguage.ENGLISH : TranslateLanguage.JAPANESE;
            if (run != epoch || book == null) return;
            put(book, "language", language); library.save(); runTranslation(text, language);
        }).show();
    }

    private void runTranslation(String text, String language) {
        String key;
        try { key = android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(
            ("mlkit-v1\n" + language + "\n" + text).getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP); }
        catch (Exception impossible) { return; }
        String cached = translations.get(key);
        if (cached != null) { translationResult(text, cached); return; }
        var wait = progress("正在準備語言模型或翻譯…");
        var engine = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(language)
            .setTargetLanguage(TranslateLanguage.CHINESE).build());
        if (translator != null) translator.close();
        translator = engine;
        int run = epoch;
        engine.downloadModelIfNeeded(new DownloadConditions.Builder().requireWifi().build())
            .continueWithTask(task -> {
                if (!task.isSuccessful()) throw Objects.requireNonNull(task.getException());
                return engine.translate(text);
            }).addOnSuccessListener(output -> io.execute(() -> {
                try {
                    String translated = chinese.convert(output, "tw");
                    runOnUiThread(() -> {
                        wait.dismiss(); engine.close(); if (translator == engine) translator = null;
                        translations.put(key, translated);
                        while (translations.size() > 40) translations.remove(translations.keySet().iterator().next());
                        getPreferences(0).edit().putString("translations", new JSONObject(translations).toString()).apply();
                        if (run == epoch && !isDestroyed()) translationResult(text, translated);
                    });
                } catch (Exception e) { runOnUiThread(() -> { wait.dismiss(); engine.close(); if (!isDestroyed()) error(e); }); }
            })).addOnFailureListener(e -> {
                wait.dismiss(); engine.close(); if (translator == engine) translator = null;
                if (!isDestroyed()) message("翻譯失敗。首次使用請以 Wi-Fi 下載語言模型。");
            });
    }

    private void translationResult(String original, String translated) {
        var pane = column();
        var scroll = new ScrollView(this);
        var result = label(translated, 18);
        result.setTextIsSelectable(true); result.setPadding(dp(18), dp(4), dp(18), dp(12)); scroll.addView(result);
        pane.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(dp(340), getResources().getDisplayMetrics().heightPixels / 2)));
        pane.addView(action("原文／譯文", () -> result.setText(result.getText().toString().equals(translated) ? original : translated)));
        pane.addView(label("  由 Google 翻譯提供", 12));
        bottom("譯文", pane);
    }

    @Override public void onActionModeStarted(ActionMode mode) {
        super.onActionModeStarted(mode);
        if (book == null || web == null) return;
        mode.getMenu().add("翻譯").setOnMenuItemClickListener(item -> {
            translate(true);
            return true;
        });
    }

    private void closeBook() {
        snapshot(true); epoch++;
        JSONObject closing = book;
        book = null; syncVoice();
        main.removeCallbacksAndMessages(null);
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        image = null;
        ZipFile z = zip; Pages p = pages;
        if (z != null || p != null || closing != null) io.execute(() -> {
            close(z); close(p);
            if (closing != null) library.clearCachedSource(closing);
        });
        zip = null; pages = null; epub = null; busy = false; queuedTurn = 0; pendingRotate = false; linkFragment = null; pendingSearch = null;
    }

    private static void close(Closeable item) { if (item != null) try { item.close(); } catch (IOException ignored) {} }
}
