plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.starveil.feature.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 单元测试跑在 JVM 上，没有 android.util.Log 的实现；不让未被 mock 的
        // Android 方法返回默认值，任何走到日志的用例都会抛
        // "Method e in android.util.Log not mocked"。这里只放开默认值，不引 Robolectric。
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        // PlayerLog 用 BuildConfig.DEBUG 关掉 release 的调试日志（docs/security.md 第 8 节）。
        // 库模块默认不生成 BuildConfig，这里显式打开，不引入任何新依赖。
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 弹层是 Dialog 窗口，要让系统栏跟着全屏一起隐藏（PlayerSheetHost）
    implementation(libs.androidx.core.ktx)

    // 竖屏单集页要画全屏竖版海报。Coil 3 是项目唯一的图片加载器（版本目录里已有，
    // 七个 feature 模块都在用），这里只是把同一份依赖开给本模块，没有引入新的库。
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // BackHandler / LocalOnBackPressedDispatcherOwner：控制层隐藏时返回键先出控制层（§18.6）。
    // 与 :app 用的是同一个 catalog 别名，不引新库。
    implementation(libs.androidx.activity.compose)

    // 唯一播放内核：Media3，不引入第二套
    implementation(project(":core:hongguo"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.session)
    // 播放数据源：OkHttp 复用连接池（切集不再重做 DNS/TLS），版本跟随 media3
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
