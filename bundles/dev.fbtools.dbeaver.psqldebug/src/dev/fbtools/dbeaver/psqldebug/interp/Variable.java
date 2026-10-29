package dev.fbtools.dbeaver.psqldebug.interp;

/** Parâmetro ou variável local de uma Rotina em execução. O valor é o que o Jaybird devolve para o tipo. */
public final class Variable {

    /** Origem da variável. */
    public enum Kind {
        INPUT, OUTPUT, LOCAL
    }

    private final String name;
    private final String type;
    private final boolean notNull;
    private final Kind kind;
    private Object value;

    Variable(String name, String type, boolean notNull, Kind kind) {
        this.name = name;
        this.type = type;
        this.notNull = notNull;
        this.kind = kind;
    }

    public String name() {
        return name;
    }

    /** Tipo declarado, como texto aceito por {@code CAST(? AS <tipo>)}. */
    public String type() {
        return type;
    }

    public boolean notNull() {
        return notNull;
    }

    public Kind kind() {
        return kind;
    }

    public Object value() {
        return value;
    }

    void assign(Object newValue) {
        this.value = newValue;
    }
}
