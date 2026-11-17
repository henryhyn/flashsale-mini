package flashsale.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> biz(BizException e) {
        return Result.fail(e.code(), e.getMessage());
    }

    /** 没预料到的异常：记日志，给用户一个笼统的提示，不把内部信息暴露出去 */
    @ExceptionHandler(Exception.class)
    public Result<Void> other(Exception e) {
        log.error("未处理的异常", e);
        return Result.fail(500, "系统繁忙，请稍后再试");
    }
}
