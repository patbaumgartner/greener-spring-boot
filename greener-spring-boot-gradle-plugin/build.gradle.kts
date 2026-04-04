plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.1.1"
}

group = "com.patbaumgartner"
version = "0.2.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("com.patbaumgartner:greener-spring-boot-core:0.2.0-SNAPSHOT")
}

gradlePlugin {
    website.set("https://github.com/patbaumgartner/greener-spring-boot")
    vcsUrl.set("https://github.com/patbaumgartner/greener-spring-boot.git")
    plugins {
        create("greenerSpringBoot") {
            id = "com.patbaumgartner.greener-spring-boot"
            implementationClass = "com.patbaumgartner.greener.gradle.GreenerPlugin"
            displayName = "Greener Spring Boot Plugin"
            description = "Measures the energy consumption of Spring Boot applications using Joular Core"
            tags.set(listOf("spring-boot", "energy", "green-software", "sustainability", "joular"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    repositories {
        mavenLocal()
        maven {
            name = "staging"
            url = uri(layout.projectDirectory.dir("../target/staging-deploy"))
        }
        maven {
            name = "centralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            credentials {
                username = System.getenv("CENTRAL_USERNAME") ?: ""
                password = System.getenv("CENTRAL_PASSWORD") ?: ""
            }
        }
    }
    publications.withType<MavenPublication> {
        pom {
            name.set("Greener Spring Boot Gradle Plugin")
            description.set("Measures the energy consumption of Spring Boot applications using Joular Core")
            url.set("https://github.com/patbaumgartner/greener-spring-boot")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("patbaumgartner")
                    name.set("Patrick Baumgartner")
                    email.set("contact@patbaumgartner.com")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/patbaumgartner/greener-spring-boot.git")
                developerConnection.set("scm:git:https://github.com/patbaumgartner/greener-spring-boot.git")
                url.set("https://github.com/patbaumgartner/greener-spring-boot")
            }
        }
    }
}
