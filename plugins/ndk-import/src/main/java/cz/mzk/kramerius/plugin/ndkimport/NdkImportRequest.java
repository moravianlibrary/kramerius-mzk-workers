// Typed process parameters for README.md → ndk-import → Parameters.
package cz.mzk.kramerius.plugin.ndkimport;

public record NdkImportRequest(
        String inputDataDir,
        boolean startIndexer,
        String license,
        String collections,
        String indexationType,
        String pathType,
        String pathRoot,
        ExternalStreams externalStreams,
        boolean dryRun) {

    public static NdkImportRequest parse(
            String inputDataDir,
            Boolean startIndexer,
            String license,
            String collections,
            String indexationType,
            String pathType,
            String pathRoot,
            String externalStreams,
            Boolean dryRun) {
        if (startIndexer == null) {
            throw new IllegalArgumentException("Missing required parameter: startIndexer");
        }
        return new NdkImportRequest(
                inputDataDir,
                startIndexer,
                license,
                collections,
                indexationType,
                pathType,
                pathRoot,
                ExternalStreams.parse(externalStreams),
                Boolean.TRUE.equals(dryRun));
    }

    public ImportOptions importOptions() {
        return new ImportOptions(startIndexer, license, collections, indexationType);
    }

    public enum ExternalStreams {
        INTERNALIZE,
        KEEP,
        CHECK;

        private static ExternalStreams parse(String value) {
            String setting = value == null || value.isBlank() ? "internalize" : value.trim();
            return switch (setting) {
                case "internalize" -> INTERNALIZE;
                case "keep" -> KEEP;
                case "check" -> CHECK;
                default -> throw new IllegalArgumentException("Invalid externalStreams: " + value);
            };
        }
    }
}
