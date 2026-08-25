package com.feisheng.bot.gateway.config;

import java.util.Optional;

/**
 * Supplies the enabled WeCom channel configuration at runtime.
 *
 * The gateway module keeps this contract independent from the admin database
 * module so standalone gateway tests and environment-based deployments remain
 * supported.
 */
public interface WeChatWorkConfigProvider {
    Optional<Config> activeConfig();

    record Config(String corpId, String corpSecret, String agentId,
                  String callbackToken, String callbackAesKey) {
        public boolean hasApiCredentials() {
            return hasText(corpId) && hasText(corpSecret) && hasText(agentId);
        }

        public boolean hasCallbackCredentials() {
            return hasText(corpId) && hasText(callbackToken) && hasText(callbackAesKey);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
