package com.l.ausm.impl.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java-8 backports of the Java 9+ library APIs used by the grafted AUSM sources. Celeritas' {@code forge122} module is
 * pinned to the Java 8 library surface (via {@code --release 8}), so {@code List.of}/{@code Map.of}/
 * {@code InputStream.readAllBytes}/{@code Files.writeString} etc. are unavailable and route through here instead.
 */
public final class J8 {
    private J8() {
    }

    @SafeVarargs
    public static <E> List<E> list(E... elements) {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(elements)));
    }

    @SafeVarargs
    public static <E> Set<E> set(E... elements) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(elements)));
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> map(Object... keysAndValues) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put((K) keysAndValues[i], (V) keysAndValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public static Path writeString(Path path, CharSequence content) throws IOException {
        return Files.write(path, content.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Path writeString(Path path, CharSequence content, Charset charset) throws IOException {
        return Files.write(path, content.toString().getBytes(charset));
    }
}
