// Per-batch exclusion lock for README.md → ndk-import → How a run works step 2.
package cz.mzk.kramerius.plugin.ndkimport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.logging.Logger;

public final class BatchLock implements AutoCloseable {
    public static final String FILE_NAME = ".ndk-import.lock";
    private static final Logger LOGGER = Logger.getLogger(BatchLock.class.getName());
    private static final String UNKNOWN_OWNER = "unknown";
    private final Path lockFile;
    private final String owner;

    private BatchLock(Path lockFile, String owner) {
        this.lockFile = lockFile;
        this.owner = owner;
    }

    public static BatchLock acquire(
            Path batchDirectory, String owner, Duration staleAfter, Clock clock)
            throws IOException {
        Path lockFile = batchDirectory.resolve(FILE_NAME);
        while (true) {
            if (tryCreate(lockFile, owner, clock)) {
                return new BatchLock(lockFile, owner);
            }

            try {
                String currentOwner = readOwner(lockFile);
                if (!isStale(lockFile, staleAfter, clock)) {
                    throw new BatchLockedException(
                            "Batch is locked: " + batchDirectory + " owner=" + currentOwner);
                }
                takeOver(lockFile, batchDirectory, currentOwner, clock);
            } catch (NoSuchFileException alreadyTakenOver) {
                continue;
            }
        }
    }

    private static boolean tryCreate(Path lockFile, String owner, Clock clock) throws IOException {
        Path temporaryFile =
                Files.createTempFile(lockFile.getParent(), ".ndk-import-lock-", ".tmp");
        try {
            String content = lockContent(owner, clock);
            Files.writeString(temporaryFile, content, StandardCharsets.UTF_8);
            try {
                Files.createLink(lockFile, temporaryFile);
                return true;
            } catch (FileAlreadyExistsException alreadyExists) {
                return false;
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static String lockContent(String owner, Clock clock) {
        return owner + "\n" + DateTimeFormatter.ISO_INSTANT.format(clock.instant()) + "\n";
    }

    private static String readOwner(Path lockFile) throws IOException {
        String contents = Files.readString(lockFile, StandardCharsets.UTF_8);
        String[] lines = contents.split("\\R", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            return UNKNOWN_OWNER;
        }

        return lines[0];
    }

    private static boolean isStale(Path lockFile, Duration staleAfter, Clock clock)
            throws IOException {
        Instant timestamp = readTimestamp(lockFile);
        if (timestamp == null) {
            timestamp = Files.getLastModifiedTime(lockFile).toInstant();
        }

        return !clock.instant().isBefore(timestamp.plus(staleAfter));
    }

    private static Instant readTimestamp(Path lockFile) throws IOException {
        String[] lines = Files.readString(lockFile, StandardCharsets.UTF_8).split("\\R", -1);
        if (lines.length < 2) {
            return null;
        }
        try {
            return Instant.parse(lines[1]);
        } catch (DateTimeParseException malformedTimestamp) {
            // A lock written by something else: isStale falls back to the file's mtime.
            return null;
        }
    }

    // Moving the stale lock aside is atomic, so two runs cannot both delete it. One race remains:
    // run A takes over and publishes a fresh lock between run B's isStale check and B's move, and
    // B then moves A's fresh lock aside. It needs two runs of one batch within that instant after
    // a lock older than ndk.import.lockStaleHours, and the import itself skips existing objects.
    private static void takeOver(Path lockFile, Path batchDirectory, String oldOwner, Clock clock)
            throws IOException {
        Path staleFile =
                lockFile.resolveSibling(
                        FILE_NAME + ".stale-" + oldOwner + "-" + clock.instant().toEpochMilli());
        try {
            Files.move(lockFile, staleFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException alreadyTakenOver) {
            return;
        }
        LOGGER.warning("Taking over stale batch lock for " + batchDirectory + " owner=" + oldOwner);
        Files.deleteIfExists(staleFile);
    }

    @Override
    public void close() throws IOException {
        if (!Files.exists(lockFile)) {
            return;
        }
        String currentOwner = readOwner(lockFile);
        if (owner.equals(currentOwner)) {
            Files.deleteIfExists(lockFile);
        }
    }
}
