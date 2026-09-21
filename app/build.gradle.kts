import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名材料外置：keystore 与口令从 local.properties（已 gitignore）读；
// 没有就不设签名配置，产物为未签名 APK。
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystoreFile = file(signingProps.getProperty("hypercurve.storeFile") ?: "mykey.jks")
val hasKeystore = keystoreFile.exists()
if (!hasKeystore) {
    logger.lifecycle(
        "⚠ 未找到签名 keystore（${keystoreFile.path}），产物将是未签名 APK；" +
            "自建请在 local.properties 里写 hypercurve.storeFile / storePassword / keyAlias / keyPassword"
    )
}

android {
    namespace = "com.blc.hypercurve"
    // libxposed 102.0.0 的 aar-metadata 要求 compileSdk >= 37（AGP 9.x 支持 minor SDK 平台）
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.blc.hypercurve"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = keystoreFile
                storePassword = signingProps.getProperty("hypercurve.storePassword")
                keyAlias = signingProps.getProperty("hypercurve.keyAlias") ?: "key0"
                keyPassword = signingProps.getProperty("hypercurve.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
        release {
            // R8 代码收缩 + 资源收缩；框架按名字调用的类由 proguard-rules.pro 的 keep 规则保住
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    // 打包时排除的噪音文件；META-INF/xposed/ 下的模块声明不能受影响
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "kotlin/**",                      // stdlib 的 .kotlin_builtins 元数据，运行时不读
                "kotlin-tooling-metadata.json",
                "DebugProbesKt.bin",               // coroutines 调试探针
                ".gitignore", "**/*.proto",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
    }
}

// Kotlin 编译选项（compilerOptions DSL）
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // 去掉 Kotlin 为「从 Java 可见的成员」自动生成的非空断言，dex 里少一批 Intrinsics.checkNotNull*
        // 调用与字符串常量；本项目不依赖这些自动抛出的 NPE。
        freeCompilerArgs.add("-Xno-param-assertions")
        freeCompilerArgs.add("-Xno-receiver-assertions")
        freeCompilerArgs.add("-Xno-call-assertions")
    }
}

dependencies {
    // 框架侧 API：运行期由 LSPosed 提供，compileOnly → 不进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
    // 规范要求的注解依赖（api 的 @NonNull/@SinceApi 需要它参与编译），同样不打包
    compileOnly("androidx.annotation:annotation:1.9.1")
    // 模块 UI 侧：作用域申请 / 远程偏好写入 / 已注入进程查询。只有 15 个类，aar 自带 consumer 规则
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.activity:activity-compose:1.10.1")
    // Dispatchers.Main / rememberCoroutineScope 需要 Android 实现，代码里也直接 import 了 launch
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // 界面只用 Icons.Default.Check，其余图标类会被 R8 裁掉；1.7.8 与 compose-bom 解析结果一致
    implementation("androidx.compose.material:material-icons-core:1.7.8")
}
