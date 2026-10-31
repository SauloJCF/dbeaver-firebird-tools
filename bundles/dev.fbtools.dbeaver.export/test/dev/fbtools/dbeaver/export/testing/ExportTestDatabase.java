package dev.fbtools.dbeaver.export.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

import org.firebirdsql.management.FBManager;

/**
 * Bancos Firebird 5 descartáveis dos testes da exportação (ADR 0008). Host e credenciais vêm de
 * {@code fbdebug-test.properties}, o mesmo arquivo dos Testes diferenciais do depurador; cada banco criado fica na
 * mesma pasta do {@code database} do arquivo, com o nome {@code export-<nome>.fdb}, e é recriado a cada chamada.
 */
public final class ExportTestDatabase {

    private static final String CONFIG_FILE = "fbdebug-test.properties";
    private static final String CONFIG_PROPERTY = "fbdebug.test.config";

    private final Properties config;
    private final String path;
    private final String charset;

    private ExportTestDatabase(Properties config, String path, String charset) {
        this.config = config;
        this.path = path;
        this.charset = charset;
    }

    /** Cria (apagando o anterior) o banco {@code name} com o charset padrão dado. */
    public static ExportTestDatabase create(String name, String charset) throws Exception {
        Properties config = loadConfig();
        String base = config.getProperty("database").strip().replace('\\', '/');
        String folder = base.substring(0, base.lastIndexOf('/') + 1);
        ExportTestDatabase database = new ExportTestDatabase(config, folder + "export-" + name + ".fdb", charset);
        try (FBManager manager = new FBManager()) {
            manager.setServer(database.host());
            manager.setPort(Integer.parseInt(config.getProperty("port").strip()));
            manager.setDefaultCharacterSet(charset);
            manager.setForceCreate(true);
            manager.start();
            manager.createDatabase(database.path, database.user(), database.password());
        }
        return database;
    }

    /** Conexão com o charset do banco, como a extração exige (ADR 0009). */
    public Connection connect() throws SQLException {
        return connect(charset);
    }

    /** Conexão com outro charset que o do banco, para provar o aviso da extração. */
    public Connection connect(String encoding) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user());
        properties.setProperty("password", password());
        properties.setProperty("encoding", encoding);
        return DriverManager.getConnection("jdbc:firebird://" + host() + ":" + config.getProperty("port").strip()
                + "/" + path, properties);
    }

    /**
     * Roda um script do isql no banco, statement a statement. Ignora o que só o isql entende: {@code SET} (menos
     * {@code SET GENERATOR}), {@code CREATE DATABASE}, {@code CONNECT} e {@code COMMIT}.
     */
    public void run(String script) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : ScriptSplitter.split(script)) {
                String head = StatementNormalizer.normalize(sql);
                if ((head.startsWith("SET ") && !head.startsWith("SET GENERATOR")) || head.startsWith("CREATE DATABASE")
                        || head.startsWith("CONNECT") || head.equals("COMMIT")) {
                    continue;
                }
                statement.execute(sql);
            }
        }
    }

    /** O banco sintético da Exportação (script em {@code samples/export-db}) num banco UTF8 novo. */
    public static ExportTestDatabase syntheticDatabase(String name) throws Exception {
        ExportTestDatabase database = create(name, "UTF8");
        database.run(Files.readString(repositoryRoot().resolve("samples/export-db/create-export-db.sql"),
                StandardCharsets.UTF_8));
        return database;
    }

    /** Caminho do banco no servidor. */
    public String path() {
        return path;
    }

    public String host() {
        return config.getProperty("host").strip();
    }

    public String user() {
        return config.getProperty("user").strip();
    }

    public String password() {
        return config.getProperty("password").strip();
    }

    public String charset() {
        return charset.toUpperCase(Locale.ROOT);
    }

    /** A raiz do repositório, subindo a partir da pasta do bundle. */
    public static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("GLOSSARY.md"))) {
                return dir;
            }
        }
        throw new IllegalStateException("Raiz do repositório não encontrada a partir de " + Path.of("").toAbsolutePath());
    }

    private static Properties loadConfig() throws IOException {
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(configFile())) {
            config.load(in);
        }
        return config;
    }

    private static Path configFile() {
        String explicit = System.getProperty(CONFIG_PROPERTY);
        if (explicit != null && Files.isRegularFile(Path.of(explicit))) {
            return Path.of(explicit);
        }
        Path candidate = repositoryRoot().resolve(CONFIG_FILE);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        throw new IllegalStateException("Banco de teste não configurado: copie " + CONFIG_FILE
                + ".example para " + CONFIG_FILE + " na raiz do repositório e ajuste host, banco e credenciais"
                + (explicit == null ? "" : " (procurado em " + explicit + ")"));
    }
}
