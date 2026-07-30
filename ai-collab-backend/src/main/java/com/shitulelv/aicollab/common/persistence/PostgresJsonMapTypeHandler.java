package com.shitulelv.aicollab.common.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public final class PostgresJsonMapTypeHandler extends BaseTypeHandler<Map<String, Object>> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            Map<String, Object> parameter,
            JdbcType jdbcType) throws SQLException {
        try {
            statement.setObject(index, OBJECT_MAPPER.writeValueAsString(parameter), Types.OTHER);
        } catch (JsonProcessingException exception) {
            throw new SQLException("无法序列化 PostgreSQL JSON 字段", exception);
        }
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet resultSet, String columnName)
            throws SQLException {
        return parse(resultSet.getString(columnName));
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet resultSet, int columnIndex)
            throws SQLException {
        return parse(resultSet.getString(columnIndex));
    }

    @Override
    public Map<String, Object> getNullableResult(CallableStatement statement, int columnIndex)
            throws SQLException {
        return parse(statement.getString(columnIndex));
    }

    private Map<String, Object> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new SQLException("无法解析 PostgreSQL JSON 字段", exception);
        }
    }
}
