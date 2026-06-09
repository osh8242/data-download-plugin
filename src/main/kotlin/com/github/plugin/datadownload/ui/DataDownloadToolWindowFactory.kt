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
        private var isRefreshing = false
        private val tableModel = object : DefaultTableModel(
            arrayOf("Name", "DataSource", "Target Table", "Format"), 0
        ) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true

            override fun setValueAt(aValue: Any?, row: Int, column: Int) {
                if (isRefreshing) return
                super.setValueAt(aValue, row, column)
                if (row >= 0 && row < config.state.profiles.size) {
                    val profile = config.state.profiles[row]
                    when (column) {
                        0 -> {
                            val newName = aValue?.toString()?.trim() ?: ""
                            if (newName.isNotEmpty()) {
                                profile.name = newName
                            }
                        }
                        1 -> {
                            val ds = aValue as? com.intellij.database.dataSource.LocalDataSource
                            if (ds != null) {
                                profile.dataSourceId = ds.uniqueId
                                profile.dataSourceName = ds.name
                                super.setValueAt(ds.name, row, column)
                            }
                        }
                        2 -> {
                            val fullTarget = aValue?.toString()?.trim() ?: ""
                            if (fullTarget.isNotEmpty()) {
                                val parts = fullTarget.split(".")
                                if (parts.size >= 2) {
                                    profile.schemaName = parts[0].trim()
                                    profile.tableName = parts.drop(1).joinToString(".").trim()
                                } else {
                                    profile.schemaName = ""
                                    profile.tableName = fullTarget
                                }
                                super.setValueAt(
                                    if (profile.schemaName.isNotEmpty()) "${profile.schemaName}.${profile.tableName}" else profile.tableName,
                                    row,
                                    column
                                )
                            }
                        }
                        3 -> {
                            val format = aValue?.toString()?.trim() ?: "CSV"
                            profile.exportFormat = format
                            super.setValueAt(format, row, column)
                        }
                    }
                }
            }
        }
        private val table = JBTable(tableModel)

        init {
            table.putClientProperty("terminateEditOnFocusLost", java.lang.Boolean.TRUE)
            refreshProfiles()
            
            // Configure DataSource ComboBox Editor
            val dsManager = com.intellij.database.dataSource.LocalDataSourceManager.getInstance(project)
            val dsCombo = com.intellij.openapi.ui.ComboBox(dsManager.dataSources.toTypedArray())
            dsCombo.setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it?.name ?: "" })
            table.columnModel.getColumn(1).cellEditor = javax.swing.DefaultCellEditor(dsCombo)

            // Configure Format ComboBox Editor
            val formatCombo = com.intellij.openapi.ui.ComboBox(arrayOf("CSV", "XLSX"))
            table.columnModel.getColumn(3).cellEditor = javax.swing.DefaultCellEditor(formatCombo)

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
                add(object : AnAction("Edit Profile", "Edit selected download profile detail", AllIcons.Actions.EditSource) {
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

            // Create popup menu for right click
            val popupActionGroup = DefaultActionGroup().apply {
                add(object : AnAction("Run Download", "Run dataset download for selected profile", AllIcons.Actions.Execute) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val selectedRow = table.selectedRow
                        if (selectedRow >= 0) {
                            val profile = config.state.profiles[selectedRow]
                            DownloadExecutor.download(project, profile)
                        }
                    }
                })
                add(object : AnAction("Edit Detail...", "Edit selected download profile detail", AllIcons.Actions.EditSource) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val selectedRow = table.selectedRow
                        if (selectedRow >= 0) {
                            editProfileAt(selectedRow)
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
                        }
                    }
                })
            }
            val popupMenu = ActionManager.getInstance().createActionPopupMenu("DataDownloadTablePopup", popupActionGroup)

            // Add right click popup listener
            table.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    handlePopup(e)
                }
                override fun mouseReleased(e: java.awt.event.MouseEvent) {
                    handlePopup(e)
                }
                private fun handlePopup(e: java.awt.event.MouseEvent) {
                    if (e.isPopupTrigger) {
                        val row = table.rowAtPoint(e.point)
                        if (row >= 0) {
                            table.setRowSelectionInterval(row, row)
                            popupMenu.component.show(table, e.x, e.y)
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
            isRefreshing = true
            try {
                tableModel.rowCount = 0
                config.state.profiles.forEach { profile ->
                    val targetTableString = if (profile.schemaName.isNotEmpty()) {
                        "${profile.schemaName}.${profile.tableName}"
                    } else {
                        profile.tableName
                    }
                    tableModel.addRow(arrayOf(
                        profile.name,
                        profile.dataSourceName,
                        targetTableString,
                        profile.exportFormat
                    ))
                }
            } finally {
                isRefreshing = false
            }
        }
    }
}
