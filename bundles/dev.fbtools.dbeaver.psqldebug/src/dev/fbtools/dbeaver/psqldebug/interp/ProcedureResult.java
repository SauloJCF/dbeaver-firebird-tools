package dev.fbtools.dbeaver.psqldebug.interp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parâmetros de saída de uma procedure executável, na ordem do {@code RETURNS}. Valores podem ser nulos. */
public record ProcedureResult(List<String> names, List<Object> values) {

    public ProcedureResult {
        names = List.copyOf(names);
        values = Collections.unmodifiableList(new ArrayList<>(values));
        if (names.size() != values.size()) {
            throw new IllegalArgumentException("nomes e valores com tamanhos diferentes");
        }
    }

    public Object value(String name) {
        int index = names.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException("Parâmetro de saída inexistente: " + name);
        }
        return values.get(index);
    }
}
