// dkLedger: private economy auditor for our Purpur 26.3 server (Java 25).

import xyz.jpenilla.runpaper.task.RunServer
import java.net.URI

plugins {
    java
    alias(libs.plugins.run.paper)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    compileOnly(libs.paper.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    jar {
        archiveFileName.set("dkLedger-${project.version}.jar")
    }
}

// ---------------------------------------------------------------- local test server
// ./gradlew runPurpur  ->  Purpur 26.3 with Vault, EssentialsX and dkLedger in run/purpur-26.3.
// Copy our real plugin configs into run/purpur-26.3/plugins to audit them.

val purpurVersion = "26.3"

val downloadPurpur = tasks.register("downloadPurpur") {
    group = "dkledger test server"
    description = "Downloads the latest Purpur $purpurVersion build"
    val target = layout.buildDirectory.file("purpur/purpur-$purpurVersion.jar")
    outputs.file(target)
    onlyIf { !target.get().asFile.exists() }
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        URI("https://api.purpurmc.org/v2/purpur/$purpurVersion/latest/download").toURL().openStream().use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
}

tasks.register<RunServer>("runPurpur") {
    group = "dkledger test server"
    description = "Runs Purpur $purpurVersion with dkLedger"
    dependsOn(downloadPurpur)
    minecraftVersion(purpurVersion)
    serverJar(layout.buildDirectory.file("purpur/purpur-$purpurVersion.jar"))
    displayName.set("Purpur")
    runDirectory.set(layout.projectDirectory.dir("run/purpur-$purpurVersion"))
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    pluginJars(tasks.jar.flatMap { it.archiveFile })
    downloadPlugins {
        github("MilkBowl", "Vault", libs.versions.test.vault.get(), "Vault.jar")
        modrinth("essentialsx", libs.versions.test.essentialsx.get())
    }
}
