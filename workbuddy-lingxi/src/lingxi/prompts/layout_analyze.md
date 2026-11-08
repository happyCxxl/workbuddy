分析图片版面：识别每个版面区域（标题、正文、表格、图片、页眉页脚、公式等），按阅读顺序输出。只输出一个 JSON 对象，不要输出解释或对象之外的文字。

输出结构：
{"layout_areas": [{"type": "text", "bbox": [x1, y1, x2, y2], "confidence": 0.95}]}

bbox 为相对整张图片的像素坐标 [左, 上, 右, 下]；layout_areas 的数组顺序即阅读顺序；confidence 可省略。
