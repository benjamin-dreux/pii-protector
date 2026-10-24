package com.example.piiprotector.encryption;

import com.example.piiprotector.crypto.PiiEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class EncryptionJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EncryptionJobService.class);
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int MAX_RETAINED_JOBS = 100;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*");
    private static final Map<String, String> JDBC_DRIVER_PREFIXES = Map.of(
            "org.postgresql.Driver", "jdbc:postgresql:",
            "oracle.jdbc.OracleDriver", "jdbc:oracle:thin:",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:",
            "com.ibm.db2.jcc.DB2Driver", "jdbc:db2:",
            "org.h2.Driver", "jdbc:h2:"
    );
    private static final Map<String, String> JDBC_DRIVER_DATABASES = Map.of(
            "org.postgresql.Driver", "postgresql",
            "oracle.jdbc.OracleDriver", "oracle",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", "sqlserver",
            "com.ibm.db2.jcc.DB2Driver", "db2",
            "org.h2.Driver", "h2"
    );

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final PiiEncryptionService encryptionService;
    private final Executor executor;
    private final Map<UUID, MutableJob> jobs = new ConcurrentHashMap<>();

    public EncryptionJobService(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            PiiEncryptionService encryptionService,
            @Qualifier("encryptionJobExecutor") Executor executor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.encryptionService = encryptionService;
        this.executor = executor;
    }

    public synchronized EncryptionJobView start(EncryptionJobRequest request) {
        DatabaseTarget target = databaseTarget(request.connection());
        try {
            List<TableSpec> selectedTables = resolveTables(target, request.tables());
            int batchSize = request.batchSize() == null ? DEFAULT_BATCH_SIZE : request.batchSize();
            MutableJob job = new MutableJob(
                    UUID.randomUUID(),
                    target.name,
                    selectedTables,
                    batchSize,
                    target.jdbcTemplate,
                    target
            );
            jobs.put(job.id, job);
            removeOldJobs();
            try {
                executor.execute(() -> run(job));
            } catch (RuntimeException exception) {
                job.fail("Encryption job could not be scheduled.");
                throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Encryption job could not be scheduled");
            }
            return job.view();
        } catch (RuntimeException exception) {
            target.close();
            if (exception instanceof ResponseStatusException statusException) {
                throw statusException;
            }
            LOGGER.error("Unable to create encryption job database connection", exception);
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Unable to create encryption job");
        }
    }

    private DatabaseTarget databaseTarget(EncryptionDatabaseRequest connection) {
        if (connection == null) {
            return new DatabaseTarget("default", dataSource, jdbcTemplate, null);
        }
        String database = databaseType(connection.driver(), connection.url());
        HikariDataSource targetDataSource = new HikariDataSource();
        targetDataSource.setJdbcUrl(connection.url());
        targetDataSource.setUsername(connection.username());
        targetDataSource.setPassword(connection.password());
        targetDataSource.setDriverClassName(connection.driver());
        targetDataSource.setPoolName("pii-encryption-" + UUID.randomUUID().toString().substring(0, 8));
        targetDataSource.setMaximumPoolSize(2);
        targetDataSource.setMinimumIdle(0);
        targetDataSource.setConnectionTimeout(10_000);
        return new DatabaseTarget(database, targetDataSource, new JdbcTemplate(targetDataSource), targetDataSource);
    }

    private String databaseType(String driver, String url) {
        String urlPrefix = JDBC_DRIVER_PREFIXES.get(driver);
        if (urlPrefix == null || !url.startsWith(urlPrefix)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported JDBC driver or URL"
            );
        }
        return JDBC_DRIVER_DATABASES.get(driver);
    }

    public EncryptionJobView get(UUID id) {
        MutableJob job = jobs.get(id);
        if (job == null) {
            throw new ResponseStatusException(NOT_FOUND, "Encryption job not found");
        }
        return job.view();
    }

    public List<EncryptionJobView> getActiveJobs() {
        return jobs.values().stream()
                .filter(job -> !job.isTerminal())
                .sorted((left, right) -> left.createdAt.compareTo(right.createdAt))
                .map(MutableJob::view)
                .toList();
    }

    private record DatabaseTarget(
            String name,
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            HikariDataSource ownedDataSource
    ) {
        private void close() {
            if (ownedDataSource != null) {
                ownedDataSource.close();
            }
        }
    }

    private List<TableSpec> resolveTables(DatabaseTarget target, List<EncryptionTableRequest> requestedTables) {
        try (Connection connection = target.dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            String defaultSchema = connection.getSchema();
            String quote = metadata.getIdentifierQuoteString().trim();
            if (quote.isEmpty()) {
                throw new IllegalStateException("The configured database does not support quoted identifiers");
            }

            LinkedHashSet<TableSpec> resolvedTables = new LinkedHashSet<>();
            for (EncryptionTableRequest requestedTable : requestedTables) {
                validateIdentifier(requestedTable.name());
                requestedTable.columns().forEach(this::validateIdentifier);
                if (requestedTable.schema() != null) {
                    validateIdentifier(requestedTable.schema());
                }

                TableIdentity identity = findTable(
                        metadata,
                        catalog,
                        requestedTable,
                        defaultSchema
                );
                if (identity == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Table not found: " + tableLabel(requestedTable)
                    );
                }
                List<ColumnIdentity> columns = findColumns(metadata, identity, requestedTable.columns());
                String idColumn = findNumericPrimaryKey(metadata, identity);
                TableSpec table = new TableSpec(identity, idColumn, columns, quote, requestedTable.maxRows(), 0);
                long matchingRows = countProcessableRows(target.jdbcTemplate, table);
                long rowsToProcess = requestedTable.maxRows() == null
                        ? matchingRows
                        : Math.min(matchingRows, requestedTable.maxRows());
                resolvedTables.add(new TableSpec(
                        identity,
                        idColumn,
                        columns,
                        quote,
                        requestedTable.maxRows(),
                        rowsToProcess
                ));
            }

            if (resolvedTables.size() != requestedTables.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A table may only be specified once");
            }
            return List.copyOf(resolvedTables);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            LOGGER.error("Unable to inspect the configured database schema", exception);
            throw new ResponseStatusException(
                    SERVICE_UNAVAILABLE,
                    "Unable to inspect the configured database schema"
            );
        }
    }

    private TableIdentity findTable(
            DatabaseMetaData metadata,
            String catalog,
            EncryptionTableRequest requested,
            String defaultSchema
    ) throws SQLException {
        String wantedSchema = requested.schema() == null ? defaultSchema : requested.schema();
        List<TableIdentity> matches = new ArrayList<>();
        try (ResultSet tables = metadata.getTables(catalog, null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                String schemaName = tables.getString("TABLE_SCHEM");
                if (tableName == null || !tableName.equalsIgnoreCase(requested.name())) {
                    continue;
                }
                if (wantedSchema != null && !wantedSchema.isBlank()
                        && (schemaName == null || !schemaName.equalsIgnoreCase(wantedSchema))) {
                    continue;
                }
                matches.add(new TableIdentity(
                        tables.getString("TABLE_CAT"),
                        schemaName,
                        tableName
                ));
            }
        }
        if (matches.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Table name is ambiguous; specify its schema: " + requested.name()
            );
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private List<ColumnIdentity> findColumns(
            DatabaseMetaData metadata,
            TableIdentity table,
            List<String> requestedColumns
    ) throws SQLException {
        Map<String, ColumnIdentity> available = new LinkedHashMap<>();
        try (ResultSet columns = metadata.getColumns(
                table.catalog,
                table.schema,
                escapeMetadataPattern(metadata, table.name),
                null
        )) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                available.put(name.toLowerCase(Locale.ROOT), new ColumnIdentity(
                        name,
                        columns.getInt("DATA_TYPE"),
                        columns.getInt("COLUMN_SIZE"),
                        columns.getInt("DECIMAL_DIGITS")
                ));
            }
        }

        LinkedHashSet<ColumnIdentity> resolved = new LinkedHashSet<>();
        for (String requestedColumn : requestedColumns) {
            ColumnIdentity column = available.get(requestedColumn.toLowerCase(Locale.ROOT));
            if (column == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Column not found in " + tableLabel(table) + ": " + requestedColumn
                );
            }
            if (!isTextColumn(column.jdbcType)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Column must be a character/text column: " + tableLabel(table) + "." + column.name
                );
            }
            if (!resolved.add(column)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "A column may only be specified once: " + column.name
                );
            }
        }
        return List.copyOf(resolved);
    }

    private String findNumericPrimaryKey(
            DatabaseMetaData metadata,
            TableIdentity table
    ) throws SQLException {
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet keys = metadata.getPrimaryKeys(table.catalog, table.schema, table.name)) {
            while (keys.next()) {
                primaryKeys.add(keys.getString("COLUMN_NAME"));
            }
        }
        if (primaryKeys.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Table must have exactly one primary-key column: " + tableLabel(table)
            );
        }

        try (ResultSet columns = metadata.getColumns(
                table.catalog,
                table.schema,
                escapeMetadataPattern(metadata, table.name),
                escapeMetadataPattern(metadata, primaryKeys.getFirst())
        )) {
            if (!columns.next() || !isIntegerPrimaryKey(
                    columns.getInt("DATA_TYPE"),
                    columns.getInt("COLUMN_SIZE"),
                    columns.getInt("DECIMAL_DIGITS")
            )) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Table primary key must be an integer column: " + tableLabel(table)
                );
            }
        }
        return primaryKeys.getFirst();
    }

    private boolean isTextColumn(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                    Types.CLOB, Types.NCLOB -> true;
            default -> false;
        };
    }

    private boolean isIntegerPrimaryKey(int jdbcType, int precision, int scale) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> true;
            case Types.NUMERIC, Types.DECIMAL -> scale == 0 && precision > 0 && precision <= 18;
            default -> false;
        };
    }

    private void validateIdentifier(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid SQL identifier: " + identifier
            );
        }
    }

    private String escapeMetadataPattern(DatabaseMetaData metadata, String identifier) throws SQLException {
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return identifier;
        }
        return identifier.replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
    }

    private void run(MutableJob job) {
        job.start();
        try {
            for (TableSpec table : job.tables) {
                job.setCurrentTable(table.identity.qualifiedName());
                encryptTable(job, table);
            }
            job.succeed();
        } catch (Exception exception) {
            LOGGER.error("Encryption job {} failed", job.id, exception);
            job.fail("Database encryption failed; inspect server logs.");
        } finally {
            job.target.close();
        }
    }

    private void encryptTable(MutableJob job, TableSpec table) {
        String sql = selectSql(table);
        long afterId = Long.MIN_VALUE;
        long processedRows = 0;
        while (true) {
            if (table.maxRows != null && processedRows >= table.maxRows) {
                return;
            }
            int currentBatchSize = table.maxRows == null
                    ? job.batchSize
                    : (int) Math.min(job.batchSize, table.maxRows - processedRows);
            long cursor = afterId;
            List<EncryptionRow> rows = job.jdbcTemplate.query(
                    sql,
                    statement -> {
                        statement.setLong(1, cursor);
                        statement.setMaxRows(currentBatchSize);
                        statement.setFetchSize(currentBatchSize);
                    },
                    (ResultSet resultSet, int rowNumber) -> readRow(resultSet, table)
            );
            if (rows.isEmpty()) {
                return;
            }

            List<EncryptedRow> encryptedRows = new ArrayList<>(rows.size());
            long encryptedValues = 0;
            for (EncryptionRow row : rows) {
                List<String> values = new ArrayList<>(row.values.size());
                for (int index = 0; index < row.values.size(); index++) {
                    String value = row.values.get(index);
                    if (value != null) {
                        String encryptedValue = encryptionService.encrypt(value, table.purpose(index));
                        if (!encryptedValue.equals(value)) {
                            encryptedValues++;
                        }
                        value = encryptedValue;
                    }
                    values.add(value);
                }
                encryptedRows.add(new EncryptedRow(row.id, values));
            }

            job.jdbcTemplate.batchUpdate(updateSql(table), new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    EncryptedRow row = encryptedRows.get(index);
                    for (int valueIndex = 0; valueIndex < row.values.size(); valueIndex++) {
                        String value = row.values.get(valueIndex);
                        if (value == null) {
                            statement.setNull(valueIndex + 1, Types.VARCHAR);
                        } else {
                            statement.setString(valueIndex + 1, value);
                        }
                    }
                    statement.setLong(row.values.size() + 1, row.id);
                }

                @Override
                public int getBatchSize() {
                    return encryptedRows.size();
                }
            });

            afterId = rows.getLast().id;
            processedRows += rows.size();
            job.addProgress(table.identity.qualifiedName(), rows.size(), encryptedValues);
        }
    }

    private EncryptionRow readRow(ResultSet resultSet, TableSpec table) throws SQLException {
        List<String> values = new ArrayList<>(table.columns.size());
        for (int index = 0; index < table.columns.size(); index++) {
            values.add(resultSet.getString(index + 2));
        }
        return new EncryptionRow(resultSet.getLong(1), values);
    }

    private String selectSql(TableSpec table) {
        String selections = table.columns.stream()
                .map(column -> quote(table.quote, column.name))
                .collect(Collectors.joining(", "));
        return "SELECT " + quote(table.quote, table.idColumn) + ", " + selections
                + " FROM " + table.identity.quotedName(table.quote)
                + " WHERE " + quote(table.quote, table.idColumn) + " > ? AND (" + unencryptedPredicate(table) + ")"
                + " ORDER BY " + quote(table.quote, table.idColumn);
    }

    private long countProcessableRows(JdbcTemplate targetJdbcTemplate, TableSpec table) {
        String sql = "SELECT COUNT(*) FROM " + table.identity.quotedName(table.quote)
                + " WHERE " + unencryptedPredicate(table);
        Long rowCount = targetJdbcTemplate.queryForObject(sql, Long.class);
        if (rowCount == null) {
            throw new IllegalStateException("Database returned no row count for " + tableLabel(table.identity));
        }
        return rowCount;
    }

    private String unencryptedPredicate(TableSpec table) {
        return table.columns.stream()
                .map(column -> "(" + quote(table.quote, column.name) + " IS NOT NULL AND "
                        + quote(table.quote, column.name) + " NOT LIKE 'v1:%')")
                .collect(Collectors.joining(" OR "));
    }

    private String updateSql(TableSpec table) {
        String assignments = table.columns.stream()
                .map(column -> quote(table.quote, column.name) + " = ?")
                .collect(Collectors.joining(", "));
        return "UPDATE " + table.identity.quotedName(table.quote) + " SET " + assignments
                + " WHERE " + quote(table.quote, table.idColumn) + " = ?";
    }

    private String quote(String quote, String identifier) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private String tableLabel(EncryptionTableRequest table) {
        return table.schema() == null ? table.name() : table.schema() + "." + table.name();
    }

    private String tableLabel(TableIdentity table) {
        return table.qualifiedName();
    }

    private synchronized void removeOldJobs() {
        if (jobs.size() <= MAX_RETAINED_JOBS) {
            return;
        }
        jobs.values().stream()
                .filter(MutableJob::isTerminal)
                .sorted((left, right) -> left.createdAt.compareTo(right.createdAt))
                .limit(jobs.size() - MAX_RETAINED_JOBS)
                .forEach(job -> jobs.remove(job.id));
    }

    private record TableIdentity(String catalog, String schema, String name) {
        private String qualifiedName() {
            return schema == null || schema.isBlank() ? name : schema + "." + name;
        }

        private String quotedName(String quote) {
            return schema == null || schema.isBlank()
                    ? quote + name + quote
                    : quote + schema + quote + "." + quote + name + quote;
        }
    }

    private record ColumnIdentity(String name, int jdbcType, int size, int scale) {
    }

    private record TableSpec(
            TableIdentity identity,
            String idColumn,
            List<ColumnIdentity> columns,
            String quote,
            Long maxRows,
            long rowsToProcess
    ) {
        private String purpose(int index) {
            return (identity.qualifiedName() + "." + columns.get(index).name).toLowerCase(Locale.ROOT);
        }
    }

    private record EncryptionRow(long id, List<String> values) {
    }

    private record EncryptedRow(long id, List<String> values) {
    }

    private static final class MutableJob {
        private final UUID id;
        private final String database;
        private final List<TableSpec> tables;
        private final int batchSize;
        private final JdbcTemplate jdbcTemplate;
        private final DatabaseTarget target;
        private final Instant createdAt = Instant.now();
        private final Map<String, Long> encryptedValuesByTable = new ConcurrentHashMap<>();
        private final Map<String, Long> processedRowsByTable = new ConcurrentHashMap<>();
        private volatile String status = "QUEUED";
        private volatile String currentTable;
        private volatile long processedRows;
        private volatile long encryptedValues;
        private volatile Instant startedAt;
        private volatile Instant completedAt;
        private volatile String error;

        private MutableJob(
                UUID id,
                String database,
                List<TableSpec> tables,
                int batchSize,
                JdbcTemplate jdbcTemplate,
                DatabaseTarget target
        ) {
            this.id = id;
            this.database = database;
            this.tables = tables;
            this.batchSize = batchSize;
            this.jdbcTemplate = jdbcTemplate;
            this.target = target;
        }

        private synchronized void start() {
            status = "RUNNING";
            startedAt = Instant.now();
        }

        private synchronized void setCurrentTable(String table) {
            currentTable = table;
        }

        private synchronized void addProgress(String table, long rows, long values) {
            processedRows += rows;
            encryptedValues += values;
            processedRowsByTable.merge(table, rows, Long::sum);
            encryptedValuesByTable.merge(table, values, Long::sum);
        }

        private synchronized void succeed() {
            status = "SUCCEEDED";
            currentTable = null;
            completedAt = Instant.now();
        }

        private synchronized void fail(String message) {
            status = "FAILED";
            currentTable = null;
            completedAt = Instant.now();
            error = message;
        }

        private boolean isTerminal() {
            return status.equals("SUCCEEDED") || status.equals("FAILED");
        }

        private EncryptionJobView view() {
            return new EncryptionJobView(
                    id,
                    status,
                    database,
                    tables.stream().map(table -> table.identity.qualifiedName()).toList(),
                    currentTable,
                    tables.stream().mapToLong(TableSpec::rowsToProcess).sum(),
                    processedRows,
                    tables.stream().collect(Collectors.toUnmodifiableMap(
                            table -> table.identity.qualifiedName(),
                            TableSpec::rowsToProcess
                    )),
                    Map.copyOf(processedRowsByTable),
                    encryptedValues,
                    Map.copyOf(encryptedValuesByTable),
                    startedAt,
                    completedAt,
                    error
            );
        }
    }
}
