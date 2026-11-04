// Adapts the stock import entry point for README.md → ndk-import → How a run works step 5.
package cz.mzk.kramerius.plugin.ndkimport;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.solr.client.solrj.SolrServerException;
import org.ceskaexpedice.processplatform.api.context.PluginContextHolder;
import org.kramerius.Import;

public final class KrameriusImporter implements ImportRun.Importer {
    private final String batchName;

    public KrameriusImporter(String batchName) {
        this.batchName = batchName;
    }

    @Override
    public void importDirectory(Path directory, ImportOptions options) throws IOException {
        try {
            Import.importMain(
                    directory.toString(),
                    options.startIndexer(),
                    options.license(),
                    options.collections(),
                    options.indexationType());
        } catch (SolrServerException exception) {
            throw new IOException("Kramerius import failed for " + directory, exception);
        }
        PluginContextHolder.getContext().updateProcessName("NDK import " + batchName);
    }
}
