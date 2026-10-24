#!/usr/bin/env bash
set -euo pipefail

db2 list node directory | grep -q 'PII_NODE' || \
    db2 catalog tcpip node PII_NODE remote db2 server 50000
db2 list database directory | grep -q 'PII_PROTECTOR' || \
    db2 catalog database pii_protector at node PII_NODE

db2 connect to pii_protector user db2inst1 using "$DB2INST1_PASSWORD"

tables=(CONTACTS CONTACT_ADDRESSES CONTACT_PHONES CONTACT_EMAILS CONTACT_RELATIONSHIPS)
all_ready=true
for table in "${tables[@]}"; do
    exists="$(db2 -x "SELECT COUNT(*) FROM SYSCAT.TABLES WHERE TABSCHEMA = CURRENT SCHEMA AND TABNAME = '$table'" | tr -d '[:space:]')"
    if [[ "$exists" != "1" ]]; then
        all_ready=false
        continue
    fi

    rows="$(db2 -x "SELECT COUNT(*) FROM $table" | tr -d '[:space:]')"
    if [[ "$rows" != "10000000" ]]; then
        all_ready=false
    fi
done

if [[ "$all_ready" != true ]]; then
    for table in CONTACT_RELATIONSHIPS CONTACT_EMAILS CONTACT_PHONES CONTACT_ADDRESSES CONTACTS; do
        exists="$(db2 -x "SELECT COUNT(*) FROM SYSCAT.TABLES WHERE TABSCHEMA = CURRENT SCHEMA AND TABNAME = '$table'" | tr -d '[:space:]')"
        if [[ "$exists" == "1" ]]; then
            db2 "DROP TABLE $table"
        fi
    done
    db2 -s -tvf /var/custom/pii-samples.sql
fi

db2 terminate
