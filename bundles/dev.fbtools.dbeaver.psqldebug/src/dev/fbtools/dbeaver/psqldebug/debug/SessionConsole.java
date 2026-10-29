package dev.fbtools.dbeaver.psqldebug.debug;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

import dev.fbtools.dbeaver.psqldebug.interp.ProcedureResult;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;

/**
 * Console "Firebird PSQL Debug", onde cada Sessão de debug escreve as entradas, as saídas (ou as linhas do
 * {@code SUSPEND}) e como a transação terminou. Pode ser chamado de qualquer thread.
 */
public final class SessionConsole {

    private static final String NAME = "Firebird PSQL Debug";

    private final MessageConsole console;
    private final String isolation;

    /** @param isolation nome do isolamento da transação, para o cabeçalho */
    public SessionConsole(String isolation) {
        this.console = findOrCreate();
        this.isolation = isolation;
    }

    private static MessageConsole findOrCreate() {
        IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
        for (IConsole existing : manager.getConsoles()) {
            if (existing instanceof MessageConsole message && NAME.equals(existing.getName())) {
                return message;
            }
        }
        MessageConsole created = new MessageConsole(NAME, null);
        manager.addConsoles(new IConsole[] {created});
        return created;
    }

    /** Aviso antes do início da sessão, que não a impede. */
    public void warn(String message) {
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        print("\nAviso: " + message + "\n");
    }

    void started(RoutineDefinition routine, List<Object> inputs) {
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        StringBuilder text = new StringBuilder("\n== ").append(routine.displayName()).append(" (").append(isolation)
                .append(")\n");
        for (int i = 0; i < routine.inputs().size(); i++) {
            ParameterDeclaration input = routine.inputs().get(i);
            text.append("   ").append(input.name()).append(" = ")
                    .append(i < inputs.size() ? ValueText.of(inputs.get(i)) : "(padrão)").append('\n');
        }
        print(text.toString());
    }

    /**
     * Fim de uma Rotina executável: as saídas, o retorno de uma function ou, numa trigger {@code BEFORE}, os
     * {@code NEW.*} finais.
     */
    void finished(RoutineDefinition routine, ProcedureResult result, boolean committed) {
        boolean trigger = routine.kind() == RoutineDefinition.Kind.TRIGGER;
        StringBuilder text = new StringBuilder(routine.returnValue() != null ? "Retorno:\n"
                : trigger ? "NEW final:\n" : "Saídas:\n");
        if (result.names().isEmpty()) {
            text.append(trigger ? "   (trigger AFTER: o NEW não é alterado)\n" : "   (nenhuma)\n");
        }
        for (int i = 0; i < result.names().size(); i++) {
            text.append("   ").append(result.names().get(i)).append(" = ")
                    .append(ValueText.of(result.values().get(i))).append('\n');
        }
        print(text.append(end(committed)).toString());
    }

    /** Linha emitida por um {@code SUSPEND}, na hora: com a execução pausada, as anteriores já estão no console. */
    void row(RoutineDefinition routine, int number, List<Object> values) {
        StringBuilder text = new StringBuilder("SUSPEND, linha ").append(number).append(": ");
        for (int i = 0; i < values.size(); i++) {
            text.append(i == 0 ? "" : ", ").append(routine.outputs().get(i).name()).append(" = ")
                    .append(ValueText.of(values.get(i)));
        }
        print(text.append('\n').toString());
    }

    /**
     * Fim de uma Rotina selecionável (ou de um {@code EXECUTE BLOCK}): as linhas do {@code SUSPEND}, na ordem em que
     * foram emitidas, numa tabela. Um {@code EXECUTE BLOCK} sem {@code SUSPEND} não devolve as saídas, como no
     * servidor.
     */
    void finished(RoutineDefinition routine, List<List<Object>> rows, boolean committed) {
        StringBuilder text = new StringBuilder();
        if (rows.isEmpty()) {
            text.append("Resultado: nenhuma linha");
            if (routine.kind() == RoutineDefinition.Kind.EXECUTE_BLOCK && !routine.outputs().isEmpty()) {
                text.append(" (sem SUSPEND, o EXECUTE BLOCK não devolve as saídas)");
            }
            text.append('\n');
        } else {
            text.append("Resultado: ").append(rows.size()).append(rows.size() == 1 ? " linha\n" : " linhas\n")
                    .append(ResultTable.of(routine.outputs().stream().map(ParameterDeclaration::name).toList(),
                            rows));
        }
        print(text.append(end(committed)).toString());
    }

    private static String end(boolean committed) {
        return committed ? "Fim: commit feito.\n" : "Fim: rollback feito.\n";
    }

    void aborted() {
        print("Interrompida pelo Terminate: rollback feito.\n");
    }

    void failed(SQLException error) {
        print("Erro (SQLSTATE " + error.getSQLState() + ", código " + error.getErrorCode() + "): " + error.getMessage()
                + "\nFim: rollback feito.\n");
    }

    void failed(RuntimeException error) {
        print("Erro do debugger: " + error.getMessage() + "\nFim: rollback feito.\n");
    }

    private void print(String text) {
        try (MessageConsoleStream stream = console.newMessageStream()) {
            stream.print(text);
        } catch (IOException e) {
            // o console fechou; não há onde mostrar
        }
    }
}
