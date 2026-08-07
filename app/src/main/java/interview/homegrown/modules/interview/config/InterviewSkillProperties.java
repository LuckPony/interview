package interview.homegrown.modules.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 面试 Skill 配置
 * 绑定 application.yml 中 app.interview.* 的配置项
 * 每个 skill 定义了一个面试方向，由 LLM 依据 direction 生成题目。
 */
@Configuration
@ConfigurationProperties(prefix = "app.interview")
public class InterviewSkillProperties {

    private int followUpCount = 1;

    private Map<String, SkillConfig> skills = new HashMap<>();


    public int getFollowUpCount() {
        return followUpCount;
    }

    public void setFollowUpCount(int followUpCount) {
        this.followUpCount = followUpCount;
    }

    public Map<String, SkillConfig> getSkills() {
        return skills;
    }

    public void setSkills(Map<String, SkillConfig> skills) {
        this.skills = skills;
    }


    public static class SkillConfig{

        //不同领域知识
        private String name;

        //该领域的考察范围，指导LLM出题
        private String description;

        //默认题目数量
        private int defaultQuestionCount = 5;


        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getDefaultQuestionCount() {
            return defaultQuestionCount;
        }

        public void setDefaultQuestionCount(int defaultQuestionCount) {
            this.defaultQuestionCount = defaultQuestionCount;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

}


