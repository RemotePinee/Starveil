import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.starveil.app"
    compileSdk = 36

    // 站点地址优先级：环境变量 STARVEIL_API_HOST → local.properties 的 starveil.apiHost → 占位符。
    // 占位符保证开源用户克隆下来能编译、能跑测试，只是连不上任何后端。
    val apiHost: String? = System.getenv("STARVEIL_API_HOST")?.takeIf { it.isNotBlank() }
        ?: run {
            val props = rootProject.file("local.properties")
            if (!props.exists()) return@run null
            Properties().apply { props.inputStream().use { load(it) } }
                .getProperty("starveil.apiHost")?.takeIf { it.isNotBlank() }
        }

    defaultConfig {
        applicationId = "com.starveil.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // resValue 而不是 strings.xml：源码里不出现真实域名，见 docs/security.md 第 2 节
        resValue("string", "starveil_api_host", apiHost ?: "https://api.example.com")
    }

    // Release 签名只从环境变量读取，缺失即构建失败。
    // 见 docs/security.md 第 2 节：禁止把口令写进仓库。
    //
    // "缺失即失败"必须在这里真的抛：只不创建 release 签名配置，
    // Gradle 照常产出一个 unsigned 包，注释里写的失败从来没发生过。
    val signingEnv = mapOf(
        "STARVEIL_KEYSTORE" to System.getenv("STARVEIL_KEYSTORE"),
        "STARVEIL_KEYSTORE_PASSWORD" to System.getenv("STARVEIL_KEYSTORE_PASSWORD"),
        "STARVEIL_KEY_ALIAS" to System.getenv("STARVEIL_KEY_ALIAS"),
        "STARVEIL_KEY_PASSWORD" to System.getenv("STARVEIL_KEY_PASSWORD"),
    )
    val missingSigningEnv = signingEnv.filterValues { it.isNullOrBlank() }.keys
    val keystorePath = signingEnv["STARVEIL_KEYSTORE"]
    val keystorePassword = signingEnv["STARVEIL_KEYSTORE_PASSWORD"]
    val keyAliasValue = signingEnv["STARVEIL_KEY_ALIAS"]
    val keyPasswordValue = signingEnv["STARVEIL_KEY_PASSWORD"]

    // 只有 release 相关任务才要求签名：裸 `assemble` 会构建 release，所以算 release 任务；
    // `assembleDebug` / `test` / `check` 必须继续能在没有密钥的机器上跑。
    val releaseTaskRequested = gradle.startParameter.taskNames.any { requested ->
        val taskName = requested.substringAfterLast(':')
        taskName.contains("Release") || taskName.startsWith("bundle") || taskName == "assemble"
    }
    if (releaseTaskRequested && missingSigningEnv.isNotEmpty()) {
        throw GradleException(
            "Release 构建缺少签名环境变量：" + missingSigningEnv.joinToString("、") +
                "。见 docs/security.md 第 2 节（口令只能来自环境变量）。" +
                "只想出 debug 包请用 ./gradlew assembleDebug。",
        )
    }

    signingConfigs {
        if (missingSigningEnv.isEmpty()) {
            create("release") {
                storeFile = keystorePath?.let { file(it) }
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 设置页要显示当前版本号，走 BuildConfig 比在运行时查 PackageManager 省事
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 两个聚合 SDK 各自内嵌了一份 apache/okhttp 的声明文件与自带的
            // Kotlin 元数据，合并时逐字节相同但路径相同，不排除会直接冲突。
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:home"))
    implementation(project(":feature:shortplay"))
    implementation(project(":feature:player"))
    implementation(project(":feature:user"))
    implementation(project(":feature:topic"))
    implementation(project(":feature:live"))
    implementation(project(":feature:rank"))
    implementation(project(":feature:pay"))
    implementation(project(":feature:detail"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose)

    // 聚合广告 SDK 只出现在 :app（AGENTS.md 第 1 节）：feature 层通过接口拿广告位，
    // 不感知具体聚合商，换 SDK 时不用改页面模块。
    implementation(libs.tradplus)
    // 广告源桥接：只接审核友好的几家（Sigmob + 厂商联盟），不接穿山甲/优量汇。
    // 没有 adapter 时聚合后台配了源也拉不到广告，填充率恒为 0。
    implementation(libs.tradplus.sigmob)
    implementation(libs.tradplus.mimo)
    implementation(libs.tradplus.oppo)
    implementation(libs.tradplus.vivo)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
