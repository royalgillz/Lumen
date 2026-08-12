package com.lumen.app.data.text

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KotlinSqlEquivalenceTest {

    /** Strings exercising every deletion rule, plus text that must pass through. */
    private val corpus = listOf(
        "F-1", "F.1", "I-20", "802.11", "1,000", "10:30", "don't", "don’t",
        "F‐1", "F–1", "F—1",
        "COVID-\n19", "COVID-\r\n19", "end-\nof-\r\nline",
        "05/07/2026", "file_name", "Hello World", "foo - bar", "a  b\nc",
        "", "-.-,:", "mixed: F-1 on 05/07/2026, cost 1,000.50",
    )

    // @spec SEARCH-NORM-002
    @Test
    fun kotlinNormalizer_equalsSqlReplaceChain_forWholeCorpus() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE t (input TEXT NOT NULL)")
            corpus.forEach { db.execSQL("INSERT INTO t (input) VALUES (?)", arrayOf(it)) }

            val expr = TextNormalizer.sqlNormExpr("input")
            db.rawQuery("SELECT input, $expr FROM t", null).use { c ->
                while (c.moveToNext()) {
                    val input = c.getString(0)
                    assertEquals(
                        "SQL and Kotlin diverge for input: ${input.replace("\n", "\\n")}",
                        TextNormalizer.normalize(input),
                        c.getString(1),
                    )
                }
            }
        }
    }
}
