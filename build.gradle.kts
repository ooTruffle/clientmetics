val modName = project.property("mod_name") as String
val modId = project.property("mod_id") as String
val modVersion = project.property("mod_version") as String
val modDescription = project.property("mod_description") as String
val modArchivesName = project.property("mod_archives_name") as String
val baseGroup = project.property("base_group") as String

val javaVersion = project.property("java_version") as String
val minecraftVersion = project.property("minecraft_version") as String
val fabricLoaderVersion = project.property("fabric_loader_version") as String
val oslEntrypointsVersion = project.property("osl_entrypoints_version") as String
val oslCoreVersion = project.property("osl_core_version") as String
val mcpMappings = project.property("mcp_mappings") as String
val twelvemonkeysVersion = project.property("twelvemonkeys_version") as String
val oneconfigVersion = project.property("oneconfig_version") as String
val polycomposeVersion = project.property("polycompose_version") as String
val kotlinVersion = project.property("kotlin_version") as String

group = baseGroup
version = "$modVersion+$minecraftVersion"

plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT"
    id("ploceus") version "1.19-SNAPSHOT"
    // For the PolyCompose wardrobe. Use the Kotlin version PolyCompose is built with; Kotlin and
    // Compose come from PolyCompose (and Fabric Language Kotlin) at runtime, never bundled.
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
}

repositories {
    mavenCentral()
    google()
    maven("https://repo.polyfrost.org/releases")
    maven("https://repo.polyfrost.org/snapshots")
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
    maven("https://central.sonatype.com/repository/maven-snapshots")
    // OneConfig's Compose/androidx dependency tree lives here.
    maven("https://maven.cloverclient.com/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
    exclusiveContent {
        forRepository { maven("https://maven.deftu.dev/releases") }
        filter { includeGroup("dev.deftu") }
    }
}

ploceus {
    setIntermediaryGeneration(2)
}
base {
    archivesName.set("$modArchivesName-$modVersion-${minecraftVersion}_ornithe")
}

loom {
    runConfigs.remove(runConfigs["server"])
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(ploceus.mcpMappings("stable", minecraftVersion, mcpMappings))
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    // Only the OSL modules this mod uses, like PolyPlus. The whole OSL bundle brings osl-blocks,
    // which crashes next to OneConfig 1.6 in dev runs.
    modImplementation("net.ornithemc.osl-gen2:entrypoints:$oslEntrypointsVersion")
    modImplementation("net.ornithemc.osl-gen2:core:$oslCoreVersion")

    // Neither is bundled. PolyCompose is required (it draws the wardrobe), so dev runs always load
    // it. OneConfig is optional and only adds the settings page and /clientmetics (see
    // OneConfigCompat); dev runs leave it out unless -Pclientmetics.devOneConfig=true is passed.
    val devOneConfig = providers.gradleProperty("clientmetics.devOneConfig").orNull == "true"
    modImplementation("org.polyfrost:polycompose-$minecraftVersion-ornithe:$polycomposeVersion")

    if (devOneConfig) {
        modImplementation("org.polyfrost.oneconfig:$minecraftVersion-ornithe:$oneconfigVersion")
    } else {
        modCompileOnly("org.polyfrost.oneconfig:$minecraftVersion-ornithe:$oneconfigVersion") { isTransitive = false }
    }
    for (module in arrayOf("config", "config-impl", "utils", "ui", "notifications")) {
        if (devOneConfig) {
            implementation("org.polyfrost.oneconfig:$module:$oneconfigVersion")
        } else {
            compileOnly("org.polyfrost.oneconfig:$module:$oneconfigVersion") { isTransitive = false }
        }
    }
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")

    // The Feminine Gender Mod's breast hook names its BreastSide type, so compile against it (never
    // bundled; the hook only applies when the mod is installed).
    modCompileOnly(files("libs/devcompat/FeminineGenderMod-1.0.2+mc1.8.9.jar"))

    // Dev runs only, with -Pclientmetics.devCompat=true: the Feminine Gender Mod and 3D Skin Layers
    // (jars in libs/devcompat with fabric-language-kotlin, remapped by Loom) and the OSL modules they need, at the versions a
    // real 1.8.9 install uses, so the autotest can check Clientmetics next to them. Never bundled.
    if (providers.gradleProperty("clientmetics.devCompat").orNull == "true") {
        for (module in listOf(
            "core:0.12.0+mc14w27a-mc1.14.4",
            "config:0.6.3+mc14w27a-mc15w39c",
            "keybinds:0.3.0+mc13w36a-mc17w15a",
            "lifecycle-events:0.6.1+mc13w36a-mc19w07a",
            "localization:0.2.0+mc13w26a-mc18w01a",
            "networking:0.12.0+mc13w41a-mc18w30b",
            "networking-impl:0.3.0+mc14w31a-mc1.13-pre2",
            "resource-loader:0.9.0+mc1.8.2-pre5-mc1.12.2",
            "executors:0.1.1+mc14w21a-mc1.13.2",
            "text-components:0.1.0+mc13w36a-mc1.14.4",
        )) {
            // Transitive for the libraries they need (conditional-mixin), but no other OSL modules.
            modLocalRuntime("net.ornithemc.osl-gen2:$module") { exclude(group = "net.ornithemc.osl-gen2") }
        }
        modLocalRuntime(fileTree("libs/devcompat") { include("*.jar") })
        // fabric-language-kotlin nests the Kotlin stdlib, which a dev run doesn't unpack.
        localRuntime("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    }

    // Lunar's textures are WebP, which 1.8.9's ImageIO can't read. TwelveMonkeys is pure Java;
    // its jars are nested in ours (include isn't transitive, so each one is listed).
    for (artifact in listOf(
        "com.twelvemonkeys.imageio:imageio-webp",
        "com.twelvemonkeys.imageio:imageio-core",
        "com.twelvemonkeys.imageio:imageio-metadata",
        "com.twelvemonkeys.common:common-lang",
        "com.twelvemonkeys.common:common-io",
        "com.twelvemonkeys.common:common-image",
    )) {
        implementation("$artifact:$twelvemonkeysVersion") { isTransitive = false }
        include("$artifact:$twelvemonkeysVersion")
    }
}

tasks.processResources {
    val props = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to modVersion,
        "mod_description" to modDescription,
        "base_group" to baseGroup,
        "java_version" to javaVersion,
        "minecraft_version" to minecraftVersion,
        "fabric_loader_version" to fabricLoaderVersion,
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "mixins.$modId.json")) {
        expand(props)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion.toInt()
    options.encoding = "UTF-8"
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

kotlin {
    jvmToolchain(javaVersion.toInt())
}

tasks.jar {
    inputs.property("archivesName", base.archivesName)
}

// The sources jar copies src/main/resources verbatim, so it keeps the unexpanded fabric.mod.json
// (${mod_id} and friends). If one is ever dropped into a mods folder, Fabric tries to read it as a
// mod and fails discovery for the whole game, so leave the metadata out of the sources jar.
tasks.named<org.gradle.api.tasks.bundling.Jar>("sourcesJar") {
    exclude("fabric.mod.json", "mixins.*.json")
}
