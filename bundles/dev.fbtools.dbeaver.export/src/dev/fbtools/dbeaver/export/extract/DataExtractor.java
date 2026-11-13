package dev.fbtools.dbeaver.export.extract;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * O Script de dados: um {@code INSERT} por registro das tabelas dadas, na ordem em que elas vêm, lidos em streaming
 * (nada de tabela inteira na memória) e gravados direto no arquivo. Cada tabela sai com uma lista explícita de
 * colunas, lida do catálogo (ADR 0007) e sem as {@code COMPUTED BY}, que o servidor não deixa gravar. As
 * Colunas array ficam como estão: a ADR 0010 não foi aceita, e o Jaybird falha ao lê-las.
 */
public final class DataExtractor {

    private static final int FETCH_SIZE = 1000;

    /** Para onde o script vai. {@link #committed()} é chamado depois de cada {@code COMMIT WORK}, onde um arquivo pode terminar. */
    interface Sink {

        Writer writer();

        default void committed() throws IOException {
        }

        /** Se o texto cabe na codificação do arquivo; o que não cabe sai como {@code ?} e a extração avisa. */
        default boolean canEncode(CharSequence text) {
            return true;
        }

        /** O nome da codificação do arquivo, para o aviso. */
        default String encodingName() {
            return "";
        }
    }

    private enum Kind {
        INTEGER, EXACT, FLOAT, DOUBLE, DECFLOAT16, DECFLOAT34, BOOLEAN, TEXT, OCTETS, BLOB_TEXT, BLOB_BINARY, DATE,
        TIME, TIMESTAMP, TIME_TZ, TIMESTAMP_TZ, ARRAY
    }

    private record Column(String name, Kind kind, boolean identityAlways, boolean none) {
    }

    private record TableColumns(List<Column> columns, List<String> omittedBlobs) {
    }

    private static final String COLUMNS = """
            SELECT rf.RDB$FIELD_NAME, rf.RDB$IDENTITY_TYPE, f.RDB$FIELD_TYPE, f.RDB$FIELD_SUB_TYPE,
                   f.RDB$FIELD_SCALE, f.RDB$DIMENSIONS, f.RDB$COMPUTED_BLR, cs.RDB$CHARACTER_SET_NAME
              FROM RDB$RELATION_FIELDS rf
              JOIN RDB$FIELDS f ON f.RDB$FIELD_NAME = rf.RDB$FIELD_SOURCE
              LEFT JOIN RDB$CHARACTER_SETS cs ON cs.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
             WHERE rf.RDB$RELATION_NAME = ?
             ORDER BY rf.RDB$FIELD_POSITION""";

    private final Connection connection;

    public DataExtractor(Connection connection) {
        this.connection = connection;
    }

    /** O início do script: dialeto e {@code SET NAMES} (ADR 0009), a menos que {@code omitHeader}. */
    public String header(DataOptions options, String setNames) throws SQLException {
        if (options.omitHeader()) {
            return "";
        }
        return "SET SQL DIALECT 3;\nSET NAMES "
                + (setNames != null ? setNames : DatabaseInfo.read(connection).defaultCharacterSet()) + ";\n";
    }

    /** Escreve o cabeçalho e os dados. Não fecha o {@code out}. */
    public void extract(List<String> tables, DataOptions options, Writer out, String setNames,
            ExtractionProgress progress) throws SQLException, IOException {
        out.write(header(options, setNames));
        extractBody(tables, options, () -> out, progress);
        out.flush();
    }

    /** Só os dados, sem cabeçalho; quem divide o script em arquivos escreve o cabeçalho de cada um. */
    void extractBody(List<String> tables, DataOptions options, Sink sink, ExtractionProgress progress)
            throws SQLException, IOException {
        DatabaseInfo info = DatabaseInfo.read(connection);
        DatabaseInfo.warnIfConnectionDiffers(connection, info, progress);
        bindNativeTypes();
        DataLiterals literals = new DataLiterals(options);
        List<String> none = new ArrayList<>();
        List<String> blobs = new ArrayList<>();
        Set<String> lossy = new LinkedHashSet<>();
        progress.begin(tables.size());
        for (String table : tables) {
            progress.checkCancelled();
            TableColumns read = readColumns(table, options);
            for (Column column : read.columns()) {
                if (column.none()) {
                    none.add(table + "." + column.name());
                }
            }
            read.omittedBlobs().forEach(column -> blobs.add(table + "." + column));
            long rows = read.columns().isEmpty() ? 0 : writeTable(table, read.columns(), options, literals, sink,
                    progress, lossy);
            progress.extracted("Data " + table + " (" + rows + " rows)");
        }
        if (!lossy.isEmpty()) {
            progress.warn("Caracteres sem correspondência na codificação do arquivo (" + sink.encodingName()
                    + ") saíram como '?' nas tabelas: " + String.join(", ", lossy) + ". Use UTF-8 em Output ou"
                    + " confira o charset das colunas.");
        }
        if (!none.isEmpty()) {
            progress.warn("Colunas de dados com CHARACTER SET NONE (" + String.join(", ", none) + "): o servidor não"
                    + " sabe o charset real desses textos. Eles saem byte a byte no charset do banco ("
                    + info.defaultCharacterSet() + "); rode o script com o mesmo charset (ADR 0009).");
        }
        if (!blobs.isEmpty()) {
            progress.warn("Colunas BLOB fora do script de dados (a opção de extrair BLOBs está desligada): "
                    + String.join(", ", blobs) + ".");
        }
    }

    /**
     * Num servidor com {@code DataTypeCompatibility} antigo (2.5 ou 3.0), {@code BOOLEAN} chega como {@code CHAR(5)},
     * {@code DECFLOAT} como {@code DOUBLE PRECISION}, {@code INT128} estoura e o fuso horário some. O
     * {@code SET BIND OF … TO NATIVE} vale só para esta conexão e devolve os tipos como são. Servidores sem o comando
     * (Firebird 3) não têm esses tipos, e o erro é ignorado.
     */
    private void bindNativeTypes() {
        try (Statement statement = connection.createStatement()) {
            for (String type : List.of("BOOLEAN", "DECFLOAT", "INT128", "TIME ZONE")) {
                try {
                    statement.execute("SET BIND OF " + type + " TO NATIVE");
                } catch (SQLException e) {
                    // sem o comando, sem os tipos novos
                }
            }
        } catch (SQLException e) {
            // não foi possível nem criar o statement: a leitura abaixo falha com o erro de verdade
        }
    }

    private TableColumns readColumns(String table, DataOptions options) throws SQLException {
        List<Column> columns = new ArrayList<>();
        List<String> omittedBlobs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMNS)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if (rs.getObject("RDB$COMPUTED_BLR") != null) {
                        continue;
                    }
                    String name = Rdb.trimmed(rs.getString("RDB$FIELD_NAME"));
                    String charset = Rdb.trimmed(rs.getString("RDB$CHARACTER_SET_NAME"));
                    Kind kind = kindOf(rs.getInt("RDB$FIELD_TYPE"), rs.getInt("RDB$FIELD_SUB_TYPE"),
                            rs.getInt("RDB$FIELD_SCALE"), rs.getInt("RDB$DIMENSIONS") > 0, charset);
                    if ((kind == Kind.BLOB_TEXT || kind == Kind.BLOB_BINARY) && !options.extractBlobs()) {
                        omittedBlobs.add(name);
                        continue;
                    }
                    Integer identity = Rdb.nullableInt(rs, "RDB$IDENTITY_TYPE");
                    boolean none = "NONE".equals(charset) && (kind == Kind.TEXT || kind == Kind.BLOB_TEXT);
                    columns.add(new Column(name, kind, identity != null && identity == 0, none));
                }
            }
        }
        return new TableColumns(columns, omittedBlobs);
    }

    private static Kind kindOf(int type, int subType, int scale, boolean array, String charset) {
        if (array) {
            return Kind.ARRAY;
        }
        boolean exact = scale < 0 || subType == 1 || subType == 2;
        return switch (type) {
            case 7, 8, 16 -> exact ? Kind.EXACT : Kind.INTEGER;
            case 26 -> Kind.EXACT;
            case 10 -> Kind.FLOAT;
            case 27 -> Kind.DOUBLE;
            case 24 -> Kind.DECFLOAT16;
            case 25 -> Kind.DECFLOAT34;
            case 23 -> Kind.BOOLEAN;
            case 14, 37 -> "OCTETS".equals(charset) ? Kind.OCTETS : Kind.TEXT;
            case 261 -> subType == 1 ? Kind.BLOB_TEXT : Kind.BLOB_BINARY;
            case 12 -> Kind.DATE;
            case 13 -> Kind.TIME;
            case 35 -> Kind.TIMESTAMP;
            case 28 -> Kind.TIME_TZ;
            case 29 -> Kind.TIMESTAMP_TZ;
            default -> throw new IllegalStateException("Tipo de campo desconhecido: " + type);
        };
    }

    private long writeTable(String table, List<Column> columns, DataOptions options, DataLiterals literals, Sink sink,
            ExtractionProgress progress, Set<String> lossy) throws SQLException, IOException {
        String quotedTable = Identifiers.quote(table);
        List<String> names = columns.stream().map(column -> Identifiers.quote(column.name())).toList();
        String insert = "INSERT INTO " + quotedTable + " (" + String.join(", ", names) + ")"
                + (columns.stream().anyMatch(Column::identityAlways) ? " OVERRIDING SYSTEM VALUE" : "")
                + " VALUES (";
        long rows = 0;
        boolean pending = false;
        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = open(statement, table, names, quotedTable)) {
                StringBuilder line = new StringBuilder();
                while (rs.next()) {
                    progress.checkCancelled();
                    if (rows == 0) {
                        sink.writer().write("\n/* Data: " + table + " */\n\n");
                    }
                    line.setLength(0);
                    line.append(insert);
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) {
                            line.append(", ");
                        }
                        line.append(literal(rs, i + 1, columns.get(i), literals));
                    }
                    line.append(");\n");
                    if (!lossy.contains(table) && !sink.canEncode(line)) {
                        lossy.add(table);
                    }
                    sink.writer().write(line.toString());
                    rows++;
                    pending = true;
                    if (rows % options.commitEvery() == 0) {
                        commit(sink);
                        pending = false;
                    }
                }
            }
        }
        if (pending) {
            commit(sink);
        }
        return rows;
    }

    /** Abre o cursor; o erro leva o nome da tabela (ex.: a Coluna array que o Jaybird não lê, ADR 0010). */
    private static ResultSet open(Statement statement, String table, List<String> names, String quotedTable)
            throws SQLException {
        try {
            return statement.executeQuery("SELECT " + String.join(", ", names) + " FROM " + quotedTable);
        } catch (SQLException e) {
            throw new SQLException("Falha ao ler os dados da tabela " + table + ": " + e.getMessage(),
                    e.getSQLState(), e.getErrorCode(), e);
        }
    }

    private static void commit(Sink sink) throws IOException {
        sink.writer().write("\nCOMMIT WORK;\n");
        sink.committed();
    }

    private static String literal(ResultSet rs, int index, Column column, DataLiterals literals) throws SQLException {
        switch (column.kind()) {
            case INTEGER: {
                long value = rs.getLong(index);
                return rs.wasNull() ? "NULL" : Long.toString(value);
            }
            case EXACT: {
                BigDecimal value = rs.getBigDecimal(index);
                return value == null ? "NULL" : literals.exact(value);
            }
            case FLOAT: {
                float value = rs.getFloat(index);
                return rs.wasNull() ? "NULL" : literals.floating(value);
            }
            case DOUBLE: {
                double value = rs.getDouble(index);
                return rs.wasNull() ? "NULL" : literals.floating(value);
            }
            case DECFLOAT16:
            case DECFLOAT34: {
                String value = rs.getString(index);
                return value == null ? "NULL" : literals.decfloat(value, column.kind() == Kind.DECFLOAT16 ? 16 : 34);
            }
            case BOOLEAN: {
                boolean value = rs.getBoolean(index);
                return rs.wasNull() ? "NULL" : literals.bool(value);
            }
            case TEXT:
            case BLOB_TEXT: {
                String value = rs.getString(index);
                return value == null ? "NULL" : literals.text(value);
            }
            case OCTETS:
            case BLOB_BINARY: {
                byte[] value = rs.getBytes(index);
                return value == null ? "NULL" : literals.hex(value);
            }
            case DATE: {
                LocalDate value = rs.getObject(index, LocalDate.class);
                return value == null ? "NULL" : literals.date(value);
            }
            case TIME: {
                LocalTime value = rs.getObject(index, LocalTime.class);
                return value == null ? "NULL" : literals.time(value);
            }
            case TIMESTAMP: {
                LocalDateTime value = rs.getObject(index, LocalDateTime.class);
                return value == null ? "NULL" : literals.timestamp(value);
            }
            case TIME_TZ: {
                OffsetTime value = rs.getObject(index, OffsetTime.class);
                return value == null ? "NULL" : literals.timeWithZone(value);
            }
            case TIMESTAMP_TZ: {
                ZonedDateTime value = rs.getObject(index, ZonedDateTime.class);
                return value == null ? "NULL" : literals.timestampWithZone(value);
            }
            default:
                // Coluna array (ADR 0010, proposta): o Jaybird falha antes, ao abrir o cursor; limitação conhecida.
                throw new SQLException("Coluna array sem suporte no Script de dados: " + column.name());
        }
    }
}
