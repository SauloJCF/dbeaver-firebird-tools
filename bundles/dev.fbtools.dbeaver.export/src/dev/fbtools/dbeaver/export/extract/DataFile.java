package dev.fbtools.dbeaver.export.extract;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Grava o Script de dados em arquivo. Com {@link DataOptions#maxFileBytes()}, o script continua num novo arquivo
 * quando o atual passa do limite, sempre depois de um {@code COMMIT WORK}; os arquivos levam o número da sequência
 * antes da extensão ({@code dados_001.sql}, {@code dados_002.sql}…) e cada um começa com o próprio cabeçalho, para
 * rodar sozinho. Se a extração falha ou é cancelada, todos os arquivos já gravados são removidos.
 */
public final class DataFile {

    private DataFile() {
    }

    /**
     * @param charset a codificação dos arquivos; {@code null} usa a do charset padrão do banco (ADR 0009)
     * @param setNames o nome Firebird do charset que {@code charset} representa; {@code null} se for o padrão
     * @return os arquivos gravados, na ordem
     * @throws ExtractionCancelledException depois de remover os arquivos parciais
     */
    public static List<Path> write(Connection connection, List<String> tables, DataOptions options, Path file,
            Charset charset, String setNames, ExtractionProgress progress) throws SQLException, IOException {
        Charset encoding = charset != null ? charset : DatabaseInfo.read(connection).javaCharset();
        DataExtractor extractor = new DataExtractor(connection);
        boolean done = false;
        Rolling rolling = new Rolling(file, encoding, options.maxFileBytes(), extractor.header(options, setNames));
        try {
            rolling.open();
            extractor.extractBody(tables, options, rolling, progress);
            rolling.close();
            done = true;
            return rolling.written();
        } finally {
            if (!done) {
                rolling.abort();
            }
        }
    }

    /** O nome do arquivo de número {@code number} (a partir de 1) quando há limite de tamanho. */
    static Path numbered(Path file, int number) {
        return withSuffix(file, String.format("_%03d", number));
    }

    /** O mesmo arquivo com {@code suffix} antes da extensão ({@code dados.sql} + {@code _data} = {@code dados_data.sql}). */
    public static Path withSuffix(Path file, String suffix) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String result = (dot < 0 ? name : name.substring(0, dot)) + suffix + (dot < 0 ? "" : name.substring(dot));
        Path parent = file.getParent();
        return parent == null ? Path.of(result) : parent.resolve(result);
    }

    private static final class Rolling implements DataExtractor.Sink {

        private final Path base;
        private final Charset encoding;
        private final long limit;
        private final String header;
        private final List<Path> written = new ArrayList<>();
        private Writer writer;
        private Counting counter;
        private boolean full;
        private final CharsetEncoder encoder;

        Rolling(Path base, Charset encoding, long limit, String header) {
            this.base = base;
            this.encoding = encoding;
            this.encoder = encoding.newEncoder();
            this.limit = limit;
            this.header = header;
        }

        void open() throws IOException {
            Path file = limit > 0 ? numbered(base, written.size() + 1) : base;
            written.add(file);
            counter = new Counting(Files.newOutputStream(file));
            writer = new BufferedWriter(new OutputStreamWriter(counter, encoding));
            writer.write(header);
        }

        /** O arquivo vai receber mais texto; se o anterior já passou do limite, é aqui que o próximo começa (sem arquivo vazio no fim). */
        @Override
        public Writer writer() {
            if (full) {
                try {
                    writer.close();
                    open();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                full = false;
            }
            return writer;
        }

        @Override
        public boolean canEncode(CharSequence text) {
            return encoder.canEncode(text);
        }

        @Override
        public String encodingName() {
            return encoding.displayName();
        }

        @Override
        public void committed() throws IOException {
            if (limit > 0) {
                writer.flush();
                full = counter.count >= limit;
            }
        }

        void close() throws IOException {
            writer.close();
        }

        List<Path> written() {
            return written;
        }

        /** Fecha o que estiver aberto e apaga tudo o que foi gravado. */
        void abort() throws IOException {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                // o arquivo vai ser removido de qualquer jeito
            }
            for (Path file : written) {
                Files.deleteIfExists(file);
            }
        }
    }

    /** Conta os bytes que já foram para o arquivo. */
    private static final class Counting extends FilterOutputStream {

        private long count;

        Counting(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
