package interview.homegrown.modules.resume;


import interview.homegrown.modules.resume.model.ResumeEntity;
import interview.homegrown.modules.resume.model.ResumeStatus;
import interview.homegrown.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

//使用H2内存库，验证实体映射和Repository
@DataJpaTest
@ActiveProfiles("test")
public class ResumeRepositoryTest {

    @Autowired
    private ResumeRepository resumeRepository;

    @Test
    @DisplayName("保存 DOCX 简历并按内容哈希查询")
    void shouldSaveAndFindByHash(){
        ResumeEntity resume = new ResumeEntity();
        resume.setUserId(1L);
        resume.setOriginalName("张三-后端开发.docx");
        resume.setFileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        resume.setFileSize(102400L);
        resume.setStorageKey("uuid.pdf");
        resume.setContentHash("13457456253");
        resume.setStatus(ResumeStatus.UPLOADED);

        resumeRepository.save(resume);

        Optional<ResumeEntity> found = resumeRepository.findByContentHash("13457456253");
        //使用单元测试中的“断言”来判断测试结果是否满足我预期的结果
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(ResumeStatus.UPLOADED);
        assertThat(found.get().getOriginalName()).isEqualTo("张三-后端开发.docx");
        assertThat(found.get().getUserId()).isEqualTo(1L);

    }
}
