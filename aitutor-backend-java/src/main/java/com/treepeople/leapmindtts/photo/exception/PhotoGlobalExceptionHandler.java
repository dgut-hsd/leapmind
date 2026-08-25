package com.treepeople.leapmindtts.photo.exception;

import com.treepeople.leapmindtts.photo.dto.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * M2 拍照搜题模块全局异常处理器
 * 仅处理 photo 包下 Controller 抛出的异常，避免与其他模块的异常处理器冲突。
 * 使用最高优先级，避免 photo 包的 BusinessException 被全局 RuntimeException 兜底抢先捕获。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.treepeople.leapmindtts.photo")
public class PhotoGlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理其他所有异常
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        e.printStackTrace();
        return Result.fail(500, "服务器异常：" + e.getMessage());
    }
}
