// Orchestrates README.md → ndk-import → How a run works steps 1–7 without Kramerius dependencies.
package cz.mzk.kramerius.plugin.ndkimport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ImportRun {
    private static final Logger LOGGER = Logger.getLogger(ImportRun.class.getName());
    private final NdkImportRequest request;
    private final NdkImportConfiguration configuration;
    private final Clock clock;
    private final Importer importer;
    private final StoredDatastreams storedDatastreams;
    private final String processUuid;

    public ImportRun(
            NdkImportRequest request,
            NdkImportConfiguration configuration,
            Clock clock,
            Importer importer,
            StoredDatastreams storedDatastreams,
            String processUuid) {
        this.request = request;
        this.configuration = configuration;
        this.clock = clock;
        this.importer = importer;
        this.storedDatastreams = storedDatastreams;
        this.processUuid = processUuid;
    }

    @SuppressWarnings("try")
    public void run() throws IOException {
        BatchPath batch =
                new PathResolver(configuration)
                        .resolve(request.inputDataDir(), request.pathType(), request.pathRoot());
        try (BatchLock ignored =
                BatchLock.acquire(
                        batch.directory(), processUuid, configuration.lockStaleAfter(), clock)) {
            runLocked(batch);
        }
    }

    private void runLocked(BatchPath batch) throws IOException {
        if (request.externalStreams() == NdkImportRequest.ExternalStreams.KEEP) {
            log("resolved=" + batch.directory() + " externalStreams=keep");
            importer.importDirectory(batch.directory(), request.importOptions());
            return;
        }
        FoxmlInliner inliner = new FoxmlInliner();
        FoxmlInliner.InlinePlan plan = inliner.plan(batch.directory(), configuration);
        log(
                "resolved="
                        + batch.directory()
                        + " objects="
                        + plan.objects().size()
                        + " internalize="
                        + plan.entries().size()
                        + " warnings="
                        + plan.warnings().size());
        for (String warning : plan.warnings()) {
            log("warning=" + warning);
        }
        if (request.dryRun()) {
            return;
        }
        if (request.externalStreams() == NdkImportRequest.ExternalStreams.CHECK) {
            verify(plan, batch.directory());
            return;
        }

        Path workDirectory = configuration.workDirectory().resolve(processUuid);
        boolean successful = false;
        try {
            inliner.write(plan, workDirectory);
            importer.importDirectory(workDirectory, request.importOptions());
            verify(plan, batch.directory());
            successful = true;
        } finally {
            if (successful || !configuration.isKeepingWorkDirectoryOnFailure()) {
                deleteWorkDirectory(workDirectory);
            }
        }
    }

    private void verify(FoxmlInliner.InlinePlan plan, Path batchDirectory) throws IOException {
        List<String> failures = new ArrayList<>();
        for (FoxmlInliner.InlineEntry entry : plan.entries()) {
            StoredDatastream metadata = storedDatastreams.get(entry.pid(), entry.dsid());
            String location = metadata == null ? null : metadata.location();
            if (metadata == null
                    || !"M".equals(metadata.controlGroup())
                    || location == null
                    || location.startsWith("file:")
                    || isUnderImportRoot(location, batchDirectory)) {
                failures.add(entry.pid() + "/" + entry.dsid());
            }
        }
        if (!failures.isEmpty()) {
            throw new IOException("Datastream verification failed: " + String.join(", ", failures));
        }
        for (FoxmlInliner.InlineEntry entry : plan.entries()) {
            log("verified pid=" + entry.pid() + " dsid=" + entry.dsid());
        }
    }

    // Akubra reports an internal datastream by an id ("uuid:...+ALTO+ALTO.0"), not a path; only an
    // absolute path can point into an import root, so a relative value is never read as one.
    private boolean isUnderImportRoot(String location, Path batchDirectory) throws IOException {
        Path path = Path.of(location);
        if (!path.isAbsolute()) {
            return false;
        }

        Path normalized = path.normalize();
        Path checked = Files.exists(normalized) ? normalized.toRealPath() : normalized;
        return checked.startsWith(configuration.root().toAbsolutePath().normalize())
                || (configuration.importDirectory() != null
                        && checked.startsWith(
                                configuration.importDirectory().toAbsolutePath().normalize()))
                || checked.startsWith(batchDirectory.toAbsolutePath().normalize());
    }

    private static void deleteWorkDirectory(Path workDirectory) {
        try {
            if (!Files.exists(workDirectory)) {
                return;
            }
            try (var paths = Files.walk(workDirectory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException exception) {
            LOGGER.log(
                    Level.WARNING,
                    "ndk-import: could not delete work directory " + workDirectory,
                    exception);
        }
    }

    private static void log(String message) {
        LOGGER.info("ndk-import: " + message);
    }

    public interface Importer {
        void importDirectory(Path directory, ImportOptions options) throws IOException;
    }

    public interface StoredDatastreams {
        StoredDatastream get(String pid, String dsid);
    }

    public record StoredDatastream(String controlGroup, String location) {}
}
