// Path resolution behavior tests for README.md → ndk-import → How a run works step 1.
package cz.mzk.kramerius.plugin.ndkimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathResolverTest {
    @TempDir Path temporaryDirectory;
    private Path root;
    private Path imports;
    private PathResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(temporaryDirectory.resolve("root"));
        imports = Files.createDirectory(temporaryDirectory.resolve("imports"));
        Path work = Files.createDirectory(temporaryDirectory.resolve("work"));
        NdkImportConfiguration configuration =
                new NdkImportConfiguration(
                        root,
                        List.of(),
                        List.of(),
                        work,
                        true,
                        java.time.Duration.ofHours(24),
                        imports);
        resolver = new PathResolver(configuration);
    }

    @Test
    void relativeUnderNdkRoot() throws Exception {
        Path batch = Files.createDirectory(root.resolve("one"));
        assertEquals(batch, resolver.resolve("one", "relative", "ndk").directory());
    }

    @Test
    void relativeDefaultsToImportDirectory() throws Exception {
        Path batch = Files.createDirectory(imports.resolve("two"));
        assertEquals(batch, resolver.resolve("two", "", null).directory());
    }

    @Test
    void absolutePathMapHit() throws Exception {
        Path batch = Files.createDirectory(root.resolve("mapped"));
        NdkImportConfiguration configuration =
                new NdkImportConfiguration(
                        root,
                        List.of(new NdkImportConfiguration.PathMapping(Path.of("/legacy"), root)),
                        List.of(),
                        temporaryDirectory.resolve("work"),
                        true,
                        java.time.Duration.ofHours(24),
                        imports);
        assertEquals(
                batch,
                new PathResolver(configuration)
                        .resolve("/legacy/mapped", "absolute", null)
                        .directory());
    }

    @Test
    void absoluteWithoutMapInsideRoot() throws Exception {
        Path batch = Files.createDirectory(root.resolve("absolute"));
        assertEquals(batch, resolver.resolve(batch.toString(), "absolute", null).directory());
    }

    @Test
    void rejectsParentSegment() {
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> resolver.resolve("../outside", "relative", "ndk"))
                        .getMessage()
                        .contains("../outside"));
    }

    @Test
    void rejectsOutsidePath() throws Exception {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(outside.toString(), "absolute", null));
    }

    @Test
    void rejectsSymlinkOutsideRoot() throws Exception {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Path link = root.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable: " + e);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve("escape", "relative", "ndk"));
    }

    @Test
    void rejectsUnknownPathType() {
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> resolver.resolve("x", "other", "ndk"))
                        .getMessage()
                        .contains("other"));
    }

    @Test
    void rejectsUnknownPathRoot() {
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> resolver.resolve("x", "relative", "other"))
                        .getMessage()
                        .contains("other"));
    }

    @Test
    void rejectsMissingDirectory() {
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> resolver.resolve("missing", "relative", "ndk"))
                        .getMessage()
                        .contains("missing"));
    }

    @Test
    void missingImportDirectoryConfigurationFailsByName() {
        NdkImportConfiguration configuration =
                new NdkImportConfiguration(
                        root,
                        List.of(),
                        List.of(),
                        temporaryDirectory.resolve("work"),
                        true,
                        java.time.Duration.ofHours(24),
                        null);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new PathResolver(configuration).resolve("batch", "relative", null));

        assertTrue(exception.getMessage().contains(NdkImportConfiguration.IMPORT_DIRECTORY));
    }
}
