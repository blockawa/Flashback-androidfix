package net.flashbackfix;

import com.moulberry.flashback.Flashback;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android native libraries shipped inside this mod:
 *
 * <ul>
 *   <li>{@code natives/ffmpeg-natives/<arch>/} - handed to JavaCPP from {@code LoaderMixin}</li>
 *   <li>{@code natives/javacpp-natives/<arch>/} - JavaCPP's own JNI bridge ({@code libjnijavacpp.so})
 *       that registers {@code org.bytedeco.javacpp.Pointer}'s natives, which every FFmpeg
 *       {@code Pointer} subclass needs. Upstream only ships it for desktop, so without this copy
 *       the FFmpeg load chain falls back to {@code System.loadLibrary} and finds nothing.</li>
 *   <li>{@code natives/imgui-natives/<arch>/} - pointed at by the {@code imgui.library.path}
 *       system property from {@link #installImgui()}</li>
 * </ul>
 *
 * <p>Extraction to disk follows the pattern DistantHorizonsZSTD uses: read the resource out of
 * the jar, drop it into a writable directory the linker accepts (app cache on Android,
 * game directory elsewhere) and give the loader a plain file path.
 */
public final class AndroidNatives {

    /** Matches {@code imgui.moulberry90.ImGui}'s own default. */
    private static final String IMGUI_LIBRARY_NAME = "imgui-moulberry90-java64";

    private static final String FFMPEG_PREFIX = "/natives/ffmpeg-natives/";
    private static final String JAVACPP_PREFIX = "/natives/javacpp-natives/";
    private static final String IMGUI_PREFIX = "/natives/imgui-natives/";

    /**
     * Version segment inside the on-disk extract folder, e.g.
     * {@code ffmpeg-natives/6.1.1-1.5.10/arm64-v8a/libjniavcodec.so}, baked in by
     * {@code processResources} through {@code flashbackandroidfix-natives.properties}.
     *
     * <p>Every Minecraft version running this mod extracts into the same root, and library file
     * names are identical across versions while their contents differ (FFmpeg 6 vs 8, JavaCPP
     * 1.5.10 vs 1.5.14). A leftover file from the other version then gets loaded by the wrong
     * bindings and the export window dies with {@code UnsatisfiedLinkError} probing
     * {@code avcodec_close} - an API FFmpeg 8 removed. Versioned folders make that overlap
     * impossible rather than guarding against it after the fact.
     */
    private static final String FFMPEG_VERSION = nativesVersion("ffmpeg");
    private static final String JAVACPP_VERSION = nativesVersion("javacpp");

    private AndroidNatives() {}

    /**
     * Folder inside this mod jar for the current CPU, or {@code null} when unsupported.
     * Names are Android ABIs ({@code arm64-v8a}, ...), the same convention
     * DistantHorizonsZSTD uses for its {@code natives/<abi>/} layout.
     */
    public static String archDirectory() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return switch (arch) {
            case "aarch64", "arm64" -> "arm64-v8a";
            case "x86_64", "amd64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "arm", "armv7", "armv7l" -> "armeabi-v7a";
            default -> null;
        };
    }

    /**
     * Resolves one native library from this mod.
     *
     * <p>FFmpeg libraries are tried first, then JavaCPP's own {@code libjnijavacpp.so}, which the
     * load chain asks for before anything else because {@code org.bytedeco.javacpp.Pointer} declares
     * its natives there.
     *
     * @param suffix raw JavaCPP library name, decorations included ({@code avcodec@.62}, ...)
     * @return a single {@code file:} URL, or {@code null} when this mod carries no such library
     *         and the original lookup should be kept
     */
    public static URL[] resolve(String suffix) {
        String name = libraryName(suffix);
        if (name == null || name.isEmpty()) return null;

        String arch = archDirectory();
        if (arch == null) return null;

        String fileName = fileName(name);
        URL[] hit = resolveFrom(FFMPEG_PREFIX, "ffmpeg-natives/" + FFMPEG_VERSION, arch, fileName);
        if (hit != null) return hit;
        return resolveFrom(JAVACPP_PREFIX, "javacpp-natives/" + JAVACPP_VERSION, arch, fileName);
    }

    private static URL[] resolveFrom(String prefix, String folder, String arch, String fileName) {
        String resource = prefix + arch + "/" + fileName;
        URL source = AndroidNatives.class.getResource(resource);
        if (source == null) return null;

        Path root = extractRoot();
        if (root == null) return null;
        return extract(source, resource, root.resolve(folder).resolve(arch).resolve(fileName));
    }

    /**
     * Reads one version key from {@code flashbackandroidfix-natives.properties}, which
     * {@code processResources} fills in at build time. Should the file ever go missing the
     * folder degrades to a shared {@code unversioned} segment - both Minecraft versions would
     * land in it again (behaving exactly like the pre-isolation layout), but a warning in the
     * log makes that visible instead of silent.
     */
    private static String nativesVersion(String key) {
        String value = null;
        try (InputStream in = AndroidNatives.class.getResourceAsStream("/flashbackandroidfix-natives.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                value = props.getProperty(key);
            }
        } catch (Exception e) {
            Flashback.LOGGER.warn("[flashback-androidfix] Cannot read flashbackandroidfix-natives.properties", e);
        }
        if (value == null || value.trim().isEmpty()) {
            Flashback.LOGGER.warn("[flashback-androidfix] No '{}' version in flashbackandroidfix-natives.properties; "
                    + "extracting into the shared unversioned folder", key);
            return "unversioned";
        }
        return value.trim();
    }

    /**
     * Extracts the imgui library and points {@code imgui.library.path} at its folder, which is the
     * first thing {@code ImGui}'s static initializer looks at. If extraction fails, that property
     * stays unset and ImGui falls through to {@code tryLoadFromClasspath()}, i.e. the copy already
     * bundled in the Flashback jar - a working fallback rather than a crash.
     */
    public static void installImgui() {
        String arch = archDirectory();
        if (arch == null) return;

        String fileName = imguiLibraryName();
        String resource = IMGUI_PREFIX + arch + "/" + fileName;
        URL source = AndroidNatives.class.getResource(resource);
        if (source == null) return;

        Path dir = extractRoot().resolve("imgui-natives").resolve(arch);
        if (extract(source, resource, dir.resolve(fileName)) == null) return;

        System.setProperty("imgui.library.path", dir.toAbsolutePath().toString());
    }

    /**
     * Where natives are unpacked.
     *
     * <p>On Android the linker namespace only lets this JVM {@code dlopen()} from
     * app-private storage ({@code /data/user/<uid>/<pkg>/...}) and refuses shared
     * storage like {@code /storage/emulated/0} - where the game directory often
     * lives. So on Android we unpack into the app cache directory, detected the
     * same way DistantHorizonsZSTD's {@code AndroidLibLoader} does; elsewhere the
     * game directory stays the target.
     */
    private static Path extractRoot() {
        Path base = androidAppCacheDir();
        if (base == null) {
            base = ExportPathUtil.gameDir();
            if (base == null) return null;
        }
        return base.resolve(".flashback-androidfix").resolve("natives");
    }

    /**
     * {@code /data/user/<uid>/<pkg>/cache} when this JVM runs inside an Android
     * app, {@code null} otherwise. Both probes mirror
     * {@code AndroidLibLoader.init()}: scan {@code java.library.path} for an
     * app-private entry, fall back to a {@code java.io.tmpdir} already there.
     */
    private static Path androidAppCacheDir() {
        String libraryPath = System.getProperty("java.library.path", "");
        for (String entry : libraryPath.split(File.pathSeparator)) {
            Matcher m = Pattern.compile("^/data/user/(\\d+)/([^/]+)/").matcher(entry);
            if (m.find()) {
                return Path.of("/data/user/" + m.group(1) + "/" + m.group(2) + "/cache");
            }
        }
        String tmpdir = System.getProperty("java.io.tmpdir", "");
        Matcher m = Pattern.compile("^/data/user/(\\d+)/[^/]+/").matcher(tmpdir);
        if (m.find()) return Path.of(tmpdir);
        return null;
    }

    private static URL[] extract(URL source, String resource, Path target) {
        try {
            long length = source.openConnection().getContentLengthLong();
            if (!Files.isRegularFile(target) || Files.size(target) != length) {
                Files.createDirectories(target.getParent());
                try (InputStream in = source.openStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new URL[]{target.toUri().toURL()};
        } catch (Exception e) {
            Flashback.LOGGER.warn("[flashback-androidfix] Cannot extract {}", resource, e);
            return null;
        }
    }

    /** Mirrors {@code ImGui.resolveFullLibName()}: only {@code os.name} decides the affixes. */
    private static String imguiLibraryName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean win = os.contains("win");
        boolean mac = os.contains("mac");
        return (win ? "" : "lib") + IMGUI_LIBRARY_NAME + (win ? ".dll" : mac ? ".dylib" : ".so");
    }

    private static String fileName(String name) {
        if (name.startsWith("lib") || name.endsWith(".so")) return name;
        return "lib" + name + ".so";
    }

    /**
     * Strips the decorations JavaCPP accepts in {@code preload}/{@code link} entries - the same
     * rules {@code Loader.findLibrary()} applies before it looks anything up.
     */
    private static String libraryName(String suffix) {
        if (suffix == null) return null;

        String s = suffix.trim();
        if (s.startsWith(":")) s = s.substring(1);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        if (s.endsWith("!")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("#") && !s.endsWith("##")) return null;

        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);

        return s.trim();
    }
}
