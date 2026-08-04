package interview.homegrown.modules.resume.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.resume.model.ResumeAnalysisResult;
import org.springframework.stereotype.Service;

//简历AI分析服务
@Service
public class ResumeAnalysisService {

    private final StructuredOutputInvoker invoker;
    public  ResumeAnalysisService(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    private static final String SYSTEM_PROMPT = """
            你是一位资深的简历分析师和面试官，
            擅长从技术栈匹配度、项目经验深度、职业发展路径等维度评估候选人简历。
            请输出专业、客观、量化的分析结论。""";

    public ResumeAnalysisResult analyze(String resumeText){

        String userPrompt = """
                请仔细阅读以下简历内容并进行分析：
                
                ----------------------------------------
                %s
                ----------------------------------------

                分析要求：
                1. overallScore 为 0-100 的整数，代表简历整体质量
                2. summary 用 2-3 句话总结候选人画像
                3. strengths / weaknesses / suggestions 各给出 3-5 条，每条一句话
                """.formatted(resumeText);
        return invoker.invoke(SYSTEM_PROMPT, userPrompt, ResumeAnalysisResult.class,null);
    }
}
