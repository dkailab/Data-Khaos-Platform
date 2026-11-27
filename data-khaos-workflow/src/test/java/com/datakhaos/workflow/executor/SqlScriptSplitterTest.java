package com.datakhaos.workflow.executor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptSplitterTest {

    @Test
    void splitsPlainStatements() {
        List<String> statements = SqlScriptSplitter.split("INSERT INTO t VALUES (1);\nUPDATE t SET a = 2;");
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("INSERT"));
        assertTrue(statements.get(1).startsWith("UPDATE"));
    }

    @Test
    void keepsSemicolonInsideQuotes() {
        List<String> statements = SqlScriptSplitter.split(
                "INSERT INTO t VALUES ('a;b', \"c;d\", `e;f`); SELECT 1;");
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("'a;b'"));
        assertTrue(statements.get(0).contains("\"c;d\""));
        assertTrue(statements.get(0).contains("`e;f`"));
    }

    @Test
    void ignoresSemicolonInsideComments() {
        List<String> statements = SqlScriptSplitter.split("""
                -- 初始化；说明
                INSERT INTO t VALUES (1);
                /* 多行注释；不拆分 */
                UPDATE t SET a = 2;
                """);
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("INSERT"));
        assertTrue(statements.get(1).startsWith("UPDATE"));
    }

    @Test
    void keepsTokensSeparatedWhenRemovingBlockComment() {
        List<String> statements = SqlScriptSplitter.split("SELECT/* hint */1;");
        assertEquals(1, statements.size());
        assertEquals("SELECT 1", statements.get(0));
    }

    @Test
    void handlesEscapedQuote() {
        List<String> statements = SqlScriptSplitter.split("INSERT INTO t VALUES ('it\\'s;ok'); SELECT 2;");
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("it\\'s;ok"));
    }

    @Test
    void dropsCommentOnlyTail() {
        List<String> statements = SqlScriptSplitter.split("SELECT 1;\n-- 收尾说明\n");
        assertEquals(1, statements.size());
        assertEquals("SELECT 1", statements.get(0));
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertTrue(SqlScriptSplitter.split(null).isEmpty());
        assertTrue(SqlScriptSplitter.split("   ").isEmpty());
        assertTrue(SqlScriptSplitter.split("-- only comment").isEmpty());
    }

    @Test
    void rejectsTooManyStatements() {
        String script = "SELECT 1;".repeat(201);
        assertThrows(IllegalArgumentException.class, () -> SqlScriptSplitter.split(script));
    }
}
