# ginkgo-agent

面向中小团队的 **IT 服务台智能体**——员工一句话描述问题，Agent 完成理解、答复、建单、流转的全链路。

本仓库是公众号「老刘的望远镜」系列合集**「手搓企业智能体」**（第一季，E01-E12）的实战代码：build-in-public，每集一个功能模块，代码先行、跑通再写稿。产品需求见 [docs/PRD.md](docs/PRD.md)（M1-M11 与 E01-E11 一一对应）。

## 运行基线

| 项 | 要求 |
| --- | --- |
| JDK | 21（本仓库编译目标，以 pom.xml 为基准，低于 21 编译报"无效的目标发行版"；AgentScope Java 框架自身基线为 17+） |
| 构建 | Maven 4.0（E07 起以 4.0.0-rc-6 验证：`clean compile` / `dependency:build-classpath` / 验收运行全通过；传统 POM 无需改动即可在 Maven 4 下构建，3.8+ 亦可） |
| 框架 | AgentScope Java 2.0.3（E04 起从 2.0.1 升级：状态乐观并发、FinalAnswerFilterMiddleware、推理循环可靠性修复等；升级点详见系列 E04 文章） |
| 模型 | DeepSeek（OpenAI 兼容端点，默认 `deepseek-chat`） |
| 向量模型（E04 起，可选） | OpenAI 兼容 embedding 服务（默认阿里云百炼 `qwen3.7-text-embedding-flash`，可换硅基流动等；DeepSeek 官方 API 无 embeddings 端点。注意用通用端点 `dashscope.aliyuncs.com/compatible-mode/v1`，别用控制台显示的专属端点——会 404） |

## 快速开始

**1. 配置 API Key**（任选其一）：

```bash
# 方式一：环境变量
export DEEPSEEK_API_KEY=sk-你的Key

# 方式二：本地配置文件（不会入库）
cp config/application.properties.example config/application.properties
# 编辑填入 ginkgo.model.api-key
```

> E04 起知识库问答为可选功能：另配一个 OpenAI 兼容 embedding 服务的 Key
> （默认阿里云百炼 [bailian.console.aliyun.com](https://bailian.console.aliyun.com) 领取），
> 填入 `ginkgo.embedding.api-key` 或环境变量 `EMBEDDING_API_KEY` 即启用；
> 不配置时知识库功能自动关闭，其余功能不受影响。

**2. 启动对话**：

```bash
mvn compile exec:java
```

> macOS 若默认 JDK 低于 21：`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn compile exec:java`

**3. CLI 命令**：

| 输入 | 作用 |
| --- | --- |
| 任意文本 | 与 Agent 对话（流式输出，多轮上下文自动保持） |
| `/reset` | 重置会话，清空上下文重新开始 |
| `/user <name>` | 切换会话身份（E03 起会话记忆隔离；**E07 起工单数据与工具权限按身份判定**——员工仅本人工单，管理员 `it-admin` 可看全部并处理工单） |
| `/sessions` | 列出当前用户的历史会话（状态落盘于 `~/.agentscope/state/`） |
| `/resume <序号或会话ID>` | 恢复指定历史会话，重启后也能接上上次进度 |
| `/kb` / `/kb reload` | 查看知识库统计 / 重新扫描导入 `knowledge/` 目录下的文档（E04 起） |
| `/mode graph\|react` | 编排模式（E06 起）：graph=图编排（默认，意图路由/知识预检索/建单确认门由代码固化）/ react=自由对话 |
| `/perm default\|explore` | 权限模式（E07 起）：explore=只读模式——`readOnly=true` 的工具自动放行、写操作被框架权限引擎拒绝 |
| `/help` | 命令帮助 |
| `/quit` | 退出 |

**4. 试试工单查询**（E02 起）：直接问「我的工单 1024 什么状态」「我名下有哪些工单」——Agent 会自主调用 mock 工单工具（输出可见 `[调用工具 xxx]`）；问「今天天气」等无关问题不会触发工具。

**5. 试试多轮追问与会话恢复**（E03 起）：先问「我的工单 1024 什么状态」，接着问「**那它**啥时候能好」——Agent 能把「它」解析为上一轮的 1024；`/quit` 退出后重新启动，`/sessions` + `/resume` 恢复上次会话，问「刚才我们聊到哪了」可无缝接续。长对话超过 20 条消息自动压缩为「摘要 + 最近 6 条」，工单号等关键实体保留在摘要中（配置见 `AgentFactory`）。

**6. 试试知识库问答**（E04 起，需配置 embedding Key）：问「密码忘了怎么重置」「VPN 连不上怎么办」——Agent 先调用 `search_knowledge` 检索 `knowledge/` 目录下的 FAQ 文档，回答附带出处文件名；问知识库没有的问题会明确说「知识库暂无该资料」并建议建单/转人工，不编造。往 `knowledge/` 放新的 `.md` 文件后 `/kb reload` 立即生效。

**7. 试试一句话建单**（E05 起）：说「帮我开通 Confluence 的编辑权限」——Agent 判断为申请类需求，抽取分类/优先级/标题/摘要生成**工单草稿卡**展示给你，回复「确认」后才落库并返回工单号（说「优先级改成高」可修改草稿）；说「电脑坏了」这类模糊描述，Agent 会先追问现象和影响范围，不瞎猜优先级；说「不用确认直接建」也不行——确认后才落库是硬约束。

## 当前进度

| 集 | 模块 | 状态 |
| --- | --- | --- |
| E01 | M1 对话基座：CLI 多轮对话 + DeepSeek 流式输出 + 配置外置 + 会话重置 | ✅ |
| E02 | M2 工单查询工具：@Tool 注解 + Toolkit 注册 + mock 数据源（TicketStore 接口抽象） | ✅ |
| E03 | M3 会话记忆：指代消解 + 多用户会话隔离 + 会话恢复（/sessions /resume）+ 长对话压缩（CompactionConfig） | ✅ |
| E04 | M4 知识库问答：文档切分与向量化（rag-simple 扩展）+ 检索工具（带出处/相似度/未命中话术）+ /kb 管理 | ✅ |
| E05 | M5 智能建单：意图路由（查询/直答/排查/建单）+ 两阶段建单（草稿卡确认后落库）+ 结构化输出意图分类器 | ✅ |
| E06 | M6 流程编排：手搓轻量 Graph 引擎（无原生 Graph 可用）+ 服务台工作流固化（意图条件边/知识预检索/确认门节点化） | ✅ |
| E07 | M7 权限控制：会话身份贯通（RuntimeContext 注入工具）+ 数据行级权限 + IT 管理员工具 + 提示注入防护 + 官方 EXPLORE 只读模式 | 🔨 开发中 |
| E08-E11 | 见 [docs/PRD.md](docs/PRD.md) 里程碑表 | ⬜ |

## 目录结构

```
├── config/application.properties.example  # 本地配置模板（真实配置不入库）
├── docs/PRD.md                            # 产品需求说明书
├── knowledge/                             # M4 知识库文档目录（E04，往里放 .md 即导入）
└── src/main/java/com/gingko/
    ├── Main.java                          # CLI 入口：对话循环 + 流式渲染 + 工具调用事件打印
    ├── agent/AgentFactory.java            # Agent 装配工厂（sysPrompt 任务纪律 + 工具箱 + 压缩策略；E06 起图/自由双模式装配）
    ├── auth/                              # M7 身份与权限域（E07）
    │   ├── User.java                      # 会话身份（userId + 角色：员工 / IT 管理员）
    │   ├── UserDirectory.java             # mock 身份源（未注册身份 fail-safe 给最低权限）
    │   └── TicketPermission.java          # 数据行级 + 工具级权限判定与拦截话术
    ├── config/AgentConfig.java            # 配置集中加载与启动校验
    ├── flow/ServiceDeskFlow.java          # M6 服务台工作流（图编排：意图路由/预检索/确认门 + E07 身份贯通）
    ├── workflow/                          # M6 手搓轻量 Graph 引擎（E06）
    │   ├── WorkflowGraph.java             # Builder 固定边/条件边 + compile 校验 + MAX_STEPS 防死循环
    │   ├── WorkflowState.java / NodeTrace.java / NodeAction.java / GraphExecutionException.java
    ├── dev/                               # 各集自动化验收（M3/M4/M5/M6/M7）
    ├── session/SessionHistory.java        # 历史会话扫描（/sessions /resume 支撑）
    ├── intent/                            # M5 意图域（E05）
    │   ├── TicketIntent.java              # 结构化输出目标类型（record + enum）
    │   └── IntentClassifier.java          # 独立分类器：裸 ReActAgent + call(text, Class)
    ├── knowledge/                         # M4 知识库域（E04）
    │   ├── KnowledgeService.java          # 导入/重载/检索装配（SimpleKnowledge + InMemoryStore）
    │   └── KnowledgeTools.java            # @Tool 工具：search_knowledge（带出处）
    └── ticket/                            # M2/M5/M6/M7 工单域（E02 查询，E05 建单，E06 草稿箱，E07 权限）
        ├── Ticket.java                    # 工单记录（M5 扩展分类/优先级/摘要/上下文）
        ├── TicketStore.java               # 数据源接口（mock/真实存储可替换；M7 增 updateStatus/findAll）
        ├── MockTicketStore.java           # mock 数据源（测试数据 + 自增工单号）
        ├── TicketDraft.java               # 建单草稿（确认前的字段集合 + 草稿卡渲染）
        ├── TicketDraftBox.java            # 共享草稿箱（reporter → 草稿；M6 外提、M7 按会话身份分桶）
        ├── TicketTools.java               # @Tool 工具：query_ticket / list_my_tickets（M7 身份注入 + 数据过滤）
        ├── TicketDraftTools.java          # @Tool 工具：draft_ticket（图模式，M6 起）
        ├── TicketCreationTools.java       # @Tool 工具：draft/create/cancel（自由模式，M5）
        └── AdminTicketTools.java          # @Tool 工具：list_all_tickets / update_ticket_status（M7 管理员专属）
```

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
