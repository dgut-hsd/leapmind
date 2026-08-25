package com.treepeople.leapmindtts.service.profile;

import com.treepeople.leapmindtts.exception.M6ApiException;
import com.treepeople.leapmindtts.pojo.dto.profile.M6Dtos.FieldViolation;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * M6 异常工厂类。
 * <p>
 * 集中管理 M6 模块所有异常的创建逻辑，消除 {@code degraded()}、{@code invalid()}、
 * {@code accessDenied()} 等工厂方法在多个 Service 类中的重复定义。
 * </p>
 */
public final class M6Errors {

    private M6Errors() { }

    /** 服务降级异常（503）。 */
    public static M6ApiException degraded() {
        return new M6ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                M6Constants.ErrorCode.PROFILE_SERVICE_DEGRADED.value(),
                "用户画像服务暂不可用");
    }

    /** 事件无效异常（400）。 */
    public static M6ApiException invalid(String message) {
        return new M6ApiException(
                HttpStatus.BAD_REQUEST,
                M6Constants.ErrorCode.PROFILE_EVENT_INVALID.value(),
                message);
    }

    /** 事件无效异常（400），带字段级详情。 */
    public static M6ApiException invalid(String message, List<FieldViolation> details) {
        return new M6ApiException(
                HttpStatus.BAD_REQUEST,
                M6Constants.ErrorCode.PROFILE_EVENT_INVALID.value(),
                message,
                details);
    }

    /** 事件类型不支持异常（400）。 */
    public static M6ApiException unsupportedEventType(String eventType) {
        return new M6ApiException(
                HttpStatus.BAD_REQUEST,
                M6Constants.ErrorCode.PROFILE_EVENT_TYPE_UNSUPPORTED.value(),
                "不支持的事件类型: " + eventType);
    }

    /** Schema 版本不支持异常（400）。 */
    public static M6ApiException unsupportedVersion(String version) {
        return new M6ApiException(
                HttpStatus.BAD_REQUEST,
                M6Constants.ErrorCode.PROFILE_EVENT_VERSION_UNSUPPORTED.value(),
                "不支持的 Schema 版本: " + version);
    }

    /** 幂等冲突异常（409）。 */
    public static M6ApiException conflict() {
        return new M6ApiException(
                HttpStatus.CONFLICT,
                M6Constants.ErrorCode.PROFILE_IDEMPOTENCY_CONFLICT.value(),
                "相同 eventId 的事件已存在但内容不同");
    }

    /** 未认证异常（401）。 */
    public static M6ApiException unauthenticated() {
        return new M6ApiException(
                HttpStatus.UNAUTHORIZED,
                M6Constants.ErrorCode.PROFILE_UNAUTHENTICATED.value(),
                "需要认证才能访问用户画像");
    }

    /** 越权访问异常（403）。 */
    public static M6ApiException denied() {
        return new M6ApiException(
                HttpStatus.FORBIDDEN,
                M6Constants.ErrorCode.PROFILE_ACCESS_DENIED.value(),
                "无权访问该用户的画像");
    }

    /** 画像未就绪异常（可返回 NOT_READY 视图时使用）。 */
    public static M6ApiException notReady(String reason) {
        return new M6ApiException(
                HttpStatus.OK,
                M6Constants.ErrorCode.PROFILE_NOT_READY.value(),
                "用户画像尚未就绪" + (reason != null ? ": " + reason : ""));
    }

    /** 内部错误异常（500）。 */
    public static M6ApiException internal() {
        return new M6ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                M6Constants.ErrorCode.PROFILE_INTERNAL_ERROR.value(),
                "用户画像服务内部错误");
    }
}
