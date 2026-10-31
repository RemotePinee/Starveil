import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

// ---------------------------------------------------------------------------
// 工程规范强制检查
//
// 单文件行数上限。这条是"规范写在文档里但没人遵守"的典型场景，因此落到 Gradle
// 任务里，由 `check` 任务统一触发，CI 与本地行为一致。
//
// 颜色字面量 / 裸 dp 已从本文件的行级正则搬进 detekt 自定义规则集
// （tools/detekt-rules，见 config/detekt/detekt.yml 的 starveil 段）。
// ---------------------------------------------------------------------------

// 目标 400 行，允许到 450：超几行就强行拆开，往往是为了省十行把内聚的逻辑切成
// 几个单行函数、或者把参数搬运到别处，读起来比原来更绕。超过 450 才算真失控。
val maxSourceFileLines = 450

val verifyFileLength by tasks.registering {
    group = "verification"
    description = "Fails when a main-source Kotlin file exceeds $maxSourceFileLines lines."

    val rootDirFile = layout.projectDirectory
    val limit = maxSourceFileLines
    // 测试源码不参与：夹具、假远端、大段内联 JSON 天然长，行数上限对它们没有约束意义，
    // 把它们算进来只会逼人把断言搬去别的文件。只约束 src/main。
    val excludedSourceSets = listOf("/src/test/", "/src/androidTest/")
    doLast {
        val offenders = mutableListOf<String>()
        rootDirFile.asFile.walkTopDown()
            .onEnter { dir ->
                dir.name !in setOf("build", ".gradle", ".idea")
            }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file ->
                val path = file.invariantSeparatorsPath
                excludedSourceSets.any { marker -> path.contains(marker) }
            }
            .forEach { file ->
                val lines = file.readLines().size
                if (lines > limit) {
                    offenders += "${file.relativeTo(rootDirFile.asFile)}: $lines 行（上限 $limit）"
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "以下 Kotlin 文件超出 $limit 行上限，请拆分：\n" +
                    offenders.joinToString("\n") { "  - $it" },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 模块依赖方向检查
//
// 模块关系失控时谁都能引谁，会让 core 反过来依赖 UI、feature 互相耦合。
// 这里把 AGENTS.md 第 1 节的矩阵变成可执行检查。
// ---------------------------------------------------------------------------

val verifyModuleGraph by tasks.registering {
    group = "verification"
    description = "Enforces the module dependency matrix."

    val rootDirFile = layout.projectDirectory
    val coreModules = setOf(
        ":core:common",
        ":core:model",
        ":core:network",
        ":core:data",
        ":core:designsystem",
        ":core:hongguo",
    )
    val explicitRules = mapOf(
        ":core:common" to emptySet<String>(),
        ":core:model" to setOf(":core:common"),
        ":core:network" to setOf(":core:common", ":core:model"),
        ":core:data" to setOf(":core:common", ":core:model", ":core:network"),
        ":core:designsystem" to setOf(":core:common"),
        // 红果加密片源的纯 Kotlin 解密模块：spade key 派生、moov/sample 表解析、
        // CENC 分片解密。不碰 Android SDK，也不碰网络与 UI，只被 :feature:player 使用，
        // 因此只允许依赖 :core:common（AppResult / 调度器这些纯 Kotlin 设施）。
        ":core:hongguo" to setOf(":core:common"),
        // 构建期工具模块：只被 detektPlugins 配置消费，不进 app 依赖图，允许空依赖
        // （规则实现只引 detekt-api 与 Kotlin 标准库，这里不能有 project(...)）。
        ":tools:detekt-rules" to emptySet(),
    )
    // feature 只允许这四个 core，与 AGENTS.md 第 1 节矩阵原文一致。
    // 特意不含 :core:network：页面层拿不到 Retrofit 接口与 DTO，换实现不用改页面。
    val featureAllowed = setOf(
        ":core:designsystem",
        ":core:data",
        ":core:model",
        ":core:common",
    )
    // 单个 feature 的额外放行：在 featureAllowed 之上多给的 core，按下标点对点开。
    // :core:hongguo 只在播放页的 Media3 DataSource 里有意义（其它页面拿解密表也无从用起），
    // 所以不写进 featureAllowed——那样七个 feature 都会白拿一条解密链路。
    // 新增条目必须有对应模块实际 project(...) 引用，删依赖时本表跟着删。
    val featureExtraAllowed = mapOf(
        ":feature:player" to setOf(":core:hongguo"),
    )
    val featureModules = setOf(
        ":feature:home",
        ":feature:shortplay",
        ":feature:player",
        ":feature:user",
        ":feature:topic",
        ":feature:live",
        ":feature:rank",
        ":feature:pay",
        ":feature:detail",
    )
    // app 可以依赖任意 feature 与 core
    val appAllowed = coreModules + featureModules
    val projectRef = Regex("""project\(\s*(?:path\s*=\s*)?"(:[^"]+)"""")

    doLast {
        val offenders = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        rootDirFile.asFile.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".idea", "gradle") }
            .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "build.gradle") }
            .filter { it.parentFile != rootDirFile.asFile }
            .forEach { file ->
                val relative = file.relativeTo(rootDirFile.asFile).invariantSeparatorsPath
                val module = ":" + relative
                    .removeSuffix("/build.gradle.kts")
                    .removeSuffix("/build.gradle")
                    .replace('/', ':')
                val allowed: Set<String>? = when {
                    module == ":app" -> appAllowed
                    module.startsWith(":feature:") ->
                        featureAllowed + featureExtraAllowed[module].orEmpty()
                    else -> explicitRules[module]
                }
                // 未登记模块直接报错，不再"默认给全部 core"：那个默认值正是新模块
                // 白拿 :core:network 的入口，而且没人会在加模块时想起来改这张表。
                if (allowed == null) {
                    unregistered += module
                    return@forEach
                }
                projectRef.findAll(file.readText()).forEach { match ->
                    val target = match.groupValues[1]
                    if (target !in allowed) {
                        offenders += "$module -> $target（允许：${allowed.sorted().joinToString().ifEmpty { "无" }}）"
                    }
                }
            }
        if (offenders.isNotEmpty() || unregistered.isNotEmpty()) {
            val report = buildList {
                unregistered.distinct().forEach { module ->
                    add("$module 未登记：请在本文件 explicitRules 里写明它允许依赖谁（AGENTS.md 第 1 节）")
                }
                addAll(offenders.distinct())
            }
            throw GradleException(
                "模块依赖违反 AGENTS.md 第 1 节矩阵：\n" +
                    report.joinToString("\n") { "  - $it" },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 安全基线检查
//
// 对应 docs/security.md 第 11 节的"机器校验"行。规则来自实际踩过的坑，
// 目的是不再写出第二份同类代码。
// ---------------------------------------------------------------------------

val verifySecurityBaseline by tasks.registering {
    group = "verification"
    description = "Enforces docs/security.md machine-checkable rules."

    val rootDirFile = layout.projectDirectory

    // 首期允许申请的权限白名单，见 docs/security.md 第 6 节
    val allowedPermissions = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        // 聚合广告 SDK（TopOn / TradPlus）判定网络类型用
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.WAKE_LOCK",
    )

    // 扫描范围：kt/java/xml/kts/properties/toml 之外还要算上 php —— 插件服务端在
    // server/ 下，明文 URL 与写死的密钥和客户端是同一类风险，之前完全在闸门之外。
    val scannedExtensions = setOf("kt", "java", "xml", "kts", "properties", "toml", "php")

    // 放行范围只到"必须写下这些字符串才能检查/说明的地方"：
    // docs 要拿它们当反面例子，本文件要拿它们当规则，README / AGENTS 同理。
    val isWhitelisted: (String) -> Boolean = { relative ->
        relative == "build.gradle.kts" ||
            relative == "AGENTS.md" ||
            relative.startsWith("README") ||
            relative.startsWith("docs/") ||
            // 测试脚本里的 localhost / 占位密钥是给本地跑的，不参与生产风险
            relative.startsWith("server/starveilapi/tests/")
    }

    // 行级例外必须带上理由：`// security-baseline-allow: <为什么这样写是安全的>`
    val allowMarker = "security-baseline-allow:"

    val forbiddenSnippets = linkedMapOf(
        "明文流量" to listOf("usesCleartextTraffic=\"true\"", "cleartextTrafficPermitted=\"true\""),
        "证书校验放开" to listOf("TrustAllCerts", "AllowAllHostnameVerifier", "X509TrustManager"),
        "WebView JS 桥" to listOf("addJavascriptInterface"),
        // Java setter 与 Kotlin 属性两种写法都盯：只匹配 setXxx(true) 时，
        // Kotlin 的 `allowFileAccess = true` 能整条绕过去。
        "WebView 文件访问" to listOf(
            "setAllowFileAccess(true)",
            "setAllowFileAccessFromFileURLs(true)",
            "allowFileAccess = true",
            "allowFileUrlAccess = true",
            "setAllowUniversalAccessFromFileURLs",
        ),
        "WebView 存储放开" to listOf("domStorageEnabled = true", "setDomStorageEnabled(true)"),
        // 解析站要靠 JS 发流请求，所以这条只能逐处报、逐处写理由，不能整文件放行
        "WebView JS 放开" to listOf("javaScriptEnabled = true", "setJavaScriptEnabled(true)"),
        "混合内容放开" to listOf("MIXED_CONTENT_ALWAYS_ALLOW"),
        "进程自杀" to listOf("System.exit(", "Runtime.getRuntime().exit"),
        "签名口令入库" to listOf("storePassword = \"", "keyPassword = \""),
        "裸日志" to listOf("Log.d(", "Log.e(", "Log.v(", "Log.i(", "Log.w("),
    )

    val forbiddenPatterns = linkedMapOf(
        // 变量名必须是这些词本身（排除 KEY_TOKEN 这类键名常量）；PHP 惯用单引号
        "硬编码密钥" to Regex("""(?i)(?<![A-Za-z_])(password|apikey|secret|token)\s*=\s*['"][^'"]{4,}['"]"""),
        "明文 URL" to Regex("""http://(?!127\.0\.0\.1|localhost|schemas\.android\.com|www\.w3\.org)[^\s"']+"""),
    )

    doLast {
        val offenders = mutableListOf<String>()
        val manifestPermissions = mutableSetOf<String>()

        rootDirFile.asFile.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".idea") }
            .filter { it.isFile && it.extension in scannedExtensions }
            .filterNot { it.invariantSeparatorsPath.contains("/src/test/") }
            .filterNot { it.invariantSeparatorsPath.contains("/src/androidTest/") }
            .filterNot { isWhitelisted(it.relativeTo(rootDirFile.asFile).invariantSeparatorsPath) }
            .forEach { file ->
                val relative = file.relativeTo(rootDirFile.asFile).invariantSeparatorsPath
                file.readLines().forEachIndexed { index, line ->
                    // 行级例外只对"禁片段/禁模式"生效，权限白名单照查
                    // 注释里出现 URL / 变量名是说明而不是配置，跳过整行
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                        trimmed.startsWith("/*") || trimmed.startsWith("#")
                    ) {
                        return@forEachIndexed
                    }
                    if (!line.contains(allowMarker)) {
                        forbiddenSnippets.forEach { (name, needles) ->
                            if (needles.any { line.contains(it) }) {
                                offenders += "[$name] $relative:${index + 1}  ${line.trim().take(90)}"
                            }
                        }
                        forbiddenPatterns.forEach { (name, regex) ->
                            if (regex.containsMatchIn(line)) {
                                offenders += "[$name] $relative:${index + 1}  ${line.trim().take(90)}"
                            }
                        }
                    }
                    if (file.name == "AndroidManifest.xml" && line.contains("android.permission.")) {
                        Regex("""android\.permission\.[A-Z_0-9]+""").findAll(line)
                            .forEach { manifestPermissions += it.value }
                    }
                }
            }

        manifestPermissions.filterNot { it in allowedPermissions }.forEach {
            offenders += "[越权权限] 未在 docs/security.md 第 6 节白名单内：$it"
        }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "安全基线检查失败（见 docs/security.md）：\n" +
                    offenders.distinct().joinToString("\n") { "  - $it" },
            )
        }
    }
}

subprojects {
    // 所有模块的 check 都会先跑根项目的规范闸门：文件行数、依赖方向、安全基线。
    // 颜色令牌与裸 dp 已由 detekt 的 starveil 规则集接管，不在这里。
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyFileLength"))
        dependsOn(rootProject.tasks.named("verifyModuleGraph"))
        dependsOn(rootProject.tasks.named("verifySecurityBaseline"))
    }

    // -----------------------------------------------------------------------
    // detekt：类 / 函数 / 复杂度阈值
    //
    // AGENTS.md 第 2 节的规模阈值只有落进 check 才算约束。配置文件在
    // config/detekt/detekt.yml，只覆盖阈值，其余沿用 detekt 默认集。
    // -----------------------------------------------------------------------
    // 纯 Kotlin 模块（:core:common / :core:model / :tools:detekt-rules）用 kotlin.jvm，
    // Android 模块用 kotlin.android。两边都要挂 detekt，否则纯 Kotlin 模块等于没约束。
    //
    // 同时给每个模块挂上自定义规则集（颜色字面量 / 裸 dp）。走 detektPlugins 配置，
    // 由 detekt 自己的 classloader 加载，不进编译期与运行期 classpath。
    listOf("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.jvm").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")

            dependencies {
                add("detektPlugins", rootProject.project(":tools:detekt-rules"))
            }

            tasks.withType<Detekt>().configureEach {
                buildUponDefaultConfig = true
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                basePath = rootProject.projectDir.absolutePath
                jvmTarget = "17"
                parallel = true
                // 用源目录（而不是整个工程目录）：detekt 的 InvalidPackageDeclaration
                // 以 src/*/kotlin 为根计算包路径，给工程根会让每个文件都判成位置不符
                setSource(
                    project.files(
                        project.file("src/main/kotlin"),
                        project.file("src/test/kotlin"),
                    ).filter { it.exists() },
                )
            }

            tasks.matching { it.name == "check" }.configureEach {
                dependsOn(tasks.named("detekt"))
            }
        }
    }
}
