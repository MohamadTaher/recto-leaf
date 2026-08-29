plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "leaf.novel.api"
}

dependencies {
    // All `api`, not `implementation`: these types appear in NovelSource's and NovelHttpSource's
    // public surface, so every consumer needs them. Declaring them here means an extension depends
    // on this module alone.
    api(projects.sourceApi)
    api(projects.core.common)
    api(libs.okhttp.core)
}
