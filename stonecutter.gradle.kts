plugins {
    alias(libs.plugins.loom) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    id("dev.kikugie.stonecutter")
}

fun git(vararg arguments: String): String? = runCatching {
    ProcessBuilder(listOf("git") + arguments)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
        .apply { if (waitFor() != 0) return@runCatching null }
        .inputStream.bufferedReader().readText().trim()
}.getOrNull()?.takeIf(String::isNotBlank)

fun commitCountAfter(anchor: String, ref: String): Int? =
    git("rev-list", "--count", "$anchor..$ref")?.toIntOrNull()

fun versionForBuild(): String {
    val versionFile = rootProject.file("buildSrc/PROJECT_VERSION")
    val baseVersion = versionFile.readText().trim().split('.')
    require(baseVersion.size >= 2) { "PROJECT_VERSION must contain at least stable and beta components" }

    val isPullRequest = true
    val versionRef ="upstream/beta"
    val versionFilePath = "buildSrc/PROJECT_VERSION"
    val versionParts = if (isPullRequest) {
        git("show", "$versionRef:$versionFilePath")?.split('.')
            ?: error("Unable to read beta version from $versionRef")
    } else baseVersion
    val stable = versionParts.getOrNull(0)?.toIntOrNull()
        ?: error("Invalid stable version in PROJECT_VERSION")
    val beta = versionParts.getOrNull(1)?.toIntOrNull()
        ?: error("Invalid beta version in PROJECT_VERSION")
    val anchor = git("log", "-1", "--format=%H", versionRef, "--", versionFilePath)
        ?: error("Unable to find the last beta version bump commit on $versionRef")
    val commitsAfterBump = commitCountAfter(anchor, versionRef)
        ?: error("Unable to count commits after the last beta version bump on $versionRef")
    val bugfix = if (isPullRequest) commitsAfterBump + 1 else commitsAfterBump

    return "$stable.$beta.$bugfix"
}

val projectVersion = providers.provider(::versionForBuild)

allprojects {
    group = "at.hannibal2.skyhanni"

    /**
     * The version of the project.
     * Stable version
     * Beta version
     * Bugfix version
     */
    version = projectVersion.get()

    repositories {
        mavenCentral()
        mavenLocal()

        // Fabric
        exclusiveContent {
            forRepository {
                maven("https://maven.fabricmc.net")
            }
            filter {
                includeGroupAndSubgroups("net.fabricmc")
            }
        }

        // Mixin
        exclusiveContent {
            forRepository {
                maven("https://repo.spongepowered.org/repository/maven-public")
            }
            filter {
                includeGroup("org.spongepowered")
            }
        }

        // DevAuth
        exclusiveContent {
            forRepository {
                maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
            }
            filter {
                includeGroup("me.djtheredstoner")
            }
        }

        // libautoupdate
        exclusiveContent {
            forRepository {
                maven("https://repo.nea.moe/releases")
            }
            filter {
                includeGroup("moe.nea")
            }
        }

        // MoulConfig and a few Detekt rules
        exclusiveContent {
            forRepositories(
                repositories.mavenLocal(),
                repositories.maven("https://maven.notenoughupdates.org/releases"),
            )
            filter {
                includeGroupAndSubgroups("org.notenoughupdates")
            }
        }

        // Hypixel Mod API
        exclusiveContent {
            forRepository {
                maven("https://repo.hypixel.net/repository/Hypixel")
            }
            filter {
                includeGroup("net.hypixel")
            }
        }

        // Modrinth
        exclusiveContent {
            forRepository {
                maven("https://api.modrinth.com/maven")
            }
            filter {
                includeGroup("maven.modrinth")
            }
        }

        // REI for compat plugin
        exclusiveContent {
            forRepository {
                maven("https://maven.shedaniel.me")
            }
            filter {
                includeGroup("dev.architectury")
                includeGroupAndSubgroups("me.shedaniel")
            }
        }

        exclusiveContent {
            forRepositories(
                repositories.maven("https://maven.azureaaron.net/releases"),
            )
            filter {
                includeGroupAndSubgroups("net.azureaaron")
            }
        }
    }
}

stonecutter active "26.2"

stonecutter handlers {
    configure("fsh", "vsh") {
        commenter = line("//")
    }
}

stonecutter parameters {
    replacements {
        string(current.parsed < "26.2") {
            replace("net.minecraft.world.entity.monster.cubemob.MagmaCube", "net.minecraft.world.entity.monster.MagmaCube")
            replace("net.minecraft.world.entity.monster.cubemob.Slime", "net.minecraft.world.entity.monster.Slime")

            val dyeColors = mapOf(
                "black" to "BLACK",
                "blue" to "BLUE",
                "brown" to "BROWN",
                "cyan" to "CYAN",
                "gray" to "GRAY",
                "green" to "GREEN",
                "lightBlue" to "LIGHT_BLUE",
                "lightGray" to "LIGHT_GRAY",
                "lime" to "LIME",
                "magenta" to "MAGENTA",
                "orange" to "ORANGE",
                "pink" to "PINK",
                "purple" to "PURPLE",
                "red" to "RED",
                "white" to "WHITE",
                "yellow" to "YELLOW",
            )
            dyeColors.forEach { (lower, upper) ->
                replace("DYE.$lower()", "${upper}_DYE")
                replace("WOOL.$lower()", "${upper}_WOOL")
                replace("STAINED_GLASS.$lower()", "${upper}_STAINED_GLASS")
                replace("STAINED_GLASS_PANE.$lower()", "${upper}_STAINED_GLASS_PANE")
                replace("DYED_TERRACOTTA.$lower()", "${upper}_TERRACOTTA")
            }
        }
    }

    filters.include("**/*.fsh", "**/*.vsh")
}
