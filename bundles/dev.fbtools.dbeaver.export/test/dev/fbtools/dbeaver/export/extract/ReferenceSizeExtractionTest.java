package dev.fbtools.dbeaver.export.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.export.testing.ExportTestDatabase;

/**
 * Issue 10: desempenho, memória e cancelamento num banco do porte de referência da spec (~250 domínios, ~380 tabelas,
 * ~590 views, ~400 procedures, ~320 triggers, ~260 generators). Exige o Firebird 5 local (ADR 0008).
 */
class ReferenceSizeExtractionTest {

    private static final int DOMAINS = 250;
    private static final int TABLES = 380;
    private static final int VIEWS = 590;
    private static final int PROCEDURES = 400;
    private static final int TRIGGERS = 320;
    private static final int GENERATORS = 260;

    private static ExportTestDatabase database;

    @BeforeAll
    static void createReferenceSizeDatabase() throws Exception {
        database = ExportTestDatabase.create("reference-size", "UTF8");
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            int[] count = {0};
            for (int i = 1; i <= DOMAINS; i++) {
                run(connection, statement, count, "CREATE DOMAIN D_" + i + " AS VARCHAR(" + (10 + i) + ")");
            }
            for (int i = 1; i <= GENERATORS; i++) {
                run(connection, statement, count, "CREATE SEQUENCE GEN_" + i);
            }
            for (int i = 1; i <= TABLES; i++) {
                String fk = i % 3 == 0 ? ", CONSTRAINT FK_T" + i + " FOREIGN KEY (PAI) REFERENCES T_" + (i - 1) + " (ID)"
                        : "";
                run(connection, statement, count, "CREATE TABLE T_" + i + " (ID INTEGER NOT NULL PRIMARY KEY, "
                        + "PAI INTEGER, NOME D_" + (1 + i % DOMAINS) + ", VALOR NUMERIC(15,2) DEFAULT 0" + fk + ")");
            }
            for (int i = 1; i <= VIEWS; i++) {
                // As primeiras leem tabelas; as demais leem uma dessas views (dependência de dois níveis).
                String from = i <= TABLES ? "T_" + i : "V_" + (i - TABLES);
                run(connection, statement, count, "CREATE VIEW V_" + i + " AS SELECT ID, NOME FROM " + from);
            }
            for (int i = 1; i <= PROCEDURES; i++) {
                String body = i % 10 == 1
                        ? "R = A + " + i + "; SUSPEND;"
                        : "FOR SELECT R FROM P_" + (i - 1) + "(:A) INTO :R DO SUSPEND;";
                run(connection, statement, count, "CREATE PROCEDURE P_" + i + " (A INTEGER) RETURNS (R INTEGER) AS "
                        + "BEGIN " + body + " END");
            }
            for (int i = 1; i <= TRIGGERS; i++) {
                run(connection, statement, count, "CREATE TRIGGER TR_" + i + " FOR T_" + i
                        + " ACTIVE BEFORE INSERT POSITION 0 AS BEGIN NEW.VALOR = COALESCE(NEW.VALOR, 0); END");
            }
            connection.commit();
        }
    }

    private static void run(Connection connection, Statement statement, int[] count, String sql) throws SQLException {
        statement.execute(sql);
        if (++count[0] % 200 == 0) {
            connection.commit();
        }
    }

    @Test
    void theExtractionFinishesInReasonableTimeAndMemory() throws Exception {
        Runtime runtime = Runtime.getRuntime();
        long[] chars = {0};
        long before = usedMemory(runtime);
        long start = System.nanoTime();
        try (Connection connection = database.connect()) {
            new MetadataExtractor(connection).extract(Selection.all(), ExtractionOptions.DEFAULT, counting(chars),
                    null, ExtractionProgress.NONE);
        }
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        assertTrue(chars[0] > 200_000, "o script tem de ter o corpo dos ~2.200 objetos: " + chars[0]);
        assertTrue(seconds < 60, "a extração do porte de referência levou " + seconds + " s");
        // Só mede o que sobra depois da extração (retenção), não o pico; o streaming dos dados é provado em
        // DataExtractionTest (tabela de 1 milhão de registros).
        long growthMb = (usedMemory(runtime) - before) / (1024 * 1024);
        assertTrue(growthMb < 512, "a memória cresceu " + growthMb + " MB");
    }

    @Test
    void theReferenceSizeDatabaseHasTheExpectedObjectsAndReportsProgress() throws Exception {
        int[] total = {0};
        try (Connection connection = database.connect()) {
            new MetadataExtractor(connection).extract(Selection.all(), ExtractionOptions.DEFAULT, counting(new long[1]),
                    null, new ExtractionProgress() {
                        @Override
                        public void extracted(String description) {
                            total[0]++;
                        }
                    });
            MetadataExtractor extractor = new MetadataExtractor(connection);
            assertEquals(DOMAINS, extractor.listDomains().size());
            assertEquals(TABLES, extractor.listTables().size());
            assertEquals(VIEWS, extractor.listViews().size());
            assertEquals(PROCEDURES, extractor.listProcedures().size());
            assertEquals(TRIGGERS, extractor.listTriggers().size());
            assertEquals(GENERATORS, extractor.listGenerators().size());
        }
        assertTrue(total[0] > 0, "o progresso é informado");
    }

    @Test
    void cancellingStopsTheExtractionPromptly() throws Exception {
        long[] cancelledAt = {0};
        ExtractionProgress cancelAfterTheFirstObjects = new ExtractionProgress() {
            private int objects;

            @Override
            public void extracted(String description) {
                if (++objects == 50 && cancelledAt[0] == 0) {
                    cancelledAt[0] = System.nanoTime();
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelledAt[0] != 0;
            }
        };
        try (Connection connection = database.connect()) {
            assertThrows(ExtractionCancelledException.class, () -> new MetadataExtractor(connection)
                    .extract(Selection.all(), ExtractionOptions.DEFAULT, counting(new long[1]), null,
                            cancelAfterTheFirstObjects));
        }
        long millis = (System.nanoTime() - cancelledAt[0]) / 1_000_000L;
        assertTrue(cancelledAt[0] != 0, "o cancelamento só vale depois de 50 objetos");
        assertTrue(millis < 5_000, "depois de cancelar, a extração demorou " + millis + " ms para parar");
    }

    private static long usedMemory(Runtime runtime) {
        System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static Writer counting(long[] chars) {
        return new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) {
                chars[0] += length;
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() throws IOException {
            }
        };
    }
}
