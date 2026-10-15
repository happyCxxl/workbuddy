package com.workbuddy.common.domain.parse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字体事实：标题层级三级级联的字体信号。
 * DOCX 有样式（Heading1/2）时由样式直接给层级；无样式时也输出本结构，层级由标题规则推定。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FontInfo {

    /** 字体名 */
    private String name;

    /** 字号（pt） */
    private Double size;

    /** 是否加粗 */
    private Boolean bold;
}
