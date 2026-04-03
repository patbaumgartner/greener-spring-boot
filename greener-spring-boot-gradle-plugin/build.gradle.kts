plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.patbaumgartner"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("com.patbaumgartner:greener-spring-boot-core:0.1.0-SNAPSHOT")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

gradlePlugin {
    plugins {
        create("greenerSpringBoot") {
            id = "com.patbaumgartner.greener-spring-boot"
            implementationClass = "com.patbaumgartner.greener.gradle.GreenerPlugin"
            displayName = "Greener Spring Boot Plugin"
            description = "Measures the energy consumption of Spring Boot applications using Joular Core"
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
    }
}
