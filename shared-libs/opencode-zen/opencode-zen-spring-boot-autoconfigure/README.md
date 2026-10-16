# OpenCode Zen Spring Boot auto-configuration

Contains the shared OpenCode Zen protocol implementation, model metadata and
free/paid credential policies, SSE decoding, Spring Boot properties, and the
auto-configured request factory. Applications keep their own credential
storage, routing, budgets, request bodies, and business contracts.

Auto-configuration is discovered through Spring Boot's AutoConfiguration.imports
metadata and backs off when an application provides its own
OpenCodeZenRequestFactory bean. Free models use the public credential; paid
models require an API key.

Published coordinates: com.sharedlibs:opencode-zen-spring-boot-autoconfigure:1.1.1.
