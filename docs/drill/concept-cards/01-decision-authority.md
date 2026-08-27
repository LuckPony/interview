# 概念卡 01：为什么教学决策权要收回服务端

> 模板：定义 → 机制 → 项目印证 → 对比 → 易错点/面试陷阱 → 记忆锚点

## 1. 定义
把「教什么、出什么题、分怎么算、何时复习」等**教学决策**，从 LLM 的自由发挥收回到**服务端确定性算法**；LLM 退化为「教具」——只负责生成内容（出题文本、逐点评分、追问），不负责做决定。

## 2. 机制
服务端拥有四类确定性权力：
- **选题**：算法按到期权重 / 掌握度挑 concept（`SelectionService.pickNext`，无 LLM）
- **去重**：三闸（未用 probe_type → 历史题干 → embedding>0.85 硬兜底）
- **判分计算**：LLM 只输出逐 point verdict，分数由服务端聚合（`grade_result.by_concept`）
- **间隔排程**：FSRS 五档表，常量可复现（`IntervalScheduler`）

LLM 调用全部包在 `QuestionGenerator` / `GraderText` / `FollowupGenerator` 内，接口稳定、可替换、可单测、可缓存限流。

## 3. 项目印证
- `SelectionService.pickNext`：纯算法，零 LLM 调用
- `Grader` 接口四个实现：MCQ/CODE 直接绕过 LLM 精确判分；LLM 只在 Text/Structured 内
- `by_concept` 统一格式：`evidence` 必须是用户原话片段，防 LLM 脑补
- 物理闸门用 DB 唯一索引，不用 prompt 规则

## 4. 对比
- **LLM 自主教学**（prompt 写"你是老师，自行出题判分"）：不可复现、画像漂移、并发失控、成本不可控
- **本方案**：决策可复现、画像诚实、DB 约束兜底并发、LLM 调用可治理

## 5. 易错点 / 面试陷阱
- 把「判分」交给 LLM 直接出分数 → 善意脑补，痛点 3 的诚实画像崩塌。**必须 LLM 只给 verdict，分数服务端算**
- 用 prompt 规则做闸门（"你不能同时开两道题"）→ 拦不住并发，必须用 DB 唯一索引
- 让 LLM 决定「接下来学什么」→ 偏斜/信息茧房，应由服务端按矩阵 + 到期算法决定

## 6. 记忆锚点
**一句话：LLM 当笔，服务端当脑。** 凡是「决定」用算法，凡是「生成」用 LLM。
