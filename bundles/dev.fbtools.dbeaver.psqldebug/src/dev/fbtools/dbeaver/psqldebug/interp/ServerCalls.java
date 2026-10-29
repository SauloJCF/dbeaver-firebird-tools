package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

import dev.fbtools.dbeaver.psqldebug.psql.CallSites;
import dev.fbtools.dbeaver.psqldebug.psql.PreCheck;
import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.StatementLines;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Subroutine;

/**
 * Chamadas de Rotina sem step into num texto de Rotina, para o aviso no editor durante a Sessão de debug: as
 * Executadas no servidor (dentro de expressão ou de Fatia DML) e as Step into elegíveis para uma Rotina que o
 * Interpretador não executa (Fonte do banco não reconhecida, construção recusada pelo Pré-check), que vão inteiras ao
 * servidor. {@link CallSites} é sintático; aqui só ficam os nomes que são de Rotina PSQL com Fonte do banco ou de
 * sub-rotina. Uma função interna ({@code UPPER(}), uma tabela depois de {@code FROM} ou uma Rotina externa não têm
 * aviso.
 */
public final class ServerCalls {

    private ServerCalls() {
    }

    /**
     * Chamada sem step into.
     *
     * @param statement posição do statement que contém a chamada, onde a execução para antes dela ({@code null} numa
     *            declaração: valor padrão de entrada, valor inicial de variável, cursor); um statement de várias linhas
     *            pode ter a chamada numa linha seguinte
     * @param reason por que não há step into: {@code dentro de Fatia DML}, {@code dentro de expressão} ou o motivo de a
     *            Rotina não ser interpretada
     */
    public record Call(CallSites.CallSite site, SourcePosition statement, String reason) {

        /** Texto do aviso: {@code F: executado no servidor (dentro de Fatia DML), sem step into}. */
        public String warning() {
            return site.displayName() + ": executado no servidor (" + reason + "), sem step into";
        }
    }

    /**
     * @param routine a Rotina de cujo texto são as posições ({@code Frame.sourceRoutine()}); as chamadas dos corpos das
     *            sub-rotinas dela entram junto
     * @param catalog de onde vêm as Rotinas chamadas, o mesmo da sessão
     * @return as chamadas em ordem de posição
     */
    public static List<Call> of(RoutineDefinition routine, RoutineCatalog catalog) throws SQLException {
        // O statement de cada chamada: o último que começa antes dela, no corpo da mesma Rotina ou sub-rotina.
        Map<CallSites.CallSite, SourcePosition> statements = new HashMap<>();
        for (Subroutine subroutine : routine.subroutines()) {
            NavigableSet<SourcePosition> starts = StatementLines.starts(subroutine.routine());
            for (CallSites.CallSite site : CallSites.of(subroutine.routine())) {
                statements.put(site, starts.floor(site.position()));
            }
        }
        NavigableSet<SourcePosition> starts = StatementLines.starts(routine);
        List<Call> calls = new ArrayList<>();
        for (CallSites.CallSite site : CallSites.of(routine)) {
            SourcePosition statement = statements.containsKey(site) ? statements.get(site)
                    : starts.floor(site.position());
            String reason;
            if (site.stepIntoEligible()) {
                reason = notInterpreted(routine, site, catalog);
            } else if (isRoutine(routine, site, catalog)) {
                reason = site.form() == CallSites.Form.DML ? "dentro de Fatia DML" : "dentro de expressão";
            } else {
                reason = null;
            }
            if (reason != null) {
                calls.add(new Call(site, statement, reason));
            }
        }
        return calls;
    }

    /** Candidato numa expressão ou Fatia DML que é sub-rotina, function ou procedure PSQL. */
    private static boolean isRoutine(RoutineDefinition routine, CallSites.CallSite site, RoutineCatalog catalog)
            throws SQLException {
        if (site.packageName() == null && (routine.subroutine(RoutineDefinition.Kind.FUNCTION, site.name()) != null
                || routine.subroutine(RoutineDefinition.Kind.PROCEDURE, site.name()) != null)) {
            return true;
        }
        // Com parênteses, pode ser function ou, depois de FROM, procedure; sem, só procedure. As duas servem.
        for (RoutineDefinition.Kind kind : List.of(RoutineDefinition.Kind.FUNCTION,
                RoutineDefinition.Kind.PROCEDURE)) {
            try {
                if (catalog.find(kind, routine.packageName(), site.packageName(), site.name()).isPresent()) {
                    return true;
                }
            } catch (PsqlSyntaxException e) {
                return true; // é Rotina, só a Fonte dela que a gramática não reconhece
            }
        }
        return false;
    }

    /**
     * Motivo de a chamada elegível ir inteira ao servidor, ou {@code null} se o step into entra nela (ou se o nome não
     * é de Rotina PSQL: {@code x = UPPER(y)}).
     */
    private static String notInterpreted(RoutineDefinition routine, CallSites.CallSite site, RoutineCatalog catalog)
            throws SQLException {
        RoutineDefinition.Kind kind = site.form() == CallSites.Form.ASSIGNMENT ? RoutineDefinition.Kind.FUNCTION
                : RoutineDefinition.Kind.PROCEDURE;
        if (site.packageName() == null && routine.subroutine(kind, site.name()) != null) {
            return null; // o Pré-check da Rotina já passou pelo corpo da sub-rotina
        }
        RoutineDefinition callee;
        try {
            callee = catalog.find(kind, routine.packageName(), site.packageName(), site.name()).orElse(null);
        } catch (PsqlSyntaxException e) {
            return "Fonte do banco não reconhecida pelo debugger: " + e.getMessage();
        }
        if (callee == null) {
            return null;
        }
        List<PreCheck.Finding> findings = PreCheck.scan(callee);
        return findings.isEmpty() ? null : "Pré-check de " + callee.displayName() + ": " + findings.get(0);
    }
}
