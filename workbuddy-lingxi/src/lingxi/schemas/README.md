# 业务 schema 目录

模型结构化输出的 JSON Schema 统一放这里（随包放在 `src/lingxi/schemas/`），文件名即 `schema_key`。
调用方只传 `schema_key`，网关加载后按模型能力走 strict 直通或校验-重试兜底，不再由调用方携带 schema 文案。

约定：

- 文件为 draft 2020-12 的 JSON Schema；加载器 `lingxi.core.schemas` 在加载时做 `check_schema` 校验，
  不合法直接在调用时暴露，不让坏 schema 流到运行时。
- 新增 schema：加 `.json` 文件并在此表登记；不要写回调用方代码常量。
- 改 schema 结构时：同步登记表的消费方（如 Java 侧响应映射与单测），跨仓契约由防御解析与单测守住。

| 文件              | 用途                              | 消费方                                    | 结构要点                                        |
|-------------------|-----------------------------------|-------------------------------------------|-------------------------------------------------|
| `ocr_lines.json`  | OCR 行识别输出（解析环节扫描/乱码页补文字） | Java `ModelGatewayStructuredOcrProvider` | `text_lines[]`：`text` + `bbox`（像素坐标，4 元）+ `confidence`（可选） |
| `layout.json`     | 版面区域输出（复杂页阅读顺序补强）       | Java `ModelGatewayStructuredLayoutProvider` | `layout_areas[]`：`type` + `bbox`（像素坐标）+ `confidence`（可选） |
| `table.json`      | 表格结构输出（规则失败表格补强）         | Java `ModelGatewayStructuredTableProvider` | `tables[]`：`rows`/`cols` + `cells[]`（`row`/`col`/`rowSpan`/`colSpan`/`text`/`isHeader`/`bbox`） |
