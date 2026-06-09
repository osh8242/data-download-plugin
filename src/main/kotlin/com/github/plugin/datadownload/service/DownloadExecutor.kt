package com.github.plugin.datadownload.service

import com.github.plugin.datadownload.config.DownloadProfile
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

object DownloadExecutor {
    private val LOG = Logger.getInstance(DownloadExecutor::class.java)

    fun download(project: Project, profile: DownloadProfile) {
        LOG.info("DataDownloadPlugin: Starting download process for profile [${profile.name}]")
        
        val dataSourceManager = LocalDataSourceManager.getInstance(project)
        val dataSource = dataSourceManager.dataSources.find { it.uniqueId == profile.dataSourceId }
        
        if (dataSource == null) {
            LOG.error("DataDownloadPlugin: Data Source not found for ID [${profile.dataSourceId}]")
            Messages.showErrorDialog(project, "Data Source not found for this profile.", "Error")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading Dataset: ${profile.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                
                val dir = File(profile.downloadPath)
                if (!dir.exists()) {
                    LOG.info("DataDownloadPlugin: Creating directory [${dir.absolutePath}]")
                    dir.mkdirs()
                }

                val isXlsx = profile.exportFormat.equals("XLSX", ignoreCase = true)
                val csvFile = if (isXlsx) {
                    File(dir, "${profile.tableName}_temp.csv")
                } else {
                    File(dir, "${profile.tableName}.csv")
                }
                
                val sql = "SELECT * FROM ${profile.schemaName}.${profile.tableName}"
                LOG.info("DataDownloadPlugin: Target SQL query: [$sql]")

                try {
                    indicator.text = "Connecting to database..."
                    LOG.info("DataDownloadPlugin: Establishing database connection to data source [${dataSource.name}]")
                    
                    val connectionManager = DatabaseConnectionManager.getInstance()
                    val builder = connectionManager.build(project, dataSource)
                    val builderClass = builder.javaClass
                    
                    // Check for createBlocking() method introduced in 2024.1
                    val createBlockingMethod = try {
                        builderClass.getMethod("createBlocking")
                    } catch (e: NoSuchMethodException) {
                        null
                    }
                    
                    @Suppress("UNCHECKED_CAST")
                    val connectionRef = if (createBlockingMethod != null) {
                        createBlockingMethod.invoke(builder) as? com.intellij.database.util.GuardedRef<com.intellij.database.dataSource.DatabaseConnection>
                    } else {
                        val createMethod = builderClass.getMethod("create")
                        createMethod.invoke(builder) as? com.intellij.database.util.GuardedRef<com.intellij.database.dataSource.DatabaseConnection>
                    }
                    
                    if (connectionRef == null) {
                        throw IllegalStateException("Failed to create database connection reference (connectionRef is null)")
                    }
                    
                    connectionRef.use { ref ->
                        val dbConnection = ref.get()
                        val remoteConnection = dbConnection.getRemoteConnection()
                        
                        LOG.info("DataDownloadPlugin: Connection established. Executing query...")
                        indicator.text = "Executing query and writing to CSV..."
                        
                        var stmt: com.intellij.database.remote.jdbc.RemotePreparedStatement? = null
                        var rs: com.intellij.database.remote.jdbc.RemoteResultSet? = null
                        val originalAutoCommit = try { remoteConnection.autoCommit } catch (e: Exception) { true }
                        try {
                            try {
                                remoteConnection.autoCommit = false
                                LOG.info("DataDownloadPlugin: Set autoCommit to false for streaming")
                            } catch (e: Exception) {
                                LOG.warn("DataDownloadPlugin: Failed to set autoCommit to false: ${e.message}")
                            }

                            stmt = remoteConnection.prepareStatement(sql)
                            try {
                                stmt.fetchSize = 10000
                                LOG.info("DataDownloadPlugin: Set fetchSize to 10000")
                            } catch (e: Exception) {
                                LOG.warn("DataDownloadPlugin: Failed to set fetchSize: ${e.message}")
                            }

                            rs = stmt.executeQuery()
                            val meta = rs.metaData
                            val columnCount = meta.columnCount
                            LOG.info("DataDownloadPlugin: Query executed successfully. Column count: $columnCount")

                            FileWriter(csvFile, StandardCharsets.UTF_8).use { writer ->
                                if (!isXlsx) {
                                    writer.write("\uFEFF") // Write UTF-8 BOM for CSV format to support Excel natively
                                }
                                CSVPrinter(writer, CSVFormat.DEFAULT).use { csvPrinter ->
                                    // Write headers
                                    val headers = mutableListOf<String>()
                                    for (i in 1..columnCount) {
                                        headers.add(meta.getColumnName(i))
                                    }
                                    csvPrinter.printRecord(headers)

                                    // Write rows
                                    var rowCount = 0
                                    while (rs.next()) {
                                        if (indicator.isCanceled) {
                                            LOG.warn("DataDownloadPlugin: Download canceled by user at row $rowCount")
                                            break
                                        }
                                        val row = mutableListOf<Any?>()
                                        for (i in 1..columnCount) {
                                            row.add(rs.getObject(i))
                                        }
                                        csvPrinter.printRecord(row)
                                        rowCount++
                                        
                                        if (rowCount % 1000 == 0) {
                                            indicator.text = "Writing rows to CSV ($rowCount written)..."
                                            if (rowCount % 10000 == 0) {
                                                LOG.info("DataDownloadPlugin: Written $rowCount rows to CSV...")
                                            }
                                        }
                                    }
                                    LOG.info("DataDownloadPlugin: Finished writing CSV. Total rows written: $rowCount")
                                }
                            }

                            try {
                                if (!remoteConnection.autoCommit) {
                                    remoteConnection.commit()
                                    LOG.info("DataDownloadPlugin: Transaction committed successfully.")
                                }
                            } catch (e: Exception) {
                                LOG.warn("DataDownloadPlugin: Failed to commit transaction: ${e.message}")
                            }

                        } catch (t: Throwable) {
                            try {
                                if (!remoteConnection.autoCommit) {
                                    remoteConnection.rollback()
                                    LOG.info("DataDownloadPlugin: Transaction rolled back due to error.")
                                }
                            } catch (e: Exception) {
                                LOG.warn("DataDownloadPlugin: Failed to rollback transaction: ${e.message}")
                            }
                            throw t
                        } finally {
                            rs?.close()
                            stmt?.close()
                            try {
                                if (remoteConnection.autoCommit != originalAutoCommit) {
                                    remoteConnection.autoCommit = originalAutoCommit
                                    LOG.info("DataDownloadPlugin: Restored original autoCommit value: $originalAutoCommit")
                                }
                            } catch (e: Exception) {
                                LOG.warn("DataDownloadPlugin: Failed to restore autoCommit: ${e.message}")
                            }
                        }
                    }
                    
                    if (indicator.isCanceled) {
                        if (csvFile.exists()) csvFile.delete()
                        showNotification(project, "Download Canceled", "The download operation was canceled.", NotificationType.WARNING)
                        return
                    }

                    if (isXlsx) {
                        indicator.text = "Converting CSV to XLSX..."
                        LOG.info("DataDownloadPlugin: Converting temp CSV to Excel...")
                        val xlsxFile = File(dir, "${profile.tableName}.xlsx")
                        try {
                            convertCsvToXlsx(csvFile, xlsxFile)
                            csvFile.delete() // Remove the intermediate CSV file
                            LOG.info("DataDownloadPlugin: XLSX conversion completed: [${xlsxFile.absolutePath}]")
                            showNotification(project, "Download Complete", "Dataset successfully downloaded as Excel to: ${xlsxFile.absolutePath}", NotificationType.INFORMATION, xlsxFile)
                        } catch (t: Throwable) {
                            LOG.error("DataDownloadPlugin: Excel conversion failed: ${t.message}", t)
                            if (xlsxFile.exists()) xlsxFile.delete()
                            if (csvFile.exists()) csvFile.delete()
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(project, "Excel conversion failed: ${t.message}", "Error")
                            }
                        }
                    } else {
                        LOG.info("DataDownloadPlugin: CSV download completed: [${csvFile.absolutePath}]")
                        showNotification(project, "Download Complete", "Dataset successfully downloaded as CSV to: ${csvFile.absolutePath}", NotificationType.INFORMATION, csvFile)
                    }

                } catch (t: Throwable) {
                    LOG.error("DataDownloadPlugin: Database connection or query execution failed", t)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Failed to download dataset: ${t.message}", "Error")
                    }
                    if (csvFile.exists()) csvFile.delete()
                }
            }
        })
    }

    private fun convertCsvToXlsx(csvFile: File, xlsxFile: File) {
        val workbook = SXSSFWorkbook(100)
        val sheet = workbook.createSheet("Dataset")
        
        FileReader(csvFile, StandardCharsets.UTF_8).use { reader ->
            val parser = CSVParser(reader, CSVFormat.DEFAULT)
            var rowNum = 0
            for (csvRecord in parser) {
                val row = sheet.createRow(rowNum++)
                for (i in 0 until csvRecord.size()) {
                    val cell = row.createCell(i)
                    cell.setCellValue(csvRecord.get(i))
                }
            }
            parser.close()
        }

        FileOutputStream(xlsxFile).use { fos ->
            workbook.write(fos)
        }
        workbook.dispose()
    }

    private fun showNotification(project: Project, title: String, content: String, type: NotificationType, file: File? = null) {
        val notification = Notification("Data Downloader Group", title, content, type)
        if (file != null && file.exists()) {
            notification.addAction(object : com.intellij.openapi.actionSystem.AnAction("Open Folder") {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    com.intellij.ide.actions.RevealFileAction.openFile(file)
                }
            })
        }
        Notifications.Bus.notify(notification, project)
    }
}
