// Copyright 2016 Cong Ding
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package stun

import (
	"net"
)

// Client is a STUN client, which can be set STUN server address and is used
// to discover NAT type.
type Client struct {
	serverAddr string
}

// NewClient returns a client without network connection. The network
// connection will be build when calling Discover function.
func NewClient() *Client {
	return new(Client)
}

// SetServerAddr allows user to set the transport layer STUN server address.
func (c *Client) SetServerAddr(address string) {
	c.serverAddr = address
}

// listen resolves the server address and opens a fresh UDP socket for one run.
func (c *Client) listen() (net.PacketConn, *net.UDPAddr, error) {
	if c.serverAddr == "" {
		c.serverAddr = DefaultServerAddr
	}
	serverUDPAddr, err := net.ResolveUDPAddr("udp", c.serverAddr)
	if err != nil {
		return nil, nil, err
	}
	conn, err := net.ListenUDP("udp", nil)
	if err != nil {
		return nil, nil, err
	}
	return conn, serverUDPAddr, nil
}

// Discover contacts the STUN server and gets the response of NAT type, host
// for UDP punching.
func (c *Client) Discover() (NATType, *Host, error, bool) {
	conn, serverUDPAddr, err := c.listen()
	if err != nil {
		return NATError, nil, err, false
	}
	defer conn.Close()
	return c.discover(conn, serverUDPAddr)
}

func (c *Client) BehaviorTest() (*NATBehavior, error) {
	conn, serverUDPAddr, err := c.listen()
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	return c.behaviorTest(conn, serverUDPAddr)
}
