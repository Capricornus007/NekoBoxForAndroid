package byedpi

import (
	"errors"
	"slices"
	"strings"
	"unicode"
)

// reservedArgs 是 byeDPI 内建参数中由本桥接层自己掌控、不允许用户覆写的部分。
// 带值者连同其值一并丢弃，独立者直接丢弃。桥接层在 buildArgs 里固定注入
// --ip/--port/--protect-path，若让用户的字符串盖过去，私有 socketpair 通道
// 就会退化成真的监听 TCP 端口，暴露在本机甚至区域网络上。
var reservedArgsWithValue = []string{
	"-i", "--ip",
	"-p", "--port",
	"-P", "--protect-path",
	"-w", "--pidfile",
	"-y", "--cache-file",
	"-B", "--copy",
	"-x", "--debug",
	"-H", "--hosts",
	"-j", "--ipset",
	"-V", "--pf",
}

var reservedArgsStandalone = []string{
	"-D", "--daemon",
	"-E", "--transparent",
	"-h", "--help",
	"-v", "--version",
}

// sanitizeArgs 丢掉上述保留参数，其余原样保留（--split/--disorder/--fake/--tlsrec 等
// 正常绕深测策略全部可用）。
func sanitizeArgs(args []string) []string {
	filtered := make([]string, 0, len(args))
	for index := 0; index < len(args); index++ {
		arg := args[index]
		if slices.Contains(reservedArgsWithValue, arg) {
			index++
			continue
		}
		if slices.Contains(reservedArgsStandalone, arg) {
			continue
		}
		filtered = append(filtered, arg)
	}
	return filtered
}

// splitArgs 以 POSIX shell 规则切字符串：空白分词，单/双引号可包含空白，
// 引号外的反斜线转义下一字符，单引号内的反斜线为字面值。
// 不支持变量展开与子命令——那类写法在 byeDPI 参数里没有意义。
func splitArgs(line string) ([]string, error) {
	args := []string{}
	field := make([]rune, 0, 16)
	var quote rune
	escaped := false
	started := false

	flush := func() {
		if started {
			args = append(args, string(field))
			field = field[:0]
			started = false
		}
	}

	for _, r := range line {
		switch {
		case escaped:
			field = append(field, r)
			escaped = false
			started = true
		case quote == '\'':
			if r == '\'' {
				quote = 0
			} else {
				field = append(field, r)
			}
		case quote == '"':
			switch r {
			case '"':
				quote = 0
			case '\\':
				escaped = true
			default:
				field = append(field, r)
			}
		case r == '\\':
			escaped = true
			started = true
		case r == '\'' || r == '"':
			quote = r
			started = true
		case unicode.IsSpace(r):
			flush()
		default:
			field = append(field, r)
			started = true
		}
	}
	if escaped {
		field = append(field, '\\')
		args = append(args, string(field))
	} else if quote != 0 {
		return nil, errors.New("byedpi: unterminated quote in cli strategy")
	}
	flush()
	return args, nil
}

// buildArgs 组合送进 byeDPI main() 的完整 argv。
func buildArgs(cli string) ([]string, error) {
	userArgs, err := splitArgs(cli)
	if err != nil {
		return nil, err
	}
	userArgs = sanitizeArgs(userArgs)
	args := []string{
		"byedpi",
		"--ip", "127.0.0.1",
		"--port", "0",
		"--protect-path", "protect_path",
	}
	return append(args, userArgs...), nil
}

// bridgeKey 是共用桥接的识别键：同一份 CLI 策略（忽略首尾空白）复用同一个
// byeDPI runner，只做 TrimSpace，不做大小写或内部空白归一化。
func bridgeKey(cli string) string {
	return strings.TrimSpace(cli)
}
