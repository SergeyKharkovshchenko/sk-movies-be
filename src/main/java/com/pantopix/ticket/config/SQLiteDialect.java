//package com.example.config;
//
//import org.hibernate.dialect.DatabaseVersion;
//import org.hibernate.dialect.Dialect;
//import org.hibernate.dialect.identity.IdentityColumnSupport;
//import org.hibernate.dialect.identity.IdentityColumnSupportImpl;
//import org.hibernate.engine.jdbc.env.spi.NameQualifierSupport;
//import org.hibernate.type.SqlTypes;
//
//public class SQLiteDialect extends Dialect {
//
//    public SQLiteDialect() {
//        super(DatabaseVersion.make(3, 32)); // You can put your SQLite version here
//        registerColumnTypes();
//    }
//
//    protected void registerColumnTypes() {
//        registerColumnType(SqlTypes.BOOLEAN, "boolean");
//        registerColumnType(SqlTypes.INTEGER, "integer");
//        registerColumnType(SqlTypes.BIGINT, "bigint");
//        registerColumnType(SqlTypes.DOUBLE, "double");
//        registerColumnType(SqlTypes.VARCHAR, "text");
//        registerColumnType(SqlTypes.DATE, "date");
//        registerColumnType(SqlTypes.TIMESTAMP, "datetime");
//        registerColumnType(SqlTypes.BLOB, "blob");
//        registerColumnType(SqlTypes.CLOB, "text");
//    }
//
//    @Override
//    public IdentityColumnSupport getIdentityColumnSupport() {
//        return new IdentityColumnSupportImpl();
//    }
//
//    @Override
//    public boolean supportsIdentityColumns() {
//        return true;
//    }
//
//    @Override
//    public boolean hasAlterTable() {
//        return false;
//    }
//
//    @Override
//    public String getDropForeignKeyString() {
//        return ""; // SQLite does not support drop foreign key
//    }
//
//    @Override
//    public String getAddColumnString() {
//        return "add column";
//    }
//
//    @Override
//    public boolean supportsIfExistsBeforeTableName() {
//        return true;
//    }
//
//    @Override
//    public boolean supportsCascadeDelete() {
//        return false;
//    }
//
//    @Override
//    public NameQualifierSupport getNameQualifierSupport() {
//        return NameQualifierSupport.NONE;
//    }
//}
