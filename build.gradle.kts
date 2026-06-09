plugins {
    id("java")
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.github.plugin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // CSV writer
    implementation("org.apache.commons:commons-csv:1.10.0")
    
    // Apache POI for CSV to Excel conversion
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    
    intellijPlatform {
        // Target DataGrip
        datagrip("2023.3")
        
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
            untilBuild.set("243.*")
        }
    }
    
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}
