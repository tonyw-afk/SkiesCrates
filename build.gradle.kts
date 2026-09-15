@file:Suppress("UnstableApiUsage")

plugins {
    java
    idea
    id("quiet-fabric-loom") version ("1.13-SNAPSHOT")
    id("org.jetbrains.kotlin.jvm").version("2.3.0")
    `maven-publish`
}

val modId = project.properties["mod_id"].toString()
version = project.properties["mod_version"].toString()
group = project.properties["mod_group"].toString()

val modName = project.properties["mod_name"].toString()
base.archivesName.set(modName)

val minecraftVersion = project.properties["minecraft_version"].toString()

loom {
    mixin.useLegacyMixinAp.set(false)
    interfaceInjection.enableDependencyInterfaceInjection.set(true)
    splitEnvironmentSourceSets()
    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
    if (file("src/main/resources/$modId.accesswidener").exists()) {
        accessWidenerPath.set(file("src/main/resources/$modId.accesswidener"))
    }
}

val modImplementationInclude by configurations.register("modImplementationInclude")

configurations {
    modImplementationInclude
}

repositories {
    mavenCentral()
    maven( "https://jitpack.io")
    maven("https://maven.parchmentmc.org")
    maven {
        name = "Modrinth"
        url = uri("https://api.modrinth.com/maven")
        content {
            includeGroup("maven.modrinth")
        }
    }
    maven("https://maven.tomalbrc.de")
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" }
    maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/") {
        name = "sonatype-oss-snapshots1"
        mavenContent { snapshotsOnly() }
    }
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://maven.impactdev.net/repository/development/")
    maven("https://repo.lucko.me")
    maven( url = "https://maven.blazing-coop.net/releases") { name = "Flemmli97" }
    maven("https://maven.pokeskies.com/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$minecraftVersion:${project.properties["parchment_version"]}")
    })

    modImplementation("net.fabricmc:fabric-loader:${project.properties["loader_version"].toString()}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.properties["fabric_version"].toString()}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.properties["fabric_kotlin_version"].toString()}")

    // Coroutines
    implementation(include("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")!!)
    implementation(include("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")!!)

    implementation(include("com.github.ben-manes.caffeine:caffeine:3.2.0")!!)

    // Adventure Text!
    modImplementation(include("net.kyori:adventure-platform-fabric:5.14.2") {
        exclude("com.google.code.gson")
        exclude("ca.stellardrift", "colonel")
        exclude("net.fabricmc")
    })

    // PermissionsAPI
    modImplementation("me.lucko:fabric-permissions-api:0.3.1")?.let {
        include(it)
    }

    // GUI libraries
    modImplementation("eu.pb4:sgui:1.6.1+1.21.1")?.let {
        include(it)
    }

    // Events
    modImplementation("xyz.nucleoid:stimuli:0.4.12+1.21")?.let {
        include(it)
    }

    modImplementation("com.github.eduardomcb:discord-webhook:1.0.1")?.let {
        include(it)
    }

    // Placeholder Mods
    modImplementation("io.github.miniplaceholders:miniplaceholders-api:2.2.3")
    modImplementation("io.github.miniplaceholders:miniplaceholders-kotlin-ext:2.2.3")
    modImplementation("eu.pb4:placeholder-api:2.4.1+1.21")

    // Impactor Libraries
    modImplementation("net.impactdev.impactor:common:5.3.0+1.21.1-SNAPSHOT")
    modImplementation("net.impactdev.impactor.api:economy:5.3.0-SNAPSHOT")
    modImplementation("net.impactdev.impactor.api:text:5.3.0-SNAPSHOT")

    // Database Storage
    implementation(include("org.mongodb:mongodb-driver-sync:4.11.0")!!)
    implementation(include("org.mongodb:mongodb-driver-core:4.11.0")!!)
    implementation(include("org.mongodb:bson:4.11.0")!!)

    // SQL Storage
    implementation(include("org.mariadb.jdbc:mariadb-java-client:3.1.0")!!)
    implementation(include("com.zaxxer:HikariCP:5.1.0")!!)
    implementation(include("org.xerial:sqlite-jdbc:3.43.2.2")!!)
    implementation(include("com.h2database:h2:2.2.224")!!)
    implementation(include("com.mysql:mysql-connector-j:8.2.0")!!)

    // Integrations
    modImplementation("com.cobblemon:fabric:1.8.1+1.21.1")
    modCompileOnly("io.github.flemmli97:flan:1.21.1-1.12.2-fabric:api") {
        isTransitive = false
    }

    // Polymer/BIL Integrations
    modImplementation("de.tomalbrc:blockbench-import-library:1.2.7+1.21")
    modImplementation("eu.pb4:polymer-core:0.9.18+1.21.1")
    modImplementation("eu.pb4:polymer-networking:0.9.18+1.21.1")
    modImplementation("eu.pb4:polymer-resource-pack:0.9.18+1.21.1")
    modImplementation("eu.pb4:polymer-virtual-entity:0.9.18+1.21.1")
    modImplementation("eu.pb4:polymer-autohost:0.9.18+1.21.1")

    modCompileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}

tasks.processResources {
    inputs.property("version", version)

    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifactId = base.archivesName.get()
        from(components["java"])
    }

    repositories {
        mavenLocal()
    }
}

tasks.processResources {
    inputs.property("mod_version", version)

    filesMatching("fabric.mod.json") {
        expand("id" to modId, "version" to version, "name" to modName)
    }

    filesMatching("**/lang/*.json") {
        expand("id" to modId, "version" to version, "name" to modName)
    }
}

tasks.remapJar {
    archiveFileName.set("${project.name}-fabric-$minecraftVersion-${project.version}.jar")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.withType<AbstractArchiveTask> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from("LICENSE") {
        rename { "${it}_${modId}" }
    }
}
