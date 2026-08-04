package com.feisheng.bot.core.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Safety check result.
 */
public class SafetyResult {
    private boolean blocked;
    private String action;       // BLOCK / REPLY_FIXED / HANDOFF / LOG_ONLY / PASS
    private String replyText;    // Fixed reply when action=REPLY_FIXED
    private List<String> hitRules = new ArrayList<>();

    public static SafetyResult pass() {
        SafetyResult r = new SafetyResult();
        r.blocked = false;
        r.action = "PASS";
        return r;
    }

    public static SafetyResult block(String reason) {
        SafetyResult r = new SafetyResult();
        r.blocked = true;
        r.action = "BLOCK";
        r.hitRules.add(reason);
        return r;
    }

    public static SafetyResult handoff(String reason) {
        SafetyResult r = new SafetyResult();
        r.blocked = true;
        r.action = "HANDOFF";
        r.hitRules.add(reason);
        return r;
    }

    public static SafetyResult fixedReply(String text, String reason) {
        SafetyResult r = new SafetyResult();
        r.blocked = true;
        r.action = "REPLY_FIXED";
        r.replyText = text;
        r.hitRules.add(reason);
        return r;
    }

    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean b) { blocked = b; }
    public String getAction() { return action; }
    public void setAction(String a) { action = a; }
    public String getReplyText() { return replyText; }
    public void setReplyText(String t) { replyText = t; }
    public List<String> getHitRules() { return hitRules; }
    public void setHitRules(List<String> r) { hitRules = r; }
}
