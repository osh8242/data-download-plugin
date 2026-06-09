package com.github.plugin.datadownload.ui

import com.github.plugin.datadownload.config.DownloadProfile
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel

class ProfileDialog(
    private val project: Project,
    private val profileToEdit: DownloadProfile? = null
) : DialogWrapper(project) {

    private val nameField = JBTextField(profileToEdit?.name ?: "")
    private val dataSourceCombo = ComboBox<LocalDataSource>()
    private val schemaField = JBTextField(profileToEdit?.schemaName ?: "")
    private val tableField = JBTextField(profileToEdit?.tableName ?: "")
    private val pathField = TextFieldWithBrowseButton()
    private val formatCombo = ComboBox(arrayOf("CSV", "XLSX"))

    init {
        title = if (profileToEdit == null) "Add Download Profile" else "Edit Download Profile"
        
        dataSourceCombo.setRenderer(object : com.intellij.ui.SimpleListCellRenderer<LocalDataSource>() {
            override fun customize(
                list: javax.swing.JList<out LocalDataSource>,
                value: LocalDataSource?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                text = value?.name ?: ""
            }
        })

        // Load data sources (sorted alphabetically by name)
        val dataSources = LocalDataSourceManager.getInstance(project).dataSources
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        dataSources.forEach { dataSourceCombo.addItem(it) }
        
        // Select current if editing
        if (profileToEdit != null) {
            val selected = dataSources.find { it.uniqueId == profileToEdit.dataSourceId }
            if (selected != null) {
                dataSourceCombo.selectedItem = selected
            }
            formatCombo.selectedItem = profileToEdit.exportFormat
        }
        
        pathField.text = profileToEdit?.downloadPath ?: ""
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Download Directory")
        pathField.addBrowseFolderListener(com.intellij.openapi.ui.TextBrowseFolderListener(descriptor, project))

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(5, 5, 5, 5)
            gridx = 0
            gridy = 0
        }

        // Row 0: Name
        panel.add(JBLabel("Profile Name:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(nameField, gbc)

        // Row 1: Data Source
        gbc.gridx = 0
        gbc.gridy++
        gbc.weightx = 0.0
        panel.add(JBLabel("Data Source:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(dataSourceCombo, gbc)

        // Row 2: Schema
        gbc.gridx = 0
        gbc.gridy++
        gbc.weightx = 0.0
        panel.add(JBLabel("Schema:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(schemaField, gbc)

        // Row 3: Table
        gbc.gridx = 0
        gbc.gridy++
        gbc.weightx = 0.0
        panel.add(JBLabel("Table:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(tableField, gbc)

        // Row 4: Download Path
        gbc.gridx = 0
        gbc.gridy++
        gbc.weightx = 0.0
        panel.add(JBLabel("Save Directory:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(pathField, gbc)

        // Row 5: Format
        gbc.gridx = 0
        gbc.gridy++
        gbc.weightx = 0.0
        panel.add(JBLabel("Format:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(formatCombo, gbc)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.trim().isEmpty()) {
            return ValidationInfo("Profile Name is required", nameField)
        }
        if (dataSourceCombo.selectedItem == null) {
            return ValidationInfo("Data Source is required", dataSourceCombo)
        }
        if (schemaField.text.trim().isEmpty()) {
            return ValidationInfo("Schema is required", schemaField)
        }
        if (tableField.text.trim().isEmpty()) {
            return ValidationInfo("Table is required", tableField)
        }
        if (pathField.text.trim().isEmpty()) {
            return ValidationInfo("Save Directory is required", pathField)
        }
        return null
    }

    fun getProfile(): DownloadProfile {
        val selectedDs = dataSourceCombo.selectedItem as LocalDataSource
        return (profileToEdit ?: DownloadProfile()).apply {
            name = nameField.text.trim()
            dataSourceId = selectedDs.uniqueId
            dataSourceName = selectedDs.name
            schemaName = schemaField.text.trim()
            tableName = tableField.text.trim()
            downloadPath = pathField.text.trim()
            exportFormat = formatCombo.selectedItem as String
        }
    }
}
