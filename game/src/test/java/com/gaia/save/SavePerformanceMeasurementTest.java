package com.gaia.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.archive.SaveArchiveLimits;
import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveArchiveWriter;
import com.gaia.save.codec.EncodedSaveGame;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.session.GameSessionPersistenceTestFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavePerformanceMeasurementTest {
    @TempDir Path tempDir;

    @Test
    void measuresRepresentativeFiniteWorldWithoutUsingTimeAsCiAssertion()
            throws Exception {
        long captureStarted = System.nanoTime();
        SaveGameSnapshot captured = Gate14BCanonicalFixture
                .representativeLiveCapture()
                .capture()
                .snapshot()
                .orElseThrow();
        long captureFinished = System.nanoTime();

        SaveSnapshotCodec codecs = Gate14BCanonicalFixture.codecs();
        EncodedSaveGame encoded = codecs.encode(
                captured, Gate14BCanonicalFixture.MODIFIED);
        long encodeFinished = System.nanoTime();

        Path archive = tempDir.resolve("representative.glsave");
        new SaveArchiveWriter().write(archive, encoded);
        long writeFinished = System.nanoTime();

        SaveArchiveReadResult read = new SaveArchiveReader(codecs).read(archive);
        long readFinished = System.nanoTime();
        assertEquals(SaveArchiveReadResult.Status.VALID, read.status());
        SaveGameSnapshot decoded = read.snapshot().orElseThrow();
        assertEquals(captured, decoded);

        SaveGameSnapshot recaptured;
        long restoreFinished;
        try (var restored = GameSessionPersistenceTestFixture
                .restoreActualProductionSession(decoded)) {
            restored.driveToReady();
            restoreFinished = System.nanoTime();
            recaptured = restored.captureAndMarkSaved();
        }

        long archiveBytes = Files.size(archive);
        assertEquals(81, captured.chunks().chunks().size());
        assertTrue(archiveBytes <= SaveArchiveLimits.MAX_ARCHIVE_FILE_BYTES);
        assertEquals(captured, recaptured);

        System.out.printf(
                "PHASE14_SAVE_PERF archiveBytes=%d captureMs=%.3f encodeMs=%.3f "
                        + "writeMs=%.3f readDecodeMs=%.3f restoreReadyMs=%.3f%n",
                archiveBytes,
                millis(captureStarted, captureFinished),
                millis(captureFinished, encodeFinished),
                millis(encodeFinished, writeFinished),
                millis(writeFinished, readFinished),
                millis(readFinished, restoreFinished));
    }

    private static double millis(long started, long finished) {
        return (finished - started) / 1_000_000.0d;
    }
}
