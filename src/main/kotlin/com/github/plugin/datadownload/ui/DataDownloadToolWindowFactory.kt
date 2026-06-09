package com.github.plugin.datadownload.ui

import com.github.plugin.datadownload.config.DataDownloadConfig
import com.github.plugin.datadownload.config.DownloadProfile
import com.github.plugin.datadownload.service.DownloadExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

class DataDownloadToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = DataDownloadPanel(project)
        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    class DataDownloadPanel(private val project: Project) : JPanel(BorderLayout()) {
        private val config = DataDownloadConfig.getInstance(project)
        private val tableModel = object : DefaultTableModel(
            arrayOf("Name", "DataSource", "Target Table", "Format"), 0
        ) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        private val table = JBTable(tableModel)

        init {
            refreshProfiles()
            
            // Create toolbar
            val actionGroup = DefaultActionGroup().apply {
                add(object : AnAction("Add Profile", "Add new download profile", AllIcons.General.Add) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val dialog = ProfileDialog(project)
                        if (dialog.showAndGet()) {
                            val newProfile = dialog.getProfile()
                            config.state.profiles.add(newProfile)
                            refreshProfiles()
                        }
                    }
                })
                add(object : AnAction("Edit Profile", "Edit selected download profile", AllIcons.Actions.EditSource) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val selectedRow = table.selectedRow
                        if (selectedRow >= 0) {
                            editProfileAt(selectedRow)
                        } else {
                            Messages.showWarningDialog(project, "Please select a profile to edit.", "No Profile Selected")
                        }
                    }
                })
                add(object : AnAction("Delete Profile", "Delete selected download profile", AllIcons.General.Remove) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val selectedRow = table.selectedRow
                        if (selectedRow >= 0) {
                            val profile = config.state.profiles[selectedRow]
                            val confirm = Messages.showYesNoDialog(
                                project,
                                "Are you sure you want to delete profile '${profile.name}'?",
                                "Confirm Delete",
                                Messages.getQuestionIcon()
                            )
                            if (confirm == Messages.YES) {
                                config.state.profiles.removeAt(selectedRow)
                                refreshProfiles()
                            }
                        } else {
                            Messages.showWarningDialog(project, "Please select a profile to delete.", "No Profile Selected")
                        }
                    }
                })
                addSeparator()
                add(object : AnAction("Run Download", "Run dataset download for selected profile", AllIcons.Actions.Execute) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val selectedRow = table.selectedRow
                        if (selectedRow >= 0) {
                            val profile = config.state.profiles[selectedRow]
                            DownloadExecutor.download(project, profile)
                        } else {
                            Messages.showWarningDialog(project, "Please select a profile to run.", "No Profile Selected")
                        }
                    }
                })
            }

            val toolbar = ActionManager.getInstance().createActionToolbar("DataDownloadToolbar", actionGroup, true)
            toolbar.targetComponent = this

            // Add double-click mouse listener to table for editing profiles
            table.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) {
                        val selectedRow = table.selectedRow
                        if (selectedRow >= 0) {
                            editProfileAt(selectedRow)
                        }
                    }
                }
            })

            add(toolbar.component, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        }

        private fun editProfileAt(selectedRow: Int) {
            if (selectedRow >= 0 && selectedRow < config.state.profiles.size) {
                val profile = config.state.profiles[selectedRow]
                val dialog = ProfileDialog(project, profile)
                if (dialog.showAndGet()) {
                    config.state.profiles[selectedRow] = dialog.getProfile()
                    refreshProfiles()
                }
            }
        }

        private fun refreshProfiles() {
            tableModel.rowCount = 0
            config.state.profiles.forEach { profile ->
                tableModel.addRow(arrayOf(
                    profile.name,
                    profile.dataSourceName,
                    "${profile.schemaName}.${profile.tableName}",
                    profile.exportFormat
                ))
            }
        }
    }
}
