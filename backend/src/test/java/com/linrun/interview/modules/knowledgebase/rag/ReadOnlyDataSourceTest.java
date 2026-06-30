package com.linrun.interview.modules.knowledgebase.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    @DisplayName("SQL 安全校验应拒绝写操作和非白名单表")
    void rejectsUnsafeSql() throws Exception {
        assertThatThrownBy(() -> SqlSafety.validate("delete from resumes", Set.of("resumes")))
            .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> SqlSafety.validate("select * from users", Set.of("resumes")))
            .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> SqlSafety.validate("select * from resumes; drop table resumes", Set.of("resumes")))
            .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> SqlSafety.validate("select version()", Set.of("resumes")))
            .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> SqlSafety.validate("select * from resumes, users", Set.of("resumes")))
            .isInstanceOf(SQLException.class);

        SqlSafety.validate("with recent as (select * from resumes) select * from recent", Set.of("resumes"));
    }

    @Test
    @DisplayName("SQL 安全校验应强制当前用户过滤")
    void enforcesCurrentUserScope() throws Exception {
        assertThatThrownBy(() -> SqlSafety.validate("select * from resumes", Set.of("resumes"), 7L))
            .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> SqlSafety.validate("select * from resumes where user_id = 8", Set.of("resumes"), 7L))
            .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> SqlSafety.validate(
            "select * from resumes where user_id = 7 or user_id = 8", Set.of("resumes"), 7L))
            .isInstanceOf(SQLException.class);

        SqlSafety.validate("select * from resumes where user_id = 7", Set.of("resumes"), 7L);
    }

    @Test
    @DisplayName("安全连接应设置 SQL 超时和最大返回行数")
    void setsTimeoutAndMaxRows() throws Exception {
        Connection target = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(target.createStatement()).thenReturn(statement);

        Connection safe = SqlSafety.proxy(target, Set.of("resumes"), 3, 25);
        safe.createStatement();

        verify(statement).setQueryTimeout(3);
        verify(statement).setMaxRows(25);
    }
}
