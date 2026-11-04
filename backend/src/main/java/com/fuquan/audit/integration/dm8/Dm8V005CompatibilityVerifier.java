package com.fuquan.audit.integration.dm8;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fails closed before serving traffic when the required V005 projection is unavailable. */
@Component
@Profile({"dev29", "customer", "local-dev"})
public class Dm8V005CompatibilityVerifier implements SmartInitializingSingleton {
    static final String REQUIRED_MIGRATION_MESSAGE = "required DB migration V005 is missing";
    private static final String PROBE_SQL =
            "SELECT DELETED_AT, DELETED_BY FROM CONTRACT_REVIEW_SESSION WHERE 1 = 0";

    private final DataSource dataSource;

    public Dm8V005CompatibilityVerifier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(PROBE_SQL);
                ResultSet ignored = statement.executeQuery()) {
            // Successful projection proves both V005 columns are readable by the runtime account.
        } catch (SQLException exception) {
            throw new IllegalStateException(REQUIRED_MIGRATION_MESSAGE);
        }
    }
}
