package libcore

import "libcore/localapi"

type LocalAPIHandler interface {
	Call(operation string, parameters string) string
}

type LocalAPIServer struct {
	server *localapi.Server
}

func StartLocalAPI(port int32, token string, handler LocalAPIHandler) (*LocalAPIServer, error) {
	server, err := localapi.Start(int(port), token, handler)
	if err != nil {
		return nil, err
	}
	return &LocalAPIServer{server: server}, nil
}

func (s *LocalAPIServer) Close() error {
	return s.server.Close()
}
