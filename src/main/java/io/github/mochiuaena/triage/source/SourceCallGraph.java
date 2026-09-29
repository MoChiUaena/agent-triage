package io.github.mochiuaena.triage.source;

import java.util.*;
import static io.github.mochiuaena.triage.source.SourceModels.*;

/** Bounded source relationships, never a claim about the runtime dispatch or executed path. */
final class SourceCallGraph {
    static final int MAX_NODES = 12, MAX_EDGES = 32, MAX_DEPTH = 4;
    @FunctionalInterface interface Reader { Excerpt read(String id, Integer focus); }
    private record Owner(TypeInfo type, String path) {}
    private record Match(String kind, String state, String message, List<Symbol> targets) {}
    private final Index index;
    private final Map<String,Symbol> symbols = new LinkedHashMap<>();
    private final Map<String,List<Owner>> types = new LinkedHashMap<>();
    private final Map<String,List<Symbol>> methods = new HashMap<>();
    private static final Set<String> JAVA_LANG = Set.of("String", "Object", "Boolean", "Integer", "Long", "Double", "Float", "Short", "Byte", "Character", "Number", "Void", "CharSequence", "Exception", "RuntimeException", "Throwable", "Class", "Math", "System", "Thread");
    private static final Set<String> HTTP_TYPES = Set.of("org.springframework.web.client.RestClient", "org.springframework.web.client.RestTemplate", "org.springframework.web.reactive.function.client.WebClient", "java.net.http.HttpClient");
    private static final Set<String> ACQUIRE_TYPES = Set.of("javax.sql.DataSource", "com.zaxxer.hikari.HikariDataSource", "java.sql.DriverManager");
    private static final Set<String> SQL_TYPES = Set.of("java.sql.Connection", "java.sql.Statement", "java.sql.PreparedStatement", "java.sql.CallableStatement", "org.springframework.jdbc.core.JdbcTemplate", "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate");

    SourceCallGraph(Index index) {
        this.index = index;
        for (var file : index.files()) {
            for (var symbol : file.symbols()) {
                symbols.put(symbol.id(), symbol);
                if (symbol.details() != null) methods.computeIfAbsent(symbol.className() + "#" + symbol.method(), ignored -> new ArrayList<>()).add(symbol);
            }
            if (file.types() != null) for (var type : file.types()) types.computeIfAbsent(type.name(), ignored -> new ArrayList<>()).add(new Owner(type, file.path()));
        }
    }

    CallGraph build(List<String> requested, Reader reader, Runnable checkpoint) {
        if (index.formatVersion() < 2) return new CallGraph("REINDEX_REQUIRED", "当前索引没有方法调用信息，请重新索引。", false, List.of(), List.of(), List.of(), List.of());
        var nodes = new LinkedHashMap<String,CallNode>(); var edges = new ArrayList<CallEdge>(); var roots = new ArrayList<String>();
        var queue = new ArrayDeque<Symbol>(); boolean truncated = requested.stream().distinct().count() > 3;
        for (String id : requested.stream().distinct().limit(3).toList()) {
            Symbol symbol = symbols.get(id);
            if (symbol == null || symbol.details() == null || symbol.method().isBlank()) continue;
            checkpoint.run(); nodes.put(id, new CallNode(reader.read(id, null), 0, symbol.signature())); roots.add(id); queue.add(symbol);
        }
        while (!queue.isEmpty()) {
            checkpoint.run(); Symbol from = queue.removeFirst(); int depth = nodes.get(from.id()).depth();
            truncated |= from.details().callsTruncated();
            if (depth >= MAX_DEPTH) { truncated |= !from.details().invocations().isEmpty(); continue; }
            for (var invocation : from.details().invocations()) {
                checkpoint.run();
                if (edges.size() >= MAX_EDGES) { truncated = true; break; }
                Match match = resolve(from, invocation); var targets = new ArrayList<String>();
                for (var target : match.targets().stream().limit(3).toList()) {
                    if (!nodes.containsKey(target.id())) {
                        if (nodes.size() >= MAX_NODES) { truncated = true; continue; }
                        nodes.put(target.id(), new CallNode(reader.read(target.id(), null), depth + 1, target.signature()));
                        queue.addLast(target);
                    }
                    targets.add(target.id());
                }
                boolean limited = targets.size() < match.targets().size(); truncated |= limited;
                String edgeId = "CALL-" + SourceFiles.hash(from.id() + ":" + invocation.line() + ":" + edges.size()).substring(0, 20);
                edges.add(new CallEdge(edgeId, from.id(), List.copyOf(targets), invocation.expression(), invocation.line(), match.kind(),
                    limited ? "LIMIT" : match.state(), limited ? "候选超过展开范围，部分方法未展示。" : match.message(), reader.read(from.id(), invocation.line())));
            }
        }
        return new CallGraph(roots.isEmpty() ? "NO_ENTRY" : "READY", roots.isEmpty() ? "未找到可展开的方法，请按接口路径或方法名检索。"
            : "根据本次索引匹配静态调用关系；接口注入、动态分派和实际执行路径仍需核实。", truncated,
            List.copyOf(roots), List.copyOf(nodes.values()), List.copyOf(edges), List.of());
    }
    EndpointMatch endpoint(io.github.mochiuaena.triage.domain.TriageModel.EndpointSummary summary) {
        var endpoint = summary.endpoint();
        if (index.formatVersion() < 2) return new EndpointMatch(endpoint, summary.requestCount(), summary.timeoutCount(), "REINDEX_REQUIRED", "当前索引缺少方法参数信息，请重新索引。", List.of());
        List<Symbol> named = methods.getOrDefault(endpoint.handlerClass() + "#" + endpoint.handlerMethod(), List.of()).stream()
            .filter(value -> value.details().parameters().size() == endpoint.parameterTypes().size()).toList();
        List<Symbol> exact = named.stream().filter(value -> {
            Owner owner = owner(value); if (owner == null) return false;
            for (int i = 0; i < value.details().parameters().size(); i++) {
                List<String> types = typeNames(value.details().parameters().get(i).type(), owner);
                if (types.size() != 1 || !types.getFirst().equals(endpoint.parameterTypes().get(i))) return false;
            }
            return true;
        }).toList();
        List<Symbol> uncertain = named.stream().filter(value -> {
            Owner owner = owner(value); if (owner == null) return false;
            for (int i = 0; i < value.details().parameters().size(); i++) {
                String parameter = value.details().parameters().get(i).type(); List<String> names = typeNames(parameter, owner);
                if (names.size() == 1 && names.getFirst().equals(endpoint.parameterTypes().get(i))) continue;
                String raw = raw(parameter).replace("[]", "");
                boolean declared = primitive(raw) || JAVA_LANG.contains(raw) || raw.contains(".") || names.stream().anyMatch(types::containsKey)
                    || owner.type().imports().stream().anyMatch(name -> !name.endsWith(".*") && name.endsWith("." + raw));
                if (declared) return false;
            }
            return true;
        }).toList();
        List<Symbol> selected = exact.isEmpty() ? uncertain : exact;
        String state = selected.isEmpty() ? "NO_MATCH" : selected.size() > 1 ? "AMBIGUOUS" : exact.isEmpty() ? "CANDIDATE" : "MATCHED";
        String message = switch (state) {
            case "MATCHED" -> "类名、方法和参数类型与服务提供的 MVC 匹配信息一致；仍需核对运行代码与本机源码版本。";
            case "CANDIDATE" -> "类名和方法匹配，参数类型尚未完整确定，仅作为候选。";
            case "AMBIGUOUS" -> "当前索引有多个同名方法或类型候选，未确认唯一源码入口。";
            default -> "当前项目索引没有找到对应处理方法，请检查绑定目录和源码版本。";
        };
        return new EndpointMatch(endpoint, summary.requestCount(), summary.timeoutCount(), state, message, selected.stream().map(Symbol::id).toList());
    }

    private Match resolve(Symbol caller, Invocation call) {
        if (call.deferred()) return match("DEFERRED", "UNRESOLVED", "回调、Lambda 或方法引用，执行时机与调用路径未确认。", List.of());
        Owner owner = owner(caller);
        if (owner == null) return match("PROJECT", "UNRESOLVED", "当前类型声明不唯一或缺少索引信息。", List.of());
        List<String> receivers = typeNames(call.receiverType(), owner);
        if (receivers.size() != 1) return match("PROJECT", receivers.isEmpty() ? "UNRESOLVED" : "AMBIGUOUS", "接收对象类型无法唯一确定。", List.of());
        String receiver = receivers.getFirst();
        String boundary = boundary(receiver, call);
        if (boundary != null) return match(boundary, "BOUNDARY", switch (boundary) {
            case "HTTP" -> "下游 HTTP 调用位置；下游实现不在本项目调用关系中。";
            case "DATABASE_ACQUIRE" -> "获取数据库连接的位置；不能据此确认连接池耗尽。";
            default -> "数据库操作位置；SQL 执行状态需结合运行观测。";
        }, List.of());
        if (!types.containsKey(receiver)) return match("LIBRARY", "EXTERNAL", "索引外或库方法，未继续展开。", List.of());
        if (call.chain().size() > 1) return match("PROJECT", "UNRESOLVED", "链式调用的中间返回类型尚未解析。", List.of());
        List<Symbol> eligible = matching(receiver, call, owner);
        if (eligible.isEmpty()) return match("PROJECT", "UNRESOLVED", "没有找到匹配的已索引方法、参数或静态声明。", List.of());
        if (eligible.size() > 1) return match("PROJECT", "AMBIGUOUS", "重载或类型声明存在多个候选，未选择其中一个。", eligible);
        Symbol declaration = eligible.getFirst();
        if (call.staticReceiver() || declaration.details().isStatic()) return match("PROJECT", "RESOLVED", "按源码声明和参数匹配到方法。", eligible);
        var possible = new LinkedHashMap<String,Symbol>();
        if (!declaration.details().isAbstract()) possible.put(declaration.id(), declaration);
        for (var entry : types.entrySet()) {
            if (entry.getKey().equals(receiver) || entry.getValue().size() != 1 || entry.getValue().getFirst().type().abstractType()) continue;
            if (!subtype(entry.getKey(), receiver, new HashSet<>(), 0)) continue;
            for (var implementation : matching(entry.getKey(), call, owner)) if (!implementation.details().isAbstract()) possible.put(implementation.id(), implementation);
        }
        if (possible.isEmpty()) return match("PROJECT", "UNRESOLVED", "只找到抽象声明，尚未找到可核查的实现方法。", eligible);
        if (possible.size() > 1) return match("PROJECT", "AMBIGUOUS", "存在多个实现或重写方法，运行时注入与分派待确认。", List.copyOf(possible.values()));
        Symbol target = possible.values().iterator().next();
        return match("PROJECT", target.id().equals(declaration.id()) ? "RESOLVED" : "CANDIDATE",
            target.id().equals(declaration.id()) ? "按源码声明匹配到方法，未确认本次执行。" : "唯一已索引实现，仅作为候选；运行时注入待确认。", List.of(target));
    }

    private List<Symbol> matching(String type, Invocation call, Owner caller) {
        var all = new LinkedHashMap<String,Symbol>(); declared(type, call.method(), new HashSet<>(), all, 0);
        var scored = new ArrayList<Map.Entry<Symbol,Integer>>();
        for (var method : all.values()) {
            if (call.staticReceiver() && !method.details().isStatic()) continue;
            int score = compatible(method, call, caller);
            if (score >= 0) scored.add(Map.entry(method, score));
        }
        if (scored.isEmpty()) return List.of();
        // Unknown expressions/null and varargs remain candidates when several overloads are possible.
        if (call.argumentTypes().stream().anyMatch(value -> value.equals("?") || value.equals("<null>")) || scored.stream().anyMatch(value -> value.getKey().details().varargs()))
            return scored.stream().map(Map.Entry::getKey).toList();
        int best = scored.stream().mapToInt(Map.Entry::getValue).min().orElseThrow();
        return scored.stream().filter(value -> value.getValue() == best).map(Map.Entry::getKey).toList();
    }
    private void declared(String type, String method, Set<String> visited, Map<String,Symbol> result, int depth) {
        if (depth > 12 || !visited.add(type)) return;
        methods.getOrDefault(type + "#" + method, List.of()).forEach(value -> result.put(value.id(), value));
        if (!result.isEmpty()) return; // Inherited overload sets are not reconstructed across hidden declarations.
        List<Owner> owners = types.getOrDefault(type, List.of());
        if (owners.size() == 1) for (String parent : owners.getFirst().type().parents())
            for (String candidate : typeNames(parent, owners.getFirst())) declared(candidate, method, visited, result, depth + 1);
    }
    private int compatible(Symbol target, Invocation call, Owner caller) {
        MethodInfo details = target.details(); int parameters = details.parameters().size(), arguments = call.argumentTypes().size();
        if (!details.varargs() && parameters != arguments || details.varargs() && arguments < parameters - 1) return -1;
        Owner declaring = owner(target); if (declaring == null) return -1;
        int total = details.varargs() ? 10 : 0;
        for (int i = 0; i < arguments; i++) {
            String expected = details.parameters().get(Math.min(i, parameters - 1)).type();
            if (details.varargs() && i >= parameters - 1 && expected.endsWith("[]")) expected = expected.substring(0, expected.length() - 2);
            List<String> to = typeNames(expected, declaring), from = typeNames(call.argumentTypes().get(i), caller);
            if (call.argumentTypes().get(i).equals("?")) { total += 8; continue; }
            if (call.argumentTypes().get(i).equals("<null>")) {
                if (primitive(raw(expected))) return -1;
                total += 8; continue;
            }
            if (to.size() != 1 || from.size() != 1) { total += 8; continue; }
            String actual = from.getFirst(), formal = to.getFirst();
            if (actual.equals(formal)) continue;
            int widening = widening(actual, formal);
            if (widening >= 0) { total += widening; continue; }
            if (formal.equals("java.lang.Object") && !primitive(actual)) { total += 5; continue; }
            if (subtype(actual, formal, new HashSet<>(), 0)) { total += 3; continue; }
            return -1;
        }
        return total;
    }
    private boolean subtype(String value, String parent, Set<String> visited, int depth) {
        if (value.equals(parent)) return true;
        if (depth > 12 || !visited.add(value)) return false;
        List<Owner> owners = types.getOrDefault(value, List.of());
        if (owners.size() != 1) return false;
        for (String declaration : owners.getFirst().type().parents()) for (String candidate : typeNames(declaration, owners.getFirst()))
            if (subtype(candidate, parent, visited, depth + 1)) return true;
        return false;
    }
    private Owner owner(Symbol symbol) {
        List<Owner> owners = types.getOrDefault(symbol.className(), List.of()).stream().filter(value -> value.path().equals(symbol.path())).toList();
        return owners.size() == 1 ? owners.getFirst() : null;
    }
    private List<String> typeNames(String input, Owner owner) {
        String name = raw(input);
        if (name.equals("?") || name.equals("var") || name.equals("<null>")) return List.of();
        if (primitive(name)) return List.of(name);
        if (name.endsWith("[]")) return typeNames(name.substring(0, name.length() - 2), owner).stream().map(value -> value + "[]").toList();
        if (types.containsKey(name) || name.contains(".") && Character.isLowerCase(name.charAt(0))) return List.of(name);
        String nested = owner.type().name() + "." + name;
        if (types.containsKey(nested)) return List.of(nested);
        var imported = owner.type().imports().stream().filter(value -> !value.endsWith(".*") && value.endsWith("." + name)).distinct().toList();
        if (!imported.isEmpty()) return imported;
        String local = owner.type().packageName().isEmpty() ? name : owner.type().packageName() + "." + name;
        if (types.containsKey(local)) return List.of(local);
        var candidates = new LinkedHashSet<String>();
        for (String value : owner.type().imports()) if (value.endsWith(".*")) {
            String candidate = value.substring(0, value.length() - 1) + name;
            if (types.containsKey(candidate) || HTTP_TYPES.contains(candidate) || ACQUIRE_TYPES.contains(candidate) || SQL_TYPES.contains(candidate)) candidates.add(candidate);
        }
        if (JAVA_LANG.contains(name)) candidates.add("java.lang." + name);
        if (candidates.isEmpty()) candidates.add(local); // Unknown dependencies stay outside the project graph.
        return List.copyOf(candidates);
    }
    private static String raw(String value) { return value == null ? "?" : value.equals("<null>") ? value : value.replaceAll("<.*>", "").strip(); }
    private static boolean primitive(String value) { return Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double", "void").contains(value); }
    private static int widening(String from, String to) {
        String path = switch (from) { case "byte" -> "short,int,long,float,double"; case "short", "char" -> "int,long,float,double"; case "int" -> "long,float,double"; case "long" -> "float,double"; case "float" -> "double"; default -> ""; };
        List<String> allowed = Arrays.asList(path.split(",")); int position = allowed.indexOf(to); return position < 0 ? -1 : position + 1;
    }
    private static String boundary(String receiver, Invocation call) {
        if (HTTP_TYPES.contains(receiver) && (call.chain().contains("retrieve") || call.chain().stream().anyMatch(value -> Set.of("exchange", "send", "sendAsync", "execute", "getForObject", "getForEntity", "postForObject", "postForEntity").contains(value)))) return "HTTP";
        if (ACQUIRE_TYPES.contains(receiver) && call.method().equals("getConnection")) return "DATABASE_ACQUIRE";
        if (SQL_TYPES.contains(receiver) && Set.of("query", "queryForObject", "queryForList", "update", "execute", "executeQuery", "executeUpdate", "executeBatch").contains(call.method())) return "DATABASE_QUERY";
        return null;
    }
    private static Match match(String kind, String state, String message, List<Symbol> targets) { return new Match(kind, state, message, targets); }
}
