package flashsale.common;

/** 当前请求的登录用户。拦截器在请求开始时放进去，请求结束时一定要清掉 (线程会被复用) */
public final class UserHolder {
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();

    private UserHolder() { }

    public static void set(Long userId) { USER.set(userId); }

    public static Long get() {
        Long id = USER.get();
        if (id == null) throw new BizException(401, "请先登录");
        return id;
    }

    public static void remove() { USER.remove(); }
}
