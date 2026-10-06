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

// Live route view against the real APIs: ./gradlew :shared:routeLiveSmoke [-Pline=194 -Poperator=3 -Prefreshes=2]
tasks.register<JavaExec>("routeLiveSmoke") {
    group = "verification"
    description = "Runs the live route view fetcher (sampled curlbus stops) against the real APIs and prints the result."
    val jvmCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmCompilation.compileTaskProvider)
    classpath = files(jvmCompilation.output.allOutputs, jvmCompilation.runtimeDependencyFiles)
    mainClass.set("il.kavmonitor.shared.dev.RouteLiveSmokeKt")
    args = listOf(
        (project.findProperty("line") as String?) ?: "194",
        (project.findProperty("operator") as String?) ?: "3",
        (project.findProperty("refreshes") as String?) ?: "2",
    )
    notCompatibleWithConfigurationCache("dev runner")
}

// Live "time to leave" run: ./gradlew :shared:leaveLiveSmoke -PstopCode=36926 -PwalkMinutes=6 -Pminutes=60 [-PoutDir=...]
tasks.register<JavaExec>("leaveLiveSmoke") {
    group = "verification"
    description = "Runs the LEAVE_FOR_STOP detector live against one curlbus stop and measures alert vs. actual arrival."
    val jvmCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmCompilation.compileTaskProvider)
    classpath = files(jvmCompilation.output.allOutputs, jvmCompilation.runtimeDependencyFiles)
    mainClass.set("il.kavmonitor.shared.dev.LeaveLiveSmokeKt")
    args = listOf(
        (project.findProperty("stopCode") as String?) ?: "36926",
        (project.findProperty("walkMinutes") as String?) ?: "6",
        (project.findProperty("minutes") as String?) ?: "60",
        (project.findProperty("outDir") as String?) ?: "/tmp/leave_36926",
    )
    notCompatibleWithConfigurationCache("dev runner")
}

// Prints the classpath of the jvm dev runners (to run a long live smoke with plain `java` without holding Gradle).
tasks.register("printDevClasspath") {
    val jvmCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmCompilation.compileTaskProvider)
    val cp = files(jvmCompilation.output.allOutputs, jvmCompilation.runtimeDependencyFiles)
    doLast { println("CP=" + cp.asPath) }
    notCompatibleWithConfigurationCache("dev runner")
}
