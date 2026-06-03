package com.interview.microservice.circuit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【面试考点】熔断器模式实现 - 手写简化版 Sentinel
 * 
 * 核心概念：
 * - 熔断器三个状态：CLOSED（正常）、OPEN（熔断）、HALF_OPEN（半开）
 * - 失败率超过阈值 → 触发熔断
 * - 熔断一段时间后 → 放行一个请求试试（半开）
 * - 成功 → 恢复正常，失败 → 继续熔断
 * 
 * 【面试追问】熔断和降级的区别？
 * → 熔断：保护整个系统，防止故障蔓延
 * → 降级：返回兜底数据，保证服务可用
 */
@Slf4j
@Component
public class SentinelDemo {

    // ========== 熔断器状态枚举 ==========
    public enum CircuitState {
        CLOSED,   // 关闭状态：正常请求
        OPEN,     // 打开状态：直接拒绝请求
        HALF_OPEN // 半开状态：放行一个请求试试
    }

    // ========== 配置参数 ==========
    private static final int FAILURE_THRESHOLD = 5;      // 失败次数阈值
    private static final double FAILURE_RATE_THRESHOLD = 0.5; // 失败率阈值（50%）
    private static final long CIRCUIT_RESET_TIMEOUT = 60_000;  // 熔断恢复时间（60秒）

    // ========== 状态变量 ==========
    private volatile CircuitState state = CircuitState.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong circuitOpenedTime = new AtomicLong(0);

    // ========== 统计窗口（滑动窗口实现） ==========
    // 用一个简单的滑动窗口记录最近N次请求的结果
    private static final int WINDOW_SIZE = 10;
    private final AtomicInteger[] window = new AtomicInteger[WINDOW_SIZE];
    private volatile int currentIndex = 0;
    
    public SentinelDemo() {
        for (int i = 0; i < WINDOW_SIZE; i++) {
            window[i] = new AtomicInteger(0); // 0=成功, 1=失败
        }
    }

    /**
     * 【面试考点】熔断器核心方法 - 判断是否允许请求
     * 
     * 流程：
     * 1. 检查熔断状态
     * 2. CLOSED → 检查失败率
     * 3. OPEN → 检查是否超时可以进入半开
     * 4. HALF_OPEN → 放行一个请求
     */
    public boolean allowRequest() {
        switch (state) {
            case CLOSED:
                return true;
                
            case OPEN:
                // 检查是否超过熔断恢复时间
                if (System.currentTimeMillis() - circuitOpenedTime.get() > CIRCUIT_RESET_TIMEOUT) {
                    // 进入半开状态，放行一个请求试试
                    state = CircuitState.HALF_OPEN;
                    log.info("【熔断器】进入HALF_OPEN状态，尝试恢复");
                    return true;
                }
                return false;
                
            case HALF_OPEN:
                // 半开状态只允许一个请求
                log.info("【熔断器】HALF_OPEN状态，放行一个请求测试");
                return true;
                
            default:
                return true;
        }
    }

    /**
     * 【面试考点】记录请求结果
     */
    public void recordSuccess() {
        successCount.incrementAndGet();
        
        // 滑动窗口记录
        window[currentIndex].set(0);
        
        switch (state) {
            case CLOSED:
                // 成功后重置失败计数
                failureCount.set(0);
                break;
                
            case HALF_OPEN:
                // 半开状态下成功，恢复正常
                state = CircuitState.CLOSED;
                failureCount.set(0);
                log.info("【熔断器】HALF_OPEN成功，恢复CLOSED状态");
                break;
        }
        
        totalCount.incrementAndGet();
        rotateWindow();
    }

    /**
     * 【面试考点】记录请求失败
     */
    public void recordFailure() {
        failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        // 滑动窗口记录
        window[currentIndex].set(1);
        
        switch (state) {
            case CLOSED:
                // 检查是否达到熔断条件
                if (shouldTrip()) {
                    tripCircuit();
                }
                break;
                
            case HALF_OPEN:
                // 半开状态下失败，重新熔断
                state = CircuitState.OPEN;
                circuitOpenedTime.set(System.currentTimeMillis());
                log.info("【熔断器】HALF_OPEN失败，重新进入OPEN状态");
                break;
        }
        
        totalCount.incrementAndGet();
        rotateWindow();
    }

    /**
     * 【面试考点】判断是否应该熔断
     */
    private boolean shouldTrip() {
        // 方式1：连续失败次数超过阈值
        if (failureCount.get() >= FAILURE_THRESHOLD) {
            return true;
        }
        
        // 方式2：滑动窗口内失败率超过阈值
        int failures = 0;
        for (int i = 0; i < WINDOW_SIZE; i++) {
            if (window[i].get() == 1) {
                failures++;
            }
        }
        double failureRate = (double) failures / WINDOW_SIZE;
        return failureRate >= FAILURE_RATE_THRESHOLD;
    }

    /**
     * 【面试考点】触发熔断
     */
    private void tripCircuit() {
        state = CircuitState.OPEN;
        circuitOpenedTime.set(System.currentTimeMillis());
        log.warn("【熔断器】触发熔断！失败次数: {}, 状态: OPEN", failureCount.get());
    }

    /**
     * 滑动窗口轮转
     */
    private void rotateWindow() {
        currentIndex = (currentIndex + 1) % WINDOW_SIZE;
    }

    /**
     * 获取熔断器状态（用于监控）
     */
    public CircuitState getState() {
        return state;
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        return Map.of(
            "state", state,
            "failureCount", failureCount.get(),
            "successCount", successCount.get(),
            "totalCount", totalCount.get(),
            "lastFailureTime", lastFailureTime.get()
        );
    }

    // ========== 模拟调用示例 ==========

    /**
     * 使用熔断器调用远程服务
     */
    public String callRemoteService(String serviceName, Callable<String> remoteCall) {
        if (!allowRequest()) {
            log.warn("【熔断器】请求被拒绝，服务: {}", serviceName);
            return "服务暂时不可用，请稍后重试";
        }

        try {
            String result = remoteCall.call();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            return "服务调用失败: " + e.getMessage();
        }
    }

    @FunctionalInterface
    public interface Callable<T> {
        T call() throws Exception;
    }
}