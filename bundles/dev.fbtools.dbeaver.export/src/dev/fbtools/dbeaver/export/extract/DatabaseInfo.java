package dev.fbtools.dbeaver.export.extract;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** O que o script precisa saber do banco antes de escrever: o charset padrão (ADR 0009). */
public record DatabaseInfo(String defaultCharacterSet) {

    public static DatabaseInfo read(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT RDB$CHARACTER_SET_NAME FROM RDB$DATABASE")) {
            String name = rs.next() ? rs.getString(1) : null;
            return new DatabaseInfo(name == null || name.isBlank() ? "NONE" : name.strip());
        }
    }

    /** O charset da conexão atual, lido de {@code MON$ATTACHMENTS}; {@code null} se o servidor não informa. */
    public static String connectionCharacterSet(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT cs.RDB$CHARACTER_SET_NAME FROM MON$ATTACHMENTS a "
                        + "JOIN RDB$CHARACTER_SETS cs ON cs.RDB$CHARACTER_SET_ID = a.MON$CHARACTER_SET_ID "
                        + "WHERE a.MON$ATTACHMENT_ID = CURRENT_CONNECTION")) {
            String name = rs.next() ? rs.getString(1) : null;
            return name == null ? null : name.strip();
        }
    }

    /** A codificação Java do charset padrão do banco; UTF-8 para {@code NONE} e para o que o Java não conhece. */
    public Charset javaCharset() {
        String name = defaultCharacterSet.toUpperCase();
        String javaName = switch (name) {
            case "UTF8", "NONE" -> "UTF-8";
            case "ASCII" -> "US-ASCII";
            case "DOS850" -> "IBM850";
            case "DOS437" -> "IBM437";
            default -> name.startsWith("WIN") ? "windows-" + name.substring(3)
                    : name.startsWith("ISO8859_") ? "ISO-8859-" + name.substring(8) : name;
        };
        try {
            return Charset.forName(javaName);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
