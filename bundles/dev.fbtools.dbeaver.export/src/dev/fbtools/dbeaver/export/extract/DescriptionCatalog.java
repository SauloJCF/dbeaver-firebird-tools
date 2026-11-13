package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * As descrições ({@code RDB$DESCRIPTION}) dos objetos da seleção, sempre como {@code COMMENT ON}. Cobre domínios,
 * generators, exceptions, roles, collations, BLOB filters, UDFs, tabelas, views e suas colunas, índices, procedures e
 * seus parâmetros, functions, packages e triggers. Objetos dentro de packages e a descrição do banco ficam de fora.
 */
final class DescriptionCatalog {

    /** Um {@code COMMENT ON <kind> <target> IS '<text>'}; {@code target} já vem com os nomes entre aspas quando preciso. */
    record Description(String kind, String target, String text) {

        String toSql() {
            return "COMMENT ON " + kind + " " + target + " IS '" + text.replace("'", "''") + "';";
        }
    }

    private static final String DOMAINS = """
            SELECT RDB$FIELD_NAME, RDB$DESCRIPTION FROM RDB$FIELDS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$FIELD_NAME NOT STARTING WITH 'RDB$'
               AND RDB$DESCRIPTION IS NOT NULL ORDER BY RDB$FIELD_NAME""";

    private static final String GENERATORS = """
            SELECT RDB$GENERATOR_NAME, RDB$DESCRIPTION FROM RDB$GENERATORS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$DESCRIPTION IS NOT NULL ORDER BY RDB$GENERATOR_NAME""";

    private static final String EXCEPTIONS = """
            SELECT RDB$EXCEPTION_NAME, RDB$DESCRIPTION FROM RDB$EXCEPTIONS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$DESCRIPTION IS NOT NULL ORDER BY RDB$EXCEPTION_NAME""";

    private static final String ROLES = """
            SELECT RDB$ROLE_NAME, RDB$DESCRIPTION FROM RDB$ROLES
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$DESCRIPTION IS NOT NULL ORDER BY RDB$ROLE_NAME""";

    private static final String COLLATIONS = """
            SELECT RDB$COLLATION_NAME, RDB$DESCRIPTION FROM RDB$COLLATIONS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$DESCRIPTION IS NOT NULL ORDER BY RDB$COLLATION_NAME""";

    private static final String FILTERS = """
            SELECT RDB$FUNCTION_NAME, RDB$DESCRIPTION FROM RDB$FILTERS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$DESCRIPTION IS NOT NULL ORDER BY RDB$FUNCTION_NAME""";

    // As functions legadas (UDFs) têm o comando próprio EXTERNAL FUNCTION.
    private static final String FUNCTIONS = """
            SELECT RDB$FUNCTION_NAME, COALESCE(RDB$LEGACY_FLAG, 0), RDB$DESCRIPTION FROM RDB$FUNCTIONS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$PACKAGE_NAME IS NULL AND RDB$DESCRIPTION IS NOT NULL
             ORDER BY RDB$FUNCTION_NAME""";

    private static final String PACKAGES = """
            SELECT RDB$PACKAGE_NAME, RDB$DESCRIPTION FROM RDB$PACKAGES
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$DESCRIPTION IS NOT NULL ORDER BY RDB$PACKAGE_NAME""";

    private static final String PROCEDURES = """
            SELECT RDB$PROCEDURE_NAME, RDB$DESCRIPTION FROM RDB$PROCEDURES
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$PACKAGE_NAME IS NULL AND RDB$DESCRIPTION IS NOT NULL
             ORDER BY RDB$PROCEDURE_NAME""";

    private static final String PARAMETERS = """
            SELECT p.RDB$PROCEDURE_NAME, p.RDB$PARAMETER_NAME, p.RDB$DESCRIPTION FROM RDB$PROCEDURE_PARAMETERS p
              JOIN RDB$PROCEDURES r ON r.RDB$PROCEDURE_NAME = p.RDB$PROCEDURE_NAME AND r.RDB$PACKAGE_NAME IS NULL
             WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0 AND p.RDB$PACKAGE_NAME IS NULL AND p.RDB$DESCRIPTION IS NOT NULL
             ORDER BY p.RDB$PROCEDURE_NAME, p.RDB$PARAMETER_TYPE, p.RDB$PARAMETER_NUMBER""";

    private static final String RELATIONS = """
            SELECT RDB$RELATION_NAME, CASE WHEN RDB$VIEW_BLR IS NULL THEN 0 ELSE 1 END, RDB$DESCRIPTION
              FROM RDB$RELATIONS WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$DESCRIPTION IS NOT NULL
             ORDER BY RDB$RELATION_NAME""";

    private static final String COLUMNS = """
            SELECT rf.RDB$RELATION_NAME, CASE WHEN r.RDB$VIEW_BLR IS NULL THEN 0 ELSE 1 END, rf.RDB$FIELD_NAME,
                   rf.RDB$DESCRIPTION
              FROM RDB$RELATION_FIELDS rf
              JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = rf.RDB$RELATION_NAME
             WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0 AND rf.RDB$DESCRIPTION IS NOT NULL
             ORDER BY rf.RDB$RELATION_NAME, rf.RDB$FIELD_POSITION""";

    // Os índices das constraints saem com a constraint, e não têm COMMENT ON.
    private static final String INDICES = """
            SELECT i.RDB$INDEX_NAME, i.RDB$DESCRIPTION FROM RDB$INDICES i
              JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = i.RDB$RELATION_NAME
             WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0 AND i.RDB$DESCRIPTION IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM RDB$RELATION_CONSTRAINTS rc WHERE rc.RDB$INDEX_NAME = i.RDB$INDEX_NAME)
             ORDER BY i.RDB$INDEX_NAME""";

    private static final String TRIGGERS = """
            SELECT t.RDB$TRIGGER_NAME, t.RDB$DESCRIPTION FROM RDB$TRIGGERS t
             WHERE COALESCE(t.RDB$SYSTEM_FLAG, 0) = 0 AND t.RDB$DESCRIPTION IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM RDB$CHECK_CONSTRAINTS c WHERE c.RDB$TRIGGER_NAME = t.RDB$TRIGGER_NAME)
             ORDER BY t.RDB$TRIGGER_NAME""";

    // Descrições que o COMMENT ON desta classe não alcança: parâmetros de function e rotinas dentro de packages.
    private static final String SKIPPED = """
            SELECT (SELECT COUNT(*) FROM RDB$FUNCTION_ARGUMENTS WHERE RDB$DESCRIPTION IS NOT NULL)
                 + (SELECT COUNT(*) FROM RDB$PROCEDURE_PARAMETERS WHERE RDB$PACKAGE_NAME IS NOT NULL
                       AND RDB$DESCRIPTION IS NOT NULL)
                 + (SELECT COUNT(*) FROM RDB$PROCEDURES WHERE RDB$PACKAGE_NAME IS NOT NULL
                       AND RDB$DESCRIPTION IS NOT NULL)
                 + (SELECT COUNT(*) FROM RDB$FUNCTIONS WHERE RDB$PACKAGE_NAME IS NOT NULL
                       AND RDB$DESCRIPTION IS NOT NULL)
              FROM RDB$DATABASE""";

    private final Connection connection;

    DescriptionCatalog(Connection connection) {
        this.connection = connection;
    }

    /** Avisa de quantas descrições o script não leva (parâmetros de function e o que mora em packages). */
    void warnAboutSkipped(ExtractionProgress progress) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(SKIPPED)) {
            rs.next();
            if (rs.getInt(1) > 0) {
                progress.warn(rs.getInt(1) + " descrições de parâmetros de function ou de rotinas dentro de packages"
                        + " não saem no script (COMMENT ON delas ainda não é gerado).");
            }
        }
    }

    /** As descrições dos objetos da seleção, agrupadas por tipo na ordem das Fases e, dentro do tipo, pelo nome. */
    List<Description> read(Selection selection, ExtractionOptions options, ExtractionProgress progress)
            throws SQLException {
        List<Description> all = new ArrayList<>();
        simple(all, DOMAINS, "DOMAIN", selection::includesDomain, options, progress);
        simple(all, GENERATORS, "GENERATOR", selection::includesGenerator, options, progress);
        simple(all, EXCEPTIONS, "EXCEPTION", selection::includesException, options, progress);
        simple(all, ROLES, "ROLE", selection::includesRole, options, progress);
        simple(all, COLLATIONS, "COLLATION", selection::includesCollation, options, progress);
        simple(all, FILTERS, "FILTER", selection::includesFilter, options, progress);
        functions(all, selection, options, progress);
        relations(all, selection, options, progress);
        simple(all, INDICES, "INDEX", selection::includesIndex, options, progress);
        simple(all, PROCEDURES, "PROCEDURE", selection::includesProcedure, options, progress);
        parameters(all, selection, options, progress);
        simple(all, PACKAGES, "PACKAGE", selection::includesPackage, options, progress);
        simple(all, TRIGGERS, "TRIGGER", selection::includesTrigger, options, progress);
        return all;
    }

    private void simple(List<Description> out, String sql, String kind, Predicate<String> included,
            ExtractionOptions options, ExtractionProgress progress) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString(1).strip();
                String text = Rdb.source(rs.getString(2));
                if (text != null && included.test(name)) {
                    out.add(new Description(kind, options.quote(name), text));
                }
            }
        }
    }

    private void functions(List<Description> out, Selection selection, ExtractionOptions options,
            ExtractionProgress progress) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(FUNCTIONS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString(1).strip();
                boolean legacy = rs.getInt(2) == 1;
                String text = Rdb.source(rs.getString(3));
                if (text != null && (legacy ? selection.includesUdf(name) : selection.includesFunction(name))) {
                    out.add(new Description(legacy ? "EXTERNAL FUNCTION" : "FUNCTION", options.quote(name), text));
                }
            }
        }
    }

    /** Tabelas e views, cada uma seguida das descrições de suas colunas. */
    private void relations(List<Description> out, Selection selection, ExtractionOptions options,
            ExtractionProgress progress) throws SQLException {
        List<Description> tables = new ArrayList<>();
        List<Description> views = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(RELATIONS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString(1).strip();
                boolean view = rs.getInt(2) == 1;
                String text = Rdb.source(rs.getString(3));
                if (text != null && (view ? selection.includesView(name) : selection.includesTable(name))) {
                    (view ? views : tables).add(new Description(view ? "VIEW" : "TABLE", options.quote(name), text));
                }
            }
        }
        List<Description> tableColumns = new ArrayList<>();
        List<Description> viewColumns = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(COLUMNS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String relation = rs.getString(1).strip();
                boolean view = rs.getInt(2) == 1;
                String text = Rdb.source(rs.getString(4));
                if (text != null && (view ? selection.includesView(relation) : selection.includesTable(relation))) {
                    (view ? viewColumns : tableColumns).add(new Description("COLUMN",
                            options.quote(relation) + "." + options.quote(rs.getString(3).strip()), text));
                }
            }
        }
        out.addAll(tables);
        out.addAll(tableColumns);
        out.addAll(views);
        out.addAll(viewColumns);
    }

    private void parameters(List<Description> out, Selection selection, ExtractionOptions options,
            ExtractionProgress progress) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(PARAMETERS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String procedure = rs.getString(1).strip();
                String text = Rdb.source(rs.getString(3));
                if (text != null && selection.includesProcedure(procedure)) {
                    out.add(new Description("PARAMETER",
                            options.quote(procedure) + "." + options.quote(rs.getString(2).strip()), text));
                }
            }
        }
    }
}
