#!/bin/sh
# Gera src/dev/fbtools/dbeaver/psqldebug/interp/gds-codes.txt a partir dos cabeçalhos de mensagens do Firebird
# (include/firebird/impl/msg/*.h, os mesmos de onde o motor monta a tabela GDSCODE -> SQLCODE).
#
# Uso: tools/gds-codes.sh "C:/Program Files/Firebird/Firebird_5_0/include/firebird/impl/msg"
set -eu

msg_dir=${1:?informe a pasta include/firebird/impl/msg do Firebird}
out=$(dirname "$0")/../src/dev/fbtools/dbeaver/psqldebug/interp/gds-codes.txt

{
    echo "# Gerado por tools/gds-codes.sh a partir de include/firebird/impl/msg do Firebird 5.0. Não editar."
    echo "# GDSCODE, nome simbólico (o de WHEN GDSCODE), SQLCODE e SQLSTATE de cada erro do motor."
    cat "$msg_dir"/*.h | tr -d '\r' | awk '
        BEGIN {
            facility["JRD"] = 0; facility["QLI"] = 1; facility["GFIX"] = 3; facility["GPRE"] = 4
            facility["DSQL"] = 7; facility["DYN"] = 8; facility["INSTALL"] = 10; facility["TEST"] = 11
            facility["GBAK"] = 12; facility["SQLERR"] = 13; facility["SQLWARN"] = 14; facility["JRD_BUGCHK"] = 15
            facility["ISQL"] = 17; facility["GSEC"] = 18; facility["GSTAT"] = 21; facility["FBSVCMGR"] = 22
            facility["UTL"] = 23; facility["NBACKUP"] = 24; facility["FBTRACEMGR"] = 25
        }
        /^FB_IMPL_MSG\(/ {
            line = substr($0, length("FB_IMPL_MSG(") + 1)
            n = split(line, f, ", ")
            gsub(/"/, "", f[5]); gsub(/"/, "", f[6])
            # FB_IMPL_MSG_ENCODE: 0x14000000 | (facility << 16) | número
            code = 335544320 + facility[f[1]] * 65536 + f[2]
            print code, f[3], f[4], f[5] f[6]
        }' | sort -n
} > "$out"
echo "$out: $(grep -vc '^#' "$out") códigos"
