package interview.homegrown.modules.resume.model;


//简历列表项 DTO

import java.time.LocalDateTime;

public record ResumeListItemDTO(
        Long id,
        String originalName,
        String fileType,
        long fileSize,
        String status,
        Integer overallScore,
        LocalDateTime createdAt
) {
}
