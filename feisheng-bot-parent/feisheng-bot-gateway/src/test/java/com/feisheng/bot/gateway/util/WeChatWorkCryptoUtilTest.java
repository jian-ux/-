package com.feisheng.bot.gateway.util;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeChatWorkCryptoUtilTest {
    private static final String AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private static final String TOKEN = "callback-token";
    private static final String CORP_ID = "ww-test-corp";

    @Test
    void encryptedPayloadRoundTripPreservesMessage() throws Exception {
        WeChatWorkCryptoUtil crypto = new WeChatWorkCryptoUtil(AES_KEY, TOKEN, CORP_ID);
        String timestamp = "1720588800";
        String nonce = "nonce-1";
        String message = "<xml><Content><![CDATA[你好]]></Content></xml>";

        Document envelope = parseXml(crypto.encryptReply(message, timestamp, nonce));
        String encrypted = value(envelope, "Encrypt");
        String signature = value(envelope, "MsgSignature");

        assertTrue(crypto.verifySignature(signature, timestamp, nonce, encrypted));
        assertEquals(message, crypto.decryptMsg(encrypted, signature, timestamp, nonce));
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        WeChatWorkCryptoUtil crypto = new WeChatWorkCryptoUtil(AES_KEY, TOKEN, CORP_ID);
        String timestamp = "1720588800";
        String nonce = "nonce-2";
        Document envelope = parseXml(crypto.encryptReply("echo", timestamp, nonce));

        assertThrows(SecurityException.class, () -> crypto.decryptMsg(
            value(envelope, "Encrypt"), "invalid", timestamp, nonce));
    }

    @Test
    void rejectsPayloadForDifferentCorpId() throws Exception {
        WeChatWorkCryptoUtil sender = new WeChatWorkCryptoUtil(AES_KEY, TOKEN, "ww-other-corp");
        WeChatWorkCryptoUtil receiver = new WeChatWorkCryptoUtil(AES_KEY, TOKEN, CORP_ID);
        String timestamp = "1720588800";
        String nonce = "nonce-3";
        Document envelope = parseXml(sender.encryptReply("echo", timestamp, nonce));

        assertThrows(SecurityException.class, () -> receiver.decryptMsg(
            value(envelope, "Encrypt"), value(envelope, "MsgSignature"), timestamp, nonce));
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String value(Document document, String tag) {
        return document.getElementsByTagName(tag).item(0).getTextContent();
    }
}
