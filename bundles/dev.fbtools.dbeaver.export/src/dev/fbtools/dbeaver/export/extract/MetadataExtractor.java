package dev.fbtools.dbeaver.export.extract;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gera o Script de metadata lendo {@code RDB$*} direto (ADR 0007), na ordem das Fases: collations, BLOB filters e
 * UDFs, domínios, generators, exceptions, roles e mappings, tabelas com chaves primárias e únicas, colunas calculadas
 * separadas, views em ordem topológica, Cabeçalhos vazios de rotinas, índices, checks e FKs, os corpos das rotinas
 * intercalados com as views que as leem, triggers, {@code SET GENERATOR}, a publication padrão e as descrições. A
 * conexão deve usar o charset padrão do banco, para que colunas {@code NONE} cheguem byte a byte (ADR 0009).
 */
public final class MetadataExtractor {

    private static final String DOMAINS = """
            SELECT f.RDB$FIELD_NAME, f.RDB$FIELD_TYPE, f.RDB$FIELD_SUB_TYPE, f.RDB$FIELD_LENGTH,
                   f.RDB$CHARACTER_LENGTH, f.RDB$FIELD_PRECISION, f.RDB$FIELD_SCALE, f.RDB$SEGMENT_LENGTH,
                   f.RDB$DEFAULT_SOURCE, f.RDB$VALIDATION_SOURCE, f.RDB$NULL_FLAG, f.RDB$DESCRIPTION,
                   cs.RDB$CHARACTER_SET_NAME, co.RDB$COLLATION_NAME
              FROM RDB$FIELDS f
              LEFT JOIN RDB$CHARACTER_SETS cs ON cs.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
              LEFT JOIN RDB$COLLATIONS co ON co.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
                                         AND co.RDB$COLLATION_ID = f.RDB$COLLATION_ID
             WHERE COALESCE(f.RDB$SYSTEM_FLAG, 0) = 0 AND f.RDB$FIELD_NAME NOT STARTING WITH 'RDB$'
             ORDER BY f.RDB$FIELD_NAME""";

    // Generators de identity (RDB$SYSTEM_FLAG = 6) e de sistema pertencem à tabela ou ao servidor, não ao script.
    private static final String GENERATORS = """
            SELECT RDB$GENERATOR_NAME, RDB$DESCRIPTION, RDB$INITIAL_VALUE, RDB$GENERATOR_INCREMENT
              FROM RDB$GENERATORS WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 ORDER BY RDB$GENERATOR_NAME""";

    private final Connection connection;

    public MetadataExtractor(Connection connection) {
        this.connection = connection;
    }

    /** Nomes dos domínios do usuário, para a aba Objects. */
    public List<String> listDomains() throws SQLException {
        List<String> names = new ArrayList<>();
        for (Domain domain : readDomains(Selection.all(), ExtractionProgress.NONE)) {
            names.add(domain.name());
        }
        return names;
    }

    /** Nomes dos generators do usuário, para a aba Objects. */
    public List<String> listGenerators() throws SQLException {
        return new ArrayList<>(readGenerators(Selection.all()).keySet());
    }

    /** Nomes das tabelas do usuário, para a aba Objects. */
    public List<String> listTables() throws SQLException {
        return new TableCatalog(connection).listTableNames();
    }

    /** Nomes dos índices do usuário (sem os das constraints), para a aba Objects. */
    public List<String> listIndexes() throws SQLException {
        return new TableCatalog(connection).readIndexes(Selection.all(), ExtractionProgress.NONE).stream()
                .map(TableCatalog.Index::name).toList();
    }

    /** Nomes das views do usuário, para a aba Objects. */
    public List<String> listViews() throws SQLException {
        return new ViewCatalog(connection).listViewNames();
    }

    /** Nomes das procedures do usuário (fora de packages), para a aba Objects. */
    public List<String> listProcedures() throws SQLException {
        return new RoutineCatalog(connection).listProcedureNames();
    }

    /** Nomes das functions do usuário (fora de packages e sem as UDFs legadas), para a aba Objects. */
    public List<String> listFunctions() throws SQLException {
        return new RoutineCatalog(connection).listFunctionNames();
    }

    /** Nomes dos packages do usuário, para a aba Objects. */
    public List<String> listPackages() throws SQLException {
        return new RoutineCatalog(connection).listPackageNames();
    }

    /** Nomes das exceptions do usuário, para a aba Objects. */
    public List<String> listExceptions() throws SQLException {
        return new ObjectCatalog(connection).listExceptionNames();
    }

    /** Nomes das UDFs (functions legadas) do usuário, para a aba Objects. */
    public List<String> listUdfs() throws SQLException {
        return new ObjectCatalog(connection).listUdfNames();
    }

    /** Nomes das collations do usuário, para a aba Objects. */
    public List<String> listCollations() throws SQLException {
        return new ObjectCatalog(connection).listCollationNames();
    }

    /** Nomes dos BLOB filters do usuário, para a aba Objects. */
    public List<String> listFilters() throws SQLException {
        return new ObjectCatalog(connection).listFilterNames();
    }

    /** Nomes das roles do usuário, para a aba Objects. */
    public List<String> listRoles() throws SQLException {
        return new ObjectCatalog(connection).listRoleNames();
    }

    /** Nomes dos triggers do usuário (sem os das constraints), para a aba Objects. */
    public List<String> listTriggers() throws SQLException {
        return new TriggerCatalog(connection).listTriggerNames();
    }

    /** A publication padrão, se o banco a usa (ligada, com tabelas ou inclusão automática), para a aba Objects. */
    public List<String> listPublications() throws SQLException {
        return new ObjectCatalog(connection).listPublicationNames();
    }

    /** Nomes dos mappings locais, para a aba Objects. */
    public List<String> listMappings() throws SQLException {
        return new ObjectCatalog(connection).listMappingNames();
    }

    /** Como {@link #extract(Selection, ExtractionOptions, Writer, String, ExtractionProgress)} com as opções padrão. */
    public void extract(Selection selection, Writer out, String setNames, ExtractionProgress progress)
            throws SQLException, IOException {
        extract(selection, ExtractionOptions.DEFAULT, out, setNames, progress);
    }

    /**
     * Escreve o script. Não fecha o {@code out}. {@code setNames} é o charset do {@code SET NAMES} (o do arquivo);
     * {@code null} usa o padrão do banco.
     *
     * @throws ExtractionCancelledException se {@code progress} pediu o cancelamento
     */
    public void extract(Selection selection, ExtractionOptions options, Writer out, String setNames,
            ExtractionProgress progress) throws SQLException, IOException {
        DatabaseInfo info = DatabaseInfo.read(connection);
        TableCatalog catalog = new TableCatalog(connection);
        List<Domain> domains = readDomains(selection, progress);
        Map<String, Generator> generators = readGenerators(selection);
        ObjectCatalog objects = new ObjectCatalog(connection);
        List<ObjectCatalog.Collation> collations = objects.readCollations(selection);
        List<ObjectCatalog.BlobFilter> filters = objects.readFilters(selection);
        List<ObjectCatalog.Udf> udfs = objects.readUdfs(selection);
        List<ObjectCatalog.ExceptionDef> exceptions = objects.readExceptions(selection);
        List<ObjectCatalog.Role> roles = objects.readRoles(selection);
        List<ObjectCatalog.Mapping> mappings = objects.readMappings(selection);
        List<TriggerCatalog.Trigger> triggers = new TriggerCatalog(connection).readTriggers(selection, progress);
        ObjectCatalog.Publication publication = selection.includesPublication(ObjectCatalog.DEFAULT_PUBLICATION)
                ? objects.readPublication() : null;
        List<TableCatalog.Table> tables = catalog.readTables(selection, progress);
        List<TableCatalog.Index> indexes = catalog.readIndexes(selection, progress);
        warnIfConnectionCharsetDiffers(info, progress);
        warnAboutNoneColumns(info, domains, tables, progress);
        ViewCatalog.Views views = new ViewCatalog(connection).readViews(selection, progress);
        RoutineCatalog routineCatalog = new RoutineCatalog(connection);
        RoutineCatalog.Routines routines = routineCatalog.readRoutines(selection, progress);
        warnAboutMissingDependencies(domains, tables, views, routines, progress);
        warnAboutRoutinesUsedByTables(routineCatalog.readTableUsers(), selection, progress);
        progress.begin(domains.size() + generators.size() + tables.size() + views.size() + indexes.size()
                + routines.size() + collations.size() + filters.size() + udfs.size() + exceptions.size()
                + roles.size() + mappings.size() + triggers.size());

        if (!options.omitHeader()) {
            out.write("SET SQL DIALECT 3;\n");
            out.write("SET NAMES " + (setNames != null ? setNames : info.defaultCharacterSet()) + ";\n");
        }
        if (options.createDatabase()) {
            out.write("\n" + createDatabase(info) + "\n");
        }

        // Collations, filters e UDFs antes dos domínios, que podem usar uma collation; as UDFs, das colunas calculadas.
        writeObjects(out, "Collations", collations, ObjectCatalog.Collation::name, "Collation ",
                collation -> collation.toSql(options), progress);
        writeObjects(out, "BLOB filters", filters, ObjectCatalog.BlobFilter::name, "BLOB filter ",
                filter -> filter.toSql(options), progress);
        writeObjects(out, "UDFs", udfs, ObjectCatalog.Udf::name, "UDF ",
                udf -> udf.toSql(options, info.defaultCharacterSet()), progress);

        if (!domains.isEmpty()) {
            out.write("\n/* Domains */\n\n");
        }
        for (Domain domain : domains) {
            progress.checkCancelled();
            out.write(domain.toSql(options, info.defaultCharacterSet()));
            out.write("\n");
            progress.extracted("Domain " + domain.name());
        }

        if (!generators.isEmpty()) {
            out.write("\n/* Generators */\n\n");
        }
        List<String> setGenerators = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<String, Generator> generator : generators.entrySet()) {
                progress.checkCancelled();
                out.write(generator.getValue().toSql(options.quote(generator.getKey()), options) + "\n");
                setGenerators.add("SET GENERATOR " + options.quote(generator.getKey()) + " TO "
                        + currentValue(statement, generator.getKey()) + ";");
                progress.extracted("Generator " + generator.getKey());
            }
        }

        writeObjects(out, "Exceptions", exceptions, ObjectCatalog.ExceptionDef::name, "Exception ",
                exception -> exception.toSql(options), progress);
        writeObjects(out, "Roles", roles, ObjectCatalog.Role::name, "Role ", role -> role.toSql(options), progress);
        writeMappings(out, mappings, options, progress);

        writeTables(out, tables, options, info, progress);
        writeComputedColumns(out, tables, options);
        writeViewBlock(out, "Views", views.beforeRoutines(), options, progress);
        writeRoutineHeaders(out, routines, options, info, progress);
        writeIndexesAndConstraints(out, tables, indexes, options, progress);
        writeBodiesAndViewsOverRoutines(out, routines, views.afterRoutines(), routineCatalog.readViewReaders(),
                options, info, progress);
        writeTriggers(out, triggers, options, progress);
        writeBlock(out, "Set generators", setGenerators);
        writePublication(out, publication, tables, options);
        writeDescriptions(out, domains, generators, options);
        out.flush();
    }

    /** Um bloco de objetos simples: um statement por objeto, sem ordem entre eles além da alfabética. */
    private static <T> void writeObjects(Writer out, String title, List<T> items,
            Function<T, String> name, String label, Function<T, String> sql,
            ExtractionProgress progress) throws IOException {
        if (items.isEmpty()) {
            return;
        }
        out.write("\n/* " + title + " */\n\n");
        for (T item : items) {
            progress.checkCancelled();
            out.write(sql.apply(item) + "\n");
            progress.extracted(label + name.apply(item));
        }
    }

    /** Mappings depois das roles, que eles podem apontar; um modo de autenticação desconhecido é avisado e pulado. */
    private static void writeMappings(Writer out, List<ObjectCatalog.Mapping> mappings, ExtractionOptions options,
            ExtractionProgress progress) throws IOException {
        List<ObjectCatalog.Mapping> known = new ArrayList<>();
        for (ObjectCatalog.Mapping mapping : mappings) {
            if (mapping.toSql(options) == null) {
                progress.warn("O mapping " + mapping.name() + " usa o modo de autenticação '" + mapping.using()
                        + "', que a extração não conhece; ele não saiu no script.");
                progress.extracted("Mapping " + mapping.name() + " (ignorado)");
            } else {
                known.add(mapping);
            }
        }
        writeObjects(out, "Mappings", known, ObjectCatalog.Mapping::name, "Mapping ",
                mapping -> mapping.toSql(options), progress);
    }

    /**
     * Triggers depois do último corpo e da última view que lê rotina (ADR 0011): eles leem views e chamam rotinas, e o
     * Firebird confere isso ao criar.
     */
    private static void writeTriggers(Writer out, List<TriggerCatalog.Trigger> triggers, ExtractionOptions options,
            ExtractionProgress progress) throws IOException {
        List<String> statements = new ArrayList<>();
        for (TriggerCatalog.Trigger trigger : triggers) {
            progress.checkCancelled();
            statements.add(trigger.toSql(options));
            progress.extracted("Trigger " + trigger.name());
        }
        writeRoutineBlock(out, "Triggers", statements, options);
    }

    /**
     * A publication padrão (o Firebird 5 não tem outra): inclusão automática, tabelas e se está ligada. Depois das
     * tabelas, que ela referencia; só as tabelas da seleção entram, e a inclusão automática ({@code INCLUDE ALL}) só sai
     * se houver tabela selecionada, que senão ela mudaria a replicação de tabelas fora da extração.
     */
    private static void writePublication(Writer out, ObjectCatalog.Publication publication,
            List<TableCatalog.Table> tables, ExtractionOptions options) throws IOException {
        if (publication == null || !publication.configured()) {
            return;
        }
        List<String> statements = new ArrayList<>();
        if (publication.autoEnable() && !tables.isEmpty()) {
            statements.add("ALTER DATABASE INCLUDE ALL TO PUBLICATION;");
            List<String> excluded = tables.stream().map(TableCatalog.Table::name)
                    .filter(table -> !publication.tables().contains(table)).map(options::quote).toList();
            if (!excluded.isEmpty()) {
                statements.add("ALTER DATABASE EXCLUDE TABLE " + String.join(", ", excluded) + " FROM PUBLICATION;");
            }
        } else {
            List<String> included = tables.stream().map(TableCatalog.Table::name)
                    .filter(publication.tables()::contains).map(options::quote).toList();
            if (!included.isEmpty()) {
                statements.add("ALTER DATABASE INCLUDE TABLE " + String.join(", ", included) + " TO PUBLICATION;");
            }
        }
        if (publication.active()) {
            statements.add("ALTER DATABASE ENABLE PUBLICATION;");
        }
        writeBlock(out, "Publication", statements);
    }

    /** Fase de tabelas: sem FKs nem checks, só chaves primárias e únicas, que as FKs precisam já existir. */
    private static void writeTables(Writer out, List<TableCatalog.Table> tables, ExtractionOptions options,
            DatabaseInfo info, ExtractionProgress progress) throws IOException {
        if (tables.isEmpty()) {
            return;
        }
        out.write("\n/* Tables */\n\n");
        for (TableCatalog.Table table : tables) {
            progress.checkCancelled();
            out.write(table.toSql(options, info.defaultCharacterSet()) + "\n\n");
            progress.extracted("Table " + table.name());
        }
        List<String> keys = new ArrayList<>();
        for (TableCatalog.Table table : tables) {
            for (TableCatalog.Constraint constraint : table.constraints()) {
                if (constraint.isKey()) {
                    keys.add(constraint.toSql(options));
                }
            }
        }
        writeBlock(out, "Primary keys and unique constraints", keys);
    }

    /**
     * Fases de rotinas, primeira parte: os Cabeçalhos vazios de packages (o header inteiro), functions e procedures.
     * Saem depois das views que não leem rotina e antes das que leem, de índices e FKs e dos corpos, para que ciclos
     * entre rotinas e views sobre procedures tenham onde se apoiar.
     */
    private static void writeRoutineHeaders(Writer out, RoutineCatalog.Routines routines, ExtractionOptions options,
            DatabaseInfo info, ExtractionProgress progress) throws IOException {
        List<String> headers = new ArrayList<>();
        for (RoutineCatalog.Package pack : routines.packages()) {
            progress.checkCancelled();
            headers.add(pack.header(options));
            progress.extracted("Package " + pack.name());
        }
        for (RoutineCatalog.Function function : routines.functions()) {
            progress.checkCancelled();
            headers.add(function.emptyHeader(options, info.defaultCharacterSet()));
            progress.extracted("Function " + function.name());
        }
        for (RoutineCatalog.Procedure procedure : routines.procedures()) {
            progress.checkCancelled();
            headers.add(procedure.emptyHeader(options, info.defaultCharacterSet()));
            progress.extracted("Procedure " + procedure.name());
        }
        writeRoutineBlock(out, "Routine headers", headers, options);
    }

    /** Um passo da segunda parte das Fases de rotinas: um corpo ou uma view que lê rotina, e o que vem antes dele. */
    private record LateStep(String key, boolean view, String name, String sql, Set<String> after) {
    }

    /**
     * Fases de rotinas, segunda parte, depois de índices e FKs: os corpos de packages, functions e procedures e as
     * views que leem rotina, em ordem de dependência. A view precisa do corpo definitivo da rotina que lê, porque o
     * Firebird recusa o {@code ALTER PROCEDURE} de uma procedure com view sobre view já criada; e o corpo que lê uma
     * dessas views precisa dela pronta. Fora isso os corpos vêm primeiro, em ordem alfabética.
     */
    private static void writeBodiesAndViewsOverRoutines(Writer out, RoutineCatalog.Routines routines,
            List<ViewCatalog.View> lateViews, Map<String, Set<String>> viewReaders, ExtractionOptions options,
            DatabaseInfo info, ExtractionProgress progress) throws IOException {
        Set<String> lateViewKeys = lateViews.stream().map(view -> "view " + view.name()).collect(Collectors.toSet());
        Map<String, LateStep> steps = new LinkedHashMap<>();
        for (RoutineCatalog.Package pack : routines.packages()) {
            if (pack.bodySource() != null) {
                addBody(steps, "package " + pack.name(), pack.name(), pack.body(options), viewReaders, lateViewKeys);
            }
        }
        for (RoutineCatalog.Function function : routines.functions()) {
            if (!function.external()) {
                addBody(steps, "function " + function.name(), function.name(),
                        function.body(options, info.defaultCharacterSet()), viewReaders, lateViewKeys);
            }
        }
        for (RoutineCatalog.Procedure procedure : routines.procedures()) {
            if (!procedure.external()) {
                addBody(steps, "procedure " + procedure.name(), procedure.name(),
                        procedure.body(options, info.defaultCharacterSet()), viewReaders, lateViewKeys);
            }
        }
        for (ViewCatalog.View view : lateViews) {
            Set<String> after = new LinkedHashSet<>(view.routines());
            view.relations().forEach(relation -> after.add("view " + relation));
            steps.put("view " + view.name(), new LateStep("view " + view.name(), true, view.name(),
                    view.toSql(options), after));
        }
        List<LateStep> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (LateStep step : steps.values()) {
            visitStep(step, steps, visited, new HashSet<>(), ordered, progress);
        }
        // Passos seguidos do mesmo tipo saem num bloco só (um SET TERM para vários corpos).
        for (int i = 0; i < ordered.size();) {
            int end = i;
            while (end < ordered.size() && ordered.get(end).view() == ordered.get(i).view()) {
                end++;
            }
            List<LateStep> run = ordered.subList(i, end);
            if (run.get(0).view()) {
                out.write("\n/* Views that read routines */\n\n");
                for (LateStep step : run) {
                    progress.checkCancelled();
                    out.write(step.sql() + "\n\n");
                    progress.extracted("View " + step.name());
                }
            } else {
                writeRoutineBlock(out, "Routine bodies", run.stream().map(LateStep::sql).toList(), options);
            }
            i = end;
        }
    }

    private static void addBody(Map<String, LateStep> steps, String key, String name, String sql,
            Map<String, Set<String>> viewReaders, Set<String> lateViewKeys) {
        Set<String> after = new LinkedHashSet<>();
        for (String view : viewReaders.getOrDefault(key, Set.of())) {
            if (lateViewKeys.contains("view " + view)) {
                after.add("view " + view);
            }
        }
        steps.put(key, new LateStep(key, false, name, sql, after));
    }

    // Um ciclo entre corpo e view (rotina que lê view que lê a própria rotina) não tem ordem que o Firebird aceite
    // (ADR 0011): quebra-se onde foi achado, o script falha nesse ponto e a extração avisa.
    private static void visitStep(LateStep step, Map<String, LateStep> steps, Set<String> visited,
            Set<String> inProgress, List<LateStep> ordered, ExtractionProgress progress) {
        if (inProgress.contains(step.key())) {
            progress.warn("Ciclo entre corpo de rotina e view que a lê, envolvendo " + step.key() + ": o Firebird não"
                    + " aceita nenhuma ordem, e o script falha nesse ponto (ADR 0011).");
            return;
        }
        if (!visited.add(step.key())) {
            return;
        }
        inProgress.add(step.key());
        for (String dependency : step.after()) {
            LateStep before = steps.get(dependency);
            if (before != null) {
                visitStep(before, steps, visited, inProgress, ordered, progress);
            }
        }
        inProgress.remove(step.key());
        ordered.add(step);
    }

    /** Quebra de linha antes do terminador se a última linha tem comentário, que senão o engoliria. */
    private static String terminatorSeparator(String statement) {
        return statement.substring(statement.lastIndexOf('\n') + 1).contains("--") ? "\n" : "";
    }

    /**
     * Statements de rotina cheios de {@code ;}: com {@code SET TERM} o bloco todo usa {@code ^} e volta ao {@code ;} no
     * fim; sem ele, cada statement termina em {@code ;} logo depois do {@code END}.
     */
    private static void writeRoutineBlock(Writer out, String title, List<String> statements, ExtractionOptions options)
            throws IOException {
        if (statements.isEmpty()) {
            return;
        }
        out.write("\n/* " + title + " */\n\n");
        if (options.noSetTerm()) {
            for (String statement : statements) {
                out.write(statement + terminatorSeparator(statement) + ";\n\n");
            }
            return;
        }
        out.write("SET TERM ^ ;\n\n");
        for (String statement : statements) {
            out.write(statement + terminatorSeparator(statement) + "^\n\n");
        }
        out.write("SET TERM ; ^\n");
    }

    /** Fase de views: depois de todas as tabelas e em ordem topológica; as que leem rotina saem à parte. */
    private static void writeViewBlock(Writer out, String title, List<ViewCatalog.View> views,
            ExtractionOptions options, ExtractionProgress progress) throws IOException {
        if (views.isEmpty()) {
            return;
        }
        out.write("\n/* " + title + " */\n\n");
        for (ViewCatalog.View view : views) {
            progress.checkCancelled();
            out.write(view.toSql(options) + "\n\n");
            progress.extracted("View " + view.name());
        }
    }

    /** Colunas {@code COMPUTED BY} separadas: depois de todas as tabelas e antes das views, que podem lê-las. */
    private static void writeComputedColumns(Writer out, List<TableCatalog.Table> tables, ExtractionOptions options)
            throws IOException {
        List<String> computed = new ArrayList<>();
        if (options.computedBySeparate()) {
            for (TableCatalog.Table table : tables) {
                for (TableCatalog.Column column : table.computedColumns()) {
                    computed.add("ALTER TABLE " + options.quote(table.name()) + " ADD "
                            + column.definition(options, null) + ";");
                }
            }
        }
        writeBlock(out, "Computed columns", computed);
    }

    /**
     * Fase de índices e FKs, depois de todas as tabelas e das views: índices, checks e FKs, nessa ordem, e por fim o
     * ponto de partida das identities. Assim a ordem das tabelas não importa e ciclos de FK não quebram o script.
     */
    private static void writeIndexesAndConstraints(Writer out, List<TableCatalog.Table> tables,
            List<TableCatalog.Index> indexes, ExtractionOptions options, ExtractionProgress progress)
            throws IOException {
        List<String> checks = new ArrayList<>();
        List<String> foreignKeys = new ArrayList<>();
        List<String> restarts = new ArrayList<>();
        for (TableCatalog.Table table : tables) {
            for (TableCatalog.Constraint constraint : table.constraints()) {
                if (constraint.kind().equals("CHECK")) {
                    checks.add(constraint.toSql(options));
                } else if (!constraint.isKey()) {
                    foreignKeys.add(constraint.toSql(options));
                }
            }
            for (TableCatalog.Column column : table.identityColumns()) {
                Long restart = column.identity().restartWith();
                if (restart != null) {
                    restarts.add("ALTER TABLE " + options.quote(table.name()) + " ALTER COLUMN "
                            + options.quote(column.name()) + " RESTART WITH " + restart + ";");
                }
            }
        }
        if (!indexes.isEmpty()) {
            out.write("\n/* Indices */\n\n");
        }
        for (TableCatalog.Index index : indexes) {
            progress.checkCancelled();
            out.write(index.toSql(options) + "\n");
            if (index.inactive()) {
                out.write(index.inactiveSql(options) + "\n");
            }
            progress.extracted("Index " + index.name());
        }
        writeBlock(out, "Check constraints", checks);
        writeBlock(out, "Foreign keys", foreignKeys);
        writeBlock(out, "Identity columns", restarts);
    }

    private static void writeBlock(Writer out, String title, List<String> statements) throws IOException {
        if (statements.isEmpty()) {
            return;
        }
        out.write("\n/* " + title + " */\n\n");
        for (String statement : statements) {
            out.write(statement + "\n");
        }
    }

    /** Sem usuário nem senha (a senha nunca vai para o arquivo) e sempre com o charset padrão (ADR 0009). */
    private String createDatabase(DatabaseInfo info) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT MON$DATABASE_NAME, MON$PAGE_SIZE FROM MON$DATABASE")) {
            rs.next();
            return "CREATE DATABASE '" + rs.getString(1).strip().replace("'", "''") + "' PAGE_SIZE " + rs.getInt(2)
                    + " DEFAULT CHARACTER SET " + info.defaultCharacterSet()
                    + (info.defaultCollation() == null ? "" : " COLLATION " + info.defaultCollation()) + ";";
        }
    }

    private void warnIfConnectionCharsetDiffers(DatabaseInfo info, ExtractionProgress progress) {
        try {
            String used = DatabaseInfo.connectionCharacterSet(connection);
            if (used != null && !used.equals(info.defaultCharacterSet())) {
                progress.warn("A conexão usa o charset " + used + ", e o banco usa " + info.defaultCharacterSet()
                        + ". Textos de colunas NONE ou de outro charset podem sair trocados; ajuste o charset da conexão"
                        + " para o do banco (ADR 0009).");
            }
        } catch (SQLException e) {
            // MON$ pode estar fora do alcance do usuário: sem a informação, não há aviso.
        }
    }

    /**
     * A seleção é literal: nada é cortado, mas uma FK ou coluna que depende de objeto fora dela só roda num banco que
     * já o tenha, e o usuário precisa saber.
     */
    private static void warnAboutMissingDependencies(List<Domain> domains, List<TableCatalog.Table> tables,
            ViewCatalog.Views views, RoutineCatalog.Routines routines, ExtractionProgress progress) {
        Set<String> selectedRoutines = new HashSet<>();
        routines.procedures().forEach(routine -> selectedRoutines.add("procedure " + routine.name()));
        routines.functions().forEach(routine -> selectedRoutines.add("function " + routine.name()));
        routines.packages().forEach(routine -> selectedRoutines.add("package " + routine.name()));
        Set<String> tableNames = tables.stream().map(TableCatalog.Table::name).collect(Collectors.toSet());
        Set<String> relationNames = new HashSet<>(tableNames);
        views.all().forEach(view -> relationNames.add(view.name()));
        Set<String> domainNames = domains.stream().map(Domain::name).collect(Collectors.toSet());
        Set<String> missing = new TreeSet<>();
        for (TableCatalog.Table table : tables) {
            for (TableCatalog.Constraint constraint : table.constraints()) {
                if (constraint.refTable() != null && !tableNames.contains(constraint.refTable())) {
                    missing.add("tabela " + constraint.refTable() + " (FK " + constraint.name() + ")");
                }
            }
            for (TableCatalog.Column column : table.columns()) {
                if (column.domain() != null && !domainNames.contains(column.domain())) {
                    missing.add("domínio " + column.domain() + " (coluna " + table.name() + "." + column.name() + ")");
                }
            }
        }
        for (ViewCatalog.View view : views.all()) {
            for (String relation : view.relations()) {
                if (!relationNames.contains(relation)) {
                    missing.add("tabela ou view " + relation + " (view " + view.name() + ")");
                }
            }
            for (String routine : view.routines()) {
                if (!selectedRoutines.contains(routine)) {
                    missing.add(routine + " (view " + view.name() + ")");
                }
            }
        }
        if (!missing.isEmpty()) {
            progress.warn("O script usa objetos que não estão na seleção: " + String.join(", ", missing)
                    + ". Eles precisam existir no banco onde o script for executado.");
        }
    }

    /**
     * Colunas calculadas, índices de expressão e checks são criados com a tabela, antes de qualquer rotina: o script só
     * roda num banco que já tenha a rotina que eles chamam.
     */
    private static void warnAboutRoutinesUsedByTables(List<RoutineCatalog.TableUser> users, Selection selection,
            ExtractionProgress progress) {
        Set<String> uses = new TreeSet<>();
        for (RoutineCatalog.TableUser user : users) {
            if (selection.includesTable(user.table())) {
                uses.add(user.routine() + " (" + user.user() + ")");
            }
        }
        if (!uses.isEmpty()) {
            progress.warn("Objetos de tabela chamam rotinas, que só são criadas depois das tabelas: "
                    + String.join(", ", uses) + ". O script só roda onde essas rotinas já existem.");
        }
    }

    /** Com o banco em {@code NONE} não há o que avisar: o banco recriado também não terá charset. */
    private static void warnAboutNoneColumns(DatabaseInfo info, List<Domain> domains, List<TableCatalog.Table> tables,
            ExtractionProgress progress) {
        if (info.defaultCharacterSet().equals("NONE")) {
            return;
        }
        List<String> none = new ArrayList<>();
        for (Domain domain : domains) {
            if (domain.type().isNoneText()) {
                none.add(domain.name());
            }
        }
        for (TableCatalog.Table table : tables) {
            for (TableCatalog.Column column : table.columns()) {
                if (column.type() != null && column.type().isNoneText()) {
                    none.add(table.name() + "." + column.name());
                }
            }
        }
        if (!none.isEmpty()) {
            progress.warn("Domínios ou colunas com CHARACTER SET NONE (" + String.join(", ", none) + "): o servidor"
                    + " não sabe o charset real desses textos. Eles saem byte a byte no charset do banco ("
                    + info.defaultCharacterSet() + "); confira a codificação ao abrir ou rodar o script (ADR 0009).");
        }
    }

    private static void writeDescriptions(Writer out, List<Domain> domains, Map<String, Generator> generators,
            ExtractionOptions options) throws IOException {
        boolean header = false;
        for (Domain domain : domains) {
            if (domain.description() != null) {
                header = descriptionsHeader(out, header);
                out.write(comment("DOMAIN", domain.name(), domain.description(), options));
            }
        }
        for (Map.Entry<String, Generator> generator : generators.entrySet()) {
            if (generator.getValue().description() != null) {
                header = descriptionsHeader(out, header);
                out.write(comment("GENERATOR", generator.getKey(), generator.getValue().description(), options));
            }
        }
    }

    private static boolean descriptionsHeader(Writer out, boolean written) throws IOException {
        if (!written) {
            out.write("\n/* Descriptions */\n\n");
        }
        return true;
    }

    private static String comment(String kind, String name, String text, ExtractionOptions options) {
        return "COMMENT ON " + kind + " " + options.quote(name) + " IS '" + text.replace("'", "''") + "';\n";
    }

    private List<Domain> readDomains(Selection selection, ExtractionProgress progress) throws SQLException {
        Map<String, String> dimensions = Rdb.dimensions(connection);
        List<Domain> domains = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(DOMAINS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString("RDB$FIELD_NAME").strip();
                if (!selection.includesDomain(name)) {
                    continue;
                }
                FieldType type = new FieldType(rs.getInt("RDB$FIELD_TYPE"), rs.getInt("RDB$FIELD_SUB_TYPE"),
                        rs.getInt("RDB$FIELD_LENGTH"), Rdb.nullableInt(rs, "RDB$CHARACTER_LENGTH"),
                        Rdb.nullableInt(rs, "RDB$FIELD_PRECISION"), rs.getInt("RDB$FIELD_SCALE"),
                        Rdb.nullableInt(rs, "RDB$SEGMENT_LENGTH"), Rdb.trimmed(rs.getString("RDB$CHARACTER_SET_NAME")),
                        Rdb.trimmed(rs.getString("RDB$COLLATION_NAME")), dimensions.get(name));
                domains.add(new Domain(name, type, Rdb.source(rs.getString("RDB$DEFAULT_SOURCE")),
                        rs.getInt("RDB$NULL_FLAG") == 1, Rdb.source(rs.getString("RDB$VALIDATION_SOURCE")),
                        Rdb.source(rs.getString("RDB$DESCRIPTION"))));
            }
        }
        return domains;
    }

    private Map<String, Generator> readGenerators(Selection selection) throws SQLException {
        Map<String, Generator> generators = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(GENERATORS)) {
            while (rs.next()) {
                String name = rs.getString(1).strip();
                if (selection.includesGenerator(name)) {
                    generators.put(name, new Generator(Rdb.source(rs.getString(2)), Rdb.nullableLong(rs, "RDB$INITIAL_VALUE"),
                            Rdb.nullableLong(rs, "RDB$GENERATOR_INCREMENT")));
                }
            }
        }
        return generators;
    }

    /**
     * Um generator. {@code initial} e {@code increment} são os do {@code START WITH} e {@code INCREMENT BY} (o padrão do
     * Firebird 5 é 1 e 1); fora do padrão, ou com a opção, sai como {@code SEQUENCE}.
     */
    private record Generator(String description, Long initial, Long increment) {

        String toSql(String quotedName, ExtractionOptions options) {
            boolean start = initial != null && initial != 1;
            boolean step = increment != null && increment != 1;
            if (!options.useSequence() && !start && !step) {
                return "CREATE GENERATOR " + quotedName + ";";
            }
            return "CREATE SEQUENCE " + quotedName + (start ? " START WITH " + initial : "")
                    + (step ? " INCREMENT BY " + increment : "") + ";";
        }
    }

    private static long currentValue(Statement statement, String generator) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT GEN_ID(\"" + generator.replace("\"", "\"\"") + "\", 0) FROM RDB$DATABASE")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Um domínio do usuário; {@code defaultSource} e {@code checkSource} já trazem a palavra-chave ({@code DEFAULT}, {@code CHECK}). */
    private record Domain(String name, FieldType type, String defaultSource, boolean notNull, String checkSource,
            String description) {

        String toSql(ExtractionOptions options, String defaultCharacterSet) {
            StringBuilder sql = new StringBuilder("CREATE DOMAIN ").append(options.quote(name)).append(" AS ")
                    .append(type.toSql(defaultCharacterSet, options.alwaysCharacterSet()));
            if (defaultSource != null) {
                sql.append(' ').append(defaultSource);
            }
            if (notNull) {
                sql.append(" NOT NULL");
            }
            if (checkSource != null) {
                sql.append(' ').append(checkSource);
            }
            return sql.append(';').toString();
        }
    }
}
