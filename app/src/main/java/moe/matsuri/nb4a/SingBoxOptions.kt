package moe.matsuri.nb4a

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.ToNumberPolicy
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import moe.matsuri.nb4a.utils.Util
import java.lang.reflect.Modifier
import java.lang.reflect.Type

@JvmSuppressWildcards
class SingBoxOptions {
    companion object {
        private val gsonSingbox = GsonBuilder()
            .registerTypeHierarchyAdapter(SingBoxOption::class.java, SingBoxOptionSerializer())
            .setPrettyPrinting()
            .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .setLenient()
            .disableHtmlEscaping()
            .create()
        private val gsonSingboxPlain = GsonBuilder()
            .setPrettyPrinting()
            .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .setLenient()
            .disableHtmlEscaping()
            .create()
        private val mapType = object : TypeToken<MutableMap<String, Any?>>() {}.type

        @JvmStatic
        fun toJsonTree(option: SingBoxOption): JsonObject = gsonSingbox.toJsonTree(option).asJsonObject

        @JvmStatic
        fun treeToJson(tree: JsonElement): String = gsonSingbox.toJson(tree)
    }

    open class SingBoxOption {
        @JvmField @Transient
        var _hack_config_map: MutableMap<String, Any?> = mutableMapOf()

        @JvmField @Transient
        var _hack_custom_config: String? = null

        fun asMap(): MutableMap<String, Any?> = gsonSingbox.fromJson(gsonSingbox.toJson(this), mapType)
    }

    class CustomSingBoxOption(@JvmField @Transient var config: String?) : SingBoxOption() {
        fun getBasicMap(): MutableMap<String, Any?> = gsonSingbox.fromJson(config, mapType) ?: mutableMapOf()
    }

    class SingBoxOptionSerializer : JsonSerializer<SingBoxOption> {
        override fun serialize(src: SingBoxOption, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            val tree = gsonSingboxPlain.toJsonTree(if (src is CustomSingBoxOption) src.getBasicMap() else src)
            applyHackConfig(src, tree)
            return tree
        }

        private fun applyHackConfig(src: Any?, tree: JsonElement?) {
            if (src == null || tree == null) return
            if (src is SingBoxOption && tree.isJsonObject) {
                val obj = tree.asJsonObject
                if (src is CustomSingBoxOption) {
                    obj.entrySet().clear()
                    gsonSingboxPlain.toJsonTree(src.getBasicMap()).asJsonObject.entrySet().forEach { (key, value) -> obj.add(key, value) }
                }
                src._hack_config_map?.takeIf { it.isNotEmpty() }?.let { overrides ->
                    val map: MutableMap<String, Any?> = gsonSingboxPlain.fromJson(obj, mapType)
                    Util.mergeMap(map, overrides)
                    obj.entrySet().clear()
                    gsonSingboxPlain.toJsonTree(map).asJsonObject.entrySet().forEach { (key, value) -> obj.add(key, value) }
                }
                src._hack_custom_config?.takeIf { it.isNotBlank() }?.let { overrides ->
                    val map: MutableMap<String, Any?> = gsonSingboxPlain.fromJson(obj, mapType)
                    Util.mergeJSON(map, overrides)
                    obj.entrySet().clear()
                    gsonSingboxPlain.toJsonTree(map).asJsonObject.entrySet().forEach { (key, value) -> obj.add(key, value) }
                }
            }
            if (!tree.isJsonObject) return
            val obj = tree.asJsonObject
            for (field in src.javaClass.fields) {
                if (Modifier.isStatic(field.modifiers) || Modifier.isTransient(field.modifiers)) continue
                val name = field.getAnnotation(SerializedName::class.java)?.value ?: field.name
                val childTree = obj[name]?.takeUnless { it.isJsonNull } ?: continue
                try {
                    when (val child = field.get(src)) {
                        is SingBoxOption -> applyHackConfig(child, childTree)

                        is Collection<*> -> if (childTree.isJsonArray) {
                            child.zip(childTree.asJsonArray).forEach { (item, value) -> applyHackConfig(item, value) }
                        }

                        is Map<*, *> -> if (childTree.isJsonObject) {
                            child.forEach { (key, value) ->
                                if (value is SingBoxOption) applyHackConfig(value, childTree.asJsonObject[key.toString()])
                            }
                        }
                    }
                } catch (_: IllegalAccessException) {
                }
            }
        }
    }

    class User {
        @JvmField var username: String? = null

        @JvmField var password: String? = null
    }

    class MyOptions : SingBoxOption() {
        @JvmField var log: LogOptions? = null

        @JvmField var dns: DNSOptions? = null

        @JvmField var ntp: NTPOptions? = null

        @JvmField var inbounds: List<Inbound>? = null

        @JvmField var outbounds: List<SingBoxOption>? = null

        @JvmField var route: RouteOptions? = null

        @JvmField var experimental: ExperimentalOptions? = null
    }

    class ClashAPIOptions : SingBoxOption() {
        @JvmField var external_controller: String? = null

        @JvmField var external_ui: String? = null

        @JvmField var external_ui_download_url: String? = null

        @JvmField var external_ui_download_detour: String? = null

        @JvmField var secret: String? = null

        @JvmField var default_mode: String? = null
    }

    class Options : SingBoxOption() {
        @JvmField var `$schema`: String? = null

        @JvmField var log: LogOptions? = null

        @JvmField var dns: DNSOptions? = null

        @JvmField var ntp: NTPOptions? = null

        @JvmField var inbounds: List<Inbound>? = null

        @JvmField var outbounds: List<Outbound>? = null

        @JvmField var route: RouteOptions? = null

        @JvmField var experimental: ExperimentalOptions? = null
    }

    class LogOptions : SingBoxOption() {
        @JvmField var disabled: Boolean? = null

        @JvmField var level: String? = null

        @JvmField var output: String? = null

        @JvmField var timestamp: Boolean? = null
    }

    class DebugOptions : SingBoxOption() {
        @JvmField var listen: String? = null

        @JvmField var gc_percent: Int? = null

        @JvmField var max_stack: Int? = null

        @JvmField var max_threads: Int? = null

        @JvmField var panic_on_fault: Boolean? = null

        @JvmField var trace_back: String? = null

        @JvmField var memory_limit: Long? = null

        @JvmField var oom_killer: Boolean? = null
    }

    class DNSOptions : SingBoxOption() {
        @JvmField var servers: List<DNSServerOptions>? = null

        @JvmField var rules: List<SingBoxOption>? = null

        @SerializedName("final")
        @JvmField
        var final_: String? = null

        @JvmField var reverse_mapping: Boolean? = null

        @JvmField var strategy: String? = null

        @JvmField var disable_cache: Boolean? = null

        @JvmField var disable_expire: Boolean? = null

        @JvmField var independent_cache: Boolean? = null
    }

    class DomainResolveOptions : SingBoxOption() {
        @JvmField var server: String? = null

        @JvmField var strategy: String? = null
    }

    class DNSServerOptions : SingBoxOption() {
        @JvmField var type: String? = null

        @JvmField var tag: String? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var path: String? = null

        @JvmField var domain_resolver: DomainResolveOptions? = null

        @JvmField var detour: String? = null

        @JvmField var inet4_range: String? = null

        @JvmField var inet6_range: String? = null
    }

    class ExperimentalOptions : SingBoxOption() {
        @JvmField var clash_api: ClashAPIOptions? = null

        @JvmField var v2ray_api: V2RayAPIOptions? = null

        @JvmField var cache_file: CacheFile? = null

        @JvmField var debug: DebugOptions? = null
    }

    class CacheFile : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var store_fakeip: Boolean? = null

        @JvmField var path: String? = null

        @JvmField var cache_id: String? = null
    }

    class Hysteria2Obfs : SingBoxOption() {
        @JvmField var type: String? = null

        @JvmField var password: String? = null

        // Gecko obfs only (flattened into the obfs object by the core's badjson serializer).
        @JvmField var min_packet_size: Int? = null

        @JvmField var max_packet_size: Int? = null
    }

    open class Inbound : SingBoxOption() {
        @JvmField var type: String? = null

        @JvmField var tag: String? = null
    }

    class NTPOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var interval: Long? = null

        @JvmField var write_to_system: Boolean? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null
    }

    open class Outbound : SingBoxOption() {
        @JvmField var type: String? = null

        @JvmField var tag: String? = null
    }

    class Fragment : SingBoxOption() {
        @JvmField var length: String? = null

        @JvmField var interval: String? = null
    }

    class DialerOptions : SingBoxOption() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null
    }

    class ServerOptions : SingBoxOption() {
        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null
    }

    class MultiplexOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var protocol: String? = null

        @JvmField var max_connections: Int? = null

        @JvmField var min_streams: Int? = null

        @JvmField var max_streams: Int? = null

        @JvmField var padding: Boolean? = null

        @JvmField var brutal: BrutalOptions? = null
    }

    class BrutalOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var up_mbps: Int? = null

        @JvmField var down_mbps: Int? = null
    }

    class RouteOptions : SingBoxOption() {
        @JvmField var rules: List<Rule>? = null

        @JvmField var rule_set: List<RuleSet>? = null

        @SerializedName("final")
        @JvmField
        var final_: String? = null

        @JvmField var find_process: Boolean? = null

        @JvmField var auto_detect_interface: Boolean? = null

        @JvmField var override_android_vpn: Boolean? = null

        @JvmField var default_interface: String? = null

        @JvmField var default_mark: Int? = null

        @JvmField var concurrent_dial: Boolean? = null

        @JvmField var default_domain_resolver: DomainResolveOptions? = null
    }

    open class Rule : SingBoxOption() {
        @JvmField var type: String? = null
    }

    class RuleSet : SingBoxOption() {
        @JvmField var type: String? = null

        @JvmField var tag: String? = null

        @JvmField var format: String? = null

        @JvmField var path: String? = null

        @JvmField var url: String? = null

        @JvmField var update_interval: String? = null
    }

    open class DNSRule : SingBoxOption() {
        @JvmField var type: String? = null
    }

    class InboundTLSOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var server_name: String? = null

        @JvmField var insecure: Boolean? = null

        @JvmField var alpn: List<String>? = null

        @JvmField var min_version: String? = null

        @JvmField var max_version: String? = null

        @JvmField var cipher_suites: List<String>? = null

        @JvmField var certificate: List<String>? = null

        @JvmField var certificate_path: String? = null

        @JvmField var key: List<String>? = null

        @JvmField var key_path: String? = null

        @JvmField var acme: InboundACMEOptions? = null

        @JvmField var ech: InboundECHOptions? = null

        @JvmField var reality: InboundRealityOptions? = null
    }

    class OutboundTLSOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var disable_sni: Boolean? = null

        @JvmField var server_name: String? = null

        @JvmField var insecure: Boolean? = null

        @JvmField var alpn: List<String>? = null

        @JvmField var min_version: String? = null

        @JvmField var max_version: String? = null

        @JvmField var cipher_suites: List<String>? = null

        @JvmField var certificate: String? = null

        @JvmField var certificate_path: String? = null

        @JvmField var ech: OutboundECHOptions? = null

        @JvmField var utls: OutboundUTLSOptions? = null

        @JvmField var reality: OutboundRealityOptions? = null
    }

    class InboundRealityOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var handshake: InboundRealityHandshakeOptions? = null

        @JvmField var private_key: String? = null

        @JvmField var short_id: List<String>? = null

        @JvmField var max_time_difference: Long? = null
    }

    class InboundRealityHandshakeOptions : SingBoxOption() {
        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null
    }

    class InboundECHOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var key: List<String>? = null

        @JvmField var key_path: String? = null
    }

    class OutboundECHOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var config: List<String>? = null

        @JvmField var config_path: String? = null
    }

    class OutboundUTLSOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var fingerprint: String? = null
    }

    class OutboundRealityOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var public_key: String? = null

        @JvmField var short_id: String? = null
    }

    class InboundACMEOptions : SingBoxOption() {
        @JvmField var domain: List<String>? = null

        @JvmField var data_directory: String? = null

        @JvmField var default_server_name: String? = null

        @JvmField var email: String? = null

        @JvmField var provider: String? = null

        @JvmField var disable_http_challenge: Boolean? = null

        @JvmField var disable_tls_alpn_challenge: Boolean? = null

        @JvmField var alternative_http_port: Int? = null

        @JvmField var alternative_tls_port: Int? = null

        @JvmField var external_account: ACMEExternalAccountOptions? = null
    }

    class ACMEExternalAccountOptions : SingBoxOption() {
        @JvmField var key_id: String? = null

        @JvmField var mac_key: String? = null
    }

    class TunPlatformOptions : SingBoxOption() {
        @JvmField var http_proxy: HTTPProxyOptions? = null
    }

    class HTTPProxyOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null
    }

    class UDPOverTCPOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var version: Int? = null
    }

    class V2RayAPIOptions : SingBoxOption() {
        @JvmField var listen: String? = null

        @JvmField var stats: V2RayStatsServiceOptions? = null
    }

    class V2RayStatsServiceOptions : SingBoxOption() {
        @JvmField var enabled: Boolean? = null

        @JvmField var inbounds: List<String>? = null

        @JvmField var outbounds: List<String>? = null

        @JvmField var users: List<String>? = null
    }

    open class V2RayTransportOptions : SingBoxOption() {
        @JvmField var type: String? = null
    }

    class WireGuardPeer : SingBoxOption() {
        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var public_key: String? = null

        @JvmField var pre_shared_key: String? = null

        @JvmField var allowed_ips: List<String>? = null

        @JvmField var reserved: String? = null
    }

    class Inbound_TunOptions : Inbound() {
        @JvmField var interface_name: String? = null

        @JvmField var mtu: Int? = null

        @JvmField var address: List<String>? = null

        @JvmField var inet4_address: List<String>? = null

        @JvmField var inet6_address: List<String>? = null

        @JvmField var auto_route: Boolean? = null

        @JvmField var strict_route: Boolean? = null

        @JvmField var inet4_route_address: List<String>? = null

        @JvmField var inet6_route_address: List<String>? = null

        @JvmField var include_interface: List<String>? = null

        @JvmField var exclude_interface: List<String>? = null

        @JvmField var include_uid: List<Int>? = null

        @JvmField var include_uid_range: List<String>? = null

        @JvmField var exclude_uid: List<Int>? = null

        @JvmField var exclude_uid_range: List<String>? = null

        @JvmField var include_android_user: List<Int>? = null

        @JvmField var include_package: List<String>? = null

        @JvmField var exclude_package: List<String>? = null

        @JvmField var endpoint_independent_nat: Boolean? = null

        @JvmField var udp_timeout: Long? = null

        @JvmField var stack: String? = null

        @JvmField var platform: TunPlatformOptions? = null

        @JvmField var sniff: Boolean? = null

        @JvmField var sniff_override_destination: Boolean? = null

        @JvmField var sniff_timeout: Long? = null

        @JvmField var domain_strategy: String? = null
    }

    class Inbound_DirectOptions : Inbound() {
        @JvmField var listen: String? = null

        @JvmField var listen_port: Int? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var udp_timeout: Long? = null

        @JvmField var proxy_protocol: Boolean? = null

        @JvmField var proxy_protocol_accept_no_header: Boolean? = null

        @JvmField var detour: String? = null

        @JvmField var sniff: Boolean? = null

        @JvmField var sniff_override_destination: Boolean? = null

        @JvmField var sniff_timeout: Long? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var network: String? = null

        @JvmField var override_address: String? = null

        @JvmField var override_port: Int? = null
    }

    class Inbound_MixedOptions : Inbound() {
        @JvmField var listen: String? = null

        @JvmField var listen_port: Int? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var udp_timeout: Long? = null

        @JvmField var proxy_protocol: Boolean? = null

        @JvmField var proxy_protocol_accept_no_header: Boolean? = null

        @JvmField var detour: String? = null

        @JvmField var sniff: Boolean? = null

        @JvmField var sniff_override_destination: Boolean? = null

        @JvmField var sniff_timeout: Long? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var users: List<User>? = null

        @JvmField var set_system_proxy: Boolean? = null

        @JvmField var tls: InboundTLSOptions? = null
    }

    class Outbound_SocksOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var version: String? = null

        @JvmField var username: String? = null

        @JvmField var password: String? = null

        @JvmField var network: String? = null

        @JvmField var udp_over_tcp: UDPOverTCPOptions? = null
    }

    class Outbound_HTTPOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var username: String? = null

        @JvmField var password: String? = null

        @JvmField var tls: OutboundTLSOptions? = null

        @JvmField var path: String? = null

        @JvmField var headers: Map<String, String>? = null
    }

    class Outbound_ShadowsocksOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var method: String? = null

        @JvmField var password: String? = null

        @JvmField var plugin: String? = null

        @JvmField var plugin_opts: String? = null

        @JvmField var network: String? = null

        @JvmField var udp_over_tcp: UDPOverTCPOptions? = null

        @JvmField var multiplex: MultiplexOptions? = null
    }

    class Outbound_VMessOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var uuid: String? = null

        @JvmField var security: String? = null

        @JvmField var alter_id: Int? = null

        @JvmField var global_padding: Boolean? = null

        @JvmField var authenticated_length: Boolean? = null

        @JvmField var network: String? = null

        @JvmField var tls: OutboundTLSOptions? = null

        @JvmField var packet_encoding: String? = null

        @JvmField var multiplex: MultiplexOptions? = null

        @JvmField var transport: V2RayTransportOptions? = null
    }

    class Outbound_TrojanOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var password: String? = null

        @JvmField var network: String? = null

        @JvmField var tls: OutboundTLSOptions? = null

        @JvmField var multiplex: MultiplexOptions? = null

        @JvmField var transport: V2RayTransportOptions? = null
    }

    class Outbound_WireGuardOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var system_interface: Boolean? = null

        @JvmField var interface_name: String? = null

        @JvmField var local_address: List<String>? = null

        @JvmField var private_key: String? = null

        @JvmField var peers: List<WireGuardPeer>? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var peer_public_key: String? = null

        @JvmField var pre_shared_key: String? = null

        @JvmField var reserved: String? = null

        @JvmField var workers: Int? = null

        @JvmField var mtu: Int? = null

        @JvmField var network: String? = null
    }

    class Outbound_AmneziaWGOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var system_interface: Boolean? = null

        @JvmField var interface_name: String? = null

        @JvmField var local_address: List<String>? = null

        @JvmField var private_key: String? = null

        @JvmField var peers: List<WireGuardPeer>? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var peer_public_key: String? = null

        @JvmField var pre_shared_key: String? = null

        @JvmField var reserved: String? = null

        @JvmField var workers: Int? = null

        @JvmField var mtu: Int? = null

        @JvmField var network: String? = null

        // AmneziaWG obfuscation parameters.
        @JvmField var jc: Int? = null

        @JvmField var jmin: Int? = null

        @JvmField var jmax: Int? = null

        @JvmField var s1: Int? = null

        @JvmField var s2: Int? = null

        @JvmField var s3: Int? = null

        @JvmField var s4: Int? = null

        @JvmField var h1: String? = null

        @JvmField var h2: String? = null

        @JvmField var h3: String? = null

        @JvmField var h4: String? = null

        @JvmField var i1: String? = null

        @JvmField var i2: String? = null

        @JvmField var i3: String? = null

        @JvmField var i4: String? = null

        @JvmField var i5: String? = null
    }

    class Outbound_HysteriaOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var up: String? = null

        @JvmField var up_mbps: Int? = null

        @JvmField var down: String? = null

        @JvmField var down_mbps: Int? = null

        @JvmField var obfs: String? = null

        @JvmField var auth: String? = null

        @JvmField var auth_str: String? = null

        @JvmField var recv_window_conn: Long? = null

        @JvmField var recv_window: Long? = null

        @JvmField var disable_mtu_discovery: Boolean? = null

        @JvmField var network: String? = null

        @JvmField var tls: OutboundTLSOptions? = null

        @JvmField var server_ports: List<String>? = null

        @JvmField var hop_interval: String? = null
    }

    class Outbound_SSHOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var user: String? = null

        @JvmField var password: String? = null

        @JvmField var private_key: String? = null

        @JvmField var private_key_path: String? = null

        @JvmField var private_key_passphrase: String? = null

        @JvmField var host_key: List<String>? = null

        @JvmField var host_key_algorithms: List<String>? = null

        @JvmField var client_version: String? = null
    }

    class Outbound_ShadowTLSOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var version: Int? = null

        @JvmField var password: String? = null

        @JvmField var tls: OutboundTLSOptions? = null
    }

    class Outbound_ShadowsocksROptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var method: String? = null

        @JvmField var password: String? = null

        @JvmField var obfs: String? = null

        @JvmField var obfs_param: String? = null

        @JvmField var protocol: String? = null

        @JvmField var protocol_param: String? = null

        @JvmField var network: String? = null
    }

    class Outbound_VLESSOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var uuid: String? = null

        @JvmField var flow: String? = null

        @JvmField var encryption: String? = null

        @JvmField var network: String? = null

        @JvmField var tls: OutboundTLSOptions? = null

        @JvmField var multiplex: MultiplexOptions? = null

        @JvmField var transport: V2RayTransportOptions? = null

        @JvmField var packet_encoding: String? = null
    }

    class Outbound_TUICOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var uuid: String? = null

        @JvmField var password: String? = null

        @JvmField var congestion_control: String? = null

        @JvmField var udp_relay_mode: String? = null

        @JvmField var udp_over_stream: Boolean? = null

        @JvmField var zero_rtt_handshake: Boolean? = null

        @JvmField var heartbeat: Long? = null

        @JvmField var network: String? = null

        @JvmField var tls: OutboundTLSOptions? = null
    }

    class Outbound_JuicityOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var uuid: String? = null

        @JvmField var password: String? = null

        @JvmField var network: String? = null

        @JvmField var pin_cert_sha256: String? = null

        @JvmField var tls: OutboundTLSOptions? = null
    }

    class Outbound_Hysteria2Options : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: Long? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var fallback_delay: Long? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var up_mbps: Int? = null

        @JvmField var down_mbps: Int? = null

        @JvmField var obfs: Hysteria2Obfs? = null

        @JvmField var password: String? = null

        @JvmField var network: String? = null

        @JvmField var tls: OutboundTLSOptions? = null

        @JvmField var server_ports: List<String>? = null

        @JvmField var hop_interval: String? = null
    }

    class Outbound_SelectorOptions : Outbound() {
        @JvmField var outbounds: List<String>? = null

        @SerializedName("default")
        @JvmField
        var default_: String? = null
    }

    class Outbound_URLTestOptions : Outbound() {
        @JvmField var outbounds: List<String>? = null

        @JvmField var url: String? = null

        @JvmField var interval: String? = null

        @JvmField var tolerance: Int? = null

        @JvmField var idle_timeout: String? = null

        @JvmField var interrupt_exist_connections: Boolean? = null
    }

    class Rule_DefaultOptions : Rule() {
        @JvmField var inbound: List<String>? = null

        @JvmField var ip_version: Int? = null

        @JvmField var network: List<String>? = null

        @JvmField var auth_user: List<String>? = null

        @JvmField var protocol: List<String>? = null

        @JvmField var domain: List<String>? = null

        @JvmField var domain_suffix: List<String>? = null

        @JvmField var domain_keyword: List<String>? = null

        @JvmField var domain_regex: List<String>? = null

        @JvmField var rule_set: List<String>? = null

        @JvmField var source_ip_is_private: Boolean? = null

        @JvmField var ip_is_private: Boolean? = null

        @JvmField var source_ip_cidr: List<String>? = null

        @JvmField var ip_cidr: List<String>? = null

        @JvmField var source_port: List<Int>? = null

        @JvmField var source_port_range: List<String>? = null

        @JvmField var port: List<Int>? = null

        @JvmField var port_range: List<String>? = null

        @JvmField var process_name: List<String>? = null

        @JvmField var process_path: List<String>? = null

        @JvmField var package_name: List<String>? = null

        @JvmField var user: List<String>? = null

        @JvmField var user_id: List<Int>? = null

        @JvmField var clash_mode: String? = null

        @JvmField var invert: Boolean? = null

        @JvmField var action: String? = null

        @JvmField var outbound: String? = null

        // action = "sniff"
        @JvmField var sniffer: List<String>? = null

        @JvmField var timeout: String? = null

        // action = "resolve"
        @JvmField var strategy: String? = null

        @JvmField var server: String? = null
    }

    class DNSRule_DefaultOptions : DNSRule() {
        @JvmField var inbound: List<String>? = null

        @JvmField var ip_version: Int? = null

        @JvmField var query_type: List<String>? = null

        @JvmField var network: List<String>? = null

        @JvmField var auth_user: List<String>? = null

        @JvmField var protocol: List<String>? = null

        @JvmField var domain: List<String>? = null

        @JvmField var domain_suffix: List<String>? = null

        @JvmField var domain_keyword: List<String>? = null

        @JvmField var domain_regex: List<String>? = null

        @JvmField var rule_set: List<String>? = null

        @JvmField var source_ip_cidr: List<String>? = null

        @JvmField var source_port: List<Int>? = null

        @JvmField var source_port_range: List<String>? = null

        @JvmField var port: List<Int>? = null

        @JvmField var port_range: List<String>? = null

        @JvmField var process_name: List<String>? = null

        @JvmField var process_path: List<String>? = null

        @JvmField var package_name: List<String>? = null

        @JvmField var user: List<String>? = null

        @JvmField var user_id: List<Int>? = null

        @JvmField var action: String? = null

        @JvmField var rcode: String? = null

        @JvmField var clash_mode: String? = null

        @JvmField var invert: Boolean? = null

        @JvmField var server: String? = null

        @JvmField var disable_cache: Boolean? = null

        @JvmField var rewrite_ttl: Int? = null
    }

    class V2RayTransportOptions_HTTPOptions : V2RayTransportOptions() {
        @JvmField var host: List<String>? = null

        @JvmField var path: String? = null

        @JvmField var method: String? = null

        @JvmField var headers: Map<String, String>? = null

        @JvmField var idle_timeout: Long? = null

        @JvmField var ping_timeout: Long? = null
    }

    class V2RayTransportOptions_WebsocketOptions : V2RayTransportOptions() {
        @JvmField var path: String? = null

        @JvmField var headers: Map<String, String>? = null

        @JvmField var max_early_data: Int? = null

        @JvmField var early_data_header_name: String? = null
    }

    class V2RayTransportOptions_GRPCOptions : V2RayTransportOptions() {
        @JvmField var service_name: String? = null

        @JvmField var idle_timeout: Long? = null

        @JvmField var ping_timeout: Long? = null

        @JvmField var permit_without_stream: Boolean? = null
    }

    class V2RayTransportOptions_HTTPUpgradeOptions : V2RayTransportOptions() {
        @JvmField var host: String? = null

        @JvmField var path: String? = null
    }

    class V2RayTransportOptions_XHTTPOptions : V2RayTransportOptions() {
        @JvmField var mode: String? = null

        @JvmField var host: String? = null

        @JvmField var path: String? = null

        // Advanced field from extra config
        @JvmField var download: JsonElement? = null

        @JvmField var xmux: JsonElement? = null

        @JvmField var headers: Map<String, String>? = null

        @JvmField var x_padding_bytes: JsonElement? = null

        @JvmField var no_grpc_header: JsonElement? = null

        @JvmField var no_sse_header: JsonElement? = null

        @JvmField var sc_max_each_post_bytes: JsonElement? = null

        @JvmField var sc_min_posts_interval_ms: JsonElement? = null

        @JvmField var sc_max_buffered_posts: JsonElement? = null

        @JvmField var sc_stream_up_server_secs: JsonElement? = null

        @JvmField var server_max_header_bytes: JsonElement? = null

        @JvmField var x_padding_obfs_mode: JsonElement? = null

        @JvmField var x_padding_key: JsonElement? = null

        @JvmField var x_padding_header: JsonElement? = null

        @JvmField var x_padding_placement: JsonElement? = null

        @JvmField var x_padding_method: JsonElement? = null

        @JvmField var uplink_http_method: JsonElement? = null

        @JvmField var session_placement: JsonElement? = null

        @JvmField var session_key: JsonElement? = null

        @JvmField var seq_placement: JsonElement? = null

        @JvmField var seq_key: JsonElement? = null

        @JvmField var uplink_data_placement: JsonElement? = null

        @JvmField var uplink_data_key: JsonElement? = null

        @JvmField var uplink_chunk_size: JsonElement? = null
    }

    class V2RayTransportOptions_KCPOptions : V2RayTransportOptions() {
        @JvmField var mtu: Int? = null

        @JvmField var tti: Int? = null

        @JvmField var uplink_capacity: Int? = null

        @JvmField var downlink_capacity: Int? = null

        @JvmField var congestion: Boolean? = null

        @JvmField var read_buffer_size: Int? = null

        @JvmField var write_buffer_size: Int? = null

        @JvmField var cwnd_multiplier: Int? = null

        @JvmField var header_type: String? = null

        @JvmField var seed: String? = null
    }

    class Outbound_AnyTLSOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: String? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var network_strategy: String? = null

        @JvmField var network_type: List<String>? = null

        @JvmField var fallback_network_type: List<String>? = null

        @JvmField var fallback_delay: String? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        @JvmField var tls: OutboundTLSOptions? = null

        @JvmField var password: String? = null

        @JvmField var idle_session_check_interval: String? = null

        @JvmField var idle_session_timeout: String? = null
    }

    class Outbound_SnellOptions : Outbound() {
        @JvmField var detour: String? = null

        @JvmField var bind_interface: String? = null

        @JvmField var inet4_bind_address: String? = null

        @JvmField var inet6_bind_address: String? = null

        @JvmField var protect_path: String? = null

        @JvmField var routing_mark: Int? = null

        @JvmField var reuse_addr: Boolean? = null

        @JvmField var connect_timeout: String? = null

        @JvmField var tcp_fast_open: Boolean? = null

        @JvmField var tcp_multi_path: Boolean? = null

        @JvmField var udp_fragment: Boolean? = null

        @JvmField var domain_strategy: String? = null

        @JvmField var network_strategy: String? = null

        @JvmField var network_type: List<String>? = null

        @JvmField var fallback_network_type: List<String>? = null

        @JvmField var fallback_delay: String? = null

        @JvmField var server: String? = null

        @JvmField var server_port: Int? = null

        // Snell specific options
        @JvmField var psk: String? = null

        @JvmField var version: Int? = null

        @JvmField var network: String? = null

        @JvmField var obfs_mode: String? = null

        @JvmField var obfs_host: String? = null

        @JvmField var reuse: Boolean? = null
    }
}
