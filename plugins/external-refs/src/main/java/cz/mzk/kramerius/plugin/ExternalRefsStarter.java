package cz.mzk.kramerius.plugin;

import cz.incad.kramerius.utils.conf.KConfiguration;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.ceskaexpedice.processplatform.api.annotations.ParameterName;
import org.ceskaexpedice.processplatform.api.annotations.ProcessMethod;
import org.ceskaexpedice.processplatform.common.model.ProcessState;
import org.ceskaexpedice.processplatform.worker.client.ManagerClientFactory;
import org.ceskaexpedice.processplatform.worker.config.ProcessConfiguration;
import org.ceskaexpedice.processplatform.worker.config.WorkerConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ExternalRefsStarter {

    private static final String CONFIG_SOLR_HOST = "externalRefs.solrHost";
    private static final String DEFAULT_IGNORED_DATASTREAMS = "POLICY";

    @ProcessMethod
    public static void externalRefsMain(
            // String, because the worker cannot instantiate an omitted Integer parameter
            @ParameterName("threads") String threads,
            @ParameterName("ignoreDs") String ignoreDs,
            @ParameterName("full") Boolean full,
            @ParameterName("paths") String paths,
            @ParameterName("since") String since
    ) throws IOException, SolrServerException, InterruptedException {
        if (Boolean.TRUE.equals(full) && since != null) {
            throw new IllegalArgumentException("Parameters 'full' and 'since' cannot be combined");
        }
        Instant modifiedSince = since != null ? Instant.parse(since.trim()) : null;
        List<String> pathPrefixes = parsePaths(paths);

        String solrHost = KConfiguration.getInstance().getConfiguration().getString(CONFIG_SOLR_HOST);
        if (solrHost == null) {
            throw new IllegalStateException("Missing configuration key '" + CONFIG_SOLR_HOST + "'");
        }
        int collectionIndex = solrHost.lastIndexOf('/');
        String solrBaseUrl = solrHost.substring(0, collectionIndex);
        String collection = solrHost.substring(collectionIndex + 1);

        Path objectStoreRoot;
        if (KConfiguration.getInstance().getConfiguration().getBoolean("legacyfs")) {
            objectStoreRoot = Paths.get(KConfiguration.getInstance().getProperty("object_store_base"));
        } else {
            objectStoreRoot = Paths.get(KConfiguration.getInstance().getProperty("objectStore.path"));
        }

        int readers = threads != null ? Integer.parseInt(threads.trim()) : Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        Set<String> ignored = Arrays.stream((ignoreDs != null ? ignoreDs : DEFAULT_IGNORED_DATASTREAMS).split(","))
                .map(String::trim)
                .filter(ds -> !ds.isEmpty())
                .collect(Collectors.toSet());

        long corrupted;
        try (HttpSolrClient solrClient = new HttpSolrClient.Builder(solrBaseUrl).build()) {
            corrupted = new ExternalRefsHarvester(solrClient, collection, ignored)
                    .harvest(objectStoreRoot, readers, Boolean.TRUE.equals(full), modifiedSince, pathPrefixes);
        }
        if (corrupted > 0) {
            finishWithWarning();
        }
    }

    /**
     * A blank value is rejected, because treating it as no limit would start a run over the whole store.
     */
    static List<String> parsePaths(String paths) {
        if (paths == null) {
            return List.of();
        }
        List<String> prefixes = Arrays.stream(paths.split(","))
                .map(String::trim)
                .map(path -> path.replaceFirst("^/+", ""))
                .filter(path -> !path.isEmpty())
                .toList();
        if (prefixes.isEmpty()) {
            throw new IllegalArgumentException("Parameter 'paths' has no path prefix: '" + paths + "'");
        }
        return prefixes;
    }

    /**
     * Throwing WarningException does not work: PluginStarter calls this class through Method.invoke,
     * which wraps it in InvocationTargetException, so its catch block misses it and the process ends as FAILED.
     * Reports WARNING directly and exits, because PluginStarter would set FINISHED after a normal return.
     */
    private static void finishWithWarning() {
        WorkerConfiguration workerConfig = WorkerConfiguration.decodeWorkerConfig();
        ProcessConfiguration processConfig = ProcessConfiguration.getProcessConfig();
        ManagerClientFactory.createManagerClient(workerConfig)
                .updateProcessState(processConfig.get(ProcessConfiguration.PROCESS_ID_KEY), ProcessState.WARNING);
        System.out.flush();
        System.err.flush();
        System.exit(0);
    }
}
