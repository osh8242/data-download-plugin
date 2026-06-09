package com.github.plugin.datadownload.config

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import java.util.UUID

data class DownloadProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var dataSourceId: String = "",
    var dataSourceName: String = "",
    var schemaName: String = "",
    var tableName: String = "",
    var downloadPath: String = "",
    var exportFormat: String = "CSV"
)

@State(
    name = "com.github.plugin.datadownload.config.DataDownloadConfig",
    storages = [Storage("data_download_profiles.xml")]
)
@Service(Service.Level.PROJECT)
class DataDownloadConfig : PersistentStateComponent<DataDownloadConfig.State> {

    class State {
        var profiles: MutableList<DownloadProfile> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): DataDownloadConfig =
            project.getService(DataDownloadConfig::class.java)
    }
}
