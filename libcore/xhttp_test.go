package libcore

import (
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	xhttp "github.com/sagernet/sing-box/transport/v2rayxhttp"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

type xhttpTestDialer struct{}

func (xhttpTestDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, destination.String())
}
func (xhttpTestDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return net.ListenPacket("udp", "127.0.0.1:0")
}

type xhttpEchoHandler struct{}

func (xhttpEchoHandler) NewConnectionEx(_ context.Context, conn net.Conn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	go func() {
		_, err := io.Copy(conn, conn)
		_ = conn.Close()
		if onClose != nil {
			onClose(err)
		}
	}()
}

func TestXHTTPLiveBidirectionalModes(t *testing.T) {
	for _, mode := range []string{"packet-up", "stream-up", "stream-one"} {
		t.Run(mode, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			logger := log.NewNOPFactory().NewLogger("xhttp-test")
			ctx = service.ContextWith[log.ContextLogger](ctx, logger)
			options := option.V2RayXHTTPOptions{Mode: mode, V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{Path: "/transport-test/"}}
			server, err := xhttp.NewServer(ctx, logger, options, nil, xhttpEchoHandler{})
			if err != nil {
				t.Fatal(err)
			}
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			defer server.Close()
			go server.Serve(listener)
			client, err := xhttp.NewClient(ctx, xhttpTestDialer{}, M.SocksaddrFromNet(listener.Addr()), options, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			for _, payload := range []string{"first payload", "second independent connection", strings.Repeat("split packet payload", 1024)} {
				conn, err := client.DialContext(ctx)
				if err != nil {
					t.Fatal(err)
				}
				done := make(chan error, 1)
				go func() {
					defer conn.Close()
					if n, err := io.WriteString(conn, payload); err != nil {
						done <- err
						return
					} else if n != len(payload) {
						done <- fmt.Errorf("short write: %d of %d", n, len(payload))
						return
					}
					response := make([]byte, len(payload))
					_, err := io.ReadFull(conn, response)
					if err == nil && string(response) != payload {
						err = io.ErrUnexpectedEOF
					}
					done <- err
				}()
				select {
				case err := <-done:
					if err != nil {
						t.Fatal(err)
					}
				case <-ctx.Done():
					_ = conn.Close()
					t.Fatal(ctx.Err())
				}
			}
		})
	}
}
