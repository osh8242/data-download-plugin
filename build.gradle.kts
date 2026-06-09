plugins {
    id("java")
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.github.plugin"
version = "1.0.3"

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
    implementation("org.dhatim:fastexcel:0.18.4")
    
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
    }
    
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}
