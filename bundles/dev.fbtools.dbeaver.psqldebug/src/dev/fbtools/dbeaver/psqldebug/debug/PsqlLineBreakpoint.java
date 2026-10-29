package dev.fbtools.dbeaver.psqldebug.debug;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.LineBreakpoint;

/**
 * Breakpoint de linha no texto de uma Rotina ({@link SourceId}). Não persistente (ADR 0004): o marker não é gravado,
 * e o breakpoint some quando o editor da Rotina fecha ({@code EditorBreakpoints}). Como o editor do DBeaver não é de
 * arquivo, o marker fica na raiz do workspace e guarda a Rotina nos atributos.
 */
public final class PsqlLineBreakpoint extends LineBreakpoint {

    private static final String MARKER_TYPE = "dev.fbtools.dbeaver.psqldebug.lineBreakpointMarker";
    private static final String DATA_SOURCE = "dev.fbtools.dbeaver.psqldebug.dataSource";
    private static final String SOURCE_KIND = "dev.fbtools.dbeaver.psqldebug.sourceKind";
    private static final String SOURCE_NAME = "dev.fbtools.dbeaver.psqldebug.sourceName";

    /** Exigido pelo debug.core para recriar breakpoints a partir de markers. */
    public PsqlLineBreakpoint() {
    }

    /** @param line linha do texto, a partir de 1 */
    public PsqlLineBreakpoint(SourceId source, int line) throws CoreException {
        IResource root = ResourcesPlugin.getWorkspace().getRoot();
        run(getMarkerRule(root), monitor -> {
            IMarker marker = root.createMarker(MARKER_TYPE);
            setMarker(marker);
            marker.setAttribute(IBreakpoint.ID, getModelIdentifier());
            marker.setAttribute(IBreakpoint.ENABLED, true);
            marker.setAttribute(IBreakpoint.PERSISTED, false);
            marker.setAttribute(IMarker.TRANSIENT, true);
            marker.setAttribute(DATA_SOURCE, source.dataSourceId());
            marker.setAttribute(SOURCE_KIND, source.kind().name());
            marker.setAttribute(SOURCE_NAME, source.name());
            setLine(marker, source, line);
        });
    }

    /** @return a Rotina, ou {@code null} se o marker já foi removido */
    public SourceId source() {
        IMarker marker = getMarker();
        if (marker == null || !marker.exists()) {
            return null;
        }
        String kind = marker.getAttribute(SOURCE_KIND, null);
        return kind == null ? null
                : new SourceId(marker.getAttribute(DATA_SOURCE, null), SourceId.Kind.valueOf(kind),
                        marker.getAttribute(SOURCE_NAME, null));
    }

    /** @param line nova linha, a partir de 1 */
    public void moveTo(int line) throws CoreException {
        SourceId source = source();
        if (source != null) {
            setLine(ensureMarker(), source, line);
        }
    }

    private static void setLine(IMarker marker, SourceId source, int line) throws CoreException {
        marker.setAttribute(IMarker.LINE_NUMBER, line);
        marker.setAttribute(IMarker.MESSAGE, label(source, line));
    }

    /**
     * Texto da view Breakpoints: {@code P [linha 12]}; num editor SQL, {@code EXECUTE BLOCK (Script-3.sql) [linha 12]};
     * {@code null} se o marker já foi removido.
     */
    public String label() {
        SourceId source = source();
        return source == null ? null : label(source, getMarker().getAttribute(IMarker.LINE_NUMBER, 0));
    }

    private static String label(SourceId source, int line) {
        String name = source.kind() == SourceId.Kind.EXECUTE_BLOCK
                ? "EXECUTE BLOCK" + (source.name() == null ? "" : " (" + source.name() + ")") : source.name();
        return name + " [linha " + line + "]";
    }

    @Override
    public String getModelIdentifier() {
        return PsqlDebugElement.MODEL_ID;
    }
}
