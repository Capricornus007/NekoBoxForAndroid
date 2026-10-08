package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"
)

// Proton's API is versioned through these headers; omitting them gets a v2
// response shape back, which is why they are not optional decoration.
const (
	protonAPIVersion = "3"
	protonAccept     = "application/vnd.proton.api.v3+json"
)

// errSessionExpired is reported instead of attempting a refresh: refreshing means
// re-running SRP, which belongs to login, not to every read command.
var errSessionExpired = errors.New("stored session was rejected, please log in again")

// Field names and types follow Proton's own client models
// (ProtonVPN/android-app servers/api/{LogicalServer,ConnectingDomain}.kt), whose
// @SerialName annotations are the wire format of /vpn/logicals.
type logicalServer struct {
	ID              string `json:"ID"`
	Name            string `json:"Name"`
	State           string `json:"State"`
	Tier            int    `json:"Tier"`
	Features        int    `json:"Features"`
	Country         string `json:"ExitCountry"`
	City            string `json:"City"`
	Servers         []connectingDomain
	StatusReference struct {
		Penalty float64 `json:"Penalty"`
		Cost    int     `json:"Cost"`
	} `json:"StatusReference"`
}

// Server features, from the same constants file: bit 4 marks IPv6 support.
const featureIPv6 = 16

type connectingDomain struct {
	EntryIP          string                     `json:"EntryIP"`
	Domain           string                     `json:"Domain"`
	Status           *int                       `json:"Status"`
	X25519PublicKey  string                     `json:"X25519PublicKey"`
	EntryPerProtocol map[string]serverEntryInfo `json:"EntryPerProtocol"`
}

type serverEntryInfo struct {
	IPv4  string `json:"IPv4"`
	Ports []int  `json:"Ports"`
}

// wireGuardEntry is the per-protocol record Proton keys "wireguard".
func (d connectingDomain) wireGuardEntry() (serverEntryInfo, bool) {
	e, ok := d.EntryPerProtocol[protocolWireGuardName]
	return e, ok
}

const protocolWireGuardName = "wireguard"

type logicalServersResp struct {
	LogicalServers []logicalServer `json:"LogicalServers"`
}

type nodeView struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	Penalty   float64 `json:"penalty"`
	Tier      int     `json:"tier"`
	IPv6      bool    `json:"ipv6,omitempty"`
	Country   string  `json:"country,omitempty"`
	City      string  `json:"city,omitempty"`
	Endpoint  string  `json:"endpoint,omitempty"`
	Domain    string  `json:"domain,omitempty"`
	PublicKey string  `json:"wgPublicKey,omitempty"`
	Port      int     `json:"port,omitempty"`
}

type nodesOutput struct {
	OK      bool       `json:"ok"`
	Servers []nodeView `json:"servers,omitempty"`
	// Dropped counts servers that came back but cannot be used (no WireGuard
	// key, or offline), so the UI can tell "few nodes" from "broken response".
	Dropped int    `json:"dropped"`
	Error   string `json:"error,omitempty"`
}

func runNodes(args []string, stdout, stderr io.Writer) int {
	command := flag.NewFlagSet("nodes", flag.ContinueOnError)
	command.SetOutput(stderr)
	statePath := command.String("state", "", "stored session to read (required)")
	limit := command.Int("limit", 0, "return at most this many usable nodes")
	country := command.String("country", "", "only this exit country, e.g. JP")
	apiURL := command.String("api-url", defaultProtonAPIURL, "API base URL")
	timeoutSec := command.Int("timeout", 30, "overall deadline in seconds")
	if err := command.Parse(args); err != nil {
		return 2
	}
	if *statePath == "" {
		fmt.Fprintln(stderr, "protonvpn: nodes: --state is required")
		return 2
	}

	cred, err := readCredentialFile(*statePath)
	if err != nil {
		return emitNodes(stdout, nodesOutput{Error: err.Error()})
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*timeoutSec)*time.Second)
	defer cancel()

	resp, err := fetchLogicalServers(ctx, *apiURL, cred)
	if err != nil {
		return emitNodes(stdout, nodesOutput{Error: err.Error()})
	}

	servers, dropped := selectNodes(resp, *country, *limit)
	if len(servers) == 0 {
		// Reporting an empty success would let the UI say "this account has no
		// servers", which is a different claim from "nothing in the list was usable".
		return emitNodes(stdout, nodesOutput{
			Dropped: dropped,
			Error:   fmt.Sprintf("no usable servers in the response (%d dropped)", dropped),
		})
	}
	return emitNodes(stdout, nodesOutput{OK: true, Servers: servers, Dropped: dropped})
}

func fetchLogicalServers(ctx context.Context, apiURL string, cred storedCredential) (*logicalServersResp, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(apiURL, "/")+"/vpn/logicals", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+cred.AccessToken)
	req.Header.Set("x-pm-uid", cred.UID)
	req.Header.Set("x-pm-appversion", protonAppVersion)
	req.Header.Set("x-pm-apiversion", protonAPIVersion)
	req.Header.Set("Accept", protonAccept)

	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()

	switch {
	case res.StatusCode == http.StatusUnauthorized, res.StatusCode == http.StatusForbidden:
		return nil, errSessionExpired
	case res.StatusCode == http.StatusUnprocessableEntity:
		// 422 在 Proton 那邊幾乎一定是人類驗證（Code 9001）。節點清單這條路目前沒有
		// 站內解驗證碼的流程（那是 login 的功能），但至少要講出發生了什麼。
		// 不能吐 Proton 的原文：那句話裡含驗證網址，網址裡含挑戰 token，進了 Kotlin
		// 的日誌就等于把短效憑證外洩。
		body, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		var api struct {
			Code int `json:"Code"`
		}
		_ = json.Unmarshal(body, &api)
		if api.Code == hvAPIErrorCode {
			return nil, errors.New("Proton wants a CAPTCHA for this network before listing servers: sign in again from the Proton page")
		}
		return nil, fmt.Errorf("API returned HTTP %d (code %d)", res.StatusCode, api.Code)
	case res.StatusCode != http.StatusOK:
		return nil, fmt.Errorf("API returned HTTP %d", res.StatusCode)
	}

	var out logicalServersResp
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("decode server list: %w", err)
	}
	if len(out.LogicalServers) == 0 {
		return nil, errors.New("API returned an empty server list")
	}
	return &out, nil
}

// selectNodes keeps only entries that can actually be dialed: a load-balancing
// group whose connecting domain carries no X25519 key is worse than no entry,
// because the UI would offer a node that cannot connect.
func selectNodes(resp *logicalServersResp, country string, limit int) ([]nodeView, int) {
	var usable []nodeView
	dropped := 0

	for _, ls := range resp.LogicalServers {
		if ls.State != "" && ls.State != "up" {
			dropped++
			continue
		}
		if country != "" && !strings.EqualFold(ls.Country, country) {
			continue
		}
		var chosen *connectingDomain
		for i := range ls.Servers {
			d := &ls.Servers[i]
			if d.X25519PublicKey == "" {
				continue
			}
			// A missing Status must not hide a node, so only an explicit 0 rejects it.
			if d.Status != nil && *d.Status == 0 {
				continue
			}
			chosen = d
			break
		}
		if chosen == nil {
			dropped++
			continue
		}
		entry, hasEntry := chosen.wireGuardEntry()
		usable = append(usable, nodeView{
			ID:        ls.ID,
			Name:      ls.Name,
			Penalty:   ls.StatusReference.Penalty,
			Tier:      ls.Tier,
			IPv6:      ls.Features&featureIPv6 != 0,
			Country:   ls.Country,
			City:      ls.City,
			Endpoint:  chosen.entry(entry, hasEntry),
			Domain:    chosen.Domain,
			PublicKey: chosen.X25519PublicKey,
			Port:      chosen.port(entry, hasEntry),
		})
	}

	// Proton ranks servers by the penalty its own balancer computes; the list has
	// no load field, so that is the only ordering available here.
	sort.SliceStable(usable, func(i, j int) bool { return usable[i].Penalty < usable[j].Penalty })
	if limit > 0 && len(usable) > limit {
		usable = usable[:limit]
	}
	return usable, dropped
}

// entry prefers the per-protocol IPv4, then the plain EntryIP, then the domain.
func (d connectingDomain) entry(entry serverEntryInfo, hasEntry bool) string {
	if hasEntry && entry.IPv4 != "" {
		return entry.IPv4
	}
	if d.EntryIP != "" {
		return d.EntryIP
	}
	return d.Domain
}

func (d connectingDomain) port(entry serverEntryInfo, hasEntry bool) int {
	if hasEntry {
		for _, port := range entry.Ports {
			if port > 0 && port <= 65535 {
				return port
			}
		}
	}
	return defaultWireGuardPort
}

const defaultWireGuardPort = 51820

func readCredentialFile(path string) (storedCredential, error) {
	payload, err := os.ReadFile(path)
	if err != nil {
		return storedCredential{}, fmt.Errorf("read session: %w", err)
	}
	var cred storedCredential
	if err := json.Unmarshal(payload, &cred); err != nil {
		return storedCredential{}, fmt.Errorf("parse session: %w", err)
	}
	if cred.AccessToken == "" || cred.UID == "" {
		return storedCredential{}, errors.New("stored session is missing its token")
	}
	return cred, nil
}

func emitNodes(w io.Writer, out nodesOutput) int {
	if err := json.NewEncoder(w).Encode(out); err != nil {
		return 1
	}
	if !out.OK {
		return 1
	}
	return 0
}
