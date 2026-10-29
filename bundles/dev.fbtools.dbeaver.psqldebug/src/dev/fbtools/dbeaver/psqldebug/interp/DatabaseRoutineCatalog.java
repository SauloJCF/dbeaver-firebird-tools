package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;

/**
 * Catálogo lido dos metadados, na conexão da Sessão de debug. Cada Rotina é carregada e parseada uma vez só: a
 * Fonte do banco não muda durante a sessão.
 */
public final class DatabaseRoutineCatalog implements RoutineCatalog {

    // Rotinas sem Fonte do banco (externas, UDF) ficam de fora: não há o que interpretar.
    private static final String PROCEDURE_SQL = """
            SELECT 1 FROM RDB$PROCEDURES
            WHERE RDB$PROCEDURE_NAME = ? AND RDB$PACKAGE_NAME IS NOT DISTINCT FROM ? AND RDB$ENGINE_NAME IS NULL""";

    private static final String FUNCTION_SQL = """
            SELECT 1 FROM RDB$FUNCTIONS
            WHERE RDB$FUNCTION_NAME = ? AND RDB$PACKAGE_NAME IS NOT DISTINCT FROM ? AND RDB$ENGINE_NAME IS NULL
              AND COALESCE(RDB$LEGACY_FLAG, 0) = 0""";

    private static final String PACKAGE_ROUTINES_SQL = """
            SELECT RDB$PROCEDURE_NAME, RDB$PRIVATE_FLAG FROM RDB$PROCEDURES WHERE RDB$PACKAGE_NAME = ?
            UNION ALL
            SELECT RDB$FUNCTION_NAME, RDB$PRIVATE_FLAG FROM RDB$FUNCTIONS WHERE RDB$PACKAGE_NAME = ?""";

    private final Connection connection;
    private final Map<List<Object>, Loaded> routines = new HashMap<>();
    private final Map<String, PackageRoutines> packages = new HashMap<>();

    public DatabaseRoutineCatalog(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Optional<RoutineDefinition> find(RoutineDefinition.Kind kind, String callerPackage, String packageName,
            String name) throws SQLException {
        String pkg = packageName;
        if (pkg == null && callerPackage != null && packageRoutines(callerPackage).contains(name)) {
            pkg = callerPackage;
        }
        List<Object> key = Arrays.asList(kind, pkg, name);
        Loaded loaded = routines.get(key);
        if (loaded == null) {
            loaded = load(kind, pkg, name);
            routines.put(key, loaded);
        }
        if (loaded.error() != null) {
            throw loaded.error();
        }
        return Optional.ofNullable(loaded.routine());
    }

    @Override
    public Set<String> packageRoutines(String packageName) throws SQLException {
        return routinesOf(packageName).all();
    }

    @Override
    public Set<String> privateRoutines(String packageName) throws SQLException {
        return routinesOf(packageName).privates();
    }

    /** Rotinas de uma package: todas e as privadas. */
    private record PackageRoutines(Set<String> all, Set<String> privates) {
    }

    private PackageRoutines routinesOf(String packageName) throws SQLException {
        PackageRoutines routines = packages.get(packageName);
        if (routines == null) {
            Set<String> all = new HashSet<>();
            Set<String> privates = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(PACKAGE_ROUTINES_SQL)) {
                statement.setString(1, packageName);
                statement.setString(2, packageName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String name = resultSet.getString(1).strip();
                        all.add(name);
                        if (resultSet.getInt(2) == 1) {
                            privates.add(name);
                        }
                    }
                }
            }
            routines = new PackageRoutines(Set.copyOf(all), Set.copyOf(privates));
            packages.put(packageName, routines);
        }
        return routines;
    }

    /** Rotina carregada, ou {@code null} se não existe; ou o erro de sintaxe da Fonte dela. */
    private record Loaded(RoutineDefinition routine, PsqlSyntaxException error) {
    }

    private Loaded load(RoutineDefinition.Kind kind, String pkg, String name) throws SQLException {
        boolean function = kind == RoutineDefinition.Kind.FUNCTION;
        try (PreparedStatement statement = connection.prepareStatement(function ? FUNCTION_SQL : PROCEDURE_SQL)) {
            statement.setString(1, name);
            statement.setString(2, pkg);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new Loaded(null, null);
                }
            }
        }
        try {
            if (pkg != null) {
                RoutineSourceLoader.PackageSource source = RoutineSourceLoader.loadPackage(connection, pkg);
                return new Loaded(RoutineParser.parsePackageRoutine(source.header(), source.body(), kind, name),
                        null);
            }
            String text = function ? RoutineSourceLoader.loadFunction(connection, name)
                    : RoutineSourceLoader.loadProcedure(connection, name);
            return new Loaded(RoutineParser.parse(text), null);
        } catch (PsqlSyntaxException e) {
            return new Loaded(null, e);
        }
    }
}
