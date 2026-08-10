package interview.homegrown.modules.drill;

import interview.homegrown.modules.drill.repository.DrillNoteRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.DrillTurnRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 拆分了 test-autoconfigure，注解包名与 Boot 3 不同，别照抄旧文档
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HQL 语法与字段路径的启动期校验，<b>不连数据库</b>。
 *
 * <p>为什么需要它：{@code @Query} 里的 HQL 是字符串，写错表名、字段名、或用了 JPA 规范
 * 不接受的语法（比如 {@code select 1}），编译器一个字都不会说，要等到运行时第一次调用
 * 才炸。Spring Data 在<b>创建 repository bean 时</b>就会调用 {@code em.createQuery(hql)}
 * 做解析校验 —— 所以只要上下文能起来，这些查询就是合法的。
 *
 * <p>三个关键配置让它无需真库：
 * <ul>
 *   <li>{@code allow_jdbc_metadata_access=false} + 显式 dialect：Hibernate 不去连库探元数据</li>
 *   <li>{@code ddl-auto=none} + 关 Flyway：不建表、不迁移</li>
 *   <li>{@code Replace.NONE}：不让测试框架塞一个 H2 进来 —— H2 认不得 {@code integer[]} 和 {@code jsonb}</li>
 * </ul>
 * 测试方法上的 {@code NOT_SUPPORTED} 是必须的：@DataJpaTest 默认开事务，一开就要连接。
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/interview",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=pony",
        "spring.datasource.password=123456"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DrillQueryParseTest {

    @Autowired
    private DrillRunRepository runRepo;
    @Autowired
    private DrillNoteRepository noteRepo;
    @Autowired
    private DrillTurnRepository turnRepo;
    @Autowired
    private GradeResultRepository gradeRepo;
    @Autowired
    private QuestionBankRepository qbRepo;
    @Autowired
    private MasteryRepository masteryRepo;

    /**
     * 断言看着弱，但真正的验证发生在<b>上下文启动那一刻</b>：
     * findNoteDebt 的三表 theta join、not exists 子查询、投影别名映射，
     * 任何一处写错，这个测试连 assert 都跑不到就会挂在 bean 创建阶段。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void allDrillRepositoryQueriesParse() {
        assertNotNull(runRepo);
        assertNotNull(noteRepo);
        assertNotNull(turnRepo);
        assertNotNull(gradeRepo);
        assertNotNull(qbRepo);
        assertNotNull(masteryRepo);
    }
}
