import com.android.builder.internal.packaging.IncrementalPackager
import com.android.tools.build.apkzlib.sign.SigningExtension
import com.android.tools.build.apkzlib.sign.SigningOptions
import com.android.tools.build.apkzlib.zfile.ZFiles
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.ZFileOptions
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Properties

plugins {
    id("com.android.application")
}

// --- Version: driven by the release tag (-PappVersionName=v1.2.3), else a dev default. ---
// versionCode is derived from the name (major*10000 + minor*100 + patch) so a single tag
// bumps both and stays monotonic across releases; override with -PappVersionCode if ever needed.
val appVersionName: String = (findProperty("appVersionName") as String?)
    ?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.toIntOrNull()
    ?: appVersionName.split('.', '-', '+').mapNotNull { it.toIntOrNull() }.let {
        ((it.getOrElse(0) { 0 } * 10000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 }).coerceAtLeast(1)
    }

// --- Release signing: keystore.properties (local) or env vars (CI); falls back to debug key. ---
fun resolveStore(path: String): File = File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)
val releaseStorePath: String? = signingValue("storeFile", "KEYSTORE_FILE")
val hasReleaseKey: Boolean = releaseStorePath != null && resolveStore(releaseStorePath).exists()

android {
    namespace = "io.github.mexus.gcallrecorder"
    defaultConfig {
        applicationId = "io.github.mexus.gcallrecorder"
        versionCode = appVersionCode
        versionName = appVersionName
        externalNativeBuild {
            ndkBuild {
                abiFilters += listOf("arm64-v8a")
                abiFilters += listOf("armeabi-v7a", "x86", "x86_64")
                arguments += "-j${Runtime.getRuntime().availableProcessors()}"
            }
        }
    }
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = resolveStore(releaseStorePath!!)
            storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles("proguard-rules.pro")
            // delMetadata re-signs the final APK (v2-only); this keeps intermediates consistent.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs["debug"]
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
    packaging {
        resources {
            // The Xposed metadata must survive; strip only build noise (keep META-INF/services etc.)
            merges += "META-INF/xposed/**"
            excludes += listOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime:2.9.1")
    testImplementation("junit:junit:4.13.2")
}

val optimizeReleaseRes by tasks.registering(Exec::class) {
    val aapt2 = project.androidComponents.sdkComponents.aapt2.get().executable.get().toString()
    val zip = Paths.get(
        project.layout.buildDirectory.get().toString(), "intermediates",
        "optimized_processed_res", "release", "optimizeReleaseResources",
        "resources-release-optimize.ap_"
    )
    val optimized = zip.resolveSibling("optimized")
    commandLine(
        aapt2, "optimize", "--collapse-resource-names",
        "--enable-sparse-encoding", "-o", optimized, zip
    )

    doLast {
        Files.delete(zip)
        Files.move(optimized, zip)
    }
}

val delMetadata by tasks.registering {
    // Sign the shipped APK with the release key when configured, else the debug key.
    val sign = android.signingConfigs.findByName("release") ?: android.signingConfigs["debug"]
    val minSdk = android.defaultConfig.minSdk!!
    val files = tasks.named("packageRelease").get().outputs.files
    doLast {
        val options = ZFileOptions().apply {
            alignmentRule = AlignmentRules.constantForSuffix(".so", 16 * 1024)
            noTimestamps = true
            autoSortFiles = true
        }
        val apk = files.asFileTree.filter { it.name.endsWith(".apk") }.singleFile
        ZFiles.apk(apk, options).use { zFile ->
            val keyStore = KeyStore.getInstance(sign.storeType ?: KeyStore.getDefaultType())
            FileInputStream(sign.storeFile!!).use {
                keyStore.load(it, sign.storePassword!!.toCharArray())
            }
            val protParam = KeyStore.PasswordProtection(sign.keyPassword!!.toCharArray())
            val entry = keyStore.getEntry(sign.keyAlias!!, protParam)
            val privateKey = entry as KeyStore.PrivateKeyEntry
            val signingOptions = SigningOptions.builder()
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setKey(privateKey.privateKey)
                .setCertificates(privateKey.certificate as X509Certificate)
                .setValidation(SigningOptions.Validation.ASSUME_INVALID)
                .build()
            SigningExtension(signingOptions).register(zFile)
            zFile.get(IncrementalPackager.APP_METADATA_ENTRY_PATH)?.delete()
        }
    }
}

tasks.configureEach {
    if (name == "optimizeReleaseResources") {
        finalizedBy(optimizeReleaseRes)
    }
    if (name == "packageRelease") {
        finalizedBy(delMetadata)
    }
}
