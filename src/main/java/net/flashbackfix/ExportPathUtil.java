package net.flashbackfix;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Path helpers for the Android fix.
 *
 * <p>Follows ReplayMod's rule: a relative path is resolved against the game folder
 * ({@code ./replay_videos/} style), an absolute path is accepted as-is - including paths
 * outside the game folder. The folder is always created before it is used.
 */
public final class ExportPathUtil {

    /**
     * Default export folder, relative to the game folder - the equivalent of ReplayMod's
     * {@code ./replay_videos/}: {@code <gameDir>/flashback_videos}, a sibling of Flashback's
     * own {@code <gameDir>/flashback} folder.
     */
    public static final String DEFAULT_EXPORT_FOLDER = "flashback_videos";

    private ExportPathUtil() {}

    public static Path gameDir() {
        try {
            return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code <gameDir>/flashback_videos}, or {@code null} when the game folder is unknown. */
    public static Path defaultExportFolder() {
        Path gameDir = gameDir();
        if (gameDir == null) return null;
        try {
            return gameDir.resolve(DEFAULT_EXPORT_FOLDER).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when a server / forced setting dictates the export path. */
    public static boolean isPathForced(FlashbackConfigV1 config) {
        return config.forceDefaultExportSettings != null
                && config.forceDefaultExportSettings.defaultExportPath != null;
    }

    /**
     * The folder exports are written to.
     *
     * <p>Same rule as {@code ReplayModRender.getVideoFolder()}: resolve the configured path and
     * force-create it when it is missing. Nothing is ever relocated.
     */
    public static Path configuredFolder(FlashbackConfigV1 config) {
        Path folder = parse(config.internalExport.defaultExportPath);
        boolean seedDefault = false;

        if (folder == null) {
            folder = defaultExportFolder();
            seedDefault = !isPathForced(config);
            if (folder == null) folder = gameDir();
        }

        if (folder == null) return null;

        forceMkdir(folder);

        if (seedDefault) {
            config.internalExport.defaultExportPath = folder.toString();
        }

        return folder;
    }

    /**
     * {@code FileUtils.forceMkdir}: recursively create the folder, skip when it already exists.
     * Never relocates the path - on failure the configured folder stays as it is, matching
     * ReplayMod, which refuses to silently export somewhere else.
     */
    private static void forceMkdir(Path folder) {
        if (Files.isDirectory(folder)) return;
        try {
            Files.createDirectories(folder);
        } catch (Exception e) {
            Flashback.LOGGER.warn(
                    "[flashback-androidfix] Cannot create export folder {}", folder, e);
        }
    }

    /**
     * Resolves a configured path the way ReplayMod does: absolute paths are taken as-is,
     * relative paths (including {@code ./foo}) resolve against the game folder.
     *
     * @return the normalised absolute path, or {@code null} when blank / unparsable
     */
    private static Path parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Path path = Path.of(value.trim());
            if (!path.isAbsolute()) {
                Path gameDir = gameDir();
                if (gameDir != null) path = gameDir.resolve(path);
            }
            return path.normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Turns what the user typed into a path: absolute paths are accepted as-is (ReplayMod
     * style), relative ones resolve against the game folder.
     *
     * @return the path, or {@code null} when it cannot be parsed at all
     */
    public static Path resolvePath(String input) {
        if (input == null || input.trim().isEmpty()) return gameDir();
        return parse(input);
    }

    /** Short, readable representation of a path relative to the game folder. */
    public static String relativeDisplay(Path folder) {
        Path gameDir = gameDir();
        if (folder == null) return "";
        if (gameDir == null) return folder.toString();
        if (folder.equals(gameDir)) return ".";
        if (folder.startsWith(gameDir)) {
            return gameDir.relativize(folder).toString().replace('\\', '/');
        }
        return folder.toString();
    }

    /** Immediate sub folders of {@code folder}, sorted, including dot-prefixed ones. */
    public static List<Path> listSubdirectories(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) return List.of();

        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(folder)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName() != null)
                    .sorted()
                    .forEach(dirs::add);
        } catch (Exception e) {
            return List.of();
        }
        return dirs;
    }

    /** Creates {@code folder} if needed, writes it into the Flashback config and persists it. */
    public static void applyFolder(FlashbackConfigV1 config, Path folder) {
        if (folder == null) return;
        forceMkdir(folder);
        config.internalExport.defaultExportPath = folder.toString();
        try {
            config.saveToDefaultFolder();
        } catch (Exception ignored) {
            config.delayedSaveToDefaultFolder();
        }
    }
}
