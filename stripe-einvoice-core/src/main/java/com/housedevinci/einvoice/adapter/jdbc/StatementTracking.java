package com.housedevinci.einvoice.adapter.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>It is deliberately conservative: a {@code SELECT ... FOR UPDATE} counts, because it takes a
 * row lock and is part of the same unit of work. The unit's own plumbing - the lock-timeout save
 * and restore, the read-only probe - runs on the untracked connection and does not count.
 */
final class StatementTracking {

  private StatementTracking() {}

  static Connection tracking(Connection target, AtomicBoolean executed) {
    return (Connection)
        Proxy.newProxyInstance(
            StatementTracking.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            new ConnectionHandler(target, executed));
  }

  private record ConnectionHandler(Connection target, AtomicBoolean executed)
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
      Object result = call(method, args, target);
      if (result instanceof CallableStatement cs) {
        return proxyStatement(CallableStatement.class, cs, executed);
      }
      if (result instanceof PreparedStatement ps) {
        return proxyStatement(PreparedStatement.class, ps, executed);
      }
      if (result instanceof Statement st) {
        return proxyStatement(Statement.class, st, executed);
      }
      return result;
    }
  }

  private static <S extends Statement> S proxyStatement(
      Class<S> type, S statement, AtomicBoolean executed) {
    return type.cast(
        Proxy.newProxyInstance(
            StatementTracking.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.startsWith("execute") || "addBatch".equals(name)) {
                executed.set(true);
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
