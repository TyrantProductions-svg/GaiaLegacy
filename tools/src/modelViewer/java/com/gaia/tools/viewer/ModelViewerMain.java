package com.gaia.tools.viewer;

import com.gaia.tools.model.GaiaGlbValidator;
import com.gaia.tools.model.ValidationReport;
import com.gaia.tools.model.ValidationReportWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Explicit-file CLI. Gate B validation and CPU packing both complete before GLFW exists. */
public final class ModelViewerMain {
    @FunctionalInterface interface Validator { GaiaGlbValidator.Result validate(Path path) throws IOException; }
    @FunctionalInterface interface Launcher {
        void launch(Path path, GaiaGlbValidator.Result result, ViewerCpuModel cpu) throws IOException;
    }

    private ModelViewerMain() { }

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err, ModelViewerMain::validate,
                ViewerApplication::launch);
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] args, PrintStream out, PrintStream err,
            Validator validator, Launcher launcher) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(launcher, "launcher");
        if (args.length != 1) {
            err.println("Gaia Model Inspector requires exactly one explicit .glb path.");
            return 2;
        }
        try {
            Path path = Path.of(args[0]);
            GaiaGlbValidator.Result result = validator.validate(path);
            out.print(ValidationReportWriter.text(result));
            if (result.report().outcome() == ValidationReport.Outcome.FAIL
                    || result.snapshot().isEmpty()) {
                return 1;
            }
            ViewerCpuModel cpu = ViewerPresentation.prepare(
                    result.snapshot().orElseThrow(), 1280, 720);
            launcher.launch(path, result, cpu);
            return 0;
        } catch (IOException rejected) {
            err.println("Input could not be read.");
            return 1;
        } catch (RuntimeException rejected) {
            err.println("Model Inspector could not complete safely.");
            return 1;
        }
    }

    private static GaiaGlbValidator.Result validate(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return GaiaGlbValidator.validate(input);
        }
    }
}
