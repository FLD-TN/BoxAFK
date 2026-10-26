plugins {
    java
}

group = "com.dex"
version = "1.0.0"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.12")
    compileOnly("me.clip:placeholderapi:2.11.6")

    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // Paper provides the SQLite driver at runtime (never shaded); tests need their own copy
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName = "BoxAFK-${project.version}.jar"
}
