package com.dimensionalminer.core.dimensionalMinerCore.util;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** File helpers for cloning and removing world folders. Blocking I/O: call off the main thread. */
public final class WorldFiles {

    /** Files that tie a world folder to one running server and must not be cloned. */
    private static final Set<String> SKIP = Set.of("session.lock", "uid.dat", "metadata.dat");

    private WorldFiles() {}

    /** Copies a world folder, skipping lock/identity files. Throws on the first failure. */
    public static void copyWorld(Path src, Path dest) throws IOException {
        try (Stream<Path> paths = Files.walk(src)) { // closed, or Windows keeps the directories locked
            paths.forEach(source -> {
                Path name = source.getFileName();
                if (name != null && SKIP.contains(name.toString().toLowerCase())) return;
                Path destination = dest.resolve(src.relativize(source));
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        removeLocks(dest.toFile());
    }

    /** Deletes a folder tree. Throws (after trying everything) if anything could not be removed. */
    public static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        List<Path> all;
        try (Stream<Path> paths = Files.walk(path)) {
            all = paths.sorted(Comparator.reverseOrder()).toList();
        }
        IOException first = null;
        for (Path p : all) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }

    private static void removeLocks(File dir) {
        for (String rel : new String[]{"uid.dat", "session.lock", "data/paper/metadata.dat"}) {
            File f = new File(dir, rel);
            if (f.exists() && !f.delete()) {
                f.deleteOnExit();
            }
        }
    }
}
