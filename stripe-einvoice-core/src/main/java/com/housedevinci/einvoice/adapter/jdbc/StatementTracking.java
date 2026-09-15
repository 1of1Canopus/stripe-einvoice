package com.housedevinci.einvoice.adapter.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * Records that at least one statement has been executed on a connection.
 *
 * <p><b>Why a wrapper and not a flag each store method sets (T-03).</b> The condition that decides
 * whether a failure must poison a host's transaction is "this unit of work has already touched the
 * database". A flag that every adapter method has to remember to set is a flag a future adapter
 * method will forget, on the one path where forgetting means a host commits a half-written
 * allocation. The unit of work wraps the execution instead, so the fact is observed rather than
 * declared.
 *
 * <p><b>A read does not count, including a locking one (D1-12).</b> Classified at the single point
 * a statement is created, from the SQL text it was created with: text that begins (after trimming)
 * with {@code SELECT} or {@code SHOW} is a read and never sets the flag, whatever it is later
 * called with, including {@code SELECT ... FOR UPDATE} - it takes a row lock, but it writes
 * nothing, and this module's two refusals that a host is meant to catch and carry on from ("no
 * number is allocated for that Stripe invoice", the illegal-transition refusal) are both raised
 * after exactly that kind of read and before any write. Marking the caller's transaction
 * rollback-only for a statement that wrote nothing turned a refusal the host is supposed to handle
 * into an {@code UnexpectedRollbackException} at commit. Everything else - every {@code INSERT} and
 * {@code UPDATE} this module issues, including the two executed with {@code executeQuery} because
 * they carry a {@code RETURNING} clause - is a write, because it does not begin with {@code SELECT}
 * or {@code SHOW}; a future read-with-side-effects would have to be named here explicitly rather
 * than assumed safe. The unit's own plumbing - the lock-timeout save and restore, the read-only
 * probe - runs on the untracked connection and does not count either way.
 */
final class StatementTracking {

  private StatementTracking() {}

  static Connection tracking(Connection target, Runnable markWritten) {
    return (Connection)
        Proxy.newProxyInstance(
            StatementTracking.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            new ConnectionHandler(target, markWritten));
  }

  /**
   * True for text that cannot write: a {@code SELECT}, including {@code ... FOR UPDATE}, or a
   * {@code SHOW}. Anything else - an {@code INSERT}/{@code UPDATE}, even one with a {@code
   * RETURNING} clause run through {@code executeQuery} - is treated as a write.
   */
  private static boolean isRead(String sql) {
    if (sql == null) {
      return false; // unknown text: the conservative direction is "this may have written"
    }
    String trimmed = sql.stripLeading();
    return startsWithIgnoreCase(trimmed, "SELECT") || startsWithIgnoreCase(trimmed, "SHOW");
  }

  private static boolean startsWithIgnoreCase(String text, String prefix) {
    return text.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  /**
   * The SQL a statement is about to be created with, for {@code prepareStatement}/{@code
   * prepareCall}; {@code null} for {@code createStatement}, which takes no SQL until it is
   * executed.
   */
  private static String creationSql(Method method, Object[] args) {
    String name = method.getName();
    if (("prepareStatement".equals(name) || "prepareCall".equals(name))
        && args != null
        && args.length > 0
        && args[0] instanceof String sql) {
      return sql;
    }
    return null;
  }

  private record ConnectionHandler(Connection target, Runnable markWritten)
      implements InvocationHandler {

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      switch (method.getName()) {
        case "equals" -> {
          return proxy == args[0];
        }
        case "hashCode" -> {
          return System.identityHashCode(proxy);
        }
        case "unwrap" -> {
          return target;
        }
        default -> {
          // fall through to the delegate
        }
      }
      // D1-12: classified here, at creation, from the SQL the caller is creating the statement
      // with - never declared later by whichever method happens to call execute*.
      String knownSql = creationSql(method, args);
      Object result = call(method, args, target);
      if (result instanceof CallableStatement cs) {
        return proxyStatement(CallableStatement.class, cs, markWritten, knownSql);
      }
      if (result instanceof PreparedStatement ps) {
        return proxyStatement(PreparedStatement.class, ps, markWritten, knownSql);
      }
      if (result instanceof Statement st) {
        // A plain Statement carries no SQL until execute(sql) is called; classified per call.
        return proxyStatement(Statement.class, st, markWritten, null);
      }
      return result;
    }
  }

  private static <S extends Statement> S proxyStatement(
      Class<S> type, S statement, Runnable markWritten, String knownSql) {
    return type.cast(
        Proxy.newProxyInstance(
            StatementTracking.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.startsWith("execute") || "addBatch".equals(name)) {
                String sql =
                    knownSql != null
                        ? knownSql
                        : (args != null && args.length > 0 && args[0] instanceof String s
                            ? s
                            : null);
                if (!isRead(sql)) {
                  markWritten.run();
                }
              }
              return call(method, args, statement);
            }));
  }

  private static Object call(Method method, Object[] args, Object target) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause() == null ? e : e.getCause();
    }
  }
}
