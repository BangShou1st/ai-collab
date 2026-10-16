plugins {
    id("java-library")
    id("maven-publish")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":opencode-zen-spring-boot-autoconfigure"))
    api("org.springframework.boot:spring-boot-starter:3.3.2")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "opencode-zen-spring-boot-starter"
            from(components["java"])
        }
    }
}
