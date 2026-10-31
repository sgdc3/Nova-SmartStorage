group = "it.sgdc3"
// Work towards 1.0.0, and not itself a release. Stays on this number until 1.0.0 itself ships: every
// beta on the way there is cut from here and comes straight back, which is what the tag v1.0.0-beta.2
// on the commit below is.
//
// Plain -SNAPSHOT rather than a snapshot of a particular beta: semver reads everything after the first
// hyphen as the pre-release, so "1.0.0-beta.3-SNAPSHOT" is the identifiers `beta` and `3-SNAPSHOT` — the
// second alphanumeric rather than numeric, which is not a version anybody meant to write. The two
// conventions do not compose, so main carries one of them and the tag carries the other.
//
// Which beta comes next is decided when one is cut, and has to be: the release workflow refuses a tag
// whose name does not match this line, so tagging means writing the exact version here first.
version = "1.0.0-SNAPSHOT"

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.nova)
}

repositories {
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.xenondevs.xyz/releases/")
}

dependencies {
    implementation(libs.nova)
    // From Modrinth's Maven, because xenondevs' own still has this at 1.5-alpha.2, built against Nova
    // ~0.19, which does not link against 0.25. Modrinth serves the release jar under the project slug,
    // with a synthesised pom that declares no dependencies — which is what we want here, since Nova
    // itself is already on the line above.
    implementation("maven.modrinth:nova-simple-upgrades:1.12.0-alpha.2")

    // MockBukkit stands in for a running server, which is what an ItemStack needs before it will answer
    // anything interesting: max stack size, component-aware isSimilar, clone. The artifact id carries
    // the Paper version it was built against; the version is MockBukkit's own.
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.119.0")
    // The API alone, and the same Paper this addon compiles against.
    //
    // Two constraints meet here. Compilation uses Origami's *widened server*, which is the whole
    // implementation — and that cannot sit on a test classpath beside MockBukkit, because both register
    // Paper's services and Paper refuses to start when it finds two. It is excluded below. And MockBukkit
    // ships the game's own data tables, so it only agrees with the Paper it was built for: the artifact
    // id above and the version here have to name the same Minecraft, and move together.
    //
    // What the tests exercise is this addon's arithmetic and routing, not Paper's item behaviour. See
    // ServerBacked for the assertion that keeps the handful of API they touch — stack sizes,
    // similarity, cloning — honest.
    testImplementation("io.papermc.paper:paper-api:26.3.build.235-stable")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Origami's widened server carries an implementation of everything Paper's API declares. On the main
// classpath that is the point — the addon reaches into NMS for backing block states. On the test
// classpath it is fatal beside MockBukkit, which is itself an implementation: Paper looks up its
// services, finds two of each, and refuses to start. Since Origami 0.6 the widened server is a transform
// of Paper's dev bundle rather than an artifact of its own, so the dev bundle is what gets left out.
listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach { configuration ->
    configuration.configure { exclude(group = "io.papermc.paper", module = "dev-bundle") }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

kotlin {
    compilerOptions {
        optIn.add("xyz.xenondevs.invui.ExperimentalReactiveApi")
        optIn.add("xyz.xenondevs.invui.dsl.ExperimentalDslApi")
    }
}

addon {
    name = "SmartStorage"
    version = project.version.toString()
    main = "it.sgdc3.smartstorage.SmartStorage"
    description = "Centralized item storage networks for Nova, inspired by AE2 / Refined Storage."
    authors = listOf("sgdc3")

    dependency("Simple_Upgrades")
    // optional: enables Logistics' item filters and item cables to interoperate with the storage interface
    dependency("Logistics", required = false)

    // output directory for the generated addon jar is read from the "outDir" project property (-PoutDir="...")
    val outDir = project.findProperty("outDir")
    if (outDir is String)
        destination = File(outDir)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
