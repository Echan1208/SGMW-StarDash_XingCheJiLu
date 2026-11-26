# 第三方组件说明

StarDash.apk 中包含以下第三方组件，它们各自遵循原有的开源许可证（本项目自身代码为 MIT 许可证）：

| 组件 | 在 App 中的位置 | 用途 | 许可证 |
|---|---|---|---|
| [ncnn](https://github.com/Tencent/ncnn) 20261020（Android 预编译版） | `lib/arm64-v8a/libncnn.so` | 侧后方来车预警的目标检测推理 | BSD 3-Clause（另含 zlib、BSD 2-Clause 等组件），全文见 [licenses/ncnn-LICENSE.txt](licenses/ncnn-LICENSE.txt) |
| [NanoDet-Plus-m 320](https://github.com/RangiLyu/nanodet) 官方权重 | `assets/nanodet.param`、`assets/nanodet.bin` | 识别行人、自行车 / 电动车、汽车等 | Apache License 2.0，Copyright 2020-2021 RangiLyu，全文见 [licenses/nanodet-LICENSE.txt](licenses/nanodet-LICENSE.txt) |

- `libncnn.so` 由 build.sh 从 ncnn 官方 Release 下载，未作修改。
- NanoDet 模型：本项目将官方发布的 `nanodet-plus-m_320.onnx` 用 pnnx 转换为 ncnn 格式（fp16 权重），未重新训练或修改网络结构。转换后的文件即 `assets/` 下的两个文件。
