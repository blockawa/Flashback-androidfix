import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.gradle.api.file.RegularFileProperty

plugins {
    id("dev.kikugie.loom-back-compat")
}

val modVersion = property("mod_version") as String
val mavenGroup = property("maven_group") as String
val archivesBaseName = property("archives_base_name") as String

val mcVersion = (sc.properties["mc.version"] ?: stonecutter.current.version) as String
val mcTargets = sc.properties["mc.targets"] as String
val loaderVersion = property("deps.fabric_loader") as String
val flashbackDep = property("deps.flashback") as String
val javaRelease = (property("java.release") as String).toInt()
val imguiFixEnabled = file("resources/flashbackandroidfix-imgui.mixins.json").isFile
val versionJavaDir = file("java")
val versionResourcesDir = file("resources")
val mainSourceSet = sourceSets["main"]

if (versionJavaDir.isDirectory) {
    mainSourceSet.java.srcDir(versionJavaDir)
}
if (versionResourcesDir.isDirectory) {
    mainSourceSet.resources.srcDir(versionResourcesDir)
}
if (imguiFixEnabled) {
    require(versionJavaDir.isDirectory) { "imgui mixins json present but java sources missing in $projectDir" }
}

val accessWidenerFile = versionResourcesDir.resolve("flashbackandroidfix.accesswidener")
val accessWidenerEnabled = accessWidenerFile.isFile

if (accessWidenerEnabled) {
    val loomExt = extensions.getByName("loom")
    val awPath = loomExt.javaClass.getMethod("getAccessWidenerPath").invoke(loomExt)
    (awPath as RegularFileProperty).set(accessWidenerFile)
}

version = modVersion
group = mavenGroup

base {
    archivesName.set(archivesBaseName)
}

val flashbackCacheDir = File(System.getProperty("user.home"), ".gradle/caches/flashback-androidfix/$mcVersion")
val flashbackUrlPattern = Regex("\"url\"\\s*:\\s*\"(https://cdn\\.modrinth\\.com/[^\"]+)\"")
val flashbackFilenamePattern = Regex("\"filename\"\\s*:\\s*\"([^\"]+)\"")

fun modrinthLatestFlashback(): Pair<String, String> {
    val query = "https://api.modrinth.com/v2/project/flashback/version" +
        "?game_versions=%5B%22$mcVersion%22%5D&loaders=%5B%22fabric%22%5D"
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val request = HttpRequest.newBuilder(URI.create(query))
        .header("User-Agent", "flashback-androidfix-build")
        .timeout(Duration.ofSeconds(60))
        .GET()
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "Modrinth API returned HTTP ${response.statusCode()}" }
    val body = response.body()
    val url = flashbackUrlPattern.find(body)?.groupValues?.get(1)
        ?: error("no Flashback release for Minecraft $mcVersion")
    val filename = flashbackFilenamePattern.find(body)?.groupValues?.get(1)
        ?: error("no Flashback file for Minecraft $mcVersion")
    return url to filename
}

fun downloadFlashback(url: String, target: File) {
    target.parentFile.mkdirs()
    val part = File(target.parentFile, "${target.name}.part")
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val request = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "flashback-androidfix-build")
        .timeout(Duration.ofMinutes(15))
        .GET()
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofFile(part.toPath()))
    check(response.statusCode() == 200) { "downloading $url failed with HTTP ${response.statusCode()}" }
    if (target.exists()) target.delete()
    check(part.renameTo(target)) { "cannot move $part to $target" }
}

fun resolveFlashbackJar(): File {
    try {
        val (url, filename) = modrinthLatestFlashback()
        val target = File(flashbackCacheDir, filename)
        if (!target.isFile || target.length() == 0L) {
            logger.lifecycle("Downloading Flashback for Minecraft $mcVersion: $filename")
            downloadFlashback(url, target)
        }
        return target
    } catch (e: Exception) {
        val cached = flashbackCacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jar") }
            ?.maxByOrNull { it.lastModified() }
        if (cached != null) {
            logger.warn("Modrinth unreachable for Minecraft $mcVersion (${e.message}); using cached ${cached.name}")
            return cached
        }
        throw e
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}

// FFmpeg / JavaCPP natives come straight from Maven Central instead of being committed to
// deps/, and their versions must match the javacpp bindings Flashback bundles for this
// Minecraft version: 1.21.11's Flashback ships ffmpeg 6.1.1-1.5.10 bindings, 26.x ships
// 8.1.2-1.5.14. A mismatch loads a libavcodec.so that lacks e.g. avcodec_close() and the
// export window dies with UnsatisfiedLinkError while probing encoders (see README).
val ffmpegNativesVersion = if (mcVersion.startsWith("1.")) "6.1.1-1.5.10" else "8.1.2-1.5.14"
val javacppNativesVersion = if (mcVersion.startsWith("1.")) "1.5.10" else "1.5.14"

// Gradle 9.6 弃用了 `by configurations.creating` 委托，改用 create()（名字不变，
// 下方 add(ffmpegNatives.name, ...) 依赖的仍是 "ffmpegNatives"）。
val ffmpegNatives = configurations.create("ffmpegNatives") { isTransitive = false }
val javacppNatives = configurations.create("javacppNatives") { isTransitive = false }

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    compileOnly(files(provider { resolveFlashbackJar() }))
    compileOnly(files(rootProject.file("deps/imgui-binding-1.90.0.jar")))

    add(ffmpegNatives.name, "org.bytedeco:ffmpeg:$ffmpegNativesVersion:android-arm64")
    add(javacppNatives.name, "org.bytedeco:javacpp:$javacppNativesVersion:android-arm64")
}

val imguiNativesJar = rootProject.file("deps/imgui-natives-android-arm64.jar")

tasks.processResources {
    inputs.property("version", version)
    inputs.property("mc_targets", mcTargets)
    inputs.property("fabric_loader", loaderVersion)
    inputs.property("java", javaRelease)
    inputs.property("flashback", flashbackDep)
    inputs.property("access_widener", accessWidenerEnabled)
    inputs.property("ffmpeg_natives", ffmpegNativesVersion)
    inputs.property("javacpp_natives", javacppNativesVersion)
    inputs.files(ffmpegNatives, javacppNatives, imguiNativesJar)

    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to version,
            "mc_targets" to mcTargets,
            "fabric_loader" to loaderVersion,
            "java" to javaRelease,
            "flashback" to flashbackDep,
            "imgui_mixins_extra" to (if (imguiFixEnabled) ", \"flashbackandroidfix-imgui.mixins.json\"" else ""),
            "access_widener" to (if (accessWidenerEnabled) "\"accessWidener\": \"flashbackandroidfix.accesswidener\"," else "")
        )
    }

    from({ zipTree(ffmpegNatives.singleFile) }) {
        include("lib/arm64-v8a/**")
        eachFile(Action<FileCopyDetails> { path = "natives/ffmpeg-natives/arm64-v8a/$name" })
        includeEmptyDirs = false
    }

    from({ zipTree(javacppNatives.singleFile) }) {
        include("lib/arm64-v8a/**")
        eachFile(Action<FileCopyDetails> { path = "natives/javacpp-natives/arm64-v8a/$name" })
        includeEmptyDirs = false
    }

    from(zipTree(imguiNativesJar)) {
        include("com/moulberry/imgui-natives/**")
        eachFile(Action<FileCopyDetails> { path = "natives/imgui-natives/arm64-v8a/$name" })
        includeEmptyDirs = false
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
}

java {
    val release = JavaVersion.toVersion(javaRelease)
    sourceCompatibility = release
    targetCompatibility = release
}

val mcTaggedVersion = "$modVersion-MC$mcVersion"

tasks.withType<AbstractArchiveTask>().configureEach {
    if (name in listOf("jar", "remapJar")) {
        archiveVersion.set(mcTaggedVersion)
    }
}

tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(loomx.modJar.map { it.archiveFile })
    into(rootProject.layout.buildDirectory.file("libs/$modVersion"))
    dependsOn("build")
}
