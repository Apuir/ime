import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// release 签名材料：优先环境变量（CI 用），其次仓库根的 keystore.properties（已 .gitignore）。
// 两者都没有 => release 保持「未签名」，不会影响其他人的普通构建。
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val signingProp: (String, String) -> String? = { key, env ->
    System.getenv(env) ?: keystoreProps.getProperty(key)
}
val releaseStoreFile: String? = signingProp("storeFile", "IME_STORE_FILE")

@Suppress("UnstableApiUsage") android {
    namespace = "com.ninthsoft.ime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ninthsoft.ime"
        minSdk = 24
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 20201
        versionName = "2.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DENABLE_LOGGING=OFF"
                arguments += "-DALSO_LOG_TO_STDERR=OFF"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        val storePath = releaseStoreFile
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = signingProp("storePassword", "IME_STORE_PASSWORD")
                keyAlias = signingProp("keyAlias", "IME_KEY_ALIAS")
                keyPassword = signingProp("keyPassword", "IME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            // 有 keystore.properties 时自动签名；没有则产出未签名 APK
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/libs")
            assets {
                directories.add("src/main/assets")
            }
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    // implementation(libs.tokenizer)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.zxing.android.embedded)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.systemservices)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ---------------------------------------------------------------------------
// APK 文件名：ime-<版本号>.apk / ime-<版本号>-debug.apk
//
// 为什么要用后置改名而不是 AGP 的 outputFileName：AGP 9 的新 Variant API 里
// 已没有输出文件名的可写入口（`applicationVariants` / `outputFileName` 都不再可用），
// 这里在 assemble 完成后把产物改名，产物目录仍是 outputs/apk/<buildType>/。
// ---------------------------------------------------------------------------
val apkVersionName: String = android.defaultConfig.versionName ?: "unknown"

listOf("Debug" to "-debug", "Release" to "").forEach { (buildType, suffix) ->
    tasks.register("rename${buildType}Apk") {
        group = "build"
        description = "把 $buildType APK 改名为 ime-$apkVersionName$suffix.apk"
        val outputDir = layout.buildDirectory.dir("outputs/apk/${buildType.lowercase()}")
        val apkSuffix = suffix
        doLast {
            val dir = outputDir.get().asFile
            if (!dir.isDirectory) return@doLast
            dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
                .orEmpty()
                .sortedBy { it.name }
                // 多输出（如按 ABI 拆分）时后面的会带上序号，避免重名互相覆盖。
                .forEachIndexed { index, file ->
                    val extra = if (index == 0) "" else "-abi$index"
                    val target = File(dir, "ime-$apkVersionName$apkSuffix$extra.apk")
                    if (file != target) {
                        target.delete()
                        file.renameTo(target)
                    }
                }
        }
    }
}

// AGP 的 assemble<BuildType> 是配置后期才注册的，所以要等 afterEvaluate 里再挂 finalizedBy。
afterEvaluate {
    listOf("Debug", "Release").forEach { buildType ->
        val assembleTask = tasks.findByName("assemble$buildType") ?: return@forEach
        val renameTask = tasks.named("rename${buildType}Apk")
        assembleTask.finalizedBy(renameTask)
    }
}
