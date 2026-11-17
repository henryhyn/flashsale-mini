import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 缓存一致性实验：每一轮让几个读线程不停地读商户 1 (GET /shop/1，正式接口的读法)，同时改一次它的人均价格，
 * 改完再读一会儿，然后核对缓存和数据库是否一致。统计最后缓存里留下旧值的轮数。
 * 用法：java tools/Consistency.java <更新写法> <轮数> <读线程数>
 *   更新写法：afterCommit (提交后删缓存)、deleteFirst (先删缓存再写库)、deleteInTx (写库后、提交前删缓存)
 * 需要先启动服务 (默认 http://localhost:8080，可以用环境变量 BASE 修改)。
 */
public class Consistency {
    static final String BASE = System.getenv().getOrDefault("BASE", "http://localhost:8080");
    static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    public static void main(String[] args) throws Exception {
        String strategy = args[0];
        int rounds = Integer.parseInt(args[1]), readers = Integer.parseInt(args[2]);
        int stale = 0;
        for (int r = 1; r <= rounds; r++) {
            AtomicBoolean stop = new AtomicBoolean();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < readers; i++) {
                    pool.submit(() -> {
                        while (!stop.get()) call("GET", "/shop/1");
                        return null;
                    });
                }
                Thread.sleep(5);
                call("POST", "/lab/shop/1/price?strategy=" + strategy + "&avgPrice=" + (10000 + r));
                Thread.sleep(20);   // 改完以后再读一会儿：旧值如果被写回缓存，会一直留着
                stop.set(true);
            }
            if (call("GET", "/lab/shop/1/check").contains("\"stale\":true")) stale++;
        }
        System.out.printf("%s：%d 轮里有 %d 轮最后缓存里是旧值%n", strategy, rounds, stale);
    }

    static String call(String method, String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + path)).method(method, HttpRequest.BodyPublishers.noBody()).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
}
