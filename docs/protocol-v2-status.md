# BandKey 协议 v2：状态查询与最终解锁回执

协议 v2 修正了两个状态语义：

1. 手机不能把本地配对记录当作电脑已就绪，必须通过 `status` 查询真实状态。
2. `unlock_result` 只表示请求已验签并投递，最终是否登录成功必须通过 `unlock_status` 查询。

## 状态查询

请求：

```json
{ "type": "status", "payload": { "deviceId": "..." } }
```

响应：

```json
{
  "type": "status_result",
  "payload": {
    "protocolVersion": 2,
    "serverVersion": "1.8.1",
    "pcName": "DESKTOP-ABC",
    "online": true,
    "paired": true,
    "revoked": false,
    "credentialSet": true,
    "credentialRejected": false,
    "credentialProviderConnected": true,
    "isLocked": true,
    "unlockReady": true,
    "serverTime": 1762777890
  }
}
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `protocolVersion` | 服务端协议版本，手机低于 2 时提示升级电脑端 |
| `pcName` | Windows 计算机名，用于手机端展示真实目标 |
| `paired` | 当前 `deviceId` 是否仍存在于电脑设备列表 |
| `revoked` | 请求携带了 `deviceId` 但电脑已删除该设备 |
| `credentialSet` | 本机是否保存了可用的 Windows 解锁凭据 |
| `credentialRejected` | Windows 是否已拒绝保存的凭据 |
| `credentialProviderConnected` | 锁屏凭据提供程序是否已连接服务 |
| `isLocked` | 由 CP 连接状态推导的锁屏组件状态 |
| `unlockReady` | 当前是否具备尝试解锁的条件 |

## 解锁受理

`unlock` 响应保留 `unlock_result` 类型以兼容旧客户端，但新增明确状态：

```json
{
  "type": "unlock_result",
  "payload": {
    "ok": true,
    "error": null,
    "requestId": 1,
    "accepted": true,
    "completed": false,
    "state": "accepted"
  }
}
```

`accepted=true` 只表示服务端已完成签名校验并把请求交给凭据提供程序。

## 最终结果

手机在拿到 `requestId` 后轮询：

```json
{ "type": "unlock_status", "payload": { "requestId": 1 } }
```

```json
{
  "type": "unlock_status_result",
  "payload": {
    "requestId": 1,
    "state": "succeeded",
    "success": true,
    "error": null,
    "updatedAt": 1762777890123,
    "completed": true
  }
}
```

状态流转：

```text
accepted -> submitted -> succeeded
                      \-> failed
```

- 状态只能单向推进。凭据提供程序可能先于解锁路由回写 `submitted`，服务端不会再把记录降级回 `accepted`。
- `accepted`：服务已验签，等待凭据提供程序处理。
- `submitted`：CP 已完成凭据序列化并交给 Windows。
- `succeeded`：CP 的 `ReportResult` 返回成功。
- `failed`：CP 返回失败，`error` 可能为 `credential_invalid`、`logon_failure`、
  `credential_unreadable`、`component_error`、`component_disconnected`、`outcome_timeout`。
- `unknown`：服务重启或请求记录已过期，手机应提示重新发起。

手机轮询超时只能显示“已提交，等待 Windows 完成登录”，不能提前展示“已解锁”。

超时后手机保留 `requestId`，用户可通过“刷新最终状态”再次查询。若服务已重启，
查询会返回 `state=unknown`、`error=unknown_request`，手机应提示重新发起解锁。
CP 在登录会话切换时可能先断开命名管道，随后才在 `ReportResult` 中得到 Windows
最终结果。因此服务端在 CP 断开后保留 8 秒宽限期，不会立刻把请求标记失败；
CP 同时把最终结果原子写入 `C:\ProgramData\BandKey\outcomes\<requestId>.result`，
服务端在处理 `unlock_status` 时补收该结果。宽限期结束仍无结果时，请求收敛为
`state=failed`，`error=component_disconnected`；超过 120 秒则收敛为
`error=outcome_timeout`。

## 旧版服务兼容

v1.7.0 及更早版本不认识 `status`，会返回：

```json
{
  "type": "error",
  "payload": { "code": "unknown_type", "message": "未知消息类型: status" }
}
```

手机必须将这种错误识别为“电脑服务版本过旧”，而不是电脑离线。旧版服务同样
不支持 `unlock_status`，手机只能显示“已提交”，并提示升级电脑端后才能确认最终结果。

## 内部管道消息

| 方向 | 消息 | 说明 |
| --- | --- | --- |
| 服务 → CP | `AUTO_UNLOCK:<requestId>` | 投递一次自动解锁请求 |
| CP → 服务 | `AUTO_UNLOCK_ACK:<requestId>` | 已收到请求并触发登录界面刷新 |
| CP → 服务 | `AUTO_UNLOCK_SUBMITTED:<requestId>` | 凭据已完成序列化并提交 |
| CP → 服务 | `UNLOCK_RESULT:<requestId>:success` | Windows 最终登录成功 |
| CP → 服务 | `UNLOCK_RESULT:<requestId>:credential_invalid` | 密码错误 |
| CP → 服务 | `UNLOCK_RESULT:<requestId>:logon_failure` | 其他登录失败 |
| CP → 服务 | `UNLOCK_RESULT:<requestId>:credential_unreadable` | 无法读取 DPAPI 凭据 |
| CP → 服务 | `UNLOCK_RESULT:<requestId>:component_error` | 组件初始化失败 |

最终结果除了通过管道发送，还会写入上述结果文件；文件内容是 `success` 或失败
错误码。服务端读取成功后删除文件，避免服务重启后把旧结果误用于新的请求。
