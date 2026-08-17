package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelAnswerSignalParserTest {
    private final ModelAnswerSignalParser parser = new ModelAnswerSignalParser();

    @Test
    void acceptsStrictNoAnswerSignalOnlyOnTheFirstNonEmptyLine() {
        ModelAnswerSignalParser.ParsedAnswer parsed = parser.parse("\n  __NO_ANSWER__  \n");

        assertTrue(parsed.isNoAnswer());
        assertTrue(parsed.protocolValid());
        assertEquals("", parsed.content());
    }

    @Test
    void acceptsPartialSignalOnlyWhenItHasAnAnswerBody() {
        ModelAnswerSignalParser.ParsedAnswer parsed = parser.parse(
            "__ANSWER_PARTIAL__\n已确认支持网页端。请问您使用的是哪个手机端？");

        assertTrue(parsed.isPartial());
        assertTrue(parsed.protocolValid());
        assertEquals("已确认支持网页端。请问您使用的是哪个手机端？", parsed.content());
    }

    @Test
    void doesNotTreatSignalMentionedInsideAnAnswerAsNoAnswer() {
        ModelAnswerSignalParser.ParsedAnswer parsed = parser.parse(
            "该内部标记 __NO_ANSWER__ 不应展示给客户。");

        assertEquals(ModelAnswerSignalParser.Decision.ANSWER, parsed.decision());
        assertFalse(parsed.protocolValid());
        assertEquals("decision_signal_not_on_first_line", parsed.violation());
        assertFalse(parsed.content().contains("__NO_ANSWER__"));
    }

    @Test
    void rejectsPartialSignalWithoutBody() {
        ModelAnswerSignalParser.ParsedAnswer parsed = parser.parse("__ANSWER_PARTIAL__");

        assertEquals(ModelAnswerSignalParser.Decision.EMPTY, parsed.decision());
        assertFalse(parsed.protocolValid());
        assertEquals("partial_answer_without_body", parsed.violation());
    }

    @Test
    void flagsUnexpectedBodyAfterNoAnswerSignal() {
        ModelAnswerSignalParser.ParsedAnswer parsed = parser.parse(
            "__NO_ANSWER__\n但我还是猜测点签支持。 ");

        assertTrue(parsed.isNoAnswer());
        assertFalse(parsed.protocolValid());
        assertEquals("no_answer_with_body", parsed.violation());
    }
}
