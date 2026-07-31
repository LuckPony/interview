package interview.homegrown.infrastructure.file;


import org.springframework.stereotype.Service;

/**
 * 文本清洗服务
 * 对 Tika 解析出的原始文本做规范化处理：
 * - 去除空字符
 * - 多个空格/制表符合并
 * - 连续换行压缩
 */
@Service
public class TextCleaningService {

    //文本清洗
    public String clean(String raw){
        if(raw == null || raw.isEmpty()){
            return "";
        }

        return raw
                .replace("\u0000","")            // 去除 NUL 空字符（单个字符匹配多个）
                .replaceAll("[ \\t]+"," ")        // 多个空格/制表符合并为单个空格(正则表达式匹配多个)
                .replaceAll("\\n{3,}","\n\n")    // 3 个以上连续换行压缩为 2 个
                .trim();                                           //去掉首尾空白字符
    }
}
