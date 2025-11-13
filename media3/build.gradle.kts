import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "io.fastpix.data.exo"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // ExoPlayer dependencies
    api(libs.exoplayer.core)
    api(libs.exoplayer.ui)
    api(libs.exoplayer.common)
    api(libs.androidx.media3.exoplayer.hls)

    // FastPix Core
    api(libs.core)
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

publishing {
    publications {
        create<MavenPublication>("bar") {
            groupId = "io.fastpix.data"
            artifactId = "media3"
            version = "1.2.1"
            artifact("${buildDir}/outputs/aar/media3-release.aar")

            pom.withXml {
                val dependenciesNode = asNode().appendNode("dependencies")
                configurations.getByName("api").dependencies.forEach { dependency ->
                    if (dependency.group != null && dependency.version != null) {
                        val dependencyNode = dependenciesNode.appendNode("dependency")
                        dependencyNode.appendNode("groupId", dependency.group)
                        dependencyNode.appendNode("artifactId", dependency.name)
                        dependencyNode.appendNode("version", dependency.version)
                        dependencyNode.appendNode("scope", "compile")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "GithubPackages"
            url = uri("https://maven.pkg.github.com/FastPix/android-data-androidXmedia3")
            credentials {
                username = localProperties.getProperty("lpr.user") ?: System.getenv("GITHUB_USER")
                password = localProperties.getProperty("lpr.key") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}