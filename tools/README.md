# tools · 真机勘探脚本

这些脚本用于真机勘探（dump 节点、探弹窗、验证滚动、任务普查等），**不属于 App 运行时代码**。

## 环境变量

脚本不再写死本机 adb 路径和设备序列号，统一从环境变量读取：

| 变量 | 说明 | 默认 |
|---|---|---|
| `ADB` | adb 可执行文件路径 | `PATH` 里的 `adb` |
| `ADB_SERIAL` | 设备序列号；多设备时指定，单设备可省略 | 空（不传 `-s`） |

示例：

```bash
export ADB_SERIAL=YOUR_DEVICE_SERIAL   # 可选
python tools/explore.py dump
python tools/explore.py tap 720 1474
bash tools/dump_nodes.sh
```

> 旧版脚本曾硬编码本机 adb 路径与设备序列号，已改为环境变量方式；
> 需要多设备并行时给每个命令单独设置 `ADB_SERIAL` 即可。
