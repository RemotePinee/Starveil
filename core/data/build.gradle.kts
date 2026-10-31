plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // 首页首帧快照要序列化 DTO：@Serializable 只有在应用了这个插件时才生成 serializer
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.starveil.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":core:network"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // 错误体二次解析需要 kotlinx.serialization 运行时（DTO 本身在 :core:network）
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // HttpException 需要构造真实的 okhttp ResponseBody
    testImplementation(libs.okhttp)
}
