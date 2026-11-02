package leaf.novel.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteAfterVersion
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfigurableDriver

/**
 * Carries the `is_novel` flag across upstream's schema upgrades.
 *
 * The flag first shipped as migration 15, the number upstream later took for its own. A database that
 * ran ours sits at 16 and would skip upstream's 15, whose dropped triggers then break its 16. Such a
 * database is stepped back to 15 before the driver reads its version, so upstream's migrations run as
 * written. Upstream's 17 rebuilds the table from a column list that has no `is_novel`, so the novel ids
 * are set aside after its 15 and written back once our migration 20 has added the column again.
 */
object NovelColumnCarry {

    private const val FORK_VERSION = 16L

    /** Runs in the driver's `onConfigure`, before it compares the schema version. */
    suspend fun AndroidxSqliteConfigurableDriver.rewind() {
        val version = executePragmaQuery("user_version", { QueryResult.Value(it.apply { next() }.getLong(0)) })
        if (version != FORK_VERSION) return
        if (executePragmaQuery("table_info(mangas)", ::hasNovelColumn)) {
            executePragma("user_version = ${FORK_VERSION - 1}")
        }
    }

    val callbacks = arrayOf(
        AndroidxSqliteAfterVersion(15) { driver ->
            val hasColumn = driver.executeQuery(null, "PRAGMA table_info(mangas)", ::hasNovelColumn, 0).await()
            if (!hasColumn) return@AndroidxSqliteAfterVersion
            driver.execute(null, "CREATE TABLE novel_carry(manga_id INTEGER NOT NULL PRIMARY KEY)", 0).await()
            driver.execute(null, "INSERT INTO novel_carry SELECT _id FROM mangas WHERE is_novel = 1", 0).await()
        },
        AndroidxSqliteAfterVersion(20) { driver ->
            val hasCarry = driver.executeQuery(
                null,
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'novel_carry'",
                { QueryResult.Value(it.next().value) },
                0,
            ).await()
            if (!hasCarry) return@AndroidxSqliteAfterVersion
            driver.execute(null, "UPDATE manga SET is_novel = 1 WHERE id IN (SELECT manga_id FROM novel_carry)", 0)
                .await()
            driver.execute(null, "DROP TABLE novel_carry", 0).await()
        },
    )

    private fun hasNovelColumn(cursor: SqlCursor): QueryResult<Boolean> {
        while (cursor.next().value) {
            if (cursor.getString(1) == "is_novel") return QueryResult.Value(true)
        }
        return QueryResult.Value(false)
    }
}
