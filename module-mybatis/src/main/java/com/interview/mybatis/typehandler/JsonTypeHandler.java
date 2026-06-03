package com.interview.mybatis.typehandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 【面试考点】自定义 TypeHandler - JSON 类型处理器
 * 
 * 业务场景：
 * - PostgreSQL 的 JSONB 字段存储复杂的嵌套对象
 * - Java 端使用 Map 或自定义对象接收
 * 
 * 实现原理：
 * - 继承 BaseTypeHandler<T>
 * - 重写 getNullableResult 和 setNonNullParameter 方法
 * - 使用 Jackson 进行序列化/反序列化
 * 
 * 【面试追问】为什么不直接存 JSON 字符串？
 * → 答：JSONB 在 PostgreSQL 中以二进制存储，支持索引，查询效率更高
 */
@Slf4j
public class JsonTypeHandler extends BaseTypeHandler<Map<String, Object>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<HashMap<String, Object>> TYPE_REF = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, 
                                     Map<String, Object> parameter, JdbcType jdbcType) 
            throws SQLException {
        try {
            // 将 Map 序列化为 JSON 字符串存入数据库
            String json = OBJECT_MAPPER.writeValueAsString(parameter);
            ps.setString(i, json);
        } catch (JsonProcessingException e) {
            log.error("序列化 Map 到 JSON 失败: {}", parameter, e);
            throw new TypeException("Error setting non null parameter", e);
        }
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, String columnName) 
            throws SQLException {
        String json = rs.getString(columnName);
        return parseJson(json);
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, int columnIndex) 
            throws SQLException {
        String json = rs.getString(columnIndex);
        return parseJson(json);
    }

    @Override
    public Map<String, Object> getNullableResult(CallableStatement cs, int columnIndex) 
            throws SQLException {
        String json = cs.getString(columnIndex);
        return parseJson(json);
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(json, TYPE_REF);
        } catch (JsonProcessingException e) {
            log.error("解析 JSON 到 Map 失败: {}", json, e);
            return Collections.emptyMap();
        }
    }
}