// 自定义 detekt 规则集：AST 级的"颜色字面量 / 裸 dp"检查。
//
// 这不是 app 的依赖，只是构建期工具模块：产物只进 `detektPlugins` 配置，
// 被 detekt 的独立 classloader 加载，因此 detekt-api 用 compileOnly，
// 运行期一律用 detekt 自己那份，不会出现两套 API 撞版本。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.detekt.api)
}
