// Process entry point for README.md → ndk-import → Parameters.
package cz.mzk.kramerius.plugin.ndkimport;

import cz.incad.kramerius.utils.conf.KConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import org.ceskaexpedice.processplatform.api.annotations.IsRequired;
import org.ceskaexpedice.processplatform.api.annotations.ParameterName;
import org.ceskaexpedice.processplatform.api.annotations.ProcessMethod;
import org.ceskaexpedice.processplatform.worker.config.ProcessConfiguration;

public final class NdkImportStarter {
    private NdkImportStarter() {}

    @ProcessMethod
    public static void importMain(
            @ParameterName(NdkImportParameters.INPUT_DATA_DIR) @IsRequired String inputDataDir,
            @ParameterName(NdkImportParameters.START_INDEXER) @IsRequired Boolean startIndexer,
            @ParameterName(NdkImportParameters.LICENSE) String license,
            @ParameterName(NdkImportParameters.COLLECTIONS) String collections,
            @ParameterName(NdkImportParameters.INDEXATION_TYPE) String indexationType,
            @ParameterName(NdkImportParameters.PATH_TYPE) String pathtype,
            @ParameterName(NdkImportParameters.PATH_ROOT) String pathRoot,
            @ParameterName(NdkImportParameters.EXTERNAL_STREAMS) String externalStreams,
            @ParameterName(NdkImportParameters.DRY_RUN) Boolean dryRun) {
        String effectiveIndexationType = indexationType;
        if (effectiveIndexationType == null || effectiveIndexationType.isBlank()) {
            effectiveIndexationType = KConfiguration.getInstance().getProperty("ingest.indexType");
        }
        NdkImportRequest request =
                NdkImportRequest.parse(
                        inputDataDir,
                        startIndexer,
                        license,
                        collections,
                        effectiveIndexationType,
                        pathtype,
                        pathRoot,
                        externalStreams,
                        dryRun);
        NdkImportConfiguration configuration = NdkImportConfiguration.fromKramerius();
        String processUuid = processUuidFromContext();
        try (AkubraStoredDatastreams storedDatastreams = new AkubraStoredDatastreams()) {
            new ImportRun(
                            request,
                            configuration,
                            Clock.systemUTC(),
                            new KrameriusImporter(Path.of(inputDataDir).getFileName().toString()),
                            storedDatastreams,
                            processUuid)
                    .run();
        } catch (IOException exception) {
            throw new IllegalStateException("ndk-import: run failed", exception);
        }
    }

    // PluginContext has no process-id accessor in process-api 1.5.2; the worker's own process
    // configuration carries it, as in external-refs.
    private static String processUuidFromContext() {
        String processUuid =
                ProcessConfiguration.getProcessConfig().get(ProcessConfiguration.PROCESS_ID_KEY);
        if (processUuid == null || processUuid.isBlank()) {
            throw new IllegalStateException(
                    "Process UUID is unavailable in the process configuration");
        }

        return processUuid;
    }
}
