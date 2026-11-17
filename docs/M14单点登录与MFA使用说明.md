# M14 单点登录与 MFA 使用说明

柒衡 ERP 继续使用原有会话、公司和数据范围。外部渠道只确认身份，业务访问范围由 ERP 按本地角色、公司、仓库和部门权限计算。

## 上线准备

1. 将 ERP 主库升级至 M14。V53 扩展 `tenant_login_provider` 和 `user_login_identity`，增加登录策略、TOTP 记录及接口账号身份标记。升级前备份数据库与登录配置密钥。
2. API 实例共享现有 Redis 会话。为所有实例配置同一个 `ERP_LOGIN_SETTINGS_KEY`，值为 32 字节随机数的 Base64 字符串。部署后备份该密钥。修改密钥需要处理已存放的渠道密文和验证器密文。
3. 用 `ERP_LOGIN_SSO_ALLOWED_HOSTS` 登记经过运维审核的 OIDC 和目录主机。默认 HTTPS 443、LDAPS 636 可只填主机名，其他端口必须写 `host:port`。多个值以英文逗号分隔。自建 IdP 或 LDAP 的 CA 导入 API JVM 的受信任证书库。OIDC 及三个扫码渠道使用固定的本站 HTTPS 回调 `https://ERP域名/api/v1/auth/sso/callback`，保留原 Host 和端口到 API。OAuth 回调查询值含短时授权码和 state，网关访问日志沿用去查询参数的模板。

| 权限 | 可操作事项 |
| --- | --- |
| `tenant:login_settings` | 配置外部渠道、启停和测试连接 |
| `tenant:security_policy` | 配置企业安全策略、为本企业用户维护外部绑定、重置其他用户的 MFA |

企业级身份管理需要至少一个有效的公司授权，以及这些公司的完整部门范围。配置只影响当前企业。正常业务权限仍按现有角色和数据范围独立分配。

## 配置登录渠道

进入系统管理的企业认证页面，为每种渠道填写显示名称、配置和密钥。保存后测试连接，再在浏览器登录页填写企业编码，确认渠道可见。每个企业的各类型渠道分别保存一份配置。

| 类型 | 配置键 | 用法 |
| --- | --- | --- |
| `oidc` | `issuer`、`authorizationUrl`、`tokenUrl`、`jwksUrl`、`clientId`、`callbackUrl`、`clientAuth` | 复制企业 IdP 的精确发行方与授权、令牌、JWK 地址。应用使用授权码及 PKCE S256，ID Token 使用 RS256。`clientAuth` 默认为 `client_secret_basic`，支持 `client_secret_post`。密钥填客户端密钥。测试连接检查 JWK 集合，登录用真实账号验收。 |
| `ldap` | `serverUrl`、`baseDn`、`userAttribute`、`bindDn` | `serverUrl` 使用 `ldaps://`，目录范围单独填入 `baseDn`。`userAttribute` 支持 `uid`、`sAMAccountName` 或 `userPrincipalName`，默认为 `uid`。服务账号 `bindDn` 需具备查找账号的权限，密钥填服务账号密码。系统先查唯一 DN，再使用用户凭据进行 LDAPS 绑定。测试连接会绑定服务账号并读取 Base DN。 |
| `wecom` | `corpId`、`agentId`、`callbackUrl` | 企业微信自建应用的企业 ID、AgentId 和应用 Secret。设置可信域名、回调与应用服务端可信 IP。测试连接获取应用访问令牌。 |
| `dingtalk` | `clientId`、`callbackUrl` | 钉钉应用的 Client ID 和 Client Secret，在开放平台启用网页应用登录并登记回调。测试连接获取应用访问令牌。 |
| `feishu` | `clientId`、`callbackUrl` | 飞书自建应用的 App ID 和 App Secret，在开放平台登记回调并发布启用登录能力。测试连接获取企业访问令牌。 |

管理员绑定用户时可填写外部主体原值。OIDC 存储为 `{issuer}|{sub}`，企业微信为 `{corpId}|{userid}`，钉钉为 `{clientId}|{unionId}`，飞书为 `{clientId}|{open_id}`，LDAP 为已规范化的完整 DN。绑定、配置和启用状态发生变化后，已关联会话按当前身份重新校验。更换已有绑定的发行方、目录或应用标识时，先处理旧绑定。释放或改绑会留下审计记录。

OAuth 回调只接受发起浏览器的单次 state。OIDC 同时校验授权码的 PKCE、Token 签名、`issuer`、`audience`、`azp`、有效期及 `nonce`。默认仅接收管理员预先绑定的外部身份。可在安全策略开启自动开通并选择默认角色。OIDC 的已验证邮箱可匹配本企业已有的验证邮箱，其他未匹配身份会建立待启用账号，管理员核验并分配数据范围后才具备 ERP 访问权限。

启用仅单点登录前，先给当前管理员绑定并登录验证一条可用外部渠道。密码开关管理 ERP 本地账号密码登录，已启用的短信、邮件和个人微信渠道仍按各自配置提供登录。角色要求 MFA 时，所有第一因素都必须通过二次验证。

## 绑定验证器与恢复

- 管理员在安全策略中选择强制 MFA 的角色并保存。已登录用户的访问立即按新策略重新检查。首次登录强制角色时，扫描绑定二维码或手工录入密钥，然后提交六位验证码。
- 普通用户可在个人中心的账号安全页开启 TOTP。完成绑定时一次性显示 10 个恢复码。每个恢复码仅可用一次，服务器只存哈希。验证器密钥单独加密。
- 待二次验证的会话只进入认证步骤。验证成功换发新会话。验证码按 30 秒步长验证，允许前后各一步，同一时间步不能再次使用。
- 用户可提交当前验证码或恢复码关闭个人启用的 MFA。强制角色需保留绑定。验证器和恢复码均遗失时，由另一位有权限的管理员重置。重置后，使用旧验证器的完整会话和未完成的验证会话均失效。

## 接口

| 请求 | 用途 |
| --- | --- |
| `GET /api/v1/auth/methods?tenantCode=…` | 查询本企业已启用登录方式 |
| `GET /api/v1/auth/sso/{providerId}/start?tenantCode=…`、`GET /api/v1/auth/sso/callback` | 发起浏览器授权和回调 |
| `POST /api/v1/auth/ldap/login` | 提交企业编码、目录账号和密码 |
| `GET /api/v1/auth/mfa/status`、`POST /api/v1/auth/mfa/setup`、`activate`、`verify` | 待验证流程。POST 需该浏览器的 CSRF 令牌 |
| `GET /api/v1/account/mfa`、`POST /api/v1/account/mfa/setup`、`activate`、`disable` | 个人 MFA 管理 |
| `GET /api/v1/account/login-methods` | 查询个人已绑定的登录方式 |
| `GET/PUT /api/v1/sys/security-policy` | 读取或更新策略，首次配置版本号为 `0` |
| `GET /api/v1/login-settings/sso`、`PUT /api/v1/login-settings/sso/{id}`、`POST /api/v1/login-settings/sso/{id}/test` | 渠道管理、版本校验和连接测试 |
| `PUT/DELETE /api/v1/sys/users/{userId}/login-identities/{method}`、`POST /api/v1/sys/users/{userId}/mfa/reset` | 管理外部绑定和恢复 |

## 本机回归和供应商联调

在仓库根目录执行。先准备独立的 `erp_test` 或 `erp_test_环境名` MySQL 库、当前进程的 `TEST_DB_*` 变量和隔离 Redis 连接。使用项目要求的 JDK 21 和 MySQL 8.0.46。

```powershell
mvn -B -f erp-server/pom.xml '-Dtest=TotpCodesTest,SsoWebClientTest,WechatLoginTest,LoginSettingsTest,IdentityRulesTest,ModulesTest' '-Dit.test=SsoMfaIT' verify
```

本机定向覆盖通过实际签名的 OIDC Token、错误签名与声明、一致的 PKCE、state 跨会话和重放、角色 MFA、应用身份拒绝成人会话、CSRF、验证码和恢复码重放、跨企业管理与 MFA 重置、外部绑定撤销和待审开通。OAuth 交换与目录响应使用受控替身，MySQL、Redis 和 ERP HTTP 层实际运行。上线时分别使用本企业应用、可信域名和证书完成实际 OIDC 授权、目录登录、企业微信、钉钉和飞书扫码验证。

[OpenID Connect Core](https://openid.net/specs/openid-connect-core-1_0.html) · [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238) · [企业微信 Web 登录](https://developer.work.weixin.qq.com/document/path/98152) · [钉钉获取用户 Token](https://open.dingtalk.com/document/orgapp/obtain-user-token) · [飞书网页登录](https://open.feishu.cn/document/home/qr-code-scanning-login-for-web-app/introduction)
