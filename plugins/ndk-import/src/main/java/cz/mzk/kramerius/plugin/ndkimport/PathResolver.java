// Resolves and confines input batch paths as required by README.md → ndk-import → How a run works step 1.
package cz.mzk.kramerius.plugin.ndkimport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PathResolver {
    private final NdkImportConfiguration configuration;

    public PathResolver(NdkImportConfiguration configuration) {
        this.configuration = configuration;
    }

    public BatchPath resolve(String inputDataDir, String pathtype, String pathRoot)
            throws IOException {
        Path input = parseInput(inputDataDir);
        Path resolved = resolvePath(input, pathtype, pathRoot);
        Path confined = confine(resolved, inputDataDir);

        return new BatchPath(confined, confined.getFileName().toString());
    }

    private Path parseInput(String inputDataDir) {
        if (inputDataDir == null || inputDataDir.isBlank()) {
            throw new IllegalArgumentException("Invalid inputDataDir: " + inputDataDir);
        }
        Path input = Path.of(inputDataDir);
        for (Path segment : input) {
            if (segment.toString().equals("..")) {
                throw new IllegalArgumentException("inputDataDir contains '..': " + inputDataDir);
            }
        }

        return input;
    }

    private Path resolvePath(Path input, String pathtype, String pathRoot) {
        String type = pathtype == null || pathtype.isBlank() ? "relative" : pathtype;
        if (type.equals("relative")) {
            return baseFor(pathRoot).resolve(input);
        }
        if (type.equals("absolute")) {
            return applyPathMap(input);
        }

        throw new IllegalArgumentException("Unknown pathtype: " + pathtype);
    }

    private Path baseFor(String pathRoot) {
        if (pathRoot == null || pathRoot.isBlank()) {
            if (configuration.importDirectory() == null) {
                throw new IllegalArgumentException(
                        "Missing required configuration: "
                                + NdkImportConfiguration.IMPORT_DIRECTORY);
            }
            return configuration.importDirectory();
        }
        if (pathRoot.equals("ndk")) {
            return configuration.root();
        }

        throw new IllegalArgumentException("Unknown pathRoot: " + pathRoot);
    }

    private Path applyPathMap(Path input) {
        return configuration.mapPath(input);
    }

    private Path confine(Path resolved, String inputDataDir) throws IOException {
        Path normalized = resolved.toAbsolutePath().normalize();
        Path checked = Files.exists(normalized) ? normalized.toRealPath() : normalized;
        if (!isUnder(checked, configuration.root())
                && !isUnder(checked, configuration.importDirectory())) {
            throw new IllegalArgumentException(
                    "inputDataDir resolves outside configured roots: " + inputDataDir);
        }
        if (!Files.isDirectory(checked)) {
            throw new IllegalArgumentException(
                    "Batch directory does not exist or is not a directory: " + normalized);
        }

        return checked;
    }

    private static boolean isUnder(Path candidate, Path root) throws IOException {
        if (root == null) {
            return false;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path checkedRoot =
                Files.exists(normalizedRoot) ? normalizedRoot.toRealPath() : normalizedRoot;

        return candidate.startsWith(checkedRoot);
    }
}
