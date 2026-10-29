package dev.fbtools.dbeaver.psqldebug.psql.ast;

/** Condição de um {@link Handler}. */
public sealed interface ErrorCondition {

    /**
     * {@code EXCEPTION nome}: exceção de usuário.
     *
     * @param name nome normalizado
     */
    record UserException(String name) implements ErrorCondition {
    }

    /** {@code SQLCODE n}. */
    record SqlCode(int code) implements ErrorCondition {
    }

    /**
     * {@code GDSCODE nome}.
     *
     * @param name nome simbólico, como na Fonte (ex.: {@code arith_except})
     */
    record GdsCode(String name) implements ErrorCondition {
    }

    /** {@code SQLSTATE 'xxxxx'}. */
    record SqlState(String state) implements ErrorCondition {
    }

    /** {@code ANY}. */
    record AnyError() implements ErrorCondition {
    }
}
