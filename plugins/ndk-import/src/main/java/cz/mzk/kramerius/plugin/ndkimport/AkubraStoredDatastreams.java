// Owns a metadata-only Akubra repository for README.md → ndk-import → How a run works step 6.
package cz.mzk.kramerius.plugin.ndkimport;

import com.google.inject.Guice;
import com.google.inject.Injector;
import cz.incad.kramerius.fedora.RepoModule;
import org.ceskaexpedice.akubra.AkubraRepository;
import org.ceskaexpedice.akubra.DatastreamMetadata;

public final class AkubraStoredDatastreams implements ImportRun.StoredDatastreams, AutoCloseable {
    private AkubraRepository repository;

    public AkubraStoredDatastreams() {}

    @Override
    public ImportRun.StoredDatastream get(String pid, String dsid) {
        DatastreamMetadata metadata = repository().getDatastreamMetadata(pid, dsid);
        if (metadata == null) {
            return null;
        }
        return new ImportRun.StoredDatastream(metadata.getControlGroup(), metadata.getLocation());
    }

    @Override
    public void close() {
        if (repository != null) {
            repository.shutdown();
        }
    }

    private AkubraRepository repository() {
        if (repository != null) {
            return repository;
        }
        Injector injector = Guice.createInjector(new RepoModule());
        repository = injector.getInstance(AkubraRepository.class);
        return repository;
    }
}
