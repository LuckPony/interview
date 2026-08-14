package interview.homegrown.common.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.config.AiConfigProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实调用 DeepSeek，验证关闭思考后 LlmRawClient 是否正常：
 * complete 不截断、返回完整 JSON、够快；stream 能流式完成、不报错。
 * 需要 .env 里配置了 API_KEY（从项目根目录读取）。
 */
class LlmRawClientIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static LlmRawClient client;

    @BeforeAll
    static void setup() {
        Map<String, String> env = readDotEnv();
        AiConfigProperties config = new AiConfigProperties();
        config.setDefaultProvider("deepseek");
        AiConfigProperties.ProviderConfig p = new AiConfigProperties.ProviderConfig();
        p.setBaseUrl("https://api.deepseek.com");
        p.setApiKey(env.getOrDefault("API_KEY", ""));
        p.setModel(env.getOrDefault("MODEL_NAME", "deepseek-v4-flash"));
        p.setTemperature(0.7);
        config.getProviders().put("deepseek", p);
        assertTrue(p.isAvailable(), "API_KEY 未配置：请在 .env 里填 API_KEY");
        client = new LlmRawClient(config);
    }

    @Test
    void complete_returnsCompleteJson_fast() throws Exception {
        String system = "你是测试助手，只输出 JSON，不要输出其它文字。";
        String user = "输出一个 JSON 对象：{\"title\": \"任意标题\", \"items\": [恰好 30 个字符串元素]}，"
                + "字段名用双引号，items 数组里正好 30 项。";
        long start = System.currentTimeMillis();
        String resp = client.complete(system, user);
        long cost = System.currentTimeMillis() - start;

        assertNotNull(resp, "complete 返回 null（请求失败）");
        assertFalse(resp.isBlank(), "complete 返回空");
        JsonNode node = MAPPER.readTree(resp);
        int n = node.path("items").size();
        assertEquals(30, n,
                "响应疑似被截断或非完整 JSON（items=" + n + "/30，耗时 " + cost + "ms）。原始:\n" + resp);
        System.out.println("[complete] 耗时 " + cost + "ms，items=" + n + "/30，完整 JSON ✓");
    }

    @Test
    void stream_completes_noError() {
        StringBuilder buf = new StringBuilder();
        AtomicReference<String> err = new AtomicReference<>();
        client.stream(
                "你是测试助手。",
                "用两三句话解释什么是数据库索引。",
                buf::append,
                t -> err.set(t.getMessage()),
                /* fallbackToReasoning */ false,
                /* onReasoning */ null);
        assertNull(err.get(), "stream 报错: " + err.get());
        assertFalse(buf.toString().isBlank(), "stream 无输出");
        assertTrue(buf.length() > 20, "stream 输出疑似被截断: " + buf);
        System.out.println("[stream] 输出 " + buf.length() + " 字符，无报错 ✓");
    }

    @Test
    void stream_withThinking_streamsReasoningAndCompleteAnswer() {
        StringBuilder reasoning = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        AtomicReference<String> err = new AtomicReference<>();
        long start = System.currentTimeMillis();
        client.stream(
                "你是资深技术老师。",
                "用两三句话解释什么是数据库索引，并给出一个记忆口诀。",
                answer::append,
                t -> err.set(t.getMessage()),
                /* fallbackToReasoning */ false,
                reasoning::append);
        long cost = System.currentTimeMillis() - start;

        assertNull(err.get(), "stream 报错: " + err.get());
        assertTrue(reasoning.length() > 0, "没有收到 reasoning_content（思考未开启？）");
        assertFalse(answer.toString().isBlank(), "没有收到正文");
        assertTrue(answer.length() > 20, "正文疑似被截断: " + answer);
        System.out.println("[stream+思考] 耗时 " + cost + "ms，思考 " + reasoning.length()
                + " 字，正文 " + answer.length() + " 字 ✓");
    }

    @Test
    void stream_withThinking_reasoningIsIncremental_andAnswerComplete() {
        List<Integer> reasoningChunks = new ArrayList<>();
        StringBuilder reasoning = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        AtomicReference<String> err = new AtomicReference<>();
        long start = System.currentTimeMillis();
        client.stream(
                "你是资深技术老师。",
                "详细解释什么是数据库索引、为什么能加速查询，并给出一个记忆口诀。",
                answer::append,
                t -> err.set(t.getMessage()),
                /* fallbackToReasoning */ false,
                r -> {
                    reasoningChunks.add(r.length());
                    reasoning.append(r);
                });
        long cost = System.currentTimeMillis() - start;

        assertNull(err.get(), "stream 报错: " + err.get());
        assertTrue(reasoning.length() > 0, "没有思考内容");
        assertTrue(reasoningChunks.size() > 1,
                "思考不是逐段流式（只回调 " + reasoningChunks.size() + " 次，可能是整块一次性返回）");
        assertTrue(answer.length() > 30, "回答疑似被截断: " + answer);
        System.out.println("[stream+思考] 耗时 " + cost + "ms，思考分 " + reasoningChunks.size()
                + " 段、共 " + reasoning.length() + " 字，正文 " + answer.length() + " 字 ✓");
    }

    /** 从 user.dir 向上逐级找 .env（支持 KEY = 'value' 或 KEY=value，去引号） */
    private static Map<String, String> readDotEnv() {
        Map<String, String> out = new HashMap<>();
        Path dir = Path.of(System.getProperty("user.dir"));
        Path env = null;
        for (int i = 0; i < 4 && dir != null; i++) {
            Path cand = dir.resolve(".env");
            if (Files.exists(cand)) { env = cand; break; }
            dir = dir.getParent();
        }
        if (env == null) return out;
        try {
            for (String line : Files.readAllLines(env)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
                int eq = t.indexOf('=');
                String k = t.substring(0, eq).trim();
                String v = t.substring(eq + 1).trim();
                if (v.length() >= 2 && ((v.startsWith("'") && v.endsWith("'"))
                        || (v.startsWith("\"") && v.endsWith("\"")))) {
                    v = v.substring(1, v.length() - 1);
                }
                out.put(k, v);
            }
        } catch (IOException e) {
            // .env 不存在时由调用方自行注入 key
        }
        return out;
    }
}
