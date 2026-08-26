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
    public Map<String,Object> query(DataSourceEntity source, String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        try (Connection c=connect(source); Statement s=c.createStatement()) {
            s.setMaxRows(1000); boolean hasResult=s.execute(sql);
            if (!hasResult) return Map.of("columns", List.of("affectedRows"), "rows", List.of(Map.of("affectedRows", s.getUpdateCount())));
            try (ResultSet rs=s.getResultSet()) {
                ResultSetMetaData meta=rs.getMetaData(); List<String> columns=new ArrayList<>();
                for(int i=1;i<=meta.getColumnCount();i++) columns.add(meta.getColumnLabel(i));
                List<Map<String,Object>> rows=new ArrayList<>();
                while(rs.next() && rows.size()<1000){ Map<String,Object> row=new LinkedHashMap<>(); for(int i=1;i<=columns.size();i++) row.put(columns.get(i-1),rs.getObject(i)); rows.add(row); }
                Map<String,Object> result=new LinkedHashMap<>(); result.put("columns",columns); result.put("rows",rows); return result;
            }
        } catch (SQLException e) { throw new com.dataanalyse.common.BusinessException(400, "SQL 执行失败：" + e.getMessage()); }
    }
}
