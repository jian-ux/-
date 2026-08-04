# DingTalk Outgoing Robot Integration Design

## Goal

Complete the existing DingTalk outgoing robot callback path so a user can mention the robot in a DingTalk group, have the message processed by the existing Feisheng Bot dialog engine, and receive a text reply in DingTalk.

## Scope

This iteration supports text messages only. It does not add image, file, card, Stream mode, or proactive notification delivery.

## Existing Context

The project already has a DingTalk gateway endpoint at `/gateway/channel/dingtalk/message`. It verifies DingTalk callback signatures, converts the incoming payload into `ChannelMessageDTO`, and sends it through `ChannelServiceImpl` into `DialogServiceImpl`.

The missing piece is the response contract. The controller currently returns the project's generic `R.ok(...)` wrapper, but DingTalk outgoing robots expect a robot message payload such as text `msgtype` plus `text.content`.

## Design

`DingTalkController` will become responsible for adapting DingTalk callback payloads to and from the internal channel abstraction.

Inbound:

- Validate `timestamp` and `sign` when `DINGTALK_APP_SECRET` is configured.
- Parse the DingTalk message body defensively.
- Extract a stable user key from `senderId`, falling back to `conversationId`.
- Extract text content from `content.content`.
- Generate a deduplication message id from `msgId`, falling back to a deterministic channel/message key if needed.

Internal processing:

- Pass the message to `ChannelServiceImpl.processMessage`.
- Reuse the existing Redis deduplication and dialog engine path.
- Treat duplicate messages as successful no-op callbacks with a short text response.

Outbound:

- Return DingTalk-compatible text JSON:

```json
{
  "msgtype": "text",
  "text": {
    "content": "..."
  }
}
```

- Use the `reply` field from the dialog result.
- Fall back to a clear service-unavailable message if the dialog result is missing or an exception occurs.

## Error Handling

- Invalid signature returns HTTP 401 with the existing project error wrapper.
- Empty text returns a DingTalk text response asking the user to send text content.
- Processing exceptions are logged and converted to a DingTalk text response so the robot callback does not silently fail.

## Testing

Add focused unit tests for:

- DingTalk signature calculation and verification.
- Controller mapping from callback body to DingTalk text response.
- Empty message fallback.
- Invalid signature rejection when `DINGTALK_APP_SECRET` is configured.

Tests should avoid calling real DingTalk or real LLM providers. The controller test will mock the internal channel service boundary.
