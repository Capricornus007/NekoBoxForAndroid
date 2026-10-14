#!/usr/bin/env python3
"""Do not read packet buffers after the upload pipe takes ownership."""
import pathlib
import sys

p = pathlib.Path(sys.argv[1]) / 'transport/v2rayxhttp/writer.go'
s = p.read_text()
old = '''for _, buff := range buffer.MultiBuffer {
		if err := w.WriteMultiBuffer(buf.MultiBuffer{buff}); err != nil {
			return writed, err
		}
		writed += int(buff.Len())
	}'''
new = '''for index, buff := range buffer.MultiBuffer {
		size := int(buff.Len())
		if err := w.WriteMultiBuffer(buf.MultiBuffer{buff}); err != nil {
			buf.ReleaseMulti(buffer.MultiBuffer[index+1:])
			return writed, err
		}
		writed += size
	}'''
if new not in s:
    if old not in s:
        raise RuntimeError('Missing XHTTP upload writer anchor')
    p.write_text(s.replace(old,new,1))
