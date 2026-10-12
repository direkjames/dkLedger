// dkLedger - the dk suite's financial analyst. Author: direk james
//
//   ./gradlew build                -> build/libs/dkLedger-<version>.jar (put on the server next to dkCore)
//   ./gradlew publishToMavenLocal  -> lets other dk plugins (e.g. the Discord bot) compile against dkLedger's API
//
// Needs in your local Maven repo first:
//   dkCore:     "./gradlew publishToMavenLocal" in dkCore
//   dkBank:     "./gradlew :api:publishToMavenLocal" in dkBank
//   Lodestock:  "./gradlew :api:publishToMavenLocal" in Lodestock

plugins {
    java
    `maven-publish`
}

group = "com.direk"
version = "0.4.0"

repositories {
    mavenLocal {
        content {
            includeGroup("com.direk")
            includeGroup("dev.direk")
            includeGroup("io.github.direkjames")
        }
    }
    mavenCentral()
    maven("https://repo.purpurmc.org/snapshots") {
        content { includeGroup("org.purpurmc.purpur") }
    }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.essentialsx.net/releases/") {
        content { includeGroup("net.essentialsx") }
    }
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.MilkBowl") // Vault API only
        }
    }
}

dependencies {
    compileOnly("org.purpurmc.purpur:purpur-api:${property("purpurVersion")}")
    // Never shade these: each is its own plugin on the server.
    compileOnly("com.direk:dkcore:${property("dkcoreVersion")}")
    compileOnly("com.github.MilkBowl:VaultAPI:${property("vaultVersion")}") { isTransitive = false }
    // Optional at runtime: only classes in ledger/hook touch these, after the plugin is known to be enabled.
    compileOnly("dev.direk:dkBank-API:${property("dkbankApiVersion")}") { isTransitive = false }
    compileOnly("net.essentialsx:EssentialsX:${property("essentialsVersion")}") { isTransitive = false }
    compileOnly("io.github.direkjames:lodestock-api:${property("lodestockVersion")}") { isTransitive = false }

    testImplementation(platform("org.junit:junit-bom:${property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveFileName.set("dkLedger-${project.version}.jar")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "dkledger"
            from(components["java"])
        }
    }
}
