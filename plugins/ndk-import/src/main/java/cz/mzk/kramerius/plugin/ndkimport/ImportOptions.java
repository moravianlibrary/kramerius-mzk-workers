// Parsed import arguments for README.md → ndk-import → Parameters.
package cz.mzk.kramerius.plugin.ndkimport;

public record ImportOptions(
        boolean startIndexer, String license, String collections, String indexationType) {}
