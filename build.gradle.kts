plugins {
    `java-library`
    idea
    signing

    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "one.pkg.velocity_rc"
version = "3.4.0-J25-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://mvn.pkg.one/snapshots")
}

dependencies {
    implementation("com.google.guava:guava:33.5.0-jre")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("io.netty:netty-handler:4.2.7.Final")
    implementation("org.checkerframework:checker-qual:3.42.0")
    compileOnly("org.jetbrains:annotations:24.1.0")
    compileOnly("org.jspecify:jspecify:1.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.1")
}

val targetJavaVersion = 25

java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
}

tasks.withType<JavaCompile> {
    options.encoding = Charsets.UTF_8.name()
    options.release = targetJavaVersion
}

tasks.withType<ProcessResources> {
    filteringCharset = Charsets.UTF_8.name()
}

testing.suites.named<JvmTestSuite>("test") {
    useJUnitJupiter()
    targets.all {
        testTask.configure {
            reports.junitXml.required = true
        }
    }
}

val apiAndDocs: Configuration by configurations.creating {
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

configurations.api {
    extendsFrom(apiAndDocs)
}

mavenPublishing {
    coordinates(group as String, "velocity-native", version as String)

    pom {
        name.set("Velocity Native J25")
        description.set("Velocity Native J25")
        inceptionYear.set("2025")
        url.set("https://github.com/404Setup/VelocityNative25")
        licenses {
            license {
                name.set("GNU GENERAL PUBLIC LICENSE V3")
                url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                distribution.set("https://www.gnu.org/licenses/gpl-3.0.html")
            }
        }
        developers {
            developer {
                id.set("Velocity Teams")
                name.set("Velocity")
                url.set("https://github.com/PaperMC")
            }
        }
        scm {
            url.set("https://github.com/404Setup/VelocityNative25")
            connection.set("scm:git:git://github.com/404Setup/VelocityNative25.git")
            developerConnection.set("scm:git:ssh://git@github.com/404Setup/VelocityNative25.git")
        }
    }

    publishing {
        repositories {
            maven {
                name = "one"
                url = uri("https://mvn.pkg.one/snapshots")
                credentials(PasswordCredentials::class)
            }
        }
    }

    signAllPublications()
}
