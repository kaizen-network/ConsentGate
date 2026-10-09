package io.github.consentgate.presentation;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds settings and messages from a newer bundled file to an existing one, keeping the admin's values and comments. */
public final class DefaultFiles {
    private static final Pattern KEY = Pattern.compile("( *)([A-Za-z0-9_.-]+):.*");
    // Entries under these maps are admin choices, so removed entries are not added back.
    private static final Set<List<String>> USER_MAPS = Set.of(List.of("language", "selector", "options"));

    private DefaultFiles() { }

    /** Returns the keys added to {@code target}; files other than config.yml and message files are left alone. */
    public static List<String> merge(String resource, Path target) throws IOException {
        String name = target.getFileName().toString();
        boolean properties = name.endsWith(".properties");
        if (!properties && !name.equals("config.yml")) return List.of();
        String bundled;
        try (var input = DefaultFiles.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing bundled resource: " + resource);
            bundled = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String current = Files.readString(target);
        var added = new ArrayList<String>();
        String merged = properties ? mergeProperties(bundled, current, added) : mergeYaml(bundled, current, added);
        if (!added.isEmpty()) {
            Path temporary = target.resolveSibling(name + ".tmp");
            Files.writeString(temporary, merged);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return added;
    }

    static String mergeProperties(String bundled, String current, List<String> added) throws IOException {
        var existing = new Properties();
        existing.load(new StringReader(current));
        String newline = current.contains("\r\n") ? "\r\n" : "\n";
        var result = new StringBuilder(current);
        for (String line : bundled.split("\r?\n")) {
            int separator = line.indexOf('=');
            if (line.isBlank() || line.startsWith("#") || separator < 0) continue;
            String key = line.substring(0, separator).trim();
            if (existing.containsKey(key)) continue;
            if (!result.isEmpty() && result.charAt(result.length() - 1) != '\n') result.append(newline);
            result.append(line).append(newline);
            added.add(key);
        }
        return result.toString();
    }

    static String mergeYaml(String bundled, String current, List<String> added) {
        Object loaded;
        try { loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(current); }
        catch (RuntimeException ex) { return current; } // the config loader reports the syntax error
        if (!(loaded instanceof Map<?, ?> parsed)) return current;
        String newline = current.contains("\r\n") ? "\r\n" : "\n";
        var template = List.of(bundled.split("\r?\n", -1));
        var lines = new ArrayList<>(List.of(current.split("\r?\n", -1)));
        var inserted = new ArrayList<List<String>>();
        for (Entry entry : entries(template)) {
            var path = entry.path();
            var parent = path.subList(0, path.size() - 1);
            if (inserted.stream().anyMatch(done -> startsWith(path, done))
                    || USER_MAPS.stream().anyMatch(map -> path.size() > map.size() && startsWith(path, map))
                    || !(value(parsed, parent) instanceof Map<?, ?> section) || section.containsKey(path.getLast())) continue;
            int[] place = parent.isEmpty() ? new int[] {lastContent(lines, 0) + 1, 0} : place(lines, parent);
            if (place == null) continue;
            int shift = place[1] - entry.indent();
            var block = new ArrayList<String>();
            if (parent.isEmpty()) block.add("");
            for (String line : template.subList(entry.start(), entry.end())) {
                block.add(line.isBlank() ? line : shift >= 0 ? " ".repeat(shift) + line : line.substring(Math.min(-shift, indent(line))));
            }
            lines.addAll(place[0], block);
            inserted.add(path);
            added.add(String.join(".", path));
        }
        return String.join(newline, lines);
    }

    private record Entry(List<String> path, int indent, int start, int end) { }

    /** Each key of the template with its comment lines above it and its nested lines below it. */
    private static List<Entry> entries(List<String> template) {
        var keys = new ArrayList<int[]>();
        var paths = new ArrayList<List<String>>();
        var stack = new ArrayList<String>();
        var indents = new ArrayList<Integer>();
        for (int i = 0; i < template.size(); i++) {
            Matcher key = KEY.matcher(template.get(i));
            if (!key.matches()) continue;
            int indent = key.group(1).length();
            while (!indents.isEmpty() && indents.getLast() >= indent) { indents.removeLast(); stack.removeLast(); }
            indents.add(indent);
            stack.add(key.group(2));
            int start = i;
            while (start > 0 && template.get(start - 1).stripLeading().startsWith("#")) start--;
            keys.add(new int[] {indent, start});
            paths.add(List.copyOf(stack));
        }
        var entries = new ArrayList<Entry>();
        for (int k = 0; k < keys.size(); k++) {
            int indent = keys.get(k)[0];
            int end = template.size();
            for (int next = k + 1; next < keys.size(); next++) {
                if (keys.get(next)[0] <= indent) { end = keys.get(next)[1]; break; }
            }
            while (end > keys.get(k)[1] && template.get(end - 1).isBlank()) end--;
            entries.add(new Entry(paths.get(k), indent, keys.get(k)[1], end));
        }
        return entries;
    }

    /** Where to insert a new child of {@code parent}: after its last line, at its children's indentation. */
    private static int[] place(List<String> lines, List<String> parent) {
        var stack = new ArrayList<String>();
        var indents = new ArrayList<Integer>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher key = KEY.matcher(lines.get(i));
            if (!key.matches()) continue;
            int indent = key.group(1).length();
            while (!indents.isEmpty() && indents.getLast() >= indent) { indents.removeLast(); stack.removeLast(); }
            indents.add(indent);
            stack.add(key.group(2));
            if (!stack.equals(parent)) continue;
            int last = i;
            int childIndent = -1;
            for (int j = i + 1; j < lines.size(); j++) {
                String line = lines.get(j);
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                if (indent(line) <= indent) break;
                if (childIndent < 0) childIndent = indent(line);
                last = j;
            }
            return new int[] {last + 1, childIndent < 0 ? indent + 2 : childIndent};
        }
        return null;
    }

    private static int lastContent(List<String> lines, int from) {
        int last = from - 1;
        for (int i = from; i < lines.size(); i++) if (!lines.get(i).isBlank()) last = i;
        return last;
    }

    private static Object value(Map<?, ?> root, List<String> path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(key);
        }
        return current;
    }

    private static boolean startsWith(List<String> path, List<String> prefix) {
        return path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix);
    }

    private static int indent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }
}
