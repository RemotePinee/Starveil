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
// 1) 单文件行数上限
// 2) 颜色字面量只能出现在设计令牌目录
//
// 这两条是"规范写在文档里但没人遵守"的典型场景，因此落到 Gradle 任务里，
// 由 `check` 任务统一触发，CI 与本地行为一致。
// ---------------------------------------------------------------------------

// 目标 400 行，允许到 450：超几行就强行拆开，往往是为了省十行把内聚的逻辑切成
// 几个单行函数、或者把参数搬运到别处，读起来比原来更绕。超过 450 才算真失控。
val maxSourceFileLines = 450
val colorTokenDir = "core/designsystem/src/main/kotlin/com/starveil/core/designsystem/token/"

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

val verifyNoHardcodedColors by tasks.registering {
    group = "verification"
    description = "Fails when a color literal appears outside the design-token package."

    val rootDirFile = layout.projectDirectory
    val allowedPrefix = colorTokenDir
    // Color(0xAARRGGBB) / Color(0xFF...) / Color(0x80...)
    val colorLiteral = Regex("""Color\(\s*0x[0-9a-fA-F]{8}\s*\)""")
    val hexComment = Regex("""#[0-9a-fA-F]{6,8}\b""")
    // res 里唯一放行的是"系统绘制阶段必需"的静态色：splash_ 与 ic_launcher_ 前缀。
    // 整个 app/src/main/res/ 被豁免时，往 colors.xml 里加任何裸色值都不报。
    val splashColorFiles = setOf(
        "app/src/main/res/values/colors.xml",
        "app/src/main/res/values-night/colors.xml",
    )
    val systemColorLine =
        Regex("""<color\s+name="(?:splash|ic_launcher)_[a-z0-9_]*"\s*>\s*#[0-9a-fA-F]{6,8}\s*</color>""")

    doLast {
        val offenders = mutableListOf<String>()
        rootDirFile.asFile.walkTopDown()
            .onEnter { dir -> dir.name !in setOf("build", ".gradle", ".idea") }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .forEach { file ->
                val relative = file.relativeTo(rootDirFile.asFile).invariantSeparatorsPath
                if (relative.startsWith(allowedPrefix)) return@forEach
                val splashFile = relative in splashColorFiles
                file.readLines().forEachIndexed { index, line ->
                    // 只跳过这两类定义行：同一文件里的注释、别的 <color> 照样要报
                    if (splashFile && systemColorLine.containsMatchIn(line)) return@forEachIndexed
                    if (colorLiteral.containsMatchIn(line) || hexComment.containsMatchIn(line)) {
                        offenders += "$relative:${index + 1}  ${line.trim().take(80)}"
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "颜色字面量只能定义在 $allowedPrefix 下；res 里只放行 " +
                    splashColorFiles.sorted().joinToString(" 与 ") +
                    " 中 name=\"splash_\" / \"ic_launcher_\" 开头的色值定义行，" +
                    "其余请改用 DesignTokens 的语义色或 @color 引用：\n" +
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
    val coreModules = setOf(":core:common", ":core:model", ":core:network", ":core:data", ":core:designsystem")
    val explicitRules = mapOf(
        ":core:common" to emptySet<String>(),
        ":core:model" to setOf(":core:common"),
        ":core:network" to setOf(":core:common", ":core:model"),
        ":core:data" to setOf(":core:common", ":core:model", ":core:network"),
        ":core:designsystem" to setOf(":core:common"),
    )
    // feature 只允许这四个 core，与 AGENTS.md 第 1 节矩阵原文一致。
    // 特意不含 :core:network：页面层拿不到 Retrofit 接口与 DTO，换实现不用改页面。
    val featureAllowed = setOf(
        ":core:designsystem",
        ":core:data",
        ":core:model",
        ":core:common",
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
                    module.startsWith(":feature:") -> featureAllowed
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

    // 只扫 kt/java/xml 会漏掉 build.gradle.kts 里的签名口令与 gradle.properties 里的
    // 明文流量开关，这两种恰恰是出过事的地方，所以扩到 kts/properties/toml。
    val scannedExtensions = setOf("kt", "java", "xml", "kts", "properties", "toml")

    // 放行范围只到"必须写下这些字符串才能检查/说明的地方"：
    // docs 要拿它们当反面例子，本文件要拿它们当规则，README / AGENTS 同理。
    val isWhitelisted: (String) -> Boolean = { relative ->
        relative == "build.gradle.kts" ||
            relative == "AGENTS.md" ||
            relative.startsWith("README") ||
            relative.startsWith("docs/")
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
        "硬编码密钥" to Regex("""(password|apiKey|secret|token)\s*=\s*"[^"]{4,}""""),
        "明文 URL" to Regex("""http://(?!127\.0\.0\.1|localhost|schemas\.android\.com|www\.w3\.org)[^\s"]+"""),
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
    // 所有模块的 check 都会先跑根项目的规范闸门：文件行数、颜色令牌、依赖方向、安全基线。
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyFileLength"))
        dependsOn(rootProject.tasks.named("verifyNoHardcodedColors"))
        dependsOn(rootProject.tasks.named("verifyModuleGraph"))
        dependsOn(rootProject.tasks.named("verifySecurityBaseline"))
    }

    // -----------------------------------------------------------------------
    // detekt：类 / 函数 / 复杂度阈值
    //
    // AGENTS.md 第 2 节的规模阈值只有落进 check 才算约束。配置文件在
    // config/detekt/detekt.yml，只覆盖阈值，其余沿用 detekt 默认集。
    // -----------------------------------------------------------------------
    // 纯 Kotlin 模块（:core:common / :core:model）用 kotlin.jvm，Android 模块用
    // kotlin.android。两边都要挂 detekt，否则纯 Kotlin 模块等于没约束。
    listOf("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.jvm").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")

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
