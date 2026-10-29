package dev.fbtools.dbeaver.psqldebug.source;

/** Tipo SQL a partir das colunas de {@code RDB$FIELDS}, no formato aceito em declarações e em {@code CAST}. */
final class FieldTypes {

    private FieldTypes() {
    }

    static String render(int fieldType, Integer subType, Integer scale, Integer precision, Integer characterLength,
            String characterSet) {
        int sub = subType == null ? 0 : subType;
        int negativeScale = scale == null ? 0 : -scale;
        return switch (fieldType) {
            case 7 -> integral("SMALLINT", 4, sub, negativeScale, precision);
            case 8 -> integral("INTEGER", 9, sub, negativeScale, precision);
            case 16 -> integral("BIGINT", 18, sub, negativeScale, precision);
            case 26 -> integral("INT128", 38, sub, negativeScale, precision);
            case 10 -> "FLOAT";
            // Dialeto 1 guardava NUMERIC como DOUBLE PRECISION com escala.
            case 27 -> negativeScale > 0 ? "NUMERIC(15," + negativeScale + ")" : "DOUBLE PRECISION";
            case 24 -> "DECFLOAT(16)";
            case 25 -> "DECFLOAT(34)";
            case 23 -> "BOOLEAN";
            case 12 -> "DATE";
            case 13 -> "TIME";
            case 28 -> "TIME WITH TIME ZONE";
            case 35 -> "TIMESTAMP";
            case 29 -> "TIMESTAMP WITH TIME ZONE";
            case 14 -> "CHAR(" + characterLength + ")" + charset(characterSet);
            case 37 -> "VARCHAR(" + characterLength + ")" + charset(characterSet);
            case 261 -> "BLOB SUB_TYPE " + sub + (sub == 1 ? charset(characterSet) : "");
            default -> throw new IllegalArgumentException("RDB$FIELD_TYPE não suportado: " + fieldType);
        };
    }

    private static String integral(String name, int maxPrecision, int subType, int scale, Integer precision) {
        if (subType == 0 && scale == 0) {
            return name;
        }
        int digits = precision == null || precision == 0 ? maxPrecision : precision;
        return (subType == 2 ? "DECIMAL(" : "NUMERIC(") + digits + "," + scale + ")";
    }

    private static String charset(String name) {
        return name == null ? "" : " CHARACTER SET " + name;
    }
}
