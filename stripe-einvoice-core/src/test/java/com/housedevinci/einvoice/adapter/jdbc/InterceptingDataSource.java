package com.housedevinci.einvoice.adapter.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * A DataSource whose connections report what they were asked to do, and can be made to fail at a
 * chosen statement.
 *
 * <p>Used by the tests that have to observe a transaction from the outside: what a rollback leaves
 * behind, what a killed backend leaves behind, and whether any single transaction ever takes both
 * this module's locks.
 */
final class InterceptingDataSource {

  private InterceptingDataSource() {}

  /** One transaction's statements, in order, with its boundary. */
  static final class Recording {
    final List<String> statements = new ArrayList<>();
    boolean committed;
    boolean rolledBack;
  }

  interface Hook {
    /** Called after each statement executes, with the SQL and the live connection. */
    void afterExecute(String sql, Connection connection) throws SQLException;
  }

  static DataSource recording(DataSource delegate, List<Recording> recordings) {
    return wrap(delegate, recordings, (sql, c) -> {});
  }

  static DataSource intercepting(DataSource delegate, Hook hook) {
    return wrap(delegate, new ArrayList<>(), hook);
  }

  private static DataSource wrap(DataSource delegate, List<Recording> recordings, Hook hook) {
    return (DataSource)
        Proxy.newProxyInstance(
            InterceptingDataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if ("getConnection".equals(method.getName())) {
                Connection real = delegate.getConnection();
                Recording recording = new Recording();
                synchronized (recordings) {
                  recordings.add(recording);
                }
                return proxyConnection(real, recording, hook);
              }
              return invoke(delegate, method, args);
            });
  }

  private static Connection proxyConnection(Connection real, Recording recording, Hook hook) {
    return (Connection)
        Proxy.newProxyInstance(
            InterceptingDataSource.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "commit" -> recording.committed = true;
                case "rollback" -> recording.rolledBack = true;
                case "prepareStatement" -> {
                  String sql = (String) args[0];
                  recording.statements.add(sql);
                  PreparedStatement statement = real.prepareStatement(sql);
                  return proxyStatement(statement, sql, real, hook);
                }
                default -> {
                  // fall through to the real connection
                }
              }
              return invoke(real, method, args);
            });
  }

  private static PreparedStatement proxyStatement(
      PreparedStatement statement, String sql, Connection connection, Hook hook) {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            InterceptingDataSource.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) -> {
              Object result = invoke(statement, method, args);
              if (method.getName().startsWith("execute")) {
                hook.afterExecute(sql, connection);
              }
              return result;
            });
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
