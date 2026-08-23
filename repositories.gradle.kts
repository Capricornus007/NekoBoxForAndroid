repositories {
    // dl.google.com intermittently terminates TLS handshakes for Media3 from
    // some networks. Route only that group through the verified mirror; all
    // other artifacts continue to prefer their official repositories below.
    exclusiveContent {
        forRepository {
            maven {
                name = "AliyunGoogleMedia3"
                url = uri("https://maven.aliyun.com/repository/google")
            }
        }
        filter {
            includeGroup("androidx.media3")
        }
    }
    google()
    mavenCentral()
    gradlePluginPortal()
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
}
