package flashsale.common;

/** 业务异常：可以预期的失败 (库存不足、重复下单)，返回给用户，不打错误日志 */
public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int code() { return code; }
}
