package dev.fbtools.dbeaver.export.extract;

/**
 * Tipo de um campo de {@code RDB$FIELDS} em SQL. Charset e collation vêm já resolvidos pelo nome; o charset padrão do
 * banco e a collation padrão do charset são omitidos, a menos que {@code alwaysCharset} (ADR 0009).
 */
record FieldType(int type, int subType, int length, Integer charLength, Integer precision, int scale,
        Integer segmentSize, String characterSet, String collation) {

    String toSql(String defaultCharacterSet, boolean alwaysCharset) {
        String sql = switch (type) {
            case 7 -> numeric("SMALLINT", 4);
            case 8 -> numeric("INTEGER", 9);
            case 16 -> numeric("BIGINT", 18);
            case 26 -> numeric("INT128", 38);
            case 10 -> "FLOAT";
            case 27 -> "DOUBLE PRECISION";
            case 12 -> "DATE";
            case 13 -> "TIME";
            case 35 -> "TIMESTAMP";
            case 28 -> "TIME WITH TIME ZONE";
            case 29 -> "TIMESTAMP WITH TIME ZONE";
            case 23 -> "BOOLEAN";
            case 24 -> "DECFLOAT(16)";
            case 25 -> "DECFLOAT(34)";
            case 14 -> "CHAR(" + textLength() + ")";
            case 37 -> "VARCHAR(" + textLength() + ")";
            case 261 -> "BLOB SUB_TYPE " + subType + " SEGMENT SIZE " + (segmentSize == null ? 80 : segmentSize);
            default -> throw new IllegalStateException("Tipo de campo desconhecido: " + type);
        };
        boolean text = type == 14 || type == 37 || (type == 261 && subType == 1);
        return text && characterSet != null ? sql + characterSetClause(defaultCharacterSet, alwaysCharset) : sql;
    }

    private String characterSetClause(String defaultCharacterSet, boolean alwaysCharset) {
        boolean customCollation = collation != null && !collation.equals(characterSet);
        StringBuilder clause = new StringBuilder();
        if (alwaysCharset || customCollation || !characterSet.equals(defaultCharacterSet)) {
            clause.append(" CHARACTER SET ").append(characterSet);
        }
        if (customCollation) {
            clause.append(" COLLATE ").append(Identifiers.quote(collation));
        }
        return clause.toString();
    }

    private String numeric(String integerType, int defaultPrecision) {
        boolean exact = scale < 0 || subType == 1 || subType == 2;
        if (!exact) {
            return integerType;
        }
        String keyword = subType == 2 ? "DECIMAL" : "NUMERIC";
        int digits = precision == null || precision == 0 ? defaultPrecision : precision;
        return keyword + "(" + digits + "," + -scale + ")";
    }

    private int textLength() {
        return charLength != null && charLength > 0 ? charLength : length;
    }
}
