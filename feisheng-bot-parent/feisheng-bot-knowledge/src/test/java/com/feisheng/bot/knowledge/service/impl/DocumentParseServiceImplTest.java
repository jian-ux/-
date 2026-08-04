package com.feisheng.bot.knowledge.service.impl;

import com.feisheng.bot.knowledge.entity.BotKnowledgeItem;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentParseServiceImplTest {

    @Mock
    private BotKnowledgeItemMapper mapper;

    @InjectMocks
    private DocumentParseServiceImpl parser;

    @Captor
    private ArgumentCaptor<BotKnowledgeItem> itemCaptor;

    @Test
    void testParseTxtFile_ValidContent_ImportsCorrectly() throws Exception {
        String content = "退货流程是什么,您可以在订单页面申请退款。,退货,退款\n"
                       + "如何申请退款,进入我的订单点击申请退款。,退款,申请\n"
                       + "# 这是一条注释\n"
                       + "// 这也是注释\n"
                       + "\n"
                       + "发货时间多久,现货商品24小时内发货。,发货,物流";

        MockMultipartFile file = new MockMultipartFile(
            "file", "test_faq.txt", "text/plain",
            content.getBytes(StandardCharsets.UTF_8));

        int count = parser.parseAndImport(file, 1L);

        assertEquals(3, count, "应该导入3条有效条目");
        verify(mapper, times(3)).insert(itemCaptor.capture());

        List<BotKnowledgeItem> items = itemCaptor.getAllValues();
        assertEquals("退货流程是什么", items.get(0).getQuestion());
        assertEquals("您可以在订单页面申请退款。", items.get(0).getAnswer());
        assertEquals("退货,退款", items.get(0).getKeywords());
        assertEquals(Long.valueOf(1L), items.get(0).getCategoryId());
        assertEquals(Integer.valueOf(1), items.get(0).getStatus());

        assertEquals("发货时间多久", items.get(2).getQuestion());
        assertEquals("发货,物流", items.get(2).getKeywords());
    }

    @Test
    void testParseTxtFile_EmptyContent_ReturnsZero() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "empty.txt", "text/plain", "".getBytes());
        int count = parser.parseAndImport(file, 1L);
        assertEquals(0, count);
        verify(mapper, never()).insert(any(BotKnowledgeItem.class));
    }

    @Test
    void testParseTsvFile_TabSeparated_ImportsCorrectly() throws Exception {
        String content = "问题1\t答案1\t关键词1\n问题2\t答案2\t关键词2\n";
        MockMultipartFile file = new MockMultipartFile(
            "file", "test.tsv", "text/plain",
            content.getBytes(StandardCharsets.UTF_8));

        int count = parser.parseAndImport(file, 1L);
        assertEquals(2, count);
        verify(mapper, times(2)).insert(itemCaptor.capture());
        assertEquals("问题1", itemCaptor.getAllValues().get(0).getQuestion());
    }

    @Test
    void testParseUnsupportedFile_ThrowsException() {
        MockMultipartFile file = new MockMultipartFile(
            "file", "test.doc", "application/msword", "fake".getBytes());
        assertThrows(UnsupportedOperationException.class, () -> {
            parser.parseAndImport(file, 1L);
        });
    }
}
