# 智能客服 RAG 系统技术梳理

> 梳理日期：2026-08-12  
> 梳理范围：当前工作区源码、配置、SQL 迁移、Docker 编排、测试与评测报告。  
> 口径说明：本文描述的是仓库当前实现，不代表未经核验的生产运行参数。凡模型名称、开关或参数可被数据库/环境变量覆盖的地方，均明确标注。

本机运行快照（2026-08-12）：Docker 中的应用、MySQL、Redis、Qdrant 和 MinIO 均为 healthy；当前启用模型为 `deepseek-v4-pro`（LLM）、智谱 `embedding-3`（Embedding）和 `qwen3.7-plus-2026-05-26`（知识抽取）。当前实际环境变量为 Prompt V2、Qdrant 开启且 2048 维、BM25 开启，查询扩展/Rerank/结构化知识单元索引/native fallback 均关闭，切片参数为 600/50/80。该快照仅代表本机当前容器，不自动等同于生产环境。

## 一、结论摘要

该系统是一个自研的、基于 Java/Spring Boot 的 RAG 智能客服系统，没有使用 LangChain 或 LlamaIndex。它不是单路向量检索，而是由 FAQ 精确/关键词匹配、向量检索、BM25、字面相似度及必要时的拼音相似度共同召回，再以倒数排名融合（RRF）合并候选；外部 Cross-Encoder Rerank 已实现但默认关闭。

知识文档按结构解析和分块：优先保留标题层级、表格问答、段落问答和文档内图片 OCR 结果。普通文本默认按字符计数，单块上限 600 字符、最小 50 字符、重叠上限 80 字符；重叠不是机械截取固定 80 字符，而是尽量携带上一块末尾的完整句子。

Embedding 和生成模型均采用运行时配置。Embedding 优先读取数据库中启用的 `Embedding` 类型模型，SQL 迁移预置智谱 `embedding-3`；生成模型从数据库中启用的 LLM 配置路由。本机当前实际启用 `embedding-3` 和 `deepseek-v4-pro`，但仓库和本机快照都不能单独证明生产当前使用相同模型。向量库主选 Qdrant，默认向量维度 2048，同时保留 MySQL 中的向量及元数据，并支持 Qdrant 不可用时的内存检索降级。

系统具备引用溯源，但默认不把 `[1]` 或“参考来源”展示在最终客服文本里。引用以结构化 `citations` 返回并写入消息元数据、AI 调用日志，同时可用于知识图片附件。评测层已经覆盖检索准确率、引用命中、回答决策、事实短语、转人工、PII 泄漏和延迟；线上可观测性主要依靠数据库日志和后台统计，尚未发现 Prometheus/Micrometer 指标、在线 P95 告警或线上召回率自动计算。

## 二、知识库构建层

### 2.1 文档解析与清洗

管理端的主导入链路支持以下格式：

| 格式 | 解析方式 | 结构保留与清洗 |
| --- | --- | --- |
| TXT、Markdown | UTF-8 读取 | 统一换行、压缩行内空白，保留段落和 Markdown 标题 |
| HTML/HTM | Jsoup | 提取正文文本，去除 HTML 标签并保留可用文本结构 |
| PDF | Apache PDFBox | 按页面、按位置排序提取文本；多页文档增加页标题，随后统一空白 |
| DOCX | Apache POI | 按正文原始顺序处理段落和表格；识别标题样式、问答段落、问答表格；内嵌图片调用 OCR |
| XLSX/XLS | Apache POI | 扫描前 20 个非空行寻找“问题/答案”列；确认后按行生成结构化问答，否则按普通表格文本处理 |

解析后不是直接入索引。系统还记录结构化问答识别数、无效行数、内嵌图片数和图片识别失败数。文档结构质量检查失败时，会阻止切片审核、向量生成和发布。原文件存入 MinIO，解析文本、文档状态、切片和向量元数据存入 MySQL。

需要区分两条历史链路：`feisheng-bot-admin` 中的 `DocumentParseService` 是当前完整的管理端导入链路；`feisheng-bot-knowledge` 中还保留一个较简单的 `DocumentParseServiceImpl`，用于把 TXT/CSV/TSV/DOCX/PDF 按行解析为 FAQ。本文以管理端完整链路为主。

### 2.2 分块策略

当前策略版本为 `chunk-v6`，核心原则是“结构优先、句子优先、字符上限兜底”：

1. 已由解析器确认的表格/段落问答，会生成 `QA` 类型切片，并保留问题、完整答案、规范化问答键和组键。
2. 普通文本先按 Markdown 标题和中文章节标题切成 section，记录类似 `一级标题 > 二级标题` 的 `sectionPath`。
3. section 内按中文/英文句末标点及换行拆分，再在字符预算内打包；超长单句才硬切。
4. 末块小于最小长度时，若合并后仍不超限，则并入前一块。
5. 从第二块开始，尽量把上一基础块末尾的一整句作为前缀，且不跨 section。无法找到满足长度条件的完整句子时，不强行重叠。
6. 检索命中后还可补充同一文档、同一 section 的相邻块，默认半径为 1，最多补 4 块。

默认参数如下，单位均为 Java 字符数，不是 tokenizer token：

| 参数 | 默认值 | 说明 |
| --- | ---: | --- |
| `knowledge.chunking.max-chars` | 600 | 最终切片字符上限 |
| `knowledge.chunking.min-chars` | 50 | 尾块合并参考下限 |
| `knowledge.chunking.overlap-chars` | 80 | 上一块完整句尾的最大重叠长度 |

当开启重叠时，基础块正文预算为 `600 - 80 - 1 = 519` 字符，为可能加入的句尾和换行预留空间。因此“Chunk 600、Overlap 80”不等于每两个块固定重复 80 字符。

Embedding 文本会把 `sectionPath` 与正文拼接。FAQ 的 Embedding 输入上限为 2000 字符，超长答案按句子拆成多个 part；普通文档切片的 Embedding 工具上限为 4000 字符，但当前切片自身通常不超过 600 字符。

### 2.3 向量化模型与向量库

Embedding 调用采用 OpenAI-compatible `/embeddings` 协议，配置优先级为：

1. 数据库 `bot_ai_model_config` 中 `status=1`、`model_type=Embedding` 的默认模型；
2. 若数据库模型不可用，核心模块回退到 YAML/环境变量中的 OpenAI-compatible 配置，默认模型名为 `text-embedding-3-small`，但只有配置 API Key 后才可用。

SQL 迁移 `06_add_embedding_model.sql` 会在已有智谱凭证的前提下，预置智谱 `embedding-3`，接口为 `https://open.bigmodel.cn/api/paas/v4/embeddings`。因此可以确认“仓库推荐/预置模型是智谱 embedding-3”，但实际运行模型仍应以数据库当前启用项为准。

本机当前数据库实际启用的是智谱 `embedding-3`，并被标记为默认 Embedding 模型。

向量存储主库为 Qdrant：

| 参数 | 默认值 |
| --- | --- |
| 是否启用 | `true` |
| 主 collection | `feisheng_knowledge` |
| 结构化知识单元 collection | `feisheng_knowledge_semantic_units` |
| 向量维度 | 2048 |
| 写入批大小 | 64 |
| 连接/读取超时 | 2 秒 / 10 秒 |

MySQL 同时保存切片/FAQ 的向量、模型、版本、维度和内容哈希。知识服务会构建内存快照；Qdrant 未启用或同步/查询失败时，可降级到内存向量搜索。索引默认每 30 秒同步一次。

## 三、检索层

### 3.1 召回与融合

当前是多路混合检索，不是单路向量检索。主要通道包括：

| 通道 | 默认状态 | 作用 |
| --- | --- | --- |
| FAQ 精确/关键词匹配 | 开启 | 精确问句、包含、关键词覆盖等确定性匹配；审核允许时可直接返回标准答案 |
| 向量检索 | Embedding 可用时开启 | Qdrant 为主，内存向量为降级；召回 FAQ、文档块等 |
| BM25 | 开启 | 内存稀疏索引；中文使用单字/双字 token，英文使用单词 token |
| 字面相似度 | 开启 | 保证精确或近似原文参与排序，不被纯语义结果压制 |
| 拼音相似度 | 条件开启 | 没有足够可信文本匹配时，用于处理中文同音/错别字 |
| 结构化知识单元 | 默认关闭 | 可影子运行或把语义单元映射回证据切片；默认不影响正式排序 |

多个排名通过 RRF 融合，默认 `rank-fusion-k=60`，同时保留向量、BM25、字面、关键词等各路分数和 rank 供诊断。默认先召回 10 个候选，最终取 3 个上下文结果；上下文接受阈值默认为 0.50。FAQ 精确匹配只有在得分至少 0.82 且该问答已启用直答时才直接返回。

默认检索关键参数：

| 参数 | 默认值 |
| --- | ---: |
| 最终 `top-k` | 3 |
| 候选 `candidate-k` | 10 |
| FAQ 直答阈值 | 0.82 |
| 上下文阈值 | 0.50 |
| 字面相似阈值 | 0.72 |
| BM25 | 开启 |
| BM25 最低分 | 0.0 |
| RRF 常数 | 60 |
| 相邻块半径 / 最大补块 | 1 / 4 |

### 3.2 Rerank

系统已实现外部 Cross-Encoder Rerank，接口契约为 `query + documents`，可从数据库中的 Rerank 类型模型或环境配置获取地址、Key 和模型名。默认关闭：`rag.rerank.enabled=false`。

开启后默认最多重排 10 个候选，每个候选正文最多 2000 字符，连接/读取超时为 1 秒/3 秒。完整返回后才应用重排，并根据 Top1 绝对分和 Top1/Top2 分差判定高、中、低置信度；调用失败或结果不完整时保留原融合排序，不中断回答。

### 3.3 查询改写与扩展

系统同时存在两类处理，需分开理解：

- 上下文改写：默认生效。`ContextualQueryResolver` 根据历史消息识别“这个、它、然后呢”等省略/指代，把依赖上下文的追问补成可独立检索的问题；`NlpIntentClassifier` 还会做意图归一化，部分复合业务问题会拆成多次确定性检索再合并证据。
- LLM 查询扩展：已实现但默认关闭。开启后最多生成 5 个查询（含原查询），类型可为标准化、同义、口语化或子问题，并分别加权。模型输出必须满足严格 JSON schema，且校验实体标识、数字、否定极性和主题锚点；任意失败都退回原查询。

精确命中、过长查询、URL/邮箱等精确字面量、订单/物流工具查询会跳过 LLM 扩展。多轮检索历史默认最多使用 4 条、1200 字符；独立补全后的查询不会再次混入旧主题，避免上下文污染。

## 四、生成层

### 4.1 大模型选择

生成模型没有在代码中固定为单一型号。系统从数据库 `bot_ai_model_config` 读取启用的 LLM，并通过 OpenAI-compatible Chat Completions 协议调用；路由层支持首选模型/供应商及可用模型降级。仓库包含 OpenAI 和 DeepSeek provider，实现上也可接入其他兼容供应商。本机当前数据库实际启用并默认使用 `deepseek-v4-pro`。

仓库初始数据只包含一个禁用的 `GPT-4o-mini` 示例，不能据此认定生产使用 GPT-4o-mini。2026-08-12 的一份 V1/V2 对比报告使用过 `deepseek-v4-pro`，且本机运行数据库当前也启用了该模型；生产是否一致仍应以生产数据库为准。

### 4.2 Prompt 设计

Prompt 分为 system prompt 和 user prompt 两层，并支持 `v1`/`v2` 版本：

- system prompt 定义官方客服身份、业务主体锁定、事实边界、冲突证据处理、禁止臆测、敏感承诺限制、纯文本输出、部分回答/拒答协议。
- user prompt 拼入检索事实、有限对话历史、用户原问题、归一化后的本轮意图，并要求首句直接回答、只使用当前主体的证据、区分“资料未提及”和“不支持”。
- 无充分知识时使用 `__NO_ANSWER__`；只能回答部分时使用 `__ANSWER_PARTIAL__`。标记在返回用户前会被移除。
- 针对公司介绍、操作步骤、详细枚举、服务时效、复合问题、肯否极性等场景，代码会追加专项约束。
- 对明确 FAQ、价格转人工、安全拦截、业务工具结果等场景，可完全绕过 LLM，走确定性回复。

当前工作区配置和本机运行容器的 Prompt 版本均为 `v2`，可由 `AI_CUSTOMER_SERVICE_PROMPT_VERSION` 覆盖。历史对比报告中曾建议生产暂留 `v1`，因此生产环境仍需核对其实际环境变量，不能只看报告或本机默认值。

### 4.3 引用溯源

系统有完整的后端引用链路：检索结果构建结构化 `citations`，包含来源类型、来源 ID、文档/切片信息、标题和匹配诊断；引用随 API 响应返回，并写入 AI 消息 metadata、`bot_ai_reply_log.trace_json` 和 `cited_chunk_ids`。结构化知识单元还保存证据切片 ID 和原文 span。

但用户可见文本采用“隐藏引用标号”的产品策略：模型若输出 `[1]`、`【1】` 或“参考来源：”，发送前会被移除。也就是说，系统支持后台审计和接口级溯源，但默认客服话术中不展示引用脚注。知识图片附件则可根据 citations 自动附加。

### 4.4 温度与 Token

| 项目 | 当前实现 |
| --- | --- |
| Temperature | 默认 0.2，由 `ai.llm.temperature` 覆盖 |
| Prompt 上下文预算 | 默认 4000 token（代码按约 2 字符/token 粗估） |
| 对话历史 | 最多 6 条历史消息 |
| 检索历史 | 最多 4 条、1200 字符 |
| 最大输出 token | Chat 请求体未发送 `max_tokens`/`max_completion_tokens`，仓库内未发现生成输出上限；实际由模型供应商默认值决定 |
| 超时与重试 | 连接 10 秒、读取 60 秒、默认最多重试 1 次（环境样例）；客户端代码兜底值为 2 次 |

这里的 4000 是输入 Prompt 的本地预算，不是模型总上下文窗口，也不是输出上限。

## 五、工程与架构

### 5.1 技术栈与部署

系统为自研 RAG，没有引入 LangChain/LlamaIndex：

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.2.4、MyBatis-Plus、Resilience4j |
| 文档处理 | Apache PDFBox、Apache POI、Jsoup、Tesseract OCR |
| 数据与存储 | MySQL 8、Redis 7、Qdrant 1.14.1、MinIO |
| 前端 | Vue 3、Element Plus、Pinia、Vite |
| 渠道 | 钉钉、企业微信 |
| 部署 | Maven 多模块 + Docker Compose |

代码按 `common`、`gateway`、`core`、`knowledge`、`admin` 分模块，但部署形态是模块化单体：`admin` 依赖并统一打包其他模块，最终运行一个 Spring Boot JAR。Docker Compose 同时编排应用、MySQL、Redis、Qdrant、MinIO 和前端服务。

### 5.2 多轮对话状态

会话事实源是 MySQL，而不是 Redis：

1. 以 `channelType + channelUserId` 查找最新的 `active` 或 `transferred` 会话，没有则新建。
2. 用户和客服/AI 消息按时间顺序持久化到 `bot_message`。
3. 每轮会读取该会话历史，但构造生成 Prompt 时只取最近 6 条；只有判断为上下文依赖时，才给检索侧最多 4 条、1200 字符的历史。
4. 会话还持久化人工接管状态、优先级、SLA、情绪、CSAT 等字段。
5. Redis 用于短期缓存、令牌、幂等/并发辅助和 Embedding 等运行缓存，不是会话长期状态的唯一来源。

当前 `getByConversation` 会先从 MySQL 读取整段会话，再在内存中裁剪最近消息。长会话下这会带来额外数据库与内存开销，是后续可优化点。

### 5.3 内部话术与敏感内容隔离

现有防线包括：

- 来源隔离：正式客服检索固定带 `sourceScope=KNOWLEDGE`，聊天临时图片/内容使用其他 scope，避免聊天内容进入公共知识召回。
- 发布隔离：只有质量检查通过、审核/发布且在有效期内的文档/切片进入正式索引；结构化单元必须关联有效证据块。
- PII 脱敏：手机号、身份证、银行卡、邮箱、地址在保存用户消息、构造检索/Prompt、引用和日志前被替换；支持少量明确白名单值。
- 安全规则：数据库可配置 `FORCE_HANDOFF`、`SENSITIVE_WORD`、`FORBIDDEN_TOPIC`、`AI_DISCLAIMER`，分别用于输入前检和输出后检，规则缓存 1 分钟。
- 业务边界：价格、隐私越权、法律/服务承诺、高风险无知识等场景走固定回复、拒答或人工转接，不完全依赖 Prompt。
- 输出保护：生成结果再次做敏感规则检查；命中后清除 citations，改为安全话术或转人工。

需要注意的边界：仓库中没有发现比 `KNOWLEDGE/CHAT` 更细的“仅内部员工可见、不得用于外部客服”的文档 ACL 或租户级知识权限。名称中带“内部”的文档如果被发布为 `KNOWLEDGE`，仍可能成为外部回答依据。因此内部运营手册、不可外发话术和可对客知识应在发布前物理分库或增加独立 scope/权限策略，不能只依赖文件名和 Prompt。

另外，RAG Prompt 和候选详情会写入 AI 回复日志供审计，这意味着日志数据库本身包含内部知识上下文，必须按敏感数据资产控制后台权限、备份和保留周期。

## 六、评估与运维

### 6.1 回答准确率测试

系统已经有两级离线评测：

**检索级评测**

- 样本定义问题是否可回答、期望来源类型/ID和可选历史对话。
- 指标包括：决策准确率、可回答召回率、拒答召回率、回答/拒答精确率、引用命中率、来源 Hit@1 和 MRR。
- 结果保留每条样本的候选、引用、置信度、决策和结构化单元诊断，适合分析召回和排序问题。

**端到端对话评测**

- 调用真实检索和真实模型，可指定 Prompt 版本、模型和多轮历史。
- 指标包括：回答决策准确率、知识依据一致率、必要短语命中率、禁止短语违规、转人工准确率、PII 泄漏、模型错误和单条延迟。
- 每条样本在事务中执行并回滚会话、消息、工单和日志，减少评测数据污染；真实模型调用仍会产生 API 成本。
- 仓库还保留人工黑盒复核报告，用于纠正纯关键词指标无法识别否定语境、同义表达和事实完整度的问题。

2026-08-12 的几组报告可作为历史快照，但不能混成一个统一基线：

| 评测集 | 主要结果 |
| --- | --- |
| Prompt V1/V2 对比，30 条 | 两版决策/知识依据/转人工均 100%；V2 必要事实 100%，平均 2630 ms |
| 真实用户黑盒，24 条 | 决策 91.67%，知识依据 95%，必要短语 65.38%，PII 泄漏 0 |
| 第二轮真实业务，20 条 | 决策/知识依据 95%，人工完整可用率 80%、含不完整可用为 90%，平均 7.38 秒、P95 12.38 秒 |

报告揭示的主要问题集中在：文档问答边界解析、长混合块、固定规则与知识冲突、复合问题证据整合、自然表达覆盖和偶发生成事实答反。

### 6.2 Bad Case 收集

已有两类机制：

- 在线未命中收集：无答案路径把规范化问题写入 `bot_unmatched_question`，相同未解决问题累计 `similar_count`；管理后台可按频次查看并标记已解决。
- 离线评测归档：`docs/evaluation-results` 保存逐条候选、引用、回复、延迟和人工复核结论，可直接形成回归集。

当前缺口是：系统主要自动收集“没有回答”的问题，已回答但答错、答漏或引用错误的案例不会自动成为 Bad Case。CSAT 已支持 1-5 分和文字反馈，但尚未发现把低分反馈自动关联到 AI 日志、检索 trace 并进入待处理队列的闭环。

### 6.3 监控指标现状

当前可记录或查询的指标包括：

| 类别 | 已有数据 |
| --- | --- |
| 调用性能 | 单次端到端 `latency_ms`，业务工具延迟，模型调用成功/失败 |
| 模型用量 | 模型、供应商、输入/输出 token、估算成本、调用状态 |
| RAG 诊断 | 是否使用 RAG、置信度、决策、各路候选分数/rank、引用、命中切片 |
| 业务运营 | 会话数、消息数、转人工数、活动会话、待处理工单 |
| 用户体验 | 会话 CSAT 1-5 分与文字反馈、情绪标签和负向趋势 |
| 索引健康 | Qdrant 可用状态、同步条数、删除条数、同步耗时和错误 |
| 基础健康 | Docker healthcheck、应用 `/api/health`、MySQL/Redis/Qdrant/MinIO 健康检查 |

尚未发现的生产化能力：

- 没有发现 Spring Boot Actuator、Micrometer、Prometheus、Grafana 或 OpenTelemetry 接入。
- 没有在线聚合 P50/P95/P99 延迟、错误率、模型超时率或 Rerank 降级率的代码与告警。
- “召回率、引用命中率、MRR”只在带标准答案的离线评测中计算，线上没有 ground truth，不能直接实时监控召回率。
- 日统计只聚合会话、消息和转人工，尚未聚合 CSAT、低置信回答、未命中率、RAG 覆盖率和 token/成本。
- 未发现 SLO/告警阈值和自动回归门禁。现有报告曾建议 P95 小于 8 秒，但第二轮真实业务评测的 P95 为 12.38 秒，仍未达到该建议线。

## 七、建议核验项

以下问题无法仅凭仓库静态代码得出，若用于对外技术答疑或上线评审，建议从运行环境补充确认：

1. 当前数据库实际启用的 LLM、Embedding 和 Rerank 模型及其版本。
2. 生产环境 Prompt 版本是 `v1`、`v2` 还是环境变量自定义值。
3. Qdrant collection 的实际向量维度是否与当前 Embedding 输出一致，是否存在历史模型混用。
4. 生产是否开启查询扩展、Rerank、结构化知识单元索引或 native fallback。
5. 哪些“内部”文档被发布到 `KNOWLEDGE` scope，是否存在不应对外的内容。
6. 日志、Prompt、引用和原始文档的权限、脱敏、保留周期与备份策略。
7. 线上延迟、错误率、未命中率、转人工率和 CSAT 的真实基线。

## 八、主要代码与配置依据

- `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/DocumentParseService.java`
- `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/ChunkingService.java`
- `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/EmbeddingService.java`
- `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/RagRetrievalService.java`
- `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/QueryExpansionService.java`
- `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/RerankService.java`
- `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerServicePromptProvider.java`
- `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/client/LlmHttpClient.java`
- `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/RagEvaluationService.java`
- `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/DialogEvaluationService.java`
- `feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/service/KnowledgeIndexService.java`
- `feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/service/QdrantVectorStore.java`
- `feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/service/Bm25SearchIndex.java`
- `feisheng-bot-parent/feisheng-bot-core/src/main/resources/application.yml`
- `feisheng-bot-parent/feisheng-bot-admin/src/main/resources/application.yml`
- `feisheng-bot-parent/feisheng-bot-knowledge/src/main/resources/application.yml`
- `.env.example`、`docker-compose.yml`、`Dockerfile`、`README.md`
- `docs/evaluation-results/` 下的检索、对话和人工黑盒评测结果
