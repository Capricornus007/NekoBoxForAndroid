<div align="center">

# 🐱 NekoBox for Android 🐱

**One app, every modern proxy. Built on sing-box.**

[![API](https://img.shields.io/badge/Android-5.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android-arsenal.com/api?level=21)
[![License](https://img.shields.io/badge/License-GPL--3.0-0A7BBB?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0)

</div>

<br>

## ⚠️ Disclaimer

> This project is intended solely for technical research and code learning purposes and does not provide any form of network proxy service. Please do not use this project for any activity that violates local laws and regulations, and do not use it in production environments. Users are fully responsible for any risks that may arise from using this project. If you download or reference this project, please delete all related content within 24 hours and avoid long-term storage, distribution, or dissemination of any part of it. **The author reserves the right to modify, update, or remove any part of this project or its contents at any time without prior notice.**

<br>

## 📥 Downloads

> 🛑 **Note:** The Google Play build has been controlled by a third party since May 2024 and is closed-source. Please don't install it.


## 🚀 Protocols

### 🧩 Core

## 连接与速度测试 / Connection and Speed Tests

当前组菜单提供“URL 测试本组”和“速度测试本组”两个入口，不再提供批量直连 TCP/ICMP Ping。URL 延迟测试的新安装默认地址为 `http://cp.cloudflare.com/`，默认并发为 10。

速度测试支持下载+上传、仅下载、仅上传和简单下载；节点会逐个测试，流量通过各自代理发送。默认模式为下载+上传，默认超时为 5000 ms，简单下载默认地址为 `http://cachefly.cachefly.net/1mb.test`。速度测试可能消耗大量流量，请按需选择模式或及时取消。升级不会覆盖已经保存的自定义测试设置。

The current-group menu provides **URL test this group** and **Speed test this group**; direct batch TCP/ICMP Ping actions are no longer exposed. New installations use `http://cp.cloudflare.com/` with 10 URL-latency workers by default.

Speed testing supports download + upload, download only, upload only, and simple download. Profiles are tested one at a time through their own proxy. The default mode is download + upload, the default timeout is 5000 ms, and the default simple-download URL is `http://cachefly.cachefly.net/1mb.test`. Speed tests may consume significant data. Existing custom test settings are preserved during upgrades.

## 支持的代理协议 / Supported Proxy Protocols

### 🔐 Modern TLS & multiplexing

`AnyTLS` · `ShadowTLS` · `Snell 1–5` · `VLESS-XHTTP` · `VLESS-Reality`

XHTTP / Reality config examples are below.

### ⚡ QUIC & high-speed

`TUIC` · `Juicity` · `Hysteria 2`

The Hysteria 2 client supports the new Gecko (experimental) obfuscation.

### 🛡️ WireGuard family

`WireGuard` · `AmneziaWG`

### 🌐 Obfuscated transports

- `NaïveProxy` (Bundled)
- `Mieru` (Bundled)

<details>
<summary><b>🕰️ Legacy protocols</b> (kept for compatibility)</summary>

<br>

- `Hysteria 1` - native over UDP; an external sidecar covers the rare faketcp transport
- `Trojan-Go` - requires the separate `trojan-go-plugin` companion

</details>

<br>

<details>
<summary>XHTTP - TLS configuration example</summary>

<pre><code class="language-json">
{
    "no_grpc_header": false,  // stream-up/one
	"x_padding_bytes": "100-10000",
	"sc_max_each_post_bytes": 1000000, // packet-up only
	"sc_min_posts_interval_ms": 30, // packet-up only
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
    "x_padding_obfs_mode": false,
    "x_padding_key": "",
    "x_padding_header": "",
    "x_padding_placement": "",
    "x_padding_method": "",
    "uplink_http_method": "",
    "session_placement": "",
    "session_key": "",
    "seq_placement": "",
    "seq_key": "",
    "uplink_data_placement": "",
    "uplink_data_key": "",
    "uplink_chunk_size": 0,
	"download": {
		"mode": "auto",
		"host": "b.yourdomain.com",
		"path": "/xhttp",
        "no_grpc_header": false,  // stream-up/one
	    "x_padding_bytes": "100-10000",
	    "sc_max_each_post_bytes": 1000000, // packet-up only
	    "sc_min_posts_interval_ms": 30, // packet-up only
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
        "x_padding_obfs_mode": false,
        "x_padding_key": "",
        "x_padding_header": "",
        "x_padding_placement": "",
        "x_padding_method": "",
        "uplink_http_method": "",
        "session_placement": "",
        "session_key": "",
        "seq_placement": "",
        "seq_key": "",
        "uplink_data_placement": "",
        "uplink_data_key": "",
        "uplink_chunk_size": 0,
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "b.yourdomain.com",
			"alpn": "h2",
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

<details>
<summary>XHTTP - Reality configuration example</summary>

<pre><code class="language-json">
{
    "no_grpc_header": false,  // stream-up/one
	"x_padding_bytes": "100-10000",
	"sc_max_each_post_bytes": 1000000, // packet-up only
	"sc_min_posts_interval_ms": 30, // packet-up only
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
    "x_padding_obfs_mode": false,
    "x_padding_key": "",
    "x_padding_header": "",
    "x_padding_placement": "",
    "x_padding_method": "",
    "uplink_http_method": "",
    "session_placement": "",
    "session_key": "",
    "seq_placement": "",
    "seq_key": "",
    "uplink_data_placement": "",
    "uplink_data_key": "",
    "uplink_chunk_size": 0,
	"download": {
		"mode": "auto",
		"host": "example.com",
		"path": "/xhttp",
        "no_grpc_header": false,  // stream-up/one
	    "x_padding_bytes": "100-10000",
	    "sc_max_each_post_bytes": 1000000, // packet-up only
	    "sc_min_posts_interval_ms": 30, // packet-up only
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
        "x_padding_obfs_mode": false,
        "x_padding_key": "",
        "x_padding_header": "",
        "x_padding_placement": "",
        "x_padding_method": "",
        "uplink_http_method": "",
        "session_placement": "",
        "session_key": "",
        "seq_placement": "",
        "seq_key": "",
        "uplink_data_placement": "",
        "uplink_data_key": "",
        "uplink_chunk_size": 0,
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "example.com",
			"reality": {
				"enabled": true,
				"public_key": "$(your_publicKey)",
				"short_id": "$(your_shortId)"
			},
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

<br>

## 🔗 Subscriptions

Imports the common formats: Shadowsocks, ClashMeta, v2rayN, and sing-box outbounds.
Only nodes are parsed; routing rules and other non-node fields are ignored.

<br>

## Credits

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [Mahdi-zarei/speedtest-go](https://github.com/Mahdi-zarei/speedtest-go)（版本与来源见 [`libcore/DEPENDENCIES.md`](libcore/DEPENDENCIES.md)）

### Android GUI

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

### Web Dashboard

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)

## Contributors

![Contributors](https://contrib.rocks/image?repo=starifly/NekoBoxForAndroid)
