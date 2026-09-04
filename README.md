# ginkgo-agent

面向中小团队的 **IT 服务台智能体**——员工一句话描述问题，Agent 完成理解、答复、建单、流转的全链路。

本仓库是公众号「老刘的望远镜」系列合集**「手搓企业智能体」**（第一季，E01-E12）的实战代码：build-in-public，每集一个功能模块，代码先行、跑通再写稿。产品需求见 [docs/PRD.md](docs/PRD.md)（M1-M11 与 E01-E11 一一对应）。

## 运行基线

| 项 | 要求 |
| --- | --- |
| JDK | 21（本仓库编译目标，以 pom.xml 为基准，低于 21 编译报"无效的目标发行版"；AgentScope Java 框架自身基线为 17+） |
| 构建 | Maven 3.6+ |
| 框架 | AgentScope Java 2.0.1 |
| 模型 | DeepSeek（OpenAI 兼容端点，默认 `deepseek-chat`） |

## 快速开始

**1. 配置 API Key**（任选其一）：

```bash
# 方式一：环境变量
export DEEPSEEK_API_KEY=sk-你的Key

# 方式二：本地配置文件（不会入库）
cp config/application.properties.example config/application.properties
# 编辑填入 ginkgo.model.api-key
```

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
| `/quit` | 退出 |

**4. 试试工单查询**（E02 起）：直接问「我的工单 1024 什么状态」「我名下有哪些工单」——Agent 会自主调用 mock 工单工具（输出可见 `[调用工具 xxx]`）；问「今天天气」等无关问题不会触发工具。

## 当前进度

| 集 | 模块 | 状态 |
| --- | --- | --- |
| E01 | M1 对话基座：CLI 多轮对话 + DeepSeek 流式输出 + 配置外置 + 会话重置 | ✅ |
| E02 | M2 工单查询工具：@Tool 注解 + Toolkit 注册 + mock 数据源（TicketStore 接口抽象） | ✅ |
| E03-E11 | 见 [docs/PRD.md](docs/PRD.md) 里程碑表 | ⬜ |

## 目录结构

```
├── config/application.properties.example  # 本地配置模板（真实配置不入库）
├── docs/PRD.md                            # 产品需求说明书
└── src/main/java/com/gingko/
    ├── Main.java                          # CLI 入口：对话循环 + 流式渲染 + 工具调用事件打印
    ├── config/AgentConfig.java            # 配置集中加载与启动校验
    └── ticket/                            # M2 工单域（E02）
        ├── Ticket.java                    # 工单记录
        ├── TicketStore.java               # 数据源接口（mock/真实存储可替换）
        ├── MockTicketStore.java           # mock 数据源（内嵌测试数据）
        └── TicketTools.java               # @Tool 工具：query_ticket / list_my_tickets
```

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
