plugins {
    java
}

val modFolderName = "WeaponTooltipPreview"
val starsectorDir = file(providers.gradleProperty("starsectorDir").orNull
    ?: error("Set starsectorDir in source/gradle.properties (copy gradle.properties.example)."))
// macOS keeps the game jars inside the app bundle; Windows and Linux in starsector-core.
val gameJars = listOf("Contents/Resources/Java", "starsector-core").map(starsectorDir::resolve).firstOrNull { it.isDirectory }
    ?: error("No game jars in $starsectorDir: expected Contents/Resources/Java (macOS) or starsector-core (Windows/Linux).")
val modsDir = starsectorDir.resolve("mods")
/** The ready-to-install mod, next to this source folder; committed so the repository doubles as a download. */
val modOutputDir = rootDir.resolve("../$modFolderName")

dependencies {
    // Game API, game core and runtime libraries. Each platform's core is obfuscated separately, so code may only name
    // the core's unobfuscated classes and members; everything else goes through the bridge.
    compileOnly(files(
        gameJars.resolve("starfarer.api.jar"),
        gameJars.resolve("starfarer_obf.jar"),
        gameJars.resolve("fs.common_obf.jar"),
        gameJars.resolve("lwjgl.jar"),
        gameJars.resolve("lwjgl_util.jar"),
        gameJars.resolve("log4j-1.2.9.jar"),
        gameJars.resolve("json.jar"),
    ))
    compileOnly(fileTree(modsDir) {
        include("LazyLib*/jars/LazyLib.jar")
        include("LazyLib*/jars/internal/Kotlin-Runtime.jar") // LunaLib is Kotlin; LazyLib provides the runtime
        include("LunaLib*/jars/LunaLib.jar")
        include("*GraphicsLib*/jars/Graphics.jar") // optional: only called while it is enabled; often installed as "zz GraphicsLib-x.y.z"
    })
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-options")
}

tasks.jar {
    archiveFileName.set("$modFolderName.jar")
    // Same sources, same bytes: the committed jar only changes when the code does.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Version Checker readers (TriOS, LunaLib) compare the .version file with the published one, and the mod's
// mod_info.json "version" with both: a release that bumps one but not the other is never offered as an update.
val checkModVersion by tasks.registering {
    group = "starsector"
    description = "Fail if mod_info.json and $modFolderName.version disagree on the mod version."
    val modInfo = file("mod/mod_info.json")
    val versionFile = file("mod/$modFolderName.version")
    inputs.files(modInfo, versionFile)
    doLast {
        val declared = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(modInfo.readText())?.groupValues?.get(1)
            ?: error("No \"version\" in $modInfo")
        val text = versionFile.readText()
        fun part(name: String) = Regex("\"$name\"\\s*:\\s*\"?([^\",}\\s]+)").find(text)?.groupValues?.get(1)
            ?: error("No \"$name\" in $versionFile")
        val checked = "${part("major")}.${part("minor")}.${part("patch")}"
        check(declared == checked) { "mod_info.json says $declared but $versionFile says $checked" }
    }
}

// mod/ plus the built jar, assembled into the installable mod folder.
val assembleMod by tasks.registering(Sync::class) {
    group = "starsector"
    description = "Assemble the installable mod folder ($modFolderName/ next to source/)."
    dependsOn(checkModVersion)
    from("mod")
    from(tasks.jar) { into("jars") }
    into(modOutputDir)
}

// The release asset the .version file's directDownloadURL points to (GitHub's releases/latest/download/...): the mod
// folder at the archive root, which is what TriOS installs from.
val modZip by tasks.registering(Zip::class) {
    group = "starsector"
    description = "Zip the assembled mod for a GitHub release (build/distributions/$modFolderName.zip)."
    from(assembleMod) { into(modFolderName) }
    archiveFileName.set("$modFolderName.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.assemble {
    dependsOn(assembleMod)
}

/** The id a mod_info.json declares (its first "id", which comes before any dependency's), or null without the file. */
fun declaredModId(modInfo: File): String? =
    if (modInfo.isFile) Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(modInfo.readText())?.groupValues?.get(1) else null

// Copies the assembled mod folder over the installed copy, found by mod id: TriOS names the folder itself (e.g.
// "Weapon Tooltip Preview-0.4.0") and disables a mod's other versions by renaming their mod_info.json. A first install
// creates mods/WeaponTooltipPreview.
val deploy by tasks.registering(Sync::class) {
    group = "starsector"
    description = "Build and install the mod into the Starsector mods folder."
    from(assembleMod)
    into(provider {
        val modId = declaredModId(file("mod/mod_info.json")) ?: error("No \"id\" in mod/mod_info.json")
        val installedCopies = modsDir.listFiles().orEmpty().filter { declaredModId(it.resolve("mod_info.json")) == modId }
        check(installedCopies.size <= 1) {
            "Several folders in $modsDir declare the mod id $modId: ${installedCopies.joinToString { it.name }}"
        }
        installedCopies.firstOrNull() ?: modsDir.resolve(modFolderName)
    })
}
