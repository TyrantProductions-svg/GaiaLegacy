package com.gaia.tools.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Bounded deterministic diagnostics. PASS is not artistic or runtime approval. */
public record ValidationReport(Outcome outcome, List<Diagnostic> diagnostics, boolean truncated) {
    public enum Outcome { PASS, PASS_WITH_WARNINGS, FAIL }
    public enum Severity { ERROR, WARNING }
    public record Diagnostic(Severity severity, String code, String path, String message) {
        public Diagnostic {
            Objects.requireNonNull(severity);
            code = bound(code, 64);
            path = bound(path, HandToolProfile.MAX_DIAGNOSTIC_PATH);
            message = bound(message, HandToolProfile.MAX_DIAGNOSTIC_MESSAGE);
        }
        private static String bound(String value, int limit) {
            Objects.requireNonNull(value);
            return value.length() <= limit ? value : value.substring(0, limit);
        }
    }
    public ValidationReport {
        Objects.requireNonNull(outcome);
        diagnostics = List.copyOf(diagnostics);
        if (diagnostics.size() > HandToolProfile.MAX_DIAGNOSTICS) {
            throw new IllegalArgumentException("Diagnostic capacity exceeded");
        }
    }

    static final class Collector {
        private record Identity(Severity severity,String code,String path,String message) {
            Diagnostic display() {return new Diagnostic(severity,code,path,message);}
        }
        private final TreeSet<Identity> entries = new TreeSet<>(Comparator
                .comparing(Identity::severity).thenComparing(Identity::path)
                .thenComparing(Identity::code).thenComparing(Identity::message));
        private boolean error, warning, truncated;

        void error(String code, String path, String message) { add(Severity.ERROR, code, path, message); }
        void warning(String code, String path, String message) { add(Severity.WARNING, code, path, message); }

        private void add(Severity severity, String code, String path, String message) {
            Objects.requireNonNull(code);Objects.requireNonNull(path);Objects.requireNonNull(message);
            if(code.length()>HandToolProfile.MAX_DIAGNOSTIC_IDENTITY
                    || path.length()>HandToolProfile.MAX_DIAGNOSTIC_IDENTITY
                    || message.length()>HandToolProfile.MAX_DIAGNOSTIC_IDENTITY) {
                // Not reachable from admitted names/current fixed paths. Fail closed if
                // a future internal producer violates this boundary; retain no long value.
                truncated=true;severity=Severity.ERROR;code="DIAGNOSTIC_IDENTITY_LIMIT";path="/";message="Diagnostic identity exceeded admitted bounds";
            }
            error |= severity == Severity.ERROR;
            warning |= severity == Severity.WARNING;
            truncated |= code.length()>64 || path.length()>HandToolProfile.MAX_DIAGNOSTIC_PATH
                    || message.length()>HandToolProfile.MAX_DIAGNOSTIC_MESSAGE;
            entries.add(new Identity(severity, code, path, message));
            if (entries.size() > HandToolProfile.MAX_DIAGNOSTICS) {
                entries.pollLast();
                truncated = true;
            }
        }

        ValidationReport report() {
            return new ValidationReport(error ? Outcome.FAIL : warning
                    ? Outcome.PASS_WITH_WARNINGS : Outcome.PASS, entries.stream().map(Identity::display).toList(), truncated);
        }
    }
}
