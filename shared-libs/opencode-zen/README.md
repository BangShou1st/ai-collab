# OpenCode Zen shared libraries

This Gradle build follows the conventional two-module Spring Boot layout:

- opencode-zen-spring-boot-autoconfigure: shared protocol and model policies, Spring Boot auto-configuration, properties, and request factory.
- opencode-zen-spring-boot-starter: dependency aggregation entry point.

The modules use group com.sharedlibs and version 1.1.1. Their published artifacts use the same names as the module directories.

## Build

From this directory, run:

    .\gradlew.bat compileJava
    .\gradlew.bat publishToMavenLocal

Spec Agent uses this source directly through Gradle composite-build substitution. Other projects can consume the starter from Maven Local or a Maven repository.
