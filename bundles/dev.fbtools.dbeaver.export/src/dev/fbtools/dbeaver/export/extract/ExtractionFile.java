package dev.fbtools.dbeaver.export.extract;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Grava o Script de metadata num arquivo; se a extração falha ou é cancelada, o arquivo parcial é removido. */
public final class ExtractionFile {

    private ExtractionFile() {
    }

    /**
     * @param charset a codificação do arquivo; {@code null} usa a do charset padrão do banco (ADR 0009)
     * @param setNames o nome Firebird do charset que {@code charset} representa (ex.: {@code UTF8}); {@code null} se for o padrão
     * @throws ExtractionCancelledException depois de remover o arquivo parcial
     */
    public static void write(Connection connection, Selection selection, Path file, Charset charset, String setNames,
            ExtractionProgress progress) throws SQLException, IOException {
        Charset encoding = charset != null ? charset : DatabaseInfo.read(connection).javaCharset();
        boolean done = false;
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(file), encoding))) {
            new MetadataExtractor(connection).extract(selection, out, setNames, progress);
            done = true;
        } finally {
            if (!done) {
                Files.deleteIfExists(file);
            }
        }
    }
}
