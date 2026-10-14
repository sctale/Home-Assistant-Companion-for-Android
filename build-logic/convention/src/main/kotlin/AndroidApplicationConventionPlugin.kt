import com.android.build.api.dsl.ApplicationExtension
import io.homeassistant.companion.android.getPluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * Package namespace of the upstream sources. This is *not* the installed application id: it is only
 * the Kotlin/JVM package that the `R` class and the manifest's relative component names resolve
 * against, so it has to stay aligned with the source tree we inherited from upstream.
 */
private const val NAMESPACE = "io.homeassistant.companion.android"

/**
 * Installed application id of **this** build.
 *
 * Deliberately different from upstream's `io.homeassistant.companion.android`: this is an unofficial,
 * Google-services-free rebuild, and installing it under the official id would collide with (and be
 * offered as an upgrade over) the official app. Keeping our own id makes the two apps provably
 * independent on a device.
 */
private const val APPLICATION_ID = "dev.sctale.homeassistant.companion"

/**
 * A convention plugin that applies common configurations to Android application modules.
 * This centralizes configuration, preventing duplication across multiple modules.
 *
 * This plugin applies several Gradle plugins that are commonly used in all application modules,
 * including the [AndroidCommonConventionPlugin].
 *
 * After applying this plugin, the configured values can be overridden if necessary. However,
 * if extensive overrides are required, it may indicate that the configuration should be moved
 * out of this convention plugin.
 *
 * The application's `versionCode` can be set via the `VERSION_CODE` environment variable.
 *
 * A `release` signing configuration is automatically created. The keystore information can be
 * provided through the following environment variables:
 * - `KEYSTORE_PATH`: The path to the keystore file.
 * - `KEYSTORE_PASSWORD`: The password for the keystore.
 * - `KEYSTORE_ALIAS`: The alias for the key within the keystore.
 * - `KEYSTORE_ALIAS_PASSWORD`: The password for the key alias.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = libs.plugins.android.application.getPluginId())
            apply(plugin = libs.plugins.ksp.getPluginId())
            apply(plugin = libs.plugins.hilt.getPluginId())
            apply(plugin = libs.plugins.aboutlibraries.getPluginId())
            apply(plugin = libs.plugins.google.services.getPluginId())
            AndroidCommonConventionPlugin().apply(target)
            AndroidComposeConventionPlugin().apply(target)

            extensions.configure<ApplicationExtension> {
                namespace = NAMESPACE

                defaultConfig {
                    applicationId = APPLICATION_ID
                    targetSdk = libs.versions.androidSdk.target.get().toInt()

                    versionName = project.version.toString()
                    versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

                    val noStrictMode = project.findProperty("noStrictMode")?.toString()?.ifEmpty { "true" }
                        ?.toBoolean() ?: false
                    buildConfigField("Boolean", "NO_STRICT_MODE", noStrictMode.toString())
                }

                buildFeatures {
                    viewBinding = true
                }

                val rootDirectory = target.rootDir
                signingConfigs {
                    create("release") {
                        // Relative KEYSTORE_PATH values resolve against the repository root, not the
                        // module: this plugin is applied by :app, :wear and :automotive, so a module
                        // relative path would point each of them at a different file and a single
                        // root-level keystore/ directory would only work for one of them.
                        storeFile = rootDirectory.resolve(System.getenv("KEYSTORE_PATH") ?: "release_keystore.keystore")
                        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                        keyAlias = System.getenv("KEYSTORE_ALIAS") ?: ""
                        keyPassword = System.getenv("KEYSTORE_ALIAS_PASSWORD") ?: ""
                        enableV1Signing = true
                        enableV2Signing = true
                    }
                }

                buildTypes {
                    named("debug").configure {
                        applicationIdSuffix = ".debug"
                    }
                    named("release").configure {
                        isDebuggable = false
                        isJniDebuggable = false
                        signingConfig = signingConfigs.getByName("release")
                    }
                }
            }
        }
    }
}
