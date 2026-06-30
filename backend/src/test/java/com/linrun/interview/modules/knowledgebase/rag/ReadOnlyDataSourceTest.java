package com.linrun.interview.modules.knowledgebase.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("只读数据源测试")
class ReadOnlyDataSourceTest {

    @Test
    @DisplayName("获取连接时应强制设置 readOnly")
    void setsConnectionReadOnly() throws Exception {
        DataSource target = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(target.getConnection()).thenReturn(connection);

        new ReadOnlyDataSource(target).getConnection();

        verify(connection).setReadOnly(true);
    }
}
