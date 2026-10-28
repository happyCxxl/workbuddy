package com.workbuddy.common.domain.parse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 原文定位信息（溯源）：PDF 用页码 + 字符范围/对象路径；Office 用结构路径（段落索引/表格行列/sheet）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Provenance {

    /** 源文件引用（file-manager 文件 ID） */
    private String file;

    /** 定位路径：pdf#page(n)/chars[i..j]、office#document.xml/paragraph[i]、office#sheet[name]/cell[r,c] 等 */
    private String path;
}
