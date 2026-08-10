group = "it.sgdc3"
// Cut for the v1.0.0-beta.2 tag. main goes back to 1.0.0-SNAPSHOT in the next commit — see it for why
// the two conventions never appear in one string.
version = "1.0.0-beta.2"

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
    // ~0.19, which does not link against 0.24. Modrinth serves the release jar under the project slug,
    // with a synthesised pom that declares no dependencies — which is what we want here, since Nova
    // itself is already on the line above.
    implementation("maven.modrinth:nova-simple-upgrades:1.11.0")

    // MockBukkit stands in for a running server, which is what an ItemStack needs before it will answer
    // anything interesting: max stack size, component-aware isSimilar, clone. The artifact id carries
    // the Paper version it was built against; the version is MockBukkit's own.
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.115.0")
    // The API alone, and deliberately one minor behind what this addon compiles against.
    //
    // Two constraints meet here. Compilation uses Origami's *widened server*, which is the whole
    // implementation — and that cannot sit on a test classpath beside MockBukkit, because both register
    // an InternalAPIBridge service and Paper refuses to start when it finds two. It is excluded below.
    // And MockBukkit ships the game's own data tables, so it only agrees with the Paper it was built
    // for: against 26.2 it dies looking up a registry entry that did not exist in 26.1.2.
    //
    // So the tests run against 26.1.2. What they exercise is this addon's arithmetic and routing, not
    // Paper's item behaviour, and the handful of API they touch — stack sizes, similarity, cloning —
    // is the same in both. See ServerBacked for the assertion that keeps that assumption honest.
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Origami's patched server carries an implementation of everything Paper's API declares. On the main
// classpath that is the point — the addon reaches into NMS for backing block states. On the test
// classpath it is fatal beside MockBukkit, which is itself an implementation: Paper looks up
// InternalAPIBridge as a service, finds two, and refuses to start.
listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach { configuration ->
    configuration.configure { exclude(group = "xyz.xenondevs.origami.patched-server") }
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
