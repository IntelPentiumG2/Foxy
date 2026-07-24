plugins {
    id("dev.prism")
}

group = "com.leclowndu93150"
version = "1.0.0"

// The net.fabricmc types under src/main/java exist only so javac can resolve the Fabric
// supertypes of the Voxy classes Foxy compiles against. Shipping them put a real
// net.fabricmc.loader.api.FabricLoader in the game, which every multiloader mod that probes for
// that class read as "we are on Fabric" before failing on a stub Foxy does not implement.
subprojects {
    afterEvaluate {
        the<SourceSetContainer>()["main"].java.srcDir(rootProject.file("common/src/main/java"))

        // dev.prism's `dependencies { }` DSL has no testImplementation/testRuntimeOnly accessors
        // (only api/implementation/compileOnly/runtimeOnly and their mod variants), so the test
        // dependencies for VoxySodiumCompatPatchTest are declared here with plain Gradle instead.
        // Deferred to afterEvaluate for the same reason as the srcDir call above: the java
        // plugin that creates the testImplementation/testRuntimeOnly configurations is applied
        // by dev.prism itself, so those configurations do not exist yet at script-evaluation time.
        //
        // ASM 9.8, not 9.7.1: Voxy 0.2.16's classes are compiled to class file major version 69
        // (Java 25). ASM 9.7.1 tops out at major version 68 (Java 24) and throws
        // "Unsupported class file major version 69" reading them; 9.8 is the first release
        // that added V25 support.
        dependencies {
            "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.3")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
            "testImplementation"("org.ow2.asm:asm:9.8")
            "testImplementation"("org.ow2.asm:asm-tree:9.8")
        }

        // VoxySodiumCompatPatchTest needs to read a class out of the real Voxy 0.2.18 jar to
        // prove the inertness guarantee (0.2.18's classes must come back byte-identical). It
        // deliberately does NOT go on testImplementation: 0.2.16 (already on that classpath) and
        // 0.2.18 both contain a class named
        // me/cortex/voxy/client/mixin/sodium/MixinRenderSectionManager, so having both jars on
        // one classpath would make classloader resource lookups non-deterministic about which
        // jar's copy answers a given getResourceAsStream call, silently invalidating the 0.2.16
        // assertions. Resolving 0.2.18 as its own file, off this separate non-consumable
        // configuration, keeps it out of any classloader entirely; the test opens it directly
        // with ZipFile instead.
        if (name == "26.1") {
            val voxy0218Fixture = configurations.create("voxy0218Fixture") {
                isCanBeConsumed = false
                isCanBeResolved = true
                isTransitive = false
            }
            dependencies.add(voxy0218Fixture.name, "maven.modrinth:voxy:0.2.18-beta")

            tasks.withType<Test>().configureEach {
                doFirst {
                    systemProperty("foxy.voxy0218Jar", voxy0218Fixture.singleFile.absolutePath)
                }
            }

            // The bytecode-verification test loads the patched MixinDefaultChunkRenderer in a
            // real classloader, which must be able to resolve its Sodium superclass; the sodium
            // jar is compile-only for main code, so the test gets its file path the same way it
            // gets Voxy 0.2.18's. getByName runs inside doFirst, at task execution, by which
            // point the sodiumFabricCompileOnly configuration (created just below) exists.
            tasks.withType<Test>().configureEach {
                doFirst {
                    systemProperty(
                        "foxy.sodiumFabricJar",
                        configurations.getByName("sodiumFabricCompileOnly").singleFile.absolutePath
                    )
                }
            }

            // Sodium's NeoForge jar only carries its early-loading-window bootstrap classes
            // (compatibility checks, the desktop launch-warning splash) directly; the actual
            // client renderer classes (ShaderChunkRenderer, DefaultChunkRenderer, etc.) are
            // jar-in-jar'd under META-INF/jarjar and only get unpacked onto a classpath by
            // NeoForge's own runtime JarJar dependency locator — javac never sees them, so
            // mixins referencing those types by name fail to compile. The Fabric jar ships the
            // exact same client classes unnested (same FQNs, same descriptors, same class file
            // bytes — Sodium's client code isn't loader-specific). Declaring it a second
            // `compileOnly("maven.modrinth:sodium:...")` doesn't work: Modrinth's virtual Maven
            // coordinates both loader jars as the same group:artifact ("maven.modrinth:sodium")
            // with only the version string differing ("mc26.1.2-0.9.1-neoforge" vs
            // "...-fabric"), so Gradle's ordinary conflict resolution silently collapses the two
            // to one. A separate, non-transitive configuration sidesteps that resolution
            // entirely; its jar is added straight to the main source set's compile classpath and
            // never touches `runtimeOnly` or the shipped jar.
            val sodiumFabricCompileOnly = configurations.create("sodiumFabricCompileOnly") {
                isCanBeConsumed = false
                isCanBeResolved = true
                isTransitive = false
            }
            dependencies.add(sodiumFabricCompileOnly.name, "maven.modrinth:sodium:mc26.1.2-0.9.1-fabric")
            the<SourceSetContainer>()["main"].compileClasspath += sodiumFabricCompileOnly
        }
    }

    tasks.withType<Jar>().configureEach {
        exclude("net/fabricmc/**")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

prism {
    metadata {
        modId = "foxy"
        name = "Foxy"
        description = "Loads the Voxy LoD rendering mod on NeoForge by bridging its Fabric entrypoints and APIs."
        license = "MIT"
        author("leclowndu93150")
    }

    modrinthMaven()
    maven("NeoForged", "https://maven.neoforged.net/releases")

    version("26.2") {
        neoforge {
            loaderVersion = "26.2.0.28-beta"
            loaderVersionRange = "[4,)"

            dependencies {
                compileOnly("maven.modrinth:sodium:mc26.2-0.9.1-neoforge")
                runtimeOnly("maven.modrinth:sodium:mc26.2-0.9.1-neoforge")
                compileOnly("maven.modrinth:voxy:0.2.18-beta")
                implementation("maven.modrinth:voxy:0.2.18-beta")
                compileOnly("cpw.mods:modlauncher:11.0.5")
                compileOnly("cpw.mods:securejarhandler:3.0.8")
                compileOnly("net.neoforged.fancymodloader:loader:11.0.13")

                compileOnly("maven.modrinth:chunky:hEXc6nbN")
            }
        }
    }

    version("26.1") {
        neoforge {
            loaderVersion = "26.1.2.84"
            loaderVersionRange = "[4,)"

            dependencies {
                compileOnly("maven.modrinth:sodium:mc26.1.2-0.9.1-neoforge")
                runtimeOnly("maven.modrinth:sodium:mc26.1.2-0.9.1-neoforge")
                compileOnly("maven.modrinth:voxy:0.2.16-beta")
                implementation("maven.modrinth:voxy:0.2.16-beta")
                compileOnly("cpw.mods:modlauncher:11.0.5")
                compileOnly("cpw.mods:securejarhandler:3.0.8")
                compileOnly("net.neoforged.fancymodloader:loader:11.0.15")
            }
        }
    }

}
