package dev.brikk.ducklake.corpus;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Struct;
import java.sql.Types;
import java.util.Arrays;
import java.util.Map;

/** Run in an isolated child: the reported tuple-list getter crashes JDBC2.0.0.0 natively. */
public final class JdbcResultProbe {
    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    private static String snapshot(Object value) throws Exception {
        if (value == null) return "<sql-null>";
        if (value instanceof Struct struct) return struct.getSQLTypeName() + snapshot(struct.getAttributes());
        if (value instanceof Array array) return snapshot(array.getArray());
        if (value instanceof byte[] bytes) return Arrays.toString(bytes);
        if (value instanceof Object[] array) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : array) out.append(snapshot(item)).append(';');
            return out.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            for (var item : map.entrySet()) out.append(snapshot(item.getKey())).append('=').append(snapshot(item.getValue())).append(';');
            return out.append('}').toString();
        }
        return value.getClass().getName() + ':' + value;
    }

    private static void getters(Connection c, String expression, String fixedExpected) throws Exception {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT " + expression + ", CAST(" + expression + " AS VARCHAR)")) {
            require(r.next(), "missing fixture row");
            String expected = r.getString(2);
            if (fixedExpected != null) require(fixedExpected.equals(expected), "invalid independent native fixture: " + expected);
            String before = snapshot(r.getObject(1));
            System.out.println("GETTER " + expression + " type=" + r.getMetaData().getColumnTypeName(1) + " expected=" + expected);
            System.out.flush();
            for (int i = 0; i < 3; i++) {
                require(java.util.Objects.equals(r.getString(1), expected), "getter/native text mismatch for " + expression);
                require(before.equals(snapshot(r.getObject(1))), "getter mutated typed objects for " + expression);
            }
            require(!r.next(), "extra fixture rows");
        }
    }

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA version")) {
                require(r.next() && r.getString(1).equals("v2.0.0") && r.getString(2).equals("d54cbbe3df"), "changed native core");
            }
            switch (args[0]) {
                case "tuple-list" -> getters(c, "[row(1,'x'),NULL]", "[(1, x), NULL]");
                case "tuple-objects" -> {
                    try (var s = c.createStatement(); var r = s.executeQuery("SELECT row(1,'x'),[row(1,'x'),NULL]")) {
                        require(r.next(), "missing tuple row");
                        require(r.getMetaData().getColumnType(1) == Types.STRUCT, "TUPLE must advertise JDBC STRUCT");
                        require(r.getMetaData().getColumnTypeName(1).equals("TUPLE(INTEGER, VARCHAR)"), "lost tuple SQL type");
                        require(r.getObject(1) instanceof Struct, "TUPLE must be a typed Struct");
                        Struct tuple = (Struct) r.getObject(1);
                        require(Arrays.deepEquals(tuple.getAttributes(), new Object[]{1, "x"}), "wrong positional attributes");
                        Object[] list = (Object[]) r.getArray(2).getArray();
                        require(list.length == 2 && list[0] instanceof Struct && list[1] == null, "lost nested tuple/null types");
                        require(Arrays.deepEquals(((Struct) list[0]).getAttributes(), new Object[]{1,"x"}), "wrong nested attributes");
                    }
                }
                case "nested-fallback" -> {
                    String[] values = {"'123456789012345678901234567890'::BIGNUM", "'12:34:56.123456789'::TIME_NS",
                        "'POINT (1 2)'::GEOMETRY", "{'a':1}::VARIANT", "'2024-01-01 12:00:00.123456789+00'::TIMESTAMPTZ_NS"};
                    String[] wrappers = {"[%s,NULL]", "array_value(%s,NULL)", "{'k':%s,'n':NULL}", "MAP {1:%s,2:NULL}",
                        "union_value(u:=%s)", "[[%s],NULL]", "[{'k':%s}]"};
                    for (String value : values) for (String wrapper : wrappers) getters(c, String.format(wrapper, value), null);
                }
                case "controls" -> {
                    getters(c, "row(1,'x')", "(1, x)");
                    getters(c, "struct_pack(element1:=1,element2:='x')", "{'element1': 1, 'element2': x}");
                    getters(c, "[NULL::INTEGER,NULL]", "[NULL, NULL]");
                    getters(c, "NULL::STRUCT(a INTEGER)", null);
                    getters(c, "MAP {'literal':'NULL','value':NULL}", "{literal='NULL', value=NULL}");
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
            System.out.println("PASS " + args[0]);
        }
    }
}
