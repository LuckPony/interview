import http from 'node:http';

const now = Date.now();
const iso = (days = 0) => new Date(now - days * 86400000).toISOString();
const envelope = (data) => ({ code: 200, message: 'ok', data });

const plans = [
  {
    id: 101,
    title: 'Java 后端高阶面试',
    goal: '系统掌握高并发、Spring 与分布式系统核心能力',
    concepts: [],
    masteredCount: 18,
    totalCount: 30,
    dueReviewCount: 3,
    corpusName: 'Java 高并发与 JVM 实战.pdf',
  },
  {
    id: 102,
    title: 'AI 应用工程化',
    goal: '从 RAG 到智能体的工程实践',
    concepts: [],
    masteredCount: 9,
    totalCount: 22,
    dueReviewCount: 2,
    corpusName: 'RAG Engineering Notes.md',
  },
];

const today = [
  { id: 1, planId: 101, planTitle: plans[0].title, kind: 'REVIEW', conceptId: 11, conceptName: 'JVM 内存模型', layer: 2, status: 'READY', questionId: 101, stem: '解释 happens-before 与可见性的关系', probeType: 'CONTRAST', subPoint: 'happens-before 规则' },
  { id: 2, planId: 101, planTitle: plans[0].title, kind: 'REVIEW', conceptId: 12, conceptName: 'Redis 缓存一致性', layer: 3, status: 'PENDING', questionId: null, stem: null, probeType: null, subPoint: '延迟双删' },
  { id: 3, planId: 101, planTitle: plans[0].title, kind: 'NEW', conceptId: 13, conceptName: 'Spring 事务边界', layer: 2, status: 'DONE', questionId: 103, stem: '为什么同类调用会让事务失效？', probeType: 'SCENARIO', subPoint: '代理机制' },
  { id: 4, planId: 102, planTitle: plans[1].title, kind: 'NEW', conceptId: 14, conceptName: '向量检索召回', layer: 2, status: 'DONE', questionId: 104, stem: 'TopK 与相似度阈值如何配合？', probeType: 'INTEGRATION', subPoint: '召回参数' },
];

const cards = [
  { id: 201, userId: 1, source: 'CHAT', question: 'Redis Stream 的 Pending 消息如何回收？', answer: '通过 XPENDING 定位超时消息，再使用 XAUTOCLAIM 转移给健康消费者处理。', detail: '消费组会把已投递但未 ACK 的消息放进 PEL。定时扫描空闲时间超过阈值的消息，并控制最大重试次数，可以避免任务永久挂起。', tags: ['Redis', '异步任务', '可靠性'], conceptId: 12, planId: 101, dueAt: iso(-1), reviewCount: 2, createdAt: iso(0) },
  { id: 202, userId: 1, source: 'CHAT', question: 'RAG 中为什么需要查询改写？', answer: '把口语化问题改写为更适合向量召回的检索表达，同时保留原始意图。', detail: '查询改写应与多轮上下文结合，并设置兜底策略，避免模型扩写后偏离用户问题。', tags: ['RAG', '向量检索', 'Spring AI'], conceptId: 14, planId: 102, dueAt: iso(-3), reviewCount: 1, createdAt: iso(1) },
  { id: 203, userId: 1, source: 'DRILL', question: '事务为什么不应该包住外部模型调用？', answer: '外部调用耗时不可控，会长时间占用数据库连接并扩大锁范围。', detail: '先在短事务内更新业务状态，再在事务外调用模型，最后用另一个短事务落结果。', tags: ['Spring', '事务', '工程实践'], conceptId: 13, planId: 101, dueAt: iso(2), reviewCount: 0, createdAt: iso(3) },
  { id: 204, userId: 1, source: 'CHAT', question: '向量检索的 TopK 如何选择？', answer: '先用离线评估确定候选区间，再结合阈值、重排和上下文预算动态收缩。', detail: 'TopK 不是越大越好，应同时观察召回率、噪声比例和模型上下文成本。', tags: ['pgvector', '检索评估'], conceptId: 14, planId: 102, dueAt: iso(0), reviewCount: 3, createdAt: iso(5) },
];

const corpora = [
  { id: 301, name: 'Java 高并发与 JVM 实战.pdf', charCount: 128460, sourceType: 'UPLOAD', createdAt: iso(2), overview: '围绕 Java 内存模型、并发容器、线程池、JVM 调优与线上故障定位展开的系统资料。', indexState: 'READY', topics: ['JMM', '并发容器', '线程池', 'JVM 调优'], chunkCount: 18, hasOriginal: true },
  { id: 302, name: 'RAG Engineering Notes.md', charCount: 46280, sourceType: 'UPLOAD', createdAt: iso(5), overview: '覆盖文档解析、语义切块、向量召回、查询改写与答案评估的工程笔记。', indexState: 'READY', topics: ['文档解析', 'pgvector', '查询改写', '评估'], chunkCount: 12, hasOriginal: true },
  { id: 303, name: 'Spring Boot 项目复盘.docx', charCount: 31620, sourceType: 'UPLOAD', createdAt: iso(8), overview: '从模块拆分、缓存、异步任务到部署安全的项目复盘材料。', indexState: 'BASIC', topics: ['Spring Boot', 'Redis Stream', 'Docker'], chunkCount: 9, hasOriginal: true },
];

const corpusDetail = {
  document: corpora[0],
  sections: [
    { id: 401, sequence: 0, title: '1. Java 内存模型与 happens-before', topic: 'JMM', summary: '从主内存、工作内存和重排序规则解释并发可见性。', charCount: 18320 },
    { id: 402, sequence: 1, title: '2. 并发容器与锁优化', topic: '并发编程', summary: '对比 ConcurrentHashMap、AQS 与不同锁策略的适用边界。', charCount: 24680 },
    { id: 403, sequence: 2, title: '3. 线程池与异步任务可靠性', topic: '线程池', summary: '分析线程池参数、拒绝策略、任务重试和积压治理。', charCount: 22140 },
    { id: 404, sequence: 3, title: '4. JVM 调优与故障定位', topic: 'JVM', summary: '以真实排障流程串联 GC、内存泄漏和线程分析。', charCount: 28650 },
  ],
  usages: [
    { kind: 'PLAN', id: '101', title: 'Java 后端高阶面试', status: 'ACTIVE' },
    { kind: 'INTERVIEW', id: 'demo-interview', title: 'Java 后端模拟面试', status: 'COMPLETED' },
  ],
};

const interviews = [
  { id: 'iv-1', skillId: null, skillName: 'Java 后端综合面试', difficulty: 'MIDDLE', status: 'COMPLETED', totalQuestions: 8, answeredCount: 8, totalScore: 86, createdAt: iso(1), mode: 'VOICE' },
  { id: 'iv-2', skillId: null, skillName: 'RAG 工程实践', difficulty: 'SENIOR', status: 'COMPLETED', totalQuestions: 6, answeredCount: 6, totalScore: 82, createdAt: iso(6), mode: 'TEXT' },
  { id: 'iv-3', skillId: null, skillName: 'Spring Boot 项目深挖', difficulty: 'MIDDLE', status: 'COMPLETED', totalQuestions: 7, answeredCount: 7, totalScore: 89, createdAt: iso(11), mode: 'TEXT' },
];

function json(res, payload, status = 200) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': '*',
  });
  res.end(JSON.stringify(payload));
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1:23333');
  const path = url.pathname;
  if (req.method === 'OPTIONS') return json(res, {});
  if (path === '/api/auth/config') return json(res, { captchaRequired: true, emailVerifyRequired: true });
  if (path === '/api/user/profile') return json(res, envelope({ id: 1, email: 'demo@mianba.vip', username: '求职进阶者', nickname: '求职进阶者', gender: null, phone: null, birthday: null }));
  if (path === '/api/study-plan') return json(res, plans);
  if (path === '/api/drill/today') return json(res, today);
  if (path === '/api/drill/debt') return json(res, [
    { runId: 501, stem: '说明 ThreadLocal 可能造成内存泄漏的原因', rawScore: 72, answeredAt: iso(0), weakPoints: ['没有说明弱引用只作用于 Key'], conceptId: 11, planId: 101 },
    { runId: 502, stem: '缓存一致性有哪些工程折中？', rawScore: 78, answeredAt: iso(1), weakPoints: ['缺少失败重试策略'], conceptId: 12, planId: 101 },
  ]);
  if (path === '/api/knowledge/cards/due') return json(res, envelope(cards.filter((card) => card.reviewCount < 2)));
  if (path === '/api/knowledge/cards') return json(res, envelope(cards));
  if (path === '/api/interviews/sessions') return json(res, envelope(interviews));
  if (path === '/api/interviews/skills') return json(res, envelope({ java: 'Java 后端', rag: 'RAG 工程实践' }));
  if (path === '/api/resumes') return json(res, envelope([{ id: 601, originalName: 'Java后端开发工程师-示例简历.pdf', fileType: 'pdf', fileSize: 286400, status: 'ANALYZED', overallScore: 86, createdAt: iso(4) }]));
  if (path === '/api/corpus') return json(res, corpora);
  if (path === '/api/corpus/301') return json(res, envelope(corpusDetail));
  if (path === '/api/corpus/301/text') return json(res, envelope({ text: '# Java 内存模型\n\n本章介绍 happens-before、volatile 与并发可见性。\n\n## happens-before\n\n它定义了操作之间的可见性与有序性保证。', totalChars: 18320, truncated: false }));
  if (path === '/api/settings/ai') return json(res, { provider: 'deepseek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', maskedApiKey: 'sk-••••••••••••••••', hasApiKey: true, temperature: 0.7, supportsVision: false, reasoningEffort: 'auto' });
  return json(res, { code: 404, message: `preview endpoint missing: ${path}`, data: null }, 404);
});

server.listen(23333, '127.0.0.1', () => console.log('SITE_PREVIEW_MOCK_READY http://127.0.0.1:23333'));

