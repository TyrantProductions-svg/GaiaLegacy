package com.gaia.tools.ui;

import com.overlord.assets.AssetManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class UiAssetGenerator {
    private UiAssetGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "expected font PNG/JSON and icon PNG/JSON output paths");
        }
        run(
                Path.of("").toAbsolutePath(),
                Path.of(args[0]), Path.of(args[1]),
                Path.of(args[2]), Path.of(args[3]),
                new AssetManager(UiAssetGenerator.class.getClassLoader()));
    }

    static void run(Path root, Path imageRelative, Path metadataRelative) throws IOException {
        List<Path> targets = resolveTargets(root, List.of(imageRelative, metadataRelative));
        BitmapFontGenerator.GeneratedFont generated =
                new BitmapFontGenerator().generate(GlyphSource.projectGlyphs());
        Files.write(targets.get(0), generated.png());
        Files.write(targets.get(1), generated.json());
    }

    static void run(
            Path root,
            Path fontImageRelative,
            Path fontMetadataRelative,
            Path iconImageRelative,
            Path iconMetadataRelative,
            AssetManager assetManager) throws IOException {
        Objects.requireNonNull(assetManager, "assetManager");
        List<Path> targets = resolveTargets(root, List.of(
                fontImageRelative, fontMetadataRelative,
                iconImageRelative, iconMetadataRelative));
        BitmapFontGenerator.GeneratedFont font =
                new BitmapFontGenerator().generate(GlyphSource.projectGlyphs());
        BlockIconGenerator.GeneratedIcons icons =
                new BlockIconGenerator().generate(assetManager);
        Files.write(targets.get(0), font.png());
        Files.write(targets.get(1), font.json());
        Files.write(targets.get(2), icons.png());
        Files.write(targets.get(3), icons.json());
    }

    private static List<Path> resolveTargets(Path root, List<Path> relativePaths)
            throws IOException {
        Objects.requireNonNull(root, "root");
        for (int index = 0; index < relativePaths.size(); index++) {
            validateRelative(relativePaths.get(index), "output path " + index);
        }

        Path absoluteRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(absoluteRoot);
        Path realRoot = absoluteRoot.toRealPath();
        List<Path> targets = new ArrayList<>();
        for (Path relative : relativePaths) {
            Path target = resolveContainedTarget(realRoot, relative);
            for (Path existing : targets) {
                if (target.equals(existing)
                        || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                                && Files.exists(existing, LinkOption.NOFOLLOW_LINKS)
                                && Files.isSameFile(target, existing))) {
                    throw new IllegalArgumentException("all output paths must differ");
                }
            }
            targets.add(target);
        }
        return List.copyOf(targets);
    }

    private static void validateRelative(Path relative, String name) {
        Objects.requireNonNull(relative, name);
        if (relative.isAbsolute()
                || relative.toString().isEmpty()
                || !relative.equals(relative.normalize())) {
            throw new IllegalArgumentException(name + " must be a normalized relative path");
        }
    }

    private static Path resolveContainedTarget(Path realRoot, Path relative) throws IOException {
        Path realParent = realRoot;
        Path relativeParent = relative.getParent();
        if (relativeParent != null) {
            for (Path part : relativeParent) {
                Path candidate = realParent.resolve(part.toString());
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    Path resolved = candidate.toRealPath();
                    if (!resolved.startsWith(realRoot) || !Files.isDirectory(resolved)) {
                        throw new IllegalArgumentException("output parent escapes the root");
                    }
                    realParent = resolved;
                } else {
                    Files.createDirectory(candidate);
                    realParent = candidate.toRealPath();
                }
            }
        }
        Path target = realParent.resolve(relative.getFileName().toString());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Path resolved = target.toRealPath();
            if (!resolved.startsWith(realRoot)) {
                throw new IllegalArgumentException("output target escapes the root");
            }
            return resolved;
        }
        return target;
    }
}
