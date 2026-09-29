package io.github.mochiuaena.triage.source;

import java.util.List;
import static io.github.mochiuaena.triage.source.SourceModels.*;

final class SourceVersionCheck {
    private SourceVersionCheck() {}
    static VersionCheck compare(String runtimeHash, List<Symbol> files) {
        if (runtimeHash == null) return new VersionCheck("UNKNOWN", "运行未提供可核验的构建源码摘要。", null, null);
        var hashes = files.stream().map(Symbol::fileHash).distinct().toList();
        if (hashes.isEmpty()) return new VersionCheck("UNAVAILABLE", "当前索引没有可核对的源码文件。", runtimeHash, null);
        if (hashes.contains(runtimeHash)) return new VersionCheck("MATCHED", "运行构建清单中的源码摘要与本机索引一致。", runtimeHash, runtimeHash);
        return new VersionCheck("DIFFERENT", "运行构建清单中的源码摘要与当前索引不同。", runtimeHash, hashes.size() == 1 ? hashes.getFirst() : null);
    }
}
