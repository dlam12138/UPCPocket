import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

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

// 微信开放平台移动应用 AppID。没有正式 AppID 时保持空字符串：界面只显示
// 复制/分享兜底，不发送 SDK 请求，也不伪造“直达成功”。可写在 gitignore 的
// keystore.properties 或本机 ~/.gradle/gradle.properties（wechatAppId=wx...）。
val wechatAppId = (keystoreProps.getProperty("wechatAppId")
    ?: providers.gradleProperty("wechatAppId").orNull)
    ?.trim()
    ?.takeIf { it.matches(Regex("wx[0-9a-f]{16}")) }
    .orEmpty()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    // ⚠️ 包名与应用标识都是 **UPC Pocket**，不是上游的 com.zzupocket.app。
    // 这是两个不同的应用：改成自己的标识之后，上游的包不会被覆盖安装，
    // 上游的数据库/令牌文件也不会被本应用读到（两者数据语义完全不同）。
    namespace = "com.upcpocket.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.upcpocket.app"
        minSdk = 31
        targetSdk = 36
        // 独立应用标识的版本号从 1 开始（与上游的 97 无关，两者互不覆盖）。
        // 轻量版在 0.1.10（code 11）之上递增，保证覆盖安装不会触发降级。
        // 第二步在 0.2.0（code 12）之上继续递增；第三步 0.2.2 / code 14；本步继续递增。
        versionCode = 15
        versionName = "0.2.3-lightly-preview"
        buildConfigField("String", "WECHAT_APP_ID", "\"$wechatAppId\"")
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
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("upcpocket")
            } else {
                logger.lifecycle("⚠️ 未找到 keystore.properties，release 包将使用 debug 签名")
                signingConfig = signingConfigs.getByName("debug")
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

// Room 导出 schema，便于日后写迁移。ksp{} 是项目级扩展，必须放在 android{} 之外。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
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

    // 本地序列化：番茄钟快照（FocusPrefsStore）用 kotlinx.serialization 落 DataStore。
    // 公开空闲教室查询使用平台 HTTPS 客户端；不引入认证客户端或后台轮询依赖。
    implementation(libs.kotlinx.serialization.json)

    // 本地存储
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.wechat.sdk)

    // 已移除的上游依赖，当前功能不需要：
    //  · androidx.work（上游用它每 4 小时采样宿舍电量；本原型没有任何后台联网任务）
    //  · androidx.security.crypto（上游用它存 CAS 令牌；本原型不保存任何凭据）
    //  · okhttp / okhttp-logging（本原型不访问校园系统）

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // 真实 Room（内存 SQLite）+ Android 运行时。
    // 为什么需要：日程换类型、作息改动这类缺陷只在**真的读写数据库**时才暴露 ——
    // "旧行有没有被清掉"用纯函数测不出来（见 ScheduleRepositoryPlanKindTest）。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
