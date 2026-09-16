import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val tensorFlowLiteCUrl =
    "https://dl.google.com/tflite-release/ios/prod/tensorflow/lite/release/ios/release/32/20240729-115310/TensorFlowLiteC/2.17.0/0c10b3543e01f547/TensorFlowLiteC-2.17.0.tar.gz"
val tensorFlowLiteCSha256 = "9667b476015f136e5b332ce040e12822c4ac6d5c58947882ddc809cdff0fb99e"
val iosFrameworksDir = rootProject.layout.projectDirectory.dir("iosApp/Frameworks")
val tensorFlowLiteCXcframework = iosFrameworksDir.dir("TensorFlowLiteC.xcframework")
val isMacHost = System.getProperty("os.name").startsWith("Mac")

val downloadTensorFlowLiteC = tasks.register<Exec>("downloadTensorFlowLiteC") {
    group = "ios"
    description = "Downloads the TensorFlow Lite C xcframework used by the iOS classifier."
    enabled = isMacHost
    inputs.property("url", tensorFlowLiteCUrl)
    inputs.property("sha256", tensorFlowLiteCSha256)
    outputs.dir(tensorFlowLiteCXcframework)
    val workDir = layout.buildDirectory.dir("tensorflow-lite-c").get().asFile.absolutePath
    commandLine(
        "sh", "-c",
        """
        set -eu
        rm -rf "${'$'}3" && mkdir -p "${'$'}3" "${'$'}4"
        curl -fsSL -o "${'$'}3/archive.tar.gz" "${'$'}1"
        echo "${'$'}2  ${'$'}3/archive.tar.gz" | shasum -a 256 -c -
        tar -xzf "${'$'}3/archive.tar.gz" -C "${'$'}3"
        rm -rf "${'$'}4/TensorFlowLiteC.xcframework"
        mv "${'$'}3"/TensorFlowLiteC-*/Frameworks/TensorFlowLiteC.xcframework "${'$'}4/"
        rm -rf "${'$'}3"
        """.trimIndent(),
        "sh", tensorFlowLiteCUrl, tensorFlowLiteCSha256, workDir, iosFrameworksDir.asFile.absolutePath,
    )
}

kotlin {
    listOf(
        iosArm64() to "ios-arm64",
        iosSimulatorArm64() to "ios-arm64_x86_64-simulator",
    ).forEach { (iosTarget: KotlinNativeTarget, slice) ->
        val sliceDir = tensorFlowLiteCXcframework.dir(slice).asFile.absolutePath
        iosTarget.compilations.getByName("main").cinterops.create("TensorFlowLiteC") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/TensorFlowLiteC.def"))
            compilerOpts("-I$sliceDir/TensorFlowLiteC.framework/Headers")
            tasks.named(interopProcessingTaskName).configure { dependsOn(downloadTensorFlowLiteC) }
        }
        iosTarget.binaries.all {
            linkerOpts("-F$sliceDir", "-framework", "TensorFlowLiteC", "-lc++")
        }
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "botix.dev.scannercatdogs.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            api(libs.litert)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.test.runner)
        }
    }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_SCANNER_ASSETS_DIR", project.file("src/androidMain/assets").absolutePath)
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}