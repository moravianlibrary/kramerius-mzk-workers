// Exercises the one-run lifecycle from README.md → ndk-import → How a run works with real fixture files.
package cz.mzk.kramerius.plugin.ndkimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportRunTest {
    private static final Path FIXTURE =
            Path.of("src/test/resources/fixtures/ndk-batch/215af974-9656-11f1-8afd-bed0c7abae6b");
    @TempDir Path temporaryDirectory;

    @Test
    void importsRewrittenWorkAndCleansItUp() throws Exception {
        Path batch = copyFixture();
        FakeImporter importer = new FakeImporter();
        run(batch, importer, stored(), NdkImportRequest.ExternalStreams.INTERNALIZE, false).run();
        assertEquals(temporaryDirectory.resolve("work/process-1"), importer.directory);
        assertTrue(importer.hasRewrittenFoxml);
        assertFalse(Files.exists(importer.directory));
        assertEquals(4, fileCount(batch));
        assertFalse(Files.exists(batch.resolve(BatchLock.FILE_NAME)));
    }

    @Test
    void verificationFailureKeepsWorkAndReleasesLock() throws Exception {
        Path batch = copyFixture();
        Map<String, ImportRun.StoredDatastream> metadata = stored();
        metadata.put(
                "uuid:2de03d33-90a5-11f1-8749-1261b7a65813/ALTO",
                new ImportRun.StoredDatastream("M", "file:/somewhere/alto"));
        FakeImporter importer = new FakeImporter();
        IOException failure =
                assertThrows(
                        IOException.class,
                        () ->
                                run(
                                                batch,
                                                importer,
                                                metadata,
                                                NdkImportRequest.ExternalStreams.INTERNALIZE,
                                                false)
                                        .run());
        assertTrue(failure.getMessage().contains("uuid:2de03d33-90a5-11f1-8749-1261b7a65813/ALTO"));
        assertTrue(Files.exists(temporaryDirectory.resolve("work/process-1")));
        assertEquals(4, fileCount(batch));
        assertFalse(Files.exists(batch.resolve(BatchLock.FILE_NAME)));
    }

    @Test
    void absoluteLocationUnderImportRootFailsVerification() throws Exception {
        Path batch = copyFixture();
        Map<String, ImportRun.StoredDatastream> metadata = stored();
        metadata.put(
                "uuid:2de03d33-90a5-11f1-8749-1261b7a65813/TEXT_OCR",
                new ImportRun.StoredDatastream("M", batch.resolve("txt.txt").toString()));

        ImportRun run =
                run(
                        batch,
                        new FakeImporter(),
                        metadata,
                        NdkImportRequest.ExternalStreams.INTERNALIZE,
                        false);

        IOException failure = assertThrows(IOException.class, run::run);
        assertTrue(failure.getMessage().contains("TEXT_OCR")); // still points into the batch
    }

    @Test
    void importerFailureKeepsWorkAndReleasesLock() throws Exception {
        Path batch = copyFixture();
        FakeImporter importer = new FakeImporter();
        importer.failure = new IOException("import failed");
        assertThrows(
                IOException.class,
                () ->
                        run(
                                        batch,
                                        importer,
                                        stored(),
                                        NdkImportRequest.ExternalStreams.INTERNALIZE,
                                        false)
                                .run());
        assertTrue(Files.exists(temporaryDirectory.resolve("work/process-1")));
        assertEquals(4, fileCount(batch));
        assertFalse(Files.exists(batch.resolve(BatchLock.FILE_NAME)));
    }

    @Test
    void dryRunDoesNotImportOrCreateWorkDirectory() throws Exception {
        Path batch = copyFixture();
        FakeImporter importer = new FakeImporter();
        run(batch, importer, stored(), NdkImportRequest.ExternalStreams.INTERNALIZE, true).run();
        assertEquals(null, importer.directory);
        assertFalse(Files.exists(temporaryDirectory.resolve("work/process-1")));
        assertFalse(Files.exists(batch.resolve(BatchLock.FILE_NAME)));
    }

    @Test
    void keepImportsTheBatchDirectory() throws Exception {
        Path batch = copyFixture();
        FakeImporter importer = new FakeImporter();
        run(batch, importer, stored(), NdkImportRequest.ExternalStreams.KEEP, false).run();
        assertEquals(batch, importer.directory);
        assertFalse(Files.exists(batch.resolve(BatchLock.FILE_NAME)));
    }

    @Test
    void checkVerifiesWithoutImporting() throws Exception {
        Path batch = copyFixture();
        FakeImporter importer = new FakeImporter();
        run(batch, importer, stored(), NdkImportRequest.ExternalStreams.CHECK, false).run();
        assertEquals(null, importer.directory);
        assertFalse(Files.exists(batch.resolve(BatchLock.FILE_NAME)));
    }

    @Test
    @SuppressWarnings("try")
    void heldLockFailsBeforePlanning() throws Exception {
        Path batch = copyFixture();
        try (BatchLock lock =
                BatchLock.acquire(
                        batch, "other-process", Duration.ofHours(24), Clock.systemUTC())) {
            FakeImporter importer = new FakeImporter();
            assertThrows(
                    BatchLockedException.class,
                    () ->
                            run(
                                            batch,
                                            importer,
                                            stored(),
                                            NdkImportRequest.ExternalStreams.INTERNALIZE,
                                            false)
                                    .run());
            assertEquals(null, importer.directory);
        }
    }

    private ImportRun run(
            Path batch,
            FakeImporter importer,
            Map<String, ImportRun.StoredDatastream> stored,
            NdkImportRequest.ExternalStreams streams,
            boolean dryRun) {
        NdkImportConfiguration configuration =
                new NdkImportConfiguration(
                        batch.getParent(),
                        List.of(
                                new NdkImportConfiguration.PathMapping(
                                        Path.of("/kramerius_ndk/import/"), batch.getParent())),
                        List.of("ALTO", "TEXT_OCR"),
                        temporaryDirectory.resolve("work"),
                        true,
                        Duration.ofHours(24),
                        batch.getParent());
        NdkImportRequest request =
                new NdkImportRequest(
                        batch.getFileName().toString(),
                        true,
                        null,
                        null,
                        "indexRoots",
                        "relative",
                        "ndk",
                        streams,
                        dryRun);
        return new ImportRun(
                request,
                configuration,
                Clock.systemUTC(),
                importer,
                (pid, dsid) -> stored.get(pid + "/" + dsid),
                "process-1");
    }

    private Map<String, ImportRun.StoredDatastream> stored() {
        Map<String, ImportRun.StoredDatastream> stored = new HashMap<>();
        stored.put(
                "uuid:2de03d33-90a5-11f1-8749-1261b7a65813/ALTO",
                new ImportRun.StoredDatastream(
                        "M", "uuid:2de03d33-90a5-11f1-8749-1261b7a65813+ALTO+ALTO.0"));
        stored.put(
                "uuid:2de03d33-90a5-11f1-8749-1261b7a65813/TEXT_OCR",
                new ImportRun.StoredDatastream(
                        "M", "uuid:2de03d33-90a5-11f1-8749-1261b7a65813+TEXT_OCR+TEXT_OCR.0"));
        return stored;
    }

    private Path copyFixture() throws IOException {
        Path batch = temporaryDirectory.resolve("import").resolve(FIXTURE.getFileName());
        Files.createDirectories(batch);
        try (var files = Files.list(FIXTURE)) {
            for (Path file : files.toList()) {
                Files.copy(file, batch.resolve(file.getFileName()));
            }
        }
        return batch;
    }

    private static long fileCount(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    private static final class FakeImporter implements ImportRun.Importer {
        private Path directory;
        private IOException failure;
        private boolean hasRewrittenFoxml;

        @Override
        public void importDirectory(Path importedDirectory, ImportOptions options)
                throws IOException {
            directory = importedDirectory;
            hasRewrittenFoxml =
                    Files.exists(
                            importedDirectory.resolve("2de03d33-90a5-11f1-8749-1261b7a65813.xml"));
            if (failure != null) {
                throw failure;
            }
        }
    }
}
