plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.3" /* [SC] DO NOT EDIT */

tasks.register("buildAllAndCollect") {
    group = "build"
    description = "Builds every Minecraft version and collects the jars into build/libs/"
    stonecutter.versions.forEach { ver ->
        dependsOn(":${ver.version}:buildAndCollect")
    }
}

stonecutter parameters {
    replacements {
        string(current.parsed < "26.3") {
            replace(
                "com/moulberry/flashback/utils/AsyncFileDialogs",
                "com/moulberry/flashback/exporting/AsyncFileDialogs"
            )
        }
    }
}

stonecutter handlers {
    inherit("java", "frag", "vert")
}
