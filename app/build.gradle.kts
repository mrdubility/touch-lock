import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------- 正式签名 ----------
// 密钥材料一律不入库（.gitignore 已忽略 /keystore/），两条读取通道：
//   CI：workflow 把 GitHub Secrets 的 KEYSTORE_BASE64 还原成文件，并注入 KEYSTORE_FILE / KEYSTORE_PASSWORD
//   本地：读取同样被 gitignore 忽略的 keystore/keystore.properties
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(envName: String, propName: String): String? =
    (providers.environmentVariable(envName).orNull ?: keystoreProps.getProperty(propName))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

// 相对路径按仓库根目录解析，绝对路径（CI 临时目录）原样使用
fun resolveKeystore(raw: String): File = if (File(raw).isAbsolute) File(raw) else rootProject.file(raw)

val releaseKeystoreFile: File? = (secret("KEYSTORE_FILE", "storeFile") ?: "keystore/touch-lock-release.jks")
    .let { resolveKeystore(it) }
    .takeIf { it.isFile }

val releaseStorePassword: String? = secret("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias: String = secret("KEY_ALIAS", "keyAlias") ?: "touch-lock"

if (releaseKeystoreFile == null || releaseStorePassword == null) {
    if (providers.environmentVariable("KEYSTORE_FILE").orNull?.isNotBlank() == true) {
        // CI 已显式指定密钥库却拿不到可用材料：宁可构建失败，也不要静默产出未签名 APK
        throw GradleException("release 签名材料不可用：KEYSTORE_FILE 指向的文件不存在或 KEYSTORE_PASSWORD 为空")
    }
    logger.lifecycle("touch-lock: 未找到 release 签名材料，assembleRelease 将产出未签名 APK（CI 请先配置 KEYSTORE_BASE64 / KEYSTORE_PASSWORD）")
}

android {
    namespace = "com.touchlock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.touchlock"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        val ks = releaseKeystoreFile
        val pw = releaseStorePassword
        if (ks != null && pw != null) {
            create("release") {
                storeFile = ks
                storePassword = pw
                keyAlias = releaseKeyAlias
                // 生成密钥时未单独设置 keypass，两者相同
                keyPassword = pw
            }
        }
    }

    buildTypes {
        release {
            // 简单起见不开启代码混淆，直接产出可安装 release APK
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 缺签名材料时为 null，此时产出未签名 APK（上面的日志已给出提示）
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 依赖最小化：仅使用平台 API + Kotlin stdlib（由 Kotlin 插件自动引入），不引入 AndroidX/Material，
    // 以避免版本冲突、最大化构建成功率。
}
