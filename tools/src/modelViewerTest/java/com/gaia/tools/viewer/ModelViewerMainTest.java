package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.tools.model.GaiaGlbValidator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelViewerMainTest {
    @Test
    void requiresExactlyOneExplicitGlbWithoutOpeningWindow() {
        AtomicInteger launches = new AtomicInteger();
        Captured captured = new Captured();

        int none = ModelViewerMain.run(new String[0], captured.out(), captured.err(),
                ignored -> { throw new AssertionError(); }, (path, result, cpu) -> launches.incrementAndGet());
        int two = ModelViewerMain.run(new String[]{"a.glb", "b.glb"}, captured.out(), captured.err(),
                ignored -> { throw new AssertionError(); }, (path, result, cpu) -> launches.incrementAndGet());

        assertEquals(2, none);
        assertEquals(2, two);
        assertEquals(0, launches.get());
        assertTrue(captured.stderr().contains("exactly one"));
    }

    @Test
    void validationFailurePrintsBoundedReportAndNeverCreatesViewer() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        Captured captured = new Captured();
        GaiaGlbValidator.Result invalid = ViewerFixtures.invalidScaleResult();

        int exit = ModelViewerMain.run(new String[]{"rejected.glb"}, captured.out(), captured.err(),
                ignored -> invalid, (path, result, cpu) -> launches.incrementAndGet());

        assertEquals(1, exit);
        assertEquals(0, launches.get());
        assertTrue(captured.stdout().contains("FAIL"));
        assertFalse(captured.stderr().contains("Exception"));
    }

    @Test
    void validSnapshotIsPackedBeforeLauncherReceivesIt() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        Captured captured = new Captured();
        GaiaGlbValidator.Result valid = ViewerFixtures.result(ViewerFixtures.triangle(false, false, 0));

        int exit = ModelViewerMain.run(new String[]{"tool.glb"}, captured.out(), captured.err(),
                ignored -> valid, (path, result, cpu) -> {
                    launches.incrementAndGet();
                    assertEquals(valid.sourceSha256(), cpu.sourceSha256());
                    assertEquals(Path.of("tool.glb"), path);
                });

        assertEquals(0, exit);
        assertEquals(1, launches.get());
        assertTrue(captured.stdout().contains(valid.sourceSha256()));
    }

    @Test
    void readFailureIsStableAndDoesNotEchoHostPathOrStackTrace() {
        Captured captured = new Captured();
        int exit = ModelViewerMain.run(new String[]{"C:\\private\\secret.glb"},
                captured.out(), captured.err(), ignored -> { throw new IOException("C:\\private\\secret.glb"); },
                (path, result, cpu) -> { throw new AssertionError(); });

        assertEquals(1, exit);
        assertEquals("Input could not be read." + System.lineSeparator(), captured.stderr());
    }

    @Test
    void malformedLocalPathIsRejectedWithoutValidatorWindowOrStackTrace() {
        AtomicInteger validations = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        Captured captured = new Captured();

        int exit = ModelViewerMain.run(new String[]{"bad\u0000path.glb"},
                captured.out(), captured.err(), ignored -> {
                    validations.incrementAndGet();
                    throw new AssertionError();
                }, (path, result, cpu) -> launches.incrementAndGet());

        assertEquals(1, exit);
        assertEquals(0, validations.get());
        assertEquals(0, launches.get());
        assertEquals("Model Inspector could not complete safely." + System.lineSeparator(),
                captured.stderr());
        assertFalse(captured.stderr().contains("bad"));
        assertFalse(captured.stderr().contains("Exception"));
    }

    @Test
    void gpuUnrepresentableValidatedSnapshotDoesNotOpenWindow() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        Captured captured = new Captured();
        GaiaGlbValidator.Result far = ViewerFixtures.result(
                ViewerFixtures.triangle(false, false, 1.0e100));

        int exit = ModelViewerMain.run(new String[]{"far.glb"}, captured.out(), captured.err(),
                ignored -> far, (path, result, cpu) -> launches.incrementAndGet());

        assertEquals(1, exit);
        assertEquals(0, launches.get());
        assertTrue(captured.stdout().contains(far.sourceSha256()));
        assertEquals("Model Inspector could not complete safely." + System.lineSeparator(),
                captured.stderr());
    }

    private static final class Captured {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out() { return new PrintStream(out); }
        PrintStream err() { return new PrintStream(err); }
        String stdout() { return out.toString(java.nio.charset.StandardCharsets.UTF_8); }
        String stderr() { return err.toString(java.nio.charset.StandardCharsets.UTF_8); }
    }
}
