# 便携版（一键启动压缩包）

面向比赛演示与离线试用：每个平台一个压缩包（约 500 MB，大部分是模型），解压后双击启动文件即可，不需要安装 Java、
Node、数据库，也不联网下载模型。所有构建、测试与发布都在 CI 里完成：GitHub Actions（`.github/workflows/portable.yml`），或 Actions 额度不足时的 CircleCI
（`.circleci/config.yml` 的 `portable-*` 任务，步骤相同）。

本文是使用与运维说明；方案选型、各环节的设计理由、测试设计与实测数据见
[便携版一键启动设计](design/2026-10-18-便携版一键启动设计.md)。

## 1. 包里有什么

```
yuda-portable-<版本>-<平台>/
  start.bat | start.command | start.sh   每个包只放本平台的一个启动文件
  README.txt                             给使用者的说明（菜单、档位、常见问题）
  app/server.jar                         服务端（前端已构建进 static；JavaCPP 与 ONNX Runtime 只含本平台本地库，
                                         ONNX Runtime 的其他平台库与 macOS 调试符号由 portable/ci/SlimJar.java 去掉）
  app/agent/                             Agent 运行时（dist + 生产依赖，纯 JS）
  app/launcher/yuda.mjs                  启动器（只用 Node 内置模块）
  app/build-info.json                    版本、提交、平台、模型大小与 SHA-256
  runtime/java/                          jlink 裁剪的 Temurin 21（约 60 MB）
  runtime/node/                          官方 Node 24 可执行文件
  models/                                U²-Net、bge 向量 fp32/int8、Chinese-CLIP int8 图像塔与文本塔、SHA256SUMS
  LICENSES/                              第三方许可说明
运行后生成：config/closet.env（配置与密钥）、data/（数据库与图片）、logs/、backups/、runtime-cache/（本地库解压与临时文件）
```

不在系统里留下其他文件：JavaCPP 缓存与 `java.io.tmpdir` 都指向包内 `runtime-cache/`，删除文件夹即卸载。

## 2. 技术选择

| 问题 | 选择 | 原因 |
|---|---|---|
| Java 打成二进制？ | 不用 GraalVM native-image，改为 **jlink 裁剪运行时 + 按平台打包的 jar** | 项目依赖 ONNX Runtime、JavaCPP/OpenCV 的 JNI 本地库，以及 MyBatis-Plus、Flyway、H2 的大量反射，native-image 需要逐个补反射与 JNI 配置，且每次升级依赖都可能失效；jlink 方案行为与容器版完全一致，体积只多约 60 MB |
| Node | 携带官方 Node 24 可执行文件（不含 npm） | Agent 生产依赖是纯 JS，一次构建通用于所有平台；启动器也用这份 Node，不再需要第二个运行时 |
| 模型 | 打进压缩包，CI 用 `actions/cache` 在作业之间传递 | 下载与 Chinese-CLIP 导出（约 6 分钟）只做一次；缓存键与 `deploy.yml` 相同，部署时导出过的直接复用 |
| 多平台 | 每个平台在自己的 runner 上构建并测试 | 本地库（OpenCV、ONNX Runtime）、jlink 运行时、签名与压缩格式都和平台相关，只有原生 runner 能真实验证 |
| 停止服务 | 启动器写入 `data/run/shutdown`，服务端 `PortableShutdownWatcher` 每秒检查并有序退出 | Windows 上后台 java.exe 收不到信号，强杀可能丢失 H2 最近 1 秒的写入；文件方式没有网络入口。该组件只在设置 `closet.portable.shutdown-file` 时启用，容器部署不受影响 |

平台：`linux-x64`、`linux-arm64`、`macos-arm64`、`macos-x64`、`windows-x64`。Windows ARM 暂不提供（JavaCPP 没有
windows-arm64 的 OpenCV 本地库）。ONNX Runtime 1.24 起不再提供 macOS x64 本地库，`macos-x64` 包用 1.23.2 构建（`-Donnxruntime.version=1.23.2`），
其他平台与容器版一致用 1.30.0；冒烟测试在 Intel Mac runner 上实际跑 U²-Net 与向量模型验证。

## 3. 启动器

无参数运行进入菜单：启动 / 停止 / 重新启动 / 打开浏览器 / 运行状态 / 查看日志（可持续跟踪）/ 环境检查 / 模型压力评估 /
实时资源监控 / 设置（端口、访问范围、模型档位、AI 服务）/ 重置管理员密码 / 备份数据 / 退出（服务留在后台）/ 停止并退出。

也可以带命令运行（CI 冒烟测试就是这样驱动的）：

```
start [--no-browser] | stop | restart | status | doctor [--verify] | stress | monitor [秒] | logs [server|agent] [行数]
open | config [get|set KEY VALUE] | reset-password [账号] | backup | menu        通用参数：--json --yes
```

- **首次启动**：生成 `config/closet.env`，包含随机的 Agent 密钥与管理员初始密码（首次登录必须修改）。
- **初始密码提示**：管理员改密码之前，启动成功、`status` 与菜单顶部都显示账号和初始密码；改过后不再显示。
  启动器用初始密码试登录判断（随即退出登录，确认已修改后不再试），菜单「重置管理员密码」后的新密码同样提示到修改为止。
- **进程管理**：服务端与 Agent 以后台进程运行，PID 记在 `data/run/state.json`，判断存活时同时核对进程名，避免 PID 复用误判。
  启动前检查端口；任何一步失败都会停止已启动的部分，并打印对应日志最后几行。
- **停止**：先请求有序退出（最多 40 秒），再 SIGTERM，最后强制结束；`stop --json` 会报告实际是哪一种（冒烟测试要求 `graceful`）。
- **访问范围**：默认只监听 127.0.0.1；改为局域网时监听 0.0.0.0 并打印本机局域网地址（HTTP 明文，只在可信网络使用）。
- **重置密码与备份**：都要求服务停止（H2 文件锁与数据库一致性），启动器会询问后自动停止、完成后自动重启。
  重置密码复用服务端离线命令 `--closet-command=reset-password`。

### 环境检查（doctor）

平台是否匹配、操作系统、CPU、内存、磁盘空间、目录写权限、安装路径（空格；Windows 非 ASCII 路径）、macOS 隔离标记、
自带 Java 能否运行及耗时、程序文件、每个模型的大小（`--verify` 时校验 SHA-256）、端口占用、AI 服务是否配置及可连通、
当前模型档位的内存压力。任一项“失败”时退出码为 1。

### 模型压力评估（stress / monitor）

按服务端自己加载前的规则估算：每个 ONNX 会话常驻约“文件大小 × 1.5”，加上 Java 堆与非堆、Agent。分别给出闲置（常驻部分）
与峰值（全部模型同时加载）内存，对比本机总内存与可用内存评为“轻松 / 适中 / 吃紧 / 不足”，CPU 按核数评估并决定推理线程数
（核数一半，1–4）。`monitor` 每 2 秒采样服务端与 Agent 的实际内存、CPU 与系统可用内存，用于录入或搜索时观察真实压力。

| 档位 | 文本向量 | 图文模型 | Java 堆上限 | 闲置卸载（抠图/图像/文本塔） | 自动选择条件 |
|---|---|---|---|---|---|
| full 完整 | fp32 | 开 | 2048 MB | 10m / 10m / 30m | 内存 ≥ 7.5 GB |
| balanced 均衡 | int8 | 开 | 1280 MB | 5m / 5m / 10m | 3.5–7.5 GB |
| lite 精简 | int8 | 关（相似衣物改用颜色轮廓，文字找图关闭） | 768 MB | 2m / 1m / 1m | < 3.5 GB |

`config/closet.env` 中显式写的 `CLOSET_*` 变量优先于档位默认值。

## 4. 工作流

```
prepare ─┬─ frontend（私有前端仓库，npm test + build:server）─┐
         ├─ agent（npm test + build，裁剪为生产依赖）─────────┤
         ├─ models（下载/导出，存入跨系统缓存）────────────────┼─ package × 5 平台 ─┐
         └─ server-tests（mvn verify，可关）──────────────────────────────────────┴─ release（标签 v* 或手动勾选）
```

每个平台的 package 作业：按平台构建 jar → `portable/ci/assemble.mjs` 组装（jlink、复制 Node 与模型、写 build-info）→
压缩（Linux tar.gz；macOS 用 ditto 生成 zip 保留可执行权限；Windows 7z zip）→ **重新解压**并用包内 Node 运行
`portable/ci/smoke.mjs`：

1. `doctor --verify` 无失败项；`stress` 给出档位
2. `start` 后检查服务端与 Agent 健康、前端页面与静态资源
3. 用首次生成的管理员登录、修改密码
4. GrabCut 与 U²-Net 抠图各一次（验证本平台 OpenCV 与 ONNX Runtime 本地库），等待文本向量模型加载日志，`monitor` 采样
5. 用管道驱动交互菜单（状态、模型压力、0 退出后服务仍在）
6. `restart`；`stop` 必须是有序退出且服务端日志有对应记录、端口已释放
7. 停止状态下 `reset-password`、`backup`；再启动用新密码登录；菜单 `q` 停止并退出

失败时上传该平台的 `logs/` 与配置；成功时上传压缩包（保留 5 天），作业摘要列出大小与冒烟测试输出。

**触发**：修改 `portable/**`、本工作流或 `com.smartcloset.portable` 时自动运行；推送 `v*` 标签时构建并把压缩包与
`SHA256SUMS.txt` 附加到该标签的 Release；手动运行（Actions → Portable package → Run workflow）可选平台、前端分支、
是否跑服务端测试、是否发布到预发布版本 `portable-latest`（每次重建，只保留最新一份）。

**CircleCI**：推送 `v*` 标签时随 `release` 工作流一起构建并发布；手动在 CircleCI 项目页 Trigger Pipeline，加参数
`portable=true`（可选 `portable_platforms`、`frontend_ref`、`portable_release=true`）。压缩包在各 `portable-<平台>` 任务的
Artifacts 里下载，失败日志在 `logs-<平台>`。CircleCI 没有 Intel Mac，`macos-x64` 包在 Apple Silicon 上用 Rosetta 运行
x64 的 Node 与 JDK 构建和冒烟测试；工具链由 `portable/ci/circleci-tools.sh` 按目标平台下载。环境变量（`FRONTEND_DEPLOY_KEY`，
发布还需带 `repo` 权限的 `GHCR_TOKEN` 或 `GITHUB_RELEASE_TOKEN`）在 CircleCI 项目设置里配置。

**依赖的 secret**：`FRONTEND_DEPLOY_KEY`，前端私有仓库 `yumengjh/smart-closet` 的只读 deploy key（2026-10-18 已配置；
轮换时在前端仓库 Settings → Deploy keys 新建只读 key，并更新本仓库 secret）。

**费用提示**：仓库为私有时，macOS runner 按 10 倍、Windows 按 2 倍计费分钟；每个压缩包约 500 MB，工作流产物计入
Actions 存储配额（保留期已设为 5 天），正式分发请用 Release。

## 5. 已知限制

- 未做代码签名与公证：macOS 第一次需右键 `start.command` → 打开（脚本随后自动清除整个目录的隔离标记）；Windows 可能出现
  “已保护你的电脑”，点“更多信息 → 仍要运行”。
- 局域网模式是 HTTP；便携登录链接等要求 HTTPS 的功能只在本机地址可用。
- AI 服务（识别、Agent、生图）需要使用者自己在设置里填写服务地址与密钥；不填时本地功能照常可用。
