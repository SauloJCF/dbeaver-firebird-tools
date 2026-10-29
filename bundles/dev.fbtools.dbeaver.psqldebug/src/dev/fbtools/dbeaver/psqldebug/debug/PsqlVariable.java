package dev.fbtools.dbeaver.psqldebug.debug;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

import dev.fbtools.dbeaver.psqldebug.interp.Frame;
import dev.fbtools.dbeaver.psqldebug.interp.Variable;

/**
 * Parâmetro ou variável local de um frame, com o valor do momento da pausa e o tipo declarado. Editável durante a
 * pausa: o texto digitado é convertido pelo servidor ({@link PsqlDebugTarget#assign}), e um erro de conversão é
 * mostrado pelo debug.ui com a variável como estava.
 */
final class PsqlVariable extends PsqlDebugElement implements IVariable {

    private final PsqlDebugTarget target;
    private final Frame frame;
    private final Variable variable;
    private final boolean changed;
    private volatile IValue value;

    /** @param changed o valor mudou desde a pausa anterior (o debug.ui o destaca) */
    PsqlVariable(PsqlDebugTarget target, Frame frame, Variable variable, boolean changed) {
        super(target);
        this.target = target;
        this.frame = frame;
        this.variable = variable;
        this.changed = changed;
        this.value = new PsqlValue(target, variable.type(), variable.value());
    }

    @Override
    public IValue getValue() {
        return value;
    }

    @Override
    public String getName() {
        return variable.name();
    }

    @Override
    public String getReferenceTypeName() {
        return variable.type().strip() + (variable.notNull() ? " NOT NULL" : "");
    }

    @Override
    public boolean hasValueChanged() {
        return changed;
    }

    /**
     * Converte o texto pelo servidor para o tipo da variável e a altera; {@code NULL}, sem aspas, é o valor nulo.
     *
     * @throws DebugException com a mensagem do servidor, se o texto não serve para o tipo
     */
    @Override
    public void setValue(String expression) throws DebugException {
        target.assign(frame, variable, expression);
        value = new PsqlValue(target, variable.type(), variable.value());
        fireChangeEvent(DebugEvent.CONTENT);
    }

    @Override
    public void setValue(IValue newValue) throws DebugException {
        setValue(newValue.getValueString());
    }

    @Override
    public boolean supportsValueModification() {
        return target.isSuspended();
    }

    /** Quem confere o texto é o servidor, no {@link #setValue(String)}: o debug.ui chama este a cada tecla. */
    @Override
    public boolean verifyValue(String expression) {
        return true;
    }

    @Override
    public boolean verifyValue(IValue newValue) {
        return false;
    }
}
