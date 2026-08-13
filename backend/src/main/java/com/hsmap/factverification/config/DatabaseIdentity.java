package com.hsmap.factverification.config;

/** PostgreSQL 当前会话返回的 database/schema 身份。 */
public record DatabaseIdentity(String databaseName, String schemaName) {}
