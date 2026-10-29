package dev.fbtools.dbeaver.psqldebug.debug;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.model.IStackFrame;

/**
 * Objeto do DBeaver de cada stack frame, que a model presentation ({@code PsqlModelPresentation}) abre no editor
 * para destacar a linha: o da Rotina raiz e os das Rotinas alcançadas por step into, achados na primeira vez que um
 * frame do texto delas aparece. O texto de um {@code EXECUTE BLOCK} ou do corpo de uma package, que só existe num
 * editor SQL, é o próprio {@link SourceId}: o editor é procurado na hora de mostrar a linha. Um frame sem fonte
 * (procedure que o DBeaver não acha, package sem console aberto) leva o debug.ui ao editor de "fonte não encontrada".
 */
public final class PsqlSourceLocator implements ISourceLocator {

    private final Function<SourceId, Object> resolver;
    private final Map<SourceId, Optional<Object>> sources = new ConcurrentHashMap<>();

    /**
     * @param resolver objeto do DBeaver de um texto de Rotina (ou o próprio texto, se ele é de um editor SQL), ou
     *            {@code null} se não há; chamado fora da thread de UI, uma vez por texto
     */
    public PsqlSourceLocator(Function<SourceId, Object> resolver) {
        this.resolver = resolver;
    }

    @Override
    public Object getSourceElement(IStackFrame stackFrame) {
        if (!(stackFrame instanceof PsqlStackFrame frame)) {
            return null;
        }
        return sources.computeIfAbsent(frame.source(), source -> Optional.ofNullable(resolver.apply(source)))
                .orElse(null);
    }
}
