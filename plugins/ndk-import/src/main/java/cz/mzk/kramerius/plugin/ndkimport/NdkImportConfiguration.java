// Configuration for the path and lock steps in README.md → ndk-import → How a run works.
package cz.mzk.kramerius.plugin.ndkimport;

import cz.incad.kramerius.utils.conf.KConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public record NdkImportConfiguration(
        Path root,
        List<PathMapping> pathMap,
        List<String> internalizedDatastreams,
        Path workDirectory,
        boolean isKeepingWorkDirectoryOnFailure,
        Duration lockStaleAfter,
        Path importDirectory) {

    public static final String ROOT = "ndk.import.root";
    public static final String PATH_MAP = "ndk.import.pathMap";
    public static final String INTERNALIZE = "ndk.import.internalize";
    public static final String WORK_DIR = "ndk.import.workDir";
    public static final String KEEP_WORK_DIR_ON_FAILURE = "ndk.import.keepWorkDirOnFailure";
    public static final String LOCK_STALE_HOURS = "ndk.import.lockStaleHours";
    public static final String IMPORT_DIRECTORY = "import.directory";

    public NdkImportConfiguration {
        pathMap = List.copyOf(pathMap);
        internalizedDatastreams = List.copyOf(internalizedDatastreams);
    }

    public Path mapPath(Path input) {
        for (PathMapping mapping : pathMap) {
            if (!input.startsWith(mapping.from())) {
                continue;
            }
            return mapping.to().resolve(mapping.from().relativize(input));
        }
        return input;
    }

    public static NdkImportConfiguration fromKramerius() {
        return from(KConfiguration.getInstance()::getProperty);
    }

    public static NdkImportConfiguration from(Function<String, String> property) {
        Path root = requiredPath(property, ROOT);
        Path workDirectory = requiredPath(property, WORK_DIR);
        ensureWorkDirectoryOutsideRoot(root, workDirectory);
        List<PathMapping> pathMap = parsePathMap(property);
        List<String> internalized = parseInternalized(property);
        boolean keepWorkDirectory = parseKeepWorkDirectory(property);
        Duration staleAfter = parseLockStaleAfter(property);
        Path importDirectory = optionalPath(property, IMPORT_DIRECTORY);

        return new NdkImportConfiguration(
                root,
                pathMap,
                internalized,
                workDirectory,
                keepWorkDirectory,
                staleAfter,
                importDirectory);
    }

    private static void ensureWorkDirectoryOutsideRoot(Path root, Path workDirectory) {
        if (!workDirectory
                .toAbsolutePath()
                .normalize()
                .startsWith(root.toAbsolutePath().normalize())) {
            return;
        }

        throw new IllegalArgumentException(WORK_DIR + " is inside " + ROOT);
    }

    private static List<PathMapping> parsePathMap(Function<String, String> property) {
        String mappings = value(property, PATH_MAP, "");
        if (mappings.isBlank()) {
            return List.of();
        }

        List<PathMapping> pathMap = new ArrayList<>();
        for (String entry : mappings.split(";", -1)) {
            pathMap.add(parsePathMapping(entry));
        }
        return List.copyOf(pathMap);
    }

    private static PathMapping parsePathMapping(String entry) {
        String[] sides = entry.split("=>", -1);
        if (sides.length != 2 || sides[0].isBlank() || sides[1].isBlank()) {
            throw new IllegalArgumentException("Malformed " + PATH_MAP + " entry: " + entry);
        }

        return new PathMapping(Path.of(sides[0].trim()), Path.of(sides[1].trim()));
    }

    private static List<String> parseInternalized(Function<String, String> property) {
        String setting = value(property, INTERNALIZE, "ALTO,TEXT_OCR");
        List<String> datastreams = new ArrayList<>();
        for (String entry : setting.split(",", -1)) {
            String datastream = entry.trim();
            if (datastream.isEmpty()) {
                throw new IllegalArgumentException(
                        "Empty entry in " + INTERNALIZE + ": " + setting);
            }
            datastreams.add(datastream);
        }

        return List.copyOf(datastreams);
    }

    private static boolean parseKeepWorkDirectory(Function<String, String> property) {
        String setting = value(property, KEEP_WORK_DIR_ON_FAILURE, "true");
        if (setting.equalsIgnoreCase("true")) {
            return true;
        }
        if (setting.equalsIgnoreCase("false")) {
            return false;
        }

        throw new IllegalArgumentException(
                KEEP_WORK_DIR_ON_FAILURE + " has invalid value: " + setting);
    }

    private static Duration parseLockStaleAfter(Function<String, String> property) {
        String setting = value(property, LOCK_STALE_HOURS, "24");
        long hours = parseHours(setting);
        if (hours < 1) {
            throw new IllegalArgumentException(LOCK_STALE_HOURS + " has invalid value: " + setting);
        }

        return Duration.ofHours(hours);
    }

    private static long parseHours(String setting) {
        try {
            return Long.parseLong(setting);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    LOCK_STALE_HOURS + " has invalid value: " + setting, exception);
        }
    }

    private static Path optionalPath(Function<String, String> property, String key) {
        String setting = property.apply(key);
        if (setting == null || setting.isBlank()) {
            return null;
        }
        return Path.of(setting.trim());
    }

    private static Path requiredPath(Function<String, String> property, String key) {
        String setting = property.apply(key);
        if (setting == null || setting.isBlank()) {
            throw new IllegalArgumentException("Missing required configuration: " + key);
        }
        return Path.of(setting.trim());
    }

    private static String value(Function<String, String> property, String key, String fallback) {
        String setting = property.apply(key);
        return setting == null || setting.isBlank() ? fallback : setting.trim();
    }

    public record PathMapping(Path from, Path to) {}
}
