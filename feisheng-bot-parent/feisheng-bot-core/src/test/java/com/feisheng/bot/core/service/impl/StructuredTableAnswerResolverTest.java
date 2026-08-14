package com.feisheng.bot.core.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredTableAnswerResolverTest {
    private static final String QUESTION = "客户是外籍人士，没有中国大陆手机号，能完成企业认证吗？";
    private static final String ANSWER = "外国友人仅支持以下方式认证：\n"
        + "[结构化表格]\n"
        + "表头：证件类型；手机认证（需要使用本人身份证办理的大陆手机号）；人脸认证（使用支付宝、快捷刷脸）；银行卡认证；人工审核认证\n"
        + "表格行：证件类型=中国居民身份证；手机认证（需要使用本人身份证办理的大陆手机号）=√（数据宝）；人脸认证（使用支付宝、快捷刷脸）=√（数据宝、腾讯、支付宝）；银行卡认证=√（数据宝、腾讯）；人工审核认证=×\n"
        + "表格行：证件类型=国际护照；手机认证（需要使用本人身份证办理的大陆手机号）=×；人脸认证（使用支付宝、快捷刷脸）=×；银行卡认证=√（数据宝）；人工审核认证=√\n"
        + "[/结构化表格]";

    @Test
    void answersSingleCellFromTheMatchingRow() {
        Optional<StructuredTableAnswerResolver.Decision> result =
            StructuredTableAnswerResolver.resolve("国际护照可以用人脸认证吗？", QUESTION, ANSWER);

        assertTrue(result.isPresent());
        assertEquals("国际护照不支持人脸认证。", result.get().answer());
    }

    @Test
    void summarizesOnlyRecordedMethodsWithoutInventingConditions() {
        Optional<StructuredTableAnswerResolver.Decision> result =
            StructuredTableAnswerResolver.resolve("国际护照没有中国大陆手机号，可以怎么认证？",
                QUESTION, ANSWER);

        assertTrue(result.isPresent());
        assertEquals("国际护照支持银行卡认证（数据宝）和人工审核认证；不支持手机认证和人脸认证。",
            result.get().answer());
    }

    @Test
    void asksForTheSpecificCredentialWhenTheQuestionNamesOnlyAClass() {
        Optional<StructuredTableAnswerResolver.Decision> result =
            StructuredTableAnswerResolver.resolve(QUESTION, QUESTION, ANSWER);

        assertTrue(result.isPresent());
        assertEquals("可以，但不同证件类型支持的认证方式不同，请提供具体证件类型，我再确认可用的认证方式。",
            result.get().answer());
    }

    @Test
    void ignoresAnUnrelatedQuestionEvenWhenTheTableWasRetrieved() {
        assertTrue(StructuredTableAnswerResolver.resolve("企业认证用对公打款，多久能通过？",
            QUESTION, ANSWER).isEmpty());
    }

    @Test
    void answersAllRequestedFieldsInACompositeQuestion() {
        Optional<StructuredTableAnswerResolver.Decision> result =
            StructuredTableAnswerResolver.resolve(
                "国际护照可以用人脸认证吗？银行卡认证可以吗？", QUESTION, ANSWER);

        assertTrue(result.isPresent());
        assertEquals("国际护照不支持人脸认证；国际护照支持银行卡认证（数据宝）。",
            result.get().answer());
        assertEquals("structured_table_fields", result.get().mode());
    }

    @Test
    void leavesACompositeQuestionUnansweredWhenOnlyOneFieldCanBeExtracted() {
        assertTrue(StructuredTableAnswerResolver.resolve(
            "国际护照可以用人脸认证吗？办理需要什么材料？", QUESTION, ANSWER).isEmpty());
    }
}
