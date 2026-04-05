pluginManagement {
    val pluginPublishVersion: String by settings
    val springJavaformatVersion: String by settings
    val useLatestVersionsVersion: String by settings
    val benManesVersionsVersion: String by settings
    val spotbugsPluginVersion: String by settings
    val openrewritePluginVersion: String by settings

    plugins {
        id("com.gradle.plugin-publish") version pluginPublishVersion
        id("io.spring.javaformat") version springJavaformatVersion
        id("se.patrikerdes.use-latest-versions") version useLatestVersionsVersion
        id("com.github.ben-manes.versions") version benManesVersionsVersion
        id("com.github.spotbugs") version spotbugsPluginVersion
        id("org.openrewrite.rewrite") version openrewritePluginVersion
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "greener-spring-boot-gradle-plugin"
