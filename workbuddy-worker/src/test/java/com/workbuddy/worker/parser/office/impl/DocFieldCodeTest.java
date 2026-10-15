package com.workbuddy.worker.parser.office.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DOC 域代码剥离单测：`0x13` 起始到 `0x14` 分隔之间是域指令（丢弃），`0x14` 之后是域结果（保留），
 * `0x15` 结束控制符清掉；无域的文本原样返回。
 *
 * @author cxxl
 */
class DocFieldCodeTest {

    /** PAGE 域：指令丢弃、结果保留、控制符清掉 */
    @Test
    void pageFieldShouldKeepResultText() {
        assertEquals("第  3  页", DocDocumentParser.stripFieldCodes("第 \u0013PAGE\u0014 3 \u0015 页"));
    }

    /** HYPERLINK 域：指令（含地址）丢弃，只留显示文本 */
    @Test
    void hyperlinkFieldShouldKeepDisplayText() {
        assertEquals("点击这里", DocDocumentParser.stripFieldCodes("\u0013HYPERLINK \"http://a\"\u0014点击这里\u0015"));
    }

    /** 无域文本：原样返回（含为空与 null） */
    @Test
    void plainTextShouldStayUnchanged() {
        assertEquals("普通段落", DocDocumentParser.stripFieldCodes("普通段落"));
        assertEquals("", DocDocumentParser.stripFieldCodes(""));
        assertEquals(null, DocDocumentParser.stripFieldCodes(null));
    }

    /** 未闭合的域起始（只有 0x13）：余下内容按指令处理，不产生半个正文 */
    @Test
    void unclosedFieldShouldDropInstruction() {
        assertEquals("", DocDocumentParser.stripFieldCodes("\u0013PAGE"));
    }
}
