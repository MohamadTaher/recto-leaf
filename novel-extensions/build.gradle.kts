import com.android.build.api.dsl.ApplicationExtension

/**
 * Shared configuration for every novel extension.
 *
 * Extensions are standalone APKs whose classes are loaded into the app's process, and the two rules
 * below are what make that work. Both fail silently at runtime rather than at build time, so they
 * are declared once here instead of being copied into each extension.
 *
 * Configured from a `withId` callback because a `subprojects` block runs before its children are
 * evaluated, so the Android extension does not exist yet at that point.
 */
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension>("android") {
            defaultConfig {
                // The loader reads the extensions-lib version from `tachiyomix.extensionLib`,
                // falling back to everything before the last dot of versionName. Both say 1.6.
                versionCode = 1
                versionName = "1.6.0"
            }

            // One manifest for all of them; each extension supplies its own name and class through
            // the extensionName and extensionClass placeholders.
            sourceSets.named("main") {
                manifest.srcFile(rootProject.file("novel-extensions/extension.AndroidManifest.xml"))
            }

            buildTypes {
                named("release") { isMinifyEnabled = false }
            }
        }

        // Rule 1: never package the Kotlin runtime. ExtensionLoader's class loader resolves
        // child-first, so a bundled copy would give the extension its own
        // kotlin.coroutines.Continuation, the suspend signatures would stop matching the app's
        // NovelSource, and every call would fail with AbstractMethodError. Excluding it from the
        // runtime classpath keeps it on the compile classpath.
        configurations.configureEach {
            if (name.endsWith("RuntimeClasspath")) {
                exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
            }
        }

        // Rule 2: everything shared with the app is compileOnly, for the same class-identity
        // reason. The app supplies all of it at runtime.
        dependencies {
            // :novel-api brings :source-api, :core:common and okhttp with it, since all three are
            // on NovelHttpSource's public surface.
            add("compileOnly", project(":novel-api"))
            add("compileOnly", libs.jsoup)
            add("compileOnly", libs.kotlinx.serialization.json)
            // Not referenced directly, but needed to compile against HttpSource: its supertypes
            // carry rx Observables, and it resolves NetworkHelper through Injekt.
            add("compileOnly", libs.rxJava)
            add("compileOnly", libs.injekt)
        }
    }
}
