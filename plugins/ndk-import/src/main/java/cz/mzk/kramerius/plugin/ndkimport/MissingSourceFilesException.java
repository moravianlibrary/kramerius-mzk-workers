// Identifies the complete set of missing FOXML sources from README.md → ndk-import → How a run works step 3.
package cz.mzk.kramerius.plugin.ndkimport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@SuppressWarnings("serial")
public final class MissingSourceFilesException extends IOException {
    private final List<Path> missingFiles;

    public MissingSourceFilesException(List<Path> missingFiles) {
        super(
                "Missing or unreadable inline files: "
                        + String.join(", ", missingFiles.stream().map(Path::toString).toList()));
        this.missingFiles = List.copyOf(missingFiles);
    }

    public List<Path> missingFiles() {
        return missingFiles;
    }
}
