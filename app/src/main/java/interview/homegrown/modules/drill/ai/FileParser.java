package interview.homegrown.modules.drill.ai;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件解析：把用户上传的字节变成纯文本 String，供 LLM 当上下文。
 *
 * <p>v1 只做文本层：Apache Tika 统一处理 PDF（数字导出、带文字层）/ txt / md / docx。
 * 底层 PDF 解析走 PDFBox。扫描件 / 纯图片没有文字层，Tika 抽不到字 —— 这种我们
 * <b>显式报错</b>让用户知道，而不是静默返回空串（否则用户以为上传成功了）。
 */
@Component
public class FileParser {

    private final Tika tika = new Tika();

    public String parse(InputStream in, String filename) {
        try {
            String text = tika.parseToString(in);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "没能从文件里提取到文字。可能是扫描件或纯图片（本期只支持带文字层的 PDF / txt / md / docx），请换一份带文字层的资料。");
            }
            return text;
        } catch (IOException | TikaException e) {
            throw new IllegalArgumentException("文件解析失败：" + e.getMessage());
        }
    }
}
