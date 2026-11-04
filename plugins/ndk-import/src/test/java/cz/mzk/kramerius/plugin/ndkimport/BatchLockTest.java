// Batch lock behavior tests for README.md → ndk-import → How a run works step 2.
package cz.mzk.kramerius.plugin.ndkimport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchLockTest {
    @TempDir Path temporaryDirectory;
    private static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void acquireAndCloseRemovesFile() throws Exception {
        BatchLock lock = BatchLock.acquire(temporaryDirectory, "owner", STALE_AFTER, CLOCK);
        assertTrue(Files.exists(temporaryDirectory.resolve(BatchLock.FILE_NAME)));
        lock.close();
        assertFalse(Files.exists(temporaryDirectory.resolve(BatchLock.FILE_NAME)));
    }

    @Test
    void secondAcquireNamesCurrentOwner() throws Exception {
        BatchLock.acquire(temporaryDirectory, "first", STALE_AFTER, CLOCK);
        BatchLockedException exception =
                assertThrows(
                        BatchLockedException.class,
                        () -> BatchLock.acquire(temporaryDirectory, "second", STALE_AFTER, CLOCK));
        assertTrue(exception.getMessage().contains("first"));
    }

    @Test
    void staleLockIsTakenOver() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve(BatchLock.FILE_NAME), "old\n2026-11-02T12:00:00Z\n");
        BatchLock lock = BatchLock.acquire(temporaryDirectory, "new", STALE_AFTER, CLOCK);
        assertTrue(
                Files.readString(temporaryDirectory.resolve(BatchLock.FILE_NAME))
                        .startsWith("new\n"));
        lock.close();
    }

    @Test
    void closeLeavesLockOwnedBySomeoneElse() throws Exception {
        BatchLock lock = BatchLock.acquire(temporaryDirectory, "mine", STALE_AFTER, CLOCK);
        Files.writeString(
                temporaryDirectory.resolve(BatchLock.FILE_NAME), "theirs\n2026-11-04T12:00:00Z\n");
        lock.close();
        assertTrue(
                Files.readString(temporaryDirectory.resolve(BatchLock.FILE_NAME))
                        .startsWith("theirs\n"));
    }

    @Test
    void corruptLockCanBeTakenOverWhenItsFileIsStale() throws Exception {
        Path lockFile = temporaryDirectory.resolve(BatchLock.FILE_NAME);
        Files.writeString(lockFile, "corrupt");
        Files.setLastModifiedTime(
                lockFile,
                java.nio.file.attribute.FileTime.from(Instant.parse("2026-11-02T12:00:00Z")));

        BatchLock lock = BatchLock.acquire(temporaryDirectory, "new", STALE_AFTER, CLOCK);

        assertTrue(Files.readString(lockFile).startsWith("new\n"));
        lock.close();
    }

    @Test
    void lockedBatchThrowsSpecificException() throws Exception {
        BatchLock.acquire(temporaryDirectory, "first", STALE_AFTER, CLOCK);

        BatchLockedException exception =
                assertThrows(
                        BatchLockedException.class,
                        () -> BatchLock.acquire(temporaryDirectory, "second", STALE_AFTER, CLOCK));

        assertTrue(exception.getMessage().contains(temporaryDirectory.toString()));
        assertTrue(exception.getMessage().contains("first"));
    }
}
