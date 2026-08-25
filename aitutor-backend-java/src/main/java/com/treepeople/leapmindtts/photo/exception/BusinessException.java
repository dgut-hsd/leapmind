package com.treepeople.leapmindtts.photo.exception;

import lombok.Data;

/**
 * 业务异常类
 * 用于抛出业务逻辑相关的异常
 */
@Data
public class BusinessException extends RuntimeException {
    /** 错误码 */
    private Integer code;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
