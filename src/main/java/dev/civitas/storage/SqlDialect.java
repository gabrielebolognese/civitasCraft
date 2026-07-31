package dev.civitas.storage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The differences between the two supported backends, in one place.
 *
 * <h2>Money</h2>
 * SPEC 3 specifies {@code DECIMAL(20,2)} for every monetary column. MySQL has that type.
 * SQLite does not: a column declared {@code DECIMAL} takes NUMERIC affinity and stores any
 * non-integral value as an 8-byte float, so {@code 0.10} comes back as {@code 0.1000000000000000055…}
 * and cents drift. Balances that drift are unauditable, and SPEC 1.5 makes auditability a
 * core requirement.
 *
 * <p>So on SQLite every monetary column is an {@code INTEGER} holding <em>minor units</em>
 * (hundredths), and this class converts. {@code SUM} and {@code ORDER BY} still behave
 * correctly in SQL because minor units are ordinary integers. Callers only ever see
 * {@link BigDecimal} and never need to know which backend is in use, but any SQL written
 * outside a DAO must bind and read money through {@link #setMoney} and {@link #getMoney}.
 */
public enum SqlDialect {

    SQLITE("migrations/sqlite", "org.sqlite.JDBC"),
    MYSQL("migrations/mysql", "com.mysql.cj.jdbc.Driver");

    /**
     * Decimal places on every monetary column. Fixed by SPEC 3's {@code DECIMAL(20,2)};
     * changing it is a schema change and needs a migration, so it is not a config key.
     */
    public static final int MONEY_SCALE = 2;

    private static final BigDecimal MINOR_UNITS_PER_UNIT = BigDecimal.TEN.pow(MONEY_SCALE);

    private final String migrationFolder;
    private final String driverClassName;

    SqlDialect(String migrationFolder, String driverClassName) {
        this.migrationFolder = migrationFolder;
        this.driverClassName = driverClassName;
    }

    /** Classpath folder holding this backend's migration scripts. */
    public String migrationFolder() {
        return migrationFolder;
    }

    public String driverClassName() {
        return driverClassName;
    }

    /**
     * @param name a value of {@code storage.type}, case-insensitive
     * @throws StorageException if the value names no supported backend
     */
    public static SqlDialect parse(String name) {
        if (name != null) {
            for (SqlDialect dialect : values()) {
                if (dialect.name().equalsIgnoreCase(name.trim())) {
                    return dialect;
                }
            }
        }
        throw new StorageException("Unsupported storage.type '" + name
                + "'. Supported values: SQLITE, MYSQL.");
    }

    /** Binds a monetary value, rounding half-up to {@link #MONEY_SCALE} places. */
    public void setMoney(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        BigDecimal scaled = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (this == SQLITE) {
            statement.setLong(index, toMinorUnits(scaled));
        } else {
            statement.setBigDecimal(index, scaled);
        }
    }

    /** Reads a monetary column. Never returns {@code null}; a SQL NULL reads as zero. */
    public BigDecimal getMoney(ResultSet resultSet, String column) throws SQLException {
        if (this == SQLITE) {
            long minorUnits = resultSet.getLong(column);
            return resultSet.wasNull() ? zero() : fromMinorUnits(minorUnits);
        }
        BigDecimal value = resultSet.getBigDecimal(column);
        return value == null ? zero() : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** A zero at the schema's scale, so equality against a read-back value holds. */
    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    /** Normalises a value to the schema's scale, rounding half-up. */
    public static BigDecimal normalise(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    static long toMinorUnits(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                .multiply(MINOR_UNITS_PER_UNIT)
                .longValueExact();
    }

    static BigDecimal fromMinorUnits(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, MONEY_SCALE);
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
