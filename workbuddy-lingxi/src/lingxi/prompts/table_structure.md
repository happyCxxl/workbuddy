识别图片中的表格结构，输出每个单元格的行列位置、合并跨度与文本。只输出一个 JSON 对象，不要输出解释或对象之外的文字。

输出结构：
{"tables": [{"rows": 2, "cols": 2, "cells": [{"row": 0, "col": 0, "rowSpan": 1, "colSpan": 1, "text": "单元格文本", "isHeader": false, "bbox": [x1, y1, x2, y2]}]}]}

row/col 从 0 开始；bbox 为相对整张图片的像素坐标 [左, 上, 右, 下]；isHeader、bbox、rowSpan、colSpan 可省略。
