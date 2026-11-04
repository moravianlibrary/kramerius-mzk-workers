// Identifies an already locked batch for README.md → ndk-import → How a run works step 2.
package cz.mzk.kramerius.plugin.ndkimport;

public final class BatchLockedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BatchLockedException(String message) {
        super(message);
    }
}
