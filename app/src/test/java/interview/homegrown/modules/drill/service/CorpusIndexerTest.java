package interview.homegrown.modules.drill.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CorpusIndexer 启发式切块验证：
 * 按标题边界切、软上限在段落边界收尾、不硬切丢失内容、块数上限合并。
 */
class CorpusIndexerTest {

    @Test
    void 按Markdown标题切块() {
        String text = """
                # 第一章 线程基础

                线程是操作系统调度的最小单位。

                ## 1.1 创建线程

                有三种创建方式。

                # 第二章 锁

                锁用于保护临界区。
                """;
        List<CorpusIndexer.Chunk> chunks = CorpusIndexer.split(text);
        assertEquals(3, chunks.size(), "按标题切出 3 块");
        assertTrue(chunks.get(0).heading().contains("第一章"));
        assertTrue(chunks.get(1).heading().contains("1.1"));
        assertTrue(chunks.get(2).heading().contains("第二章"));
    }

    @Test
    void 无标题时按段落聚合不截断() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("这是第").append(i).append("行内容，用于撑满软上限，保证聚合逻辑走段落边界。\n");
            if (i % 30 == 29) sb.append("\n"); // 段落边界
        }
        List<CorpusIndexer.Chunk> chunks = CorpusIndexer.split(sb.toString());
        assertTrue(chunks.size() >= 2, "段落式文本切成多块");
        String all = chunks.stream().map(CorpusIndexer.Chunk::text).reduce("", String::concat);
        // 内容不丢失（切块只是分组，不丢行）
        assertTrue(all.contains("这是第0行内容"), "块内容包含开头");
        assertTrue(all.contains("这是第399行内容"), "块内容包含结尾（不截断）");
    }

    @Test
    void 单块超硬上限才截断() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("x"); // 无空行 → 无法在段落边界收尾，触达硬上限
        }
        List<CorpusIndexer.Chunk> chunks = CorpusIndexer.split(sb.toString());
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text().length() <= CorpusIndexer.HARD_MAX_CHARS + 12,
                "超硬上限截断（允许结尾标记多几个字符），实际=" + chunks.get(0).text().length());
    }

    @Test
    void 块数上限合并进最后一块() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("### 小节 ").append(i).append("\n内容 ").append(i).append("\n\n");
        }
        List<CorpusIndexer.Chunk> chunks = CorpusIndexer.split(sb.toString());
        assertTrue(chunks.size() <= CorpusIndexer.MAX_CHUNKS, "块数不超过上限，实际=" + chunks.size());
        String all = chunks.stream().map(CorpusIndexer.Chunk::text).reduce("", String::concat);
        assertTrue(all.contains("内容 99"), "末尾小节合并不丢，实际含末尾=" + all.contains("内容 99"));
    }
}
