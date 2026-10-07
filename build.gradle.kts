plugins {
    kotlin("multiplatform") version "2.1.0"
    id("com.android.library") version "9.0.0"
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp") version "0.0.8"
}

group = "eu.dotshell"
version = "2.2.0"

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        // commonMain holds the whole library (pure Kotlin, no platform APIs).
        // The Kotlin stdlib is added automatically for every target.
    }
}

android {
    namespace = "eu.dotshell.raptor"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}

// Kotlin Multiplatform's maven-publish integration creates one publication per target
// (kotlinMultiplatform metadata + androidRelease + iosArm64 + iosSimulatorArm64) automatically.
// We only attach the shared POM metadata to each of them.
publishing {
    publications.withType<MavenPublication>().configureEach {
        val pubName = name
        val emptyJavadocJar = tasks.register("${pubName}JavadocJar", Jar::class) {
            description = "Creates an empty javadoc JAR for Maven Central publication."
            archiveClassifier.set("javadoc")
            archiveAppendix.set(pubName)
        }
        artifact(emptyJavadocJar)

        pom {
            name.set("Raptor-KMP")
            description.set("RAPTOR algorithm implementation in Kotlin Multiplatform (Android + iOS)")
            url.set("https://github.com/dotshell-org/raptor-kmp")

            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }

            developers {
                developer {
                    id.set("tristan")
                    name.set("Tristan")
                    email.set("contact@dotshell.eu")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/dotshell-org/raptor-kmp.git")
                developerConnection.set("scm:git:ssh://github.com:dotshell-org/raptor-kmp.git")
                url.set("https://github.com/dotshell-org/raptor-kmp")
            }
        }
    }
}

nmcp {
    publishAllProjectsProbablyBreakingProjectIsolation {
        username = findProperty("ossrhUsername") as String? ?: System.getenv("OSSRH_USERNAME")
        password = findProperty("ossrhPassword") as String? ?: System.getenv("OSSRH_PASSWORD")
        publicationType = "AUTOMATIC"
    }
}

signing {
    val signingKeyId = findProperty("signing.keyId") as String? ?: System.getenv("SIGNING_KEY_ID")
    val signingPassword = findProperty("signing.password") as String? ?: System.getenv("SIGNING_PASSWORD")
    val signingKeyRingFile = findProperty("signing.secretKeyRingFile") as String? ?: System.getenv("SIGNING_KEY_RING_FILE")

    if (signingKeyId != null && signingPassword != null && signingKeyRingFile != null) {
        extra["signing.keyId"] = signingKeyId
        extra["signing.password"] = signingPassword
        extra["signing.secretKeyRingFile"] = signingKeyRingFile
        sign(publishing.publications)
    }
}
