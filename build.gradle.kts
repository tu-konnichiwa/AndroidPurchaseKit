// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.library) apply false
}

group = providers.gradleProperty("group").orElse("com.github.tu-konnichiwa").get()
version = providers.gradleProperty("version").orElse("1.0.0").get()
