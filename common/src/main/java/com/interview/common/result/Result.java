package com.interview.common.result;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 【面试考点】统一 API 返回结构设计
 *
 * 问题描述：
 *   前后端分离架构中，后端接口返回格式不统一，前端需要针对每个接口单独处理，
 *   维护成本极高，且出错时难以定位问题。
 *
 * 解决思路：
 *   定义统一的 Result<T> 泛型包装类，所有接口统一返回此结构。
 *   配合全局异常处理器（@RestControllerAdvice），实现：
 *   1. 正常响应：code=200, data=业务数据
 *   2. 业务异常：code=业务错误码, message=错误描述
 *   3. 系统异常：code=500, message=系统错误
 *
 * 【对比方案】
 * ❌ 方案一（错误示范）：直接返回业务对象
 *    @GetMapping("/user/{id}")
 *    public User getUser(@PathVariable Long id) { ... }
 *    → 问题：成功和失败无法统一处理，HTTP 状态码语义混乱
 *
 * ✅ 方案二（正确）：统一包装返回
 *    @GetMapping("/user/{id}")
 *    public Result<User> getUser(@PathVariable Long id) { ... }
 *    → 优点：前端统一判断 code，业务逻辑清晰
 *
 * 【面试追问】
 * Q: code 字段为什么不直接用 HTTP 状态码？
 * A: HTTP 状态码是传输层协议，业务错误码是应用层概念。
 *    业务错误码可以更细粒度（如 1001=用户不存在, 1002=密码错误），
 *    而 HTTP 状态码只有有限的几个（400/401/403/404/500）。
 *    实践中通常 HTTP 状态码统一返回 200，业务结果通过 code 区分。
 *
 * Q: 为什么实现 Serializable？
 * A: Result 对象可能需要序列化（如存入 Redis 缓存、通过 RPC 传输），
 *    实现 Serializable 是良好的编程习惯。
 *
 * Q: 为什么用静态工厂方法而不是构造方法？
 * A: 静态工厂方法语义更清晰（success/fail），且可以控制对象创建逻辑，
 *    符合 Effective Java 第 1 条建议。
 *
 * @param <T> 业务数据类型
 * @author interview-prep
 * @since 2024-01-01
 */
@Data
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ========== 预定义状态码常量 ==========
    // 【面试考点】为什么定义为常量而不是枚举？
    // 答：Result 类本身只负责"包装"，状态码语义由调用方决定。
    //     枚举适合有限且固定的值集合，业务错误码可能随业务扩展，
    //     因此在 BizException 中用枚举，这里只定义通用常量。

    /** 成功状态码 */
    public static final int SUCCESS_CODE = 200;

    /** 通用失败状态码 */
    public static final int FAIL_CODE = 500;

    // ========== 字段定义 ==========

    /**
     * 业务状态码
     * 200 = 成功
     * 400 = 请求参数错误
     * 401 = 未认证
     * 403 = 无权限
     * 404 = 资源不存在
     * 500 = 系统内部错误
     * 1000+ = 自定义业务错误码
     */
    private int code;

    /**
     * 响应消息
     * 成功时通常为 "success" 或 null
     * 失败时为具体的错误描述，便于前端展示或日志排查
     */
    private String message;

    /**
     * 业务数据
     * 成功时携带实际数据，失败时为 null
     * 使用泛型 T 避免强制类型转换
     */
    private T data;

    // ========== 私有构造方法 ==========
    // 强制使用静态工厂方法创建实例，保证创建方式统一

    private Result() {}

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ========== 静态工厂方法 ==========

    /**
     * 【面试考点】成功响应（无数据）
     *
     * 使用场景：操作成功但不需要返回数据，如删除、更新操作
     * 示例：
     *   return Result.success();
     *   → {"code": 200, "message": "success", "data": null}
     *
     * @return 成功的 Result 对象
     */
    public static <T> Result<T> success() {
        return new Result<>(SUCCESS_CODE, "success", null);
    }

    /**
     * 【面试考点】成功响应（携带数据）
     *
     * 使用场景：查询操作，需要返回业务数据
     * 示例：
     *   return Result.success(userVO);
     *   → {"code": 200, "message": "success", "data": {...}}
     *
     * @param data 业务数据
     * @return 成功的 Result 对象
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, "success", data);
    }

    /**
     * 【面试考点】成功响应（携带数据和自定义消息）
     *
     * 使用场景：需要同时返回数据和提示信息
     * 示例：
     *   return Result.success(order, "订单创建成功，预计3天内发货");
     *
     * @param data    业务数据
     * @param message 提示消息
     * @return 成功的 Result 对象
     */
    public static <T> Result<T> success(T data, String message) {
        return new Result<>(SUCCESS_CODE, message, data);
    }

    /**
     * 【面试考点】失败响应（仅消息）
     *
     * 使用场景：通用错误，使用默认错误码 500
     * 示例：
     *   return Result.fail("操作失败，请稍后重试");
     *   → {"code": 500, "message": "操作失败，请稍后重试", "data": null}
     *
     * @param message 错误消息
     * @return 失败的 Result 对象
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(FAIL_CODE, message, null);
    }

    /**
     * 【面试考点】失败响应（自定义错误码 + 消息）
     *
     * 使用场景：需要精确的业务错误码，前端根据 code 做不同处理
     * 示例：
     *   return Result.fail(401, "登录已过期，请重新登录");
     *   → {"code": 401, "message": "登录已过期，请重新登录", "data": null}
     *
     * 【对比方案】
     * ❌ 直接抛 HTTP 异常：throw new ResponseStatusException(HttpStatus.UNAUTHORIZED)
     *    → 问题：HTTP 状态码语义与业务错误码混用，前端处理复杂
     * ✅ 统一用 Result.fail(code, message)
     *    → 前端只需判断 code 字段，HTTP 状态码始终为 200
     *
     * @param code    业务错误码
     * @param message 错误消息
     * @return 失败的 Result 对象
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    // ========== 便捷判断方法 ==========

    /**
     * 判断是否成功
     *
     * 使用场景：在 Service 层调用其他微服务接口后，判断结果是否成功
     * 示例：
     *   Result<OrderVO> result = orderService.createOrder(req);
     *   if (result.isSuccess()) { ... }
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return this.code == SUCCESS_CODE;
    }

    /**
     * 判断是否失败
     *
     * @return true 表示失败
     */
    public boolean isFail() {
        return !isSuccess();
    }

    /**
     * 获取数据，失败时抛出异常
     *
     * 【面试考点】链式调用的安全处理
     * 使用场景：调用方确信结果成功，直接获取数据，否则抛出异常
     *
     * @return 业务数据
     * @throws IllegalStateException 当结果为失败时
     */
    public T getDataOrThrow() {
        if (isFail()) {
            throw new IllegalStateException("Result is failed: [" + code + "] " + message);
        }
        return this.data;
    }

    /**
     * toString 方法，便于日志输出
     * 覆盖 Lombok @Data 生成的 toString，格式更简洁
     */
    @Override
    public String toString() {
        return "Result{code=" + code + ", message='" + message + "', data=" + data + "}";
    }
}
