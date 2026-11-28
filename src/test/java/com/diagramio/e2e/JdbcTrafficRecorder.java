package com.diagramio.e2e;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.MethodExecutionContext;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.postgresql.util.PGobject;

/**
 * Ghi lại lưu lượng JDBC giữa app và PostgreSQL: số câu lệnh theo loại, số transaction đã commit, số lần
 * mượn kết nối từ pool, số dòng đọc được và số byte giá trị đọc/ghi. Bọc DataSource bằng datasource-proxy
 * nên đếm được mọi câu lệnh (JPA, câu SQL viết tay, JdbcTemplate), không chỉ câu lệnh của Hibernate.
 *
 * <p>Số byte là kích thước giá trị cột và tham số (chuỗi, JSON, mảng byte theo UTF-8; số, UUID, thời gian theo
 * kích thước nhị phân), không phải số byte trên đường truyền, nhưng đủ để thấy cột data (tới 2 MB) bị chở
 * qua lại bao nhiêu lần. Chỉ dùng trong test, và chỉ đúng khi các request được gửi lần lượt từng cái.
 */
final class JdbcTrafficRecorder {

    /** Getter đọc giá trị cột của ResultSet (bỏ qua getMetaData, getStatement...). */
    private static final Set<String> VALUE_GETTERS = Set.of("getString", "getNString", "getObject", "getBytes",
            "getInt", "getLong", "getShort", "getByte", "getBoolean", "getDouble", "getFloat", "getBigDecimal",
            "getTimestamp", "getDate", "getTime", "getArray");

    /** Lưu lượng JDBC của một request. */
    record Traffic(int selects, int inserts, int updates, int deletes, int others, int commits, int rollbacks,
                   int connections, long rows, long bytesRead, long bytesWritten, List<String> sql) {

        int statements() {
            return selects + inserts + updates + deletes + others;
        }
    }

    private int selects;
    private int inserts;
    private int updates;
    private int deletes;
    private int others;
    private int commits;
    private int rollbacks;
    private int connections;
    private long rows;
    private long bytesRead;
    private long bytesWritten;
    private final List<String> sql = new ArrayList<>();

    DataSource wrap(DataSource target) {
        return ProxyDataSourceBuilder.create("jdbc-traffic", target)
                .afterQuery(this::afterQuery)
                .afterMethod(this::afterMethod)
                .proxyResultSet()
                .build();
    }

    synchronized void reset() {
        selects = inserts = updates = deletes = others = commits = rollbacks = connections = 0;
        rows = bytesRead = bytesWritten = 0;
        sql.clear();
    }

    synchronized Traffic snapshot() {
        return new Traffic(selects, inserts, updates, deletes, others, commits, rollbacks, connections, rows,
                bytesRead, bytesWritten, List.copyOf(sql));
    }

    private synchronized void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
        for (QueryInfo query : queries) {
            countStatement(query.getQuery());
            for (List<ParameterSetOperation> parameters : query.getParametersList()) {
                for (ParameterSetOperation parameter : parameters) {
                    Object[] args = parameter.getArgs();
                    if (args != null && args.length >= 2
                            && !ParameterSetOperation.isSetNullParameterOperation(parameter)) {
                        bytesWritten += sizeOf(args[1]);
                    }
                }
            }
        }
    }

    private synchronized void afterMethod(MethodExecutionContext call) {
        Object target = call.getTarget();
        String method = call.getMethod().getName();
        if (target instanceof ResultSet) {
            if (method.equals("next")) {
                if (Boolean.TRUE.equals(call.getResult())) {
                    rows++;
                }
            } else if (VALUE_GETTERS.contains(method)) {
                bytesRead += sizeOf(call.getResult());
            }
        } else if (target instanceof Connection) {
            if (method.equals("commit")) {
                commits++;
            } else if (method.equals("rollback")) {
                rollbacks++;
            }
        } else if (target instanceof DataSource && method.equals("getConnection")) {
            connections++;
        }
    }

    private void countStatement(String query) {
        sql.add(query);
        String head = query.stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("select") || head.startsWith("with")) {
            selects++;
        } else if (head.startsWith("insert")) {
            inserts++;
        } else if (head.startsWith("update")) {
            updates++;
        } else if (head.startsWith("delete")) {
            deletes++;
        } else {
            others++;
        }
    }

    static long sizeOf(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof String text) {
            return utf8Length(text);
        }
        if (value instanceof PGobject object) {
            return object.getValue() == null ? 0 : utf8Length(object.getValue());
        }
        if (value instanceof byte[] bytes) {
            return bytes.length;
        }
        if (value instanceof Boolean || value instanceof Byte) {
            return 1;
        }
        if (value instanceof Short) {
            return 2;
        }
        if (value instanceof Integer || value instanceof Float) {
            return 4;
        }
        if (value instanceof Number || value instanceof Date || value instanceof Temporal) {
            return 8;
        }
        if (value instanceof UUID) {
            return 16;
        }
        return utf8Length(value.toString());
    }

    private static long utf8Length(CharSequence text) {
        long length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                length += 1;
            } else if (c < 0x800) {
                length += 2;
            } else if (Character.isHighSurrogate(c)) {
                length += 4;
                i++;
            } else {
                length += 3;
            }
        }
        return length;
    }
}
