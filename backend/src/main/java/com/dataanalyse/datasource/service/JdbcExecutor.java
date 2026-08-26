package com.dataanalyse.datasource.service;

import com.dataanalyse.datasource.entity.DataSourceEntity;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.*;

@Component
public class JdbcExecutor {
    private final PasswordCipher cipher;
    public JdbcExecutor(PasswordCipher cipher) { this.cipher = cipher; }
    private Connection connect(DataSourceEntity source) throws SQLException {
        String password = cipher.decrypt(source.getPassword());
        if (source.getUsername() == null || source.getUsername().isBlank()) return DriverManager.getConnection(source.getJdbcUrl());
        return DriverManager.getConnection(source.getJdbcUrl(), source.getUsername(), password == null ? "" : password);
    }
    public boolean test(DataSourceEntity source) {
        try (Connection c=connect(source)) { return c.isValid(3); } catch (Exception e) { return false; }
    }
    /**
     * 执行一条或多条 SQL（分号分隔）。
     * - 多条 UPDATE/DDL：按分号拆分逐条执行，返回 {"columns":["affectedRows"],"rows":[{affectedRows:N},...]}
     * - 单条 SELECT：返回结果集 {"columns":[...],"rows":[...]}
     * - 拆分忽略单引号/双引号字符串字面量内的分号（含 '' 转义），空语句片段跳过
     */
    public Map<String,Object> query(DataSourceEntity source, String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        List<String> statements = splitSql(sql);
        try (Connection c = connect(source)) {
            List<Map<String,Object>> affected = new ArrayList<>();
            boolean hasResultSet = false;
            Map<String,Object> resultSet = null;
            try (Statement s = c.createStatement()) {
                s.setMaxRows(1000);
                for (String stmt : statements) {
                    boolean hasResult = s.execute(stmt);
                    if (hasResult) {
                        try (ResultSet rs = s.getResultSet()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            List<String> columns = new ArrayList<>();
                            for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnLabel(i));
                            List<Map<String,Object>> rows = new ArrayList<>();
                            while (rs.next() && rows.size() < 1000) {
                                Map<String,Object> row = new LinkedHashMap<>();
                                for (int i = 1; i <= columns.size(); i++) row.put(columns.get(i-1), rs.getObject(i));
                                rows.add(row);
                            }
                            resultSet = new LinkedHashMap<>();
                            resultSet.put("columns", columns);
                            resultSet.put("rows", rows);
                            hasResultSet = true;
                        }
                    } else {
                        affected.add(Map.of("affectedRows", s.getUpdateCount()));
                    }
                }
            }
            if (hasResultSet) return resultSet;
            return Map.of("columns", List.of("affectedRows"), "rows", affected);
        } catch (SQLException e) { throw new com.dataanalyse.common.BusinessException(400, "SQL 执行失败：" + e.getMessage()); }
    }
    /**
     * 按分号拆分 SQL，忽略字符串字面量（单引号、双引号、反引号）内的分号。
     * 处理 '' 转义：连续两个单引号视为字符串内字符，不结束字符串状态。
     */
    private List<String> splitSql(String sql) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0; // 0=不在字符串内, '\''=单引号, '"'=双引号, '`'=反引号
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) {
                    // 判断是否转义（两个连续引号）
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                        cur.append(sql.charAt(i + 1));
                        i++; // 跳过下一个引号
                    } else {
                        quote = 0; // 字符串结束
                    }
                }
            } else {
                if (ch == '\'' || ch == '"' || ch == '`') {
                    quote = ch;
                    cur.append(ch);
                } else if (ch == ';') {
                    String part = cur.toString().trim();
                    if (!part.isEmpty()) parts.add(part);
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) parts.add(last);
        return parts;
    }
}
