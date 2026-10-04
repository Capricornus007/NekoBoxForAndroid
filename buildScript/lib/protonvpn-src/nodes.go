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

type logicalServer struct {
	ID      int      `json:"ID"`
	Name    string   `json:"Name"`
	Label   string   `json:"Label"`
	State   string   `json:"State"`
	Status  int      `json:"Status"`
	Load    int      `json:"Load"`
	Tier    int      `json:"Tier"`
	Country string   `json:"ExitCountry"`
	City    string   `json:"ExitCity"`
	Servers []physic `json:"Servers"`
}

type physic struct {
	EntryIP string `json:"EntryIP"`
	Domain  string `json:"Domain"`

	// The WireGuard key is the one field Proton's clients disagree about in
	// casing, so all observed spellings are accepted rather than guessing one.
	WgPublicKey    string `json:"WgPublicKey"`
	WrGwPublicKey  string `json:"WrGwPublicKey"`
	WgPublicKeyLow string `json:"wg_public_key"`

	TCPPorts []int `json:"TCPPorts"`
	UDPPorts []int `json:"UDPPorts"`
}

func (p physic) publicKey() string {
	for _, k := range []string{p.WgPublicKey, p.WrGwPublicKey, p.WgPublicKeyLow} {
		if k != "" {
			return k
		}
	}
	return ""
}

type logicalServersResp struct {
	LogicalServers []logicalServer `json:"LogicalServers"`
}

type nodeView struct {
	ID        int    `json:"id"`
	Name      string `json:"name"`
	Load      int    `json:"load"`
	Tier      int    `json:"tier"`
	Country   string `json:"country,omitempty"`
	City      string `json:"city,omitempty"`
	Endpoint  string `json:"endpoint,omitempty"`
	Domain    string `json:"domain,omitempty"`
	PublicKey string `json:"wgPublicKey,omitempty"`
	Port      int    `json:"port,omitempty"`
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
// group whose physical server carries no WireGuard key is worse than no entry,
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
		var chosen *physic
		for i := range ls.Servers {
			if ls.Servers[i].publicKey() != "" {
				chosen = &ls.Servers[i]
				break
			}
		}
		if chosen == nil {
			dropped++
			continue
		}
		usable = append(usable, nodeView{
			ID:        ls.ID,
			Name:      ls.Name,
			Load:      ls.Load,
			Tier:      ls.Tier,
			Country:   ls.Country,
			City:      ls.City,
			Endpoint:  chosen.EntryIP,
			Domain:    chosen.Domain,
			PublicKey: chosen.publicKey(),
			Port:      pickPort(chosen),
		})
	}

	sort.SliceStable(usable, func(i, j int) bool { return usable[i].Load < usable[j].Load })
	if limit > 0 && len(usable) > limit {
		usable = usable[:limit]
	}
	return usable, dropped
}

func pickPort(p *physic) int {
	for _, ports := range [][]int{p.UDPPorts, p.TCPPorts} {
		for _, port := range ports {
			if port > 0 && port <= 65535 {
				return port
			}
		}
	}
	return 51820
}

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
