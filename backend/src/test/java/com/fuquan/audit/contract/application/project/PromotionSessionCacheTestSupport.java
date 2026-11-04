package com.fuquan.audit.contract.application.project;

import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.SimpleExecutor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual MyBatis first-level cache; only the database read and transaction commit are local doubles. */
final class PromotionSessionCacheTestSupport {
    private final Object resourceKey = new Object();
    @FunctionalInterface interface Read { Object get() throws Throwable; }

    Object read(String statement, Read database) throws Throwable {
        // MyBatis-Spring binds a SqlSession whenever synchronization is active, including NOT_SUPPORTED scopes.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return database.get();
        var executor = (ReadExecutor) TransactionSynchronizationManager.getResource(resourceKey);
        if (executor == null) {
            executor = new ReadExecutor();
            var session = executor;
            TransactionSynchronizationManager.bindResource(resourceKey, session);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void suspend() { TransactionSynchronizationManager.unbindResource(resourceKey); }
                @Override public void resume() { TransactionSynchronizationManager.bindResource(resourceKey, session); }
                @Override public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResource(resourceKey);
                    session.clearLocalCache();
                }
            });
        }
        executor.database = database;
        var mapped = new MappedStatement.Builder(executor.config, statement,
                new StaticSqlSource(executor.config, "SELECT " + statement), SqlCommandType.SELECT).build();
        // SELECT/FOR UPDATE does not flush MyBatis' default SESSION cache.
        return executor.query(mapped, null, RowBounds.DEFAULT, null).get(0);
    }

    private static final class ReadExecutor extends SimpleExecutor {
        final Configuration config;
        Read database;
        ReadExecutor() { this(new Configuration()); }
        ReadExecutor(Configuration config) {
            super(config, org.mockito.Mockito.mock(org.apache.ibatis.transaction.Transaction.class));
            this.config = config;
        }
        @Override public <E> List<E> doQuery(MappedStatement statement, Object parameter, RowBounds bounds,
                ResultHandler handler, BoundSql sql) throws SQLException {
            try { return List.of((E) database.get()); }
            catch (Throwable failure) { throw new SQLException(failure); }
        }
    }

    /** Real Spring propagation/synchronization lifecycle, without pretending to provide database rollback. */
    static final class Transactions extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Boolean> active = new ThreadLocal<>();
        private static final class State { boolean active; State(boolean active) { this.active = active; } }
        @Override protected Object doGetTransaction() { return new State(Boolean.TRUE.equals(active.get())); }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((State) transaction).active; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            ((State) transaction).active = true; active.set(true);
        }
        @Override protected Object doSuspend(Object transaction) { active.remove(); return true; }
        @Override protected void doResume(Object transaction, Object suspended) { active.set(true); }
        @Override protected void doCleanupAfterCompletion(Object transaction) { active.remove(); }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { }
    }
}
