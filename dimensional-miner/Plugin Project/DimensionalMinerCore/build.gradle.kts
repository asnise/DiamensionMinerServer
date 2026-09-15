plugins {
    id("java")
}

group = "com.dimensionalminer.core"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        name = "papermc-repo"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // ใช้รูปแบบการระบุ Build ให้ตรงกับเซิร์ฟเวอร์เวอร์ชัน 26.2
    compileOnly("io.papermc.paper:paper-api:26.2.build.124-stable")

    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}