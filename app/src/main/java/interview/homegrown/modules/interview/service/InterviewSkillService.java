package interview.homegrown.modules.interview.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.interview.config.InterviewSkillProperties;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 面试 Skill 服务
 * 提供面试方向的查询与校验
 */

@Service
public class InterviewSkillService {

    private final InterviewSkillProperties properties;

    public InterviewSkillService(InterviewSkillProperties properties) {
        this.properties = properties;
    }

    //获取所有的Skill的展示信息（id -> 名称）
    public Map<String,String> listSkills(){

        return properties.getSkills().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,         //等价于e -> e.getKey()
                        e -> e.getValue().getName()
                        ));
    }

    //校验skillId是否存在，返回其配置
    public InterviewSkillProperties.SkillConfig getSkill(String skillId){
        InterviewSkillProperties.SkillConfig skillConfig = properties.getSkills().get(skillId);

        if(skillConfig == null){
            throw new BusinessException(ErrorCode.BAD_REQUEST,"不存在的面试方向: " + skillId);
        }
        return skillConfig;
    }

    //获取面试方向描述(用于出题 prompt)
    public String getSkillDescription(String skillId){
        return properties.getSkills().get(skillId).getDescription();
    }

    //获取追问次数
    public int getFollowUpCount(){
        return properties.getFollowUpCount();
    }

}
