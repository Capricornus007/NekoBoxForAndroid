package libcore

import (
	"context"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"io"
	"net"
	"testing"
	"time"

	upstream "github.com/exclavenetwork/sing-juicity"
	"github.com/gofrs/uuid/v5"
	boxtls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	local "libcore/protocol/juicity"
)

type juicityEcho struct{ xhttpEchoHandler }

func (juicityEcho) NewPacketConnectionEx(_ context.Context, conn N.PacketConn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	go func() {
		defer conn.Close()
		for {
			buffer := buf.NewPacket()
			buffer.Resize(N.CalculateFrontHeadroom(conn), 0)
			destination, err := conn.ReadPacket(buffer)
			if err != nil {
				buffer.Release()
				if onClose != nil {
					onClose(err)
				}
				return
			}
			if err = conn.WritePacket(buffer, destination); err != nil {
				return
			}
		}
	}()
}

func TestJuicityLiveTLSAndTCPUDP(t *testing.T) {
	ctx, cancel := context.WithTimeout(service.ContextWithDefaultRegistry(context.Background()), 5*time.Second)
	defer cancel()
	logger := log.NewNOPFactory().NewLogger("juicity-test")
	cert, err := boxtls.GenerateKeyPair(nil, nil, time.Now, "fixture.test")
	if err != nil {
		t.Fatal(err)
	}
	key, err := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	tlsServer, err := boxtls.NewSTDServer(ctx, logger, option.InboundTLSOptions{Enabled: true, ALPN: []string{"h3"}, Certificate: []string{string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]}))}, Key: []string{string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))}})
	if err != nil {
		t.Fatal(err)
	}
	defer tlsServer.Close()
	server, err := upstream.NewService[int](upstream.ServiceOptions{Context: ctx, Logger: logger, TLSConfig: tlsServer, Handler: juicityEcho{}})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	id := uuid.Must(uuid.NewV4())
	server.UpdateUsers([]int{1}, [][16]byte{[16]byte(id)}, []string{"test-password"})
	socket, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	if err = server.Start(socket); err != nil {
		t.Fatal(err)
	}
	endpoint := M.SocksaddrFromNet(socket.LocalAddr())
	outbound, err := local.NewOutbound(ctx, nil, logger, "test", local.JuicityOutboundOptions{ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: endpoint.Port}, UUID: id.String(), Password: "test-password", OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{TLS: &option.OutboundTLSOptions{Enabled: true, ServerName: "fixture.test", Certificate: []string{string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]}))}}}})
	if err != nil {
		t.Fatal(err)
	}
	defer outbound.(io.Closer).Close()
	for _, network := range []string{"tcp", "udp"} {
		conn, err := outbound.DialContext(ctx, network, M.ParseSocksaddr("echo.test:443"))
		if err != nil {
			t.Fatal(err)
		}
		conn.SetDeadline(time.Now().Add(3 * time.Second))
		payload := "juicity-migration-" + network + hex.EncodeToString(id[:])
		if _, err = io.WriteString(conn, payload); err != nil {
			conn.Close()
			t.Fatal(err)
		}
		response := make([]byte, len(payload))
		if _, err = io.ReadFull(conn, response); err != nil {
			conn.Close()
			t.Fatal(err)
		}
		conn.Close()
		if string(response) != payload {
			t.Fatal("Juicity changed payload")
		}
	}
}
