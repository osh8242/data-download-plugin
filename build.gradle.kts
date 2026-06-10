plugins {
    id("java")
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.1.0"
    id("org.jetbrains.changelog") version "2.2.0"
}

group = "com.github.plugin"
version = "1.0.6"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // CSV writer
    implementation("org.apache.commons:commons-csv:1.10.0")
    
    // fastexcel for lightweight CSV to Excel conversion
    implementation("org.dhatim:fastexcel:0.20.1")
    
    intellijPlatform {
        // Target DataGrip
        datagrip("2024.1")
        
        // Target Database Tools plugin
        bundledPlugin("com.intellij.database")
        
        instrumentationTools()
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        name.set("Dataset Downloader")
        id.set("com.github.plugin.datadownload")
        version.set(project.version.toString())
        
        ideaVersion {
            sinceBuild.set("233")
            untilBuild.set("263.*")
        }
        
        changeNotes.set(provider {
            changelog.renderItem(
                changelog.get(project.version.toString()).withHeader(false).withEmptySections(false),
                org.jetbrains.changelog.Changelog.OutputType.HTML
            )
        })
    }
    
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}
