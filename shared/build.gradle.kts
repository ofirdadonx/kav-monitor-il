import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        namespace = "il.kavmonitor.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        androidResources { enable = true }
    }

    // Host JVM target: used for unit tests (fixtures + trigger logic) and the live smoke runner.
    // An iOS target (iosArm64/iosSimulatorArm64) can be added here later; commonMain has no Android deps.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.multiplatform.settings.test)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "il.kavmonitor.shared.resources"
}

// Live smoke test against the real APIs: ./gradlew :shared:liveSmoke [-Pline=194 -Poperator=3 -PstopCode=...]
tasks.register<JavaExec>("liveSmoke") {
    group = "verification"
    description = "Calls Stride + curlbus through the shared client code and prints directions, stops and live visits."
    val jvmCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmCompilation.compileTaskProvider)
    classpath = files(jvmCompilation.output.allOutputs, jvmCompilation.runtimeDependencyFiles)
    mainClass.set("il.kavmonitor.shared.dev.LiveSmokeKt")
    args = listOfNotNull(
        (project.findProperty("line") as String?) ?: "194",
        (project.findProperty("operator") as String?) ?: "3",
        (project.findProperty("stopCode") as String?) ?: "-",
        (project.findProperty("watchMinutes") as String?) ?: "0",
    )
    notCompatibleWithConfigurationCache("dev runner")
}
