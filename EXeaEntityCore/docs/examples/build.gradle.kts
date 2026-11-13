plugins {
    java
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.exea:exea-entity-core-api:1.1.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}
