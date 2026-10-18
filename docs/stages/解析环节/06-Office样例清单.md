# 解析环节 · Office 样例清单（复核用）

本文件说明 `docs/stages/解析环节/05-待办清单.md` 第四节各条目对应的**复核样例**、期望产物与复现命令。
样例文件落在 `samples/office-parse-NN/`（该目录按 `.gitignore` **不入库**），生成脚本与核对程序入库。

## 一、目录约定

| 目录                       | 对应条目                    | 文件                                                                        |
|----------------------------|-----------------------------|-----------------------------------------------------------------------------|
| `samples/office-parse-01/` | OFF-01 PPT/PPTX 接入        | `01-一页含文本框表格与图片.pptx`                                            |
| `samples/office-parse-02/` | OFF-02 段落序号             | `01-中间空段.docx`                                                          |
| `samples/office-parse-03/` | OFF-03 疑似图片判定         | `01-只有一张图无文字.docx`、`02-有图但正文很多.docx`                        |
| `samples/office-parse-04/` | OFF-04 图片归属单元         | `01-长正文后带图片.docx`                                                    |
| `samples/office-parse-05/` | OFF-05 预算含表格与页眉页脚 | `01-只有表格.docx`、`02-只有页眉.docx`                                      |
| `samples/office-parse-06/` | OFF-06 表头行               | `01-首行空数据从第二行.xlsx`、`02-表头在首行.xlsx`                          |
| `samples/office-parse-07/` | OFF-07 列数口径             | `01-首行空三列.xlsx`、`02-窄标题行四列.xlsx`                                |
| `samples/office-parse-08/` | OFF-08 合并格文本不丢       | `01-横向合并被覆盖格带文本.xlsx`、`02-纵向合并续接格带文本.docx`            |
| `samples/office-parse-09/` | OFF-09 表头判定             | `01-首行有文本的表.docx`、`02-首行全空的表.docx`                            |
| `samples/office-parse-10/` | OFF-10 页眉页脚             | `01-页眉页脚.docx`、`02-页眉三区与格式码.xlsx`                              |
| `samples/office-parse-11/` | OFF-11 Excel 图片           | `01-内嵌一张图.xlsx`                                                        |
| `samples/office-parse-12/` | OFF-12 公式缓存优先         | `01-公式缓存7现算2.xlsx`                                                    |
| `samples/office-parse-13/` | OFF-13 `.xls` 公式缓存留痕  | `01-旧格式公式缓存.xls`                                                     |
| `samples/office-parse-14/` | OFF-14 DOCX 文本框          | `01-文本框文字.docx`                                                        |
| `samples/office-parse-15/` | OFF-15 `.doc` 域代码        | **无样例**，目录内为说明（POI 无法写出旧二进制 .doc，环境亦无 LibreOffice） |

## 二、期望产物（复核要点）

| 条目   | 关键期望                                                                                                                                   |
|--------|--------------------------------------------------------------------------------------------------------------------------------------------|
| OFF-01 | 单元 = 幻灯片；一页产出 `PARAGRAPH`（文本）+ `TABLE`（`rows=2 cols=2 headerRow=0`）+ `IMAGE`；每页一条 `OCR_IMAGE` 事实；指标 `images=1`   |
| OFF-02 | 段落溯源为 `office#document.xml/paragraph[0]` 与 `paragraph[2]`（空段占号不错位）                                                          |
| OFF-03 | 只有图的单元 `chars=0 images=1`（走图片数 + 字符数判定）；有图且有正文的单元 `chars` 足够大（不判疑似）                                    |
| OFF-04 | 正文折算成多个单元，**图片记在它所在的最后一个单元**（不是首个单元）                                                                       |
| OFF-05 | 只有表格时单元 `chars=12`（单元格文本计入）；只有页眉时单元 `chars=4`（页眉计入）                                                          |
| OFF-06 | 首行空 → `headerRow=1`；表头在首行 → `headerRow=0`                                                                                         |
| OFF-07 | 首行空三列 → `cols=3`；窄标题行四列 → `cols=4`（不被首行截短，且不为负数）                                                                 |
| OFF-08 | 覆盖格/续接格文本并入锚格：Excel 合并区锚格文本含「合并块 + 被覆盖值1 + 被覆盖值2」；DOCX 起始格文本含「A1 报价 + 续接文本」且 `rowSpan=2` |
| OFF-09 | 首行有文本 → `headerRow=0`；首行全空 → `headerRow=null` 且无格标 `isHeader`                                                                |
| OFF-10 | DOCX 页眉页脚产 `HEADER`/`FOOTER` 元素；XLSX 页眉三区合成一条且格式码被清掉（`&"Arial,Bold"`、`&P`、`&N` 去除、`&&` 还原为 `&`）           |
| OFF-11 | `IMAGE`（`assetRef` 形如 `image-0.png`、`needsOcr=true`）+ 每表一条 `OCR_IMAGE` 事实                                                       |
| OFF-12 | 公式格取**缓存显示值**：`01` 号样例应得 `7`，而不是现算的 `2`                                                                              |
| OFF-13 | `.xls` 有公式格时路说明含「旧格式公式缓存可能未计算（本档 N 个公式格按缓存值取值）」                                                       |
| OFF-14 | 文本框文字进入段落文本，且**只出现一次**（真实样例曾暴露重复采集，已修）                                                                   |

## 三、本次核对结论（样例产出摘要）

- OFF-01：`PARAGRAPH`/`TABLE(2×2, headerRow=0)`/`IMAGE` + 事实 + `images=1` ✓
- OFF-02：`paragraph[0]` 与 `paragraph[2]` ✓
- OFF-03：`chars=0 images=1` 与 `chars=651 images=1` ✓
- OFF-04：5 个单元（3000/3000/3000/3000/191），**图片记在第 5 单元** ✓
- OFF-05：只有表格 `chars=12`；只有页眉 `chars=4` ✓
- OFF-06 / OFF-07：`headerRow=1`、`cols=3`；窄标题行 `cols=4` ✓
- OFF-08：Excel 覆盖格文本并入锚格（单元 `chars=19`）；DOCX 起始格含续接文本（`cells=5`）✓
- OFF-09：`headerRow=0` 与 `headerRow=null` ✓
- OFF-10：DOCX 页眉页脚计入预算（`chars=12`）；XLSX 页眉产 `HEADER`/`FOOTER` ✓
- OFF-11：`IMAGE asset=image-0.png` + 事实 ✓
- OFF-12：见核对输出里该文件路径下的单元格文本（应为 `7`）✓
- OFF-13：路说明「旧格式公式缓存可能未计算（本档 1 个公式格按缓存值取值）」✓
- OFF-14：文本框文字进入段落且只出现一次 ✓（**该样例曾暴露重复采集问题，已随本轮修复**）

## 四、未覆盖与限制

1. **OFF-15（`.doc` 域代码）无样例**：POI 的 HWPF 只能读不能写旧二进制格式，当前环境 PATH 与 DSH 资源里也没有 LibreOffice 可做 `--convert-to doc`；该条目的验证仍停在「纯函数单测 + 代码级核实」。
2. **DOC（`.doc`）相关的页面级改动**（段落样式、页眉页脚、表格 `cols`）同样没有真实文件回归，原因同上。
3. 样例只覆盖 `samples/` 可达的形态；真实业务文件若出现别的排布（如既有文本框又有正文的复杂页），建议按目录约定补一份样例再复核。
