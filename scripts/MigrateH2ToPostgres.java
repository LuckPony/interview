import java.sql.*;
import java.util.*;

/** 一次性把 H2 业务表复制到由 Flyway 创建好的 PostgreSQL。 */
public class MigrateH2ToPostgres {
    record Column(String name, String dataType, String udtName) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("usage: <h2-url> <pg-url> <pg-user> <pg-password> <h2-user>");
        }
        try (Connection source = DriverManager.getConnection(args[0], args[4], "");
             Connection target = DriverManager.getConnection(args[1], args[2], args[3])) {
            source.setReadOnly(true);
            target.setAutoCommit(false);
            List<String> tables = commonTables(source, target);
            System.out.println("business tables: " + tables.size());
            try (Statement st = target.createStatement()) {
                st.execute("SET session_replication_role = replica");
                if (!tables.isEmpty()) {
                    st.execute("TRUNCATE TABLE " + String.join(",", tables.stream().map(MigrateH2ToPostgres::quote).toList()) + " CASCADE");
                }
            }
            Map<String, Long> sourceCounts = new LinkedHashMap<>();
            for (String table : tables) {
                long count = copyTable(source, target, table);
                sourceCounts.put(table, count);
                System.out.printf("%-32s %d%n", table, count);
            }
            resetSequences(target, tables);
            try (Statement st = target.createStatement()) {
                st.execute("SET session_replication_role = DEFAULT");
            }
            verify(target, sourceCounts);
            target.commit();
            System.out.println("MIGRATION_OK");
        }
    }

    static List<String> commonTables(Connection h2, Connection pg) throws SQLException {
        Set<String> source = new TreeSet<>();
        try (PreparedStatement ps = h2.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) source.add(rs.getString(1).toLowerCase(Locale.ROOT));
        }
        Set<String> target = new HashSet<>();
        try (PreparedStatement ps = pg.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) target.add(rs.getString(1));
        }
        source.retainAll(target);
        source.remove("flyway_schema_history");
        return new ArrayList<>(source);
    }

    static List<Column> targetColumns(Connection pg, String table) throws SQLException {
        List<Column> result = new ArrayList<>();
        String sql = "SELECT column_name,data_type,udt_name FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name=? AND is_generated='NEVER' ORDER BY ordinal_position";
        try (PreparedStatement ps = pg.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new Column(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return result;
    }

    static Set<String> sourceColumns(Connection h2, String table) throws SQLException {
        Set<String> result = new HashSet<>();
        try (PreparedStatement ps = h2.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    static long copyTable(Connection h2, Connection pg, String table) throws Exception {
        Set<String> available = sourceColumns(h2, table);
        List<Column> columns = targetColumns(pg, table).stream().filter(c -> available.contains(c.name())).toList();
        if (columns.isEmpty()) return 0;
        String names = String.join(",", columns.stream().map(c -> quote(c.name())).toList());
        String params = String.join(",", columns.stream().map(c -> c.dataType().equals("jsonb") ? "?::jsonb" : "?").toList());
        String select = "SELECT " + names + " FROM " + quote(table);
        String insert = "INSERT INTO " + quote(table) + " (" + names + ") VALUES (" + params + ")";
        long count = 0;
        try (Statement read = h2.createStatement(); ResultSet rs = read.executeQuery(select); PreparedStatement write = pg.prepareStatement(insert)) {
            while (rs.next()) {
                for (int i = 0; i < columns.size(); i++) {
                    Column column = columns.get(i);
                    Object value = column.dataType().equals("jsonb") ? rs.getString(i + 1) : rs.getObject(i + 1);
                    bind(pg, write, i + 1, value, column);
                }
                write.addBatch();
                count++;
                if (count % 500 == 0) write.executeBatch();
            }
            write.executeBatch();
        }
        return count;
    }

    static void bind(Connection pg, PreparedStatement ps, int index, Object value, Column column) throws Exception {
        if (value == null) { ps.setObject(index, null); return; }
        if (column.dataType().equals("jsonb")) {
            ps.setString(index, value.toString());
        } else if (column.dataType().equals("ARRAY")) {
            Object array = value instanceof java.sql.Array a ? a.getArray() : value;
            Object[] values = array instanceof Object[] objects ? objects : new Object[]{array};
            ps.setArray(index, pg.createArrayOf(column.udtName().replaceFirst("^_", ""), values));
        } else if (value instanceof Clob clob) {
            ps.setString(index, clob.getSubString(1, (int) clob.length()));
        } else {
            ps.setObject(index, value);
        }
    }

    static void resetSequences(Connection pg, List<String> tables) throws SQLException {
        for (String table : tables) {
            try (PreparedStatement cols = pg.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_default LIKE 'nextval(%'")) {
                cols.setString(1, table);
                try (ResultSet rs = cols.executeQuery()) {
                    while (rs.next()) {
                        String column = rs.getString(1);
                        String sql = "SELECT setval(pg_get_serial_sequence(?,?), COALESCE((SELECT MAX(" + quote(column) + ") FROM " + quote(table) + "),1), EXISTS(SELECT 1 FROM " + quote(table) + "))";
                        try (PreparedStatement reset = pg.prepareStatement(sql)) {
                            reset.setString(1, table); reset.setString(2, column); reset.execute();
                        }
                    }
                }
            }
        }
    }

    static void verify(Connection pg, Map<String, Long> expected) throws SQLException {
        for (var entry : expected.entrySet()) {
            try (Statement st = pg.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quote(entry.getKey()))) {
                rs.next();
                long actual = rs.getLong(1);
                if (actual != entry.getValue()) throw new SQLException(entry.getKey() + " count mismatch: " + entry.getValue() + " != " + actual);
            }
        }
    }

    static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
