package tw.boox.ereading;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Chinese {
    private final Context context;
    private Dictionary simplified;
    private Dictionary traditional;
    private Dictionary taiwan;

    Chinese(Context context) { this.context = context; }

    synchronized String convert(String text, String mode) throws IOException {
        if (mode.equals("original")) return text;
        if (mode.equals("cn")) {
            if (simplified == null) simplified = dictionary("TSPhrases", "TSCharacters");
            return simplified.replace(text);
        }
        if (traditional == null) traditional = dictionary("STPhrases", "STCharacters");
        if (taiwan == null) taiwan = dictionary("TWPhrases", "TWVariants");
        return taiwan.replace(traditional.replace(text));
    }

    private Dictionary dictionary(String... sources) throws IOException {
        var dict = new Dictionary();
        for (String source : sources) {
            try (var reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("opencc/" + source + ".txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab < 1 || line.startsWith("#")) continue;
                    String key = line.substring(0, tab);
                    String value = line.substring(tab + 1).split(" ", 2)[0];
                    dict.values.putIfAbsent(key, value);
                    dict.max = Math.max(dict.max, key.length());
                }
            }
        }
        return dict;
    }

    private static final class Dictionary {
        final Map<String, String> values = new HashMap<>();
        int max;
        String replace(String value) {
            var out = new StringBuilder(value.length());
            for (int i = 0; i < value.length();) {
                String replacement = null;
                int len = Math.min(max, value.length() - i);
                for (; len > 0; len--) {
                    replacement = values.get(value.substring(i, i + len));
                    if (replacement != null) break;
                }
                if (replacement == null) { out.append(value.charAt(i)); i++; }
                else { out.append(replacement); i += len; }
            }
            return out.toString();
        }
    }
}
