#!/usr/bin/env python3
"""Preserve DNS HTTPS/H3 URL query parameters instead of escaping them as path."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
for name in ['dns/transport/https.go', 'dns/transport/quic/http3.go']:
    file = root / name
    text = file.read_text()
    old = '\terr = sHTTP.URLSetPath(&destinationURL, path)'
    new = '''\trequestPath, parseErr := url.Parse(path)
\tif parseErr != nil || requestPath.Scheme != "" || requestPath.Host != "" || requestPath.Fragment != "" {
\t\treturn nil, E.New("invalid DNS request path")
\t}
\tdestinationURL.RawQuery = requestPath.RawQuery
\terr = sHTTP.URLSetPath(&destinationURL, requestPath.EscapedPath())'''
    if new not in text:
        if old not in text:
            raise RuntimeError(f'DNS patch anchor missing: {name}')
        file.write_text(text.replace(old, new, 1))
