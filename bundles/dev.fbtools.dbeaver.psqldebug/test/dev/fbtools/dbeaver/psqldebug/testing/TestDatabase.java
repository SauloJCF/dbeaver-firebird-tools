package dev.fbtools.dbeaver.psqldebug.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.firebirdsql.management.FBManager;

import dev.fbtools.dbeaver.psqldebug.interp.NativeTypes;

/**
 * Banco Firebird 5 dedicado aos testes, recriado uma vez por execução da suíte. Host, banco e credenciais vêm de
 * {@code fbdebug-test.properties} na raiz do repositório (modelo em {@code fbdebug-test.properties.example}).
 */
public final class TestDatabase {

    private static final String CONFIG_FILE = "fbdebug-test.properties";
    private static final String CONFIG_PROPERTY = "fbdebug.test.config";

    private static TestDatabase instance;

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;

    private TestDatabase(Properties config) {
        host = required(config, "host");
        port = Integer.parseInt(required(config, "port"));
        database = required(config, "database");
        user = required(config, "user");
        password = required(config, "password");
    }

    /** O banco de teste, recriado vazio na primeira chamada. */
    public static synchronized TestDatabase get() throws Exception {
        if (instance == null) {
            TestDatabase created = new TestDatabase(loadConfig());
            created.recreate();
            instance = created;
        }
        return instance;
    }

    /** Conexão com os tipos nativos, como a da Sessão de debug ({@link NativeTypes}). */
    public Connection connect() throws SQLException {
        Connection connection = connectAsIs();
        try {
            NativeTypes.bind(connection);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** Conexão sem nenhum ajuste: os tipos que o servidor devolve conforme o {@code DataTypeCompatibility} dele. */
    public Connection connectAsIs() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("encoding", "UTF8");
        return DriverManager.getConnection("jdbc:firebird://" + host + ":" + port + "/" + database, properties);
    }

    /** Executa DDL/DML de preparação, cada comando com commit. */
    public void execute(String... statements) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private void recreate() throws Exception {
        try (FBManager manager = new FBManager()) {
            manager.setServer(host);
            manager.setPort(port);
            manager.setDefaultCharacterSet("UTF8");
            manager.setForceCreate(true);
            manager.start();
            manager.createDatabase(database, user, password);
        }
    }

    private static Properties loadConfig() throws IOException {
        Path file = configFile();
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            config.load(in);
        }
        return config;
    }

    private static Path configFile() {
        String explicit = System.getProperty(CONFIG_PROPERTY);
        if (explicit != null && Files.isRegularFile(Path.of(explicit))) {
            return Path.of(explicit);
        }
        // Maven e IDE rodam os testes na pasta do bundle: procura subindo a partir dela até a raiz do repositório.
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(CONFIG_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Banco de teste não configurado: copie " + CONFIG_FILE
                + ".example para " + CONFIG_FILE + " na raiz do repositório e ajuste host, banco e credenciais"
                + (explicit == null ? "" : " (procurado em " + explicit + ")"));
    }

    private static String required(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta '" + key + "' em " + CONFIG_FILE);
        }
        return value.strip();
    }
}
