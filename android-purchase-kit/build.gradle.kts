plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

android {
    namespace = "com.tukonnichiwa.purchasekit"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = rootProject.name
            version = project.version.toString()
            pom {
                name.set("AndroidPurchaseKit")
                description.set("A Kotlin Android library for Google Play purchases.")
                url.set("https://github.com/tu-konnichiwa/AndroidPurchaseKit")
                scm {
                    url.set("https://github.com/tu-konnichiwa/AndroidPurchaseKit")
                    connection.set("scm:git:https://github.com/tu-konnichiwa/AndroidPurchaseKit.git")
                }
            }
            // AGP creates its publishing component after Android configuration completes.
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation(libs.play.billing)
    api(libs.coroutines.core)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.mockito)
}
