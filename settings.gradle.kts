pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ClickControl"

include(":core")
include(":desktop")

val skipAndroid = providers.gradleProperty("skipAndroid").orNull?.toBoolean() == true
    || gradle.startParameter.projectProperties["skipAndroid"]?.toBoolean() == true
    || System.getProperty("skipAndroid")?.toBoolean() == true

fun hasAndroidSdk(): Boolean {
    if (skipAndroid) return false

    // Check local.properties
    val localProps = File(rootDir, "local.properties")
    if (localProps.exists()) {
        try {
            val properties = java.util.Properties()
            localProps.inputStream().use { properties.load(it) }
            val sdkDir = properties.getProperty("sdk.dir")
            if (!sdkDir.isNullOrBlank()) {
                val dir = File(sdkDir)
                if (File(dir, "platforms").isDirectory) return true
            }
        } catch (_: Exception) {
        }
    }

    // Check ANDROID_HOME or ANDROID_SDK_ROOT
    val envSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!envSdk.isNullOrBlank()) {
        val dir = File(envSdk)
        if (File(dir, "platforms").isDirectory) return true
    }

    return false
}

if (hasAndroidSdk()) {
    include(":app")
}
