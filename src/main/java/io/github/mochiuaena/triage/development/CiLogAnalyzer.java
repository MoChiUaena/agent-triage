package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.CiModels.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

/** Local log classification. Log text never becomes instructions or an executable command. */
@Component
public class CiLogAnalyzer {
    private record Rule(String code, Pattern pattern, String title, String summary, List<String> checks) {}
    private static Rule rule(String code, String regex, String title, String summary, String... checks) {
        return new Rule(code, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), title, summary, List.of(checks));
    }
    private static final List<Rule> RULES = List.of(
        rule("DEPENDENCY_RESOLUTION", "could not (?:resolve dependencies|find artifact|transfer artifact)|failed to resolve.*dependenc|could not resolve all files",
            "依赖解析或下载失败", "错误片段表明构建未能取得需要的依赖。具体是坐标、仓库还是连接问题，还需结合片段核对。",
            "核对失败依赖的坐标、版本和仓库配置。", "检查 CI 到依赖仓库的访问、认证和缓存。"),
        rule("COMPILATION_FAILURE", "compilation error|cannot find symbol|error:.*(?:incompatible types|package .* does not exist)|\\.java:\\[\\d+,\\d+\\].*(?:error|cannot|not found|does not)",
            "编译阶段失败", "错误片段包含编译错误或无法解析的符号。日志位置可用于核对文件和类型，但尚未读取对应源码。",
            "查看日志提到的文件、行号和符号。", "使用相同提交、JDK 与依赖版本复现编译。"),
        rule("TEST_FAILURE", "tests run:.*(?:failures: [1-9]|errors: [1-9])|there are test failures|assertionfailederror|assertionerror|FAILED \\(.*(?:failures|errors)=",
            "测试阶段失败", "错误片段包含失败测试、测试执行错误或断言不一致。报告不把断言失败直接当作业务根因。",
            "定位失败测试及预期值和实际值。", "单独重跑对应测试，核对共享状态、依赖和环境差异。"),
        rule("PERMISSION_DENIED", "resource not accessible by integration|permission denied|authentication failed|403 forbidden|401 unauthorized",
            "权限或认证被拒绝", "失败步骤中出现明确的权限或认证拒绝。需要核对该步骤实际访问的资源。",
            "核对 workflow permissions 和 Token 的只读范围。", "检查 fork、事件类型及受保护环境的凭据条件。"),
        rule("NETWORK_FAILURE", "unknownhostexception|connectexception|failed to connect|connection refused|could not resolve host",
            "连接阶段失败", "错误片段包含域名解析或连接失败。日志不足以判断是远端服务还是 CI 主机的问题。",
            "检查日志中的目标服务和网络连通性。", "对照同一时段的服务状态，避免只靠增加重试掩盖失败。"),
        rule("SCRIPT_ERROR", "traceback \\(most recent call last\\)|processlookuperror|modulenotfounderror|filenotfounderror",
            "脚本执行异常", "失败步骤包含脚本异常。异常类型与调用位置应结合完整错误片段核对。",
            "检查异常类型、调用栈和脚本参数。", "复现相同步骤，核对进程、文件和依赖是否在预期时点存在。"),
        rule("TIMEOUT", "timeoutexception|operation timed out|execution timed out|job.*timed out",
            "执行等待超时", "错误片段记录了一次超时。现有日志尚不能判断耗时来自代码、服务、队列还是主机调度。",
            "确认超时发生在哪个调用或步骤。", "核对该步骤的时长、资源和外部服务响应。")
    );
    private static final Pattern ERROR = Pattern.compile("##\\[error\\]|\\[ERROR\\]|^ERROR:|Exception:|Process completed with exit code [1-9]", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAMP = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z)\\s+(.*)$");
    private static final Pattern TOKEN = Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}");
    private static final String CREDENTIAL_KEY = "(?:authorization|[A-Z0-9_-]{0,64}(?:TOKEN|SECRET|PASSWORD|API[-_]?KEY|PRIVATE[-_]?KEY)[A-Z0-9_-]{0,32})";
    private static final String QUOTED_VALUE = "(?:\"(?:\\\\.|[^\"\\\\])*(?:\"|$)|'(?:\\\\.|[^'\\\\])*(?:'|$))";
    private static final Pattern SECRET = Pattern.compile("(?i)(?<![A-Z0-9_-])(" + CREDENTIAL_KEY + "\\s*[:=]\\s*)(?:" + QUOTED_VALUE + "|(?:Bearer\\s+|Basic\\s+)?[^\\s,;]+)");
    private static final Pattern JSON_SECRET = Pattern.compile("(?i)([\"']" + CREDENTIAL_KEY + "[\"']\\s*:\\s*)(?:" + QUOTED_VALUE + "|[^\\s,}\\]]+)");
    private static final Pattern FILE = Pattern.compile("([\\p{L}\\p{N}_.$/\\\\ -]{1,240}\\.(?:java|kt|py|js|ts))(?::\\[(\\d+),\\d+\\]|:(\\d+)(?::\\d+)?|\", line (\\d+))");

    public Analysis analyze(String input, Job job) {
        String[] raw = input.replace("\r\n", "\n").split("\n", -1);
        var lines = new ArrayList<String>(); boolean privateKey = false, clipped = false;
        for (String row : raw) {
            if (row.matches(".*-----BEGIN .*PRIVATE KEY-----.*")) privateKey = true;
            String clean = privateKey ? "[REDACTED PRIVATE KEY]" : redact(row);
            if (row.matches(".*-----END .*PRIVATE KEY-----.*")) privateKey = false;
            if (clean.length() > 1200) { clean = clean.substring(0, 1200) + "…"; clipped = true; }
            lines.add(clean);
        }
        int offset = Math.max(0, lines.size() - 20_000);
        if (offset > 0) lines = new ArrayList<>(lines.subList(offset, lines.size()));
        Step failure = job.steps().stream().filter(step -> step.conclusion().equals("failure") || step.conclusion().equals("timed_out"))
            .min(Comparator.comparingInt(Step::number)).orElse(null);
        boolean timestamped = lines.stream().anyMatch(line -> STAMP.matcher(line).matches());
        boolean scoped = timestamped && failure != null && failure.startedAt() != null && failure.completedAt() != null;
        Instant upper = null; boolean sharedBoundary = false;
        if (scoped) {
            upper = failure.completedAt().getNano() == 0 ? failure.completedAt().plusSeconds(1) : failure.completedAt().plusNanos(1);
            for (Step next : job.steps()) if (next.number() > failure.number() && next.startedAt() != null) {
                if (next.startedAt().equals(failure.completedAt())) { upper = next.startedAt(); sharedBoundary = true; }
                else if (next.startedAt().isAfter(failure.completedAt()) && next.startedAt().isBefore(upper)) upper = next.startedAt();
            }
        }
        int selected = RULES.size(); var candidates = new ArrayList<Integer>(); boolean[] eligible = new boolean[lines.size()];
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index); Matcher stamp = STAMP.matcher(line);
            if (scoped) {
                if (!stamp.matches()) continue;
                Instant time;
                try { time = Instant.parse(stamp.group(1)); } catch (RuntimeException error) { continue; }
                if (time.isBefore(failure.startedAt()) || !time.isBefore(upper)) continue;
                line = stamp.group(2);
            }
            eligible[index] = true;
            boolean hit = ERROR.matcher(line).find();
            for (int rule = 0; rule < RULES.size(); rule++) if (RULES.get(rule).pattern().matcher(line).find()) {
                selected = Math.min(selected, rule); hit = true; break;
            }
            if (hit) candidates.add(index);
        }
        Rule decision = selected < RULES.size() ? RULES.get(selected) : null;
        var blocks = new ArrayList<Evidence>(); int lastEnd = -1;
        for (int index : candidates) {
            if (decision != null && !decision.pattern().matcher(lines.get(index)).find() && !ERROR.matcher(lines.get(index)).find()) continue;
            if (index <= lastEnd) continue;
            if (blocks.size() == 6) { clipped = true; break; }
            int start = Math.max(0, index - 2), end = Math.min(lines.size() - 1, index + 6);
            if (scoped) {
                while (start < index && !eligible[start]) start++;
                while (end > index && !eligible[end]) end--;
            }
            blocks.add(new Evidence("CI-LOG-" + (blocks.size() + 1), offset + start + 1, offset + end + 1,
                String.join("\n", lines.subList(start, end + 1)))); lastEnd = end;
        }
        var locations = new LinkedHashSet<Location>();
        for (var block : blocks) {
            Matcher match = FILE.matcher(block.text());
            while (match.find() && locations.size() < 10) {
                String number = match.group(2) != null ? match.group(2) : match.group(3) != null ? match.group(3) : match.group(4);
                try { int line = Integer.parseInt(number); if (line > 0 && line <= 1_000_000) locations.add(new Location(match.group(1).strip(), line)); }
                catch (NumberFormatException ignored) { }
            }
        }
        String scope = scoped ? "只扫描失败步骤“" + failure.name() + "”的时间段。" : "日志缺少可匹配的步骤时间，扫描了提供的日志内容。";
        if (sharedBoundary) scope += " 相邻步骤时间边界重叠，共享区段未用于归因。";
        if (offset > 0) scope += " 只保留最后 20,000 行，引用沿用原日志行号。";
        if (decision == null) return new Analysis("UNKNOWN", "INSUFFICIENT_EVIDENCE", "日志证据不足", blocks.isEmpty()
            ? "没有找到可引用的错误块。请提供失败步骤日志，或在 GitHub 核对作业状态。" : "找到了错误标记，但无法由现有片段确定失败类别。",
            List.of("查看 GitHub 的失败步骤和完整日志。", "补充首个错误附近的上下文，再生成报告。"), List.copyOf(blocks), List.copyOf(locations), scope, clipped || offset > 0);
        return new Analysis(decision.code(), "OBSERVED", decision.title(), decision.summary(), decision.checks(),
            List.copyOf(blocks), List.copyOf(locations), scope, clipped || offset > 0);
    }
    private static String redact(String text) {
        String clean = text.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "");
        clean = JSON_SECRET.matcher(clean).replaceAll("$1\"[REDACTED]\"");
        clean = TOKEN.matcher(clean).replaceAll("[REDACTED]");
        return SECRET.matcher(clean).replaceAll("$1[REDACTED]");
    }
}
