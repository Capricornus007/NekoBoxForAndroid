package libcore

import (
	"context"
	"fmt"
	"testing"
)

func TestConnectionHistoryRequiresDebugAndIsBounded(t *testing.T) {
	for _, level := range []string{"warn", "info", "debug", "trace"} {
		instance, err := NewSingBoxInstance(fmt.Sprintf(`{"log":{"level":%q},"outbounds":[{"type":"direct","tag":"direct"}]}`, level), nil)
		if err != nil {
			t.Fatal(err)
		}
		enabled := level == "debug" || level == "trace"
		if (instance.diagnostics != nil) != enabled {
			t.Fatalf("history at %s", level)
		}
		_ = instance.Close()
	}
	collector := &connectionDiagnostics{}
	for i := 0; i < 200; i++ {
		collector.GroupDial(context.Background(), fmt.Sprint(i))
	}
	snapshot := collector.snapshot()
	if len(snapshot) != 128 || snapshot[0]["outbound"] != "72" || snapshot[127]["outbound"] != "199" {
		t.Fatal("ring lost ordering or exceeded bound")
	}
}
