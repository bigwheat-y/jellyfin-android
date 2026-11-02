# 内置 Tailscale VPN

## 使用方式

1. 在服务器上部署 Tailscale，并允许当前账号访问 Jellyfin 端口。
2. 在客户端连接页或应用设置中打开「Tailscale 连接」。
3. 配置控制服务器（预填 `https://vnet.yangxian.cloud/`，也可使用 `https://controlplane.tailscale.com`），填入服务器的 Tailscale IPv4 地址，预填 `100.64.0.4`，点击连接并同意 Android VPN 授权。
4. 初次使用时点击账号授权按钮，在浏览器完成 Tailscale 登录或 Headscale 设备注册，然后返回客户端。Headscale 未启用网页登录时需管理员在控制服务器批准注册；客户端不需要保存管理员密码或 API Key。
5. 隧道就绪后返回 Jellyfin，添加 `http://100.64.0.4:8096`，或使用服务器实际的 HTTPS 地址和端口。不要为绕过证书校验而关闭 TLS 校验；第一版建议 HTTP 在加密隧道内访问，或使用能够验证 IP 的证书。
6. 后续打开客户端自动恢复连接；通知或设置页可以断开。退出 Tailscale 登录会撤销本地登录会话，管理后台的设备删除需自行操作。

第一版仅支持单个 Tailscale IPv4 主机地址。地址不得为 URL、域名、CIDR、默认路由或 `100.100.100.100`。变更 IP 前先断开。

## 网络边界

- `VpnService.Builder.addAllowedApplication(packageName)` 只允许当前安装包进入 VPN，debug 与 release 包名自动适配。
- 路由固定为配置地址 `/32`，忽略官方引擎额外请求的路由、DNS、搜索域。未匹配目标走普通网络；不配置默认路由。
- 引擎套接字通过 `protect` 和底层非 VPN 网络绑定避免回环。网络变化通知官方引擎重新探测和重连。
- 不支持出口节点、MagicDNS、子网路由、Taildrop 或远端入站服务；设备使用 ShieldsUp。
- 外部播放器、投屏接收端不在当前应用 VPN 范围内，需要各自能访问服务器。
- Android 每个用户只有一个活动 VPN；即使只路由一个 IP，也不能与其他 Android VPN 并行。不要为此应用开启系统的「阻止未使用 VPN 的连接」。
- 账号认证使用所配置控制服务器或 Tailscale 官方 HTTPS 页面；设备身份用 Android Keystore AES-GCM 加密，独立偏好文件和引擎目录不参与备份。客户端不收集授权 URL 或原生引擎日志。

## 原生引擎构建

使用官方 `tailscale/tailscale-android`，固定修订 `9b62cdc07e3e2b2981894dbd18798721902273a1`，本次为官方开发快照（依赖版本详见锁定源码 go.mod）。没有把开发快照描述为稳定版；发布前应选定稳定修订并完成真机验证。

构建要求：JDK 21、Go（支持自动下载源码 go.mod 所需工具链）、Android SDK、NDK r28 或更新版本。客户端自身需要 SDK 37。配置实际 SDK 路径：

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./scripts/build-tailscale.sh
./gradlew :app:assembleLibreDebug
```

原生脚本生成 `app/libs/libtailscale.aar`，包含 arm64-v8a、armeabi-v7a、x86_64 和 x86 四种架构，保持现有客户端的架构覆盖。脚本使用标准 Go 工具链，16KB 页面对齐链接参数与客户端最低 API 23。生成的二进制不纳入 Git，构建缓存位于 `.tailscale-build`。缺失 AAR 时 Gradle 明确报错，不会生成空壳 VPN。官方 BSD 许可保留于 `app/src/main/assets/tailscale-LICENSE.txt`，发布时还应核查原生依赖许可证。

前台服务使用 `systemExempted` 类型，且只在 `VpnService.prepare()` 已授权后启动。Android 官方将已配置的 VPN 列为此类型的适用条件，不需要申请闹钟权限。Manifest 的局部 Lint 抑制仅覆盖检测器未识别 VPN 例外的误报，参见 [Android 前台服务类型说明](https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted)。

## 验证清单

自动检查应覆盖 IP 输入边界、VPN 服务声明、单应用和单目标路由约束、原生接口编译及 APK 打包。

实际联网需要测试账号和设备：首次授权/取消授权、登录及重新登录、退出账号、服务重建、Wi-Fi/蜂窝切换、权限撤销、与其他 VPN 切换、其他应用网络和 DNS 不受影响、媒体库加载、WebSocket、视频直放/转码、拖动进度、字幕、后台播放。分别在直连和 DERP 中继下检查吞吐。隧道就绪仅表示引擎在线及 TUN 建立，不代表服务器端口或 ACL 已通过连通性检查。

## 本次构建验证（2026-11-02）

- 官方 Go 引擎已构建为包含四种 ABI 的 AAR；APK 内包含对应动态库及官方许可。
- `:app:assembleLibreDebug`、`:app:testLibreDebugUnitTest`、`:app:lintLibreDebug` 执行完成，Gradle 构建成功；6 项单元测试全部通过。
- Lint 未发现本次改动新增的错误，仍有 11 项未修改的既有翻译资源 `ImpliedQuantity` 错误。项目原配置 `abortOnError = false` 未更改。
- Headscale `https://vnet.yangxian.cloud/health` 返回 `{"status":"pass"}`。
- 尚未在手机完成节点注册、VPN 连通及视频播放联调；需要用户批准 Headscale 节点后验证 `http://100.64.0.4:8096`。桌面直接请求该私网地址未取得有效 HTTP 响应，不能据此判定手机隧道或 Jellyfin 服务状态。
