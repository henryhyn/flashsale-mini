package flashsale.common;

/** 统一返回体：code 为 0 表示成功，其他值表示失败，msg 是给用户看的提示 */
public record Result<T>(int code, String msg, T data) {
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }
}
