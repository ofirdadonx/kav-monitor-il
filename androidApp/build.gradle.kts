plugins {
    alias(libs.plugins.androidApplication)
    // No kotlin-android plugin: AGP 9 has built-in Kotlin support.
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "il.kavmonitor.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "il.kavmonitor.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2"
    }

    // Release channel. beta: separate app next to the regular one (".beta" id, own label + orange BETA icon),
    // version "0.2-beta". prod: the regular app, version "0.2".
    flavorDimensions += "channel"
    productFlavors {
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
        }
        create("prod") {
            dimension = "channel"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "2g"
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
                // -Pkav.live=true: run the UI tests against the real Stride/curlbus APIs instead of fixtures
                it.systemProperty("kav.live", (project.findProperty("kav.live") as String?) ?: "false")
                it.systemProperty("kav.liveWaitMin", (project.findProperty("kav.liveWaitMin") as String?) ?: "0")
            }
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.multiplatform.settings.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// APKs named with the full version: build/outputs/named/kav-monitor-0.2-beta.apk (debug), …-release-unsigned.apk.
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val versionName = variant.outputs.first().versionName
        val buildType = variant.buildType ?: "debug"
        val apkDir = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
        val copy = tasks.register<Copy>("copy${cap}NamedApk") {
            from(apkDir) { include("*.apk") }
            into(layout.buildDirectory.dir("outputs/named"))
            rename { "kav-monitor-${versionName.get()}" + (if (buildType == "debug") "" else "-$buildType-unsigned") + ".apk" }
        }
        tasks.matching { it.name == "assemble$cap" }.configureEach { finalizedBy(copy) }
    }
}

roborazzi {
    outputDir.set(file("../docs/screenshots"))
}
