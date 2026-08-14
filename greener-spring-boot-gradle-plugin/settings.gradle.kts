pluginManagement {
    val pluginPublishVersion = providers.gradleProperty("pluginPublishVersion").get()
    val springJavaformatVersion = providers.gradleProperty("springJavaformatVersion").get()
    val useLatestVersionsVersion = providers.gradleProperty("useLatestVersionsVersion").get()
    val benManesVersionsVersion = providers.gradleProperty("benManesVersionsVersion").get()
    val spotbugsPluginVersion = providers.gradleProperty("spotbugsPluginVersion").get()
    val openrewritePluginVersion = providers.gradleProperty("openrewritePluginVersion").get()

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

// com.github.ben-manes.versions does not support parallel project execution
if (gradle.startParameter.taskNames.any { it == "dependencyUpdates" || it.endsWith(":dependencyUpdates") }) {
	gradle.startParameter.isParallelProjectExecutionEnabled = false
}
