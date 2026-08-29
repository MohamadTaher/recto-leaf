plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "leaf.novel.extension.en.freewebnovel"

    defaultConfig {
        applicationId = "leaf.novel.extension.en.freewebnovel"

        manifestPlaceholders["extensionName"] = "FreeWebNovel"
        manifestPlaceholders["extensionClass"] = ".FreeWebNovel"
    }
}
