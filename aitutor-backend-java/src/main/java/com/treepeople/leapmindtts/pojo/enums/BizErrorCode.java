package com.treepeople.leapmindtts.pojo.enums;

import lombok.Getter;

@Getter
public enum BizErrorCode {
    SUCCESS(200, "成功"),
    RATE_LIMITED(429, "提问过于频繁，请稍后再试"),
    UNAUTHORIZED(401, "未授权的访问"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源未找到"),
    INTERNAL_SERVER_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    BizErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
