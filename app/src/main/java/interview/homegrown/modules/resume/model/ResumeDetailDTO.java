package interview.homegrown.modules.resume.model;


import java.time.LocalDateTime;
import java.util.List;

//简历详情DTO（含AI分析结果）
public record ResumeDetailDTO(
        Long id,
        String originalName,
        String fileType,
        long fileSize,
        String storageKey,
        String status,
        String errorMessage,
        String resumeText,
        Integer overallScore,
        String summary,
        List<String> strengths,
        List<String> weaknesses,
        List<String> suggestions,
        LocalDateTime createdAt
) {
}
