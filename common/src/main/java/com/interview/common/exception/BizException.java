package com.interview.common.exception;

/**
 * 【面试考点】业务异常设计
 *
 * 问题描述：
 *   业务逻辑中存在大量"预期内"的错误（如用户不存在、余额不足），
 *   需要与"预期外"的系统错误（如数据库连接失败、NPE）区分处理。
 *
 * 解决思路：
 *   自定义 BizException 继承 RuntimeException，携带错误码枚举，
 *   配合全局异常处理器（@RestControllerAdvice）统一捕获并转换为 Result。
 *
 * ========== 方案对比 ==========
 * ❌ 方案一（错误示范）：用返回值表示错误
 *    public User getUser(Long id) {
 *        if (user == null) return null;  // 调用方可能忘记判空
 *    }
 *    → 问题：调用方容易忽略错误处理，导致 NPE
 *
 * ❌ 方案二（错误示范）：用受检异常（Checked Exception）
 *    public User getUser(Long id) throws UserNotFoundException { ... }
 *    → 问题：调用链上每一层都要 try-catch 或 throws，代码冗余
 *
 * ✅ 方案三（正确）：用非受检异常（RuntimeException）
 *    public User getUser(Long id) {
 *        throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
 *    }
 *    → 优点：异常自动向上传播，由全局异常处理器统一处理
 * ==============================
 *
 * 【面试追问】
 * Q: 受检异常（Checked Exception）和非受检异常（Unchecked Exception）的区别？
 * A: 受检异常（继承 Exception）：编译器强制要求处理（try-catch 或 throws）
 *    非受检异常（继承 RuntimeException）：编译器不强制要求处理
 *    业务异常通常用非受检异常，因为：
 *    1. 业务异常往往无法在当前层处理，需要传播到最外层
 *    2. 受检异常会污染调用链，每层都要声明 throws
 *    3. Spring 的事务回滚默认只对 RuntimeException 生效
 *
 * Q: 为什么 Spring 事务默认只回滚 RuntimeException？
 * A: 这是 Spring 遵循 EJB 规范的设计。受检异常被认为是"可预期的业务异常"，
 *    不一定需要回滚；RuntimeException 被认为是"不可预期的错误"，应该回滚。
 *    可通过 @Transactional(rollbackFor = Exception.class) 改变此行为。
 *
 * Q: 错误码为什么用枚举而不是常量？
 * A: 枚举的优势：
 *    1. 类型安全：不会传入非法的错误码值
 *    2. 自描述：枚举名称即含义（NOT_FOUND 比 404 更清晰）
 *    3. 可扩展：枚举可以添加方法，如 getHttpStatus()
 *    4. 防止魔法数字：避免代码中散落的 400、500 等数字
 *
 * @author interview-prep
 * @since 2024-01-01
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     * 使用枚举保证类型安全，避免传入非法错误码
     */
    private final ErrorCode errorCode;

    // ========== 构造方法 ==========

    /**
     * 使用错误码构造异常（使用枚举默认消息）
     *
     * 使用场景：错误信息固定，不需要动态消息
     * 示例：
     *   throw new BizException(ErrorCode.NOT_FOUND);
     *   → 错误码: 404, 消息: "资源不存在"
     *
     * @param errorCode 错误码枚举
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 使用错误码 + 自定义消息构造异常
     *
     * 使用场景：需要更具体的错误描述
     * 示例：
     *   throw new BizException(ErrorCode.NOT_FOUND, "用户 ID=" + userId + " 不存在");
     *   → 错误码: 404, 消息: "用户 ID=123 不存在"
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息（覆盖枚举默认消息）
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 使用错误码 + 自定义消息 + 原始异常构造
     *
     * 使用场景：包装底层异常，保留异常链，便于排查根因
     * 示例：
     *   try {
     *       // 调用第三方服务
     *   } catch (IOException e) {
     *       throw new BizException(ErrorCode.INTERNAL_ERROR, "调用支付服务失败", e);
     *   }
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     * @param cause     原始异常（保留异常链）
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 仅使用消息构造异常（使用通用业务错误码 BIZ_ERROR）
     *
     * 使用场景：快速抛出业务异常，不关心具体错误码
     * 示例：
     *   throw new BizException("库存不足，无法下单");
     *
     * 【面试追问】什么时候用这个构造方法？
     * A: 当错误码不重要，只需要向用户展示错误消息时使用。
     *    如果前端需要根据错误码做不同处理，应使用带 ErrorCode 的构造方法。
     *
     * @param message 错误消息
     */
    public BizException(String message) {
        super(message);
        this.errorCode = ErrorCode.BIZ_ERROR;
    }

    /**
     * 获取错误码枚举
     *
     * @return 错误码枚举
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 获取错误码数值
     * 便于在全局异常处理器中直接获取 code 值
     *
     * @return 错误码数值
     */
    public int getCode() {
        return errorCode.getCode();
    }

    // ========== 错误码枚举 ==========

    /**
     * 【面试考点】错误码枚举设计
     *
     * 设计原则：
     * 1. 与 HTTP 状态码对齐（200/400/401/403/404/500）：便于理解和映射
     * 2. 自定义业务错误码从 1000 开始：避免与 HTTP 状态码冲突
     * 3. 每个枚举值包含 code 和 message：自描述，减少硬编码
     *
     * 【面试追问】
     * Q: 错误码应该放在哪个模块？
     * A: 放在 common 模块，所有业务模块共享。
     *    如果业务模块有特定错误码，可以在各自模块定义扩展枚举，
     *    但基础错误码统一在 common 中管理。
     *
     * Q: 如何设计多模块的错误码体系？
     * A: 按模块划分错误码段：
     *    1000-1999：用户模块
     *    2000-2999：订单模块
     *    3000-3999：支付模块
     *    这样通过错误码就能快速定位是哪个模块的问题。
     */
    public enum ErrorCode {

        // ==================== 通用成功码 ====================

        /**
         * 成功
         * 通常不用于异常，但在某些场景下（如统一返回结构）需要
         */
        SUCCESS(200, "成功"),

        // ==================== 客户端错误（4xx） ====================

        /**
         * 请求参数错误
         * 使用场景：参数校验失败（如必填字段为空、格式不正确）
         * 对应 HTTP 400 Bad Request
         */
        BAD_REQUEST(400, "请求参数错误"),

        /**
         * 未认证（未登录）
         * 使用场景：访问需要登录的接口但未提供有效 Token
         * 对应 HTTP 401 Unauthorized
         *
         * 【面试追问】401 和 403 的区别？
         * 401 Unauthorized：未认证，不知道你是谁（需要登录）
         * 403 Forbidden：已认证但无权限，知道你是谁但不让你访问
         */
        UNAUTHORIZED(401, "未登录或登录已过期"),

        /**
         * 无权限
         * 使用场景：已登录但没有操作权限（如普通用户访问管理员接口）
         * 对应 HTTP 403 Forbidden
         */
        FORBIDDEN(403, "无权限访问"),

        /**
         * 资源不存在
         * 使用场景：查询的数据不存在（如用户ID不存在、订单不存在）
         * 对应 HTTP 404 Not Found
         */
        NOT_FOUND(404, "资源不存在"),

        // ==================== 服务端错误（5xx） ====================

        /**
         * 系统内部错误
         * 使用场景：未预期的系统异常（如数据库连接失败、第三方服务超时）
         * 对应 HTTP 500 Internal Server Error
         *
         * 【面试追问】系统错误应该向用户暴露详细信息吗？
         * A: 不应该。系统错误的详细信息（如 SQL 语句、堆栈信息）
         *    可能暴露系统内部实现，存在安全风险。
         *    应该：向用户返回通用错误消息，详细信息记录到日志。
         */
        INTERNAL_ERROR(500, "系统内部错误，请稍后重试"),

        // ==================== 自定义业务错误码（1000+） ====================

        /**
         * 通用业务错误
         * 使用场景：不需要精确错误码的业务异常
         * 使用 BizException(String message) 构造方法时默认使用此错误码
         */
        BIZ_ERROR(1000, "业务处理失败"),

        /**
         * 用户相关错误（1001-1099）
         * 示例：用户不存在、密码错误、账号被禁用等
         * 实际项目中可按需扩展
         */
        USER_NOT_FOUND(1001, "用户不存在"),
        USER_PASSWORD_ERROR(1002, "用户名或密码错误"),
        USER_ACCOUNT_DISABLED(1003, "账号已被禁用"),

        /**
         * 订单相关错误（2001-2099）
         * 示例：库存不足、订单状态异常等
         */
        ORDER_STOCK_INSUFFICIENT(2001, "库存不足"),
        ORDER_STATUS_INVALID(2002, "订单状态异常，无法操作"),

        /**
         * 限流相关错误
         * 使用场景：触发限流策略时返回
         */
        TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试");

        // ==================== 枚举字段 ====================

        /** 错误码数值 */
        private final int code;

        /** 错误描述 */
        private final String message;

        // ==================== 枚举构造方法 ====================

        ErrorCode(int code, String message) {
            this.code = code;
            this.message = message;
        }

        // ==================== Getter ====================

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        /**
         * 根据错误码数值查找枚举
         *
         * 使用场景：从 RPC 响应中反序列化错误码时使用
         *
         * @param code 错误码数值
         * @return 对应的枚举，找不到返回 INTERNAL_ERROR
         */
        public static ErrorCode fromCode(int code) {
            for (ErrorCode errorCode : values()) {
                if (errorCode.code == code) {
                    return errorCode;
                }
            }
            return INTERNAL_ERROR;
        }

        @Override
        public String toString() {
            return "ErrorCode{code=" + code + ", message='" + message + "'}";
        }
    }
}
