package libcore

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"math"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/sagernet/sing-box/common/dialer"
	M "github.com/sagernet/sing/common/metadata"
)

const (
	locationLookupTimeout = 10 * time.Second
	locationResponseLimit = 8192
)

// locationRootCAs replaces the system roots only in tests.
var locationRootCAs *x509.CertPool

func validateLocationURL(link string) error {
	u, err := url.Parse(link)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
		return errors.New("location lookup requires an HTTPS URL without credentials or a fragment")
	}
	return nil
}

// decodeLocation accepts only finite, in-range numeric coordinates and returns them as
// "latitude,longitude" in plain decimal notation.
func decodeLocation(content []byte) (string, error) {
	var result struct {
		Latitude  *float64 `json:"latitude"`
		Longitude *float64 `json:"longitude"`
	}
	if err := json.Unmarshal(content, &result); err != nil || result.Latitude == nil || result.Longitude == nil {
		return "", errors.New("location service did not return coordinates")
	}
	lat, lon := *result.Latitude, *result.Longitude
	if math.IsNaN(lat) || math.IsInf(lat, 0) || math.Abs(lat) > 90 ||
		math.IsNaN(lon) || math.IsInf(lon, 0) || math.Abs(lon) > 180 {
		return "", errors.New("location service returned invalid coordinates")
	}
	return strconv.FormatFloat(lat, 'f', -1, 64) + "," + strconv.FormatFloat(lon, 'f', -1, 64), nil
}

// LookupOutboundLocation asks the service at link for the approximate location of the exit
// address of the outbound or endpoint tag. The request leaves only through that outbound,
// never directly, and ends with the instance or after locationLookupTimeout. Errors never
// carry the response.
func LookupOutboundLocation(i *BoxInstance, tag, link string) (location string, err error) {
	defer deferPanicToError("box.LookupOutboundLocation", func(err_ error) { err = err_ })
	if err := validateLocationURL(link); err != nil {
		return "", err
	}
	if i == nil {
		return "", errors.New("location lookup requires a running proxy")
	}
	i.access.Lock()
	lifetime, release, err := i.admitCoreWorkLocked()
	i.access.Unlock()
	if err != nil {
		return "", errors.New("location lookup requires a running proxy")
	}
	defer release()
	if _, loaded := i.Box.Outbound().Outbound(tag); !loaded {
		return "", errors.New("location lookup outbound is unavailable")
	}
	ctx, cancel := context.WithTimeout(lifetime, locationLookupTimeout)
	defer cancel()
	detour := dialer.NewDetour(i.Box.Outbound(), tag, true)
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			return detour.DialContext(ctx, network, M.ParseSocksaddr(address))
		},
		TLSClientConfig:   &tls.Config{RootCAs: locationRootCAs},
		DisableKeepAlives: true,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return errors.New("location lookup redirects are not allowed")
		},
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return "", errors.New("invalid location lookup URL")
	}
	request.Header.Set("Accept", "application/json")
	response, err := client.Do(request)
	if err != nil {
		return "", errors.New("location lookup failed")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", errors.New("location service is unavailable")
	}
	content, err := readAllLimited(response.Body, locationResponseLimit)
	if err != nil {
		return "", errors.New("location response could not be read")
	}
	return decodeLocation(content)
}
