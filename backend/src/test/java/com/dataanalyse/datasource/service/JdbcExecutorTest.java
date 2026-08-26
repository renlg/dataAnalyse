package com.dataanalyse.datasource.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcExecutor 多语句拆分逻辑测试（不连真实库，反射调用私有 splitSql）
 */
class JdbcExecutorTest {
    private JdbcExecutor executor;

    @BeforeEach
    void setUp() {
        // 构造函数需要 PasswordCipher，用 mock 传入
        executor = new JdbcExecutor(Mockito.mock(PasswordCipher.class));
    }

    @Test
    void splitSql_按分号拆分多语句() throws Exception {
        String sql = "UPDATE t SET s='a' WHERE id=1; UPDATE t SET s='c' WHERE id=2";
        List<String> parts = split(sql);
        assertEquals(2, parts.size());
        assertEquals("UPDATE t SET s='a' WHERE id=1", parts.get(0));
        assertEquals("UPDATE t SET s='c' WHERE id=2", parts.get(1));
    }

    @Test
    void splitSql_字符串内分号不误拆() throws Exception {
        // summary 字段内容里含分号
        String sql = "UPDATE article SET AI_SUMMARY='test; still' WHERE ID=1; UPDATE article SET AI_SUMMARY='x' WHERE ID=2";
        List<String> parts = split(sql);
        assertEquals(2, parts.size());
        assertEquals("UPDATE article SET AI_SUMMARY='test; still' WHERE ID=1", parts.get(0));
    }

    @Test
    void splitSql_转义单引号不结束字符串() throws Exception {
        // SQL 里 'It''s' 表示 It's
        String sql = "UPDATE t SET s='It''s; ok' WHERE id=1; UPDATE t SET s='b' WHERE id=2";
        List<String> parts = split(sql);
        assertEquals(2, parts.size());
        assertEquals("UPDATE t SET s='It''s; ok' WHERE id=1", parts.get(0));
    }

    @Test
    void splitSql_空语句忽略() throws Exception {
        String sql = "UPDATE t SET s='a' WHERE id=1; ; ;  ";
        List<String> parts = split(sql);
        assertEquals(1, parts.size());
        assertEquals("UPDATE t SET s='a' WHERE id=1", parts.get(0));
    }

    @Test
    void splitSql_末尾无分号也识别最后一条() throws Exception {
        String sql = "UPDATE t SET s='a' WHERE id=1;UPDATE t SET s='b' WHERE id=2";
        List<String> parts = split(sql);
        assertEquals(2, parts.size());
        assertEquals("UPDATE t SET s='b' WHERE id=2", parts.get(1));
    }

    @SuppressWarnings("unchecked")
    private List<String> split(String sql) throws Exception {
        java.lang.reflect.Method m = JdbcExecutor.class.getDeclaredMethod("splitSql", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(executor, sql);
    }
}
