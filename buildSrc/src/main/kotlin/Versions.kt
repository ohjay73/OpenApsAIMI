import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

@Suppress("ConstPropertyName")
object Versions {

    const val appVersion = "4.0.0.0-dev.AIMI.120426"
    const val versionCode = 1500

    const val compileSdk = 36  // Back to original - SDK 34 breaks omnipod and other modules
    const val minSdk = 31
    const val targetSdk = 34 // Health Connect / Android 14 compatibility
    const val wearMinSdk = 30
    const val wearTargetSdk = 30

    val javaVersion = JavaVersion.VERSION_21
    val jvmTarget = JvmTarget.JVM_21
    const val jacoco = "0.8.11"
}
