package com.gaia.tools.model;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Explicit single local file adapter. No browsing, reference resolution or GUI. */
public final class ModelInspectorMain {
    private ModelInspectorMain() { }
    public static void main(String[] args) {System.exit(run(args,System.out,System.err));}
    public static int run(String[] args,PrintStream out,PrintStream err) {
        boolean json=args.length==2 && "--json".equals(args[0]);
        if(!(args.length==1 || json)) {err.print("Usage: modelInspector [--json] <local.glb>\n");return 2;}
        String file=args[json?1:0];
        if(file.isBlank() || file.startsWith("--") || file.startsWith("\\\\") || file.startsWith("//")) {err.print("Expected one local file\n");return 2;}
        try {
            Path path=Path.of(file);
            if(!Files.isRegularFile(path)) {err.print("Input is not a readable regular file\n");return 2;}
            try(var input=Files.newInputStream(path)) {
                var result=GaiaGlbValidator.validate(input);
                out.print(json?ValidationReportWriter.json(result)+"\n":ValidationReportWriter.text(result));
                return result.report().outcome()==ValidationReport.Outcome.FAIL?1:0;
            }
        }catch(IOException|InvalidPathException|SecurityException io) {err.print("Unable to read the explicit input file\n");return 2;}
    }
}
