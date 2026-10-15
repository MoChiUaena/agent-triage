package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.PrModels.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

/** Narrow change reminders, not a vulnerability scanner or an approval decision. */
@Component
public class PrDiffAnalyzer {
    private record Line(int number, String side, String text) {}
    private record Parsed(List<Line> lines, boolean complete) {}
    private record Rule(String category, String title, Pattern pattern, String extension, String side, String reason, String check) {}
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
    private static Rule rule(String category, String title, String regex, String extension, String side, String reason, String check) {
        return new Rule(category, title, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), extension, side, reason, check);
    }
    private static final List<Rule> RULES = List.of(
        rule("AUTH_RULE_CHANGE", "访问控制规则变更", "\\.permitAll\\s*\\(|\\.csrf\\s*\\(.*\\.disable\\s*\\(", ".java", "RIGHT",
            "新增行包含允许匿名访问或关闭 CSRF 的调用；是否符合预期还需核对匹配路径与调用上下文。", "检查匿名与已登录请求的访问边界，并补充对应行为验证。"),
        rule("DESTRUCTIVE_SQL", "数据删除或结构删除语句", "\\b(?:DROP\\s+(?:TABLE|COLUMN|DATABASE)|TRUNCATE\\s+TABLE)\\b", ".sql", "RIGHT",
            "新增 SQL 行包含破坏性结构或数据操作，规则未判断环境、事务和备份条件。", "核对迁移顺序、数据保留和恢复方案，并在副本上验证。"),
        rule("SCHEMA_AUTOMATION_CHANGE", "自动建表配置变更", "(?:ddl-auto|hbm2ddl\\.auto)\\s*[:=]\\s*[\"']?(?:create(?:-drop)?|update)\\b", "config", "RIGHT",
            "新增配置行使用自动创建或修改数据库结构的模式。", "确认配置生效的环境，核对迁移脚本与数据保留要求。"),
        rule("PROCESS_EXECUTION_CHANGE", "外部进程调用变更", "Runtime\\.getRuntime\\s*\\(\\s*\\)\\.exec\\s*\\(|new\\s+ProcessBuilder\\s*\\(", ".java", "RIGHT",
            "新增行包含外部进程创建；尚未追踪参数来源或进程生命周期。", "核对参数来源、执行权限、超时和输出读取。"),
        rule("PUBLIC_CONTRACT_REMOVAL", "公开方法签名删除", "^\\s*public\\s+(?:(?:static|final|synchronized|abstract|default)\\s+)*(?:[\\w<>?,.\\[\\]]+\\s+)+[\\w]+\\s*\\(", ".java", "LEFT",
            "删除行包含公开方法签名；可能是移动、改名或接口调整，尚未分析调用方。", "对照替代方法、调用方和兼容性要求，检查是否需要迁移。")
    );

    Analysis analyze(Key key, Context pull, Diff diff) {
        var files = new ArrayList<FileChange>(); var findings = new ArrayList<Finding>(); var gaps = new ArrayList<String>(diff.gaps());
        boolean findingsClipped = false;
        for (PatchFile file : diff.files()) {
            Parsed parsed = file.patch() == null || file.patch().isBlank() ? null : parse(file);
            String coverage = parsed == null ? file.additions() + file.deletions() == 0 ? "METADATA_ONLY" : "NO_PATCH"
                : parsed.complete() ? "COMPLETE" : "INCOMPLETE";
            files.add(new FileChange(GithubPrClient.clean(file.file()), GithubPrClient.clean(file.previousFile()), file.status(), file.additions(), file.deletions(), coverage));
            if (coverage.equals("NO_PATCH")) gaps.add(GithubPrClient.clean(file.file()) + "：GitHub 未提供可用补丁，可能是二进制或被省略的差异，未进行行级检查。");
            if (coverage.equals("INCOMPLETE")) gaps.add(GithubPrClient.clean(file.file()) + "：补丁行数或区段不完整，未使用不确定行号生成提醒。");
            if (parsed == null || !parsed.complete()) continue;
            var matched = new HashSet<String>();
            for (Line line : parsed.lines()) {
                String text = line.text().strip();
                if (text.startsWith("//") || text.startsWith("/*") || text.startsWith("*") || text.startsWith("--") || text.startsWith("#")) continue;
                for (Rule rule : RULES) {
                    String filename = file.file().toLowerCase(Locale.ROOT);
                    boolean extension = rule.extension().equals("config") ? filename.endsWith(".yml") || filename.endsWith(".yaml") || filename.endsWith(".properties") : filename.endsWith(rule.extension());
                    if (!extension || !rule.side().equals(line.side()) || matched.contains(rule.category()) || !rule.pattern().matcher(text).find()) continue;
                    matched.add(rule.category());
                    if (findings.size() >= 12) { findingsClipped = true; continue; }
                    String path = line.side().equals("LEFT") && !file.previousFile().isEmpty() ? file.previousFile() : file.file();
                    String sha = line.side().equals("LEFT") ? diff.mergeBaseSha() : pull.headSha();
                    String encoded = Arrays.stream(path.split("/")).map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")).reduce((a, b) -> a + "/" + b).orElse("");
                    findings.add(new Finding("PR-CHANGE-" + (findings.size() + 1), rule.category(), rule.title(), GithubPrClient.clean(path), line.number(), line.side(),
                        line.text().length() > 1200 ? line.text().substring(0, 1200) + "…" : line.text(), rule.reason(), rule.check(), key.url() + "/blob/" + sha + "/" + encoded + "#L" + line.number()));
                }
            }
        }
        if (findingsClipped) gaps.add("只保留前 12 条变更提醒，其余命中未展示。");
        return new Analysis(List.copyOf(files), List.copyOf(findings), List.copyOf(gaps));
    }

    private Parsed parse(PatchFile file) {
        if (file.patch().length() > 60_000) return new Parsed(List.of(), false);
        var lines = new ArrayList<Line>(); int old = 0, added = 0, left = 0, right = 0, oldRemain = 0, newRemain = 0;
        boolean hunk = false, key = false; int rowCount = 0;
        try {
            for (String row : file.patch().replace("\r\n", "\n").split("\n")) {
                if (++rowCount > 6000) return new Parsed(List.of(), false);
                Matcher header = HUNK.matcher(row);
                if (header.matches()) {
                    if (hunk && (oldRemain != 0 || newRemain != 0)) return new Parsed(List.of(), false);
                    int oldStart = Integer.parseInt(header.group(1)), newStart = Integer.parseInt(header.group(3));
                    if (hunk && (oldStart < old || newStart < added)) return new Parsed(List.of(), false);
                    old = oldStart; added = newStart;
                    oldRemain = header.group(2) == null ? 1 : Integer.parseInt(header.group(2));
                    newRemain = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
                    if (old < 0 || added < 0 || oldRemain < 0 || newRemain < 0 || (long) old + oldRemain > Integer.MAX_VALUE || (long) added + newRemain > Integer.MAX_VALUE) return new Parsed(List.of(), false);
                    hunk = true; continue;
                }
                if (row.equals("\\ No newline at end of file")) continue;
                if (!hunk || row.isEmpty()) return new Parsed(List.of(), false);
                char sign = row.charAt(0); String raw = row.substring(1);
                if (raw.contains("-----BEGIN ") && raw.contains("PRIVATE KEY-----")) key = true;
                String clean = key ? "[REDACTED PRIVATE KEY]" : GithubPrClient.clean(raw);
                if (raw.contains("-----END ") && raw.contains("PRIVATE KEY-----")) key = false;
                if (sign == '+') { if (newRemain-- <= 0 || added < 1) return new Parsed(List.of(), false); lines.add(new Line(added++, "RIGHT", clean)); right++; }
                else if (sign == '-') { if (oldRemain-- <= 0 || old < 1) return new Parsed(List.of(), false); lines.add(new Line(old++, "LEFT", clean)); left++; }
                else if (sign == ' ') { if (oldRemain-- <= 0 || newRemain-- <= 0) return new Parsed(List.of(), false); old++; added++; }
                else return new Parsed(List.of(), false);
            }
        } catch (NumberFormatException error) { return new Parsed(List.of(), false); }
        return new Parsed(List.copyOf(lines), hunk && oldRemain == 0 && newRemain == 0 && left == file.deletions() && right == file.additions());
    }
}
