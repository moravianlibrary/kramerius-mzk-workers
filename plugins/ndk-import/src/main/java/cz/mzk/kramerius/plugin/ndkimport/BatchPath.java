// Resolved batch location for README.md → ndk-import → How a run works step 1.
package cz.mzk.kramerius.plugin.ndkimport;

import java.nio.file.Path;

public record BatchPath(Path directory, String name) {}
