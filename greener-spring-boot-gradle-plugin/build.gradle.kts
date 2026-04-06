plugins {
    `java-gradle-plugin`
    `maven-publish`
    `jacoco`
    `pmd`
    id("com.gradle.plugin-publish")
    id("io.spring.javaformat")
    id("se.patrikerdes.use-latest-versions")
    id("com.github.ben-manes.versions")
    id("com.github.spotbugs")
    id("org.openrewrite.rewrite")
}

group = "com.patbaumgartner"
// version is read from gradle.properties

val coreVersion: String by project
val junitBomVersion: String by project
val assertjVersion: String by project
val mockitoVersion: String by project
val rewriteStaticAnalysisVersion: String by project
val jacocoVersion: String by project
val pmdVersion: String by project

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
    implementation("com.patbaumgartner:greener-spring-boot-core:$coreVersion")
    testImplementation(platform("org.junit:junit-bom:$junitBomVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    rewrite("org.openrewrite.recipe:rewrite-static-analysis:$rewriteStaticAnalysisVersion")
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
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = jacocoVersion
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.35".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

pmd {
    toolVersion = pmdVersion
    ruleSetFiles = files("../pmd-ruleset.xml")
    isConsoleOutput = true
    isIgnoreFailures = false
}

tasks.named<Pmd>("pmdTest") {
    enabled = false
}

spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
}

rewrite {
    activeRecipe("com.patbaumgartner.greener/CodeQuality")
}

tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
    rejectVersionIf {
        val dominated = "(?i).*[-_.]?(alpha|beta|b|rc|cr|m|ea)[-_.]?[0-9]*$"
        candidate.version.matches(Regex(dominated))
    }
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
