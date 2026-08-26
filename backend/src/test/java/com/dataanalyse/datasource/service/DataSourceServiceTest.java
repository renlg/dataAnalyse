package com.dataanalyse.datasource.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
class DataSourceServiceTest {
    private final DataSourceService service=new DataSourceService(mock(com.dataanalyse.datasource.repo.DataSourceRepository.class),mock(PasswordCipher.class),mock(JdbcExecutor.class));
    @Test void buildsAllJdbcUrls(){assertEquals("jdbc:sqlite:/tmp/a.db",service.buildJdbcUrl("sqlite",null,null,"/tmp/a.db"));assertEquals("jdbc:h2:mem:demo",service.buildJdbcUrl("h2",null,null,"mem:demo"));assertTrue(service.buildJdbcUrl("mysql","db.local",3307,"demo").startsWith("jdbc:mysql://db.local:3307/demo"));}
}
