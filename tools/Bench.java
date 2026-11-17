import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * 压测客户端：先让 N 个用户登录拿到 token，再让每个用户同时发 K 个请求，统计吞吐、延迟和结果分布。
 * 用法：java tools/Bench.java <接口路径> <用户数> <每个用户的请求数>
 *   例：java tools/Bench.java /seckill/1 500 2
 * 需要先启动服务 (默认 http://localhost:8080，可以用环境变量 BASE 修改)。
 */
public class Bench {
    static final String BASE = System.getenv().getOrDefault("BASE", "http://localhost:8080");
    static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    public static void main(String[] args) throws Exception {
        String path = args[0];
        int users = Integer.parseInt(args[1]), perUser = Integer.parseInt(args[2]);

        List<String> tokens = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 1; i <= users; i++) {
                String phone = "138" + String.format("%08d", i);
                fs.add(pool.submit(() -> body(post("/user/login?phone=" + phone, null)).replaceAll(".*\"data\":\"([^\"]+)\".*", "$1")));
            }
            for (Future<String> f : fs) tokens.add(f.get());
        }

        int total = users * perUser;
        long[] latency = new long[total];
        String[] results = new String[total];
        CountDownLatch start = new CountDownLatch(1);
        long t0;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                int idx = i;
                String token = tokens.get(i / perUser);   // 同一个用户的几个请求挨着发，模拟连点
                pool.submit(() -> {
                    start.await();
                    long t = System.nanoTime();
                    String r = body(post(path, token));
                    latency[idx] = System.nanoTime() - t;
                    results[idx] = r.contains("\"code\":0") ? "成功" : r.replaceAll(".*\"msg\":\"([^\"]+)\".*", "$1");
                    return null;
                });
            }
            t0 = System.nanoTime();
            start.countDown();
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        Arrays.sort(latency);
        Map<String, Long> dist = Arrays.stream(results).collect(Collectors.groupingBy(r -> r, TreeMap::new, Collectors.counting()));
        System.out.printf("请求 %d 个 (%d 个用户，每人 %d 个)，耗时 %.2f 秒，吞吐 %.0f 次/秒，P50 %.1f ms，P99 %.1f ms%n",
                total, users, perUser, seconds, total / seconds,
                latency[total / 2] / 1e6, latency[(int) (total * 0.99) - 1] / 1e6);
        System.out.println("结果分布：" + dist);
    }

    static HttpResponse<String> post(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) b.header("Authorization", token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String body(HttpResponse<String> r) { return r.body(); }
}
