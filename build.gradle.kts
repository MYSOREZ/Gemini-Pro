// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// Align JavaPoet version to avoid Hilt AggregateDeps JavaPoet canonicalName() crash
allprojects {
    configurations.all {
        resolutionStrategy.force("com.squareup:javapoet:1.13.0")
    }
}
