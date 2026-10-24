package libcore

const (
	geoipDat       = "geoip.db"
	geositeDat     = "geosite.db"
	geoipVersion   = "geoip.version.txt"
	geositeVersion = "geosite.version.txt"
)

var apkAssetPrefixSingBox = "sing-box/"
var internalAssetsPath string
var externalAssetsPath string

var assetsReady <-chan struct{}

// Wait off the Android main thread before opening databases or listing assets.
func WaitForAssets() {
	if assetsReady != nil {
		<-assetsReady
	}
}
