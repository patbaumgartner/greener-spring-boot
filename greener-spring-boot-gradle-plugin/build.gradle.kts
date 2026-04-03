plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "com.patbaumgartner"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("com.patbaumgartner:greener-spring-boot-core:0.1.0-SNAPSHOT")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.2")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
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
