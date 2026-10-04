# 云服务器 U²-Net 模型读取权限故障记录

## 现象

2026-10-04 云服务器部署后，多次本地去背景任务在 `ENSURE_TRANSPARENCY` 失败，后续 `STICKER` 被跳过。接口返回“本地模型无法运行，请检查权重完整性和当前平台的 ONNX Runtime 支持”。

## 排查结论

容器运行环境为 `x86_64`，Java 报告 `os.arch=amd64`、版本 `21.0.12.1`。服务日志显示：

```text
ai.onnxruntime.OrtException: Error code - ORT_FAIL
Load model from /models/u2net.onnx failed:system error number 13
```

异常发生在 `OrtSession.createSession` 加载模型时。Linux 错误码 13 表示权限拒绝；本次证据指向模型文件的读取权限，而非 CPU 架构不匹配。容器以非 root 的 `app` 用户运行，Compose 将宿主机 `./models` 以只读方式挂载到 `/models`。只读挂载允许读取，但宿主机目录和文件权限仍须允许容器用户访问。

## 修复

在服务器项目目录以 root 执行：

```bash
cd /opt/smart-closet-server
chmod 755 models
chmod 644 models/u2net.onnx
docker compose exec server sh -lc 'test -r /models/u2net.onnx && echo "model readable"'
```

权限调整后，容器内的可读性检查输出 `model readable`。模型会在首次分割请求时惰性加载，因此权限修复后重新发起一条本地去背景任务即可验证，无需仅为加载模型而重启服务。

## 后续验证

目前已确认容器用户能够读取文件；还需重新提交本地去背景任务，确认模型创建会话和完整分割流程成功。若仍失败，检查最新 `U2Net inference failed` 日志及模型校验和。仓库下载脚本 `scripts/download-model.sh` 会校验 U²-Net 文件大小和 SHA-256，避免把不完整权重误判为运行平台问题。

## 命令注意事项

把模型路径作为参数时不要在 `/models/u2net.onnx` 中间插入换行。例如：

```bash
docker compose exec server sh -lc 'ls -l /models/u2net.onnx; sha256sum /models/u2net.onnx'
```

否则 shell 会把路径拆成多个命令或参数，输出无法用于判断模型文件状态。
