package interview.homegrown.modules.resume.model;


import java.util.List;

//AI 简历分析的结构化输出：由 StructuredOutputInvoker 将 LLM 返回的 JSON 解析成这个 record
public record ResumeAnalysisResult(
        int overallScore,
        String summary,
        List<String> strengths,
        List<String> weaknesses,
        List<String> suggestions
) {


}
