package com.feisheng.bot.admin.service;

import com.feisheng.bot.common.util.StructuredTableUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructuredTableUtilTest {
    @Test
    void roundTripsRowsWithoutUsingTabs() {
        String text = StructuredTableUtil.serialize(List.of(
            List.of("证件类型", "手机认证", "银行卡认证"),
            List.of("国际护照", "×", "√ (数据宝)")));

        assertEquals("[结构化表格]\n"
                + "表头：证件类型；手机认证；银行卡认证\n"
                + "表格行：证件类型=国际护照；手机认证=×；银行卡认证=√ (数据宝)\n"
                + "[/结构化表格]", text);
        StructuredTableUtil.Table table = StructuredTableUtil.parse(text);
        assertEquals(List.of("证件类型", "手机认证", "银行卡认证"), table.headers());
        assertEquals("×", table.rows().get(0).get("手机认证"));
        assertEquals("√ (数据宝)", table.rows().get(0).get("银行卡认证"));
    }

    @Test
    void rejectsNonRectangularOrAmbiguousHeaderRows() {
        assertThrows(IllegalArgumentException.class, () -> StructuredTableUtil.serialize(List.of(
            List.of("证件类型", "认证", "认证"),
            List.of("国际护照", "×", "√"))));
        assertThrows(IllegalArgumentException.class, () -> StructuredTableUtil.serialize(List.of(
            List.of("证件类型", "认证"),
            List.of("国际护照"))));
    }
}
