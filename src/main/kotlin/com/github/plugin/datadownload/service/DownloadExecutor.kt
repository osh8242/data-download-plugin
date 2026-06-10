package com.github.plugin.datadownload.service

import com.github.plugin.datadownload.config.DownloadProfile
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.dhatim.fastexcel.Workbook
import java.io.*
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

        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, "Downloading Dataset: ${profile.name}", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    val dir = File(profile.downloadPath)
                    if (!dir.exists()) {
                        LOG.info("DataDownloadPlugin: Creating directory [${dir.absolutePath}]")
                        dir.mkdirs()
                    }

                    val isXlsx = profile.exportFormat.equals("XLSX", ignoreCase = true)
                    val csvFile = if (!isXlsx) {
                        File(dir, "${profile.tableName}.csv")
                    } else {
                        null
                    }

                    val sql = "SELECT * FROM ${profile.schemaName}.${profile.tableName}"
                    LOG.info("DataDownloadPlugin: Target SQL query: [$sql]")

                    try {
                        indicator.text = "Connecting to database..."
                        LOG.info("DataDownloadPlugin: Establishing database connection to data source [${dataSource.name}]")

                        val connectionManager = try {
                            // 1) Attempt Java static method directly (2023.3 legacy format)
                            val getInstanceMethod = DatabaseConnectionManager::class.java.getMethod("getInstance")
                            getInstanceMethod.invoke(null) as DatabaseConnectionManager
                        } catch (e: Exception) {
                            try {
                                // 2) Fallback to Kotlin Companion class resolution (2024.1+ format)
                                val companionField = DatabaseConnectionManager::class.java.getField("Companion")
                                val companionObj = companionField.get(null)
                                val getInstanceMethod = companionObj.javaClass.getMethod("getInstance")
                                getInstanceMethod.invoke(companionObj) as DatabaseConnectionManager
                            } catch (ex: Exception) {
                                throw IllegalStateException(
                                    "Failed to resolve DatabaseConnectionManager instance via reflection",
                                    ex
                                )
                            }
                        }
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
                            val remoteConnection = dbConnection.remoteConnection

                            LOG.info("DataDownloadPlugin: Connection established. Executing query...")

                            var stmt: com.intellij.database.remote.jdbc.RemotePreparedStatement? = null
                            var rs: com.intellij.database.remote.jdbc.RemoteResultSet? = null
                            val originalAutoCommit = try {
                                remoteConnection.autoCommit
                            } catch (e: Exception) {
                                true
                            }
                            try {
                                try {
                                    remoteConnection.autoCommit = false
                                    LOG.info("DataDownloadPlugin: Set autoCommit to false for streaming")
                                } catch (e: Exception) {
                                    LOG.warn("DataDownloadPlugin: Failed to set autoCommit to false: ${e.message}")
                                }

                                val dbProductName = try {
                                    remoteConnection.metaData.databaseProductName.lowercase()
                                } catch (e: Exception) {
                                    ""
                                }
                                stmt = remoteConnection.prepareStatement(sql)
                                try {
                                    if (dbProductName.contains("mysql") || dbProductName.contains("mariadb")) {
                                        stmt.fetchSize = Integer.MIN_VALUE
                                        LOG.info("DataDownloadPlugin: Set fetchSize to Integer.MIN_VALUE for MySQL/MariaDB streaming")
                                    } else {
                                        stmt.fetchSize = 10000
                                        LOG.info("DataDownloadPlugin: Set fetchSize to 10000")
                                    }
                                } catch (e: Exception) {
                                    LOG.warn("DataDownloadPlugin: Failed to set fetchSize: ${e.message}")
                                }

                                rs = stmt.executeQuery()
                                val meta = rs.metaData
                                val columnCount = meta.columnCount

                                // Cache column types to avoid repetitive getType/instanceof checks
                                val columnTypes = IntArray(columnCount + 1)
                                val columnNames = Array(columnCount + 1) { "" }
                                val isNullable = BooleanArray(columnCount + 1)
                                for (i in 1..columnCount) {
                                    columnTypes[i] = meta.getColumnType(i)
                                    columnNames[i] = meta.getColumnName(i)
                                    isNullable[i] = meta.isNullable(i) != java.sql.ResultSetMetaData.columnNoNulls
                                }
                                LOG.info("DataDownloadPlugin: Query executed successfully. Column count: $columnCount")

                                val POISON_PILL = emptyArray<Any?>()
                                val emptyQueue = java.util.concurrent.ArrayBlockingQueue<Array<Any?>>(5000)
                                val dataQueue = java.util.concurrent.ArrayBlockingQueue<Array<Any?>>(5000)
                                for (i in 0 until 5000) {
                                    emptyQueue.put(Array<Any?>(columnCount) { null })
                                }

                                val consumerLatch = java.util.concurrent.CountDownLatch(1)
                                val sharedError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

                                ApplicationManager.getApplication().executeOnPooledThread {
                                    try {
                                        if (isXlsx) {
                                            indicator.text = "Executing query and writing to XLSX..."
                                            val xlsxFile = File(dir, "${profile.tableName}.xlsx")
                                            FileOutputStream(xlsxFile).use { fos ->
                                                BufferedOutputStream(fos, 1024 * 1024).use { bos ->
                                                    val workbook = Workbook(bos, "DatasetDownloader", "1.0")
                                                    var sheetIndex = 1
                                                    var currentSheet = workbook.newWorksheet("data")

                                                    for (i in 1..columnCount) {
                                                        currentSheet.value(0, i - 1, columnNames[i])
                                                    }

                                                    var totalRowCount = 0
                                                    var sheetRowNum = 1

                                                    while (true) {
                                                        val row = dataQueue.take()
                                                        if (row === POISON_PILL) {
                                                            if (sharedError.get() != null) throw sharedError.get()!!
                                                            break
                                                        }
                                                        if (indicator.isCanceled) break

                                                        if (sheetRowNum >= 1000000) {
                                                            sheetIndex++
                                                            currentSheet = workbook.newWorksheet("data_$sheetIndex")
                                                            for (i in 1..columnCount) {
                                                                currentSheet.value(0, i - 1, columnNames[i])
                                                            }
                                                            sheetRowNum = 1
                                                        }

                                                        for (i in 0 until columnCount) {
                                                            val value = row[i]
                                                            if (value != null) {
                                                                when (columnTypes[i + 1]) {
                                                                    java.sql.Types.INTEGER, java.sql.Types.TINYINT, java.sql.Types.SMALLINT,
                                                                    java.sql.Types.BIGINT, java.sql.Types.FLOAT, java.sql.Types.REAL,
                                                                    java.sql.Types.DOUBLE, java.sql.Types.NUMERIC, java.sql.Types.DECIMAL ->
                                                                        currentSheet.value(sheetRowNum, i, value as Number)

                                                                    java.sql.Types.BOOLEAN, java.sql.Types.BIT ->
                                                                        currentSheet.value(sheetRowNum, i, value as Boolean)

                                                                    java.sql.Types.DATE ->
                                                                        currentSheet.value(sheetRowNum, i, value as java.time.LocalDate)

                                                                    java.sql.Types.TIMESTAMP ->
                                                                        currentSheet.value(sheetRowNum, i, value as java.time.LocalDateTime)

                                                                    else -> {
                                                                        if (value is String) currentSheet.value(sheetRowNum, i, value)
                                                                        else currentSheet.value(sheetRowNum, i, value.toString())
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        sheetRowNum++
                                                        totalRowCount++

                                                        emptyQueue.put(row) // Return to pool

                                                        if (totalRowCount % 1000 == 0) {
                                                            currentSheet.flush()
                                                            indicator.text =
                                                                "Writing rows to XLSX ($totalRowCount written)..."
                                                            if (totalRowCount % 10000 == 0) {
                                                                LOG.info("DataDownloadPlugin: Written $totalRowCount rows to XLSX...")
                                                            }
                                                        }
                                                    }
                                                    workbook.finish()
                                                    LOG.info("DataDownloadPlugin: Finished writing XLSX. Total rows written: $totalRowCount")
                                                }
                                            }
                                        } else {
                                            indicator.text = "Executing query and writing to CSV..."
                                            if (csvFile != null) {
                                                FileWriter(csvFile, StandardCharsets.UTF_8).use { writer ->
                                                    BufferedWriter(writer, 1024 * 1024).use { bw ->
                                                        bw.write("\uFEFF")
                                                        CSVPrinter(bw, CSVFormat.DEFAULT).use { csvPrinter ->
                                                            val headers = mutableListOf<String>()
                                                            for (i in 1..columnCount) {
                                                                headers.add(columnNames[i])
                                                            }
                                                            csvPrinter.printRecord(headers)

                                                            var rowCount = 0
                                                            while (true) {
                                                                val row = dataQueue.take()
                                                                if (row === POISON_PILL) {
                                                                    if (sharedError.get() != null) throw sharedError.get()!!
                                                                    break
                                                                }
                                                                if (indicator.isCanceled) break

                                                                for (i in 0 until columnCount) {
                                                                    csvPrinter.print(row[i])
                                                                }
                                                                csvPrinter.println()
                                                                rowCount++

                                                                emptyQueue.put(row) // Return to pool

                                                                if (rowCount % 1000 == 0) {
                                                                    indicator.text =
                                                                        "Writing rows to CSV ($rowCount written)..."
                                                                    if (rowCount % 10000 == 0) {
                                                                        LOG.info("DataDownloadPlugin: Written $rowCount rows to CSV...")
                                                                    }
                                                                }
                                                            }
                                                            LOG.info("DataDownloadPlugin: Finished writing CSV. Total rows written: $rowCount")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        sharedError.compareAndSet(null, e)
                                        LOG.error("DataDownloadPlugin: Error in consumer thread", e)
                                    } finally {
                                        consumerLatch.countDown()
                                    }
                                }

                                try {
                                    var rowCount = 0
                                    while (rs.next()) {
                                        if (indicator.isCanceled || sharedError.get() != null) {
                                            LOG.warn("DataDownloadPlugin: Producer stopped (canceled or consumer error).")
                                            break
                                        }

                                        val row = emptyQueue.take()
                                        for (i in 1..columnCount) {
                                            val type = columnTypes[i]
                                            val v = when (type) {
                                                java.sql.Types.INTEGER, java.sql.Types.TINYINT, java.sql.Types.SMALLINT -> {
                                                    val v = rs.getInt(i); if (isNullable[i] && rs.wasNull()) null else v
                                                }

                                                java.sql.Types.BIGINT -> {
                                                    val v = rs.getLong(i); if (isNullable[i] && rs.wasNull()) null else v
                                                }

                                                java.sql.Types.FLOAT, java.sql.Types.REAL -> {
                                                    val v = rs.getFloat(i); if (isNullable[i] && rs.wasNull()) null else v
                                                }

                                                java.sql.Types.DOUBLE, java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> {
                                                    val v = rs.getDouble(i); if (isNullable[i] && rs.wasNull()) null else v
                                                }

                                                java.sql.Types.BOOLEAN, java.sql.Types.BIT -> {
                                                    val v = rs.getBoolean(i); if (isNullable[i] && rs.wasNull()) null else v
                                                }

                                                java.sql.Types.DATE -> {
                                                    val v = rs.getDate(i); if (isNullable[i] && rs.wasNull()) null else v.toLocalDate()
                                                }

                                                java.sql.Types.TIMESTAMP -> {
                                                    val v =
                                                        rs.getTimestamp(i); if (isNullable[i] && rs.wasNull()) null else v.toLocalDateTime()
                                                }

                                                else -> rs.getString(i)
                                            }
                                            row[i - 1] = v
                                        }
                                        dataQueue.put(row)
                                        rowCount++
                                    }
                                    dataQueue.put(POISON_PILL)
                                } catch (e: Exception) {
                                    sharedError.compareAndSet(null, e)
                                    dataQueue.put(POISON_PILL)
                                    throw e
                                }

                                consumerLatch.await()
                                if (sharedError.get() != null) {
                                    throw sharedError.get()!!
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

                        if (isXlsx) {
                            val xlsxFile = File(dir, "${profile.tableName}.xlsx")
                            if (indicator.isCanceled && xlsxFile.exists()) {
                                xlsxFile.delete()
                                showNotification(
                                    project,
                                    "Download Canceled",
                                    "The download operation was canceled.",
                                    NotificationType.WARNING
                                )
                            } else {
                                LOG.info("DataDownloadPlugin: XLSX download completed: [${xlsxFile.absolutePath}]")
                                showNotification(
                                    project,
                                    "Download Complete",
                                    "Dataset successfully downloaded as Excel to: ${xlsxFile.absolutePath}",
                                    NotificationType.INFORMATION,
                                    xlsxFile
                                )
                            }
                        } else {
                            if (csvFile != null) {
                                if (indicator.isCanceled && csvFile.exists()) {
                                    csvFile.delete()
                                    showNotification(
                                        project,
                                        "Download Canceled",
                                        "The download operation was canceled.",
                                        NotificationType.WARNING
                                    )
                                } else {
                                    LOG.info("DataDownloadPlugin: CSV download completed: [${csvFile.absolutePath}]")
                                    showNotification(
                                        project,
                                        "Download Complete",
                                        "Dataset successfully downloaded as CSV to: ${csvFile.absolutePath}",
                                        NotificationType.INFORMATION,
                                        csvFile
                                    )
                                }
                            }
                        }

                    } catch (t: Throwable) {
                        LOG.error("DataDownloadPlugin: Database connection or query execution failed", t)
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, "Failed to download dataset: ${t.message}", "Error")
                        }
                        if (!isXlsx && csvFile != null && csvFile.exists()) csvFile.delete()
                        if (isXlsx) {
                            val xlsxFile = File(dir, "${profile.tableName}.xlsx")
                            if (xlsxFile.exists()) xlsxFile.delete()
                        }
                    }
                }
            })
    }

    private fun showNotification(
        project: Project,
        title: String,
        content: String,
        type: NotificationType,
        file: File? = null
    ) {
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
