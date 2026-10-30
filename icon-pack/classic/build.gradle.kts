plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kunzisoft.keepass.icon.classic"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    resourcePrefix = "classic_"
}
