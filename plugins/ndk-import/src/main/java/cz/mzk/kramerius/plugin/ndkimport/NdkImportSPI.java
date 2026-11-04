// Process plugin SPI for the profiles described in README.md → ndk-import.
package cz.mzk.kramerius.plugin.ndkimport;

import java.util.Set;
import org.ceskaexpedice.processplatform.api.AbstractPluginSpi;

public class NdkImportSPI extends AbstractPluginSpi {
    @Override
    public String getMainClass() {
        return NdkImportStarter.class.getName();
    }

    @Override
    public Set<String> getScheduledProfiles() {
        return Set.of("new_indexer_index_object", "new_indexer_index_model");
    }
}
