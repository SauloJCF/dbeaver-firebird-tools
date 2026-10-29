package dev.fbtools.dbeaver.psqldebug.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * Tabela de texto das linhas do {@code SUSPEND}, para o console: uma coluna {@code #} com o número da linha, uma por
 * saída, alinhadas pelo valor mais largo.
 */
final class ResultTable {

    /** Largura máxima de uma célula; a linha do {@code SUSPEND} no console tem o valor inteiro. */
    static final int MAX_CELL = 60;

    private ResultTable() {
    }

    /**
     * @param columns nomes das saídas, na ordem do {@code RETURNS}
     * @param rows valores de cada linha, na ordem de emissão
     * @return uma linha de texto por linha da tabela, a primeira com os nomes, cada uma terminada em {@code \n}
     */
    static String of(List<String> columns, List<List<Object>> rows) {
        List<List<String>> table = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of("#"));
        header.addAll(columns);
        table.add(header);
        for (int i = 0; i < rows.size(); i++) {
            List<String> cells = new ArrayList<>(List.of(Integer.toString(i + 1)));
            rows.get(i).forEach(value -> cells.add(cell(value)));
            table.add(cells);
        }
        int[] widths = new int[header.size()];
        for (List<String> line : table) {
            for (int i = 0; i < line.size(); i++) {
                widths[i] = Math.max(widths[i], line.get(i).length());
            }
        }
        StringBuilder text = new StringBuilder();
        for (List<String> line : table) {
            StringBuilder row = new StringBuilder("  ");
            for (int i = 0; i < line.size(); i++) {
                row.append(' ').append(line.get(i)).append(" ".repeat(widths[i] - line.get(i).length() + 1));
            }
            text.append(row.toString().stripTrailing()).append('\n');
        }
        return text.toString();
    }

    /** Valor numa célula: numa linha só, e cortado se passar de {@link #MAX_CELL}. */
    private static String cell(Object value) {
        String text = ValueText.of(value).replaceAll("\\R", " ");
        return text.length() > MAX_CELL ? text.substring(0, MAX_CELL - 1) + "…" : text;
    }
}
