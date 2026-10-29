package dev.fbtools.dbeaver.psqldebug.source;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import dev.fbtools.dbeaver.psqldebug.psql.Identifiers;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;

/**
 * Lê a Fonte do banco de uma Rotina. O corpo vem dos metadados ({@code RDB$PROCEDURE_SOURCE},
 * {@code RDB$FUNCTION_SOURCE}, {@code RDB$PACKAGE_BODY_SOURCE}); o cabeçalho de procedure e de function, que o
 * Firebird não guarda como texto, é montado a partir dos parâmetros e de {@code RDB$FIELDS}.
 *
 * <p>
 * O cabeçalho segue as quebras de linha do que o editor do DBeaver 26.2.2 mostra para o objeto
 * ({@code FireBirdUtils.getProcedureSourceWithHeader}): entradas na linha do {@code CREATE}, cada saída numa linha,
 * numa function com {@code SQL SECURITY} explícito uma linha para ele, e {@code AS} sozinho antes do corpo intacto. O texto dos tipos pode ser outro, mas o corpo começa na mesma linha,
 * então as linhas e colunas do AST dentro do corpo são as do editor.
 */
public final class RoutineSourceLoader {

    private static final String PROCEDURE_SQL = """
            SELECT RDB$PROCEDURE_SOURCE
            FROM RDB$PROCEDURES
            WHERE RDB$PROCEDURE_NAME = ? AND RDB$PACKAGE_NAME IS NULL""";

    private static final String PROCEDURE_PARAMETERS_SQL = """
            SELECT P.RDB$PARAMETER_NAME AS NAME, P.RDB$PARAMETER_TYPE AS DIRECTION, P.RDB$NULL_FLAG AS NULL_FLAG,
                   P.RDB$PARAMETER_MECHANISM AS MECHANISM, P.RDB$FIELD_SOURCE AS FIELD_SOURCE,
                   P.RDB$RELATION_NAME AS RELATION_NAME, P.RDB$FIELD_NAME AS FIELD_NAME,
                   P.RDB$DEFAULT_SOURCE AS DEFAULT_SOURCE,
                   F.RDB$FIELD_TYPE, F.RDB$FIELD_SUB_TYPE, F.RDB$FIELD_SCALE, F.RDB$FIELD_PRECISION,
                   F.RDB$CHARACTER_LENGTH, CS.RDB$CHARACTER_SET_NAME
            FROM RDB$PROCEDURE_PARAMETERS P
            JOIN RDB$FIELDS F ON F.RDB$FIELD_NAME = P.RDB$FIELD_SOURCE
            LEFT JOIN RDB$CHARACTER_SETS CS ON CS.RDB$CHARACTER_SET_ID = F.RDB$CHARACTER_SET_ID
            WHERE P.RDB$PROCEDURE_NAME = ? AND P.RDB$PACKAGE_NAME IS NULL
            ORDER BY P.RDB$PARAMETER_TYPE, P.RDB$PARAMETER_NUMBER""";

    private static final String FUNCTION_SQL = """
            SELECT RDB$FUNCTION_SOURCE, RDB$RETURN_ARGUMENT, RDB$SQL_SECURITY
            FROM RDB$FUNCTIONS
            WHERE RDB$FUNCTION_NAME = ? AND RDB$PACKAGE_NAME IS NULL""";

    // O argumento de posição RDB$RETURN_ARGUMENT é o retorno; os outros são as entradas, na ordem.
    private static final String FUNCTION_ARGUMENTS_SQL = """
            SELECT A.RDB$ARGUMENT_NAME AS NAME, A.RDB$ARGUMENT_POSITION AS ARG_POSITION, A.RDB$NULL_FLAG AS NULL_FLAG,
                   A.RDB$ARGUMENT_MECHANISM AS MECHANISM, A.RDB$FIELD_SOURCE AS FIELD_SOURCE,
                   A.RDB$RELATION_NAME AS RELATION_NAME, A.RDB$FIELD_NAME AS FIELD_NAME,
                   A.RDB$DEFAULT_SOURCE AS DEFAULT_SOURCE,
                   F.RDB$FIELD_TYPE, F.RDB$FIELD_SUB_TYPE, F.RDB$FIELD_SCALE, F.RDB$FIELD_PRECISION,
                   F.RDB$CHARACTER_LENGTH, CS.RDB$CHARACTER_SET_NAME
            FROM RDB$FUNCTION_ARGUMENTS A
            JOIN RDB$FIELDS F ON F.RDB$FIELD_NAME = A.RDB$FIELD_SOURCE
            LEFT JOIN RDB$CHARACTER_SETS CS ON CS.RDB$CHARACTER_SET_ID = F.RDB$CHARACTER_SET_ID
            WHERE A.RDB$FUNCTION_NAME = ? AND A.RDB$PACKAGE_NAME IS NULL
            ORDER BY A.RDB$ARGUMENT_POSITION""";

    private static final String PACKAGE_SQL = """
            SELECT RDB$PACKAGE_HEADER_SOURCE, RDB$PACKAGE_BODY_SOURCE
            FROM RDB$PACKAGES
            WHERE RDB$PACKAGE_NAME = ?""";

    private static final String PACKAGES_SQL = """
            SELECT RDB$PACKAGE_NAME
            FROM RDB$PACKAGES
            WHERE RDB$PACKAGE_BODY_SOURCE IS NOT NULL AND COALESCE(RDB$SYSTEM_FLAG, 0) = 0
            ORDER BY RDB$PACKAGE_NAME""";

    private static final String TRIGGER_SQL = """
            SELECT RDB$RELATION_NAME, RDB$TRIGGER_TYPE, RDB$TRIGGER_SOURCE
            FROM RDB$TRIGGERS
            WHERE RDB$TRIGGER_NAME = ?""";

    private static final String COLUMNS_SQL = """
            SELECT RF.RDB$FIELD_NAME AS NAME,
                   F.RDB$FIELD_TYPE, F.RDB$FIELD_SUB_TYPE, F.RDB$FIELD_SCALE, F.RDB$FIELD_PRECISION,
                   F.RDB$CHARACTER_LENGTH, CS.RDB$CHARACTER_SET_NAME
            FROM RDB$RELATION_FIELDS RF
            JOIN RDB$FIELDS F ON F.RDB$FIELD_NAME = RF.RDB$FIELD_SOURCE
            LEFT JOIN RDB$CHARACTER_SETS CS ON CS.RDB$CHARACTER_SET_ID = F.RDB$CHARACTER_SET_ID
            WHERE RF.RDB$RELATION_NAME = ?
            ORDER BY RF.RDB$FIELD_POSITION""";

    /** {@code RDB$TRIGGER_TYPE}: os bits que separam trigger de tabela, de banco e de DDL. */
    private static final long TRIGGER_TYPE_MASK = 0x6000;
    private static final long TRIGGER_TYPE_DB = 0x2000;

    /** Eventos de trigger de banco, pelo {@code RDB$TRIGGER_TYPE} menos {@link #TRIGGER_TYPE_DB}. */
    private static final List<String> DATABASE_EVENTS = List.of("ON CONNECT", "ON DISCONNECT",
            "ON TRANSACTION START", "ON TRANSACTION COMMIT", "ON TRANSACTION ROLLBACK");

    /** Ações de trigger de tabela, pelo número de cada posição de {@code RDB$TRIGGER_TYPE}. */
    private static final List<String> TRIGGER_ACTIONS = List.of("", "INSERT", "UPDATE", "DELETE");

    /**
     * Fonte do banco de uma trigger.
     *
     * @param text {@code CREATE TRIGGER nome FOR tabela <fase e eventos>} numa linha e, a partir da linha 2,
     *            {@code RDB$TRIGGER_SOURCE} intacto (que começa no {@code AS}), como o editor do DBeaver mostra
     * @param columns colunas da tabela, na ordem dela, com o tipo base de cada uma (sem o domínio, cujas restrições o
     *            motor só confere ao gravar a linha); vazio numa trigger de banco ou de DDL
     */
    public record TriggerSource(String text, List<RoutineParser.Column> columns) {
    }

    /**
     * Fonte do banco de uma package: o cabeçalho (onde ficam os valores padrão dos parâmetros) e o corpo (onde
     * ficam as rotinas). A Fonte de uma rotina de package é o corpo inteiro.
     *
     * @param header {@code CREATE OR ALTER PACKAGE nome AS ...}, com o cabeçalho idêntico ao gravado no banco
     * @param body {@code RECREATE PACKAGE BODY nome AS ...}, com o corpo idêntico ao gravado no banco
     */
    public record PackageSource(String header, String body) {
    }

    private RoutineSourceLoader() {
    }

    /**
     * @param name nome da procedure (fora de package) como está nos metadados
     * @return {@code CREATE OR ALTER PROCEDURE ...} completo, com o corpo idêntico ao gravado no banco
     */
    public static String loadProcedure(Connection connection, String name) throws SQLException {
        String body;
        try (PreparedStatement statement = connection.prepareStatement(PROCEDURE_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Procedure não encontrada: " + name);
                }
                body = resultSet.getString(1);
            }
        }
        if (body == null) {
            throw new SQLException("Procedure sem Fonte do banco (externa ou sem fonte): " + name);
        }

        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(PROCEDURE_PARAMETERS_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    (resultSet.getInt("DIRECTION") == 0 ? inputs : outputs).add(parameter(resultSet));
                }
            }
        }

        StringBuilder text = new StringBuilder("CREATE OR ALTER PROCEDURE ").append(Identifiers.quote(name))
                .append(' ');
        appendInputs(text, inputs);
        if (!outputs.isEmpty()) {
            text.append("RETURNS (\n");
            for (int i = 0; i < outputs.size(); i++) {
                text.append('\t').append(outputs.get(i)).append(i < outputs.size() - 1 ? ",\n" : "\n");
            }
            text.append(")\n");
        }
        return text.append("AS\n").append(body).toString();
    }

    /**
     * @param name nome da function (fora de package) como está nos metadados
     * @return {@code CREATE OR ALTER FUNCTION ...} completo, com o corpo idêntico ao gravado no banco
     */
    public static String loadFunction(Connection connection, String name) throws SQLException {
        String body;
        int returnPosition;
        String sqlSecurity;
        try (PreparedStatement statement = connection.prepareStatement(FUNCTION_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Function não encontrada: " + name);
                }
                body = resultSet.getString(1);
                returnPosition = resultSet.getInt(2);
                boolean definer = resultSet.getBoolean(3);
                sqlSecurity = resultSet.wasNull() ? null : definer ? "DEFINER" : "INVOKER";
            }
        }
        if (body == null) {
            throw new SQLException("Function sem Fonte do banco (externa ou sem fonte): " + name);
        }

        List<String> inputs = new ArrayList<>();
        String returns = null;
        try (PreparedStatement statement = connection.prepareStatement(FUNCTION_ARGUMENTS_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (resultSet.getInt("ARG_POSITION") == returnPosition) {
                        returns = type(resultSet) + (resultSet.getInt("NULL_FLAG") == 1 ? " NOT NULL" : "");
                    } else {
                        inputs.add(parameter(resultSet));
                    }
                }
            }
        }
        if (returns == null) {
            throw new SQLException("Function sem tipo de retorno nos metadados: " + name);
        }

        StringBuilder text = new StringBuilder("CREATE OR ALTER FUNCTION ").append(Identifiers.quote(name))
                .append(' ');
        appendInputs(text, inputs);
        text.append("RETURNS ").append(returns).append('\n');
        if (sqlSecurity != null) {
            text.append("SQL SECURITY ").append(sqlSecurity).append('\n'); // como o DBeaver, numa linha própria
        }
        return text.append("AS\n").append(body).toString();
    }

    /** @return os nomes, como estão nos metadados, das packages de usuário que têm corpo, em ordem alfabética */
    public static List<String> listPackages(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(PACKAGES_SQL);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1).strip());
            }
        }
        return names;
    }

    /** @param name nome da package como está nos metadados */
    public static PackageSource loadPackage(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(PACKAGE_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Package não encontrada: " + name);
                }
                String header = resultSet.getString(1);
                String body = resultSet.getString(2);
                if (header == null || body == null) {
                    throw new SQLException("Package sem corpo ou sem Fonte do banco: " + name);
                }
                String quoted = Identifiers.quote(name);
                return new PackageSource("CREATE OR ALTER PACKAGE " + quoted + "\nAS\n" + header,
                        "RECREATE PACKAGE BODY " + quoted + "\nAS\n" + body);
            }
        }
    }

    /**
     * Trigger de tabela, de banco ou de DDL (estas duas o Pré-check recusa). O cabeçalho segue o de
     * {@code FireBirdUtils.getTriggerSourceWithHeader} do DBeaver 26.2.2, sem {@code ACTIVE} nem {@code POSITION};
     * numa trigger de DDL, que o DBeaver não mostra, os eventos viram {@code ANY DDL STATEMENT}.
     *
     * @param name nome da trigger como está nos metadados
     */
    public static TriggerSource loadTrigger(Connection connection, String name) throws SQLException {
        String relation;
        long type;
        String body;
        try (PreparedStatement statement = connection.prepareStatement(TRIGGER_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Trigger não encontrada: " + name);
                }
                relation = trimmed(resultSet, "RDB$RELATION_NAME");
                type = resultSet.getLong("RDB$TRIGGER_TYPE");
                body = resultSet.getString("RDB$TRIGGER_SOURCE");
            }
        }
        if (body == null) {
            throw new SQLException("Trigger sem Fonte do banco (externa ou sem fonte): " + name);
        }
        StringBuilder text = new StringBuilder("CREATE TRIGGER ").append(Identifiers.quote(name)).append(' ');
        List<RoutineParser.Column> columns = new ArrayList<>();
        if (relation != null) {
            text.append("FOR ").append(Identifiers.quote(relation)).append(' ').append(tableTriggerType(type));
            try (PreparedStatement statement = connection.prepareStatement(COLUMNS_SQL)) {
                statement.setString(1, relation);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        columns.add(new RoutineParser.Column(trimmed(resultSet, "NAME"),
                                FieldTypes.render(resultSet.getInt("RDB$FIELD_TYPE"),
                                        intOrNull(resultSet, "RDB$FIELD_SUB_TYPE"),
                                        intOrNull(resultSet, "RDB$FIELD_SCALE"),
                                        intOrNull(resultSet, "RDB$FIELD_PRECISION"),
                                        intOrNull(resultSet, "RDB$CHARACTER_LENGTH"),
                                        trimmed(resultSet, "RDB$CHARACTER_SET_NAME"))));
                    }
                }
            }
        } else if ((type & TRIGGER_TYPE_MASK) == TRIGGER_TYPE_DB) {
            text.append(DATABASE_EVENTS.get((int) (type - TRIGGER_TYPE_DB)));
        } else {
            text.append((type & 1) == 0 ? "BEFORE" : "AFTER").append(" ANY DDL STATEMENT");
        }
        return new TriggerSource(text.append('\n').append(body).toString(), columns);
    }

    /**
     * Fase e eventos de uma trigger de tabela. O {@code RDB$TRIGGER_TYPE} guarda até três ações (1 = INSERT,
     * 2 = UPDATE, 3 = DELETE) em pares de bits a partir do bit 1, e é ímpar numa {@code BEFORE} (1 = BEFORE INSERT,
     * 17 = BEFORE INSERT OR UPDATE, 114 = AFTER INSERT OR UPDATE OR DELETE).
     */
    private static String tableTriggerType(long type) {
        boolean before = (type & 1) == 1;
        long slots = before ? type + 1 : type;
        StringBuilder text = new StringBuilder(before ? "BEFORE" : "AFTER");
        String separator = " ";
        for (int shift = 1; shift <= 5; shift += 2) {
            int action = (int) ((slots >> shift) & 3);
            if (action != 0) {
                text.append(separator).append(TRIGGER_ACTIONS.get(action));
                separator = " OR ";
            }
        }
        return text.toString();
    }

    /** {@code (a, b)} e a quebra de linha, como o DBeaver; sem entradas, nada. */
    private static void appendInputs(StringBuilder text, List<String> inputs) {
        if (!inputs.isEmpty()) {
            text.append('(').append(String.join(", ", inputs)).append(")\n");
        }
    }

    private static String parameter(ResultSet row) throws SQLException {
        StringBuilder text = new StringBuilder(Identifiers.quote(trimmed(row, "NAME"))).append(' ')
                .append(type(row));
        if (row.getInt("NULL_FLAG") == 1) {
            text.append(" NOT NULL");
        }
        String defaultSource = row.getString("DEFAULT_SOURCE");
        if (defaultSource != null) {
            text.append(' ').append(defaultSource.strip());
        }
        return text.toString();
    }

    private static String type(ResultSet row) throws SQLException {
        String relation = trimmed(row, "RELATION_NAME");
        String column = trimmed(row, "FIELD_NAME");
        if (relation != null && column != null) {
            return "TYPE OF COLUMN " + Identifiers.quote(relation) + '.' + Identifiers.quote(column);
        }
        String fieldSource = trimmed(row, "FIELD_SOURCE");
        if (!fieldSource.startsWith("RDB$")) {
            boolean typeOf = row.getInt("MECHANISM") == 1;
            return (typeOf ? "TYPE OF " : "") + Identifiers.quote(fieldSource);
        }
        return FieldTypes.render(row.getInt("RDB$FIELD_TYPE"), intOrNull(row, "RDB$FIELD_SUB_TYPE"),
                intOrNull(row, "RDB$FIELD_SCALE"), intOrNull(row, "RDB$FIELD_PRECISION"),
                intOrNull(row, "RDB$CHARACTER_LENGTH"), trimmed(row, "RDB$CHARACTER_SET_NAME"));
    }

    private static String trimmed(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : value.strip();
    }

    private static Integer intOrNull(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }
}
