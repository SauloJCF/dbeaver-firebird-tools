package dev.fbtools.dbeaver.psqldebug.debug;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

/** Valor de uma variável num momento, como o Jaybird o devolveu; o texto é o de {@link ValueText}. */
final class PsqlValue extends PsqlDebugElement implements IValue {

    private final String type;
    private final Object value;

    PsqlValue(IDebugTarget target, String type, Object value) {
        super(target);
        this.type = type;
        this.value = value;
    }

    @Override
    public String getReferenceTypeName() {
        return type;
    }

    @Override
    public String getValueString() {
        return ValueText.of(value);
    }

    @Override
    public boolean isAllocated() {
        return true;
    }

    @Override
    public IVariable[] getVariables() {
        return new IVariable[0];
    }

    @Override
    public boolean hasVariables() {
        return false;
    }
}
