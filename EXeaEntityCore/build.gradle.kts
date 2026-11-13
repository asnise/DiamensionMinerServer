plugins {
    id("java-library")
    id("maven-publish")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("org.joml:joml:1.10.8")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// Where the plugin jar goes. Default: this repo's test server. Elsewhere: gradlew jar -PoutDir=<folder>
val pluginOutDir: String = (findProperty("outDir") as String?) ?: "../server/plugins"

tasks {
    jar {
        destinationDirectory.set(file(pluginOutDir))
        archiveFileName.set("EXeaEntityCore.jar")
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
        // The EXea model pack (written by tools/exea_exporter.js) ships inside the jar and is
        // served to players by PackServer.
        inputs.dir("pack")
        from("pack") {
            into("exeapack")
        }
    }

    // The public API only (com.exea.entity.api), for other plugins to compile against:
    // compileOnly("com.exea:exea-entity-core-api:<version>") after publishToMavenLocal.
    register<Jar>("apiJar") {
        archiveBaseName.set("exea-entity-core-api")
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        from(sourceSets.main.get().output) {
            include("com/exea/entity/api/**")
        }
    }

    register<Jar>("apiSourcesJar") {
        archiveBaseName.set("exea-entity-core-api")
        archiveClassifier.set("sources")
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        from(sourceSets.main.get().allJava) {
            include("com/exea/entity/api/**")
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            groupId = project.group.toString()
            artifactId = "exea-entity-core-api"
            version = project.version.toString()
            artifact(tasks.named("apiJar"))
            artifact(tasks.named("apiSourcesJar"))
            pom {
                name.set("EXeaEntityCore API")
                description.set("API of EXeaEntityCore: animated Blockbench entities on Paper (ItemDisplay bones).")
            }
        }
    }
}
