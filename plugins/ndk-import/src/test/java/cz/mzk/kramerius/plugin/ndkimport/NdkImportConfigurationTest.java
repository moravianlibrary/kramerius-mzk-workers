// Configuration behavior tests for README.md → ndk-import → Configuration.
package cz.mzk.kramerius.plugin.ndkimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NdkImportConfigurationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void parsesDefaults() {
        NdkImportConfiguration configuration = NdkImportConfiguration.from(properties()::get);
        assertEquals(
                java.util.List.of("ALTO", "TEXT_OCR"), configuration.internalizedDatastreams());
        assertTrue(configuration.isKeepingWorkDirectoryOnFailure());
        assertEquals(Duration.ofHours(24), configuration.lockStaleAfter());
        assertTrue(configuration.pathMap().isEmpty());
    }

    @Test
    void requiresRoot() {
        Map<String, String> values = validProperties();
        values.remove(NdkImportConfiguration.ROOT);
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> NdkImportConfiguration.from(values::get))
                        .getMessage()
                        .contains(NdkImportConfiguration.ROOT));
    }

    @Test
    void requiresWorkDirectory() {
        Map<String, String> values = validProperties();
        values.remove(NdkImportConfiguration.WORK_DIR);
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> NdkImportConfiguration.from(values::get))
                        .getMessage()
                        .contains(NdkImportConfiguration.WORK_DIR));
    }

    @Test
    void rejectsMalformedPathMap() {
        Map<String, String> values = validProperties();
        values.put(NdkImportConfiguration.PATH_MAP, "/source");
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> NdkImportConfiguration.from(values::get))
                        .getMessage()
                        .contains("/source"));
    }

    @Test
    void rejectsWorkDirectoryInsideRoot() {
        Map<String, String> values = validProperties();
        values.put(
                NdkImportConfiguration.WORK_DIR,
                Path.of(values.get(NdkImportConfiguration.ROOT)).resolve("work").toString());
        assertThrows(
                IllegalArgumentException.class, () -> NdkImportConfiguration.from(values::get));
    }

    @Test
    void trimsInternalizedDatastreamEntries() {
        Map<String, String> values = validProperties();
        values.put(NdkImportConfiguration.INTERNALIZE, "ALTO, TEXT_OCR");

        assertEquals(
                java.util.List.of("ALTO", "TEXT_OCR"),
                NdkImportConfiguration.from(values::get).internalizedDatastreams());
    }

    @Test
    void rejectsEmptyInternalizedDatastreamEntry() {
        Map<String, String> values = validProperties();
        values.put(NdkImportConfiguration.INTERNALIZE, "ALTO,,TEXT_OCR");

        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> NdkImportConfiguration.from(values::get))
                        .getMessage()
                        .contains(NdkImportConfiguration.INTERNALIZE));
    }

    @Test
    void rejectsInvalidLockStaleHoursWithKeyAndValue() {
        Map<String, String> values = validProperties();
        values.put(NdkImportConfiguration.LOCK_STALE_HOURS, "zero");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NdkImportConfiguration.from(values::get));

        assertTrue(exception.getMessage().contains(NdkImportConfiguration.LOCK_STALE_HOURS));
        assertTrue(exception.getMessage().contains("zero"));
    }

    @Test
    void rejectsLockStaleHoursBelowOne() {
        Map<String, String> values = validProperties();
        values.put(NdkImportConfiguration.LOCK_STALE_HOURS, "0");

        assertThrows(
                IllegalArgumentException.class, () -> NdkImportConfiguration.from(values::get));
    }

    @Test
    void rejectsInvalidKeepWorkDirectoryValueWithKeyAndValue() {
        Map<String, String> values = validProperties();
        values.put(NdkImportConfiguration.KEEP_WORK_DIR_ON_FAILURE, "yes");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NdkImportConfiguration.from(values::get));

        assertTrue(
                exception.getMessage().contains(NdkImportConfiguration.KEEP_WORK_DIR_ON_FAILURE));
        assertTrue(exception.getMessage().contains("yes"));
    }

    private Map<String, String> properties() {
        return validProperties();
    }

    private Map<String, String> validProperties() {
        Map<String, String> values = new HashMap<>();
        values.put(NdkImportConfiguration.ROOT, temporaryDirectory.resolve("batch").toString());
        values.put(NdkImportConfiguration.WORK_DIR, temporaryDirectory.resolve("work").toString());
        values.put(
                NdkImportConfiguration.IMPORT_DIRECTORY,
                temporaryDirectory.resolve("imports").toString());
        return values;
    }
}
