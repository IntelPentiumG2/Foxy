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
    }

    tasks.withType<Jar>().configureEach {
        exclude("net/fabricmc/**")
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
                // Version ID, not "0.2.18-beta": Voxy publishes that same version_number twice,
                // once per Minecraft version, and the plain name resolves to whichever Modrinth
                // happens to list first (today, this one). zZX86mbc is the 26.2 jar; Zt3LPI0b,
                // used by the 26.1 module below, is the 26.1.2 one.
                compileOnly("maven.modrinth:voxy:zZX86mbc")
                implementation("maven.modrinth:voxy:zZX86mbc")
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
                // Zt3LPI0b is Voxy 0.2.18-beta's 26.1.2 jar. See the note in the 26.2 block:
                // the bare version_number would resolve to the 26.2 build instead.
                compileOnly("maven.modrinth:voxy:Zt3LPI0b")
                implementation("maven.modrinth:voxy:Zt3LPI0b")
                compileOnly("cpw.mods:modlauncher:11.0.5")
                compileOnly("cpw.mods:securejarhandler:3.0.8")
                compileOnly("net.neoforged.fancymodloader:loader:11.0.15")

                compileOnly("maven.modrinth:chunky:hEXc6nbN")
            }
        }
    }

}
