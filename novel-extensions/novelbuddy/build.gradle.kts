plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "leaf.novel.extension.en.novelbuddy"

    defaultConfig {
        applicationId = "leaf.novel.extension.en.novelbuddy"

        manifestPlaceholders["extensionName"] = "NovelBuddy"
        manifestPlaceholders["extensionClass"] = ".NovelBuddy"
    }
}
