import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// ── 应用内更新：公开清单验签公钥与 debug-only 受控测试源 ───────────────
// 公钥可入库；私钥只在本机仓库外，由 tools/release_update.ps1 通过环境变量读取。
// Release 中的测试源字段固定为空，代码还要求 BuildConfig.DEBUG，双重排除。
val updatePublicKeyFile = rootProject.file("docs/updates/upcpocket-update-public-key.b64")
val updatePublicKey = if (updatePublicKeyFile.isFile) {
    updatePublicKeyFile.readText(Charsets.UTF_8).trim()
} else {
    ""
}
require(updatePublicKey.isNotBlank()) {
    "缺少更新清单验签公钥：docs/updates/upcpocket-update-public-key.b64"
}

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val updateTestBaseUrl = providers.gradleProperty("updateTestBase").orNull.orEmpty().trim()
val updateTestPublicKey = providers.gradleProperty("updateTestPublicKey").orNull.orEmpty().trim()

// 仅用于本机受控安装验收：显式传入 e2eVersionCode 才改变版本；Release 任务直接失败。
// 正常发布构建不要传这两个属性。
val e2eVersionCode = providers.gradleProperty("e2eVersionCode").orNull?.toIntOrNull()
val e2eVersionName = providers.gradleProperty("e2eVersionName").orNull.orEmpty().trim()
val allowE2eReleaseBuild = providers.gradleProperty("allowE2eReleaseBuild").orNull == "true"
if (e2eVersionCode != null) {
    val releaseRequested = gradle.startParameter.taskNames.any {
        it.contains("release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
    }
    // 默认拒绝 Release；仅本机受控安装验收可显式追加 -PallowE2eReleaseBuild=true。
    // 这种包不得上传/发布，最终发布包必须重新以默认 versionCode=25 构建。
    require(!releaseRequested || allowE2eReleaseBuild) {
        "e2eVersionCode 仅用于 debug 验收，禁止用于 Release/Bundle（受控释放需显式 allowE2eReleaseBuild）"
    }
    require(e2eVersionCode in 1..999_999) { "e2eVersionCode 非法" }
    require(e2eVersionName.matches(Regex("^[0-9]+(\\.[0-9]+){1,3}(-[0-9A-Za-z.-]{1,32})?$"))) {
        "e2eVersionName 非法"
    }
}

// ── 签名 ───────────────────────────────────────────────────────────────
// 凭据在仓库根的 keystore.properties（已 gitignore），keystore 文件本身在仓库之外。
// 刻意让 debug 与 release 用**同一套签名**：不同签名不能互相覆盖安装，
// 切换时必须卸载 → 本地数据全丢。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

// 新版固定使用仓库外长期签名；即使通过聚合任务构建，也禁止 Release 回退开发签名。
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project.path == ":app" && it.name.contains("Release", ignoreCase = true) }) {
        require(hasReleaseSigning && listOf("storePassword", "keyAlias", "keyPassword").all {
            !keystoreProps.getProperty(it).isNullOrBlank()
        }) {
            "新版 Release 需要有效的仓库外长期 keystore 和本机 keystore.properties；禁止回退 debug 签名"
        }
    }
}

// 新包名与长期签名需单独注册微信 AppID；不复用旧包的 wechatAppId。未配置时只显示
// 复制/分享兜底，不发送 SDK 请求，也不伪造“直达成功”。可写在 gitignore 的
// keystore.properties 或本机 ~/.gradle/gradle.properties（wechatNextAppId=wx...）。
val wechatAppId = (keystoreProps.getProperty("wechatNextAppId")
    ?: providers.gradleProperty("wechatNextAppId").orNull)
    ?.trim()
    ?.takeIf { it.matches(Regex("wx[0-9a-f]{16}")) }
    .orEmpty()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Kotlin/资源命名空间保持不变；新版 applicationId 独立，可与旧版并存。
    // 两包各自保存账号、数据库、组件及更新状态，不读取或迁移旧包数据。
    namespace = "com.upcpocket.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.upcpocket.next"
        minSdk = 31
        targetSdk = 36
        // 独立应用标识的版本号从 1 开始（与上游的 97 无关，两者互不覆盖）。
        // 轻量版在 0.1.10（code 11）之上递增，保证覆盖安装不会触发降级。
        // 第二步在 0.2.0（code 12）之上继续递增；第三步 0.2.2 / code 14；
        // 第四步 0.2.4 / code 16；第五步 0.2.5 / code 17；本轮大字号/时间修复 0.2.6 / code 18。
        // 2.0.4 候选：超星两馆区座位交互与唐岛湾对齐；基于已发布 2.0.3（24）。
        // 仅本机受控安装验收可显式传 e2eVersionCode/e2eVersionName，且构建脚本拒绝 Release。
        versionCode = e2eVersionCode ?: 25
        versionName = if (e2eVersionCode != null) e2eVersionName else "2.0.4"
        buildConfigField("String", "WECHAT_APP_ID", "\"$wechatAppId\"")
        buildConfigField("String", "UPDATE_PUBLIC_KEY", buildConfigString(updatePublicKey))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("upcpocket") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("upcpocket")
            // 只有命令行显式提供时才注入；不写死本机地址，发布包也拿不到这两个字段。
            buildConfigField("String", "UPDATE_TEST_BASE_URL", buildConfigString(updateTestBaseUrl))
            buildConfigField("String", "UPDATE_TEST_PUBLIC_KEY", buildConfigString(updateTestPublicKey))
        }
        release {
            buildConfigField("String", "UPDATE_TEST_BASE_URL", "\"\"")
            buildConfigField("String", "UPDATE_TEST_PUBLIC_KEY", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("upcpocket")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric 需要真实的资源与清单（Room 的 schema 校验、Context 都依赖它们）
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room schema 现由 :feature:focus 导出；app 只保留宿主偏好与系统组件。

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:account"))
    implementation(project(":core:account-api"))
    implementation(project(":core:upc-auth"))
    implementation(project(":core:platform"))
    implementation(project(":core:plugin-api"))
    implementation(project(":core:plugin-runtime"))
    implementation(project(":core:plugin-ui"))
    implementation(project(":feature:card"))
    implementation(project(":feature:power"))
    implementation(project(":feature:classroom"))
    implementation(project(":feature:library"))
    implementation(project(":feature:focus"))
    implementation(project(":feature:timetable"))
    implementation(project(":feature:todo"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Material You 配色生成（由种子色算出整套 M3 浅/深配色，见 ui/theme/MaterialYouScheme.kt）
    implementation(libs.material.color.utilities)

    // 本地偏好；专注的 DataStore 与 Room 数据库已随插件迁入 :feature:focus。
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.wechat.sdk)

    // 桌面小组件：只有用户点刷新时才入队一次性联网任务；没有周期任务，也没有后台轮询。
    implementation(libs.androidx.work.runtime.ktx)

    // 已移除的上游依赖，当前功能不需要：
    //  · androidx.security.crypto（当前凭据/会话使用自有 Keystore + AtomicFile 格式，不依赖 Tink）
    //  · okhttp / okhttp-logging（当前页面仍使用平台 HTTP 客户端，不引入请求日志）

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":core:account"))
    androidTestImplementation(project(":core:account-api"))

    // 真实 Room（内存 SQLite）+ Android 运行时。
    // 为什么需要：日程换类型、作息改动这类缺陷只在**真的读写数据库**时才暴露 ——
    // "旧行有没有被清掉"用纯函数测不出来（见 ScheduleRepositoryPlanKindTest）。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
