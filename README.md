# AxiInfra

AXI 基础设施 IP（Reorder / Buffer / Width Cvt / Field Adapter / Async / XBar 子系统等）。  
源码为 Chisel，通过 `xmake rtl` + mill 生成 SystemVerilog。

## 依赖

- [xmake](https://xmake.io) ≥ 2.9
- mill（仓库自带 `.mill-version`）
- JDK 17+（与 mill / Chisel 一致）
- firtool（由 Chisel 7.9.0 对应版本自动拉取，当前为 1.140.0）

## 初始化

```bash
cd AxiInfra
xmake init -P .          # git submodule update --init
xmake comp -P .          # 可选：先编译 main + test（Scala）
```

> 在父工程目录时务必加 `-P .`（或 `-P AxiInfra`），否则 xmake 会落到上层 `TestAXIXBar` 工程。

## 构建 RTL

### 基本用法

```bash
cd AxiInfra

# 默认生成 Lmss 子系统顶层 → build/rtl
xmake rtl -P .

# 按模块生成（-M / --main-function）
xmake rtl -M AxiReorder -P .
xmake rtl -M AxiBuffer  -P .

# 指定输出根目录（默认 build）
xmake rtl -M AxiReorder -b build -P .
```

等价于调用：

```text
mill -i test.runMain generator.<Name>Top \
  --throw-on-first-error \
  --target systemverilog \
  --split-verilog \
  --full-stacktrace \
  -td <build-dir>/<rtl-subdir>
```

### 可选模块（`-M`）

| `-M` 参数 | Generator 入口 | 输出目录 | 说明 |
|-----------|----------------|----------|------|
| `Lmss`（默认） | `generator.LmssTop` | `build/rtl` | 完整 LMSS 子系统 `AxiSubsysTop` |
| `AxiBridgeCfg` | `generator.AxiBridgeCfgTop` | `build/rtl_bridge_cfg` | CFG 桥 1S×3M |
| `AxiReorder` | `generator.AxiReorderTop` | `build/rtl_AxiReorder` | AXI 重排序 |
| `AxiBuffer` | `generator.AxiBufferTop` | `build/rtl_AxiBuffer` | 单级缓冲 |
| `AxiBufferChain` | `generator.AxiBufferChainTop` | `build/rtl_AxiBufferChain` | 缓冲链 |
| `AxiFieldAdapter` | `generator.AxiFieldAdapterTop` | `build/rtl_AxiFieldAdapter` | 非数据域字段适配 |
| `AxiNarrowToWide` | `generator.AxiNarrowToWideTop` | `build/rtl_AxiNarrowToWide` | 窄→宽数据位宽转换 |
| `AxiWideToNarrow` | `generator.AxiWideToNarrowTop` | `build/rtl_AxiWideToNarrow` | 宽→窄数据位宽转换 |
| `AxiErrorDevice` | `generator.AxiErrorDeviceTop` | `build/rtl_AxiErrorDevice` | DECERR 从设备 |
| `AxiLite2Axi` | `generator.AxiLite2AxiTop` | `build/rtl_AxiLite2Axi` | AXI-Lite → AXI |
| `AxiAsyncSource` | `generator.AxiAsyncSourceTop` | `build/rtl_AxiAsyncSource` | 异步跨时钟 Source |
| `AxiAsyncSink` | `generator.AxiAsyncSinkTop` | `build/rtl_AxiAsyncSink` | 异步跨时钟 Sink |

兼容别名：

```bash
xmake rtl_bridge_cfg -P .    # 等价于 xmake rtl -M AxiBridgeCfg -P .
```

### 常用示例

```bash
# 1) 只出 AxiReorder
xmake rtl -M AxiReorder -P .
# → build/rtl_AxiReorder/AxiReorder.sv 及子模块

# 2) CFG 桥（父工程 TestAXIXBar 会用到）
xmake rtl -M AxiBridgeCfg -P .
# 或
xmake rtl_bridge_cfg -P .
# → build/rtl_bridge_cfg/

# 3) 宽窄转换
xmake rtl -M AxiWideToNarrow -P .
xmake rtl -M AxiNarrowToWide -P .

# 4) 自定义 build 目录
xmake rtl -M AxiReorder -b /tmp/my_rtl -P .
# → /tmp/my_rtl/rtl_AxiReorder/
```

### 帮助

```bash
xmake rtl --help -P .
```

## 其它任务

| 命令 | 作用 |
|------|------|
| `xmake init -P .` | 初始化 git submodule |
| `xmake comp -P .` | mill 编译 main + test |
| `xmake idea -P .` | 生成 IntelliJ 工程 |

## 直接用 mill（可选）

不经过 xmake 时：

```bash
cd AxiInfra
mill -i test.runMain generator.AxiReorderTop \
  --throw-on-first-error \
  --target systemverilog \
  --split-verilog \
  --full-stacktrace \
  -td build/rtl_AxiReorder
```

把 `AxiReorderTop` 换成上表中的其它 `*Top` 即可。

## 默认参数说明

各 standalone 顶层的默认参数写在  
`src/test/scala/generator/Generator.scala`，例如：

- **AxiReorder**：`AxiParams()`（48b addr / 12b id / 256b data），`buffer = 16`
- **AxiBuffer**：`depth = 2`
- **AxiNarrowToWide**：mst 128b → slv 256b，`buffer = 8`
- **AxiWideToNarrow**：mst 256b → slv 128b，`buffer = 8`
- **AxiLite2Axi**：`AxiLiteParams(addrBits=48, dataBits=64)`（`lastBits=0`）

改参数后重新 `xmake rtl -M <Name> -P .` 即可。

## 目录结构（简要）

```text
AxiInfra/
├── src/main/scala/xs/infra/axi/   # DUT：AxiReorder / Buffer / WidthCvt ...
├── src/test/scala/generator/      # RTL 生成入口 *Top
├── xmake.lua                      # rtl / comp / init / idea
├── build.sc                       # mill 工程
└── build/                         # 生成 RTL 输出
    ├── rtl/                       # Lmss
    ├── rtl_bridge_cfg/            # AxiBridgeCfg
    └── rtl_<Module>/              # 其它独立模块
```
