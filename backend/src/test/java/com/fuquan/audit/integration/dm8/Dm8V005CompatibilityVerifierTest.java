package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class Dm8V005CompatibilityVerifierTest {

    @Test
    void readableV005ColumnsPassTheCompatibilityGate() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("DELETED_AT, DELETED_BY"))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);

        assertThatCode(() -> new Dm8V005CompatibilityVerifier(dataSource)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();

        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void unreadableV005ColumnsFailClosedWithSafeMessage() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("DELETED_AT, DELETED_BY")))
                .thenThrow(new SQLException("raw DM8 schema detail"));

        assertThatThrownBy(() -> new Dm8V005CompatibilityVerifier(dataSource)
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(Dm8V005CompatibilityVerifier.REQUIRED_MIGRATION_MESSAGE)
                .hasNoCause();
    }
}
