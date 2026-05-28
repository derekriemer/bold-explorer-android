package com.boldexplorer.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/** Creates an in-memory BoldExplorerDatabase for unit tests. */
fun createTestDatabase(): BoldExplorerDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    BoldExplorerDatabase.Schema.create(driver)
    return BoldExplorerDatabase(driver)
}
